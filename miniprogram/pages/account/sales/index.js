Page({
  onLoad() {
    wx.switchTab({
      url: '/pages/profile/index',
      fail(error) {
        console.error('老版业务我的入口跳转失败', error);
        wx.showToast({ title: '切换失败，请重试', icon: 'none' });
      }
    });
  }
});
