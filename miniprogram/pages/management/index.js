// The management tab is a role router. Authorized roles enter their dedicated
// workbench; guests see the login guide and unrelated roles see an empty state.
const X = require('../../utils/access');

Page({
  data: { allowed: false, loggedIn: false, role: '', guest: false, active: false, homeTitle: '', deskTitle: '管理工作台' },
  onShow() {
    // 联合调试修复（2026-11-08）：路由必须先走 API 岗位——纯 API 登录（微信一键/短信）下
    // 本地 demo 身份（X.context）恒为空，boss/finance 落此 tab 只会看到守卫页而非工作台。
    const apiPost = (() => { try { const http = require('../../api/http'); return http.token() ? require('../../api/auth').currentApiPost() : null; } catch (e) { return null; } })();
    if (apiPost && ['boss', 'owner'].includes(apiPost.roleCode)) return wx.redirectTo({ url: '/pages/admin/home/index' });
    if (apiPost && ['finance', 'finance-lead'].includes(apiPost.roleCode)) return wx.redirectTo({ url: '/pages/admin/finance-desk/index' });
    if (apiPost && apiPost.roleCode === 'hq-design-admin') return wx.redirectTo({ url: '/pages/workflow/commission/index?view=admin' });
    const actor = X.context();
    if (actor.active && X.isBoss(actor)) return wx.redirectTo({ url: '/pages/admin/home/index' });
    if (actor.active && ['finance', 'finance-lead'].includes(actor.role)) return wx.redirectTo({ url: '/pages/admin/finance-desk/index' });
    // 到达此 tab 的其余角色（sales-lead/project-manager 等）：只显示空态与登录引导，不注入任何本地管理数据。
    const nav = require('../../utils/workbench-nav')();
    const post = apiPost;
    this.setData({
      guest: nav.guest,
      loggedIn: !!post,
      active: actor.active || !!post,
      role: post ? (require('../../utils/organization').roles[post.roleCode] || post.roleCode) : (X.roles[actor.role] || '访客'),
      homeTitle: nav.homeTitle,
      deskTitle: '管理工作台'
    });
    const bar = this.getTabBar();
    if (bar) {
      bar.refresh?.();
      bar.setData({ selected: 3, hidden: false });
    }
  },
  goHome() { wx.switchTab({ url: '/pages/' + X.home() + '/index' }); },
  identity() { wx.navigateTo({ url: '/pages/account/index?view=' + (this.data.loggedIn ? 'identity' : 'login') }); }
});
