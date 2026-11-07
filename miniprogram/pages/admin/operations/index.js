// 经营办理旧页已下线：本页仅做跳转壳，不再加载任何本地模拟数据。
// payroll → 绩效任务；receivables → 财务台项目视图；其余（含 expenses/costs/funds/direct/intercompany/desk）→ 经营页。
Page({
  data: {},
  onLoad(options) {
    const view = (options && options.view) || '';
    if (view === 'payroll') return wx.redirectTo({ url: '/pages/admin/tasks/index' });
    if (view === 'receivables') return wx.redirectTo({ url: '/pages/admin/finance-desk/index?view=projects' });
    wx.redirectTo({ url: '/pages/admin/home/index?view=operating' });
  }
});
