const W = require('../owner-model');
const base = require('../../profile/profile-page')(3);
Page({
  ...base,
  onShow() {
    if (!W.allowed()) return wx.switchTab({ url: '/pages/profile/index' });
    return base.onShow.call(this);
  }
});
