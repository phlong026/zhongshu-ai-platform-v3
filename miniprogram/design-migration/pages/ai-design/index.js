const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const config = require('../../utils/config');
const sha256 = require('../../utils/sha256');
const designInputs = require('../../utils/design-inputs');
const generationOptions = require('../../utils/generation-options');
const attempts = require('../../utils/generation-attempt');
const classifications = require('../../utils/case-classifications');
const flatDefaults = generationOptions.selection('FLAT');
const elevDefaults = generationOptions.selection('ELEVATION');
const NOTE_SUGGESTIONS = ['老人房设在一楼', '客餐厅朝南', '厨房设在一楼', '保留露台', '增加储物空间'];
const REFERENCE_BASES = [
  { label: '尺寸口径待核实', code: 'UNKNOWN' },
  { label: '已确认是宅基地尺寸', code: 'SITE' },
  { label: '已确认是建筑外轮廓', code: 'BUILDING_OUTLINE' }
];

function referenceSession() {
  return http.captureSession ? http.captureSession() : (http.getToken ? http.getToken() : null);
}

function referenceView(candidate) {
  if (!candidate || !candidate.caseId || !candidate.caseVersionId || !Array.isArray(candidate.floorPlans)
      || !candidate.floorPlans.length || candidate.floorPlans.some(plan => !plan || !plan.assetId)) {
    throw { msg: '候选数据不完整，请重新查询' };
  }
  const labels = { UNKNOWN: '口径待核实', SITE: '宅基地', BUILDING_OUTLINE: '建筑外轮廓' };
  const size = candidate.faceWidth != null && candidate.depth != null
    ? candidate.faceWidth + ' × ' + candidate.depth + ' m' : '尺寸未记录';
  return Object.assign({}, candidate, {
    caseId: String(candidate.caseId), caseVersionId: String(candidate.caseVersionId),
    sizeText: size + ' · ' + (labels[candidate.dimensionBasis] || labels.UNKNOWN),
    floorPlans: candidate.floorPlans.map(plan => ({ assetId: String(plan.assetId),
      floorNo: plan.floorNo == null ? null : plan.floorNo,
      label: plan.floorNo == null ? '楼层未标注' : plan.floorNo + '层平面', url: '', status: 'idle' }))
  });
}

// 立面细部枚举沿用原立面设置页编码（入库为稳定编码，中文仅展示）
const ELEV_STYLES = [
  { label: '新中式', code: 'NEW_CHINESE' },
  { label: '现代', code: 'MODERN' },
  { label: '中式', code: 'CHINESE' },
  { label: '欧式', code: 'EUROPEAN' }
];
const ELEV_ROOFS = [
  { label: '坡屋顶', code: 'GABLE_ROOF' },
  { label: '平屋顶', code: 'FLAT_ROOF' }
];
const ELEV_WALLS = [
  { label: '米白真石漆', code: 'WHITE_STUCCO' },
  { label: '浅灰石材', code: 'GREY_STONE' },
  { label: '白色涂料', code: 'WHITE_COAT' }
];
const ELEV_ACCENTS = [
  { label: '深木色', code: 'DEEP_WOOD' },
  { label: '暖灰色', code: 'WARM_GREY' },
  { label: '黑色', code: 'BLACK' }
];

// 上传大小上限按资产类型，与后端 AssetTypePolicy 一致（草图 10MB，立面参考图 20MB）
const UPLOAD_MAX_BYTES = { USER_SKETCH: 10 * 1024 * 1024, DESIGN_REFERENCE: 20 * 1024 * 1024 };

function mimeFromPath(path) {
  const lower = (path || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  // 个别机型临时文件无后缀：按 jpeg 申报，真实类型由后端魔数校验兜底；
  // 明确带有其他后缀（gif/webp/heic…）的直接拒绝，不浪费一次上传
  const name = lower.split('/').pop();
  return /\.[a-z0-9]{1,5}$/.test(name) ? '' : 'image/jpeg';
}

function styleLabel(code) {
  const found = ELEV_STYLES.find(item => item.code === code);
  return found ? found.label : '';
}

function indexOfOption(options, code, fallback) {
  if (!code) return fallback;
  const index = options.findIndex(item => item.code === code);
  return index >= 0 ? index : fallback;
}

protectedPage({
  data: {
    line: 'FLAT', count: 2,
    // ---- 平面线路状态 ----
    faceWidth: '', depth: '',
    // 参考案例：refCaseAuto=true 表示系统按层数/面积自动匹配的默认案例（可被手动更换），false 表示用户手选
    refCase: null, refCaseText: '', refCaseAuto: false,
    referenceBases: REFERENCE_BASES, referenceBasisIndex: 0,
    caseCandidates: [], candidateLoading: false, candidateSearched: false,
    candidateNotice: '', candidateError: '', candidateMode: '',
    previewCandidate: null, previewPlanIndex: 0, candidatePreviewLoading: false, candidateConfirming: false,
    entranceLabels: designInputs.entranceLabels(),
    entranceIndex: 0,
    floorLabels: designInputs.floorLabels(),
    familyLabels: designInputs.familyLabels(),
    layoutLabels: designInputs.layoutLabels(),
    floorIndex: designInputs.indexOfFloor('两层'),
    familyIndex: designInputs.indexOfFamily('5室3厅2卫'),
    layoutIndex: 0,
    drawingTypes: designInputs.DRAWING_TYPES,
    drawingIndex: 0,
    note: '', sketchAssetId: null, sketchImage: '',
    resolution: flatDefaults.resolution, orientation: flatDefaults.orientation, outputPixels: flatDefaults.outputPixels,
    // ---- 立面线路状态 ----
    elevRefCase: null, elevRefs: [],
    elevStyles: [], styleError: '', elevStyleCode: 'NEW_CHINESE', elevStyleLabel: styleLabel('NEW_CHINESE'),
    floorChips: designInputs.FLOOR_OPTIONS, elevFloorIndex: 1,
    elevRoofs: ELEV_ROOFS, elevWalls: ELEV_WALLS, elevAccents: ELEV_ACCENTS,
    elevRoof: 'GABLE_ROOF', elevWall: 'WHITE_STUCCO', elevAccent: 'DEEP_WOOD',
    elevAdvancedOpen: false, elevAdvancedTouched: false, elevNote: '',
    elevResolution: elevDefaults.resolution, elevOrientation: elevDefaults.orientation, elevOutputPixels: elevDefaults.outputPixels,
    elevCount: 1,
    // ---- 共享 ----
    points: '—', creating: false, polishing: false, quoteReady: false, quoteLoading: false, quoteError: '', quoteByResolution: {}, quoteUnit: null,
    showNoteSuggestions: false, noteEditorOpen: false, draftNote: '',
    noteSuggestions: NOTE_SUGGESTIONS,
    resolutions: generationOptions.RESOLUTIONS, orientations: generationOptions.orientations(flatDefaults.resolution)
  },
  onLoad(options) {
    this._sourceCustomer = options.customerId || '';
    this._sourceScope = require('../../utils/budget-draft').sessionScope();
    this.loadStyles();
    if(options && ['FLAT','ELEVATION'].includes(options.line))getDesignApp().globalData.aiLine=options.line;
    try {
      const saved = attempts.read('initial-flat');
      if (saved && saved.payload && saved.payload.body) this.recoverAttempt(saved);
    } catch (error) { /* No authenticated persisted attempt. */ }
    try {
      const savedElev = attempts.read('initial-elevation');
      if (savedElev && savedElev.payload && savedElev.payload.body) { this.recoverAttempt(savedElev); }
    } catch (error) { /* No authenticated persisted attempt. */ }
    if (options && options.caseId) return; // 由 onShow 统一读全局参考案例
  },
  recoverAttempt(saved) {
    // Missing legacy provenance remains unknown; never borrow the current entry's customer.
    this._sourceCustomer = saved.sourceCustomerId || require('../../utils/customer-context').read('project', saved.projectId) || '';
    const body = saved.payload.body, inputs = body.requirementInputs || {};
    const imageOptions = saved.payload.imageOptions;
    const refCase = body.sourceType === 'CASE_REFERENCE' ? { caseId: body.refCaseId } : null;
    if (refCase && body.refCaseVersionId) {
      refCase.caseVersionId = String(body.refCaseVersionId);
      if (saved.payload.line !== 'ELEVATION') this._candidateFlowActive = true;
    }
    this._recoveringAttempt = true;
    this._lastAttemptKey = saved.key;
    const patch = { line: saved.payload.line || 'FLAT', pendingGeneration: true, creating: false };
    if (saved.payload.line === 'ELEVATION') {
      patch.elevCount = saved.payload.count;
      this._resumeElevationProjectId = saved.payload.resumeProjectId || null;
      patch.elevRefCase = refCase;
      patch.elevRefs = Array.isArray(inputs.referenceAssetIds)
        ? inputs.referenceAssetIds.map(function (id) { return { assetId: id, path: '' }; }) : [];
      patch.elevNote = inputs.note || '';
      const elevFloor = designInputs.FLOOR_OPTIONS.findIndex(o => o.count === inputs.floorCount);
      patch.elevFloorIndex = elevFloor >= 0 ? elevFloor : 1;   // 层数不在选项里时回落默认"两层"，不留 -1 让界面无选中项
      patch.elevStyleCode = inputs.styleCode || '';
      patch.elevStyleLabel = styleLabel(inputs.styleCode || '');
      patch.elevRoof = inputs.roofType || 'GABLE_ROOF';
      patch.elevWall = inputs.material || 'WHITE_STUCCO';
      patch.elevAccent = inputs.color || 'DEEP_WOOD';
      patch.elevAdvancedTouched = !!(inputs.roofType || inputs.material || inputs.color);
      patch.elevResolution = imageOptions.resolution; patch.elevOrientation = imageOptions.orientation;
      patch.elevOutputPixels = generationOptions.selection('ELEVATION', imageOptions).outputPixels;
    } else {
      // Restoring an already submitted request cancels any older candidate confirmation.
      this.closeReferencePreview();
      this._candidateSeq = (this._candidateSeq || 0) + 1;
      patch.candidateLoading = false;
      patch.count = saved.payload.count;
      patch.refCase = refCase;
      patch.refCaseAuto = false;   // 恢复的是已提交的请求，案例视同用户确认，不再被自动匹配覆盖
      patch.sketchAssetId = body.sketchAssetId || null;
      patch.faceWidth = inputs.faceWidthM == null ? '' : String(inputs.faceWidthM);
      patch.depth = inputs.depthM == null ? '' : String(inputs.depthM);
      patch.floorIndex = Math.max(0, designInputs.FLOOR_OPTIONS.findIndex(o => o.count === inputs.floorCount));
      patch.familyIndex = designInputs.indexOfFamily(inputs.family);
      patch.note = inputs.note || '';
      patch.entranceIndex = indexOfOption(designInputs.ENTRANCE_OPTIONS, inputs.entranceOrientation, 0);
      patch.drawingIndex = indexOfOption(designInputs.DRAWING_TYPES, inputs.drawingType, 0);
      patch.layoutIndex = indexOfOption(designInputs.LAYOUT_OPTIONS, inputs.courtyardLayout, 0);
      patch.resolution = imageOptions.resolution; patch.orientation = imageOptions.orientation;
      patch.outputPixels = generationOptions.selection('FLAT', imageOptions).outputPixels;
    }
    this.setData(patch);
  },
  onShow() {
    this._candidateClosed = false;
    // A different entry may have accepted and cleared the shared operation while this
    // tab was cached. A fresh appearance starts a new design rather than retaining a
    // completed operation marker forever.
    if (this._lastAttemptKey && !attempts.read('initial-flat') && !attempts.read('initial-elevation')) {
      this._lastAttemptKey = null;
      this._recoveringAttempt = false;
      this.setData({ creating: false, pendingGeneration: false });
    }
    // 恢复态/其他入口指定打开立面 Tab（如 project-resume 的 CREATE_ELEVATION_JOB）
    const app = getDesignApp();
    if (app.globalData.resumeElevationProjectId && !this._recoveringAttempt) {
      this._resumeElevationProjectId = app.globalData.resumeElevationProjectId;
      app.globalData.resumeElevationProjectId = null;
    }
    if (app.globalData.aiLine && !this._recoveringAttempt) {
      this.setData({ line: app.globalData.aiLine });
      app.globalData.aiLine = null;
    }
    const entry = app.globalData.caseDesignEntry;
    if (entry && !this._recoveringAttempt) {
      app.globalData.caseDesignEntry = null;
      const values = require('../../utils/case-entry').parameters(entry), patch = {};
      if(values.faceWidth)patch.faceWidth=values.faceWidth;
      if(values.depth)patch.depth=values.depth;
      const floor=designInputs.FLOOR_OPTIONS.findIndex(o=>o.count===values.floorCount);
      if(floor>=0){patch.floorIndex=floor;patch.elevFloorIndex=floor;}
      if(entry.styleCode){patch.elevStyleCode=entry.styleCode;patch.elevStyleLabel=styleLabel(entry.styleCode);}
      this.setData(patch);
    }
    const line = this.data.line;
    // 户型库选择的参考案例按当前线路归位：平面=参考案例行，立面=参考图卡
    const picked = this._recoveringAttempt ? null : app.globalData.refCase;
    if (picked && picked.caseId) {
      app.globalData.refCase = null;
      if (line === 'ELEVATION') this.setData({ elevRefCase: picked });
      else this.applyRefCase(picked);
    }
    this.refreshPoints();
    require('../../utils/generation-price').refresh(this, line === 'ELEVATION' ? 'ELEVATION' : 'FLAT');
  },
  loadStyles() {
    const session = http.captureSession(), seq = this._styleSeq = (this._styleSeq || 0) + 1;
    this.setData({ styleError: '' });
    return classifications.styles().then(styles => {
      if (seq !== this._styleSeq || !http.isSameSession(session)) return;
      const selected = styles.find(row => row.code === this.data.elevStyleCode);
      this.setData({ elevStyles: styles, elevStyleLabel: selected ? selected.label : this.data.elevStyleLabel });
    }).catch(() => {
      if (seq === this._styleSeq && http.isSameSession(session)) this.setData({ styleError: '风格加载失败，点击重试' });
    });
  },
  applyRefCase(refCase) {
    const patch = { refCase: refCase, refCaseAuto: false };
    const parts = [];
    if (refCase.buildingArea) parts.push(refCase.buildingArea + '㎡');
    if (refCase.floorCount) parts.push(refCase.floorCount + '层');
    patch.refCaseText = parts.join(' · ');
    if (refCase.caseVersionId) {
      // T26-07: choosing a versioned reference never rewrites dimensions, floor count, note or other inputs.
      this._candidateFlowActive = true;
      this._autoCaseSeq = (this._autoCaseSeq || 0) + 1;
      patch.refCaseText += (patch.refCaseText ? ' · ' : '') + '已确认参考版本';
    } else {
      // Compatibility for older unversioned entries; their original prefilling policy remains unchanged.
      patch.faceWidth = refCase.faceWidth != null && refCase.faceWidth !== '' ? String(refCase.faceWidth) : '';
      patch.depth = refCase.depth != null && refCase.depth !== '' ? String(refCase.depth) : '';
      const floorIndex = designInputs.FLOOR_OPTIONS.findIndex(o => o.count === Number(refCase.floorCount));
      if (floorIndex >= 0) patch.floorIndex = floorIndex;
    }
    this.setData(patch);
  },
  // ---- T26-07: explicit real-candidate lookup, preview and confirmation; all calls are read-only. ----
  referenceRequestCurrent(sequence, session, preview) {
    return !this._candidateClosed && !this.data.creating && !this.data.pendingGeneration
      && this.data.line === 'FLAT' && !!session
      && (preview ? sequence === this._candidatePreviewSeq : sequence === this._candidateSeq)
      && (http.isSameSession ? http.isSameSession(session) : session === referenceSession());
  },
  invalidateReferenceResults() {
    this._candidateSeq = (this._candidateSeq || 0) + 1;
    this._candidatePreviewSeq = (this._candidatePreviewSeq || 0) + 1;
    this.setData({ caseCandidates: [], previewCandidate: null, candidateLoading: false,
      candidatePreviewLoading: false, candidateConfirming: false, candidateSearched: false,
      candidateError: '', candidateNotice: this._candidateFlowActive ? '条件已更新，请重新查询参考案例。' : '' });
  },
  onReferenceBasisChange(e) {
    if (this.data.creating || this.data.pendingGeneration) return;
    this.setData({ referenceBasisIndex: Number(e.detail.value) });
    this.invalidateReferenceResults();
  },
  findReferenceCandidates() {
    if (this.data.creating || this.data.pendingGeneration || this.data.line !== 'FLAT') return Promise.resolve();
    const session = referenceSession();
    if (!session) return Promise.resolve();
    const width = String(this.data.faceWidth || '').trim(), depth = String(this.data.depth || '').trim();
    if ((width && !designInputs.isValidLength(width)) || (depth && !designInputs.isValidLength(depth))) {
      this.setData({ candidateError: '面宽、进深请填写 3~40 米，或留空仅查询参考案例。' });
      return Promise.resolve();
    }
    this._candidateFlowActive = true;
    this._autoCaseSeq = (this._autoCaseSeq || 0) + 1;
    this.invalidateReferenceResults();
    const sequence = this._candidateSeq;
    const basis = REFERENCE_BASES[this.data.referenceBasisIndex] || REFERENCE_BASES[0];
    const query = { floorCount: designInputs.FLOOR_OPTIONS[this.data.floorIndex].count,
      faceWidth: width || undefined, depth: depth || undefined, dimensionBasis: basis.code };
    this.setData({ candidateLoading: true, candidateNotice: '', candidateError: '' });
    return api.getGenerationCaseCandidates(query).then(result => {
      if (!this.referenceRequestCurrent(sequence, session, false)) return;
      if (!result || !Array.isArray(result.candidates)) throw { msg: '候选数据异常，请重试' };
      const seen = new Set();
      const candidates = result.candidates.slice(0, 5).map(referenceView).filter(candidate => {
        if (seen.has(candidate.caseId)) return false;
        seen.add(candidate.caseId); return true;
      });
      this.setData({ caseCandidates: candidates, candidateSearched: true, candidateMode: result.matchMode,
        candidateNotice: result.notice || '请核对图纸后选择参考。', candidateLoading: false });
    }).catch(error => {
      if (this.referenceRequestCurrent(sequence, session, false)) this.setData({ candidateLoading: false,
        candidateError: (error && (error.msg || error.message)) || '参考案例查询失败，请重试' });
    });
  },
  openReferenceCandidate(e) {
    if (this.data.creating || this.data.pendingGeneration) return Promise.resolve();
    const candidate = this.data.caseCandidates[Number(e.currentTarget.dataset.index)];
    const session = referenceSession();
    if (!candidate || !session) return Promise.resolve();
    const sequence = this._candidatePreviewSeq = (this._candidatePreviewSeq || 0) + 1;
    this.setData({ previewCandidate: null, previewPlanIndex: 0, candidatePreviewLoading: true, candidateError: '' });
    return api.getGenerationCaseCandidate(candidate.caseId, candidate.caseVersionId).then(result => {
      if (!this.referenceRequestCurrent(sequence, session, true)) return;
      const view = referenceView(result);
      if (view.caseId !== candidate.caseId || view.caseVersionId !== candidate.caseVersionId) throw { msg: '参考案例已更新，请重新查询' };
      this.setData({ previewCandidate: view, candidatePreviewLoading: false });
      return this.loadReferencePlan(0, sequence, session);
    }).catch(error => {
      if (this.referenceRequestCurrent(sequence, session, true)) this.setData({ previewCandidate: null,
        candidatePreviewLoading: false, candidateError: (error && (error.msg || error.message)) || '案例已更新或授权已失效，请重新查询' });
    });
  },
  loadReferencePlan(index, sequence, session) {
    const candidate = this.data.previewCandidate;
    if (!candidate || !candidate.floorPlans[index]) return Promise.resolve();
    const plan = candidate.floorPlans[index];
    if (plan.status === 'ready' || plan.status === 'loading') return Promise.resolve();
    const setPlan = patch => {
      if (!this.referenceRequestCurrent(sequence, session, true) || !this.data.previewCandidate
          || this.data.previewCandidate.caseVersionId !== candidate.caseVersionId) return;
      const current = this.data.previewCandidate;
      const plans = current.floorPlans.map((item, at) => at === index ? Object.assign({}, item, patch) : item);
      this.setData({ previewCandidate: Object.assign({}, current, { floorPlans: plans }) });
    };
    setPlan({ status: 'loading' });
    return require('../../utils/assets').fetchCaseAssetDataUrl(candidate.caseId,plan.assetId).then(url => {
      setPlan({ url: url, status: 'ready' });
    }).catch(() => { setPlan({ status: 'error', url: '' }); });
  },
  selectReferencePlan(e) {
    const index = Number(e.currentTarget.dataset.index);
    if (!this.data.previewCandidate || !this.data.previewCandidate.floorPlans[index]) return;
    this.setData({ previewPlanIndex: index });
    return this.loadReferencePlan(index, this._candidatePreviewSeq, referenceSession());
  },
  previewReferenceImage() {
    const candidate = this.data.previewCandidate;
    const plan = candidate && candidate.floorPlans[this.data.previewPlanIndex];
    if (plan && plan.url) require('../../utils/assets').previewImages([plan.url], plan.url);
  },
  referenceImageError() {
    const candidate = this.data.previewCandidate;
    if (!candidate) return;
    const plans = candidate.floorPlans.map((plan, index) => index === this.data.previewPlanIndex
      ? Object.assign({}, plan, { status: 'error', url: '' }) : plan);
    this.setData({ previewCandidate: Object.assign({}, candidate, { floorPlans: plans }) });
  },
  closeReferencePreview() {
    this._candidatePreviewSeq = (this._candidatePreviewSeq || 0) + 1;
    this.setData({ previewCandidate: null, candidatePreviewLoading: false, candidateConfirming: false });
  },
  confirmReferenceCandidate() {
    const candidate = this.data.previewCandidate, session = referenceSession();
    if (!candidate || !session || this.data.creating || this.data.pendingGeneration || this.data.candidateConfirming) return Promise.resolve();
    const sequence = this._candidatePreviewSeq;
    this.setData({ candidateConfirming: true, candidateError: '' });
    return api.getGenerationCaseCandidate(candidate.caseId, candidate.caseVersionId).then(result => {
      if (!this.referenceRequestCurrent(sequence, session, true)) return;
      const view = referenceView(result);
      if (view.caseId !== candidate.caseId || view.caseVersionId !== candidate.caseVersionId) throw { msg: '参考案例已更新，请重新查询' };
      this.applyRefCase(view);
      this.closeReferencePreview();
      wx.showToast({ title: '已选择参考，填写内容已保留', icon: 'none' });
    }).catch(error => {
      if (this.referenceRequestCurrent(sequence, session, true)) this.setData({ candidateConfirming: false,
        candidateError: (error && (error.msg || error.message)) || '案例已更新或授权已失效，请重新查询' });
    });
  },
  onHide() {
    this._candidateClosed = true;
    this._candidateSeq = (this._candidateSeq || 0) + 1;
    this._candidatePreviewSeq = (this._candidatePreviewSeq || 0) + 1;
    this._autoCaseSeq = (this._autoCaseSeq || 0) + 1;
    if (typeof clearTimeout === 'function') clearTimeout(this._autoCaseTimer);
    this.setData({ candidateLoading: false, candidatePreviewLoading: false, candidateConfirming: false, previewCandidate: null });
  },
  onUnload() { this._styleSeq = (this._styleSeq || 0) + 1; this.onHide(); },
  // ---- 默认参考案例（定稿 7.1-10）：层数一致优先、建筑面积接近；手选的案例永远优先 ----
  scheduleAutoCase() {
    if (this._candidateFlowActive) { this.invalidateReferenceResults(); return; }
    clearTimeout(this._autoCaseTimer);
    this._autoCaseTimer = setTimeout(() => this.autoMatchCase(), 500);
  },
  autoMatchCase() {
    const d = this.data;
    if (this._candidateFlowActive) return Promise.resolve();
    if (d.line !== 'FLAT' || (d.refCase && !d.refCaseAuto)) return Promise.resolve();
    const faceWidth = String(d.faceWidth == null ? '' : d.faceWidth).trim();
    const depth = String(d.depth == null ? '' : d.depth).trim();
    if (!designInputs.isValidLength(faceWidth) || !designInputs.isValidLength(depth)) return Promise.resolve();
    const floors = (designInputs.FLOOR_OPTIONS[d.floorIndex] || designInputs.FLOOR_OPTIONS[1]).count;
    // 目标面积 = 面宽 × 进深 × 层数，仅用于排序；口径由这里定，服务端只负责按它找最接近的
    const area = Math.round(Number(faceWidth) * Number(depth) * floors);
    const seq = this._autoCaseSeq = (this._autoCaseSeq || 0) + 1;
    return api.recommendCase(floors, area).then(found => {
      if (seq !== this._autoCaseSeq) return;
      const current = this.data;
      if (current.refCase && !current.refCaseAuto) return;   // 等待期间用户手选了案例
      if (!found || !found.caseId) {
        if (current.refCaseAuto) this.setData({ refCase: null, refCaseText: '', refCaseAuto: false });
        return;
      }
      const parts = [];
      if (found.buildingArea) parts.push(found.buildingArea + '㎡');
      if (found.floorCount) parts.push(found.floorCount + '层');
      // 不回填尺寸/层数：自动匹配只是默认参考，不得改写用户已输入的内容
      this.setData({ refCase: { caseId: found.caseId, title: found.title, floorCount: found.floorCount, buildingArea: found.buildingArea },
        refCaseText: parts.join(' · '), refCaseAuto: true });
    }).catch(() => { /* 推荐失败静默：按无参考案例继续，不打断填写 */ });
  },
  refreshPoints() {
    const self = this;
    api.getPointAccount().then(function (account) {
      self.setData({ points: account.availablePoints });
    }).catch(function () { /* 静默保留占位 */ });
  },
  selectLine(e) {
    const line = e.currentTarget.dataset.line;
    if (line === this.data.line || this.data.creating) return;
    // 张数与清晰度按线路各存一份（count/resolution 属平面，elevCount/elevResolution 属立面），切换只换显示
    this.setData({ line: line });
    this.closeReferencePreview();
    this._candidateSeq = (this._candidateSeq || 0) + 1;
    this.setData({ candidateLoading: false });
    require('../../utils/generation-price').refresh(this, line === 'ELEVATION' ? 'ELEVATION' : 'FLAT');
  },
  selectCount(e) {
    if (this.data.creating) return;
    const count = Number(e.currentTarget.dataset.count);
    this.setData({ count: count });
    require('../../utils/generation-price').refresh(this, 'FLAT');
  },
  selectResolution(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('FLAT', { resolution: e.currentTarget.dataset.value, orientation: this.data.orientation });
    this.setData({ resolution: selected.resolution, orientation: selected.orientation, outputPixels: selected.outputPixels,
      orientations: generationOptions.orientations(selected.resolution) });
    require('../../utils/generation-price').refresh(this, 'FLAT');
  },
  selectOrientation(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('FLAT', { resolution: this.data.resolution, orientation: e.currentTarget.dataset.value });
    this.setData({ orientation: selected.orientation, outputPixels: selected.outputPixels });
  },
  // ---- 立面出图设置 ----
  selectElevCount(e) {
    if (this.data.creating) return;
    const count = Number(e.currentTarget.dataset.count);
    this.setData({ elevCount: count });
    require('../../utils/generation-price').refresh(this, 'ELEVATION');
  },
  selectElevResolution(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('ELEVATION', { resolution: e.currentTarget.dataset.value, orientation: this.data.elevOrientation });
    this.setData({ elevResolution: selected.resolution, elevOrientation: selected.orientation, elevOutputPixels: selected.outputPixels });
    require('../../utils/generation-price').refresh(this, 'ELEVATION');
  },
  selectElevOrientation(e) {
    if (this.data.creating) return;
    const selected = generationOptions.selection('ELEVATION', { resolution: this.data.elevResolution, orientation: e.currentTarget.dataset.value });
    this.setData({ elevOrientation: selected.orientation, elevOutputPixels: selected.outputPixels });
  },
  retryQuote() { require('../../utils/generation-price').refresh(this, this.data.line === 'ELEVATION' ? 'ELEVATION' : 'FLAT'); },
  chooseReference() { navigation.switchTab({ url: '/design-migration/pages/library/index' }); },
  onNoteInput(e) { this.setData({ note: e.detail.value }); },
  onElevNoteInput(e) { this.setData({ elevNote: e.detail.value }); },
  // ---- 补充需求大窗编辑：右下角「展开」进入半屏编辑（按当前线路读写） ----
  openNoteEditor() {
    this.setData({ noteEditorOpen: true, draftNote: this.data.line === 'ELEVATION' ? this.data.elevNote : this.data.note });
  },
  // 补充需求 AI 优化：保留原意，按线路语境改写；成功后替换当前线路的补充需求内容
  polishNote() {
    if (this.data.polishing) return;
    const isElev = this.data.line === 'ELEVATION';
    const note = (isElev ? this.data.elevNote : this.data.note).trim();
    if (!note) { wx.showToast({ title: '请先输入补充需求', icon: 'none' }); return; }
    this.setData({ polishing: true });
    api.notePolish(note, this.data.line).then(result => {
      const polished = result && result.polished ? String(result.polished).slice(0, 200) : '';
      if (!polished) throw { msg: 'AI 优化失败，请稍后再试' };
      this.setData(isElev ? { elevNote: polished } : { note: polished });
      wx.showToast({ title: '已优化', icon: 'success' });
    }).catch(err => {
      wx.showToast({ title: (err && err.msg) || 'AI 优化失败，请稍后再试', icon: 'none' });
    }).then(() => this.setData({ polishing: false }));
  },
  closeNoteEditor() { this.setData({ noteEditorOpen: false }); },
  applyNoteEditor() {
    const value = (this.data.draftNote || '').slice(0, 200);
    this.setData(this.data.line === 'ELEVATION' ? { elevNote: value, noteEditorOpen: false } : { note: value, noteEditorOpen: false });
  },
  onDraftNoteInput(e) { this.setData({ draftNote: e.detail.value }); },
  noop() { /* 阻止点击冒泡关闭弹层 */ },
  onFaceWidthInput(e) { this.setData({ faceWidth: e.detail.value }); this.scheduleAutoCase(); },
  onDepthInput(e) { this.setData({ depth: e.detail.value }); this.scheduleAutoCase(); },
  onEntranceChange(e) { this.setData({ entranceIndex: Number(e.detail.value) }); },
  onFloorChange(e) { this.setData({ floorIndex: Number(e.detail.value) }); this.scheduleAutoCase(); },
  onFamilyChange(e) { this.setData({ familyIndex: Number(e.detail.value) }); },
  onLayoutChange(e) { this.setData({ layoutIndex: Number(e.detail.value) }); },
  onDrawingChange(e) { this.setData({ drawingIndex: Number(e.currentTarget.dataset.index) }); },
  // ---- 立面细部选择 ----
  onElevStyle(e) {
    const code = e.currentTarget.dataset.code || '';
    const style = this.data.elevStyles.find(row => row.code === code);
    if (code && !style) return;
    this.setData({ elevStyleCode: code, elevStyleLabel: style ? style.label : '' });
  },
  onElevFloor(e) { this.setData({ elevFloorIndex: Number(e.currentTarget.dataset.index) }); },
  onElevRoof(e) { this.setData({ elevRoof: e.currentTarget.dataset.code, elevAdvancedTouched: true }); },
  onElevWall(e) { this.setData({ elevWall: e.currentTarget.dataset.code, elevAdvancedTouched: true }); },
  onElevAccent(e) { this.setData({ elevAccent: e.currentTarget.dataset.code, elevAdvancedTouched: true }); },
  toggleElevAdvanced() { this.setData({ elevAdvancedOpen: !this.data.elevAdvancedOpen }); },
  clearElevRef(e) {
    const index = Number(e.currentTarget.dataset.index);
    this.setData({ elevRefs: this.data.elevRefs.filter((r, i) => i !== index) });
  },
  clearElevRefCase() { this.setData({ elevRefCase: null }); },

  // ---- 图片上传（平面附草图 USER_SKETCH / 立面参考图 DESIGN_REFERENCE）----
  chooseImage() { this.pickAndUpload('USER_SKETCH', function (self, assetId, path) { self.setData({ sketchAssetId: assetId, sketchImage: path }); }); },
  chooseElevImage() {
    if (this.data.elevRefs.length >= 3) { wx.showToast({ title: '参考图最多 3 张', icon: 'none' }); return; }
    this.pickAndUpload('DESIGN_REFERENCE', function (self, assetId, path) {
      self.setData({ elevRefs: self.data.elevRefs.concat([{ assetId: assetId, path: path }]) });
    });
  },
  pickAndUpload(assetType, apply) {
    const self = this;
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sizeType: ['compressed'],
      success: function (res) {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file) return;
        self.uploadImage(assetType, file, apply);
      }
    });
  },
  uploadImage(assetType, file, apply) {
    const self = this;
    const mime = mimeFromPath(file.tempFilePath);
    if (!mime) { wx.showToast({ title: '仅支持 JPG/PNG 图片', icon: 'none' }); return; }
    const maxBytes = UPLOAD_MAX_BYTES[assetType];
    if (file.size > maxBytes) { wx.showToast({ title: '图片不能超过' + maxBytes / 1024 / 1024 + 'MB', icon: 'none' }); return; }
    wx.showLoading({ title: '上传中', mask: true });
    wx.getFileSystemManager().readFile({
      filePath: file.tempFilePath,
      success: function (res) {
        api.getUploadTicket(assetType, mime, file.size, sha256.sha256Hex(res.data))
          .then(function (ticket) {
            return self.putObject(ticket, file.tempFilePath, res.data, mime).then(function () {
              return api.completeUpload(ticket.assetId);
            }).then(function (accepted) {
              if (!accepted) throw { msg: '图片校验未通过，请更换图片' };
              return ticket;
            });
          })
          .then(function (ticket) {
            wx.hideLoading();
            apply(self, ticket.assetId, file.tempFilePath);
          })
          .catch(function (err) {
            wx.hideLoading();
            wx.showToast({ title: (err && err.msg) || '上传失败，请重试', icon: 'none' });
          });
      },
      fail: function () {
        wx.hideLoading();
        wx.showToast({ title: '读取图片失败', icon: 'none' });
      }
    });
  },
  // COS 直传分流（与 utils/avatar-upload.js 一致）：
  //  · https:// 预签名地址 → wx.request PUT 发二进制体；签名对象 PUT 绝不能带应用 Bearer（会破坏签名/越权），
  //    Content-Type 必须与申请票据时申报的 mime 一致，否则与后端签名不匹配被拒。
  //  · local:// 开发期地址 → wx.uploadFile multipart POST 到字节端点，带 Bearer + tenant-id（Content-Type 由 wx 自动置 multipart 边界）。
  putObject(ticket, filePath, data, mime) { return require('../../utils/put-object')(ticket, data, mime); },

  generate() {
    if (this.data.creating || !this.data.quoteReady
        || (this.data.line === 'FLAT' && this.data.candidateConfirming)) return;
    const slot = this.data.line === 'ELEVATION' ? 'initial-elevation' : 'initial-flat';
    if (this._lastAttemptKey && !attempts.read(slot)) {
      this.setData({ pendingGeneration: false });
      wx.showToast({ title: '上次任务已由其他入口受理，请到我的方案查看', icon: 'none' });
      return;
    }
    if (this.data.line === 'ELEVATION') return this.generateElevation(slot);
    return this.generateFlat(slot);
  },

  // ---- 平面线路：宅基地信息 → 平面布置图 ----
  generateFlat(slot) {
    if (this.data.creating || this.data.candidateConfirming) return;
    const count = this.data.count;
    const imageOptions = generationOptions.selection('FLAT', this.data);
    // A new or recovered attempt owns the selected version from this point on.
    // Invalidate preview/search callbacks before the first asynchronous submission step.
    this.closeReferencePreview();
    this._candidateSeq = (this._candidateSeq || 0) + 1;
    this.setData({ candidateLoading: false });
    this.setData({ creating: true, creatingText: '正在确认报价…' });
    const refCase = this.data.refCase;
    // 全量上送需求输入（尺寸/朝向/层数/家庭需求/院落布局/图纸类型/补充需求），后端冻结进需求快照供 AI 提示词使用
    const built = designInputs.buildRequirementInputs(0, this.data);
    if (built.error) {
      wx.showToast({ title: built.error, icon: 'none' });
      this.setData({ creating: false });
      return;
    }
    const body = refCase && refCase.caseId
      ? { sourceType: 'CASE_REFERENCE', refCaseId: String(refCase.caseId), line: 'FLAT', sketchAssetId: this.data.sketchAssetId || undefined, requirementInputs: built.inputs }
      : { sourceType: 'SELF_UPLOAD', line: 'FLAT', sketchAssetId: this.data.sketchAssetId || undefined, requirementInputs: built.inputs };
    if (refCase && refCase.caseVersionId) body.refCaseVersionId = String(refCase.caseVersionId);
    return this.submitAttempt({
      slot: slot, stage: 'FLAT', count: count, imageOptions: imageOptions, body: body,
      createJob: (projectId, price, attempt) => api.createFlatJob(projectId, count, attempt.key, price,
        { resolution: imageOptions.resolution, orientation: imageOptions.orientation }),
      afterAccepted: () => { getDesignApp().globalData.refCase = null; },
      redirectUrl: '/design-migration/pages/ai-design/generating?stage=plane&count=' + count
    });
  },

  // ---- 立面线路：独立建项目（line=ELEVATION），参考图可选，零参考纯文生图 ----
  generateElevation(slot) {
    const count = this.data.elevCount;
    const imageOptions = generationOptions.selection('ELEVATION', { resolution: this.data.elevResolution, orientation: this.data.elevOrientation });
    this.setData({ creating: true, creatingText: '正在确认报价…' });
    const built = designInputs.buildRequirementInputs(1, {
      floorIndex: this.data.elevFloorIndex, note: this.data.elevNote,
      referenceAssetIds: this.data.elevRefs.map(r => r.assetId),
      styleCode: this.data.elevStyleCode,
      roofType: this.data.elevAdvancedTouched ? this.data.elevRoof : '',
      material: this.data.elevAdvancedTouched ? this.data.elevWall : '',
      color: this.data.elevAdvancedTouched ? this.data.elevAccent : ''
    });
    if (built.error) {
      wx.showToast({ title: built.error, icon: 'none' });
      this.setData({ creating: false });
      return;
    }
    // 参考图两条通道可同时生效：选了户型库案例就按 CASE_REFERENCE 建项目（后端带入案例授权立面图），
    // 自传的 DESIGN_REFERENCE（≤3张）随需求上送、与案例图合并；都不选则零参考文生图
    const refCase = this.data.elevRefCase;
    const body = refCase && refCase.caseId
      ? { sourceType: 'CASE_REFERENCE', refCaseId: String(refCase.caseId), line: 'ELEVATION', requirementInputs: built.inputs }
      : { sourceType: 'SELF_UPLOAD', line: 'ELEVATION', requirementInputs: built.inputs };
    const elevConfig = {
      count: count,
      styleCode: this.data.elevStyleCode || undefined,
      roofType: this.data.elevAdvancedTouched ? this.data.elevRoof : undefined,
      material: this.data.elevAdvancedTouched ? this.data.elevWall : undefined,
      color: this.data.elevAdvancedTouched ? this.data.elevAccent : undefined
    };
    return this.submitAttempt({
      slot: slot, stage: 'ELEVATION', count: count, imageOptions: imageOptions, body: body, payloadExtra: { config: elevConfig, ...(this._resumeElevationProjectId ? { resumeProjectId: this._resumeElevationProjectId } : {}) },
      createJob: (projectId, price, attempt) => {
        const config = Object.assign({}, elevConfig, {
          resolution: imageOptions.resolution, orientation: imageOptions.orientation, priceConfirmation: price,
          requirementInputs: Object.assign({}, built.inputs, { referenceAssetIds: built.inputs.referenceAssetIds || [] }),
          refCaseId: body.refCaseId || null
        });
        getDesignApp().globalData.elevationConfig = Object.assign({}, config);
        return api.createElevationJob(projectId, config, attempt.key);
      },
      redirectUrl: '/design-migration/pages/ai-design/generating?stage=elevation&count=' + count
    });
  },

  // ---- 两条线路共用：保存待提交请求 → 确认价格 → 建项目 → 建任务 → 跳转生成页 ----
  // 每一步都先落盘 attempt（幂等键 + 已确认价格 + 已建项目），中途杀进程/网络抖动后可原样续传，不会重复扣点。
  // spec：{ slot, stage, count, imageOptions, body, payloadExtra?, createJob(projectId, price, attempt), afterAccepted?, redirectUrl }
  submitAttempt(spec) {
    const self = this, slot = spec.slot, body = spec.body, imageOptions = spec.imageOptions;
    let attempt;
    try {
      attempt = attempts.begin(slot, Object.assign({ body: body, line: spec.stage, count: spec.count,
        imageOptions: { resolution: imageOptions.resolution, orientation: imageOptions.orientation } }, spec.payloadExtra), self._sourceScope === require('../../utils/budget-draft').sessionScope() ? self._sourceCustomer : '');
    } catch (error) { self.setData({ creating: false }); wx.showToast({ title: error.msg || '无法保存生成请求', icon: 'none' }); return; }
    this._lastAttemptKey = attempt.key;
    const openAccepted = () => {
      attempts.assertCurrent(attempt);
      if (self._candidateClosed) { self.setData({creating:false,pendingGeneration:true}); return Promise.resolve(); }
      self.setData({ creating: true, creatingText: '任务已创建，正在打开进度…' });
      const url = spec.redirectUrl + '&projectId=' + encodeURIComponent(attempt.projectId) + '&jobId=' + encodeURIComponent(attempt.jobId);
      return new Promise(resolve => wx.redirectTo({ url,
        success: () => { try { attempts.complete(slot, attempt); self._lastAttemptKey = null; } catch(e) { /* Session changed. */ } resolve(); },
        fail: () => { try { attempts.assertCurrent(attempt); self.setData({ creating: false, pendingGeneration: true }); if(!self._candidateClosed)wx.showToast({title:'任务已创建，点击继续查看进度',icon:'none'}); } catch(e) { /* Session changed. */ } resolve(); }
      }));
    };
    if (attempt.jobId && attempt.projectId) return openAccepted();
    let confirmedPrice;
    const priceRequest = attempt.confirmedPrice ? Promise.resolve(attempt.confirmedPrice)
      : require('../../utils/generation-price').confirm(spec.stage, spec.count, '', { resolution: imageOptions.resolution });
    return priceRequest.then(function (price) {
      attempts.assertCurrent(attempt);
      self.setData({ creatingText: '正在创建设计项目…' });
      confirmedPrice = price;
      attempt.confirmedPrice = price; attempts.save(slot, attempt);
      if (attempt.projectId) return { projectId: attempt.projectId };
      if (attempt.payload.resumeProjectId) return { projectId: attempt.payload.resumeProjectId };
      attempts.submitted(slot, attempt);
      return api.createProject(body, attempt.key);
    }).then(function (project) {
      attempts.assertCurrent(attempt);
      const projectId = project && project.projectId;
      if (!projectId) throw { msg: '创建设计项目失败' };
      attempt.projectId = projectId; attempts.save(slot, attempt);
      require('../../utils/customer-context').bind('project', projectId, attempt.sourceCustomerId);
      getDesignApp().globalData.projectId = projectId;
      getDesignApp().globalData.resultVersionId = null;
      attempts.submitted(slot, attempt);
      self.setData({ creatingText: '正在提交生成任务…' });
      return spec.createJob(projectId, confirmedPrice, attempt).then(function (job) {
        attempts.assertCurrent(attempt);
        if (!job || !job.jobId) throw {msg:'任务编号未确认，请重试读取同一任务'};
        attempt.jobId = job.jobId; attempts.save(slot, attempt);
        self._recoveringAttempt = false; self.setData({ pendingGeneration: false });
        if (spec.stage === 'ELEVATION') self._resumeElevationProjectId = null;
        if (spec.afterAccepted) spec.afterAccepted();
        getDesignApp().globalData.jobId = job.jobId;
        return openAccepted();
      });
    }).catch(function (err) {
      try {
        const referenceRejected = spec.stage === 'FLAT' && body.refCaseVersionId && !attempt.projectId
          && attempt.state === 'SUBMITTED' && err && [1099000001, 1071000003].includes(Number(err.code));
        if (referenceRejected) {
          // Explicit first-create rejection means the transaction created no project/job. A transport failure
          // never enters this branch; generic account/resource denials also retain the original version/key.
          attempts.assertCurrent(attempt); attempts.complete(slot, attempt);
          self._lastAttemptKey = null; self._recoveringAttempt = false;
          self.invalidateReferenceResults();
          self.setData({ refCase: null, refCaseText: '', refCaseAuto: false,
            candidateError: '参考案例已更新或授权已失效，请重新查询。已填写的内容已保留。' });
        } else if (attempts.failed(slot, attempt, err)) self._lastAttemptKey = null;
      } catch (stale) { /* Do not modify another session. */ }
      if (!err || !err.cancelled) wx.showToast({ title: (err && err.msg) || '创建任务失败', icon: 'none' });
      let pending = false; try { pending = !!attempts.read(slot); } catch (unavailable) { /* Logged out. */ }
      self.setData({ creating: false, pendingGeneration: pending });
    });
  }
});
