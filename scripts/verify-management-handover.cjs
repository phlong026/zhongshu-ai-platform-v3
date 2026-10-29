const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));
const store = {};
global.wx = {
  getStorageSync(key) { return store[key]; },
  setStorageSync(key, value) { store[key] = value; }
};

const companyId = 'handover-store';
const staff = [
  { id:'boss-1', personId:'person-boss', name:'老板甲', active:true, roles:['boss'] },
  { id:'owner-1', personId:'person-owner', name:'负责人甲', active:true, roles:['owner'] },
  { id:'sales-1', personId:'person-sales', name:'业务甲', active:true, roles:['sales'] },
  { id:'sales-2', personId:'person-sales-2', name:'业务乙', active:true, roles:['sales'] },
  { id:'engineer-1', personId:'person-engineer', name:'工程甲', active:true, roles:['engineer'] }
];
const work = {
  id:'work:customer-1', kind:'customer-responsibility', customerId:'customer-1', projectId:'project-1',
  customer:'周先生', name:'客户跟进', stage:'方案沟通', owner:'sales-1', author:'sales-1'
};
store['zhongshu-business-v3'] = { version:1, customers:[{ id:'customer-1', projectId:'project-1', companyId, name:'周先生', ownerId:'sales-1' }] };
store['zhongshu-engineering-v1'] = { version:1, projects:[{ id:'engineering-1', projectId:'project-2', companyId, customer:'李先生', managerId:'engineer-1', recordFlow:{} }] };

function login(staffId, role, company = companyId) {
  const members = company === companyId ? staff : [{ id:staffId, personId:'other-person', name:'异店老板', active:true, roles:[role] }];
  store['zhongshu-management-v1'] = { version:1, companyId:company, staff:clone(members), books:{} };
  store['zhongshu-account-v1'] = {
    session:{ loggedIn:true, companyId:company, staffId, userId:'demo-' + staffId, role },
    profile:{ name:members.find(row => row.id === staffId)?.name || staffId },
    companies:[{ id:company, type:'store', roles:[role] }]
  };
}

const Handover = require('../miniprogram/utils/management-handover');
const Home = require('../miniprogram/pages/admin/owner-home');
const form = { checked:true, reason:'客户近期更换服务安排，需明确转交后续责任' };
const projectWork = { id:'engineering-work:engineering-1', kind:'project-responsibility', engineeringId:'engineering-1', projectId:'project-2', customer:'李先生', name:'工程项目负责', stage:'待开工', owner:'engineer-1', author:'engineer-1' };
const state = { companyId, staff:clone(staff), work:[clone(work), clone(projectWork)], transfers:[], events:[], managementHandoverFlags:{} };

login('sales-1','sales');
assert.throws(() => Handover.apply(state,'handover-mark',work.id,form), /老板|负责人/);
login('boss-1','boss','other-company');
assert.throws(() => Handover.apply(state,'handover-mark',work.id,form), /组织|公司/);
login('boss-1','boss');
assert.throws(() => Handover.apply(state,'handover-mark',work.id,{ checked:false, reason:'' }), /原因|核对/);

const marked = Handover.apply(state,'handover-mark',work.id,form);
assert.equal(marked.id, work.id);
let flag = Handover.load(state, work.id);
assert.equal(flag.active, true);
assert.equal(flag.version, 1);
assert.equal(flag.history.length, 1);
assert.equal(state.events.at(-1).action, 'handover-mark');
assert.equal(Handover.overlay(state).work[0].handoverRequired, true);

const snapshot = { approvals:[], reviews:[], staff:state.staff, state };
assert.deepEqual(Home.todoRows(snapshot, 'handover').map(row => row.key), ['handover:' + work.id], '明确标记后进入交接待办');

const transfer = {
  id:'transfer-1', workId:work.id, customerId:work.customerId, projectId:work.projectId,
  from:'sales-1', to:'sales-2', author:'sales-1', effectiveAt:new Date(Date.parse(flag.markedAt) + 1).toISOString()
};
state.transfers.push(transfer);
state.work[0].owner = 'sales-2';
assert.equal(Home.todoRows(snapshot, 'handover').length, 0, '真实转交后即使尚未写回关闭字段，待办也须关闭');
assert.equal(Handover.closeTransferred(state, transfer), true);
flag = Handover.load(state, work.id);
assert.equal(flag.active, false);
assert.equal(flag.closedByTransferId, transfer.id);
assert.equal(flag.history.length, 2);
assert.equal(state.events.at(-1).action, 'handover-close');

login('owner-1','owner');
Handover.apply(state,'handover-mark',work.id,{ checked:true, reason:'新安排发生后再次明确需要交接' });
flag = Handover.load(state, work.id);
assert.equal(flag.active, true, '新标记时间晚于上次转交时可重新打开');
assert.equal(flag.version, 2);
assert.equal(flag.history.length, 3);
assert.deepEqual(Home.todoRows(snapshot, 'handover').map(row => row.key), ['handover:' + work.id]);

login('boss-1','boss');
Handover.apply(state,'handover-mark',projectWork.id,{ checked:true, reason:'新委托工程责任需转给另一名在岗工程人员' });
assert.equal(Handover.pending(state, state.work[1]), true, '工程项目责任与客户责任使用同一标记闭环');

const legacyWork = { id:'legacy-content', customer:'秋季建房口播', name:'内容任务', owner:'sales-1', author:'sales-1' };
state.work.push(legacyWork);
Handover.apply(state,'handover-mark',legacyWork.id,{ checked:true, reason:'离岗前需转交已登记的内部内容任务' });
assert.equal(Handover.pending(state, legacyWork), true, '原有无 kind 内部任务仍可沿原记录标记交接');
const legacyFlag = Handover.load(state, legacyWork.id);
const legacyTransfer = { id:'legacy-transfer', workId:legacyWork.id, from:'sales-1', to:'sales-2', effectiveAt:new Date(Date.parse(legacyFlag.markedAt) + 1).toISOString() };
state.transfers.push(legacyTransfer);
legacyWork.owner = 'sales-2';
assert.equal(Handover.closeTransferred(state,legacyTransfer),true);
assert.equal(Handover.pending(state,legacyWork),false);
state.work.push({ id:'engineering-handover', kind:'engineering-handover', name:'内容任务', owner:'sales-1' });
assert.throws(() => Handover.apply(state,'handover-mark','engineering-handover',form), /仅能标记/);

const otherState = { ...clone(state), companyId:'other-company' };
login('boss-1','boss');
assert.throws(() => Handover.apply(otherState,'handover-mark',work.id,form), /组织|公司/);

console.log('PASS 管理责任明确标记：标记待办、真实转交关闭、重标开启及角色组织隔离');
