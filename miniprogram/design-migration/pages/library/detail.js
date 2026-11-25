const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';

const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const assets = require('../../utils/assets');
const format = require('../../utils/format');
const http = require('../../utils/request');

protectedPage({
  data: {
    caseDetail: null, loading: true, tab: 0, favorite: false, favoriteLoading: false, favoriteSaving: false, favoriteError: false,
    drawings: [], hero: { assetId: '', url: '', status: 'missing' }, detailError: false, pickMode: false
  },
  onLoad(options) {
    this._token = http.captureSession ? http.captureSession() : http.getToken(); this._closed = false;
    this.setData({ pickMode: options && options.pick === 'workflow' });
    const caseId = options && options.id;
    this._caseId = caseId;
    if (!caseId) {
      wx.showToast({ title: '请从户型库选择案例', icon: 'none' });
      wx.navigateBack({ fail: function () { navigation.switchTab({ url: '/design-migration/pages/library/index' }); } });
      return;
    }
    this.loadDetail(caseId);
    this.loadFavoriteState(caseId);
  },
  onUnload() { this._closed = true; },
  current() { return !this._closed && !!this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()); },
  loadDetail(caseId) {
    const self = this;
    const sequence = this._detailSequence = (this._detailSequence || 0) + 1;
    this.setData({ loading: true, detailError: false });
    return api.getCaseDetail(caseId).then(function (detail) {
      if (!self.current() || sequence !== self._detailSequence) return;
      const parameters = detail.parameters || {};
      const style = format.styleLabel(parameters.styleCode);
      self.setData({
        loading: false,
        caseDetail: {
          id: detail.caseId, caseVersionId: detail.caseVersionId, dimensionBasis: detail.dimensionBasis,
          title: detail.title, description: detail.description,
          sourceLabel: (detail.sourceType === 'AI' ? 'AI案例' : '公司案例') + (style ? ' · ' + style : ''),
          meta: (parameters.buildingArea || '—') + 'm² / ' + (parameters.floorCount || '—') + '层 / 面宽'
            + (parameters.faceWidth || '—') + 'm / 进深' + (parameters.depth || '—') + 'm',
          canDesign: (detail.allowedActions || []).includes('DESIGN_WITH'),
          designUnavailableReason: detail.designUnavailableReason || '该案例暂无有效的设计参考授权',
          // 尺寸作为参考元数据传递；T26-07 绑定版本的选择保留 AI 页中已填写的输入。
          faceWidth: parameters.faceWidth,
          depth: parameters.depth,
          floorCount: parameters.floorCount,
          buildingArea: parameters.buildingArea,
          styleCode: parameters.styleCode, regionCode: parameters.regionCode,
          footprintArea: parameters.footprintArea, roofArea: parameters.roofArea,
          coverAssetId: detail.coverAssetId,
          floorPlanAssetIds: detail.floorPlanAssetIds || [],
          elevationAssetId: detail.elevationAssetId
        }
      });
      self.loadTabImages(detail, sequence);
    }).catch(function () {
      if (!self.current() || sequence !== self._detailSequence) return;
      self.setData({ loading: false, detailError: true });
      wx.showToast({ title: '加载失败', icon: 'none' });
    });
  },
  retryDetail() { return this.loadDetail(this._caseId); },
  loadTabImages(detail, sequence) {
    const plans = Array.isArray(detail.floorPlans) ? detail.floorPlans
      : (detail.floorPlanAssetIds || []).map(assetId => ({ assetId }));
    const drawings = [{ assetId: detail.elevationAssetId, label: '立面效果' }].concat(plans.map((plan, index) => ({
      assetId: plan.assetId,
      label: detail.sourceType !== 'AI' && plan.floorNo != null ? plan.floorNo + '层平面'
        : plans.length === 1 ? '平面方案' : '平面图' + (index + 1)
    }))).map(item => Object.assign(item, { url: '', status: item.assetId ? 'loading' : 'missing' }));
    const hero = { assetId: detail.coverAssetId, url: '', status: detail.coverAssetId ? 'loading' : 'missing' };
    this.setData({ drawings, hero, tab: 0 });
    if(drawings[0])this.loadImage('drawings[0]',drawings[0].assetId,sequence);
  },
  updateImage(path, patch) {
    if (path === 'hero') return this.setData({ hero: Object.assign({}, this.data.hero, patch) });
    const match = /^drawings\[(\d+)\]$/.exec(path);
    if (match) this.setData({ drawings: this.data.drawings.map((item, index) => index === Number(match[1]) ? Object.assign({}, item, patch) : item) });
  },
  loadImage(path, assetId, sequence, force = false) {
    if (!assetId) return;
    this.updateImage(path, { status: 'loading' });
    return assets.fetchCaseAssetDataUrl(this._caseId,assetId,{version:this.data.caseDetail.caseVersionId,rendition:'PREVIEW',force}).then(url => {
      if (this.current() && sequence === this._detailSequence)
        this.updateImage(path, { url, status: 'ready' });
    }).catch(() => {
      if (this.current() && sequence === this._detailSequence) this.updateImage(path, { status: 'error' });
    });
  },
  retryImage(e) {
    const index = e.currentTarget.dataset.index;
    const item = index === 'hero' ? this.data.hero : this.data.drawings[Number(index)];
    if (item && item.status === 'error') return this.loadImage(index === 'hero' ? 'hero' : 'drawings[' + Number(index) + ']', item.assetId, this._detailSequence);
  },
  imageError(e) {
    const index = e.currentTarget.dataset.index;
    this.updateImage(index === 'hero' ? 'hero' : 'drawings[' + Number(index) + ']', { status: 'error' });
  },
  loadFavoriteState(caseId, cursor) {
    const self = this;
    if (!self.current()) return;
    if (!cursor) this._favoriteCursors = new Set();
    self.setData({ favoriteLoading: true });
    return api.listFavorites(cursor || null, 50).then(function (page) {
      if (!self.current()) return;
      if (!page || !Array.isArray(page.list)) throw Error('收藏数据异常');
      const ids = page.list.map(function (c) { return String(c.caseId); });
      if (ids.includes(String(caseId))) self.setData({ favorite: true, favoriteLoading: false });
      else if (page.nextCursor) {
        if (self._favoriteCursors.has(page.nextCursor)) throw Error('收藏分页异常');
        self._favoriteCursors.add(page.nextCursor);
        return self.loadFavoriteState(caseId, page.nextCursor);
      }
      else self.setData({ favorite: false, favoriteLoading: false });
    }).catch(function () { if (self.current()) self.setData({ favoriteLoading: false, favoriteError: true }); });
  },
  selectTab(e) {
    const tab=Number(e.currentTarget.dataset.index),item=this.data.drawings[tab];if(!item)return;
    this.setData({tab});if(!item.url)this.loadImage('drawings['+tab+']',item.assetId,this._detailSequence);
  },
  previewLibraryImage() {
    const item=this.data.drawings[this.data.tab];if(!item||!item.assetId||this._previewLoading)return;
    this._previewLoading=true;wx.showLoading({title:'加载高清图'});
    return assets.fetchCaseAssetDataUrl(this._caseId,item.assetId,{version:this.data.caseDetail.caseVersionId,rendition:'ORIGINAL'})
      .then(url=>{if(this.current())assets.previewImages([url],url);})
      .catch(()=>{if(this.current())wx.showToast({title:'高清图加载失败，请重试',icon:'none'});})
      .finally(()=>{this._previewLoading=false;wx.hideLoading();});
  },
  toggleFavorite() {
    const detail = this.data.caseDetail;
    if (!this.current() || !detail || this.data.favoriteLoading || this.data.favoriteSaving) return;
    if (this.data.favoriteError) { this.setData({ favoriteError: false }); this.loadFavoriteState(detail.id); wx.showToast({ title: '正在重新确认收藏状态，请稍后重试', icon: 'none' }); return; }
    const next = !this.data.favorite;
    const self = this;
    self.setData({ favoriteSaving: true });
    return (next ? api.favorite(detail.id) : api.unfavorite(detail.id)).then(function (saved) {
      if (!self.current()) return;
      if (saved !== true) throw Error('收藏状态未保存，请重试');
      self.setData({ favorite: next });
      wx.showToast({ title: next ? '已收藏' : '已取消收藏', icon: 'success' });
    }).catch(function (err) {
      if (self.current()) wx.showToast({ title: (err && (err.msg || err.message)) || '操作失败', icon: 'none' });
    }).then(function () { if (self.current()) self.setData({ favoriteSaving: false }); });
  },
  chooseReference() {
    const detail = this.data.caseDetail;
    if (!this.data.pickMode || !this.current() || !detail) return;
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel();
    if (!channel || typeof channel.emit !== 'function') return wx.showToast({ title: '请从客户流程选择户型', icon: 'none' });
    channel.emit('workflowReference', { caseId: String(detail.id), title: detail.title });
    wx.navigateBack({ delta: 2 });
  },
  useHouse() {wx.showToast({title:require('../../../utils/design-retirement').message,icon:'none'});},
  quickBudget() {
    const detail=this.data.caseDetail;if(!this.current()||!detail)return;
    getDesignApp().globalData.budgetCaseEntry={caseId:detail.id,caseVersionId:detail.caseVersionId,title:detail.title,values:require('../../utils/case-entry').parameters(detail)};
    wx.navigateTo({url:'/design-migration/pages/budget/quick?fromCase=1'});
  }
});
