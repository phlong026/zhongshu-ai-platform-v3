const http = require('../../api/http');
const auth = require('../../api/auth');
const design = require('../../utils/platform-design');
function retiredDestination(page, options) {
  if (page.route === 'design-migration/pages/ai-design/index') return '/design-migration/pages/library/index';
  if (!/^design-migration\/pages\/budget\/(quick|input|parameters|prices|body|exterior)$/.test(page.route)) return '';
  const id = value => /^[1-9]\d{0,18}$/.test(String(value || ''));
  const history = '/design-migration/pages/budget/history?';
  if (id(options.projectId)) return history + 'projectId=' + options.projectId;
  return history + 'independent=1' + (id(options.draftId) ? '&draftId=' + options.draftId : '');
}
function allowed(page, options = page.options || {}) {
  const post = http.token() && auth.currentApiPost();
  if (post && design.pageMode(http.token(), post) === 'api') {
    const target = retiredDestination(page, options);
    if(target){
      page.setData({accessReady:false});
      if(!page.__retiredRedirect){page.__retiredRedirect=true;wx.redirectTo({url:target});}
      return false;
    }
    page.setData({ accessReady: true }); return true;
  }
  page.setData({ accessReady: false });
  const target = '/' + page.route + (page.options ? '?' + Object.keys(page.options).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(page.options[k])).join('&') : '');
  wx.redirectTo({ url: !http.token() ? '/pages/account/index?view=login&return=' + encodeURIComponent(target) : '/pages/account/index?view=access&content=' + encodeURIComponent('设计赋能') });
  return false;
}
function protectedPage(definition) {
  const guarded = Object.assign({}, definition, { data: Object.assign({}, definition.data, { accessReady: false }) });
  Object.keys(definition).forEach(name => {
    if (typeof definition[name] !== 'function' || ['onUnload', 'onHide'].includes(name)) return;
    guarded[name] = function (...args) { if (allowed(this, name === 'onLoad' ? args[0] : undefined)) return definition[name].apply(this, args); };
  });
  Page(guarded);
}
function openFeature(url) { require('./navigation').switchTab({ url }); }
module.exports = { protectedPage, openFeature };
