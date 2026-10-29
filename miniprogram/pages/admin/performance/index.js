const page = require('../owner-page')('performance');
const loadHistory = page.onLoad;
const showHistory = page.onShow;
page.onLoad = function(options = {}) {
  this.historyMode = options.history === '1';
  if (!this.historyMode) return wx.redirectTo({ url:'/pages/admin/tasks/index?view=team' });
  loadHistory.call(this, options);
};
page.onShow = function() { if (this.historyMode) showHistory.call(this); };
Page(page);
