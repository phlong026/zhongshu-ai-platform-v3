const A = require('../../utils/account');
const base = require('./profile-page')(4);
// Boss and sales staff use the same page inside their own navigation shell; everyone else stays on the tab page.
Page({
  ...base,
  onShow() {
    const a = A.access.context();
    if (a.active && A.access.isBoss(a)) return wx.redirectTo({ url: '/pages/admin/profile/index' });
    if (a.active && require('../../utils/organization').has(a.role, 'sales')) { this.getTabBar()?.refresh?.(); return wx.redirectTo({ url: '/pages/account/sales/index' }); }
    return base.onShow.call(this);
  }
});
