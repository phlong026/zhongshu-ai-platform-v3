// The management tab is a role router. Authorized roles enter their dedicated
// workbench; guests and unrelated roles never receive sample management data.
const X = require('../../utils/access');

Page({
  data: { allowed: false, loggedIn: false, role: '', guest: false, active: false, homeTitle: '', deskTitle: '管理工作台' },
  onShow() {
    const actor = X.context();
    if (actor.active && X.isBoss(actor)) return wx.redirectTo({ url: '/pages/admin/home/index' });
    if (actor.active && ['finance', 'finance-lead'].includes(actor.role)) return wx.redirectTo({ url: '/pages/admin/finance-desk/index' });
    this.setData(require('../../utils/workbench').management());
    const bar = this.getTabBar();
    if (bar) {
      bar.refresh?.();
      bar.setData({ selected: 3, hidden: false });
    }
  },
  goHome() { wx.switchTab({ url: '/pages/' + X.home() + '/index' }); },
  identity() { wx.navigateTo({ url: '/pages/account/index?view=' + (this.data.loggedIn ? 'identity' : 'login') }); }
});
