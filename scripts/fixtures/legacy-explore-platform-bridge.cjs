// Historical integration-test fixture only. The live explore route redirects to design-migration.
/**
 * explore 页平台（API）态桥接层（阶段 1 · P3）：灵感库/AI 平面/AI 立面/快速预算/回写。
 * 注入式 attach(page, deps)（finance-desk platform-actions 先例）；契约=docs/backend-parity/13 §三/§四/§五。
 * demo 隔离红线：本层任何路径不读写 demo Storage（designExplorePrefix 表单白名单除外——S 层由页面 guard 放行后按原链写入）。
 */
const POLL_INTERVAL = 3000, POLL_MAX = 90000;

module.exports = function attach(page, deps) {
  const PF = deps.PF, http = deps.http, api = deps.api, S = deps.S;
  const TITLES = { inspiration: '灵感库', search: '平面检索', case: '灵感详情', 'ai-config': 'AI平面', 'ai-facade': 'AI立面', budget: '快速预算', 'plan-result': '平面方案', 'facade-result': '立面方案', 'budget-result': '预算结果' };
  // 表单读写在页面 state（API 态不写 S——S 身份锚定 demo personId，API 用户无该身份）
  page.platFormInput = function (e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ ['form.' + key]: e.detail.value, error: '' });
    if (key === 'regionCode' || key === 'structureType' || key === 'materialGrade') this.platLoadRule();
  };
  page.platFormPick = function (e) {
    const { key, list } = e.currentTarget.dataset;
    const values = list ? this.data[list] : null;
    if (!values) { this.setData({ ['form.' + key]: e.detail.value, error: '' }); return; }
    const sel = values[Number(e.detail.value)];
    const val = Array.isArray(sel) ? sel[0] : (sel && typeof sel === 'object' && sel.code !== undefined ? sel.code : sel);
    this.setData({ ['form.' + key]: val, error: '' });
  };
  page.platUseCase = function (e) { // 灵感库→立面参考（facade 保留；plan D20 隐藏入口）
    const c = this.data.platCase;
    if (this.data.platCaseLoading || !this.data.platCaseVerified || !c || !c.id) return;
    if (!c.canDesign) return this.platFail(c.designUnavailableReason);
    const kind = this.data.pickingRef || 'facade';
    this.setData({ 'form.referenceCaseId': c.id, pickingRef: '' });
    this.platShow(kind === 'plan' ? 'ai-config' : 'ai-facade');
  };
  page.platFormPickPicker = function (e) {
    const { key, list } = e.currentTarget.dataset;
    const values = list ? this.data[list] : null;
    if (!values) return;
    const sel = values[Number(e.detail.value)];
    if (!sel) return;
    const labelKey = key + 'Label';
    this.setData({ ['form.' + key]: sel.code || '', [labelKey]: sel.label || '', error: '' });
  };
  page.platShow = function (view) {
    const kind = view.startsWith('facade') || view === 'ai-facade' ? 'facade' : view.startsWith('budget') ? 'budget' : 'plan';
    const titles = { 'ai-config': 'AI平面', 'ai-facade': 'AI立面', budget: '快速预算', 'plan-result': '平面方案', 'facade-result': '立面方案', 'budget-result': '预算结果', inspiration: '灵感库', search: '平面检索', case: '灵感详情', projects: '我的方案', project: '方案详情' };
    this.setData({ view, title: titles[view] || view, kind });
    if (view === 'inspiration' || view === 'search') { if (!(this.data.platCases || []).length) this.platLoadCases(true); }
    if (view === 'budget') this.platLoadRule();
    if (view === 'projects') this.platLoadProjects(true);
  };

  // ---- 我的方案（对齐旧版 1.8：列表+resumeAction 恢复；D1 缺口）----
  page.platLoadProjects = function (reset) {
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    const params = { limit: 20 };
    if (!reset && this.data.platProjCursor) params.cursor = this.data.platProjCursor;
    if (reset) this.setData({ platProjCursor: '', platProjects: [], platProjLoading: true });
    else this.setData({ platProjLoading: true });
    api.projects.list(params).then(res => {
      if (this.platStale(gen, sg)) return;
      const rows = (res && res.list || []).map(PF.projectRow);
      this.setData({ platProjects: reset ? rows : (this.data.platProjects || []).concat(rows),
        platProjCursor: (res && res.nextCursor) || '', platProjLoading: false });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platProjLoading: false, platformErr: PF.errorMessageDesign(err) });
    });
  };
  page.platLoadMoreProjects = function () { if (this.data.platProjCursor) this.platLoadProjects(false); };
  page.platOpenProject = function (e) {
    const id = e.currentTarget.dataset.id;
    const p2 = (this.data.platProjects || []).find(x => String(x.projectId) === String(id));
    if (!p2) return;
    this.setData({ platProjectDetail: p2, platProjectCandidates: [], platProjectImages: [] });
    this.platShow('project');
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    // 带 jobId 恢复（最近任务触发拉式晋升）——列表行的 latestJobId 由 projectRow 预留
    api.projects.detail(id, p2.latestJobId).then(detail => {
      if (this.platStale(gen, sg)) return;
      const resume = detail.resumeAction || '';
      this.__platProjectId = String(id);
      this.__platJobId = String(detail.latestJobId || p2.latestJobId || '');
      this.__platKind = detail.line === 'ELEVATION' || resume === 'SELECT_ELEVATION' ? 'facade' : 'plan';
      const candidates = PF.candidateRows(detail.candidates);
      this.setData({ platProjectDetail: Object.assign({}, p2, { resumeAction: resume,
        status: detail.latestJobStatus || p2.jobStatus, requirements: detail.requirements || null }) });
      if (['SELECT_FLAT', 'SELECT_ELEVATION'].includes(resume) && candidates.length) {
        return this.platFetchImages(candidates.map(c => c.assetId)).then(urls => {
          if (this.platStale(gen, sg)) return;
          candidates.forEach((c, i) => { c.imageUrl = urls[i] || ''; });
          const kind = resume === 'SELECT_FLAT' ? 'plan' : 'facade';
          this.__platProjectId = String(id); this.__platKind = kind;
          if (detail.latestJobId) this.__platJobId = String(detail.latestJobId);
          this.setData({ platProjectCandidates: candidates,
            platResult: { kind, jobId: this.__platJobId, projectId: String(id), candidates, selected: 0 },
            view: kind === 'plan' ? 'plan-result' : 'facade-result' });
        });
      }
      if (resume === 'VIEW_RESULT') {
        return api.projects.resultVersions(id).then(versions => {
          if (this.platStale(gen, sg)) return;
          const vs = (versions || []).map(PF.resultVersionRow);
          const latest = vs.find(v => !v.superseded) || vs[0];
          if (latest && latest.assetId) {
            return this.platFetchImages([latest.assetId]).then(urls => {
              if (this.platStale(gen, sg)) return;
              this.setData({ platProjectResultImage: urls[0] || '', platProjectResultVersion: latest });
            });
          }
        });
      }
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platformErr: PF.errorMessageDesign(err) });
    });
  };
  page.platResumeAction = function () { // resumeAction 驱动续做（旧版 utils/project-resume 同语义）
    const d = this.data.platProjectDetail; if (!d) return;
    const act = d.resumeAction;
    if (act === 'POLL_JOB' && this.__platJobId) {
      this.setData({ platPolling: true, platStatusText: PF.STATUS_TEXT.RUNNING, platProgress: 5,
        platEstimateText: PF.estimateText(1) });
      this.platPoll(null, ++this.__platGen, this.__http.sessionGeneration(), this.__platKind || 'plan', d.projectId);
      return;
    }
    if (act === 'CREATE_FLAT_JOB' || act === 'SELECT_FLAT') return this.platShow('ai-config');
    if (act === 'CREATE_ELEVATION_JOB' || act === 'SELECT_ELEVATION') return this.platShow('ai-facade');
    if (act === 'VIEW_RESULT' && this.data.platProjectResultImage) return wx.previewImage({ urls: [this.data.platProjectResultImage] });
    this.platFail('该项目暂无可续做事项');
  };

  // ---- 基础：命令快照 / 乱序三元组 / 401 ----
  page.__platGen = 0; page.__platStylesGen = 0; page.__platCasesGen = 0; page.__platCasesRequestGen = 0;
  page.__platKeys = {}; page.__platTimers = [];
  page.platSessionStale = function (sessionGen) {
    return this.__platUnloaded || sessionGen !== http.sessionGeneration() ||
      String((this.__post || {}).postId) !== String(this.__postKey || '');
  };
  page.platStale = function (gen, sessionGen) {
    return gen !== this.__platGen || this.platSessionStale(sessionGen);
  };
  page.platKey = function (action) {
    if (!this.__platKeys[action]) this.__platKeys[action] = 'dsn-' + action + '-' + String(Math.floor(Math.random() * 1e16)).padStart(16, '0');
    return this.__platKeys[action];
  };
  page.platDropKey = function (action) { delete this.__platKeys[action]; };
  page.platHandle401 = function (err, sessionGen) {
    if (!(err && err.code === 401 && !err.silent)) return false;
    if (this.__http.token()) {
      if (sessionGen !== this.__http.sessionGeneration()) return true; // 迟到 401：整体丢弃
      this.__http.clearSession();
    }
    this.platTeardown(); return true;
  };
  page.platTeardown = function () {
    this.__platGen += 1; // 使换岗/登出前发起的字节上传与 complete 立即失效
    this.__platStylesGen += 1;
    this.__platCasesGen += 1;
    this.__platCasesRequestGen += 1;
    this.__platTimers.forEach(t => clearTimeout(t)); this.__platTimers = [];
    this.__platKeys = {};
    this.setData({ platformMode: false, busy: false, platformErr: '', platCases: [], platStyles: [],
      platCase: null, platCaseVerified: false, platCaseImages: [], platLoading: false, platCaseLoading: false, platCaseImageLoading: false,
      platResult: null, platProjects: [], platRule: null });
  };
  page.platOnUnload = function () { this.__platUnloaded = true; this.__platTimers.forEach(t => clearTimeout(t)); };
  page.platFail = msg => { page.setData({ busy: false, platformErr: msg }); };

  // ---- 灵感库（R1/R2/R4/R5；图 R8 现取）----
  page.platLoadStyles = function () {
    const gen = ++this.__platStylesGen, sg = this.__http.sessionGeneration();
    api.cases.styles().then(styles => {
      if (gen !== this.__platStylesGen || this.platSessionStale(sg)) return;
      const options = (styles || []).map(s2 => ({ code: s2.code, label: s2.label }));
      this.setData({ platStyles: options, platStyleOptions: [{ code: '', label: '全部风格' }].concat(options), platStylesError: '' });
    }).catch(err => {
      if (gen !== this.__platStylesGen || this.platSessionStale(sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platStylesError: '风格筛选暂不可用，可重试或直接浏览户型' });
    });
  };
  page.platLoadCases = function (reset) {
    const gen = ++this.__platCasesRequestGen, sg = this.__http.sessionGeneration();
    if (reset) this.__platCasesGen += 1;
    const listGen = this.__platCasesGen;
    const coversAlive = () => listGen === this.__platCasesGen && !this.platSessionStale(sg);
    const stale = () => gen !== this.__platCasesRequestGen || !coversAlive();
    const f = this.data.platFilter || {};
    const params = { limit: 20 };
    if (['faceWidth', 'depth'].some(key => f[key] && (!Number.isFinite(Number(f[key])) || Number(f[key]) <= 0))) {
      this.setData({ platLoading: false, platformErr: '开间和进深须为正数' }); return;
    }
    if (!reset && this.data.platCursor) params.cursor = this.data.platCursor;
    if (f.styleCode) params.styleCode = f.styleCode;
    if (f.floorCount) params.floorCount = Number(f.floorCount);
    if (f.faceWidth) params.faceWidth = Number(f.faceWidth);
    if (f.depth) params.depth = Number(f.depth);
    if (this.data.keyword) params.keyword = this.data.keyword;
    if (reset) { this.setData({ platCursor: '', platCases: [], platLoading: true, platformErr: '' }); this.platLoadStyles(); }
    else this.setData({ platLoading: true, platformErr: '' });
    api.cases.page(params).then(res => {
      if (stale()) return;
      const rows = (res && res.list || []).map(PF.caseRow);
      this.setData({
        platCases: reset ? rows : (this.data.platCases || []).concat(rows),
        platCursor: (res && res.nextCursor) || '',
        platLoading: false
      });
      return PF.loadCaseCovers(rows, (caseId, assetId) => api.cases.downloadUrl(caseId, assetId),
        coversAlive, row => {
          const index = (this.data.platCases || []).findIndex(current => current.id === row.id);
          if (index >= 0) this.setData({ ['platCases[' + index + ']']: row });
        });
    }).catch(err => {
      if (stale()) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platLoading: false, platformErr: PF.errorMessageDesign(err) });
    });
  };
  page.platLoadMore = function () { if (this.data.platCursor && !this.data.platLoading) this.platLoadCases(false); };
  page.platRetryCases = function () { this.platLoadCases(!this.data.platCases.length); };
  page.platCoverError = function (e) {
    const index = (this.data.platCases || []).findIndex(row => row.id === String(e.currentTarget.dataset.id));
    if (index < 0) return;
    this.setData({ ['platCases[' + index + ']']: Object.assign({}, this.data.platCases[index], { coverUrl: '', coverError: true, coverLoading: false }) });
  };
  page.platRetryCover = function (e) {
    const id = String(e.currentTarget.dataset.id), index = (this.data.platCases || []).findIndex(row => row.id === id);
    if (index < 0 || this.data.platCases[index].coverLoading) return;
    const gen = this.__platCasesGen, sg = this.__http.sessionGeneration();
    const alive = () => gen === this.__platCasesGen && !this.platSessionStale(sg);
    const row = Object.assign({}, this.data.platCases[index], { coverLoading: true, coverError: false });
    this.setData({ ['platCases[' + index + ']']: row });
    PF.loadCaseCovers([row], (caseId, assetId) => api.cases.downloadUrl(caseId, assetId), alive)
      .then(rows => {
        if (!alive()) return;
        const currentIndex = (this.data.platCases || []).findIndex(current => current.id === id);
        if (currentIndex >= 0) this.setData({ ['platCases[' + currentIndex + ']']: rows[0] });
      })
      .catch(err => {
        if (!alive() || this.platHandle401(err, sg)) return;
        this.setData({ ['platCases[' + index + ']']: Object.assign({}, row, { coverLoading: false, coverError: true }),
          platformErr: PF.errorMessageDesign(err) });
      });
  };
  page.platFilterPick = function (e) {
    const key = e.currentTarget.dataset.key, idx = Number(e.detail.value);
    const list = key === 'styleCode' ? [{ code: '', label: '全部' }].concat(this.data.platStyles) : (key === 'floorCount' ? ['', '1', '2', '3', '4'] : null);
    const selected = list && list[idx];
    if (list && selected === undefined) return;
    const value = list ? selected.code !== undefined ? selected.code : selected : e.detail.value;
    const label = key === 'styleCode' ? selected.label : (idx ? idx + '层' : '全部');
    this.setData({ platFilter: Object.assign({}, this.data.platFilter, { [key]: value || undefined }),
      [key === 'styleCode' ? 'platStyleLabel' : 'platFloorLabel']: label });
    this.platLoadCases(true);
  };
  page.platSearchInput = function (e) { this.setData({ keyword: e.detail.value }); };
  page.platDimensionInput = function (e) {
    const key = e.currentTarget.dataset.key;
    if (!['faceWidth', 'depth'].includes(key)) return;
    this.setData({ platFilter: Object.assign({}, this.data.platFilter, { [key]: e.detail.value }), platformErr: '' });
  };
  page.platSearch = function () { this.platLoadCases(true); };
  page.platOpenCase = function (e) {
    const id = e.currentTarget.dataset.id;
    const c = (this.data.platCases || []).find(x => String(x.id) === String(id));
    if (!c) return;
    this.platLoadCase(c.id, c);
  };
  page.platLoadCase = function (id, summary) {
    if (!id) { this.setData({ platformErr: '未指定户型，请返回灵感库选择' }); return; }
    id = String(id);
    this.setData({ platCase: summary || { id }, platCaseVerified: false, platCaseImages: [], caseTabs: [], caseTab: 0, platCaseFav: false,
      platFavoriteLoading: true, platFavoriteSaving: false, platFavoriteError: false,
      platCaseLoading: true, platCaseImageLoading: true, platCaseImagesError: false, platformErr: '' });
    this.platShow('case');
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    api.cases.detail(id).then(detail => {
      if (this.platStale(gen, sg)) return;
      const drawings = PF.caseDrawings(detail), ids = drawings.map(item => item.assetId);
      this.setData({ platCase: PF.caseRow(detail), platCaseVerified: true, platCaseAssetIds: ids, platCaseLoading: false,
        caseTabs: drawings.map(item => item.label) });
      this.platLoadFavorite(id, gen, sg);
      return PF.loadCaseCovers(ids.map(asset => ({ id, coverAssetId: asset })),
        (caseId, assetId) => api.cases.downloadUrl(caseId, assetId), () => !this.platStale(gen, sg)).then(rows => {
          if (this.platStale(gen, sg)) return;
          this.setData({ platCaseImages: rows.map(row => row.coverUrl || ''), platCaseImageLoading: false,
            platCaseImagesError: rows.some(row => row.coverError) });
        });
    }).catch(err => {
      if (this.platStale(gen, sg) || this.platHandle401(err, sg)) return;
      this.setData({ platCaseLoading: false, platCaseImageLoading: false, platformErr: PF.errorMessageDesign(err) });
    });
  };
  page.platRetryCaseImages = function () { if (this.data.platCase) this.platLoadCase(this.data.platCase.id); };
  page.platCaseImageError = function () {
    const images = (this.data.platCaseImages || []).slice();
    images[this.data.caseTab || 0] = '';
    this.setData({ platCaseImages: images, platCaseImagesError: true });
  };
  page.platFetchImages = async function (assetIds, caseId) {
    const ids = (assetIds || []).filter(Boolean);
    const read = a => (caseId ? api.cases.downloadUrl(caseId, a) : api.assets.downloadUrl(a)).then(r => {
      if (!r || typeof r.downloadUrl !== 'string' || !r.downloadUrl.trim()) throw new Error('图片下载票据无效，请重试');
      return r.downloadUrl;
    });
    // 公开图每张需要三次上游读取；逐张取票，避免四层平面+封面触发本组织并发限额。
    if (caseId) {
      const urls = [];
      for (const id of ids) urls.push(await read(id));
      return urls;
    }
    return Promise.all(ids.map(read));
  };
  page.platCaseTab = function (e) { this.setData({ caseTab: Number(e.currentTarget.dataset.index) }); };
  page.platLoadFavorite = async function (id, gen = this.__platGen, sg = http.sessionGeneration()) {
    this.setData({ platFavoriteLoading: true, platFavoriteError: false });
    let cursor = '', favorite = false;
    const seen = new Set();
    try {
      do {
        const response = await api.cases.favorites(cursor, 50);
        if (this.platStale(gen, sg) || !this.data.platCase || String(this.data.platCase.id) !== String(id)) return;
        if (!response || !Array.isArray(response.list)) throw Error('收藏状态读取失败');
        favorite = response.list.some(row => String(row.caseId == null ? row.id : row.caseId) === String(id));
        cursor = response.nextCursor || '';
        if (cursor && seen.has(cursor)) throw Error('收藏分页异常，请重试');
        seen.add(cursor);
      } while (cursor && !favorite);
      this.setData({ platCaseFav: favorite, platFavoriteLoading: false });
    } catch (error) {
      if (!this.platStale(gen, sg) && !this.platHandle401(error, sg)) this.setData({ platFavoriteLoading: false, platFavoriteError: true });
    }
  };
  page.platFavorite = function () {
    const c = this.data.platCase; if (!c) return;
    if (this.data.platFavoriteLoading || this.data.platFavoriteSaving) return;
    if (this.data.platFavoriteError) return this.platLoadFavorite(c.id);
    const gen = this.__platGen, sg = http.sessionGeneration(), next = !this.data.platCaseFav;
    const key = this.data.platCaseFav ? 'unfavorite' : 'favorite';
    this.setData({ platFavoriteSaving: true });
    return api.cases[key](c.id).then(saved => {
      if (this.platStale(gen, sg) || this.data.platCase.id !== c.id) return;
      if (saved !== true) throw Error('收藏未保存，请重试');
      this.setData({ platCaseFav: next, platFavoriteSaving: false });
      wx.showToast({ title: this.data.platCaseFav ? '已收藏' : '已取消收藏', icon: 'none' });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      this.setData({ platFavoriteSaving: false, platFavoriteError: true });
      if (!(err && err.code === 401)) wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' });
    });
  };

  // ---- AI 生成链（R9 → R12/R13 → R14 轮询 → R11 → R15/R16）----
  page.platProjectFingerprint = function (f) {
    return JSON.stringify([f.width, f.depth, f.floors, f.style || '', f.note || '', f.entranceOrientation || '', f.family || '', f.courtyardLayout || '', f.drawingType || '', f.referenceCaseId || '', (f.referenceAssetIds || []).map(String)]);
  };
  page.platGenerate = function (kind) {
    if (this.data.busy) return;
    const f = this.data.form || {};
    const inputs = PF.requirementInputs(f);
    if (inputs.error) return this.platFail(inputs.error);
    if (!['1', '2', '4'].includes(String(f.count))) return this.platFail('请核对候选数量');
    if (!['2K', '4K'].includes(f.resolution)) return this.platFail('请核对清晰度');
    if (kind === 'facade' && !PF.roofCode(f.roof)) return this.platFail('请选择屋顶形式');
    // 需求参数变化 → 旧项目（旧 requirementInputs/幂等键）作废：重置项目/候选/预算状态，防 409 与错参数生成
    const fp = this.platProjectFingerprint(f);
    if (this.__platProjectFp && this.__platProjectFp !== fp) {
      this.__platProjectId = ''; ['proj-budget', 'proj-plan', 'proj-facade', 'flat', 'elev'].forEach(action => this.platDropKey(action));
      this.setData({ platResultVersionId: '' });
      this.__platEstimateId = '';
    }
    this.__platProjectFp = fp;
    const line = kind === 'plan' ? 'FLAT' : 'ELEVATION';
    const orientation = f.orientation; // PORTRAIT/LANDSCAPE 或空（走后端默认）
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true, platformErr: '', platResult: null, platPolling: true, platPollTip: '已提交，AI 生成中…',
      platProgress: 5, platStatusText: '排队等待中…', platAccepted: 0, platCancelResult: null,
      platEstimateText: PF.estimateText(Number(f.count)) });
    const body = { sourceType: 'SELF_UPLOAD', line, requirementInputs: Object.assign({}, inputs.inputs) };
    if (kind === 'facade') body.requirementInputs.referenceAssetIds = (f.referenceAssetIds || []).filter(Boolean).map(String);
    if (kind === 'facade' && f.referenceCaseId) { body.sourceType = 'CASE_REFERENCE'; body.refCaseId = f.referenceCaseId; }
    const projAction = 'proj-' + kind;
    const createKey = this.platKey(projAction);
    api.projects.create(body, createKey).then(proj => {
      if (this.platStale(gen, sg)) return;
      const projectId = String(proj.projectId);
      this.__platProjectId = projectId;
      const jobAction = kind === 'plan' ? 'flat' : 'elev';
      const jobKey = this.platKey(jobAction);
      const jobBody = { count: Number(f.count), resolution: f.resolution };
      if (orientation === 'LANDSCAPE' || orientation === 'PORTRAIT') jobBody.orientation = orientation;// 后端值域=画面方向；非法值不传走后端默认（平面竖图/立面横图）
      if (kind === 'facade') {
        jobBody.styleCode = PF.styleCode(f.style);
        jobBody.roofType = PF.roofCode(f.roof);
        jobBody.material = f.material; jobBody.color = f.color;
        if (!jobBody.styleCode || !jobBody.roofType) { this.setData({ busy: false, platPolling: false }); return this.platFail('请选择有效风格与屋顶'); }
        if (!jobBody.material || !jobBody.color) { this.setData({ busy: false, platPolling: false }); return this.platFail('请选择材质与主色'); }
      }
      const createJob = kind === 'plan' ? api.projects.createFlatJob : api.projects.createElevationJob;
      return createJob(projectId, jobBody, jobKey).then(job => {
        this.platDropKey(projAction); this.platDropKey(jobAction);
        if (this.platStale(gen, sg)) return;
        this.__platJobId = String(job.jobId);
        this.__platKind = kind;
        this.platPoll(jobId => String(job.jobId) === String(this.__platJobId) ? this.__platJobId : this.__platJobId, gen, sg, kind, projectId);
      });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false, platPolling: false });
      if (![-1, 1112100002].includes(Number(err && err.code))) { this.platDropKey(projAction); this.platDropKey(kind === 'plan' ? 'flat' : 'elev'); }
      this.platFail(PF.errorMessageDesign(err));
    });
  };
  page.platPoll = function (_idGetter, gen, sg, kind, projectId) {
    const tick = () => {
      if (this.platStale(gen, sg)) return; // 切岗/登出/卸载：终止轮询
      const elapsed = Date.now() - this.__platPollStart;
      api.projects.aiJob(this.__platJobId).then(job => {
        if (this.platStale(gen, sg)) return;
        const decision = PF.pollDecision(job.status, elapsed);
        if (decision.action === 'continue') {
          // 进度平滑（旧版公式）+ 状态文案 + 部分成功已到账提示（acceptedCount>0 即有可用候选）
          this.setData({ platProgress: PF.smoothProgress(job.status, elapsed, job.progress),
            platStatusText: PF.STATUS_TEXT[job.status] || this.data.platStatusText || 'AI 正在绘制方案…',
            platAccepted: job.acceptedCount || 0 });
          this.__platTimers.push(setTimeout(tick, PF.POLL_INTERVAL_MS)); return;
        }
        this.setData({ platPolling: false, busy: false });
        if (decision.action === 'timeout') return this.platFail(decision.message);
        if (decision.action === 'failed') return this.platFail(decision.message);
        // candidates：R11 detail?jobId（拉式晋升出候选）
        api.projects.detail(projectId, this.__platJobId).then(detail => {
          if (this.platStale(gen, sg)) return;
          const candidates = PF.candidateRows(detail.candidates);
          if (!candidates.length) return this.platFail('生成完成但无候选，请调整参数重试');
          return this.platFetchImages(candidates.map(c => c.assetId)).then(urls => {
            if (this.platStale(gen, sg)) return;
            candidates.forEach((c, i) => { c.imageUrl = urls[i] || ''; });
            this.__platKind = kind; this.__platProjectId = projectId;
            this.setData({ platResult: { kind, jobId: this.__platJobId, projectId, candidates, selected: 0,
              refundedPointCost: job.refundedPointCost || 0, netPointCost: job.netPointCost || 0,
              candidateTotal: job.requestedCount || candidates.length, status: job.status }, view: kind === 'plan' ? 'plan-result' : 'facade-result' });
          });
        }).catch(err => {
          if (this.platStale(gen, sg)) return;
          if (this.platHandle401(err, sg)) return;
          // 未终态业务码（手动刷新场景）→ 仍在生成
          this.platFail(PF.errorMessageDesign(err));
        });
      }).catch(err => {
        if (this.platStale(gen, sg)) return;
        this.setData({ platPolling: false, busy: false });
        this.platFail(PF.errorMessageDesign(err));
      });
    };
    this.__platPollStart = Date.now();
    this.__platTimers.push(setTimeout(tick, PF.POLL_INTERVAL_MS));
  };
  page.platRefreshJob = function () { // S1-2 手动刷新
    if (!this.__platJobId || !this.__platProjectId) return;
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ platPolling: true, platformErr: '' });
    api.projects.detail(this.__platProjectId, this.__platJobId).then(detail => {
      if (this.platStale(gen, sg)) return;
      const candidates = PF.candidateRows(detail.candidates);
      if (!candidates.length) return this.platFail(PF.STILL_RUNNING_MESSAGE);
      return this.platFetchImages(candidates.map(c => c.assetId)).then(urls => {
        if (this.platStale(gen, sg)) return;
        candidates.forEach((c, i) => { c.imageUrl = urls[i] || ''; });
        this.setData({ platPolling: false, platResult: { kind: this.__platKind, jobId: this.__platJobId, projectId: this.__platProjectId, candidates, selected: 0 }, view: this.__platKind === 'plan' ? 'plan-result' : 'facade-result' });
      });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platPolling: false });
      this.platFail(PF.errorMessageDesign(err));
    });
  };
  // ---- 取消生成（对齐旧版 1.5：QUEUED/RUNNING/VALIDATING 均可取消，退点随结算）----
  page.platCancelJob = function () {
    const jobId = this.__platJobId; if (!jobId || !this.data.platPolling) return;
    wx.showModal({ title: '取消本次生成', content: '已生成的候选会保留，未完成部分将自动退点。确定取消？', success: r => {
      if (!r.confirm) return;
      const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
      api.projects.cancelJob(jobId).then(job => {
        if (this.platStale(gen, sg)) return;
        this.setData({ platPolling: true, platStatusText: PF.STATUS_TEXT.CANCEL_REQUESTED });
        // 取消后继续短暂轮询等结算（CANCELLED/PARTIALLY_SUCCEEDED 终态）
        const tick = () => {
          if (this.platStale(gen, sg)) return;
          api.projects.aiJob(jobId).then(j2 => {
            if (this.platStale(gen, sg)) return;
            if (['CANCELLED', 'PARTIALLY_SUCCEEDED', 'SUCCEEDED', 'FAILED'].includes(j2.status)) {
              this.setData({ platPolling: false, busy: false, platCancelResult: { status: j2.status, refunded: j2.refundedPointCost || 0, net: j2.netPointCost || 0 } });
              const msg = j2.status === 'PARTIALLY_SUCCEEDED' ? '已取消：部分方案已生成，退回 ' + (j2.refundedPointCost || 0) + ' 点'
                : j2.status === 'CANCELLED' ? '已取消，扣除的 ' + (j2.refundedPointCost || 0) + ' 点已退回' : '任务已结束';
              wx.showToast({ title: msg, icon: 'none', duration: 3000 });
              if (j2.status === 'PARTIALLY_SUCCEEDED') this.platRefreshJob();
              return;
            }
            this.__platTimers.push(setTimeout(tick, 3000));
          }).catch(() => { if (!this.platStale(gen, sg)) this.setData({ platPolling: false, busy: false }); });
        };
        this.__platTimers.push(setTimeout(tick, 3000));
      }).catch(err => {
        if (this.platStale(gen, sg)) return;
        wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' });
      });
    }});
  };
  // ---- note AI 润色（POST /note-polish；限流 10 次/分——429 提示繁忙）----
  page.platNotePolish = function () {
    const note = String((this.data.form || {}).note || '').trim();
    if (!note) return this.platFail('请先填写需求说明再润色');
    if (this.data.busy) return;
    const line = this.data.kind === 'facade' ? 'ELEVATION' : 'FLAT';
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true });
    api.projects.notePolish({ note, line }).then(res => {
      if (this.platStale(gen, sg)) return;
      this.setData({ busy: false, 'form.note': res.polished || note });
      wx.showToast({ title: '已优化需求说明', icon: 'success' });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      this.setData({ busy: false });
      this.platFail((err && err.code === 429) ? 'AI 优化繁忙，请稍后再试' : PF.errorMessageDesign(err));
    });
  };
  // ---- 立面自传参考图（≤3 张；upload-tickets → B 代理/COS 原始字节 PUT → complete）----
  page.platAddReference = function () {
    const existing = (this.data.form.referenceAssetIds || []).filter(Boolean);
    if (existing.length >= 3) return this.platFail('自传参考最多 3 张');
    wx.chooseMedia({ count: 3 - existing.length, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], success: r => {
      const files = (r.tempFiles || []).slice(0, 3 - existing.length);
      if (!files.length) return;
      const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
      this.setData({ busy: true });
      const fs = wx.getFileSystemManager();
      const uploadOne = f => new Promise((resolve, reject) => {
        fs.readFile({ filePath: f.tempFilePath, success: rd => {
          const bytes = rd.data;
          // sha256：走 finance.js 的工具（与工程凭据链同一实现）
          const finance = require('../../miniprogram/api/finance');
          let mimeType;
          try {
            // 立面参考图只收 JPG/PNG；识别不出的格式（HEIC/WebP/GIF…）本地直接拒绝，不浪费一次票据与上传
            mimeType = finance.voucherMime(f.tempFilePath, bytes, { allowPdf: false });
          } catch (err) {
            reject({ message: (err && (err.msg || err.message)) || '请选择 JPG 或 PNG 图片' });
            return;
          }
          const sha = finance.sha256Hex(bytes);
          api.assets.uploadTicket({ assetType: 'DESIGN_REFERENCE', mimeType, sizeBytes: bytes.byteLength || bytes.length, sha256: sha })
            .then(ticket => {
              if (this.platStale(gen, sg)) throw { silent: true };
              const rawUrl = String(ticket.uploadUrl || '');
              const direct = /^https?:\/\//.test(rawUrl);
              const proxy = /^\/admin-api\/franchise\/assets\/\d+\/content\?token=/.test(rawUrl);
              const oldPrivateAssetUrl = /\/(?:app-api\/design\/v1\/assets|platform\/dev\/design\/assets)\//.test(rawUrl);
              if (oldPrivateAssetUrl || (!direct && !proxy)) throw { message: '当前环境未配置可用的 B 素材上传通道' };
              let uploadUrl = rawUrl;
              const header = { 'Content-Type': mimeType };
              if (proxy) {
                const apiBase = String(http.base ? http.base() : '');
                const origin = (apiBase.match(/^https?:\/\/[^/]+/) || [])[0];
                if (!origin) throw { message: '当前环境未配置可用的 B 素材上传通道' };
                uploadUrl = origin + rawUrl;
                if (http.token()) header.Authorization = 'Bearer ' + http.token();
                if (http.currentPostId()) header['X-Post-Id'] = http.currentPostId();
                header['tenant-id'] = http.tenantId();
              }
              return new Promise((resolvePut, rejectPut) => {
                wx.request({ url: uploadUrl, method: 'PUT', data: bytes, timeout: 15000, header,
                  success: response => {
                    const body = response && response.data;
                    if (response.statusCode >= 200 && response.statusCode < 300 && !(body && body.code !== undefined && body.code !== 0)) {
                      resolvePut(ticket); return;
                    }
                    rejectPut({ message: (body && body.msg) || '参考图上传失败（HTTP ' + response.statusCode + '）' });
                  },
                  fail: error => rejectPut({ message: (error && error.errMsg) || '参考图上传网络失败，请重试' })
                });
              });
            })
            .then(ticket => {
              if (this.platStale(gen, sg)) throw { silent: true };
              return api.assets.complete(ticket.assetId).then(done => ({ ticket, done }));
            })
            .then(({ ticket, done }) => {
              if (this.platStale(gen, sg)) throw { silent: true };
              if (!done || done.uploadStatus !== 'ACCEPTED') throw { message: '参考图未通过校验：' + ((done && done.rejectedReason) || '请更换图片') };
              resolve(ticket.assetId);
            })
            .catch(reject);
        }, fail: () => reject({ message: '读取图片失败' }) });
      });
      Promise.all(files.map(uploadOne)).then(ids => {
        if (this.platStale(gen, sg)) return;
        const all = existing.concat(ids.map(String));
        this.setData({ busy: false, 'form.referenceAssetIds': all, platRefCount: all.length });
        wx.showToast({ title: '已添加 ' + ids.length + ' 张参考图', icon: 'success' });
      }).catch(err => {
        if (this.platStale(gen, sg)) return;
        this.setData({ busy: false });
        this.platFail((err && err.message) || '参考图上传失败，请重试');
      });
    }});
  };
  page.platRemoveReference = function (e) {
    const idx = Number(e.currentTarget.dataset.index);
    const all = (this.data.form.referenceAssetIds || []).filter(Boolean);
    all.splice(idx, 1);
    this.setData({ 'form.referenceAssetIds': all, platRefCount: all.length });
  };
  // ---- 结果图保存相册（旧版结果页能力）----
  page.platSaveImage = function () {
    const result = this.data.platResult; if (!result) return;
    const url = (result.candidates[result.selected] || {}).imageUrl;
    if (!url) return this.platFail('请先生成并选定方案');
    wx.downloadFile({ url, success: r => wx.saveImageToPhotosAlbum({ filePath: r.tempFilePath,
      success: () => wx.showToast({ title: '已保存到相册', icon: 'success' }),
      fail: e2 => this.platFail(/auth/.test(e2.errMsg || '') ? '需要相册权限，请在设置中开启' : '保存失败，请重试') }),
      fail: () => this.platFail('下载失败，请重试') });
  };
  page.platSelectCandidate = function (e) {
    const idx = Number(e.currentTarget.dataset.index);
    const result = this.data.platResult; if (!result || !result.candidates[idx]) return;
    const c = result.candidates[idx];
    const kind = result.kind;
    const selApi = kind === 'plan' ? api.projects.selectFlat : api.projects.selectElevation;
    this.setData({ busy: true });
    selApi(result.projectId, { jobId: result.jobId, candidateId: c.candidateId }).then(res => {
      this.setData({ busy: false, 'platResult.selected': idx, platResultVersionId: res && res.resultVersionId ? String(res.resultVersionId) : (this.data.platResultVersionId || '') });
      wx.showToast({ title: '已选定该方案', icon: 'none' });
    }).catch(err => {
      this.setData({ busy: false });
      if (this.platHandle401(err, this.__http.sessionGeneration())) return;
      wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' });
    });
  };
  page.platRevision = function () { // R18 调整请求
    const result = this.data.platResult; if (!result) return;
    wx.showModal({ title: '调整请求', editable: true, placeholderText: '请填写调整原因（必填）', success: r => {
      if (!r.confirm) return;
      const reason = (r.content || '').trim();
      if (!reason || reason.length > 500) return this.platFail('请填写调整原因（500字以内）');
      const key = this.platKey('rev');
      const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
      this.setData({ busy: true });
      api.projects.revision(result.projectId, { reason }, key).then(res => {
        if (this.platStale(gen, sg)) return;
        this.platDropKey('rev'); this.setData({ busy: false });
        this.__platJobId = String(res.newJobId);
        wx.showToast({ title: '已提交调整，生成中', icon: 'none' });
        this.setData({ platPolling: true, platPollTip: '调整生成中…' });
        this.__platPollStart = Date.now();
        const tick = () => {
          if (this.platStale(gen, sg)) return;
          api.projects.aiJob(this.__platJobId).then(job => {
            if (this.platStale(gen, sg)) return;
            const decision = PF.pollDecision(job.status, Date.now() - this.__platPollStart);
            if (decision.action === 'continue') { this.__platTimers.push(setTimeout(tick, 3000)); return; }
            this.setData({ platPolling: false, busy: false });
            if (decision.action !== 'candidates') return this.platFail(decision.message);
            this.platRefreshJob();
          }).catch(() => { if (!this.platStale(gen, sg)) this.setData({ platPolling: false, busy: false }); });
        };
        this.__platTimers.push(setTimeout(tick, 3000));
      }).catch(err => {
        if (this.platStale(gen, sg)) return;
        if (this.platHandle401(err, sg)) return;
        this.setData({ busy:false });
        if ((err && err.code) !== -1) this.platDropKey('rev');
        this.platFail(PF.errorMessageDesign(err));
      });
    }});
  };

  // ---- 快速预算（R9 确保项目 → R19 → R20）----
  page.platEnsureProject = function (f) {
    // 预算的参数指纹独立比对：宽深层数变化 → 旧项目作废（旧 requirementInputs 的 R19 结果不可信）
    const fp = this.platProjectFingerprint({ width: f.width, depth: f.depth, floors: f.floors, style: f.style });
    if (this.__platProjectId && this.__platProjectFp === fp) return Promise.resolve(this.__platProjectId);
    this.__platProjectFp = fp; this.__platProjectId = '';
    const inputs = PF.requirementInputs({ width: f.width, depth: f.depth, floors: f.floors, style: f.style || '现代简约', note: '' });
    if (inputs.error) return Promise.reject({ code: 0, message: inputs.error });
    const key = this.platKey('proj-budget');
    return api.projects.create({ sourceType: 'SELF_UPLOAD', line: 'FLAT', requirementInputs: inputs.inputs }, key)
      .then(proj => { this.platDropKey('proj-budget'); this.__platProjectId = String(proj.projectId); return this.__platProjectId; });
  };
  page.platLoadRule = function () {
    const f = this.data.form || {};
    if (!f.regionCode || !f.structureType || !f.materialGrade) return;
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    api.budget.ruleInfo(f.regionCode, f.structureType, f.materialGrade).then(data => {
      if (this.platStale(gen, sg)) return;
      this.setData({ platRule: PF.ruleInfoView(data) });
    }).catch(() => { if (!this.platStale(gen, sg)) this.setData({ platRule: { available: false, lowText: '', highText: '', ruleVersionId: '' } }); });
  };
  page.platCalculate = function () {
    if (this.data.busy) return;
    const f = this.data.form || {};
    const body = PF.estimateBody(f);
    if (body.error) return this.platFail(body.error);
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true, platformErr: '' });
    this.platEnsureProject(f).then(projectId => {
      if (this.platStale(gen, sg)) return;
      const key = this.platKey('budget');
      const reqBody = Object.assign({ resultVersionId: undefined }, body.body);
      const rvi = this.data.platResultVersionId; if (rvi) reqBody.resultVersionId = rvi;
      return api.budget.estimate(projectId, reqBody, key).then(res => {
        if (this.platStale(gen, sg)) return;
        this.platDropKey('budget');
        const view = PF.estimateView(res);
        this.__platEstimateId = view.estimateId;
        this.setData({ busy: false, platBudget: view, view: 'budget-result', kind: 'budget' });// kind 随数据源切换（防保存 kind 残留为 plan）
      });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.handle401 && this.handle401(err, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false });
      if ((err && err.code) !== -1) this.platDropKey('budget');
      this.platFail(err && err.message ? PF.errorMessageDesign(err) : err);
    });
  };

  // ---- 保存到客户（R21；客户列表=CRM customers.page 可读集合）----
  page.platSave = function (kind) {
    if (this.data.busy) return;
    if (kind === 'plan' && !this.data.platResultVersionId) return this.platFail('请先选定平面候选');
    if (kind === 'facade' && !this.data.platResultVersionId) return this.platFail('请先选定立面候选');
    if (kind === 'budget' && !this.__platEstimateId) return this.platFail('请先完成预算测算');
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true });
    require('../../miniprogram/api/franchise/crm.js').customers.page(1, 50).then(res => {
      if (this.platStale(gen, sg)) return;
      const rows = (res && res.list || []).map(c => ({ id: String(c.id), name: c.name, city: c.city }));
      this.setData({ busy: false, saveCustomers: rows, sheet: true });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.platFail(PF.errorMessageDesign(err));
    });
  };
  page.platSaveCustomer = function (e) {
    const kindMap = { plan: 'PLAN', facade: 'FACADE', budget: 'BUDGET' };
    // kind 取当前视图语义（platShow/platCalculate 链路维护；budget-result 由 platCalculate 置 budget）
    const currentKind = this.data.kind || 'plan';
    const kind = kindMap[currentKind] || 'PLAN';
    const customerId = e.currentTarget.dataset.id;
    const f = this.data.form || {};
    const body = { customerId, kind: kind.toUpperCase() };
    if (kind === 'BUDGET') {
      body.estimateId = this.__platEstimateId;
      body.width = f.width; body.depth = f.depth; body.floors = Number(f.floors);
      if (!body.width || !body.depth || !body.floors) return this.platFail('预算回写须有宽深层数，请核对预算表单');
    } else {
      body.resultVersionId = this.data.platResultVersionId;
      if (!body.resultVersionId) return this.platFail('请先选定候选');
    }
    if (!this.__platProjectId) return this.platFail('页面状态已失效，请重新生成');
    const key = this.platKey('save');
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true });
    api.projects.saveToCustomer(this.__platProjectId, body, key).then(res => {
      if (this.platStale(gen, sg)) return;
      this.platDropKey('save'); this.setData({ busy: false, sheet: false, platSaved: { customerId, kind, schemeVersion: res && res.version } });
      wx.showToast({ title: '已保存到客户方案', icon: 'success' });
    }).catch(err => {
      if (this.stale ? this.stale(gen, sg, this.__postKey || '') : this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false });
      if ((err && err.code) !== -1) this.platDropKey('save');
      this.platFail(PF.errorMessageDesign(err));
    });
  };
};
