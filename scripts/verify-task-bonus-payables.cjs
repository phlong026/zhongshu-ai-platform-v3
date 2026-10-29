const assert = require('node:assert/strict');

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
const Facts = require('../miniprogram/pages/admin/owner-performance').facts;
const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(Facts);
const TaskBonus = require('../miniprogram/pages/admin/task-bonus-actions');

const login = (id, role) => Account.mutate(account => {
  Account.loginDemo(account, id);
  if (role) Account.switchIdentity(account, account.session.companyId, role);
});
const rawBook = () => store[keys.management];
const writeBook = mutate => {
  const book = clone(rawBook());
  mutate(book);
  store[keys.management] = book;
};
const createManual = (title, bonus, key) => Tasks.create(Model.load(), {
  period:'2026-10', title, assigneeId:'finance-dual', due:'2026-10-31', type:'manual', target:'', bonus, idempotencyKey:key
});

login('boss', 'boss');
const first = createManual('月度对账', '10', 'bonus-task:first');
const second = createManual('资金核对', '20', 'bonus-task:second');
login('finance-dual', 'finance');
Tasks.completeManual(Model.load(), first.id, { note:'月度对账完成', photos:[], idempotencyKey:'done:first' });
Tasks.completeManual(Model.load(), second.id, { note:'资金核对完成', photos:[], idempotencyKey:'done:second' });
login('boss', 'boss');
const archive = Tasks.archive(Model.load(), '2026-10', { note:'奖金归档', idempotencyKey:'archive:first' });
const draft = Tasks.payoutDraft(Model.load(), archive.id);
assert.equal(draft.lines.length, 2);
const paymentsBeforeRegistration = Model.load().payments.length;

login('sales-dual', 'sales');
assert.throws(() => Model.mutate(state => Model.apply(state, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey })), /老板|财务|授权/);
const quanzhouState = Model.load();
login('hq-boss', 'boss');
assert.throws(() => TaskBonus.apply(quanzhouState, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey }), /本组织/);

login('boss', 'boss');
const beforeFailedSave = clone(rawBook());
const savedSet = wx.setStorageSync;
let failed = false;
wx.setStorageSync = (key, value) => {
  if (key === keys.management && !failed) { failed = true; throw Error('模拟账簿保存失败'); }
  return savedSet(key, value);
};
assert.throws(() => Model.mutate(state => Model.apply(state, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey })), /模拟账簿保存失败/);
wx.setStorageSync = savedSet;
assert.deepEqual(rawBook(), beforeFailedSave, '账簿保存失败不得提前留下奖金应付');

const payableId = Model.mutate(state => Model.apply(state, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey }));
let payable = Model.load().payables.find(row => row.id === payableId);
assert(payable, '应生成按真实人员汇总的奖金应付');
assert.equal(payable.total, 3000);
assert.equal(payable.performanceArchive.personId, 'finance-dual');
assert.deepEqual(payable.performanceArchive.taskIds.sort(), [first.id, second.id].sort());
assert.equal(payable.performanceArchive.batchReference, 'performance-bonus-batch:quanzhou:2026-10');
assert.equal(payable.source, undefined, '本地应付不得伪装成业务或工程来源');
login('finance-dual', 'finance');
assert.equal(Model.mutate(state => Model.apply(state, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey })), payableId);
assert.equal(Model.load().payables.filter(row => row.id === payableId).length, 1, '同一归档重试不得重复生成');
assert.equal(Model.load().payments.length, paymentsBeforeRegistration, '奖金登记不得自行生成付款');

writeBook(book => {
  const row = book.payables.find(item => item.id === payableId);
  row.total += 1;
});
assert.throws(() => Model.mutate(state => Model.apply(state, 'performance-bonus-register', archive.id, { idempotencyKey:draft.idempotencyKey })), /内容|占用|不一致/);
writeBook(book => {
  const row = book.payables.find(item => item.id === payableId);
  row.total = 3000;
});

store[keys.growth].contents.push({ id:'changed-before-pay', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:'2026-10-20', pack:{title:'原单变化'} });
login('finance-dual', 'finance');
assert.throws(() => Model.mutate(state => Model.apply(state, 'pay', payableId, {
  amount:'5', date:'2026-10-29', transaction:'BONUS-STALE', file:{name:'奖金付款.pdf'}, checked:true
})), /原业务事实|修订归档/);
store[keys.growth].contents = store[keys.growth].contents.filter(row => row.id !== 'changed-before-pay');

const paymentId = Model.mutate(state => Model.apply(state, 'pay', payableId, {
  amount:'5', date:'2026-10-29', transaction:'BONUS-PAY-1', file:{name:'奖金付款.pdf'}, checked:true
}));
assert(Model.load().payments.some(row => row.id === paymentId && row.payableId === payableId), '奖金付款必须复用既有通用付款记录');

store[keys.growth].contents.push({ id:'changed-for-revision', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:'2026-10-21', pack:{title:'修订依据'} });
login('boss', 'boss');
const revised = Tasks.reviseArchive(Model.load(), '2026-10', { note:'原单变化后修订', idempotencyKey:'archive:revision' });
const revisedDraft = Tasks.payoutDraft(Model.load(), revised.id);
assert.throws(() => Model.mutate(state => Model.apply(state, 'performance-bonus-register', revised.id, { idempotencyKey:revisedDraft.idempotencyKey })), /已登记付款|预留|旧版/);
assert.equal(Model.load().payables.filter(row => row.id === payableId).length, 1, '旧版发生付款后不得为新版再建应付');

login('finance-dual', 'finance');
writeBook(book => book.payables.push({ id:'ordinary-payable', companyId:'quanzhou', projectId:'company:quanzhou', customer:'公司', name:'普通费用', stage:'普通费用', total:1000, recipient:'普通收款方', status:'待办理' }));
const ordinaryPayment = Model.mutate(state => Model.apply(state, 'pay', 'ordinary-payable', {
  amount:'10', date:'2026-10-29', transaction:'ORDINARY-PAY-1', file:{name:'普通付款.pdf'}, checked:true
}));
assert(Model.load().payments.some(row => row.id === ordinaryPayment), '奖金付款校验不得阻断普通应付');
assert.equal(TaskBonus.validatePayment(Model.load(), Model.load().payables.find(row => row.id === 'ordinary-payable')), true);

login('boss', 'boss');
store[keys.growth].contents.push({ id:'september-bonus-source', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:'2026-09-20', pack:{title:'九月发布'} });
const septemberTask = Tasks.create(Model.load(), {
  period:'2026-09', title:'九月发布奖金', assigneeId:'finance-dual', due:'2026-09-30', type:'auto:publications', target:'1', bonus:'15', idempotencyKey:'bonus-task:september'
});
const septemberArchive = Tasks.archive(Model.load(), '2026-09', { note:'九月奖金归档', idempotencyKey:'archive:september' });
const septemberDraft = Tasks.payoutDraft(Model.load(), septemberArchive.id);
const septemberPayableId = Model.mutate(state => Model.apply(state, 'performance-bonus-register', septemberArchive.id, { idempotencyKey:septemberDraft.idempotencyKey }));
assert(Model.load().payables.some(row => row.id === septemberPayableId && row.total === 1500));
store[keys.growth].contents = store[keys.growth].contents.filter(row => row.id !== 'september-bonus-source');
const zeroRevision = Tasks.reviseArchive(Model.load(), '2026-09', { note:'原发布记录撤销', idempotencyKey:'archive:september:r2' });
const zeroDraft = Tasks.payoutDraft(Model.load(), zeroRevision.id);
assert.equal(zeroDraft.total, 0);
assert.deepEqual(zeroDraft.lines, []);
writeBook(book => book.payments.push({ id:'september-reservation', payableId:septemberPayableId, amount:100, status:'待复核' }));
assert.throws(() => Model.mutate(state => Model.apply(state, 'performance-bonus-register', zeroRevision.id, { idempotencyKey:zeroDraft.idempotencyKey })), /付款|预留|对账/);
assert(Model.load().payables.some(row => row.id === septemberPayableId), '有预留时必须保留旧应付供对账');
writeBook(book => { book.payments = book.payments.filter(row => row.id !== 'september-reservation'); });
const zeroResult = Model.mutate(state => Model.apply(state, 'performance-bonus-register', zeroRevision.id, { idempotencyKey:zeroDraft.idempotencyKey }));
assert.equal(zeroResult, TaskBonus.batchReference('quanzhou', '2026-09'));
assert(!Model.load().payables.some(row => row.id === septemberPayableId), '零奖金修订应核销旧版未付应付');
assert.equal(Tasks.list(Model.load(), { scope:'company', period:'2026-09' }).find(row => row.id === septemberTask.id).frozen, true);

console.log('PASS 奖金归档按真实人员生成稳定应付，原单变化与旧版付款均阻断且复用通用付款');
