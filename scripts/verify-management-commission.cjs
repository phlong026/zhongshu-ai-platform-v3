const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));
const store = {};
global.wx = {
  getStorageSync(key) { return store[key]; },
  setStorageSync(key, value) { store[key] = value; }
};

const companyId = 'commission-store';
const staff = [
  { id:'boss-1', personId:'person-boss', name:'老板甲', active:true, roles:['boss'] },
  { id:'sales-1', personId:'person-sales', name:'业务甲', active:true, roles:['sales'] },
  { id:'finance-1', personId:'person-finance', name:'财务甲', active:true, roles:['finance'] },
  { id:'other-boss', personId:'person-other', name:'异店老板', active:true, roles:['boss'] }
];
const project = {
  id:'engineering-1', projectId:'project-1', companyId, customer:'周先生', name:'周先生 · 自建房', managerId:'sales-1',
  recordFlow:{ contract:{ version:1, total:10000000, first:3000000, file:{ name:'建造合同.pdf' } } },
  plan:{ nodes:[] }
};
store['zhongshu-engineering-v1'] = { version:1, projects:[project] };
store['zhongshu-business-v3'] = { version:1, customers:[] };
store['zhongshu-account-v1'] = {
  session:{ loggedIn:true, companyId, staffId:'boss-1', userId:'demo-boss-1', role:'boss' },
  profile:{ name:'老板甲' }, companies:[{ id:companyId, type:'store', roles:['boss','finance','sales'] }]
};

const Ops = require('../miniprogram/utils/operations');
const Commission = require('../miniprogram/utils/collection-commission');
const F = require('../miniprogram/utils/finance-source');

function state() {
  const s = {
    version:1, revision:1, companyId, staff:clone(staff), receipts:[], refunds:[], payables:[], payments:[],
    operations:{ plans:[], collectionCommissionPolicies:[], events:[] }
  };
  const signature = Ops.contract(Ops.project('project-1', s)).signature;
  s.operations.plans.push({ id:'plan-1', projectId:'project-1', signature, version:1, status:'approved', installments:[
    { id:'start', name:'开工款', stage:'start', amount:3000000, due:'2026-10-01' },
    { id:'topping', name:'封顶款', stage:'topping', amount:3000000, due:'2026-11-01' },
    { id:'delivery', name:'尾款', stage:'delivery', amount:4000000, due:'2026-12-01' }
  ] });
  return s;
}
function login(staffId, role, company = companyId) {
  store['zhongshu-account-v1'] = {
    session:{ loggedIn:true, companyId:company, staffId, userId:'demo-'+staffId, role },
    profile:{ name:staff.find(row => row.id === staffId)?.name || staffId },
    companies:[{ id:company, type:'store', roles:[role] }]
  };
  if (company !== companyId) store['zhongshu-management-v1'] = { version:1, companyId:company, staff:[staff.find(row => row.id === staffId)], books:{ [companyId]:{ version:1, companyId, staff:clone(staff) } } };
  else store['zhongshu-management-v1'] = { version:1, companyId, staff:clone(staff), books:{} };
}
const proof = { name:'提成确认单.pdf' };
const policyForm = { amount:'100.01', assignee:'sales-1', file:proof, checked:true, note:'双方已核对固定提成总额', expectedVersion:0 };

let s = state();
login('finance-1','finance');
assert.throws(() => Commission.apply(s,'commission-policy','project-1',policyForm), /老板/);
login('boss-1','boss');
let result = Commission.apply(s,'commission-policy','project-1',policyForm);
assert.equal(result.id, 'collection-commission-policy:project-1');
let policy = s.operations.collectionCommissionPolicies[0];
assert.equal(policy.total, 10001);
assert.equal(policy.stages.start.amount, 5000);
assert.equal(policy.stages.topping.amount, 5001, '奇数分余数归封顶款阶段');
assert.equal(policy.personId, 'person-sales', '提成归属固定为政策确认时的真实人员');
assert.throws(() => Commission.apply(s,'commission-policy','project-1',{ ...policyForm, expectedVersion:0 }), /版本/);
Commission.apply(s,'commission-policy','project-1',{ ...policyForm, note:'第二次确认，保留第一版历史', expectedVersion:1 });
policy = s.operations.collectionCommissionPolicies[0];
assert.equal(policy.version, 2);
assert.equal(policy.history.length, 1);
assert.equal(policy.history[0].version, 1);

login('finance-1','finance');
s.receipts.push({ id:'receipt-start-1', projectId:'project-1', customer:'周先生', name:'开工款', receiptStage:'start', amount:2999999, status:'已核算', date:'2026-10-10' });
assert.throws(() => Commission.apply(s,'commission-release','project-1',{ stage:'start', expectedVersion:2 }), /足额/);
s.receipts.push({ id:'receipt-start-2', projectId:'project-1', customer:'周先生', name:'开工款补款', receiptStage:'start', amount:1, status:'已核算', date:'2026-10-11' });
result = Commission.apply(s,'commission-release','project-1',{ stage:'start', expectedVersion:2 });
assert.equal(result.id, 'collection-commission-payable:project-1:start');
assert.equal(s.payables.length, 1);
assert.equal(Commission.apply(s,'commission-release','project-1',{ stage:'start', expectedVersion:2 }).id, result.id);
assert.equal(s.payables.length, 1, '重复刷新不能重复生成提成应付');
s = F.merge(s);
let q = s.payables.find(row => row.id === result.id);
assert(q, '提成应付经过统一财务账簿合并后仍须保留');
assert.equal(q.total, 5000);
assert.equal(q.approval.role, 'boss');
assert.equal(q.approval.signature, F.signature(q));
assert.doesNotThrow(() => Commission.assertPayment(s,q));

assert.throws(() => Commission.apply(s,'commission-release','project-1',{ stage:'topping', expectedVersion:2 }), /足额/);
s.receipts.push({ id:'receipt-topping-1', projectId:'project-1', customer:'周先生', name:'封顶款', receiptStage:'topping', amount:3000000, status:'已备案', date:'2026-11-10' });
Commission.apply(s,'commission-release','project-1',{ stage:'topping', expectedVersion:2 });
assert.equal(s.payables.length, 2);
assert.equal(s.payables.find(row => row.commissionSource.stage === 'topping').total, 5001);

s.refunds.push({ id:'refund-start-1', receiptId:'receipt-start-1', projectId:'project-1', amount:100000, status:'已核实', date:'2026-10-20' });
assert.throws(() => Commission.assertPayment(s,q), /退款|冲正|不足/);
let rows = Commission.rows(s);
let start = rows[0].stages.find(row => row.stage === 'start');
assert.equal(start.eligible, false);
assert.equal(start.reviewDue, true);
assert.equal(start.paid, 0);

s.payments.push({ id:'commission-payment-1', payableId:q.id, projectId:q.projectId, amount:3000, status:'已核实', date:'2026-10-18' });
rows = Commission.rows(s);
start = rows[0].stages.find(row => row.stage === 'start');
assert.equal(start.paid, 3000);
assert.equal(start.outstanding, 2000);
assert.equal(start.reviewDue, true, '已付差额只进入人工核对，不自动追回或篡改旧支付');
assert.equal(s.payments[0].amount, 3000);

login('boss-1','boss');
assert.throws(() => Commission.apply(s,'commission-reconcile','project-1',{ stage:'start', expectedVersion:2, file:{ name:'人工核对.pdf' }, checked:true, note:'已核对退款影响' }), /足额/);
s.receipts.push({ id:'receipt-start-restore', projectId:'project-1', customer:'周先生', name:'开工款恢复到账', receiptStage:'start', amount:100000, status:'已核算', date:'2026-10-22' });
Commission.apply(s,'commission-reconcile','project-1',{ stage:'start', expectedVersion:2, file:{ name:'人工核对.pdf' }, checked:true, note:'恢复到账后核对原提成应付' });
assert.doesNotThrow(() => Commission.assertPayment(s,q));
assert.equal(Commission.rows(s)[0].stages.find(row => row.stage === 'start').reviewDue, false);
s.receipts.find(row => row.id === 'receipt-start-2').revisions = [{ amount:2, note:'原收款冲正后更正1分' }];
assert.throws(() => Commission.assertPayment(s,q), /冲正|更正|核对/);
Commission.apply(s,'commission-reconcile','project-1',{ stage:'start', expectedVersion:2, file:{ name:'冲正核对.pdf' }, checked:true, note:'冲正后净实收仍足额，保留原已付记录' });
assert.doesNotThrow(() => Commission.assertPayment(s,q));

assert.throws(() => Commission.apply(s,'commission-policy','project-1',{ ...policyForm, amount:'120', expectedVersion:2 }), /应付|付款/);
project.managerId = 'another-owner';
assert.equal(Commission.rows(s)[0].assigneeId, 'sales-1', '后续责任转交不改写历史提成归属');

login('other-boss','boss','other-company');
assert.throws(() => Commission.apply(s,'commission-policy','project-1',policyForm), /组织|授权/);

const totalExpense = s.payments.filter(row => row.status === '已核实').reduce((sum,row) => sum + row.amount, 0);
assert.equal(totalExpense, 3000, '政策、应付和付款不能被累计成多次实际支出');
console.log('PASS 分阶段回款提成：固定政策、足额解锁、退款暂停、人工核对与幂等');
