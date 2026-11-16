const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const draftStore = require('../../utils/budget-draft');

protectedPage({
  data: { loading: false, error: '', draft: null, values: {}, regions: [], regionNames: [], regionIndex: -1, regionName: '请选择',
    // 层数口径与设计输入端一致（1~4 层）；超出范围的历史草稿由草稿校验拦截
    floorNames: ['1层', '2层', '3层', '4层'], floorIndex: -1,
    errors: {}, needsReview: false, storageError: '', saving: false, quantities: {} },
  onLoad(options) {
    this._projectId = draftStore.id(options.projectId);
    this._versionId = options.resultVersionId == null ? null : draftStore.id(options.resultVersionId);
    if (!this._projectId || (options.resultVersionId != null && !this._versionId)) {
      this._projectId = null;
      this.setData({ error: '请从设计项目的快速预算页进入' });
      return;
    }
    this.load();
  },
  onShow() {
    if (this._sessionScope && this._sessionScope !== draftStore.sessionScope()) {
      this.invalidateSession();
      this.load();
    }
  },
  onUnload() { this._requestId = (this._requestId || 0) + 1; this._sessionScope = null; },
  load() {
    if (this.data.loading || !this._projectId) return;
    const scope = draftStore.sessionScope();
    if (!scope) { this.invalidateSession(); return; }
    if (this._sessionScope !== scope) this.invalidateSession();
    this._sessionScope = scope;
    const requestId = this._requestId = (this._requestId || 0) + 1;
    this.setData({ loading: true, error: '', regionsError: '' });
    const regionRequest = Promise.all([api.getBudgetRegions(), api.listMyRegions().catch(() => [])])
      .then(([platform, mine]) => ({
        regions: platform.concat((mine || []).map(item => Object.assign({ custom: true }, item)))
      }))
      .catch(() => ({ regions: [], regionsError: '地区加载失败，请重试' }));
    Promise.all([api.getBudgetInputs(this._projectId, this._versionId), regionRequest]).then(([response, regionResult]) => {
      if (!this.acceptResponse(scope, requestId)) return;
      if (response.projectId !== this._projectId || (response.resultVersionId || null) !== this._versionId) throw new Error('项目参数不匹配');
      let draft = draftStore.create(response);
      let storageError = '';
      try { draft = draftStore.read(this._projectId, this._versionId) || draft; draftStore.write(draft); }
      catch (error) { storageError = '修改暂时无法保存，请重试'; }
      // 页面级撤销快照：进入本页（或会话重建）时的草稿状态，撤销=整体写回
      this._snapshot = JSON.parse(JSON.stringify(draft));
      this.setData(Object.assign({ loading: false, storageError, regionNames: regionResult.regions.map(item => item.name) }, regionResult));
      this.renderDraft(draft);
    }).catch(error => {
      if (this.acceptResponse(scope, requestId)) this.setData({ loading: false, error: (error && (error.msg || error.message)) || '参数加载失败，请重试' });
    });
  },
  invalidateSession() {
    this._requestId = (this._requestId || 0) + 1;
    this.setData({ loading: false, saving: false, draft: null, values: {}, regions: [], regionNames: [], regionIndex: -1,
      regionName: '请选择', floorIndex: -1, errors: {}, needsReview: false, storageError: '', regionsError: '',
      error: '登录状态已更新，请重新进入本页' });
  },
  acceptResponse(scope, requestId) {
    if (requestId !== this._requestId) return false;
    if (!scope || scope !== draftStore.sessionScope()) { this.invalidateSession(); return false; }
    return true;
  },
  currentSession() {
    if (!this._sessionScope || this._sessionScope !== draftStore.sessionScope()) { this.invalidateSession(); return false; }
    return true;
  },
  renderDraft(draft) {
    if (!this.currentSession() || draft.sessionScope !== this._sessionScope) return;
    const values = draftStore.values(draft);
    const regionIndex = this.data.regions.findIndex(item => item.code === values.regionCode);
    const region = regionIndex >= 0 ? this.data.regions[regionIndex] : null;
    this.setData({ draft, values, regionIndex, regionName: region ? region.name : values.regionCode ? '原地区已不可选' : '请选择',
      regionCustom: !!(region && region.custom),
      floorIndex: values.floorCount ? Number(values.floorCount) - 1 : -1,
      quantities: Object.assign({}, draft.imported && draft.imported.quantities, draft.overrides && draft.overrides.quantities),
      needsReview: draftStore.validate(draft, this.data.regions).needsReview });
  },
  // ---- 工程量（选填）：AI 需求不含工程量时预算只能"待核定"；手填或按经验值预填后即可算出完整总价 ----
  onQuantityInput(event) {
    if (!this.currentSession() || !this.data.draft) return;
    const key = event.currentTarget.dataset.key;
    const current = Object.assign({}, (this.data.draft.overrides && this.data.draft.overrides.quantities) || {});
    const value = String(event.detail.value || '').trim();
    if (value) current[key] = value; else delete current[key];
    if (!Object.keys(current).length) {
      // 全清空时恢复为无覆盖（与"未填"语义一致）
      const overrides = Object.assign({}, this.data.draft.overrides);
      delete overrides.quantities;
      this.updateDraft(Object.assign({}, this.data.draft, { overrides }));
      this.setData({ quantities: Object.assign({}, this.data.draft.imported && this.data.draft.imported.quantities) });
      return;
    }
    this.updateDraft(draftStore.edit(this.data.draft, 'quantities', current));
    this.setData({ quantities: Object.assign({}, current) });
  },
  prefillQuantities() {
    if (!this.currentSession() || !this.data.draft) return;
    const values = draftStore.values(this.data.draft);
    const roof = values.roofArea || '';
    const preset = { DOOR_HOUSEHOLDS: '1', CULTURE_STONE_LENGTH: '44', LIGHTING_WASHER_LENGTH: '20', LIGHTING_STRIP_LENGTH: '30' };
    if (roof) preset.WATERPROOF_AREA = roof;
    this.updateDraft(draftStore.edit(this.data.draft, 'quantities', preset));
    this.setData({ quantities: Object.assign({}, preset) });
    wx.showToast({ title: '已按经验值预填，可手改', icon: 'none' });
  },
  // 新增自定义地区（仅自己可见）：命名后立即并入地区列表并选用；价格可在「我的当地单价」按当地行情填写
  addRegion() {
    if (!this.currentSession()) return;
    const self = this;
    wx.showModal({
      title: '新增自定义地区',
      editable: true,
      placeholderText: '如：黄冈、信阳（1~20 字，仅自己可见）',
      success(res) {
        if (!res.confirm) return;
        const name = String(res.content || '').trim();
        if (!name) { wx.showToast({ title: '请填写地区名称', icon: 'none' }); return; }
        if (name.length > 20) { wx.showToast({ title: '地区名称不能超过 20 字', icon: 'none' }); return; }
        api.createMyRegion(name).then(region => {
          if (!self.currentSession()) return;
          let regions = self.data.regions, index = regions.findIndex(item => item.code === region.code);
          if (index < 0) { regions = regions.concat([Object.assign({ custom: true }, region)]); index = regions.length - 1; }
          self.setData({ regions, regionNames: regions.map(item => item.name), regionIndex: index,
            regionName: regions[index].name, regionCustom: true, regionsError: '' });
          if (self.data.draft) {
            const changed = region.code !== draftStore.values(self.data.draft).regionCode;
            self.updateDraft(draftStore.edit(self.data.draft, 'regionCode', region.code));
            if (changed) wx.showToast({ title: '已选用，请重新选择预算配置并填写当地单价', icon: 'none' });
          } else wx.showToast({ title: '已添加自定义地区', icon: 'none' });
        }).catch(error => wx.showToast({ title: (error && (error.msg || error.message)) || '添加失败，请重试', icon: 'none' }));
      }
    });
  },
  updateDraft(draft) {
    if (!this.currentSession() || draft.sessionScope !== this._sessionScope) return;
    let storageError = '';
    try { draftStore.write(draft); }
    catch (error) { storageError = '修改尚未保存：手机存储异常，请重试'; }
    this.setData({ storageError, errors: {} });
    this.renderDraft(draft);
  },
  onAreaInput(event) { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.edit(this.data.draft, event.currentTarget.dataset.field, event.detail.value)); },
  // 屋顶面积设计流程不采集：一键按当前占地面积填充（平屋顶常规假设），仍可手改
  copyFootprint() {
    if (!this.currentSession() || !this.data.draft) return;
    const footprint = draftStore.values(this.data.draft).footprintArea;
    if (footprint) this.updateDraft(draftStore.edit(this.data.draft, 'roofArea', footprint));
  },
  onFloorChange(event) { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.edit(this.data.draft, 'floorCount', Number(event.detail.value) + 1)); },
  onRegionChange(event) {
    if (!this.currentSession() || !this.data.draft) return;
    const region = this.data.regions[Number(event.detail.value)];
    if (region) {
      const changed = region.code !== draftStore.values(this.data.draft).regionCode;
      this.updateDraft(draftStore.edit(this.data.draft, 'regionCode', region.code));
      if (changed) wx.showToast({ title: '地区已变更，请重新选择预算配置', icon: 'none' });
    }
  },
  restore() { if (this.currentSession() && this.data.draft) this.updateDraft(draftStore.restore(this.data.draft)); },
  // 撤销本次修改：写回进入本页时的快照（编辑即时落草稿，这是唯一的整页回滚手段）
  revert() {
    if (!this.currentSession() || !this._snapshot) return;
    this.updateDraft(JSON.parse(JSON.stringify(this._snapshot)));
    wx.showToast({ title: '已恢复进入本页时的参数', icon: 'none' });
  },
  // T14：跳转我的当地单价（把基准价调成当地成本价，跟账号永久生效）
  openMyPrices() {
    const regionCode = this.data.draft ? draftStore.values(this.data.draft).regionCode : '';
    wx.navigateTo({ url: '/design-migration/pages/budget/prices' + (regionCode ? '?regionCode=' + encodeURIComponent(regionCode) : '') });
  },
  save() {
    if (!this.currentSession() || this.data.saving || !this.data.draft) return;
    const validation = draftStore.validate(this.data.draft, this.data.regions);
    this.setData({ errors: validation.errors, needsReview: validation.needsReview });
    if (!validation.valid) { wx.showToast({ title: '请检查基础参数', icon: 'none' }); return; }
    this.setData({ saving: true });
    try { draftStore.write(this.data.draft); }
    catch (error) { this.setData({ saving: false, storageError: '参数未保存：手机存储异常，请重试' }); return; }
    // Commits local parameter editing, not a backend budget save.
    wx.navigateBack({ fail: () => wx.redirectTo({ url: '/design-migration/pages/budget/input' + draftStore.query(this.data.draft) }),
      complete: () => this.setData({ saving: false }) });
  },
});
