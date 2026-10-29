const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));

async function main() {
  const { A, M } = await require('./verify-owner-workbench.cjs').fixture();
  const Ops = require('../miniprogram/utils/operations');
  const Report = require('../miniprogram/utils/operating-report');
  const Business = require('../miniprogram/pages/admin/owner-business');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const file = { name: '经营专项模拟凭据.pdf', sample: true };
  const companyId = 'quanzhou';
  const projectId = 'management-finance-project';
  const customer = {
    id: 'management-finance-customer', projectId, companyId, name: '经营测试客户', city: '泉州市',
    agreements: [{ id: 'agreement-1', total: 1000 }],
    follows: [{ date: '2026-10-03', note: '确认真实建房需求' }],
    serviceOrders: [], receipts: [], quotes: [], budgets: [], plans: [], facades: [], deliverables: [], changes: []
  };
  const engineering = {
    id: 'management-finance-engineering', projectId, companyId, customer: customer.name, name: '经营测试项目',
    siteRecords: [{ id: 'site-old', date: '2026-10-02', note: '基础施工', file: { name: '基础现场.jpg', path: '/tmp/foundation.jpg' } }, { id: 'site-new', date: '2026-10-20', note: '主体施工', file: { name: '主体现场.jpg', path: '/tmp/topping.jpg' } }],
    supplies: [], materialMovements: [], receipts: [], reships: [], payments: [], plan: { nodes: [] }
  };
  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [customer] });
  wx.setStorageSync('zhongshu-engineering-v1', { version: 1, projects: [engineering] });

  const months = ['2026-05', '2026-06', '2026-07', '2026-08', '2026-09', '2026-10'];
  const state = {
    companyId, period: '2026-10', staff: [], sources: [], archives: [], appeals: [], work: [], transfers: [], events: [],
    receipts: months.map((period, index) => ({ id: 'receipt-' + period, projectId: period === '2026-10' ? projectId : 'trend-only', customer: customer.name, amount: (index + 1) * 10000, status: '已核算', bankDate: period + '-05', receiptStage: period === '2026-10' ? 'start' : '', source: { module: 'engineering', id: engineering.id, kind: 'receipt' }, revisions: period === '2026-06' ? [{ amount: 25000 }] : [] })),
    refunds: [{ id: 'refund-oct', receiptId: 'receipt-2026-10', projectId, amount: 5000, status: '已核实', date: '2026-10-08' }],
    payments: [{ id: 'promotion-payment', payableId: 'expense-payable:promotion-expense', projectId: 'company:' + companyId, amount: 10000, status: '已核实', date: '2026-10-09' }],
    payables: [],
    operations: { projectClosures: [], plans: [], expenses: [], costChecks: [], exceptions: [], funds: [{ id: 'fund-oct', date: '2026-10-01', amount: 100000, by: '财务', at: '2026-10-01T08:00:00.000Z' }], events: [], direct: [], directApprovals: [], payrollPolicies: [], payrollSettlements: [], periodSnapshots: [] }
  };
  const contract = Ops.contract(Ops.project(projectId, state));
  state.operations.plans.push({ id: 'plan-current', projectId, signature: contract.signature, status: 'approved', approval: { at: '2026-09-30T08:00:00.000Z' }, installments: [{ id: 'start', name: '开工款', stage: 'start', amount: 100000, due: '2026-10-10' }] });
  state.operations.expenses.push({ id: 'promotion-expense', projectId: 'company:' + companyId, category: '现场其他', purpose: '推广获客', name: '短视频推广', amount: 30000, date: '2026-10-02', due: '2026-10-09', status: 'approved', approval: { at: '2026-10-02T08:00:00.000Z' } });
  state.payables.push({ id: 'expense-payable:promotion-expense', expenseId: 'promotion-expense', projectId: 'company:' + companyId, customer: '公司日常费用', name: '短视频推广', stage: '现场其他费用', purpose: '推广获客', total: 30000, due: '2026-10-09', approvalValid: true, status: '部分已付', approval: { status: 'approved', at: '2026-10-02T08:00:00.000Z' } });
  const cost = Ops.cost(state, projectId);
  state.operations.costChecks.push({ id: 'cost-check', projectId, signature: cost.signature, remaining: Object.fromEntries(Ops.categories.map(key => [key, 0])), at: '2026-10-02T08:00:00.000Z' });

  const report = Report.analytics(state, '2026-10', '2026-10-23');
  assert.deepEqual(report.trend.map(row => row.period), months);
  assert.equal(report.trend[1].incomeCents, 25000, '修订金额只能计最新版本一次');
  assert.equal(report.trend.at(-1).paidCents, 15000, '实付与退款分别计入支出且不重复批准费用');
  assert.equal(report.collectionRate.numeratorCents, 55000, '本月工程退款必须回冲本月净工程收款');
  assert.equal(report.collectionRate.denominatorCents, 100000);
  assert.equal(report.collectionRate.basisPoints, 5500);
  assert.equal(report.coverage.cashCents, 145000);
  assert.equal(report.coverage.payableCents, 20000);
  assert.equal(report.coverage.milli, 7250);
  assert.equal(report.acquisition.promotionCents, 30000);
  assert.equal(report.acquisition.effectiveCustomers, 1);
  assert.equal(report.acquisition.costCents, 30000);
  assert.equal(report.receivableAging.buckets.find(row => row.key === 'overdue-1-30').amountCents, 45000, '应收必须按收款减已核实退款后的净额计算');
  assert.equal(report.payableAging.buckets.find(row => row.key === 'overdue-1-30').amountCents, 20000);
  assert.equal(report.profit.available, true);
  assert.equal(report.profit.profitCents, 100000);
  assert.equal(report.paidComposition.rows.find(row => row.key === '推广获客').amountCents, 10000);

  const originalBusiness = wx.getStorageSync('zhongshu-business-v3');
  const atOnlyCustomer = { ...customer, follows: [{ id:'follow-at-only', at:'2026-10-03T09:00:00.000Z', note:'仅记录时间戳的真实跟进' }] };
  const atOnlyIssues = [];
  wx.setStorageSync('zhongshu-business-v3', { version:1, customers:[atOnlyCustomer] });
  const atOnlyReport = Report.analytics(state, '2026-10', '2026-10-23');
  if (atOnlyReport.acquisition.effectiveCustomers !== 1) atOnlyIssues.push('本月 at-only 真实跟进未计入有效客户');
  if (atOnlyReport.acquisition.costCents !== 30000) atOnlyIssues.push('本月 at-only 真实跟进未参与推广获客成本');

  const currentAtSignature = Report.sourceSignature(state, '2026-10');
  wx.setStorageSync('zhongshu-business-v3', { version:1, customers:[{ ...atOnlyCustomer, follows:[{ ...atOnlyCustomer.follows[0], at:'2026-10-04T09:00:00.000Z' }] }] });
  if (Report.sourceSignature(state, '2026-10') === currentAtSignature) atOnlyIssues.push('本月 at-only 跟进时间变化未改变来源签名');

  const septemberAtCustomer = { ...customer, follows:[{ id:'follow-september-at', at:'2026-09-18T09:00:00.000Z', note:'九月真实跟进' }] };
  wx.setStorageSync('zhongshu-business-v3', { version:1, customers:[septemberAtCustomer] });
  const septemberAtSignature = Report.sourceSignature(state, '2026-09');
  wx.setStorageSync('zhongshu-business-v3', { version:1, customers:[{ ...septemberAtCustomer, follows:septemberAtCustomer.follows.concat({ id:'follow-future-at', at:'2026-10-05T09:00:00.000Z', note:'未来月份真实跟进' }) }] });
  if (Report.sourceSignature(state, '2026-09') !== septemberAtSignature) atOnlyIssues.push('未来月份 at-only 跟进错误改写过去月来源签名');
  wx.setStorageSync('zhongshu-business-v3', originalBusiness);
  assert.deepEqual(atOnlyIssues, [], 'at-only 跟进必须与 date 跟进口径一致并按月份隔离');

  const crossMonth = clone(state);
  crossMonth.receipts.push({ id: 'receipt-september-project', projectId, customer: customer.name, amount: 10000, status: '已核算', bankDate: '2026-09-25', receiptStage: 'start', source: { module: 'engineering', id: engineering.id, kind: 'receipt' }, revisions: [] });
  crossMonth.refunds.push({ id: 'refund-cross-month', receiptId: 'receipt-september-project', projectId, amount: 2000, status: '已核实', date: '2026-10-12' });
  const crossMonthReport = Report.analytics(crossMonth, '2026-10', '2026-10-23');
  assert.equal(crossMonthReport.collectionRate.numeratorCents, 53000, '本月退款即使对应上月收款，也必须在本月净工程收款中回冲');
  assert.equal(crossMonthReport.receivableAging.totalCents, 37000, '跨月退款也必须恢复对应项目应收');
  const liveSnapshot = { state, customers: [customer], projects: [engineering], period:'2026-10', today:'2026-10-23', operating:Ops.overview(state) };
  const liveAR = Business.rows('receivables', liveSnapshot).find(row=>row.sourceId===projectId+':start');
  assert.equal(liveAR.amountCents, 45000, '实时应收列表同样扣除退款后的净收款');
  assert.equal(Business.recordFor('receivable', liveSnapshot, liveAR.sourceId).amount, 45000);

  const recordFlowEngineering = { ...engineering, recordFlow: { contract: { version: 1, total: 100000, first: 50000, file } } };
  wx.setStorageSync('zhongshu-engineering-v1', { version: 1, projects: [recordFlowEngineering] });
  const staged = clone(state);
  staged.receipts = [{ id: 'receipt-two-starts', projectId, customer: customer.name, amount: 70000, status: '已核算', bankDate: '2026-10-05', receiptStage: 'start', source: { module: 'engineering', id: engineering.id, kind: 'receipt' } }];
  staged.refunds = [];
  staged.operations.plans = [{ id: 'plan-two-starts', projectId, signature: Ops.contract(Ops.project(projectId, staged)).signature, status: 'approved', approval: { at: '2026-09-30T08:00:00.000Z' }, installments: [
    { id: 'start-one', name: '开工款一', stage: 'start', amount: 50000, due: '2026-10-10' },
    { id: 'start-two', name: '开工款二', stage: 'start', amount: 50000, due: '2026-10-20' }
  ] }];
  assert.deepEqual(Ops.receivable(staged, projectId).installments.map(row => row.paid), [50000, 20000], '同阶段实收应逐期消耗');
  const stagedReport = Report.analytics(staged, '2026-10', '2026-10-23');
  assert.equal(stagedReport.receivableAging.totalCents, 30000);
  assert.deepEqual(stagedReport.receivableRows.filter(row => row.projectId === projectId).map(row => row.paidCents), [20000], '经营报表应与实时未结清期次保持一致');
  staged.refunds.push({ id: 'refund-two-starts', receiptId: 'receipt-two-starts', projectId, amount: 20000, status: '已核实', date: '2026-10-08' });
  const refundedReceivable = Ops.receivable(staged, projectId);
  assert.equal(refundedReceivable.received, 50000, '实收总额应扣除已核实退款');
  assert.equal(refundedReceivable.remaining, 50000);
  assert.deepEqual(refundedReceivable.installments.map(row => row.paid), [50000, 0]);
  const refundedReport = Report.analytics(staged, '2026-10', '2026-10-23');
  assert.equal(refundedReport.receivableAging.totalCents, 50000);
  assert.deepEqual(refundedReport.receivableRows.filter(row => row.projectId === projectId).map(row => row.paidCents), [0]);

  const crossStage = clone(staged);
  crossStage.refunds = [];
  crossStage.operations.plans[0].installments = [
    { id: 'start-one', name: '开工款', stage: 'start', amount: 50000, due: '2026-10-10' },
    { id: 'topping-one', name: '封顶款', stage: 'topping', amount: 50000, due: '2026-10-20' }
  ];
  assert.deepEqual(Ops.receivable(crossStage, projectId).installments.map(row => row.paid), [50000, 20000], '阶段内足额后的净实收余额应继续抵扣未结期次');
  let crossStageReport = Report.analytics(crossStage, '2026-10', '2026-10-23');
  assert.equal(crossStageReport.receivableAging.totalCents, 30000);
  assert.deepEqual(crossStageReport.receivableRows.filter(row => row.projectId === projectId).map(row => [row.id, row.paidCents, row.amountCents]), [[projectId + ':topping-one', 20000, 30000]]);
  crossStage.refunds.push({ id: 'refund-cross-stage', receiptId: 'receipt-two-starts', projectId, amount: 20000, status: '已核实', date: '2026-10-08' });
  assert.deepEqual(Ops.receivable(crossStage, projectId).installments.map(row => row.paid), [50000, 0], '退款后应撤回跨阶段抵扣');
  crossStageReport = Report.analytics(crossStage, '2026-10', '2026-10-23');
  assert.equal(crossStageReport.receivableAging.totalCents, 50000);
  assert.deepEqual(crossStageReport.receivableRows.filter(row => row.projectId === projectId).map(row => [row.id, row.paidCents, row.amountCents]), [[projectId + ':topping-one', 0, 50000]]);
  wx.setStorageSync('zhongshu-engineering-v1', { version: 1, projects: [engineering] });
  pass('六个月趋势、构成、账龄、工程回款率、资金覆盖、预计毛利和获客成本均使用整数分原事实');

  const incomplete = clone(state);
  incomplete.operations.costChecks = [];
  assert.equal(Report.analytics(incomplete, '2026-10', '2026-10-23').profit.profitCents, null);
  const noFacts = Report.analytics({ ...clone(state), operations: { ...clone(state.operations), funds: [], expenses: [], costChecks: [] }, payables: [], payments: [] }, '2026-10', '2026-10-23');
  assert.equal(noFacts.coverage.cashCents, null);
  assert.equal(noFacts.acquisition.costCents, null);
  pass('资金、推广和成本依据缺失时保留空态');

  const snapshotState = clone(state);
  snapshotState.period = '2026-09';
  snapshotState.operations.plans[0].installments[0].due = '2026-09-10';
  snapshotState.operations.plans[0].signature = Ops.contract(Ops.project(projectId, snapshotState)).signature;
  snapshotState.operations.expenses.push({ id: 'september-expense', projectId: 'company:' + companyId, category: '现场其他', purpose: '门店运营', name: '九月门店费用', amount: 50000, date: '2026-09-20', due: '2026-09-28', status: 'approved', approval: { at: '2026-09-20T08:00:00.000Z' } });
  snapshotState.payables.push({ id: 'expense-payable:september-expense', expenseId: 'september-expense', projectId: 'company:' + companyId, customer: '公司日常费用', name: '九月门店费用', total: 50000, due: '2026-09-28', approvalValid: true, status: '待办理', approval: { at: '2026-09-20T08:00:00.000Z' } });
  assert.throws(() => Ops.apply(snapshotState, 'operating-snapshot', '2026-10', { checked: true, note: '不能归档当前月', version: 0 }), /过去月份/);
  const reviewedSignature = Report.sourceSignature(snapshotState, '2026-09');
  snapshotState.operations.expenses.find(row => row.id === 'september-expense').name = '九月门店费用（事实已变化）';
  assert.throws(() => Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '确认期间事实已经变化', version: 0, expectedSignature: reviewedSignature }), /来源已变化.*刷新/);
  snapshotState.operations.expenses.find(row => row.id === 'september-expense').name = '九月门店费用';
  const firstId = Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '核对九月经营事实', version: 0 }).id;
  const frozen = clone(snapshotState.operations.periodSnapshots[0]);
  assert.equal(frozen.report.payableAging.totalCents, 50000);
  const signatureBeforeFuture = Report.sourceSignature(snapshotState, '2026-09');
  snapshotState.payments.push({ id: 'future-payment', payableId: 'expense-payable:september-expense', amount: 50000, status: '已核实', date: '2026-11-01' });
  snapshotState.payables.find(row => row.id === 'expense-payable:september-expense').status = '已付清';
  assert.equal(Report.sourceSignature(snapshotState, '2026-09'), signatureBeforeFuture, '未来收付不能改写过去月份来源签名');
  assert.equal(Report.analytics(snapshotState, '2026-09', '2026-10-23').payableAging.totalCents, 50000, '未来付款不能清空历史月末应付');
  assert.equal(Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '幂等重试', version: 1 }).id, firstId);
  snapshotState.receipts.find(row => row.id === 'receipt-2026-09').revisions = [{ amount: 99000 }];
  const secondId = Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '原到账更正后追加修订', version: 1 }).id;
  assert.notEqual(secondId, firstId);
  assert.equal(snapshotState.operations.periodSnapshots.length, 2);
  assert.equal(snapshotState.operations.periodSnapshots[1].supersedes, firstId);
  assert.deepEqual(snapshotState.operations.periodSnapshots[0], frozen);
  assert.throws(() => Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '陈旧版本', version: 1 }), /版本/);
  const followSignature = Report.sourceSignature(snapshotState, '2026-09');
  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [{ ...customer, follows: customer.follows.concat({ date: '2026-09-18', note: '九月有效跟进' }) }] });
  assert.notEqual(Report.sourceSignature(snapshotState, '2026-09'), followSignature, '有效客户来源变化必须改变快照签名');
  const thirdId = Ops.apply(snapshotState, 'operating-snapshot', '2026-09', { checked: true, note: '有效跟进补录后修订', version: 2 }).id;
  assert.notEqual(thirdId, secondId);
  assert.equal(snapshotState.operations.periodSnapshots[2].report.acquisition.effectiveCustomers, 1);
  assert.deepEqual(snapshotState.operations.periodSnapshots[0], frozen, '新增修订不能改写首版快照');
  const contractSignature = Report.sourceSignature(snapshotState, '2026-09');
  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [{ ...customer, agreements: [{ id: 'agreement-1', total: 1200 }], follows: customer.follows.concat({ date: '2026-09-18', note: '九月有效跟进' }) }] });
  assert.notEqual(Report.sourceSignature(snapshotState, '2026-09'), contractSignature, '合同金额变化必须改变预计毛利来源签名');
  const costSourceSignature = Report.sourceSignature(snapshotState, '2026-09');
  const costFactSignature = Ops.cost(snapshotState, projectId).signature;
  snapshotState.operations.expenses.push({ id: 'project-cost-change', projectId, category: '材料', purpose: '其他', name: '九月项目成本补录', amount: 1000, date: '2026-09-22', due: '2026-09-30', status: 'approved' });
  assert.notEqual(Ops.cost(snapshotState, projectId).signature, costFactSignature, '测试前提：项目成本签名确实变化');
  assert.notEqual(Report.sourceSignature(snapshotState, '2026-09'), costSourceSignature, '项目成本签名变化必须改变预计毛利来源签名');
  const classificationSignature = Report.sourceSignature(snapshotState, '2026-09');
  snapshotState.receipts.find(row => row.id === 'receipt-2026-09').purposeCode = '设计服务';
  assert.notEqual(Report.sourceSignature(snapshotState, '2026-09'), classificationSignature, '收支分类变化必须改变快照签名');
  const archivedBusiness = Business.business({ period: '2026-09', today: '2026-10-23', state: snapshotState, customers: [customer], projects: [engineering], operating: Ops.overview(state), reconcileCount: 0, reviewCount: 0 });
  assert.equal(archivedBusiness.snapshotInfo.available, true);
  assert(archivedBusiness.receivable.rows.length > 0);
  const archivedRecord = Business.recordFor('receivable', { period: '2026-09', today: '2026-10-23', state: snapshotState, customers: [customer], projects: [engineering], operating: Ops.overview(state) }, archivedBusiness.receivable.rows[0].sourceId);
  assert.equal(archivedRecord.reminder.ready, false, '历史冻结余额不能按今天生成催款话术');
  assert.match(archivedRecord.reminder.reason, /当前月份/);
  pass('月快照仅过去月份、同源幂等、来源变化追加修订且旧版不可变');

  const historicalState = clone(state);
  historicalState.period = '2026-08';
  historicalState.operations.periodSnapshots = [];
  const historical = Business.business({ period: '2026-08', today: '2026-10-23', state: historicalState, customers: [customer], projects: [engineering], operating: Ops.overview(state), reconcileCount: 3, reviewCount: 2 });
  assert.equal(historical.balance.value, null);
  assert.equal(historical.receivable.rows.length, 0);
  assert.equal(historical.snapshotInfo.available, false);

  const frozenMissState = clone(state);
  frozenMissState.operations.periodSnapshots = [{ id: 'empty-snapshot', period: '2026-08', version: 1, status: 'final', asOf: '2026-08-31', sourceSignature: 'empty', report: { payableRows: [], receivableRows: [] } }];
  const frozenMiss = { period: '2026-08', today: '2026-10-23', state: frozenMissState, customers: [customer], projects: [engineering], operating: Ops.overview(frozenMissState) };
  assert.equal(Business.recordFor('payable', frozenMiss, 'expense-payable:promotion-expense'), null, '历史快照缺失的应付不得回退实时原单');
  assert.equal(Business.recordFor('receivable', frozenMiss, projectId + ':start'), null, '历史快照缺失的应收不得回退实时原单');
  pass('未知历史月份不混入当前余额、应收应付或待办');

  const reminder = Report.reminder({ projectId, customer: customer.name, installment: '开工款', due: '2026-10-10', remainingCents: 40000 }, '2026-10-23');
  assert.equal(reminder.ready, true);
  assert(reminder.text.includes('400.00元'));
  assert(reminder.text.includes('2026年10月10日'));
  assert.equal(reminder.file.name, '主体现场.jpg');
  assert(!/违约金|银行账号|手机号/.test(reminder.text));
  const missing = Report.reminder({ projectId, customer: customer.name, installment: '待确认款', due: '', remainingCents: 40000 }, '2026-10-23');
  assert.equal(missing.ready, false);
  assert.equal(missing.file, null);
  pass('催款文案只复制原金额日期并使用最新真实现场附件，缺依据保持空态');

  A.mutate(account => A.loginDemo(account, 'finance-wang'));
  const expenseId = M.mutate(book => M.apply(book, 'expense-submit', 'company:' + companyId, { category: '现场其他', purpose: '推广获客', name: '经营专项推广', amount: '300', date: A.localDate(), due: A.localDate(), recipient: '模拟推广平台', reference: 'PROMOTION-MANAGEMENT-001', file, token: 'promotion-management-001' }));
  A.mutate(account => A.loginDemo(account, 'boss'));
  M.mutate(book => M.apply(book, 'expense-approve', expenseId, { checked: true, note: '核对推广原费用' }));
  let chained = M.load();
  const payableId = 'expense-payable:' + expenseId;
  assert.equal(chained.operations.expenses.filter(row => row.id === expenseId).length, 1);
  assert.equal(chained.payables.filter(row => row.id === payableId).length, 1);
  A.mutate(account => A.loginDemo(account, 'finance-wang'));
  M.mutate(book => M.apply(book, 'pay', payableId, { amount: '100', date: A.localDate(), transaction: 'PROMOTION-PAY-001', file, checked: true }));
  chained = M.load();
  assert.equal(chained.payments.filter(row => row.payableId === payableId).length, 1);
  assert.equal(Report.analytics(chained, A.localDate().slice(0, 7), A.localDate()).paidComposition.rows.find(row => row.key === '推广获客').amountCents >= 10000, true);
  pass('推广费用复用原费用、稳定应付和实际付款链且不重复生成');

  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
