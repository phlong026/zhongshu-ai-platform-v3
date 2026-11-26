const http = require('../../../api/http');
const profile = '/pages/profile/index';

Page({
  openCompanyPoints() {
    if (http.token()) return wx.switchTab({ url: profile });
    return wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(profile) });
  }
});
