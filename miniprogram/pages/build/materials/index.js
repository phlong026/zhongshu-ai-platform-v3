const http = require('../../../api/http');
// 材料兼容入口：没有项目上下文时先选项目，不能请求空编号的材料详情。
Page({
  data: { error: '' },
  onLoad(options = {}) {
    this.destination = options.id
      ? '/pages/build/record/index?view=materials&id=' + encodeURIComponent(options.id)
        + (options.nodeId ? '&nodeId=' + encodeURIComponent(options.nodeId) : '')
      : '/pages/build/projects/index?destination=materials';
    this.retry();
  },
  retry() {
    this.setData({ error: '' });
    if (!http.token()) return this.login();
    wx.redirectTo({
      url: this.destination,
      fail: error => {
        if (!http.token()) return this.login();
        console.error('[materials] 打开材料入口失败', error);
        this.setData({ error: '材料页面打开失败，请重试' });
      }
    });
  },
  login() {
    wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(this.destination) });
  },
  home() { wx.switchTab({ url: '/pages/engineering/index' }); }
});
