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
    const id = e.currentTarget.dataset.id;
    const c = (this.data.platCases || []).find(x => String(x.id) === String(id));
    if (!c) return;
    const kind = this.data.pickingRef || 'facade';
    this.setData({ 'form.referenceCaseId': c.id, pickingRef: '' });
    this.platShow(kind === 'plan' ? 'ai-config' : 'ai-facade');
  };
  page.platShow = function (view) {
    const kind = view.startsWith('facade') || view === 'ai-facade' ? 'facade' : view.startsWith('budget') ? 'budget' : 'plan';
    const titles = { 'ai-config': 'AI平面', 'ai-facade': 'AI立面', budget: '快速预算', 'plan-result': '平面方案', 'facade-result': '立面方案', 'budget-result': '预算结果', inspiration: '灵感库', search: '平面检索', case: '灵感详情' };
    this.setData({ view, title: titles[view] || view, kind });
    if (view === 'inspiration' || view === 'search') { if (!(this.data.platCases || []).length) this.platLoadCases(true); }
    if (view === 'budget') this.platLoadRule();
  };

  // ---- 基础：命令快照 / 乱序三元组 / 401 ----
  page.__platGen = 0; page.__platKeys = {}; page.__platTimers = [];
  page.platStale = function (gen, sessionGen) {
    return this.__platUnloaded || gen !== this.__platGen || sessionGen !== http.sessionGeneration() ||
      String((this.__post || {}).postId) !== String(this.__postKey || '');
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
    this.__platTimers.forEach(t => clearTimeout(t)); this.__platTimers = [];
    this.__platKeys = {};
    this.setData({ platformMode: false, platformErr: '', platCases: [], platStyles: [], platResult: null, platProjects: [], platRule: null });
  };
  page.platOnUnload = function () { this.__platUnloaded = true; this.__platTimers.forEach(t => clearTimeout(t)); };
  page.platFail = msg => { page.setData({ busy: false, platformErr: msg }); };

  // ---- 灵感库（R1/R2/R4/R5；图 R8 现取）----
  page.platLoadStyles = function () {
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
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
      // GET 幂等读：最后派发者胜（连续加载的旧响应覆盖无害）；写命令仍走 platStale 严格守卫
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
    this.setData({ platCase: c, platCaseImages: [], caseTab: 0 });
    this.platShow('case');
    // 详情图现取：cover + elevation（floorPlanAssetIds 需 R3 详情——列表行只有 cover）
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    api.cases.detail(c.id).then(detail => {
      if (this.platStale(gen, sg)) return;
      const ids = [detail.coverAssetId].concat(detail.floorPlanAssetIds || []).filter(Boolean);
      this.setData({ platCaseAssetIds: ids });
      return this.platFetchImages(ids).then(urls => this.setData({ platCaseImages: urls }));
    }).catch(err => { if (!this.platStale(gen, sg)) this.setData({ platformErr: PF.errorMessageDesign(err) }); });
  };
  page.platFetchImages = function (assetIds) {
    const jobs = (assetIds || []).filter(Boolean).map(a => api.assets.downloadUrl(a).then(r => r.downloadUrl).catch(() => ''));
    return Promise.all(jobs);
  };
  page.platCaseTab = function (e) { this.setData({ caseTab: Number(e.currentTarget.dataset.index) }); };
  page.platFavorite = function () {
    const c = this.data.platCase; if (!c) return;
    const key = this.data.platCaseFav ? 'unfavorite' : 'favorite';
    api.cases[key](c.id).then(() => {
      this.setData({ platCaseFav: !this.data.platCaseFav });
      wx.showToast({ title: this.data.platCaseFav ? '已收藏（组织共享）' : '已取消收藏', icon: 'none' });
    }).catch(err => { if (!(err && err.code === 401)) wx.showToast({ title: PF.errorMessageDesign(err), icon: 'none' }); });
  };

  // ---- AI 生成链（R9 → R12/R13 → R14 轮询 → R11 → R15/R16）----
  page.platGenerate = function (kind) {
    if (this.data.busy) return;
    const f = this.data.form || {};
    const inputs = PF.requirementInputs(f);
    if (inputs.error) return this.platFail(inputs.error);
    if (!['1', '2', '4'].includes(String(f.count))) return this.platFail('请核对候选数量');
    if (!['2K', '4K'].includes(f.resolution)) return this.platFail('请核对清晰度');
    if (kind === 'facade' && !String(f.palette || '').trim()) return this.platFail('请填写外观主色调');
    const line = kind === 'plan' ? 'FLAT' : 'ELEVATION';
    const orientation = f.orientation; // PORTRAIT/LANDSCAPE 或空（走后端默认）
    const gen = ++this.__platGen, sg = this.__http.sessionGeneration();
    this.setData({ busy: true, platformErr: '', platResult: null, platPolling: true, platPollTip: '已提交，AI 生成中…' });
    const body = { sourceType: 'SELF_UPLOAD', line, requirementInputs: inputs.inputs };
    if (kind === 'facade' && f.referenceCaseId) { body.sourceType = 'CASE_REFERENCE'; body.refCaseId = f.referenceCaseId; }
    const createKey = this.platKey('proj-' + kind);
    api.projects.create(body, createKey).then(proj => {
      this.platDropKey('proj-' + kind);
      if (this.platStale(gen, sg)) return;
      const projectId = String(proj.projectId);
      this.__platProjectId = projectId;
      const jobKey = this.platKey(kind === 'plan' ? 'flat' : 'elev');
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
        this.platDropKey(kind === 'plan' ? 'flat' : 'elev');
        if (this.platStale(gen, sg)) return;
        this.__platJobId = String(job.jobId);
        this.platPoll(jobId => String(job.jobId) === String(this.__platJobId) ? this.__platJobId : this.__platJobId, gen, sg, kind, projectId);
      });
    }).catch(err => {
      if (this.platStale(gen, sg)) return;
      if (this.platHandle401(err, sg)) return;
      this.setData({ busy: false, platPolling: false });
      if ((err && err.code) !== -1) this.platDropKey(createKey);
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
        if (decision.action === 'continue') { this.__platTimers.push(setTimeout(tick, PF.POLL_INTERVAL_MS)); return; }
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
            this.setData({ platResult: { kind, jobId: this.__platJobId, projectId, candidates, selected: 0 }, view: kind === 'plan' ? 'plan-result' : 'facade-result' });
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
    if (this.__platProjectId) return Promise.resolve(this.__platProjectId);
    const inputs = PF.requirementInputs({ width: f.width, depth: f.depth, floors: f.floors, style: f.style || '现代简约', note: '' });
    if (inputs.error) return Promise.reject({ code: 0, message: inputs.error });
    const key = this.platKey('proj-budget');
    return api.projects.create({ sourceType: 'SELF_UPLOAD', line: 'FLAT', requirementInputs: inputs.inputs }, key)
      .then(proj => { this.__platProjectId = String(proj.projectId); return this.__platProjectId; });
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
        this.setData({ busy: false, platBudget: view, view: 'budget-result' });
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
    const kindMap = { plan: 'PLAN', facade: 'FACADE', budget: 'BUDGET' };
    const kind = kindMap[this.__platSaveKind || 'plan'] || 'PLAN';
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
