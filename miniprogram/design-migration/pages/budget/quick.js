const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const drafts = require('../../utils/budget-draft');
const selection = require('../../utils/budget-selection');
const view = require('../../utils/budget-view');
const QUANTITIES = [
  ['DOOR_HOUSEHOLDS', '入户门', '户'], ['WINDOW_AREA', '窗面积', '㎡'], ['WALL_PAINT_AREA', '外墙漆核定面积', '㎡'],
  ['CULTURE_STONE_LENGTH', '文化石长度', '米'], ['LIGHTING_WASHER_LENGTH', '洗墙灯长度', '米'], ['LIGHTING_STRIP_LENGTH', '灯带长度', '米'],
  ['LIGHTING_WALL_LAMP_COUNT', '壁灯数量', '个'], ['WATERPROOF_AREA', '天沟露台防水', '㎡'], ['INSULATED_WATERPROOF_AREA', '平顶保温防水', '㎡']
];
const key = prefix => prefix + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12);
protectedPage({
  data: { draft: null, draftRows: [], draftNames: [], draftCursor: null, loading: false, busy: false, error: '', notice: '', storageError: '',
    values: {}, overrides: {}, regions: [], regionNames: [], regionName: '请选择', floorNames: Array.from({ length: 20 }, (_, i) => (i + 1) + '层'),
    quantityRows: [], configCells: [], catalogReady: false, catalogLoading: false, catalogError: '', dirty: false, localConflict: false,
    projects: [], projectNames: [], projectCursor: null, projectsLoaded: false },
  onLoad(options) {
    this._requestedId = options.draftId == null ? null : drafts.id(options.draftId);
    if (options.draftId != null && !this._requestedId) { this.setData({ error: '草稿编号无效' }); return; }
    this.load();
  },
  onShow() { if (this._scope && this._scope !== drafts.sessionScope()) this.load(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._scope = null; },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._scope && this._scope === drafts.sessionScope()) return true;
    this._seq++;
    this.resetContext();
    this.setData({ loading: false, busy: false, error: '登录状态已更新，请重新进入' });
    return false;
  },
  cacheKey(id) { return 'zs_ind_budget_v1:' + this._scope + ':' + id; },
  resetContext() {
    this._conflictingLocal = null; this._catalog = []; this._catalogRegion = null;
    this.setData({ draft: null, draftRows: [], draftNames: [], draftCursor: null, values: {}, overrides: {}, quantityRows: [], configCells: [],
      projects: [], projectNames: [], projectCursor: null, projectsLoaded: false, regions: [], regionNames: [], regionName: '请选择',
      dirty: false, localConflict: false, storageError: '', notice: '', catalogReady: false, catalogLoading: false, catalogError: '' });
  },
  canReplaceDraft() {
    if (!this.data.draft || !this.data.dirty || this.persist()) return true;
    this.setData({ error: '修改尚未保存，暂时不能切换或新建草稿。请先点击保存草稿，成功后再继续。' });
    return false;
  },
  async load() {
    const scope = drafts.sessionScope();
    // Account changes must clear private state even when local storage is unavailable.
    if (this._scope && this._scope === scope && (this.data.busy || !this.canReplaceDraft())) return;
    const seq = this._seq = (this._seq || 0) + 1;
    this._scope = scope; this.resetContext();
    this.setData({ busy: false, loading: true, error: '' });
    if (!this._scope) { this.current(seq); return; }
    try {
      const [rows, platform, mine] = await Promise.all([api.getBudgetDrafts(), api.getBudgetRegions(), api.listMyRegions()]);
      if (!this.current(seq)) return;
      if (!Array.isArray(rows) || !Array.isArray(platform) || !Array.isArray(mine)) throw Error('预算资料读取失败');
      const regions = platform.concat(mine.map(r => ({ ...r, custom: true })));
      this.setData({ regions, regionNames: regions.map(r => r.name), loading: false });
      this.applyRows(rows);
      if (this._requestedId) await this.openDraft(this._requestedId);
      else if (rows.length) await this.openDraft(rows[0].draftId);
      else await this.newDraft();
    } catch (e) { if (this.current(seq)) this.setData({ loading: false, error: view.errorText(e) }); }
  },
  applyRows(rows) {
    this.setData({ draftRows: rows, draftNames: rows.map(d => d.name + ' · ' + d.draftId.slice(-6)), draftCursor: rows.length && rows.length % 20 === 0 ? rows.at(-1).draftId : null });
  },
  async moreDrafts() {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draftCursor) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    try { const rows = await api.getBudgetDrafts(this.data.draftCursor); if (this.current(seq)) { this.applyRows(this.data.draftRows.concat(rows)); this.setData({ draftCursor: rows.length === 20 ? rows.at(-1).draftId : null }); } }
    catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  },
  async newDraft() {
    if (!this.current(this._seq) || this.data.busy || !this.canReplaceDraft()) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    try {
      const pendingKey = this.cacheKey('create');
      const requestKey = wx.getStorageSync(pendingKey) || key('draft'); wx.setStorageSync(pendingKey, requestKey);
      const response = await api.createBudgetDraft(requestKey);
      if (!this.current(seq)) return;
      this.acceptDraft(response, false); wx.removeStorageSync(pendingKey);
      this.applyRows([response, ...this.data.draftRows.filter(d => d.draftId !== response.draftId)]);
    } catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  },
  async openDraft(id) {
    if (!this.current(this._seq) || this.data.busy || !this.canReplaceDraft()) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    try { const response = await api.getBudgetDraft(id); if (this.current(seq)) { if (response.draftId !== id) throw Error('草稿响应不匹配'); this.acceptDraft(response, true); } }
    catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  },
  switchDraft(event) { const row = this.data.draftRows[Number(event.detail.value)]; if (row) return this.openDraft(row.draftId); },
  acceptDraft(response, restoreLocal) {
    if (!drafts.id(response && response.draftId) || !Number.isInteger(response.version) || !response.state || !Array.isArray(response.state.optionIds)) throw Error('草稿数据不完整');
    let draft = JSON.parse(JSON.stringify(response)), dirty = false, notice = '';
    this._conflictingLocal = null;
    if (restoreLocal) {
      const saved = wx.getStorageSync(this.cacheKey(draft.draftId));
      if (saved && saved.version === draft.version && saved.state && Array.isArray(saved.state.optionIds)) { draft.state = saved.state; dirty = !!saved.dirty; }
      else if (saved && saved.dirty) { this._conflictingLocal = saved; notice = '账号草稿已有更新，本机未保存内容仍保留；请选择如何继续。'; }
    }
    this._catalog = []; this._catalogRegion = null;
    this.setData({ draft, dirty, notice, localConflict: !!this._conflictingLocal, storageError: '', catalogReady: false, catalogError: '', error: '' });
    this.render(); this.loadCatalog();
  },
  render() {
    const d = this.data.draft; if (!d) return;
    const overrides = d.state.inputOverrides || {};
    const values = drafts.values({ imported: d.importedValues || {}, importedSources: d.sources || {}, overrides });
    const region = this.data.regions.find(r => r.code === values.regionCode);
    const quantities = overrides.quantities || {};
    const configCells = [];
    (this._catalog || []).forEach(item => selection.groupChoices(item, { selections: d.state.optionIds }).forEach(group => {
      configCells.push({ key: item.itemId + '|' + group.name, itemId: item.itemId, group: group.name,
        label: (item.name ? item.name + ' · ' : '') + group.label,
        range: ['暂不选择'].concat(group.options.map(option => option.displayLabel)),
        optionIds: group.options.map(option => option.optionId),
        index: Math.max(0, group.options.findIndex(option => option.selected) + 1) });
    }));
    this.setData({ values, overrides, regionName: region ? region.name : '请选择',
      quantityRows: QUANTITIES.map(([code, label, unit]) => ({ code, label, unit, value: quantities[code] || '' })),
      configCells });
  },
  resolveLocal(event) {
    if (!this.current(this._seq) || this.data.busy || !this._conflictingLocal || !this.data.draft) return;
    const local = this._conflictingLocal;
    this._conflictingLocal = null;
    if (event.currentTarget.dataset.choice === 'local') {
      this.setData({ draft: { ...this.data.draft, state: local.state }, dirty: true, localConflict: false, notice: '已恢复本机修改，请核对后保存为账号草稿的新版本' });
    } else this.setData({ localConflict: false, notice: '继续使用账号最新内容' });
    this.render(); this.persist(); this.loadCatalog();
  },
  persist() {
    if (this._conflictingLocal) return false; // Do not overwrite the only copy of unsaved edits.
    try { const d = this.data.draft; wx.setStorageSync(this.cacheKey(d.draftId), { version: d.version, state: d.state, dirty: this.data.dirty }); this.setData({ storageError: '' }); return true; }
    catch (e) { this.setData({ storageError: '本机暂存失败，请先保存草稿；未发起收费测算' }); return false; }
  },
  edit(event) {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft) return;
    const field = event.currentTarget.dataset.field;
    if (!['footprintArea', 'roofArea', 'faceWidth', 'depth', 'buildingArea'].includes(field)) return;
    this.setOverride(field, event.detail.value || null);
  },
  setOverride(field, value) {
    const d = this.data.draft;
    const state = { ...d.state, inputOverrides: { ...d.state.inputOverrides, [field]: value } };
    if (field === 'regionCode') { state.optionIds = []; this._catalog = []; this.setData({ notice: '地区已改变，请重新选择预算配置', catalogReady: false }); }
    this.setData({ draft: { ...d, state }, dirty: true }); this.render(); this.persist();
    if (field === 'regionCode') this.loadCatalog();
  },
  region(event) { if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft) return; const r = this.data.regions[Number(event.detail.value)]; if (r) this.setOverride('regionCode', r.code); },
  floor(event) { if (this.current(this._seq) && !this.data.busy && !this.data.localConflict && this.data.draft) this.setOverride('floorCount', Number(event.detail.value) + 1); },
  quantity(event) {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft) return;
    const code = event.currentTarget.dataset.code; if (!QUANTITIES.some(row => row[0] === code)) return;
    this.setOverride('quantities', { ...(this.data.draft.state.inputOverrides.quantities || {}), [code]: event.detail.value || null });
  },
  async loadCatalog() {
    if (!this.current(this._seq) || !this.data.draft) return;
    const region = this.data.values.regionCode;
    const request = this._catalogRequest = (this._catalogRequest || 0) + 1, seq = this._seq, id = this.data.draft.draftId;
    this.setData({ catalogReady: false, catalogLoading: false, catalogError: '' });
    if (!region) return;
    this.setData({ catalogLoading: true });
    try {
      const raw = await api.getBudgetOptions(region);
      if (!this.current(seq) || request !== this._catalogRequest || !this.data.draft || id !== this.data.draft.draftId || region !== this.data.values.regionCode) return;
      this._catalog = selection.catalog(raw); this._catalogRegion = region;
      const before = this.data.draft.state.optionIds;
      const selected = selection.reconcile({ selections: before }, this._catalog).selections;
      this.setData({ draft: { ...this.data.draft, state: { ...this.data.draft.state, optionIds: selected } }, catalogLoading: false, catalogReady: true,
        dirty: this.data.dirty || selected.length !== before.length, ...(selected.length !== before.length ? { notice: '失效选项已移除，请核对后保存' } : {}) });
      this.render(); this.persist();
    } catch (e) { if (this.current(seq) && request === this._catalogRequest) this.setData({ catalogLoading: false, catalogError: view.errorText(e) }); }
  },
  choose(event) {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.catalogReady) return;
    const cell = (this.data.configCells || []).find(c => c.key === event.currentTarget.dataset.key);
    const d = this.data.draft;
    if (!cell || !d) return;
    const idx = Number(event.detail.value);
    const value = idx > 0 ? (cell.optionIds[idx - 1] || null) : null;
    const next = selection.choose({ selections: d.state.optionIds }, this._catalog, cell.itemId, cell.group, value);
    this.setData({ draft: { ...d, state: { ...d.state, optionIds: next.selections } }, dirty: true }); this.render(); this.persist();
  },
  renameDraft(event) {
    if (!this.current(this._seq) || this.data.busy || !this.data.draft) return;
    const name = String(event.detail.value || '').trim();
    const d = this.data.draft;
    if (!name || name === d.name) return;
    const seq = this._seq, id = d.draftId;
    this.setData({ busy: true, error: '' });
    api.renameBudgetDraft(id, name).then(updated => {
      if (!this.current(seq) || !this.data.draft || this.data.draft.draftId !== id) return;
      // 改名响应携带服务端存量 state，可能落后于本地未保存编辑；只合并名称，绝不整体替换草稿。
      if (!updated || updated.draftId !== id || updated.name !== name) throw Error('改名响应不匹配');
      this.setData({ draft: { ...this.data.draft, name: updated.name }, busy: false });
      this.applyRows(this.data.draftRows.map(row => row.draftId === id ? { ...row, name: updated.name } : row));
    }).catch(e => { if (this.current(seq)) this.setData({ busy: false, error: view.errorText(e) }); });
  },
  async saveCurrent() {
    const d = this.data.draft, seq = this._seq;
    if (!this.data.dirty) return d;
    const saved = await api.saveBudgetDraft(d.draftId, d.version, d.state);
    if (!this.current(seq)) throw { cancelled: true };
    if (saved.draftId !== d.draftId) throw Error('保存草稿响应不匹配');
    this.setData({ draft: saved, dirty: false }); this.render(); this.persist(); return saved;
  },
  async save() {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    try { await this.saveCurrent(); if (this.current(seq)) wx.showToast({ title: '草稿已保存', icon: 'success' }); }
    catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  },
  async loadProjects() {
    if (!this.current(this._seq) || this.data.busy || (this.data.projectsLoaded && !this.data.projectCursor)) return;
    const seq = this._seq; this.setData({ busy: true, error: '' });
    try { const page = await api.listProjects(this.data.projectsLoaded ? this.data.projectCursor : null, 20);
      if (this.current(seq)) { const projects = this.data.projects.concat(page.list); this.setData({ projects, projectNames: projects.map(p => p.name || '项目 ' + p.projectId), projectCursor: page.nextCursor, projectsLoaded: true }); }
    } catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  },
  importProject(event) {
    const project = this.data.projects[Number(event.detail.value)];
    if (!project || !this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft) return;
    const seq = this._seq, d = this.data.draft;
    this.setData({ busy: true, error: '' });
    wx.showModal({ title: '导入项目参数副本', content: '将替换本草稿的基础参数和配置。原项目、已生成预算及历史金额不受影响。',
      success: async result => {
        try { if (!this.current(seq) || !result.confirm) return;
          const imported = await api.importBudgetDraft(d.draftId, d.version, project.projectId, null);
          if (this.current(seq)) { if (imported.draftId !== d.draftId) throw Error('导入草稿响应不匹配'); this.acceptDraft(imported, false); this.persist(); }
        } catch (e) { if (this.current(seq)) this.setData({ error: view.errorText(e) }); }
        finally { if (this.current(seq)) this.setData({ busy: false }); }
      }, fail: () => { if (this.current(seq)) this.setData({ busy: false, error: '确认窗口未打开，草稿未替换' }); } });
  },
  history() { if (this.current(this._seq) && !this.data.busy) wx.navigateTo({ url: '/design-migration/pages/budget/history?independent=1' }); },
  async generate() {
    if (!this.current(this._seq) || this.data.busy || this.data.localConflict || !this.data.draft || !this.data.catalogReady || this._catalogRegion !== this.data.values.regionCode) return;
    const seq = this._seq; let attemptKey; this.setData({ busy: true, error: '' });
    try {
      const d = await this.saveCurrent(); if (!this.current(seq)) return;
      attemptKey = this.cacheKey(d.draftId + ':attempt');
      let attempt = wx.getStorageSync(attemptKey);
      if (!attempt || attempt.version !== d.version) {
        const quote = await api.getBudgetPointQuote(); if (!this.current(seq)) return;
        if (!quote || !quote.companyPrice || quote.companyPrice.subject !== 'budget_estimate' || !Number.isSafeInteger(quote.pointCost) || quote.pointCost < 1) throw Error('预算积分报价不可用');
        const confirmed = await new Promise((resolve, reject) => wx.showModal({ title: '确认预算测算', content: '成功测算扣除 ' + quote.pointCost + ' 公司积分。缺价或缺量仍会显示待补，不会按0元计算。', success: r => resolve(r.confirm), fail: reject }));
        if (!this.current(seq) || !confirmed) return;
        attempt = { version: d.version, key: key('ind-budget'), body: { expectedVersion: d.version, companyPrice: quote.companyPrice } };
        wx.setStorageSync(attemptKey, attempt); // Must persist before any charged request.
      }
      const response = await api.createIndependentBudget(d.draftId, attempt.body, attempt.key);
      if (!this.current(seq)) return;
      if (!response || !drafts.id(response.budgetId) || response.draftId !== d.draftId || response.projectId !== null) throw Error('预算响应不匹配，请重试读取同一笔测算');
      wx.navigateTo({ url: '/design-migration/pages/budget/result?budgetId=' + response.budgetId,
        success: () => { if (this.current(seq)) wx.removeStorageSync(attemptKey); },
        fail: () => { if (this.current(seq)) this.setData({ error: '预算已生成，页面未打开；重试将读取同一预算' }); } });
    } catch (e) { if (this.current(seq) && !e.cancelled) {
      if (e.code === 'DESIGN_PRICE_CHANGED' && attemptKey) { wx.removeStorageSync(attemptKey); this.setData({ error: '计价规则已更新，请再次测算并确认新价格' }); }
      else this.setData({ error: view.errorText(e) });
    } }
    finally { if (this.current(seq)) this.setData({ busy: false }); }
  }
});
