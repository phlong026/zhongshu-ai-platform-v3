const http = require('../../../api/http');
const auth = require('../../../api/auth');
const design = require('../../../utils/platform-design');
const base = '/design-migration/pages/';
const validId = value => /^[1-9]\d{0,18}$/.test(String(value || ''));

function destination(options) {
  const {view, projectId, resultVersionId, revisionId} = options;
  if (view === 'case' && validId(options.id)) return base + 'library/detail?id=' + options.id;
  if (view === 'budget') {
    if (validId(projectId)) return base + 'budget/input?projectId=' + projectId + (validId(resultVersionId) ? '&resultVersionId=' + resultVersionId : '');
    const query = ['draftId','customerId'].filter(key => validId(options[key])).map(key => key + '=' + options[key]).join('&');
    return base + 'budget/quick' + (query ? '?' + query : '');
  }
  if (view === 'budget-result') {
    const budgetId = options.budgetId || options.estimateId;
    if (validId(budgetId)) {
      return base + 'budget/result?budgetId=' + budgetId + (validId(revisionId) ? '&revisionId=' + revisionId : '');
    }
    if (validId(projectId)) return base + 'budget/history?projectId=' + projectId;
    return base + 'budget/history?independent=1' + (validId(options.draftId) ? '&draftId=' + options.draftId : '');
  }
  if (['plan-result','facade-result'].includes(view) && validId(projectId)) {
    return base + 'ai-design/result?projectId=' + projectId + (validId(resultVersionId) ? '&resultVersionId=' + resultVersionId : '');
  }
  if (view === 'project' && validId(projectId || options.id)) return base + 'profile/record?type=projects&id=' + (projectId || options.id);
  if (['projects','project','plan-result','facade-result'].includes(view)) return base + 'profile/records?type=projects';
  return base + 'library/index';
}

Page({
  data: {failed: false},
  onLoad(options = {}) { this.entry = options; this.openDestination(); },
  onShow() { if (this.entry && this.tokenAtOpen !== http.token()) this.openDestination(); },
  openDestination() {
    const options = this.entry || {};
    this.tokenAtOpen = http.token();
    let url;
    if (!this.tokenAtOpen) {
      const query = ['view','id','customerId','projectId','draftId','budgetId','estimateId','revisionId','resultVersionId']
        .filter(key => options[key]).map(key => key + '=' + encodeURIComponent(options[key])).join('&');
      url = '/pages/account/index?view=login&return=' + encodeURIComponent('/pages/workflow/explore/index' + (query ? '?' + query : ''));
    } else if (design.pageMode(this.tokenAtOpen, auth.currentApiPost()) !== 'api') {
      url = '/pages/account/index?view=access&content=' + encodeURIComponent('设计赋能');
    } else {
      url = destination(options);
    }
    this.setData({failed: false});
    wx.redirectTo({url, fail: () => this.setData({failed: true})});
  }
});
