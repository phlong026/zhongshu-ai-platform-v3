const assert = require('node:assert/strict');

const storage = {
  'zhongshu-engineering-v1': { version: 1, projects: [] },
  'zhongshu-business-v3': { version: 1, customers: [] }
};
global.wx = {
  getStorageSync(key) { return storage[key]; },
  setStorageSync(key, value) { storage[key] = value; }
};

const Home = require('../miniprogram/pages/admin/owner-home');
const Business = require('../miniprogram/pages/admin/owner-business');
const Customer = require('../miniprogram/utils/business');

const customers = [
  { id: 'legacy-contract', name: '旧合同客户', contract: { total: 500000 }, follows: [], quotes: [], budgets: [], plans: [], facades: [], deliverables: [], changes: [] },
  { id: 'commission', name: '设计委托客户', businessFlow: { type: 'sales-offline-v1', submittedAt: '2026-10-10' }, serviceOrders: [{ total: 12000 }], follows: [], quotes: [], budgets: [], plans: [], facades: [], deliverables: [], changes: [] },
  { id: 'budget', name: '方案客户', follows: [], quotes: [], budgets: [{ id: 'budget-1' }], plans: [], facades: [], deliverables: [], changes: [] }
];
const snapshot = {
  period: '2026-10', today: '2026-10-23', sales: '1.2', income: '0', reconcileCount: 0,
  customers, commissions: [customers[1]], newCustomers: customers, metrics: [{ percent: null }, { percent: null }, { percent: null }],
  approvals: [], reviews: [], projects: [], state: { period: '2026-10', payments: [], payables: [], receipts: [], refunds: [], operations: { funds: [] }, sources: [] },
  operating: { receivable: 0, overdue: 0, cash: null, rows: [] }, team: [], staff: [], goals: {}
};

const home = Home.home(snapshot);
assert.equal(home.six[2].value, '1', '签约单数必须使用设计委托事实，不能恢复旧合同计数');
assert.deepEqual(home.business.stages.map(row => row.label), Customer.STAGES, '业务概况必须沿用 V3 七阶段');

const approved = { id: 'approved', projectId: 'p1', customer: '甲', name: '材料款', stage: '材料采购', total: 10000, due: '2026-10-25', approvalValid: true, status: '待办理' };
const pending = { id: 'pending', projectId: 'p2', customer: '乙', name: '未批费用', stage: '其他费用', total: 90000, due: '2026-10-24', approvalValid: false, status: '待审批' };
const business = Business.business({ ...snapshot, state: { ...snapshot.state, payables: [approved, pending] } });
assert.equal(business.payable.count, 1, '应付账款只能包含已批准未付事项');
assert.equal(business.payable.rows[0].key, 'ap:approved');

const performance = Business.performance({ ...snapshot, goals: { collections: 20000, effective: 3, staff: {} } });
assert.deepEqual(performance.targets.map(row => row.label), ['设计委托金额', '回款金额', '有效客户', '工程节点']);

console.log('PASS 老板四栏核心语义：设计委托、七阶段、审批后应付、扩展目标');
