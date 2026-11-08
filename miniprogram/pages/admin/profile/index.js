const W = require('../owner-model');
const base = require('../../profile/profile-page')(3);
Page({
  ...base,
  onShow() {
    // API 岗位优先（联合调试修复 2026-11-09）：纯 API 登录（微信一键/短信）下本地 demo 身份恒空，
    // W.allowed() 恒 false 会把 boss 无条件 switchTab 弹回原生 tab——页面在「页面内 owner-nav」
    // 与「原生 tabBar」两套导航体系间反复弹跳=切页闪屏+黑屏卡死。API boss 岗命中即停留渲染。
    const apiPost = (() => { try { const http = require('../../api/http'); return http.token() ? require('../../api/auth').currentApiPost() : null; } catch (e) { return null; } })();
    if (apiPost && ['boss', 'owner'].includes(apiPost.roleCode)) return base.onShow.call(this);
    if (!W.allowed()) return wx.switchTab({ url: '/pages/profile/index' });
    return base.onShow.call(this);
  }
});
