const assert = require('assert/strict');
const fs = require('fs');

const copy = value => JSON.parse(JSON.stringify(value));

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();

  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));

  const A = require('../miniprogram/utils/account');
  const Approval = require('../miniprogram/utils/commercial-approval');
  const Desk = require('../miniprogram/utils/finance-desk');

  A.mutate(state => A.loginDemo(state, 'hq-finance-dual'));

  const management = wx.getStorageSync('zhongshu-management-v1');
  const headquarters = management.books.headquarters;
  const store = management;
  headquarters.period = '2026-10';
  headquarters.receipts = [];
  headquarters.payables = [];
  headquarters.payments = [];
  headquarters.refunds = [{
    id: 'refund-material-verified',
    receiptId: 'receipt-material-september',
    amount: 100000,
    status: '已核实',
    date: '2026-10-03'
  }, {
    id: 'refund-material-pending',
    receiptId: 'receipt-material-september',
    amount: 50000,
    status: '待核实',
    date: '2026-10-04'
  }];
  store.period = '2026-10';
  store.receipts = [];
  store.payables = [];
  store.payments = [];
  store.refunds = [];

  const order = {
    version: 1,
    first: 10000,
    tail: 5000,
    total: 15000,
    recipient: '众墅之家总部',
    file: { name: '设计服务约定.pdf', sample: true }
  };
  const customer = {
    id: 'customer-hq-metrics',
    projectId: 'project-hq-metrics',
    companyId: 'quanzhou',
    designOrganizationId: 'headquarters',
    name: '林女士',
    city: '武汉市',
    ownerId: 'sales-dual',
    serviceOrders: [order],
    designPayments: [{
      id: 'design-pay-first',
      stage: 'first',
      serviceVersion: 1,
      amount: 1000000,
      status: '已核实',
      registrarId: 'wang-finance',
      registrarPersonId: 'wang-finance',
      reviewerId: 'chen-finance-lead',
      reviewerPersonId: 'chen-finance-lead'
    }],
    moneyApprovals: []
  };
  customer.moneyApprovals.push({
    id: 'approval-service-1',
    kind: 'service',
    signature: Approval.signature(Approval.source(customer, 'service')),
    status: 'approved',
    approverRole: 'boss'
  });

  const designReceipt = {
    id: 'receipt-design-october',
    companyId: 'headquarters',
    projectId: customer.projectId,
    customer: customer.name,
    name: '设计首款实际到账',
    amount: 400000,
    allocationCap: 400000,
    status: '已核算',
    bankDate: '2026-10-08',
    registrarId: 'hq-finance',
    registrarPersonId: 'hq-finance',
    reviewerId: 'hq-finance-lead',
    reviewerPersonId: 'hq-finance-lead',
    revisions: [],
    source: {
      module: 'intercompany',
      kind: 'design',
      link: 'design:quanzhou:design-pay-first',
      fromCompanyId: 'quanzhou',
      toCompanyId: 'headquarters',
      originPaymentId: 'design-pay-first',
      customerId: customer.id,
      serviceVersion: 1,
      stage: 'first'
    }
  };
  const materialReceipt = {
    id: 'receipt-material-september',
    companyId: 'headquarters',
    projectId: customer.projectId,
    customer: customer.name,
    name: '材料代垫归还实际到账',
    amount: 500000,
    status: '已核算',
    bankDate: '2026-09-28',
    revisions: [{ amount: 200000 }],
    source: {
      module: 'intercompany',
      kind: 'material-advance',
      link: 'material-advance:quanzhou:advance-repayment-1',
      engineeringId: 'engineering-hq-metrics',
      supplyId: 'supply-hq-metrics',
      fromCompanyId: 'quanzhou'
    }
  };
  headquarters.receipts.push(
    designReceipt,
    materialReceipt,
    { id: 'receipt-other-october', companyId: 'headquarters', amount: 30000, status: '已备案', bankDate: '2026-10-11', revisions: [] },
    { id: 'receipt-corrected-october', companyId: 'headquarters', amount: 100000, status: '已核算', bankDate: '2026-10-12', revisions: [{ amount: 40000 }] },
    { id: 'receipt-no-date', companyId: 'headquarters', amount: 10000, status: '已核算', revisions: [] },
    { id: 'receipt-written-off', companyId: 'headquarters', amount: 50000, status: '已核算', bankDate: '2026-10-13', revisions: [{ amount: 0 }] },
    { id: 'receipt-pending', companyId: 'headquarters', amount: 700000, status: '待核算', bankDate: '2026-10-14', revisions: [] },
    { id: 'receipt-cancelled', companyId: 'headquarters', amount: 800000, status: '已撤销', bankDate: '2026-10-15', revisions: [] }
  );
  wx.setStorageSync('zhongshu-management-v1', management);
  wx.setStorageSync('zhongshu-business-v3', { customers: [customer] });
  wx.setStorageSync('zhongshu-engineering-v1', {
    projects: [{
      id: 'engineering-hq-metrics',
      projectId: customer.projectId,
      companyId: 'quanzhou',
      customer: customer.name,
      supplies: [{
        id: 'supply-hq-metrics',
        supplier: '乡建材料商',
        payer: '总部',
        basis: { name: '材料采购依据.pdf', sample: true },
        quantity: 1,
        unitPrice: 6000
      }],
      payments: [{
        id: 'advance-payment-1',
        supplyId: 'supply-hq-metrics',
        payerCompanyId: 'headquarters',
        cents: 600000
      }],
      advanceRepayments: [{
        id: 'advance-repayment-1',
        supplyId: 'supply-hq-metrics',
        cents: 200000,
        reviewerId: 'chen-finance-lead'
      }]
    }]
  });

  const hq = Desk.home(Desk.load());
  assert.deepEqual(hq.metrics.map(row => row.label), ['本月实收', '待收金额', '总收款金额']);
  assert.deepEqual(hq.metrics.map(row => row.value), ['¥ 4,700', '1.6万', '¥ 6,800']);
  assert(hq.metrics[0].url.includes('view=flows') && hq.metrics[0].url.includes('tab=ledger'));
  assert(hq.metrics[0].url.includes('scope=current') && decodeURIComponent(hq.metrics[0].url).includes('category=实收'));
  assert(hq.metrics[1].url.includes('view=partners'));
  assert(hq.metrics[2].url.includes('view=flows') && hq.metrics[2].url.includes('tab=ledger'));
  assert(hq.metrics[2].url.includes('scope=all') && decodeURIComponent(hq.metrics[2].url).includes('category=实收'));

  wx.reLaunch({ url: '/pages/management/index' });
  const currentMetricButton = await (await runtime.currentPage()).$('.fin-home-metric[data-index="0"]');
  assert(currentMetricButton, '总部财务首页应显示本月实收卡片');
  await currentMetricButton.tap();
  const currentPage = getCurrentPages().at(-1);
  assert.equal(currentPage.data.flowScope, 'current');
  assert(currentPage.data.rows.length > 1 && currentPage.data.rows.every(row => row.category === '实收' && row.date.startsWith('2026-10')));
  assert.equal(Desk.number(currentPage.data.rows.reduce((total, row) => total + row.amount, 0)), hq.metrics[0].value, '本月明细行合计必须等于本月实收');

  wx.reLaunch({ url: '/pages/management/index' });
  const metricButton = await (await runtime.currentPage()).$('.fin-home-metric[data-index="2"]');
  assert(metricButton, '总部财务首页应显示总收款金额卡片');
  await metricButton.tap();
  const cumulativePage = getCurrentPages().at(-1);
  assert.equal(cumulativePage.data.flowScope, 'all');
  assert.equal(cumulativePage.data.periodText, '全部月份');
  assert(cumulativePage.data.rows.length > 1 && cumulativePage.data.rows.every(row => row.category === '实收'));
  assert(cumulativePage.data.rows.some(row => row.date.startsWith('2026-09')), '累计明细应包含所选月份之前的有效收款');
  assert(cumulativePage.data.rows.some(row => row.id === 'receipt-no-date' && row.date === ''), '累计明细应安全展示日期待核对的有效收款');
  assert(!cumulativePage.data.rows.some(row => ['receipt-pending', 'receipt-cancelled', 'receipt-written-off'].includes(row.id)));
  assert.equal(Desk.number(cumulativePage.data.rows.reduce((total, row) => total + row.amount, 0)), hq.metrics[2].value, '累计明细行合计必须等于总收款金额');
  assert.equal(cumulativePage.data.stats[0].value, hq.metrics[2].value);

  assert(cumulativePage.switchWorkspaceTab({ key: 'finance-books' }), '累计收款明细应可切换到加盟商往来');
  assert.equal(cumulativePage.data.view, 'partners');
  assert.equal(cumulativePage.data.flowScope, 'period', '加盟商往来不能继承累计收款的全部月份范围');
  assert.notEqual(cumulativePage.data.periodText, '全部月份');
  assert(cumulativePage.switchWorkspaceTab({ key: 'finance-flows' }), '加盟商往来应可返回收支明细');
  assert.equal(cumulativePage.data.view, 'flows');
  assert.equal(cumulativePage.data.flowScope, 'all', '返回收支明细应恢复累计收款范围');
  assert.equal(cumulativePage.data.category, '实收');
  assert.equal(Desk.number(cumulativePage.data.rows.reduce((total, row) => total + row.amount, 0)), hq.metrics[2].value, 'Tab 往返后累计明细仍需与总收款金额一致');

  const partner = Desk.partners(Desk.load()).find(row => row.id === 'quanzhou');
  assert(partner, '总部待收明细应保留同一加盟商往来');
  assert.equal(partner.designRemaining, 1100000, '设计待收应为已审批应收减总部有效到账');
  assert.equal(partner.advanceRemaining, 500000, '材料代垫待收应扣除已核实退款后的总部净到账，未核实退款不生效');

  A.mutate(state => A.loginDemo(state, 'finance-dual'));
  cumulativePage.refresh();
  assert.equal(cumulativePage.data.hq, false);
  assert.equal(cumulativePage.data.flowScope, 'period', '同页切换组织后必须退出总部累计范围');
  assert.notEqual(cumulativePage.data.periodText, '全部月份');
  assert.deepEqual(cumulativePage.tabStates, {}, '同页切换组织后不得保留旧组织的 Tab 范围');
  assert(cumulativePage.switchWorkspaceTab({ key: 'finance-books' }));
  assert(cumulativePage.switchWorkspaceTab({ key: 'finance-flows' }));
  assert.equal(cumulativePage.data.flowScope, 'period', '新组织 Tab 往返不得恢复旧组织累计范围');
  const storeDesk = Desk.home(Desk.load());
  assert.deepEqual(storeDesk.metrics.map(row => row.label), ['本月已登记收款', '本月已登记付款', '合同待收', '待办事项']);
  assert.equal(Desk.advances(Desk.load())[0].received, 100000, '门店往来明细应读取总部扣除已核实退款后的净到账');

  const template = fs.readFileSync('miniprogram/pages/management/finance-home.wxml', 'utf8');
  const style = fs.readFileSync('miniprogram/pages/management/index.wxss', 'utf8');
  assert(template.includes("desk.hq?'is-hq':''"), '总部三指标应使用独立三列布局类');
  assert(/\.fin-home-metrics\.is-hq\{[^}]*grid-template-columns:repeat\(3,minmax\(0,1fr\)\)/.test(style), '总部指标应按三等分排列');

  console.log('PASS HQ finance metrics: monthly receipts, approved receivables, cumulative receipts, store unchanged, metric links');
})().catch(error => {
  console.error(error.stack || error);
  process.exit(1);
});
