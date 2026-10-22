const assert = require('assert/strict');
const path = require('path');

const SIX_ROLES = {
  'sales-lead': ['李业务', 'sales-lead', 'store'],
  boss: ['张老板', 'boss', 'store'],
  'finance-lead': ['王财务', 'finance-lead', 'store'],
  'project-manager': ['张工程', 'project-manager', 'store'],
  'hq-design-admin': ['林设计', 'hq-design-admin', 'headquarters'],
  'hq-finance': ['罗财务', 'finance-lead', 'headquarters']
};

const copy = value => JSON.parse(JSON.stringify(value));
const install = rows => {
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  rows.forEach(([key, value]) => wx.setStorageSync(key, copy(value)));
};

(async () => {
  const fixture = await require('./audit-fixtures.cjs')();
  const Access = require('../miniprogram/utils/access');

  for (const [label, [name, role, orgType]] of Object.entries(SIX_ROLES)) {
    install(fixture.rowsByRole[label]);
    const actor = Access.context();
    const business = wx.getStorageSync('zhongshu-business-v3');
    const engineering = wx.getStorageSync('zhongshu-engineering-v1');
    const customer = business.customers.find(row => row.id === fixture.customerId);
    const project = engineering.projects.find(row => row.id === fixture.projectId);

    assert.equal(actor.name, name, label + ' 使用真实演示人物');
    assert.equal(actor.role, role, label + ' 使用当前岗位');
    assert.equal(actor.orgType, orgType, label + ' 使用当前组织类型');
    assert(customer, label + ' 可关联统一客户');
    assert(project, label + ' 可关联统一工程项目');
    assert.equal(customer.projectId, project.projectId, label + ' 客户与工程项目编号一致');
    assert.equal(project.sourceCustomerId, customer.id, label + ' 工程项目回指同一客户');
  }
  console.log('PASS 六类身份共用同一客户和同一项目夹具');

  install(fixture.rowsByRole['sales-lead']);
  const business = wx.getStorageSync('zhongshu-business-v3');
  const customer = business.customers.find(row => row.id === fixture.customerId);
  const budget = customer.budgets.at(-1);
  const QuoteSheet = require('../miniprogram/pages/workflow/quote-export/sheet');
  const form = QuoteSheet.defaults(customer, 'budget', budget);
  const document = QuoteSheet.build(customer, 'budget', budget.version, form);
  assert.equal(document.total, Math.round(budget.total * 100), '导出合计与同一预算版本一致');
  assert.equal(
    budget.bodyRows.reduce((sum, row) => sum + row.value, 0),
    budget.main,
    '主体预算分项与主体合计一致'
  );
  assert.equal(
    budget.exteriorRows.reduce((sum, row) => sum + row.value, 0),
    budget.exterior,
    '外装预算分项与外装合计一致'
  );
  console.log('PASS 销售预算使用真实算法分项并通过生产导出守恒校验');

  const FinanceDesk = require('../miniprogram/utils/finance-desk');
  for (const label of ['finance-lead', 'hq-finance']) {
    install(fixture.rowsByRole[label]);
    const ledger = FinanceDesk.flows(FinanceDesk.load(), 'ledger');
    const historical = ledger.filter(row => /历史/.test(row.category + row.name + row.detail));
    historical.forEach(row => {
      assert(row.amount > 0, label + ' 历史只读记录保留原金额');
      assert(/^\d{4}-\d{2}-\d{2}$/.test(row.date), label + ' 历史只读记录保留日期');
      assert(/原收款/.test(row.detail), label + ' 历史只读记录保留原单编号');
      assert(!row.action, label + ' 历史只读记录不应显示可点击动作');
      assert(!row.url, label + ' 历史只读记录不应链接已关闭办理页');
    });
    assert(!ledger.some(row => /view=(refund-result|refund-review|refund-supplement)/.test(row.url || '')),
      label + ' 当前流水不得生成已下线退款办理链接');
    const tree = require('./role-page-runtime.cjs').render(
      path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),
      {
        ready: true,
        view: 'flows',
        flowTab: 'ledger',
        rows: ledger,
        stats: [],
        keyword: '',
        company: label,
        role: Access.context().role,
        period: '',
        periodText: '',
        statusOptions: [],
        categoryOptions: [],
        statusFilter: '全部状态',
        category: '全部类型',
        sort: '最新优先',
        due: '',
        filter: ''
      }
    );
    const nodes = values => values.flatMap(node => [node, ...nodes(node.children || [])]);
    const actionButtons = nodes(tree).filter(node => String(node.attrs?.class || '').split(/\s+/).includes('fin-outline'));
    assert.equal(actionButtons.length, ledger.filter(row => row.url).length, label + ' 正常凭证入口应继续渲染');
    actionButtons.forEach(button => assert(button.attrs['data-url'], label + ' WXML 不应渲染空链接按钮'));
    if (historical.length) assert(nodes(tree).some(node => node.text === '历史只读'), label + ' 历史卡片应明确只读');
  }
  console.log('PASS 财务历史流水保留原金额且不产生已关闭办理入口');
})().catch(error => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
