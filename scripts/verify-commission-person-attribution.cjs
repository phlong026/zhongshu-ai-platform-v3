const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));
const keys = require('../miniprogram/utils/storage-keys');
const store = {};
global.wx = {
  getStorageSync(key) { return clone(store[key]); },
  setStorageSync(key, value) { store[key] = clone(value); },
  removeStorageSync(key) { delete store[key]; }
};

store[keys.management] = {
  version:1, companyId:'acme', payments:[],
  staff:[
    { id:'owner-a', personId:'person-owner-a', name:'业务负责人', active:true, roles:['sales'], teamIds:{ sales:'sales' } },
    { id:'leader-a', personId:'person-leader-a', name:'业务主管', active:true, roles:['sales-lead'], managedTeamIds:['sales'], teamIds:{ sales:'sales' } }
  ]
};
store[keys.account] = {
  version:1,
  session:{ loggedIn:true, companyId:'acme', staffId:'leader-a', userId:'leader-user', role:'sales-lead' },
  profile:{ name:'业务主管' },
  companies:[{ id:'acme', type:'store', name:'测试门店', roles:['sales-lead'] }]
};

const SalesFlow = require('../miniprogram/utils/sales-flow');
const customer = {
  id:'customer-attribution', projectId:'project-attribution', companyId:'acme', teamId:'sales',
  name:'归属证据客户', ownerId:'owner-a', createdById:'owner-a',
  budgets:[{ version:1, area:300, total:100000, parameters:{ width:12, depth:15, floors:2, area:300 } }],
  request:null, serviceOrders:[], designPayments:[], firstPayment:null, tailPayment:null
};
const submitted = SalesFlow.transition(customer, 'commission-submit', {
  text:'两层乡墅设计需求', scope:'平面、立面及施工图', total:'10000', first:'5000',
  recipient:'总部设计服务中心', checked:true, __budgetVersion:1
}, '2026-10-30T08:00:00.000Z');
const order = submitted.serviceOrders.at(-1);

assert.equal(order.responsibleId, 'owner-a', '原佣金责任仍归原业务负责人 staffId');
assert.equal(submitted.businessFlow.responsibleId, 'owner-a', '委托主链责任 staffId 不变');
assert.equal(order.submittedById, 'leader-a', '代提交人须与原负责人分开留痕');
assert.equal(submitted.businessFlow.submittedById, 'leader-a');
assert.equal(order.responsiblePersonId, 'person-owner-a', '费用约定须冻结委托发生时真实负责人 person');
assert.equal(submitted.businessFlow.responsiblePersonId, 'person-owner-a', '委托主链须冻结同一真实负责人 person');
assert.notEqual(order.responsiblePersonId, 'person-leader-a', '不能把代提交人误记为佣金责任人');

store[keys.management].staff.find(row => row.id === 'owner-a').personId = 'replacement-owner-person';
assert.equal(order.responsiblePersonId, 'person-owner-a', '后续 staffId 换人不得回写历史费用约定归属');
assert.equal(submitted.businessFlow.responsiblePersonId, 'person-owner-a', '后续 staffId 换人不得回写历史委托归属');
assert.equal(order.responsibleId, 'owner-a', '历史 staffId 责任字段继续保留原值');

console.log('PASS 新委托冻结真实负责人 person，代提交与后续 staff 变化不改历史归属');
