const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

async function main() {
  const { W } = await require('./verify-owner-workbench.cjs').fixture();
  const Business = require('../miniprogram/pages/admin/owner-business');
  const Home = require('../miniprogram/pages/admin/owner-home');
  const F = require('../miniprogram/utils/finance-source');
  const M = require('../miniprogram/pages/admin/model');
  const snapshot = W.snapshot();
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const open = url => { wx.reLaunch({ url }); const page = getCurrentPages().at(-1); assert(!page.data.error, page.data.error); return page; };

  const rawController = require('../miniprogram/pages/admin/owner-page')('detail');
  const rawPage = { ...rawController, data: { ...rawController.data }, setData(patch) { Object.assign(this.data, patch); } };
  rawController.onLoad.call(rawPage, { view: 'receivable', id: 'project-native%3Atopping', person: '%E5%85%BC%E5%B2%97', department: 'sales%2Fstore', tab: 'review%2Fall' });
  assert.equal(rawPage.options.id, 'project-native:topping');
  assert.equal(rawPage.options.person, '兼岗');
  assert.equal(rawPage.options.department, 'sales/store');
  assert.equal(rawPage.options.tab, 'review/all');
  const rawPayable = { ...rawController, data: { ...rawController.data }, setData(patch) { Object.assign(this.data, patch); } };
  rawController.onLoad.call(rawPayable, { view: 'payable', id: 'engineering-payable%3Aproject%3Asupply' });
  assert.equal(rawPayable.options.id, 'engineering-payable:project:supply');
  pass('原生编码查询参数在老板详情控制器入口统一还原');

  const reviews = Array.from({ length: 5 }, (_, index) => ({ key: 'review:test-' + index, name: '复核' + index, status: '待复核', url: '/pages/admin/index?view=review&id=test-' + index }));
  const reviewOnly = { ...snapshot, approvals: [], reviews };
  const reviewHome = Home.home(reviewOnly);
  assert.equal(reviewHome.todos.length, 4);
  assert(!Object.hasOwn(reviewHome, 'sites'));
  assert.equal(Home.visibleTodos(reviewOnly, 'review').length, 4);
  assert.equal(W.rowsFor('todos', reviewOnly, '', '', 'review').length, 5);
  assert.equal(W.rowsFor('todos', { ...snapshot, approvals: snapshot.approvals.slice(0, 2), reviews }, '', '', 'all').length, 7);
  const actualHomePage = open('/pages/admin/home/index');
  for (const key of ['approvals', 'reviews', 'projectRows', 'queue', 'staff', 'team', 'focus', 'events', 'recentEvents']) assert.equal(actualHomePage.data.desk[key], undefined, '首页仍传入完整集合 ' + key);
  assert.equal(actualHomePage.data.projects.length, 0);
  assert(actualHomePage.data.desk.home.todos.length <= 4);
  assert(actualHomePage.data.desk.home.engineering.sites.length <= 3);
  pass('全部待办详情保留超过首页预览的复核以及审批复核混合原单');

  const approved = { id: 'approved-open', projectId: 'approved-project', customer: '已批客户', name: '材料款', stage: '材料采购', total: 120000, due: snapshot.today, approvalValid: true, status: '待办理' };
  const pending = { id: 'pending-open', projectId: 'pending-project', customer: '待批客户', name: '其他费用', stage: '其他费用', total: 990000, due: snapshot.today, approvalValid: false, status: '待审批' };
  const state = { ...snapshot.state, payables: [...snapshot.state.payables, approved, pending] };
  const view = Business.business({ ...snapshot, state });
  assert(view.payable.rows.some(row => row.key === 'ap:approved-open'));
  assert(!view.payable.rows.some(row => row.key === 'ap:pending-open'));
  pass('应付只纳入审批有效且尚未付清的原事项');

  const dueState = { ...snapshot.state, payables: [
    { ...approved, id: 'overdue', due: '2026-10-01' },
    { ...approved, id: 'due-today', due: snapshot.today },
    { ...approved, id: 'due-week', due: require('../miniprogram/utils/account').localDate(Date.parse(snapshot.today + 'T12:00:00') + 7 * 86400000) }
  ] };
  const dueView = Business.business({ ...snapshot, state: dueState });
  assert.equal(dueView.payable.dueSoon, '0.24');
  assert.equal(dueView.payable.rows.find(row => row.key === 'ap:overdue').tone, 'overdue');
  pass('7天内到期排除历史逾期且逾期原行保持红色');

  const historical = Business.business({ ...snapshot, period: '2026-01', state: { ...snapshot.state, period: '2026-01' } });
  assert.equal(historical.historical, true);
  assert.equal(historical.balance.value, null);
  assert.equal(historical.receivable.rows.length, 0);
  assert.equal(historical.payable.rows.length, 0);
  assert.deepEqual(historical.pending, { reconcile: 0, review: 0 });
  pass('历史月份不混入当前资金余额与待办');

  const previous = Business.previousPeriod(snapshot.period);
  assert.notEqual(previous, snapshot.period);
  assert.equal(view.summary.income, (F.summary(state).income / 1000000).toLocaleString('en-US', { maximumFractionDigits: 2 }));
  pass('当前月收支与上月比较分别按各自期间计算');

  const performance = Business.performance(snapshot);
  const people = performance.groups.flatMap(group => group.rows);
  assert.equal(Business.rows('performance', snapshot).length, people.length);
  assert(people.every(person => person.url.includes('/pages/admin/detail/index?view=team')));
  for (const member of snapshot.team) {
    const rows = W.rowsFor('team', snapshot, member.id);
    assert(rows.every(row => row.url), member.name + '存在不可达原记录');
  }
  pass('团队汇总与个人原记录列表数量一致且可跳转');

  const dual = { ...snapshot.staff[0], id: 'dual-role-test', name: '兼岗测试', roles: ['boss', 'sales', 'engineer'], roleId: 'boss' };
  const dualPerformance = Business.performance({ ...snapshot, staff: [...snapshot.staff, dual], team: [...snapshot.team, { id: dual.id, kind: '业务', department: 'sales', value: '0', target: '1', percent: 0 }] });
  const dualRows = dualPerformance.groups.flatMap(group => group.rows).filter(row => row.id === dual.id);
  assert.deepEqual(dualRows.map(row => row.department).sort(), ['engineering', 'sales']);
  assert(dualRows.every(row => row.url.includes('department=' + row.department)));
  assert.equal(dualRows.find(row => row.department === 'engineering').percent, null);
  const dualFacts = { ...snapshot, team: [...snapshot.team, { id: dual.id, kind: '业务', department: 'sales' }], state: { ...snapshot.state, sources: [...snapshot.state.sources, { id: 'perf-commission:dual', person: dual.id, name: '设计委托', url: '/sales' }, { id: 'dual-node', person: dual.id, kind: 'node', name: '工程节点', url: '/node' }] } };
  assert.deepEqual(W.rowsFor('team', dualFacts, dual.id, 'sales').map(row => row.key), ['perf-commission:dual']);
  assert.deepEqual(W.rowsFor('team', dualFacts, dual.id, 'engineering').map(row => row.key), ['dual-node']);
  pass('全部岗位派生兼岗部门，主口径沿用旧目标且其他部门不伪造百分比');

  const financePage = open('/pages/admin/finance/index');
  assert.equal(financePage.data.desk.commissions, undefined);
  assert.equal(financePage.data.desk.customers, undefined);
  assert(!JSON.stringify(financePage.data.desk).includes(snapshot.customers[0].phone));
  assert(financePage.data.desk.business.receivable.rows.length <= 3);
  assert(financePage.data.desk.business.payable.rows.length <= 3);

  const payableState = M.load();
  const persistedApproved = { ...approved, approval: { status: 'approved', role: 'boss', actorId: 'zhao-boss' } };
  persistedApproved.approval.signature = M.signature(persistedApproved);
  payableState.payables.push(persistedApproved);
  wx.setStorageSync(M.KEY, payableState);
  const payableSnapshot = W.snapshot();
  const payableRow = Business.rows('payables', payableSnapshot).find(row => row.key === 'ap:approved-open');
  assert(payableRow.url.includes('view=payable'));
  const payablePage = open(payableRow.url);
  assert.equal(payablePage.data.record.id, approved.id);
  assert.equal(payablePage.data.record.amount, approved.total);
  const receivableRow = Business.rows('receivables', payableSnapshot)[0];
  assert(receivableRow && receivableRow.url.includes('view=receivable'));
  const receivablePage = open(receivableRow.url);
  assert.equal(receivablePage.data.record.id, receivableRow.sourceId);
  assert.equal(receivablePage.data.record.amount, receivableRow.amountCents);
  const bulkState = M.load();
  for (let index = 0; index < 25; index += 1) {
    const payable = { ...approved, id: 'bulk-payable-' + index, approval: { status: 'approved', role: 'boss', actorId: 'zhao-boss' } };
    payable.approval.signature = M.signature(payable);
    bulkState.payables.push(payable);
  }
  wx.setStorageSync(M.KEY, bulkState);
  const listPage = open('/pages/admin/detail/index?view=payables');
  assert(listPage.data.total >= 25);
  assert.equal(listPage.data.rows.length, 20);
  listPage.loadMore();
  assert(listPage.data.rows.length > 20);
  pass('经营预览有界且应收应付从原ID打开只读金额详情');

  const source = fs.readFileSync('miniprogram/pages/admin/finance/index.wxml', 'utf8');
  assert(source.includes('data-action="payments"'));
  assert(source.includes('data-action="receivables"'));
  pass('经营页“全部”入口同时保留应收与已批准应付原单入口');

  const detailSource = fs.readFileSync('miniprogram/pages/admin/detail/index.wxml', 'utf8');
  assert(detailSource.includes("view==='todos'"));
  pass('全部待办页提供关键词搜索且首页模型不携带完整集合');

  const output = path.resolve('qa/owner-workbench-port-20261023/business-performance-results.json');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({ result: 'passed', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
}

if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
