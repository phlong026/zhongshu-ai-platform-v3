const assert = require('node:assert/strict');

global.wx = {
  getStorageSync() { return {}; },
  setStorageSync() {}
};

const Home = require('../miniprogram/pages/admin/owner-home');

const customers = [
  {
    id: 'commission-october', projectId: 'project-commission', name: '十月委托', owner: '张业务', ownerId: 'sales-a',
    createdAt: '2026-10-02', businessFlow: { type: 'sales-offline-v1', submittedAt: '2026-10-08' }, serviceOrders: [{ total: 20000 }],
    follows: [{ date: '2026-10-06', method: '电话', note: '确认宅基地尺寸' }, { date: '2026-10-12', method: '到店', note: '到店确认设计需求' }], quotes: []
  },
  {
    id: 'current-lead', projectId: 'project-lead', name: '本月客户', owner: '刘业务', ownerId: 'sales-b', createdAt: '2026-10-03',
    follows: [{ date: '2026-10-05', method: '微信', note: '登记客资，系统生成' }, { date: '2026-10-09', method: '电话', note: '确认实际建房计划', appointment: '2026-10-25', appointmentType: '到店' }], quotes: []
  },
  { id: 'old-lead', projectId: 'project-old', name: '历史客户', createdAt: '2026-09-03', follows: [{ date: '2026-09-04', method: '到店', note: '九月到店' }], quotes: [] }
];

const snapshot = {
  period: '2026-10', today: '2026-10-23', sales: '2', income: '1.2', reconcileCount: 0,
  customers, commissions: [customers[0]], newCustomers: customers.slice(0, 2), metrics: [{ percent: null }, { percent: null }, { percent: null }],
  approvals: [], reviews: [], projects: [], staff: [
    { id: 'sales-a', name: '张业务', active: true },
    { id: 'sales-b', name: '刘业务', active: true },
    { id: 'left', name: '陈业务', active: false }
  ],
  state: {
    period: '2026-10', payments: [], payables: [], refunds: [], operations: { funds: [] }, sources: [
      { id: 'perf-publication:content-1', kind: 'publication', person: 'sales-a', customer: '十月内容', name: '已登记发布链接', date: '2026-10-07', url: '/pages/growth/index?view=content&id=content-1' },
      { id: 'perf-lead:lead-1', kind: 'lead', person: 'sales-a', customer: '内容咨询客户', name: '有效客资转客户', date: '2026-10-11', url: '/pages/growth/index?view=lead&id=lead-1' },
      { id: 'perf-publication:old', kind: 'publication', person: 'sales-a', customer: '九月内容', name: '已登记发布链接', date: '2026-09-07', url: '/pages/growth/index?view=content&id=old' }
    ],
    receipts: [{ id: 'receipt-1', projectId: 'project-commission', customer: '十月委托', name: '工程到账', amount: 120000, status: '已核算', date: '2026-10-10' }],
    transfers: [],
    work: [
      { id: 'work-left', kind: 'customer-responsibility', customerId: 'customer-left', projectId: 'project-left', customer: '离岗客户', name: '客户跟进', stage: '需求沟通', owner: 'left', author: 'left' },
      { id: 'work-flagged', kind: 'customer-responsibility', customerId: 'customer-flagged', projectId: 'project-flagged', customer: '标记客户', name: '方案沟通', stage: '方案沟通', owner: 'sales-a', author: 'sales-a', handoverRequired: true },
      { id: 'work-normal', kind: 'customer-responsibility', customerId: 'current-lead', projectId: 'project-lead', customer: '本月客户', name: '客户跟进', stage: '需求沟通', owner: 'sales-b', author: 'sales-b' },
      { id: 'work-archived', kind: 'project-responsibility', engineeringId: 'archived-project', projectId: 'archived-project', customer: '归档项目', name: '工程项目负责', stage: '已归档 · 款项可收尾', owner: 'left', author: 'left' }
    ]
  },
  operating: { receivable: 0, overdue: 0, cash: null, rows: [] }, team: [], goals: {}
};

const home = Home.home(snapshot);
assert.deepEqual(home.six.map(row => row.key), ['metric:sales', 'metric:income', 'metric:commissions', 'metric:new-customers', 'metric:effective', 'metric:visits']);
assert(home.six.every(row => row.url.startsWith('/pages/admin/detail/index?view=metric&metric=')));

const metrics = Object.fromEntries(['sales', 'income', 'commissions', 'new-customers', 'effective', 'visits'].map(metric => [metric, Home.rows('metric', snapshot, { metric })]));
assert.equal(metrics.sales.reduce((sum, row) => sum + row.amount, 0), 2000000, '设计委托明细金额使用整数分');
assert.equal(metrics.income.reduce((sum, row) => sum + row.amount, 0), 120000);
assert.equal(metrics.commissions.length, 1);
assert.equal(metrics['new-customers'].length, 2);
assert.equal(metrics.effective.length, 2, '有效客户只按当月真实跟进计算');
assert.equal(metrics.visits.length, 1, '到店只认明确到店方式');
assert(metrics.visits[0].url.includes('commission-october'));

assert.equal(home.business.appointments.value, 1);
assert.equal(Home.rows('appointments', snapshot).length, 1);
assert.equal(home.business.acquisition.value, 2);
assert.equal(Home.rows('acquisition', snapshot).length, 2);
assert.equal(Home.rows('unknown', snapshot), null);

const handovers = Home.todoRows(snapshot, 'handover');
assert.deepEqual(handovers.map(row => row.key), ['handover:work-left', 'handover:work-flagged']);
assert(handovers.every(row => row.url.includes('view=transfer')));
assert.equal(home.groups.find(row => row.key === 'handover').count, 2);
assert(home.todos.length <= 4);

const transferred = JSON.parse(JSON.stringify(snapshot));
transferred.state.work.find(row => row.id === 'work-left').owner = 'sales-b';
transferred.state.work.find(row => row.id === 'work-flagged').owner = 'sales-b';
transferred.state.transfers = [
  { workId: 'work-left', from: 'left', to: 'sales-b', effectiveAt: '2026-10-23T10:00:00.000Z' },
  { workId: 'work-flagged', from: 'sales-a', to: 'sales-b', effectiveAt: '2026-10-23T10:01:00.000Z' }
];
assert.equal(Home.todoRows(transferred, 'handover').length, 0, '转给在岗人员后交接待办关闭，不要求增加审批状态');

console.log('PASS 管理首页指标、预约、内容获客与真实交接待办');
