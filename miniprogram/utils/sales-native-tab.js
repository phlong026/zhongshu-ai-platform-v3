// The five sales workspaces share the existing five native Tab pages. Other
// roles retain their own workbenches; no subpackage is required for a Tab paint.
const ROUTES = { creation: '/pages/home/index', design: '/pages/engineering/index', customers: '/pages/management/index' };
function sales() {
  const http = require('../api/http');
  const post = http.token() && require('../api/auth').currentApiPost();
  return !!post && ['sales', 'sales-lead'].includes(post.roleCode);
}
function register(kind, legacy) {
  const make = {creation:require('./sales-creation-page'),design:require('./sales-design-page'),customers:require('./sales-customers-page')}[kind];
  const salesPage = make();
  const definitions = { sales: salesPage, legacy };
  const active = () => sales() ? 'sales' : 'legacy';
  const initial = active();
  const definition = { data: { ...legacy.data, ...salesPage.data, salesNative: initial === 'sales' } };
  const lifecycle = new Set(['onLoad', 'onShow', 'onHide', 'onUnload']);
  for (const name of new Set([...Object.keys(legacy), ...Object.keys(salesPage)])) {
    if (name === 'data' || lifecycle.has(name)) continue;
    if (typeof legacy[name] === 'function' || typeof salesPage[name] === 'function') {
      definition[name] = function(...args) { return definitions[this.__nativeMode || active()][name]?.apply(this, args); };
    }
  }
  function initialize(page, options) {
    const next = active();
    if (page.__nativeMode !== next) {
      if (page.__nativeMode) definitions[page.__nativeMode].onUnload?.call(page);
      // An identity change invalidates pending requests in the underlying page.
      page.__nativeMode = next;page.__unloaded=false;page.__catalogUnloaded=false;page.__closed=false;
      page.setData({ ...JSON.parse(JSON.stringify(definitions[next].data || {})), salesNative: next === 'sales' });
      definitions[next].onLoad?.call(page, options || {});
    }
  }
  definition.onLoad = function(options) { this.__nativeOptions = options || {}; initialize(this, options); };
  definition.onShow = function() {
    this.__nativeVisible=true;
    const render=()=>{
      if(!this.__nativeVisible)return;
      initialize(this,this.__nativeOptions);this.__closed=false;
      const result=definitions[this.__nativeMode].onShow?.call(this);
      if(this.__nativeMode==='sales'){const bar=this.getTabBar?.();bar?.refresh?.();bar?.setData({hidden:!!this.data.sheet});}
      return result;
    };
    // Home is also the cold-start entry: cached posts never substitute for server authorization.
    if(kind==='creation'&&sales())return require('../api/auth').restoreSession().then(render).catch(error=>{
      if(this.__nativeVisible&&!error.silent&&error.code!==401)wx.showToast({title:'登录状态暂未确认，请重试',icon:'none'});
    });
    return render();
  };
  definition.onHide = function() {
    this.__nativeVisible=false;
    const d = definitions[this.__nativeMode || active()]; d.onHide?.call(this);
    if (this.__nativeMode === 'sales' && kind === 'creation') {
      this.__closed = true; this.__parsePoll?.cancel?.();
      this.setData({ extracting: false });
    }
    this.cancelSearch?.();
  };
  definition.onUnload = function() { this.__nativeVisible=false;return definitions[this.__nativeMode || active()].onUnload?.call(this); };
  definition.onNativeEntry = function(options) {
    if (this.__nativeMode !== 'sales') return;
    salesPage.onLoad?.call(this, options || {});
    salesPage.onShow?.call(this);
  };
  Page(definition);
  return definition;
}
function compatibility(kind, make) {
  const d = make(), original = d.onLoad;
  d.onLoad = function(options = {}) {
    if (!sales()) return original?.call(this, options);
    this.__compatRedirect = true;
    wx.switchTab({ url: ROUTES[kind], success: () => getCurrentPages().slice(-1)[0]?.onNativeEntry?.(options),
      fail: () => wx.showToast({ title: '页面打开失败，请重试', icon: 'none' }) });
  };
  const show = d.onShow;
  d.onShow = function() { if (!this.__compatRedirect) return show?.call(this); };
  Page(d);
}
module.exports = { register, compatibility, sales, ROUTES };
