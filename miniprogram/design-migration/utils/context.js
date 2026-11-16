const http = require('../../api/http');
const auth = require('../../api/auth');
function scope() {
  const post = http.token() && auth.currentApiPost();
  return post ? [http.tenantId(), post.orgId, post.userId, post.postId].join(':') : '';
}
function getDesignApp() {
  const app = getApp(), current = scope();
  if (!app.globalData) app.globalData = {};
  if (!app.globalData.designMigration || app.globalData.designMigration.scope !== current) {
    app.globalData.designMigration = { scope: current, globalData: {}, catalogAssets: {} };
  }
  return app.globalData.designMigration;
}
module.exports = { scope, getApp: getDesignApp };
