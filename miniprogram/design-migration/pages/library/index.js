const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';

const { openFeature, protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const assets = require('../../utils/assets');
const format = require('../../utils/format');
const classifications = require('../../utils/case-classifications');
const coverImages = require('../../../utils/design-case-images');
const SEARCH_DELAY_MS = 280;

function cancelSearchTimer(page) {
  if (page._searchTimer != null) clearTimeout(page._searchTimer);
  page._searchTimer = null;
}

protectedPage({
  data: {
    cases: [], visibleCases: [], visibleCount: 0, caseCount: 0, cursor: null, hasMore: true, loading: false, loaded: false, error: '',
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
  onShow() { if(this.data.cases.length) this.watchCovers(); },
  onUnload() {
    this.__caseImageObserver?.disconnect();
    cancelSearchTimer(this);
    this._unloaded = true;
    this._listSeq = (this._listSeq || 0) + 1;
  },
  onReachBottom() { if (this.data.hasMore && !this.data.loading && !this.data.error) this.loadPage(false); },
  retry() { return this.loadPage(this._retryReset !== false); },
  loadPage(reset) {
    if (this.data.loading && !reset) return;
    if (reset) {
      cancelSearchTimer(this);
      this._coverFlights = new Set();
    }
    this._retryReset = !!reset;
    const seq = this._listSeq = (this._listSeq || 0) + (reset ? 1 : 0);
    const self = this;
    const session = http.captureSession ? http.captureSession() : http.getToken();
    const current = () => seq === self._listSeq && !self._unloaded && (http.isSameSession ? http.isSameSession(session) : session === http.getToken());
    const cursor = reset ? null : this.data.cursor;
    self.setData(Object.assign({ loading: true, error: '' }, reset
      ? { cases: [], visibleCases: [], visibleCount: 0, caseCount: 0, cursor: null, hasMore: true, loaded: false } : {}));
    const filters = this.data.filters;
    const faceWidth = parseFloat(filters.faceWidth);
    const depth = parseFloat(filters.depth);
    const params = {
      cursor: cursor, limit: 10,
      keyword: (this.data.keyword || '').trim() || undefined,
      sourceType: filters.sourceType || undefined,
      styleCode: filters.styleCode || undefined,
      floorCount: filters.floorCount || undefined,
      // 面宽/进深 ±1m 尺度筛选：后端按 [输入值-1, 输入值+1] 闭区间检索
      faceWidth: isNaN(faceWidth) ? undefined : faceWidth,
      depth: isNaN(depth) ? undefined : depth
    };
    return api.listCases(params).then(function (page) {
      if (!current()) return;
      const newCases = (page.list || []).map(function (c) {
        const tag = format.styleLabel(c.styleCode);
        return {
          id: c.caseId, title: c.title, area: c.buildingArea,
          floors: c.floorCount, style: c.styleCode, tag: tag,
          coverAssetId: c.coverAssetId, versionId: c.caseVersionId || c.version || '', image: '', imageLoading: !!c.coverAssetId, imageError: false,
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
      self.watchCovers();

    }).catch(function (error) {
      if (!current()) return;
      self.setData({ loading: false, error: String(error && (error.msg || error.message) || '方案加载失败，请重试') });
    });
  },
  refreshVisible() {
    const cases = this.data.cases.map(c => {
      const style = this.data.styleOptions.find(option => option.code === c.style);
      const tag = style ? style.label : c.tag;
      return Object.assign({}, c, { tag, meta: (c.area ? c.area + '㎡' : '') + (c.floors ? ' · ' + c.floors + '层' : '') + (tag ? ' · ' + tag : '') });
    });
    this.setData({ visibleCases: cases, visibleCount: cases.length, caseCount: cases.length });
  },
  updateCover(id, patch) {
    this.setData({ cases: this.data.cases.map(row => row.id === id ? Object.assign({}, row, patch) : row) });
    this.refreshVisible();
  },
  watchCovers() {
    coverImages.watch(this,'.case-cover-watch',this.data.visibleCases,row=>this.loadCover(row));
  },
  loadCover(row,force=false) {
    if(!row || !row.coverAssetId || (this._coverFlights && this._coverFlights.has(row.id)) || (!force && row.image && Date.now()-row.imageLoadedAt<coverImages.FRESH_MS))return;
    const seq=this._listSeq,session=http.captureSession();
    const current=()=>!this._unloaded&&seq===this._listSeq&&http.isSameSession(session);
    const flights = this._coverFlights = this._coverFlights || new Set();
    flights.add(row.id);
    this.updateCover(row.id,{imageLoading:true,imageError:false});
    return assets.fetchCaseAssetDataUrl(row.id,row.coverAssetId,{rendition:'COVER',version:row.versionId,force}).then(url=>{
      if(current())this.updateCover(row.id,{image:url,imageLoading:false,imageError:false,imageLoadedAt:Date.now()});
    }).catch(()=>{if(current())this.updateCover(row.id,{image:'',imageLoading:false,imageError:true});}).finally(()=>flights.delete(row.id));
  },
  imageError(e) {
    const row=this.data.cases.find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(!row)return;
    this.updateCover(row.id,{image:'',imageLoading:false,imageError:true});
    if(!row.imageRetried){this.updateCover(row.id,{imageRetried:true});return this.loadCover(row,true);}
  },
  retryCover(e) { return this.loadCover(this.data.cases.find(r=>String(r.id)===String(e.currentTarget.dataset.id)),true); },
  onSearch(e) {
    const keyword = String(e.detail.value || '');
    const previous = (this.data.keyword || '').trim();
    this.setData({ keyword });
    if (keyword.trim() === previous) return;
    // Discard the old cursor and in-flight results as soon as a new query is typed.
    cancelSearchTimer(this);
    this._listSeq = (this._listSeq || 0) + 1;
    this._retryReset = true;
    this.setData({ loading: true, error: '', loaded: false, cases: [], visibleCases: [],
      visibleCount: 0, caseCount: 0, cursor: null, hasMore: true });
    const session = http.captureSession();
    this._searchTimer = setTimeout(() => {
      this._searchTimer = null;
      if (!this._unloaded && http.isSameSession(session)) this.loadPage(true);
    }, SEARCH_DELAY_MS);
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
