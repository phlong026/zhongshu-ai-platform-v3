/** 旧个人设计点钱包退役页：公司积分池是平台唯一积分账本。 */
const http = require('../../../api/http');
const navigation = require('../../../utils/page-navigation');

const COMPANY_POINTS_PAGE = '/pages/profile/index';
const LOGIN_PAGE = '/pages/account/index?view=login&return=' + encodeURIComponent(COMPANY_POINTS_PAGE);

Page({
  data: { loggedIn: false },

  onLoad() { this.syncSession(); },
  onShow() { this.syncSession(); },

  syncSession() {
    const loggedIn = !!http.token();
    this.setData({ loggedIn });
    return loggedIn;
  },

  openCompanyPoints() {
    if (!this.syncSession()) return wx.redirectTo({ url: LOGIN_PAGE });
    return wx.switchTab({ url: COMPANY_POINTS_PAGE });
  },

  returnWorkbench() {
    if (!this.syncSession()) return wx.redirectTo({ url: LOGIN_PAGE });
    return navigation.home();
  }
});
