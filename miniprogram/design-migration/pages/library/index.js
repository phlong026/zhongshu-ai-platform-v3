const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';

const { openFeature, protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const format = require('../../utils/format');
const classifications = require('../../utils/case-classifications');

protectedPage({
  data: {
    cases: [], visibleCases: [], cursor: null, hasMore: true, loading: false, loaded: false, error: '',
    keyword: '', source: 0,
    filters: { sourceType: 'COMPANY', styleCode: '', floorCount: null, faceWidth: '', depth: '' },
    styleOptions: [{ label: '全部', code: '' }], classificationError: '',
    floorOptions: [{ label: '全部', count: null }].concat(classifications.floors())
  },
  onLoad() { this.loadClassifications(); this.loadPage(true); },
  loadClassifications() {
    const seq = this._classificationSeq = (this._classificationSeq || 0) + 1;
    const session = http.captureSession();
    this.setData({ classificationError: '' });
    return classifications.styles().then(styles => {
      if (this._unloaded || seq !== this._classificationSeq || !http.isSameSession(session)) return;
      this.setData({ styleOptions: [{ label: '全部', code: '' }].concat(styles) });
      this.refreshVisible();
    }).catch(() => {
      if (!this._unloaded && seq === this._classificationSeq && http.isSameSession(session))
        this.setData({ classificationError: '风格加载失败，点击重试' });
    });
  },
  onUnload() { this._unloaded = true; this._listSeq = (this._listSeq || 0) + 1; },
  onReachBottom() { if (this.data.hasMore && !this.data.loading && !this.data.error) this.loadPage(false); },
  retry() { return this.loadPage(this._retryReset !== false); },
  loadPage(reset) {
    if (this.data.loading && !reset) return;
    this._retryReset = !!reset;
    const seq = this._listSeq = (this._listSeq || 0) + (reset ? 1 : 0);
    const self = this;
    const session = http.captureSession ? http.captureSession() : http.getToken();
    const current = () => seq === self._listSeq && !self._unloaded && (http.isSameSession ? http.isSameSession(session) : session === http.getToken());
    const cursor = reset ? null : this.data.cursor;
    self.setData(Object.assign({ loading: true, error: '' }, reset
      ? { cases: [], visibleCases: [], cursor: null, hasMore: true, loaded: false } : {}));
    const filters = this.data.filters;
    const faceWidth = parseFloat(filters.faceWidth);
    const depth = parseFloat(filters.depth);
    const params = {
      cursor: cursor, limit: 10,
      sourceType: filters.sourceType || undefined,
      styleCode: filters.styleCode || undefined,
      floorCount: filters.floorCount || undefined,
      // 面宽/进深 ±1m 尺度筛选：后端按 [输入值-1, 输入值+1] 闭区间检索
      faceWidth: isNaN(faceWidth) ? undefined : faceWidth,
      depth: isNaN(depth) ? undefined : depth
    };
    return api.listCases(params).then(function (page) {
      if (!current()) return;
      const startIndex = reset ? 0 : self.data.cases.length;
      const newCases = (page.list || []).map(function (c) {
        const tag = format.styleLabel(c.styleCode);
        return {
          id: c.caseId, title: c.title, area: c.buildingArea,
          floors: c.floorCount, style: c.styleCode, tag: tag,
          coverAssetId: c.coverAssetId, image: '', imageLoading: !!c.coverAssetId, imageError: false,
          meta: (c.buildingArea ? c.buildingArea + '㎡' : '') + (c.floorCount ? ' · ' + c.floorCount + '层' : '') + (tag ? ' · ' + tag : '')
        };
      });
      self.setData({
        cases: reset ? newCases : self.data.cases.concat(newCases),
        cursor: page.nextCursor,
        hasMore: !!page.nextCursor,
        loading: false, loaded: true, error: ''
      });
      self.refreshVisible();
      let nextCover = 0;
      const loadCover = function () {
        if (!current() || nextCover >= newCases.length) return Promise.resolve();
        const i = nextCover++, item = newCases[i];
        if (!item.coverAssetId) return loadCover();
        return assets.fetchCaseAssetDataUrl(item.id,item.coverAssetId).then(function (url) {
          // 回调期间可能已切换筛选/翻页：仅在索引仍有效且同一封面时回写
          if (!current()) return;
          const coverTarget = self.data.cases[startIndex + i];
          if (!coverTarget || coverTarget.id !== item.id || coverTarget.coverAssetId !== item.coverAssetId) return;
          const cases = self.data.cases.map((row, index) => index === startIndex + i ? Object.assign({}, row, { image: url, imageLoading: false }) : row);
          self.setData({ cases });
          self.refreshVisible();
        }).catch(function () {
          if (current()) self.updateCover(item.id, { imageLoading: false, imageError: true });
        }).then(loadCover);
      };
      return Promise.all(Array.from({ length: Math.min(3, newCases.length) }, loadCover));
    }).catch(function (error) {
      if (!current()) return;
      self.setData({ loading: false, error: String(error && (error.msg || error.message) || '方案加载失败，请重试') });
    });
  },
  refreshVisible() {
    const keyword = (this.data.keyword || '').trim();
    const cases = this.data.cases.map(c => {
      const style = this.data.styleOptions.find(option => option.code === c.style);
      const tag = style ? style.label : c.tag;
      return Object.assign({}, c, { tag, meta: (c.area ? c.area + '㎡' : '') + (c.floors ? ' · ' + c.floors + '层' : '') + (tag ? ' · ' + tag : '') });
    });
    const visible = !keyword ? cases : cases.filter(function (c) {
      return (c.title || '').indexOf(keyword) >= 0
        || (c.tag || '').indexOf(keyword) >= 0
        || String(c.area || '').indexOf(keyword) >= 0;
    });
    this.setData({ visibleCases: visible });
  },
  updateCover(id, patch) {
    this.setData({ cases: this.data.cases.map(row => row.id === id ? Object.assign({}, row, patch) : row) });
    this.refreshVisible();
  },
  imageError(e) { this.updateCover(e.currentTarget.dataset.id, { image: '', imageLoading: false, imageError: true }); },
  retryCover(e) {
    const id = e.currentTarget.dataset.id, row = this.data.cases.find(item => item.id === id);
    if (!row || !row.coverAssetId || row.imageLoading) return;
    const seq = this._listSeq, session = http.captureSession();
    const current = () => !this._unloaded && seq === this._listSeq && http.isSameSession(session);
    this.updateCover(id, { imageLoading: true, imageError: false });
    return assets.fetchCaseAssetDataUrl(id, row.coverAssetId).then(url => {
      if (current()) this.updateCover(id, { image: url, imageLoading: false });
    }).catch(() => { if (current()) this.updateCover(id, { imageLoading: false, imageError: true }); });
  },
  onSearch(e) {
    this.setData({ keyword: e.detail.value });
    this.refreshVisible();
  },
  selectSource(e) {
    const index = Number(e.currentTarget.dataset.index);
    if (index === this.data.source) return;
    this.setData({
      source: index,
      filters: Object.assign({}, this.data.filters, { sourceType: index === 1 ? 'AI' : 'COMPANY' }),
      cases: [], cursor: null, hasMore: true
    });
    this.loadPage(true);
  },
  onFilterChange(e) {
    const key = e.currentTarget.dataset.key;
    const value = e.currentTarget.dataset.value;
    if (value === undefined || this.data.filters[key] === value) return;
    // styleCode 用 ''、floorCount 用 null 表示「全部」，与选项数组严格相等以维持选中态
    this.setData({
      filters: Object.assign({}, this.data.filters, { [key]: value }),
      cases: [], cursor: null, hasMore: true
    });
    this.loadPage(true);
  },
  // 面宽/进深输入：输入过程只回写不查询，失焦/确认后按当前输入重新检索，避免逐字符打接口
  onDimensionInput(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ ['filters.' + key]: e.detail.value });
  },
  onDimensionSearch() {
    this.setData({ cases: [], cursor: null, hasMore: true });
    this.loadPage(true);
  },
  onDimensionClear(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ ['filters.' + key]: '', cases: [], cursor: null, hasMore: true });
    this.loadPage(true);
  },
  openDetail(e) { openFeature('/design-migration/pages/library/detail?id=' + e.currentTarget.dataset.id); }
});
