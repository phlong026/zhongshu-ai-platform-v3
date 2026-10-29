const assert = require('node:assert/strict');
const fs = require('node:fs');

const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const store = {};
global.wx = {
  getStorageSync(key) { return clone(store[key]); },
  setStorageSync(key, value) { store[key] = clone(value); },
  removeStorageSync(key) { delete store[key]; }
};

const keys = require('../miniprogram/utils/storage-keys');
store[keys.management] = require('../miniprogram/utils/management-seed')();
store[keys.business] = { version:1, customers:[] };
store[keys.engineering] = { version:1, projects:[] };
store[keys.growth] = { version:1, contents:[], leads:[], events:[] };

const Account = require('../miniprogram/utils/account');
const Model = require('../miniprogram/pages/admin/model');
const Payroll = require('../miniprogram/utils/payroll');
const Finance = require('../miniprogram/utils/finance-source');
const Ops = require('../miniprogram/utils/operations');

const login = (id, role) => Account.mutate(account => {
  Account.loginDemo(account, id);
  if (role) Account.switchIdentity(account, account.session.companyId, role);
});

const state = Model.seed();
Ops.init(state);
state.period = '2026-10';
state.archives.push({
  id:'legacy-cycle-new', person:'engineering-dual', period:'2026-10', dynamic:true, status:'已归档',
  snapshot:{ actuals:{ nodes:3 }, sources:[] }
});
state.operations.payrollPolicies.push({
  id:'legacy-policy-new', person:'engineering-dual', period:'2026-10', version:1,
  base:0, metric:'nodes', rate:10000, file:{ name:'旧薪酬规则.pdf' }
});

login('boss', 'boss');
const beforePolicy = JSON.stringify(state);
assert.throws(
  () => Model.apply(state, 'payroll-policy', 'legacy-cycle-new', { metric:'nodes', base:'0', rate:'100', file:{ name:'新规则.pdf' }, checked:true, note:'不应再创建' }),
  /已停用|绩效任务/
);
assert.equal(JSON.stringify(state), beforePolicy, '旧规则命令被拒后不得改写历史账簿');

login('finance-dual', 'finance');
const beforeSubmit = JSON.stringify(state);
assert.throws(
  () => Model.apply(state, 'payroll-submit', 'legacy-cycle-new', { checked:true, note:'不应再生成逐人应付' }),
  /已停用|绩效任务/
);
assert.equal(JSON.stringify(state), beforeSubmit, '旧逐人结算命令被拒后不得生成重复应付');

const settlement = {
  id:'legacy-payroll-settlement', cycleId:'legacy-cycle-paid', person:'engineering-dual', period:'2026-10',
  amount:30000, status:'approved', policy:{ id:'legacy-policy-paid', metric:'nodes', file:{ name:'历史制度.pdf' } }
};
state.archives.push({
  id:settlement.cycleId, person:settlement.person, period:settlement.period, dynamic:true, status:'已归档',
  snapshot:{ actuals:{ nodes:3 }, sources:[] }
});
state.operations.payrollPolicies.push({ ...settlement.policy, person:settlement.person, period:settlement.period, base:0, rate:10000 });
state.operations.payrollSettlements.push(settlement);
const payable = {
  id:'payroll-payable:' + settlement.id, payrollId:settlement.id, companyId:state.companyId,
  projectId:'company:' + state.companyId, customer:'2026-10绩效结算', name:'张工程', stage:'绩效薪酬',
  total:settlement.amount, agreement:settlement.policy.file, recipient:'张工程', status:'待办理'
};
payable.approval = { id:'payroll-approval:' + settlement.id, status:'approved', signature:Finance.signature(payable) };
state.payables.push(payable);

const rows = Payroll.rows(state);
assert(rows.length > 0 && rows.every(row => row.settlement && state.payables.some(q => q.id === row.payableId)), '旧页只展示已生成应付的历史档案');
assert(!rows.some(row => row.id === 'legacy-cycle-new'), '未生成结算的新归档不得进入旧薪酬页');
assert.equal(rows.find(row => row.id === settlement.cycleId)?.settlement.id, settlement.id);

const paymentId = Model.apply(state, 'pay', payable.id, {
  amount:'300', date:require('../miniprogram/utils/account').localDate(), transaction:'LEGACY-PAYROLL-PAID',
  file:{ name:'历史工资付款凭据.pdf' }, checked:true, expectedSignature:Finance.signature(payable)
});
assert(state.payments.some(row => row.id === paymentId && row.payableId === payable.id && row.amount === 30000));
assert.equal(payable.status, '已付清', '既有工资欠款仍沿通用付款原单登记');

const wxml = fs.readFileSync('miniprogram/pages/admin/operations/index.wxml', 'utf8');
assert(!/data-action="payroll-(policy|submit)"/.test(wxml), '旧薪酬直链不得再渲染新建按钮');
assert(!/确认本期计费规则|按归档结果生成结算/.test(wxml), '旧新建入口文案应退役');
assert(/绩效任务/.test(wxml), '旧页应引导到绩效任务主链');

async function verifyDirectPage() {
  const account = clone(store[keys.account]);
  const runtime = await require('./role-page-runtime.cjs').connect();
  await runtime.callWxMethod('setStorageSync', keys.account, account);
  await runtime.callWxMethod('setStorageSync', keys.management, state);
  await runtime.callWxMethod('setStorageSync', keys.business, store[keys.business]);
  await runtime.callWxMethod('setStorageSync', keys.engineering, store[keys.engineering]);
  await runtime.callWxMethod('reLaunch', { url:'/pages/admin/operations/index?view=payroll' });
  const page = await runtime.currentPage();
  assert.equal(await page.$('[data-action="payroll-policy"]'), null);
  assert.equal(await page.$('[data-action="payroll-submit"]'), null);
  assert(await page.$('[data-url="/pages/admin/tasks/index"]'), '旧直链应显示绩效任务入口');
  await runtime.disconnect();
}

verifyDirectPage().then(() => {
  console.log('PASS 旧薪酬规则与逐人结算退役，历史档案及既有应付仍可付款');
}).catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
