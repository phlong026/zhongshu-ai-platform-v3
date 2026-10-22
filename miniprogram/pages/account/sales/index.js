const X = require('../../../utils/access'), J = require('../../../utils/organization');
const base = require('../../profile/profile-page')(4);
Page({
  ...base,
  onShow() {
    const a = X.context();
    if (!a.active || !J.has(a.role, 'sales')) return wx.switchTab({ url: '/pages/profile/index' });
    return base.onShow.call(this);
  },
  open(e) { const a = X.context(); if (!a.active || !J.has(a.role, 'sales')) return; return base.open.call(this, e); }
});
