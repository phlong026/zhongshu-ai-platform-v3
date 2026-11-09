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
    wx.redirectTo({
      url: this.destination,
      fail: error => {
        console.error('[materials] 打开材料入口失败', error);
        this.setData({ error: '材料页面打开失败，请重试' });
      }
    });
  },
  home() { wx.switchTab({ url: '/pages/engineering/index' }); }
});
