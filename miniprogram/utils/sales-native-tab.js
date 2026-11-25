// Sales creation and customers reuse native Tab pages. Other roles retain
// their own workbenches; no subpackage is required for a Tab paint.
const navigation = require('./page-navigation');
const ROUTES = navigation.salesTabs;
function sales() {
  const http = require('../api/http');
  const post = http.token() && require('../api/auth').currentApiPost();
  return !!post && ['sales', 'sales-lead'].includes(post.roleCode);
}
function register(kind, legacy) {
  const make = {creation:require('./sales-creation-page'),customers:require('./sales-customers-page')}[kind];
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
    this.__nativeVisible=true;this.__nativeReady=false;this.__nativeScope=navigation.scope();this.__nativeAuthorization=null;
    const render=()=>{
      if(!this.__nativeVisible)return;
      initialize(this,this.__nativeOptions);this.__closed=false;
      const entry=this.__nativeMode==='sales'?navigation.takeTabEntry(ROUTES[kind]):null;
      if(entry&&kind!=='creation')salesPage.applyEntryIntent?.call(this,entry);
      const result=definitions[this.__nativeMode].onShow?.call(this);
      // Creation refreshes the actor's draft in onShow before opening its editor.
      if(entry&&kind==='creation')salesPage.applyEntryIntent?.call(this,entry);
      this.__nativeScope=navigation.scope();this.__nativeReady=this.__nativeMode==='sales';
      if(this.__nativeMode==='sales'){const bar=this.getTabBar?.();bar?.refresh?.();bar?.setData({hidden:!!this.data.sheet});}
      return result;
    };
    // Home is also the cold-start entry: cached posts never substitute for server authorization.
    if(kind==='creation'&&sales()){
      const authorization=this.__nativeAuthorization={scope:this.__nativeScope};
      return require('../api/auth').restoreSession().then(()=>{
        if(this.__nativeAuthorization===authorization)return render();
      }).catch(error=>{
        if(this.__nativeAuthorization===authorization&&this.__nativeVisible&&!error.silent&&error.code!==401)wx.showToast({title:'登录状态暂未确认，请重试',icon:'none'});
      }).finally(()=>{if(this.__nativeAuthorization===authorization)this.__nativeAuthorization=null;});
    }
    return render();
  };
  definition.onHide = function() {
    this.__nativeVisible=false;this.__nativeReady=false;this.__nativeAuthorization=null;navigation.clearTabEntry(ROUTES[kind],this.__nativeScope,this);
    const d = definitions[this.__nativeMode || active()]; d.onHide?.call(this);
    if (this.__nativeMode === 'sales' && kind === 'creation') {
      this.__closed = true; this.__parsePoll?.cancel?.();
      this.setData({ extracting: false });
    }
    this.cancelSearch?.();
  };
  definition.onUnload = function() { this.__nativeVisible=false;this.__nativeReady=false;this.__nativeAuthorization=null;navigation.clearTabEntry(ROUTES[kind],this.__nativeScope,this);return definitions[this.__nativeMode || active()].onUnload?.call(this); };
  definition.acceptNavigationEntry = function() {
    if(!this.__nativeVisible||this.__nativeMode!=='sales'||!sales())return false;
    if(this.__nativeScope!==navigation.scope())return false;
    if(!this.__nativeReady)return this.__nativeAuthorization?.scope===this.__nativeScope;
    const entry=navigation.takeTabEntry(ROUTES[kind]);
    if(entry)salesPage.applyEntryIntent?.call(this,entry,true);
    return true;
  };
  Page(definition);
  return definition;
}
function compatibility(kind, make) {
  const d = make(), original = d.onLoad;
  d.onLoad = function(options = {}) {
    if (!sales()) return original?.call(this, options);
    this.__compatRedirect = true;
    const query=Object.keys(options).filter(key=>['focus','intent','editor','section'].includes(key)).map(key=>key+'='+encodeURIComponent(options[key])).join('&');
    return navigation.open('/pages/'+kind+'/index'+(query?'?'+query:''),{replace:true});
  };
  const show = d.onShow;
  d.onShow = function() { if (!this.__compatRedirect) return show?.call(this); };
  Page(d);
}
module.exports = { register, compatibility, sales, ROUTES };
