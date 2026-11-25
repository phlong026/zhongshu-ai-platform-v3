const http = require('../../api/http');
const auth = require('../../api/auth');
const apiScope = require('../../utils/api-scope');
const O = require('../../utils/organization');

function current() {
  const post = http.token() && auth.currentApiPost();
  if (!post || !O.headquartersReader(post) || String(post.postId) !== String(http.currentPostId())
      || String(post.userId) !== http.currentUserId() || String(post.tenantId) !== String(http.tenantId())) return null;
  return post;
}
function start(page) {
  page._hqHidden = false; page._hqEpoch = (page._hqEpoch || 0) + 1;
  page._hqScope = apiScope(); page._hqContext = null;
  return ticket(page);
}
function ticket(page) { return { epoch: page._hqEpoch, scope: page._hqScope }; }
function alive(page, captured) {
  if (page._hqHidden || !captured || page._hqEpoch !== captured.epoch) return false;
  if (!captured.scope || captured.scope !== apiScope() || !current()) {
    page._hqEpoch += 1; page._hqContext = null; page.clearPrivate();
    page.setData({ error: '工作身份已变化，请重新进入总部工作台', ready: false, loading: false });
    return false;
  }
  return true;
}
function context(page, captured, value) {
  if (!alive(page, captured)) return false;
  const post = current();
  if (!value || value.readOnly !== true || String(value.tenantId) !== String(http.tenantId())
      || String(value.headquartersOrgId) !== String(post.orgId) || String(value.postId) !== String(post.postId)
      || value.roleCode !== post.roleCode) throw { code: 403, message: '总部工作身份校验未通过' };
  page._hqContext = value;
  return true;
}
function hide(page) {
  page._hqHidden = true; page._hqEpoch = (page._hqEpoch || 0) + 1; page._hqContext = null; page.clearPrivate();
}
function fail(page, captured, error, patch = {}) {
  if (!alive(page, captured)) return;
  if (error && (error.postInvalid || [401, 403].includes(Number(error.code)))) {
    page._hqEpoch += 1; page._hqContext = null; page.clearPrivate();
    page.setData({ ready: false, loading: false, error: '当前总部身份已失效或没有访问权限，请切换身份后重试' });
    return;
  }
  if (error && error.silent) return;
  page.setData({ ...patch, error: error && (error.message || error.msg) || '数据加载失败，请重试', loading: false });
}
module.exports = { current, start, ticket, alive, context, hide, fail };
