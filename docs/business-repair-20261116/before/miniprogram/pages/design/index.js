// AI 设计入口（纯平台态）：三类设计岗位从 B 读取 A 已发布的公开户型；收藏与业务数据仍归 B。
const ENTRY_ROLES = ['sales', 'sales-lead', 'hq-design-admin'];
const auth = require('../../api/auth');
const http = require('../../api/http');
const api = require('../../api/franchise/design');
const PF = require('../../utils/platform-design');

const currentPost = () => {
  try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; }
};
const allowed = post => !!post && ENTRY_ROLES.includes(post.roleCode);

Page({
  data: {
    entries: [
      { title: '灵感库', view: 'inspiration', icon: 'home-design-icon', hint: '从优秀方案找灵感' },
      { title: 'AI平面', view: 'ai-config', icon: 'plan-file', hint: '按家庭需求做布局' },
      { title: 'AI立面', view: 'ai-facade', icon: 'house', hint: '探索建筑外观方案' },
      { title: '快速预算', view: 'budget', icon: 'contract', hint: '快速估算建造投入' }
    ],
    entranceEnabled: false,
    catalogCases: [],
    catalogCursor: '',
    catalogLoading: false,
    catalogLoaded: false,
    catalogError: ''
  },
  onShow() {
    const post = currentPost();
    const enabled = allowed(post);
    const key = enabled ? [http.sessionGeneration(), post.postId].join(':') : '';
    if (!enabled) {
      this.__catalogGeneration = (this.__catalogGeneration || 0) + 1;
      this.__catalogKey = '';
      this.setData({ entranceEnabled: false, catalogCases: [], catalogLoading: false, catalogLoaded: false, catalogError: '' });
      return;
    }
    this.setData({ entranceEnabled: true });
    if (this.__catalogKey === key && (this.data.catalogLoaded || this.data.catalogLoading)) return;
    this.__catalogKey = key;
    this.loadCatalog();
  },
  onUnload() {
    this.__catalogUnloaded = true;
    this.__catalogGeneration = (this.__catalogGeneration || 0) + 1;
  },
  catalogAlive(generation, sessionGeneration, postId) {
    const post = currentPost();
    return !this.__catalogUnloaded && generation === this.__catalogGeneration &&
      sessionGeneration === http.sessionGeneration() && !!post && String(post.postId) === String(postId);
  },
  loadCatalog(reset = true) {
    const post = currentPost();
    if (!allowed(post)) return;
    if (!reset && (this.data.catalogLoading || !this.data.catalogCursor)) return;
    if (reset) this.__catalogGeneration = (this.__catalogGeneration || 0) + 1;
    const generation = this.__catalogGeneration;
    const request = this.__catalogRequest = (this.__catalogRequest || 0) + 1;
    const sessionGeneration = http.sessionGeneration();
    const postId = String(post.postId);
    const alive = () => this.catalogAlive(generation, sessionGeneration, postId);
    this.setData(Object.assign({ catalogLoading: true, catalogError: '' }, reset ? { catalogLoaded: false, catalogCases: [], catalogCursor: '' } : {}));
    const params = { limit: 10 };
    if (!reset) params.cursor = this.data.catalogCursor;
    return api.cases.page(params).then(result => {
      if (!alive() || request !== this.__catalogRequest) return;
      if (params.cursor && result && result.nextCursor === params.cursor) throw Error('户型分页暂不可用，请刷新后重试');
      const existing = reset ? [] : this.data.catalogCases;
      const rows = (result && result.list || []).map(PF.caseRow).filter(row => !existing.some(item => item.id === row.id)).map(row => Object.assign({}, row, {
        coverLoading: !!row.coverAssetId, coverError: !row.coverAssetId, coverUrl: ''
      }));
      this.setData({ catalogCases: existing.concat(rows), catalogCursor: (result && result.nextCursor) || '', catalogLoading: false, catalogLoaded: true });
      return PF.loadCaseCovers(rows, (caseId, assetId) => api.cases.downloadUrl(caseId, assetId), alive).then(covered => {
        if (alive()) this.setData({ catalogCases: this.data.catalogCases.map(row => covered.find(item => item.id === row.id) || row) });
      });
    }).catch(error => {
      if (!alive() || request !== this.__catalogRequest) return;
      this.setData({ catalogLoading: false, catalogLoaded: true, catalogError: PF.errorMessageDesign(error) });
    });
  },
  retryCatalog() { this.loadCatalog(!this.data.catalogCases.length); },
  loadMoreCatalog() { return this.loadCatalog(false); },
  onReachBottom() { if (this.data.catalogCursor && !this.data.catalogLoading) this.loadMoreCatalog(); },
  catalogImageError(e) {
    const id = String(e.currentTarget.dataset.id);
    this.setData({ catalogCases: (this.data.catalogCases || []).map(row => String(row.id) === id ?
      Object.assign({}, row, { coverLoading: false, coverError: true, coverUrl: '' }) : row) });
  },
  retryCatalogCover(e) {
    const id = String(e.currentTarget.dataset.id);
    const row = (this.data.catalogCases || []).find(item => String(item.id) === id);
    const post = currentPost();
    if (!row || !row.coverAssetId || row.coverLoading || !allowed(post)) return;
    const generation = this.__catalogGeneration;
    const sessionGeneration = http.sessionGeneration();
    const postId = String(post.postId);
    const alive = () => this.catalogAlive(generation, sessionGeneration, postId);
    this.setData({ catalogCases: this.data.catalogCases.map(item => String(item.id) === id ?
      Object.assign({}, item, { coverLoading: true, coverError: false, coverUrl: '' }) : item) });
    PF.loadCaseCovers([row], (caseId, assetId) => api.cases.downloadUrl(caseId, assetId), alive).then(covered => {
      if (!alive() || !covered[0]) return;
      this.setData({ catalogCases: this.data.catalogCases.map(item => String(item.id) === id ? covered[0] : item) });
    }).catch(error => {
      if (!alive()) return;
      this.setData({
        catalogCases: this.data.catalogCases.map(item => String(item.id) === id ?
          Object.assign({}, item, { coverLoading: false, coverError: true, coverUrl: '' }) : item),
        catalogError: PF.errorMessageDesign(error)
      });
    });
  },
  open(e) {
    const post = currentPost();
    if (!allowed(post)) return;
    const view = e.currentTarget.dataset.view;
    if (!this.data.entries.some(item => item.view === view)) return;
    wx.navigateTo({ url: '/pages/workflow/explore/index?view=' + view });
  },
  openCatalog() {
    if (allowed(currentPost())) wx.navigateTo({ url: '/pages/workflow/explore/index?view=inspiration' });
  },
  openCatalogCase(e) {
    if (!allowed(currentPost())) return;
    const id = String(e.currentTarget.dataset.id || '');
    if (id) wx.navigateTo({ url: '/pages/workflow/explore/index?view=case&id=' + encodeURIComponent(id) });
  }
});
