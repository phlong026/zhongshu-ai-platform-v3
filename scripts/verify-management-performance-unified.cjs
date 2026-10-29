const assert = require('node:assert/strict');

const Performance = require('../miniprogram/pages/admin/owner-performance');

const period = '2026-10';
const staff = [
  { id: 'all-business', name: '全业务兼岗', active: true, roleId: 'sales', roles: ['sales', 'hq-operations', 'hq-design-admin'] },
  { id: 'engineer', name: '工程', active: true, roleId: 'engineer', roles: ['engineer'] },
  { id: 'finance', name: '财务', active: true, roleId: 'finance', roles: ['finance'] }
];
const snapshot = {
  period,
  today: period + '-23',
  staff,
  team: [{ id: 'all-business', department: 'operations' }],
  customers: [],
  commissions: [],
  metrics: [],
  goals: {
    version: 4,
    staff: { 'all-business': 6 },
    departmentGoals: {
      sales: { commissionAmount: 100000, collectionsAmount: 50000 },
      operations: { activityTotal: 12, publications: 8 },
      design: { approvedDeliverables: 5, onTimeDeliverables: 4 },
      engineering: { completedNodes: 7 },
      finance: { registrations: 9 }
    },
    staffDepartmentGoals: {
      'all-business': {
        sales: { commissionAmount: 60000 },
        operations: { activityTotal: 6 },
        design: { approvedDeliverables: 3 }
      }
    }
  },
  state: {
    archives: [], receipts: [], payments: [], refunds: [], operations: { funds: [] },
    sources: [
      { id: 'perf-commission:1', kind: 'contract', person: 'all-business', date: period + '-01', amount: 200000, url: '/sales' },
      { id: 'publication:1', kind: 'publication', person: 'all-business', date: period + '-02', url: '/operations' },
      { id: 'design:1', kind: 'design', person: 'all-business', date: period + '-03', onTime: true, url: '/design' }
    ]
  }
};

assert.deepEqual(Performance.DEPARTMENTS.map(row => row.id), ['business', 'engineering', 'finance']);
assert.deepEqual(Performance.departmentsFor(staff[0]).map(row => row.id), ['business']);

const model = Performance.build(snapshot, 'all', 'all-business');
assert.deepEqual(model.filterOptions.map(row => row.id), ['all', 'business', 'engineering', 'finance']);
const business = model.groups.find(group => group.key === 'business');
assert(business);
assert.equal(business.rows.filter(row => row.id === 'all-business').length, 1);
assert.deepEqual(business.rows[0].metrics.map(row => row.id), [
  'commissionAmount', 'collectionsAmount', 'effectiveCustomers',
  'activityTotal', 'publications', 'effectiveLeads',
  'approvedDeliverables', 'onTimeDeliverables', 'overdueDeliverables'
]);
assert.equal(business.rows[0].metrics.find(row => row.id === 'commissionAmount').target, 60000);
assert.equal(business.rows[0].metrics.find(row => row.id === 'activityTotal').target, 6);
assert.equal(business.rows[0].metrics.find(row => row.id === 'approvedDeliverables').target, 3);
assert.deepEqual(business.rows[0].recentRecords.map(row => row.sourceId).sort(), ['design:1', 'perf-commission:1', 'publication:1']);

const scalarOnly = JSON.parse(JSON.stringify(snapshot));
scalarOnly.goals.staffDepartmentGoals = {};
const scalarMetrics = Performance.build(scalarOnly, 'business').groups[0].rows[0].metrics;
assert.equal(scalarMetrics.find(row => row.id === 'activityTotal').target, 6);
assert.equal(scalarMetrics.find(row => row.id === 'commissionAmount').target, null);

const salesScalar = JSON.parse(JSON.stringify(snapshot));
salesScalar.team[0].department = 'sales';
salesScalar.goals.staff['all-business'] = 1234.56;
salesScalar.goals.staffDepartmentGoals = {};
assert.equal(Performance.formFor(salesScalar).staffDepartmentGoals['all-business'].business.commissionAmount, 1234.56);
assert.equal(Performance.validateGoals(salesScalar, Performance.formFor(salesScalar)).staff['all-business'], 1234.56);

const form = Performance.formFor(snapshot);
assert.deepEqual(Object.keys(form.departmentGoals), ['business', 'engineering', 'finance']);
assert.equal(form.departmentGoals.business.commissionAmount, 100000);
assert.equal(form.departmentGoals.business.activityTotal, 12);
assert.equal(form.departmentGoals.business.approvedDeliverables, 5);
assert.deepEqual(Object.keys(form.staffDepartmentGoals['all-business']), ['business']);

const saved = Performance.validateGoals(snapshot, form);
assert.deepEqual(Object.keys(saved.departmentGoals), ['business', 'engineering', 'finance']);
assert(!Object.hasOwn(saved.departmentGoals, 'sales'));
assert.deepEqual(Object.keys(saved.staffDepartmentGoals['all-business']), ['business']);

const archived = { ...snapshot, state: { ...snapshot.state, archives: [{ id: 'closed', dynamic: true, period, status: '已归档' }] } };
assert.doesNotThrow(() => Performance.validateGoals(archived, Performance.formFor(archived)));
const changed = Performance.formFor(archived);
changed.departmentGoals.business.activityTotal = 99;
assert.throws(() => Performance.validateGoals(archived, changed), /已归档/);

const emptyGoals = { ...snapshot, goals: { version:0, staff:{}, departmentGoals:{}, staffDepartmentGoals:{} } };
const emptyForm = Performance.formFor(emptyGoals);
const renderedForm = Performance.settingsFor({ ...emptyGoals, goals:{ ...emptyGoals.goals, ...emptyForm } });
assert.equal(renderedForm.departments[0].metrics[0].value, '', '未设置目标不能在二次派生表单中变成零');
console.log(JSON.stringify({ result: 'passed', checks: 11 }));
