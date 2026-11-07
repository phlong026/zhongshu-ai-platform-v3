const page = require('../owner-page')('performance');
const loadHistory = page.onLoad;
const showHistory = page.onShow;
page.onLoad = function(options = {}) {
  this.historyMode = options.history === '1';
  if (!this.historyMode) return wx.redirectTo({ url:'/pages/admin/tasks/index?view=team' });
  loadHistory.call(this, options);
};
page.onShow = function() { if (this.historyMode) showHistory.call(this); };
const __origOnShow = page.onShow;
page.onShow = function() {
  __origOnShow.call(this);
  // R3 P1-A：自动判定（refreshPlatView 的 platDegraded=local / 平台健康=api），不硬编码 mixed
};
Page(page);
