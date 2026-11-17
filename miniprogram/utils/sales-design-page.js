// AI 设计入口（纯平台态）：三类设计岗位从 B 读取 A 已发布的公开户型；收藏与业务数据仍归 B。
const ENTRY_ROLES = ['sales', 'sales-lead', 'hq-design-admin'];
const auth = require('../api/auth');
const http = require('../api/http');
const api = require('../api/franchise/design');
const PF = require('./platform-design');
const images = require('./design-case-images');

const currentPost = () => {
  try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; }
};
const allowed = post => !!post && ENTRY_ROLES.includes(post.roleCode);

module.exports=()=>({
  data: {
    entries: [
      { title: '灵感库', view: 'inspiration', icon: 'home-design-icon', hint: '从优秀方案找灵感' },
      { title: 'AI平面', view: 'ai-config', icon: 'plan-file', hint: '按家庭需求做布局' },
      { title: 'AI立面', view: 'ai-facade', icon: 'house', hint: '探索建筑外观方案' },
      { title: '快速预算', view: 'budget', icon: 'contract', hint: '快速估算建造投入' }
    ],
    entranceEnabled: false,
    catalogCases: [], catalogCount: 0,
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
      this.setData({ entranceEnabled: false, catalogCases: [], catalogCount: 0, catalogLoading: false, catalogLoaded: false, catalogError: '' });
      return;
    }
    this.setData({ entranceEnabled: true });
    if (this.__catalogKey === key && (this.data.catalogLoaded || this.data.catalogLoading)) { this.watchCatalogCovers(); return; }
    this.__catalogKey = key;
    this.loadCatalog();
  },
  onUnload() {
    this.__catalogUnloaded = true;
    this.__caseImageObserver?.disconnect();
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
    this.setData(Object.assign({ catalogLoading: true, catalogError: '' }, reset ? { catalogLoaded: false, catalogCases: [], catalogCount: 0, catalogCursor: '' } : {}));
    const params = { limit: 10 };
    if (!reset) params.cursor = this.data.catalogCursor;
    return api.cases.page(params).then(result => {
      if (!alive() || request !== this.__catalogRequest) return;
      if (params.cursor && result && result.nextCursor === params.cursor) throw Error('户型分页暂不可用，请刷新后重试');
      const existing = reset ? [] : this.data.catalogCases;
      const rows = (result && result.list || []).map(PF.caseRow).filter(row => !existing.some(item => item.id === row.id)).map(row => Object.assign({}, row, {
        coverLoading: !!row.coverAssetId, coverError: !row.coverAssetId, coverUrl: ''
      }));
      this.setData({ catalogCases: existing.concat(rows), catalogCount: existing.length + rows.length, catalogCursor: (result && result.nextCursor) || '', catalogLoading: false, catalogLoaded: true });
      this.watchCatalogCovers();

    }).catch(error => {
      if (!alive() || request !== this.__catalogRequest) return;
      this.setData({ catalogLoading: false, catalogLoaded: true, catalogError: PF.errorMessageDesign(error) });
    });
  },
  retryCatalog() { this.loadCatalog(!this.data.catalogCases.length); },
  loadMoreCatalog() { return this.loadCatalog(false); },
  onReachBottom() { if (this.data.catalogCursor && !this.data.catalogLoading) this.loadMoreCatalog(); },
  watchCatalogCovers() {
    images.watch(this,'.case-cover-watch',this.data.catalogCases,row => this.loadCatalogCover(row));
  },
  loadCatalogCover(row, force = false) {
    if (!row || !row.coverAssetId || (this.__coverFlights && this.__coverFlights.has(row.id)) || (row.coverUrl && Date.now() - row.coverLoadedAt < images.FRESH_MS && !force)) return;
    const post = currentPost(); if (!allowed(post)) return;
    const generation = this.__catalogGeneration, session = http.sessionGeneration(), postId = String(post.postId);
    const alive = () => this.catalogAlive(generation,session,postId);
    const patch = value => { if(alive()) this.setData({catalogCases:this.data.catalogCases.map(item=>item.id===row.id?Object.assign({},item,value):item)}); };
    this.__coverFlights = this.__coverFlights || new Set(); this.__coverFlights.add(row.id); patch({coverLoading:true,coverError:false});
    return images.load(row.id,row.coverAssetId,{version:row.versionId,force}).then(url=>patch({coverUrl:url,coverLoading:false,coverError:false,coverLoadedAt:Date.now()}))
      .catch(error=>{patch({coverUrl:'',coverLoading:false,coverError:true});if(alive()&&Number(error.code||error.statusCode)===401)this.setData({catalogError:PF.errorMessageDesign(error)});}).finally(()=>{this.__coverFlights.delete(row.id);});
  },
  catalogImageError(e) {
    const row = this.data.catalogCases.find(item=>String(item.id)===String(e.currentTarget.dataset.id));
    if(!row)return;
    this.setData({catalogCases:this.data.catalogCases.map(item=>item.id===row.id?Object.assign({},item,{coverUrl:'',coverError:true,coverLoading:false,_imageRetried:true}):item)});
    if (!row._imageRetried) { row._imageRetried=true; return this.loadCatalogCover(row,true); }
  },
  retryCatalogCover(e) {
    const row = this.data.catalogCases.find(item=>String(item.id)===String(e.currentTarget.dataset.id));
    if (row && !row.coverLoading) return this.loadCatalogCover(row,true);
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
