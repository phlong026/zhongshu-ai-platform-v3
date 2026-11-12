/**
 * explore 页平台（API）态桥接层（阶段 1 · P3）：灵感库/AI 平面/AI 立面/快速预算/回写。
 * 注入式 attach(page, deps)（finance-desk platform-actions 先例）；契约=docs/backend-parity/13 §三/§四/§五。
 * demo 隔离红线：本层任何路径不读写 demo Storage（designExplorePrefix 表单白名单除外——S 层由页面 guard 放行后按原链写入）。
 */
const intents = require('../../../utils/design-intent');

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
    const id = e.currentTarget.dataset.id;
    const c = (this.data.platCases || []).find(x => String(x.id) === String(id));
    if (!c) return;
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
  page.__platGen = 0; page.__platTimers = [];
  const initialScope = intents.context(), initialSession = http.sessionGeneration();
  page.platActive = function () {
    if (this.__platUnloaded || initialSession !== http.sessionGeneration()) return false;
    try { return initialScope === intents.context(); } catch (err) { return false; }
  };
  page.platStale = function (gen, sessionGen) {
    return !this.platActive() || gen !== this.__platGen || sessionGen !== http.sessionGeneration();
  };
  page.platHandle401 = function (err, sessionGen) {
    if (!(err && err.code === 401 && !err.silent)) return false;
    if (this.__http.token()) {
      if (sessionGen !== this.__http.sessionGeneration()) return true; // 迟到 401：整体丢弃
      this.__http.clearSession();
    }
    this.platTeardown(); return true;
  };
  page.platTeardown = function () {
    this.__platTimers.forEach(t => clearTimeout(t)); this.__platTimers = [];
    this.__platUnloaded = true;
    this.__platProjectId = ''; this.__platJobId = ''; this.__platEstimateId = '';
    this.setData({ platformMode: false, busy: false, form: {}, platformErr: '', platCases: [], platStyles: [], platResult: null, platProjects: [], platRule: null });
  };
  page.platOnUnload = function () { this.__platUnloaded = true; this.__platTimers.forEach(t => clearTimeout(t)); };
  page.platFail = msg => { page.setData({ busy: false, platformErr: msg }); };

  // ---- 灵感库（R1/R2/R4/R5；图 R8 现取）----
  page.platLoadStyles = function () {
    const gen = this.__platGen, sg = this.__http.sessionGeneration();
    api.cases.styles().then(styles => {
      if (this.platStale(gen, sg)) return;
      this.setData({ platStyles: (styles || []).map(s2 => ({ code: s2.code, label: s2.label })) });
    }).catch(() => {});
  };
  page.platLoadCases = function (reset) {
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    const f = this.data.platFilter || {};
    const params = { limit: 20 };
    if (!reset && this.data.platCursor) params.cursor = this.data.platCursor;
    if (f.styleCode) params.styleCode = f.styleCode;
    if (f.floorCount) params.floorCount = Number(f.floorCount);
    if (f.faceWidth) params.faceWidth = Number(f.faceWidth);
    if (f.depth) params.depth = Number(f.depth);
    if (this.data.keyword) params.keyword = this.data.keyword;
    if (reset) { this.setData({ platCursor: '', platCases: [], platLoading: true }); this.platLoadStyles(); }
    else this.setData({ platLoading: true });
    api.cases.page(params).then(res => {
      if (this.platStale(gen, sg)) return;
      const rows = (res && res.list || []).map(PF.caseRow);
      this.setData({
        platCases: reset ? rows : (this.data.platCases || []).concat(rows),
        platCursor: (res && res.nextCursor) || '',
        platLoading: false
      });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ platLoading: false, platformErr: PF.errorMessageDesign(err) });
    });
  };
  page.platLoadMore = function () { if (this.data.platCursor) this.platLoadCases(false); };
  page.platFilterPick = function (e) {
    const key = e.currentTarget.dataset.key, idx = Number(e.detail.value);
    const list = key === 'styleCode' ? [{ code: '', label: '全部' }].concat(this.data.platStyles) : (key === 'floorCount' ? ['', '1', '2', '3', '4'] : null);
    const value = list ? list[idx].code !== undefined ? list[idx].code : list[idx] : e.detail.value;
    this.setData({ platFilter: Object.assign({}, this.data.platFilter, { [key]: value || undefined }) });
    this.platLoadCases(true);
  };
  page.platSearchInput = function (e) { this.setData({ keyword: e.detail.value }); };
  page.platSearch = function () { this.platLoadCases(true); };
  page.platOpenCase = function (e) {
    const id = e.currentTarget.dataset.id;
    const c = (this.data.platCases || []).find(x => String(x.id) === String(id));
    if (!c) return;
    this.setData({ platCase: c, platCaseImages: [], caseTab: 0, platCaseFav: false });// 收藏回显初始化（防沿用上一案例收藏态）
    this.platShow('case');
    // 详情图现取：cover + elevation（floorPlanAssetIds 需 R3 详情——列表行只有 cover）
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    api.cases.detail(c.id).then(detail => {
      if (this.platStale(gen, sg)) return;
      const ids = [detail.coverAssetId].concat(detail.floorPlanAssetIds || []).filter(Boolean);
      this.setData({ platCaseAssetIds: ids });
      return this.platFetchImages(ids).then(urls => { if (!this.platStale(gen, sg)) this.setData({ platCaseImages: urls }); });
    }).catch(err => { if (!this.platStale(gen, sg)) this.setData({ platformErr: PF.errorMessageDesign(err) }); });
  };
  page.platFetchImages = function (assetIds) {
    const jobs = (assetIds || []).filter(Boolean).map(a => api.assets.downloadUrl(a).then(r => r.downloadUrl).catch(() => ''));
    return Promise.all(jobs);
  };
  page.platCaseTab = function (e) { this.setData({ caseTab: Number(e.currentTarget.dataset.index) }); };
  page.platFavorite = function () {
    const c = this.data.platCase; if (!c || this.data.busy || !this.platActive()) return;
    const favorite = !this.data.platCaseFav;
    const action = favorite ? 'favorite' : 'unfavorite';
    const gen = ++this.__platGen, sg = http.sessionGeneration();
    this.setData({ busy: true });
    return api.cases[action](c.id).then(() => {
      if (this.platStale(gen, sg)) return;
      this.setData({ busy: false, platCaseFav: favorite });
      wx.showToast({ title: favorite ? '已收藏（组织共享）' : '已取消收藏', icon: 'none' });
    }).catch(err => {
      if (this.platStale(gen, sg) || (err && err.silent)) return;
      this.setData({ busy: false });
      if (!this.platHandle401(err, sg)) wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' });
    });
  };

  // ---- AI 生成链（R9 → R12/R13 → R14 轮询 → R11 → R15/R16）----
  page.platProjectFingerprint = function (f) {
    return [f.width, f.depth, f.floors, f.style || '', f.note || ''].join('|');
  };
  page.platGenerate = async function (kind) {
    if (this.data.busy || !this.platActive()) return;
    const f = JSON.parse(JSON.stringify(this.data.form || {}));
    const inputs = PF.requirementInputs(f);
    if (inputs.error) return this.platFail(inputs.error);
    if (!['1', '2', '4'].includes(String(f.count))) return this.platFail('请核对候选数量');
    if (!['2K', '4K'].includes(f.resolution)) return this.platFail('请核对清晰度');
    if (kind === 'facade' && !String(f.palette || '').trim()) return this.platFail('请填写外观主色调');
    const body = { sourceType: 'SELF_UPLOAD', line: kind === 'plan' ? 'FLAT' : 'ELEVATION', requirementInputs: inputs.inputs };
    const jobBody = { count: Number(f.count), resolution: f.resolution };
    if (['LANDSCAPE', 'PORTRAIT'].includes(f.orientation)) jobBody.orientation = f.orientation;
    if (kind === 'facade') {
      if (f.referenceCaseId) { body.sourceType = 'CASE_REFERENCE'; body.refCaseId = f.referenceCaseId; }
      const refs = (f.referenceAssetIds || []).filter(Boolean);
      if (refs.length) body.requirementInputs.referenceAssetIds = refs;
      Object.assign(jobBody, { styleCode: PF.styleCode(f.style), roofType: PF.roofCode(f.roof), material: f.material, color: f.color });
      if (!jobBody.styleCode || !jobBody.roofType) return this.platFail('请选择有效风格与屋顶');
      if (!jobBody.material || !jobBody.color) return this.platFail('请选择材质与主色');
    }
    const gen = ++this.__platGen, sg = http.sessionGeneration();
    this.setData({ busy: true, platformErr: '', platResult: null, platPolling: true, platPollTip: '已提交，AI 生成中…',
      platProgress: 5, platStatusText: '排队等待中…', platAccepted: 0, platCancelResult: null,
      platEstimateText: PF.estimateText(Number(f.count)) });
    try {
      const referenceAssets = kind === 'facade' ? (f.referenceAssetIds || []).map(assetId =>
        (this.__platReferences || []).find(ref => String(ref.assetId) === String(assetId))).filter(Boolean) : undefined;
      const intent = intents.begin('generate-' + kind, { project: body, job: jobBody }, undefined, referenceAssets);
      const key = intent.record.requestKey;
      let projectId = intent.record.projectId;
      if (!projectId) {
        const proj = await api.projects.create(body, key + '-project');
        if (this.platStale(gen, sg)) return;
        projectId = String(proj.projectId);
        intents.checkpoint(intent, { projectId });
      }
      this.__platProjectId = projectId;
      this.__platProjectFp = this.platProjectFingerprint(f);
      this.__platEstimateId = '';
      this.setData({ platResultVersionId: '' });
      const createJob = kind === 'plan' ? api.projects.createFlatJob : api.projects.createElevationJob;
      const job = await createJob(projectId, jobBody, key + '-job');
      if (this.platStale(gen, sg)) return;
      intents.complete(intent);
      this.__platJobId = String(job.jobId); this.__platKind = kind;
      this.platPoll(null, gen, sg, kind, projectId);
    } catch (err) {
      if (this.platStale(gen, sg) || (err && err.silent)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false, platPolling: false });
      this.platFail(PF.errorMessageDesign(err));
    }
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
          this.platFail(String((err && err.message) || '').indexOf('未终态') >= 0 || (err && err.code) ? PF.STILL_RUNNING_MESSAGE : PF.errorMessageDesign(err));
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
      this.platFail(PF.STILL_RUNNING_MESSAGE);
    });
  };
  // ---- 取消生成（对齐旧版 1.5：QUEUED/RUNNING/VALIDATING 均可取消，退点随结算）----
  page.platCancelJob = function () {
    const jobId = this.__platJobId; if (!jobId || !this.data.platPolling || !this.platActive()) return;
    wx.showModal({ title: '取消本次生成', content: '已生成的候选会保留，未完成部分将自动退点。确定取消？', success: r => {
      if (!r.confirm || !this.platActive()) return;
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
    if (this.data.busy || !this.platActive()) return;
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
  // ---- 立面自传参考图：B 申请票据 → 票据指定对象存储 PUT → B 完成上传 ----
  page.platAddReference = function () {
    if (this.data.busy || !this.platActive()) return;
    const existing = (this.data.form.referenceAssetIds || []).filter(Boolean);
    if (existing.length >= 3) return this.platFail('自传参考最多 3 张');
    wx.chooseMedia({ count: 3 - existing.length, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], success: async r => {
      if (!this.platActive() || this.data.busy) return;
      const files = (r.tempFiles || []).slice(0, 3 - existing.length);
      if (!files.length) return;
      const gen = ++this.__platGen, sg = http.sessionGeneration();
      this.setData({ busy: true });
      try {
        const finance = require('../../../api/finance');
        const { base64ToBinary } = require('../../../api/franchise/engineering');
        const all = existing.slice();
        // 每张成功后立即更新，后一张失败不会丢掉已经上传的引用。
        for (const file of files) {
          const base64 = await finance.readFileBase64(file.tempFilePath);
          if (this.platStale(gen, sg)) return;
          const binary = base64ToBinary(base64), sha256 = finance.sha256Hex(binary);
          // 未决生成已冻结这些资源；同图重选只恢复原引用，避免另建资产破坏原请求摘要。
          const generation = intents.saved('generate-facade');
          const original = (generation && generation.referenceAssets || []).find(ref => ref.sha256 === sha256 && !all.includes(ref.assetId));
          if (original) {
            all.push(original.assetId);
            this.__platReferences = (this.__platReferences || []).concat(original);
            this.setData({ 'form.referenceAssetIds': all.slice(), platRefCount: all.length });
            continue;
          }
          const mimeType = finance.guessMime(file.tempFilePath);
          const body = { assetType: 'DESIGN_REFERENCE', mimeType, sizeBytes: binary.length, sha256 };
          const intent = intents.begin('upload-reference-' + sha256, body);
          if (intent.fresh) intents.checkpoint(intent, { uploadState: 'READY' });
          const key = intent.record.requestKey;
          let assetId = intent.record.assetId;
          if (!assetId) {
            if (intent.record.uploadState === 'EXPIRED') throw { code: 'DESIGN_UPLOAD_EXPIRED', intent, message: '上传地址已过期，图片尚未上传成功' };
            const ticket = await api.assets.uploadTicket(body, key + '-ticket');
            if (this.platStale(gen, sg)) return;
            if (!String(ticket.uploadUrl || '').startsWith('https://')) {
              throw { message: '上传通道暂不可用，请联系管理员配置安全上传地址' };
            }
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            // 先落未知态；进程退出、断线或旧版缺少阶段记录时，不能假定从未上传。
            const hadUnknownPut = intent.record.uploadState !== 'READY';
            intents.checkpoint(intent, { uploadState: 'UNKNOWN' });
            const response = await new Promise((resolve, reject) => wx.request({
              url: ticket.uploadUrl, method: 'PUT', data: bytes.buffer, timeout: 15000,
              header: { 'Content-Type': mimeType },
              success: resolve,
              fail: () => reject({ code: -1, message: '参考图上传中断，请重新选择原图片重试' })
            }));
            if (this.platStale(gen, sg)) return;
            if (!(response.statusCode >= 200 && response.statusCode < 300)) {
              // S3 兼容预签名的明确过期响应；普通 AccessDenied/403 不代表票据过期。
              const xml = typeof response.data === 'string' ? response.data : '';
              const expired = response.statusCode === 403 && xml.includes('<Code>AccessDenied</Code>') && xml.includes('<Message>Request has expired</Message>');
              if (expired && !hadUnknownPut) {
                intents.checkpoint(intent, { uploadState: 'EXPIRED' });
                throw { code: 'DESIGN_UPLOAD_EXPIRED', intent, message: '上传地址已过期，图片尚未上传成功' };
              }
              throw { message: '参考图上传结果尚未确认，请重试原上传，暂不创建新上传' };
            }
            assetId = String(ticket.assetId);
            intents.checkpoint(intent, { assetId });
          }
          const done = await api.assets.complete(assetId, key + '-complete');
          if (this.platStale(gen, sg)) return;
          if (done && done.uploadStatus === 'REJECTED') {
            intents.complete(intent);
            throw { message: '参考图未通过校验：' + (done.rejectedReason || '请更换图片') };
          }
          if (!done || done.uploadStatus !== 'ACCEPTED') throw { message: '参考图仍待审核，请稍后重新选择原图片确认' };
          all.push(String(assetId));
          this.__platReferences = (this.__platReferences || []).concat({ sha256, assetId: String(assetId) });
          this.setData({ 'form.referenceAssetIds': all.slice(), platRefCount: all.length });
          intents.complete(intent);
        }
        this.setData({ busy: false });
        wx.showToast({ title: '已添加参考图', icon: 'success' });
      } catch (err) {
        if (this.platStale(gen, sg) || (err && err.silent)) return;
        console.warn('[design] reference upload failed', err && err.code);
        this.platFail((err && err.message) || '参考图上传失败，请重试');
        if (err && err.code === 'DESIGN_UPLOAD_EXPIRED') {
          wx.showModal({ title: '上传地址已过期', content: '本次图片未上传成功。重新上传将创建新的上传记录，请重新选择原图片。',
            confirmText: '重新上传', cancelText: '暂不处理', success: choice => {
              if (!choice.confirm || this.platStale(gen, sg)) return;
              try {
                intents.complete(err.intent);
                this.platAddReference();
              } catch (failure) {
                if (!this.platStale(gen, sg)) this.platFail(PF.errorMessageDesign(failure));
              }
            }, fail: () => {
              console.warn('[design] upload restart confirmation unavailable');
            }
          });
        }
      }
    }, fail: err => {
      if (this.platActive() && !String(err.errMsg || '').includes('cancel')) {
        console.warn('[design] choose reference failed');
        this.platFail('无法选择参考图，请重试');
      }
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
    if (this.data.busy || !this.platActive()) return;
    const idx = Number(e.currentTarget.dataset.index);
    const result = this.data.platResult; if (!result || !result.candidates[idx]) return;
    const c = result.candidates[idx];
    const kind = result.kind;
    const selApi = kind === 'plan' ? api.projects.selectFlat : api.projects.selectElevation;
    const gen = ++this.__platGen, sg = http.sessionGeneration();
    this.setData({ busy: true });
    selApi(result.projectId, { jobId: result.jobId, candidateId: c.candidateId }).then(res => {
      if (this.platStale(gen, sg)) return;
      this.setData({ busy: false, 'platResult.selected': idx, platResultVersionId: res && res.resultVersionId ? String(res.resultVersionId) : (this.data.platResultVersionId || '') });
      wx.showToast({ title: '已选定该方案', icon: 'none' });
    }).catch(err => {
      if (this.platStale(gen, sg) || (err && err.silent)) return;
      this.setData({ busy: false });
      if (this.platHandle401(err, sg)) return;
      wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' });
    });
  };
  page.platRevision = function () { // R18 调整请求
    const result = this.data.platResult; if (!result || this.data.busy || !this.platActive()) return;
    wx.showModal({ title: '调整请求', editable: true, placeholderText: '请填写调整原因（必填）', success: r => {
      if (!r.confirm || !this.platActive()) return;
      const reason = (r.content || '').trim();
      if (!reason || reason.length > 500) return this.platFail('请填写调整原因（500字以内）');
      const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
      this.setData({ busy: true });
      api.projects.revision(result.projectId, { reason }).then(res => {
        if (this.platStale(gen, sg)) return;
        this.setData({ busy: false });
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
        this.platFail(PF.errorMessageDesign(err));
      });
    }});
  };

  // ---- 快速预算（R9 确保项目 → R19 → R20）----
  page.platLoadRule = function () {
    const f = this.data.form || {};
    if (!f.regionCode || !f.structureType || !f.materialGrade) return;
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    api.budget.ruleInfo(f.regionCode, f.structureType, f.materialGrade).then(data => {
      if (this.platStale(gen, sg)) return;
      this.setData({ platRule: PF.ruleInfoView(data) });
    }).catch(() => { if (!this.platStale(gen, sg)) this.setData({ platRule: { available: false, lowText: '', highText: '', ruleVersionId: '' } }); });
  };
  page.platCalculate = async function () {
    if (this.data.busy || !this.platActive()) return;
    const f = Object.assign({}, this.data.form);
    const body = PF.estimateBody(f);
    if (body.error) return this.platFail(body.error);
    const inputs = PF.requirementInputs({ width: f.width, depth: f.depth, floors: f.floors, style: f.style || '现代简约', note: '' });
    if (inputs.error) return this.platFail(inputs.error);
    const projectBody = { sourceType: 'SELF_UPLOAD', line: 'FLAT', requirementInputs: inputs.inputs };
    const projectFingerprint = intents.digest(projectBody);
    const reqBody = body.body;
    if (this.data.platResultVersionId) reqBody.resultVersionId = this.data.platResultVersionId;
    const fp = this.platProjectFingerprint({ width: f.width, depth: f.depth, floors: f.floors, style: f.style });
    const gen = ++this.__platGen, sg = http.sessionGeneration();
    this.setData({ busy: true, platformErr: '' });
    try {
      const intent = intents.begin('calculate-budget', { project: projectBody, budget: reqBody });
      const key = intent.record.requestKey;
      let projectId = !intent.record.projectFingerprint || intent.record.projectFingerprint === projectFingerprint ? intent.record.projectId : '';
      if (!projectId) {
        if (this.__platProjectId && this.__platProjectFp === fp) projectId = this.__platProjectId;
        else {
          const proj = await api.projects.create(projectBody, key + '-project');
          if (this.platStale(gen, sg)) return;
          projectId = String(proj.projectId);
        }
        intents.checkpoint(intent, { projectId, projectFingerprint });
      }
      this.__platProjectId = projectId; this.__platProjectFp = fp;
      const res = await api.budget.estimate(projectId, reqBody, key + '-budget').catch(err => {
        if (err && err.designTerminalRejected && !this.platStale(gen, sg)) intents.reject(intent);
        throw err;
      });
      if (this.platStale(gen, sg)) return;
      intents.complete(intent);
      const view = PF.estimateView(res);
      this.__platEstimateId = view.estimateId;
      this.setData({ busy: false, platBudget: view, view: 'budget-result', kind: 'budget' });
    } catch (err) {
      if (this.platStale(gen, sg) || (err && err.silent)) return;
      if (this.platHandle401(err, sg)) return;
      this.platFail(PF.errorMessageDesign(err));
    }
  };

  // ---- 保存到客户（R21；客户列表=CRM customers.page 可读集合）----
  page.platSave = function (kind) {
    if (this.data.busy || !this.platActive()) return;
    if (kind === 'plan' && !this.data.platResultVersionId) return this.platFail('请先选定平面候选');
    if (kind === 'facade' && !this.data.platResultVersionId) return this.platFail('请先选定立面候选');
    if (kind === 'budget' && !this.__platEstimateId) return this.platFail('请先完成预算测算');
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true });
    require('../../../api/franchise/crm.js').customers.page(1, 50).then(res => {
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
    if (this.data.busy || !this.platActive()) return;
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
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true });
    api.projects.saveToCustomer(this.__platProjectId, body).then(res => {
      if (this.platStale(gen, sg)) return;
      this.setData({ busy: false, sheet: false, platSaved: { customerId, kind, schemeVersion: res && res.version } });
      wx.showToast({ title: '已保存到客户方案', icon: 'success' });
    }).catch(err => {
      if (this.stale ? this.stale(gen, sg, this.__postKey || '') : this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false });
      this.platFail(PF.errorMessageDesign(err));
    });
  };
};
