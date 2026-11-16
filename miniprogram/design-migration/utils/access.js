const http = require('../../api/http');
const auth = require('../../api/auth');
const design = require('../../utils/platform-design');
function allowed(page) {
  const post = http.token() && auth.currentApiPost();
  if (post && design.pageMode(http.token(), post) === 'api') {
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
    guarded[name] = function (...args) { if (allowed(this)) return definition[name].apply(this, args); };
  });
  Page(guarded);
}
function openFeature(url) { require('./navigation').switchTab({ url }); }
module.exports = { protectedPage, openFeature };
