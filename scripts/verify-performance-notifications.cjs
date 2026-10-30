const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const keys = require(path.join(root, 'miniprogram/utils/storage-keys'));
const storage = new Map();
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key)
};

const staff = [
  { id:'sales-a', personId:'person-a', name:'甲员工', active:true, roles:['sales','engineer','finance'] },
  { id:'engineer-a', personId:'person-a', name:'甲员工', active:true, roles:['sales','engineer','finance'] },
  { id:'sales-b', personId:'person-b', name:'乙员工', active:true, roles:['sales'] }
];
storage.set(keys.management, { companyId:'store-a', staff, receipts:[], payables:[], payments:[], refunds:[] });
storage.set(keys.performanceTasks, { version:1, companies:{
  'store-a': { periods:{
    '2026-10': { tasks:[
      { id:'manual-a', period:'2026-10', title:'提交客户复盘', due:'2026-10-31', type:'manual', assigneeId:'sales-a', assigneePersonId:'person-a', manualDone:null, history:[{ action:'create', to:'sales-a', toPersonId:'person-a', at:'2026-10-20T01:00:00.000Z' }] },
      { id:'auto-a', period:'2026-10', title:'完成三个工程节点', due:'2026-10-31', type:'auto:completedNodes', target:3, assigneeId:'engineer-a', assigneePersonId:'person-a', manualDone:null, history:[{ action:'create', to:'engineer-a', toPersonId:'person-a', at:'2026-10-21T01:00:00.000Z' }] },
      { id:'done-a', period:'2026-10', title:'已提交的周总结', due:'2026-10-22', type:'manual', assigneeId:'sales-a', assigneePersonId:'person-a', manualDone:{ at:'2026-10-22T02:00:00.000Z' }, history:[{ action:'create', to:'sales-a', toPersonId:'person-a', at:'2026-10-19T01:00:00.000Z' }] },
      { id:'reassigned', period:'2026-10', title:'改派月报', due:'2026-10-30', type:'manual', assigneeId:'sales-b', assigneePersonId:'person-b', manualDone:null, history:[
        { action:'create', to:'sales-a', toPersonId:'person-a', at:'2026-10-18T01:00:00.000Z' },
        { action:'reassign', from:'sales-a', fromPersonId:'person-a', to:'sales-b', toPersonId:'person-b', at:'2026-10-23T01:00:00.000Z' }
      ] }
    ], archives:[], commands:{} }
  } },
  'store-b': { periods:{ '2026-10': { tasks:[
    { id:'foreign', period:'2026-10', title:'其他公司任务', due:'2026-10-31', type:'manual', assigneeId:'sales-a', assigneePersonId:'person-a', manualDone:null, history:[{ action:'create', to:'sales-a', toPersonId:'person-a', at:'2026-10-24T01:00:00.000Z' }] }
  ], archives:[], commands:{} } } }
} });

function account(staffId, role, personId = 'person-a') {
  return {
    version:1,
    session:{ loggedIn:true, companyId:'store-a', staffId, userId:'user-' + personId, role },
    profile:{ name:personId === 'person-a' ? '甲员工' : '乙员工' },
    companies:[{ id:'store-a', type:'store', roles:[role] }],
    messages:[], messageReads:{}, orders:[], feedback:[], drafts:{}, learning:{}, favorites:[]
  };
}

let current = account('sales-a', 'sales');
storage.set(keys.account, current);
const Performance = require(path.join(root, 'miniprogram/utils/performance-notifications'));
const Account = require(path.join(root, 'miniprogram/utils/account'));

let notifications = Performance.list(current);
assert.deepEqual(notifications.map(row => row.objectId).sort(), ['2026-10','2026-10','2026-10']);
assert(notifications.some(row => row.title === '新绩效任务已派发' && row.description.includes('提交客户复盘')));
assert(notifications.some(row => row.description.includes('完成三个工程节点')), '自动采集目标应知会本人');
assert(!notifications.some(row => row.description.includes('改派月报')), '改派后旧负责人不再看到派生消息');
assert(!notifications.some(row => row.description.includes('其他公司任务')), '不同公司任务必须隔离');

let todos = Performance.todos(current);
assert.deepEqual(todos.map(row => row.id), ['performance:manual-a']);
assert.equal(todos[0].triggeredAt, '2026-10-20T01:00:00.000Z');
assert.equal(todos[0].url, '/pages/admin/tasks/index?view=mine&period=2026-10');

const actorA = require(path.join(root, 'miniprogram/utils/access')).context(current);
const ownerRows = require(path.join(root, 'miniprogram/pages/admin/owner-home')).todoRows({
  actor:actorA,
  approvals:[{ key:'older', url:'/older', name:'较早审批', status:'待审批', amount:'¥ 1', triggeredAt:'2026-10-19T01:00:00.000Z' }],
  reviews:[], state:{ staff, work:[], transfers:[] }
});
assert.equal(ownerRows[0].id, 'performance:manual-a', '新派发手动任务应排在老板首页较早待办之前');

const designDesk = require(path.join(root, 'miniprogram/utils/design-admin-workbench')).build([], {
  ...actorA, role:'hq-design-admin', orgType:'headquarters'
});
assert.equal(designDesk.todos[0].id, 'performance:manual-a', '设计岗位首页应接入本人绩效待办');
assert.equal(designDesk.todos[0].customerId, undefined, '绩效待办不能伪装成设计客户');
assert.equal(designDesk.todos[0].url, todos[0].url, '设计绩效待办应沿用统一绩效路由');
assert.equal(require(path.join(root, 'miniprogram/utils/engineering-records')).performanceTasks()[0].id, 'performance:manual-a', '工程岗位待办构建器应接入本人绩效待办');
assert(require(path.join(root, 'miniprogram/utils/sales-todos')).list().pending.some(row => row.id === 'performance:manual-a'), '业务岗位首页应接入本人绩效待办');
const salesBoard = require(path.join(root, 'miniprogram/utils/sales-workspace')).todoBoard(todos, [], [], 'performance', true);
assert(salesBoard.todoTabs.some(tab => tab.key === 'performance' && tab.count === 1), '业务首页应显示绩效分组与数量');
assert(salesBoard.todoRows.some(row => row.show && row.id === 'performance:manual-a' && row.url === todos[0].url), '绩效分组应显示可点击统一绩效路由');

current = account('sales-a', 'finance');
storage.set(keys.account, current);
const financeRows = require(path.join(root, 'miniprogram/utils/finance-desk')).taskRows({
  companyId:'store-a', period:'2026-10', staff, receipts:[], payables:[], payments:[], refunds:[],
  operations:{ expenses:[], plans:[], reconciliations:[] }
});
assert.equal(financeRows[0].id, 'performance:manual-a', '财务岗位首页应接入本人绩效待办');
const financeHome = require(path.join(root, 'miniprogram/utils/finance-desk')).home({
  companyId:'store-a', period:'2026-10', staff, receipts:[], payables:[], payments:[], refunds:[],
  operations:{ expenses:[], plans:[], reconciliations:[] }
});
assert(financeHome.metrics.some(metric => metric.label === '待办事项'), '财务首页汇总含绩效后应使用“待办事项”');

current = account('engineer-a', 'engineer');
storage.set(keys.account, current);
assert.deepEqual(Performance.todos(current).map(row => row.id), ['performance:manual-a'], '同一人员兼岗切换后仍看到本人手动待办');

current = account('sales-b', 'sales', 'person-b');
current.messages.push({ id:'stored-old', companyId:'store-a', userId:'user-person-b', type:'业务', title:'较早站内消息', description:'历史消息', date:'2026-10-01 08:00', read:false, route:'messages' });
storage.set(keys.account, current);
notifications = Performance.list(current);
assert.equal(notifications.length, 1);
assert.equal(notifications[0].title, '绩效任务已改派给你');
assert.equal(notifications[0].date, '2026-10-23 09:00');
assert.deepEqual(Performance.todos(current).map(row => row.id), ['performance:reassigned']);
assert.equal(Performance.todos(current)[0].triggeredAt, '2026-10-23T01:00:00.000Z');

const visible = Account.scoped(current, current.messages);
assert.equal(visible.length, 2, '账号消息中心应合并存储消息与绩效派生消息');
assert.equal(visible[0].id, notifications[0].id, '不同消息来源合并后仍应按真实时间全局最新置顶');
let accountPage;
global.Page = definition => { accountPage = definition; };
delete require.cache[require.resolve(path.join(root, 'miniprogram/pages/account/index'))];
require(path.join(root, 'miniprogram/pages/account/index'));
let opened;
accountPage.read.call({ navigate:(route, id) => { opened = { route, id }; }, fail:message => { throw Error(message); } }, { currentTarget:{ dataset:{ id:visible[0].id } } });
assert.deepEqual(opened, { route:'personal-performance', id:'2026-10' }, '消息页点击绩效知会应进入统一绩效页面');
Account.markMessageRead(current, visible[0].id);
assert.equal(Performance.list(current)[0].read, true, '派生消息按公司与人员保存已读状态');

const management = storage.get(keys.management), memberB = management.staff.find(person => person.id === 'sales-b');
memberB.active = false;
storage.set(keys.management, management);
assert.deepEqual(Performance.list(current), [], '停用人员不得继续读取绩效消息');
assert.deepEqual(Performance.todos(current), [], '停用人员不得继续读取绩效待办');
memberB.active = true;
storage.set(keys.management, management);

const taskStore = storage.get(keys.performanceTasks);
taskStore.companies['store-a'].periods['2026-09'] = { tasks:[{
  id:'archived-old', period:'2026-09', title:'已归档历史任务', due:'2026-09-30', type:'manual', assigneeId:'sales-b', assigneePersonId:'person-b', manualDone:null,
  history:[{ action:'create', to:'sales-b', toPersonId:'person-b', at:'2026-09-20T01:00:00.000Z' }]
}], archives:[{ id:'archive-2026-09', revision:1 }], commands:{} };
taskStore.companies['store-a'].periods['2026-11'] = { tasks:[{
  id:'next-month', period:'2026-11', title:'跨月未完成任务', due:'2026-11-30', type:'manual', assigneeId:'sales-b', assigneePersonId:'person-b', manualDone:null,
  history:[{ action:'create', to:'sales-b', toPersonId:'person-b', at:'2026-10-25T01:00:00.000Z' }]
}], archives:[], commands:{} };
storage.set(keys.performanceTasks, taskStore);
assert(Performance.list(current).some(message => message.objectId === '2026-09'), '归档任务仍应保留历史知会');
assert(!Performance.todos(current).some(todo => todo.id === 'performance:archived-old'), '归档任务不能继续形成可办待办');
assert(Performance.todos(current).some(todo => todo.id === 'performance:next-month'), '跨月未完成手动任务仍应可见');

taskStore.companies['store-a'].periods['2026-10'].tasks.find(task => task.id === 'reassigned').manualDone = { at:'2026-10-24T01:00:00.000Z' };
storage.set(keys.performanceTasks, taskStore);
assert(!Performance.todos(current).some(todo => todo.id === 'performance:reassigned'), '提交完成凭证后对应待办应消失');
assert(Performance.list(current).some(message => message.description.includes('改派月报')), '完成后派发知会仍作为历史消息保留');

console.log('PASS 绩效派发/改派消息、手动待办、完成消失、兼岗与公司人员隔离');
