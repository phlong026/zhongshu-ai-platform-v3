const A = require('../../utils/account');
const base = require('./profile-page')(4);
// Boss and sales staff use the same page inside their own navigation shell; everyone else stays on the tab page.
Page({
  ...base,
  onShow() {
    // 联合调试修复（2026-11-08）：API 岗位优先路由——纯 API 登录下本地身份为空，boss/sales 需按岗位落地
    const apiPost = (() => { try { const http = require('../../api/http'); return http.token() ? require('../../api/auth').currentApiPost() : null; } catch (e) { return null; } })();
    if (apiPost && ['boss', 'owner'].includes(apiPost.roleCode)) return wx.redirectTo({ url: '/pages/admin/profile/index' });
    if (apiPost && ['sales'].includes(apiPost.roleCode)) { this.getTabBar()?.refresh?.(); return wx.redirectTo({ url: '/pages/account/sales/index' }); }
    const a = A.access.context();
    if (a.active && A.access.isBoss(a)) return wx.redirectTo({ url: '/pages/admin/profile/index' });
    if (a.active && require('../../utils/organization').has(a.role, 'sales')) { this.getTabBar()?.refresh?.(); return wx.redirectTo({ url: '/pages/account/sales/index' }); }
    return base.onShow.call(this);
  }
});
