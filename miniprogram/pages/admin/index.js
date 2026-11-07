// admin 旧聚合页已下线：本页仅做跳转壳，按 view 语义分流至平台 API 页面。
// 本地演示数据链路（M.load/model.js）已删除——平台接口失败由目标页展示错误态，绝不回落本地数据。
const FINANCE_TABS = {
  // 收款类 → receivables
  receipt: 'receivables', 'receipt-supplement': 'receivables', 'incoming-register': 'receivables',
  refund: 'receivables', 'refund-result': 'receivables', 'refund-review': 'receivables', correction: 'receivables',
  // 付款/直付类 → payables
  pay: 'payables', 'pay-supplement': 'payables', verify: 'payables', 'partial-payment': 'payables',
  // 对账/核对类 → reconcile
  reconcile: 'reconcile', 'cross-evidence': 'reconcile',
  // 其他财务视图默认 receivables
  todos: 'receivables', finance: 'receivables', projects: 'receivables', project: 'receivables'
};
const ORG_VIEWS = ['staff', 'person', 'transfer', 'departure', 'disabled', 'company', 'identity'];
const OPERATING_VIEWS = ['data', 'operating', 'costs', 'funds', 'direct', 'intercompany'];
Page({
  data: {},
  onLoad(options) {
    const view = (options && options.view) || '';
    let url;
    if (ORG_VIEWS.indexOf(view) >= 0) url = '/pages/account/index?view=api-members';
    else if (OPERATING_VIEWS.indexOf(view) >= 0) url = '/pages/admin/home/index?view=operating';
    else if (FINANCE_TABS[view]) url = '/pages/admin/finance-desk/index?view=flows&tab=' + FINANCE_TABS[view];
    else url = '/pages/admin/finance-desk/index';
    wx.redirectTo({ url });
  }
});
