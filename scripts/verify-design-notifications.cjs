const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const Notifications = require('../miniprogram/utils/design-notifications');

const actor = {
  active: true,
  role: 'hq-design-admin',
  orgType: 'headquarters',
  companyId: 'headquarters',
  staffId: 'admin-a',
  personId: 'person-a',
  userId: 'demo-admin-a'
};

const customer = (id, extra = {}) => ({
  id,
  projectId: `project-${id}`,
  companyId: extra.companyId || 'store-a',
  designOrganizationId: extra.designOrganizationId || 'headquarters',
  name: extra.name || '陈先生',
  phone: '13800000000',
  wechat: 'private-wechat',
  request: { version: extra.requestVersion || 2 },
  serviceOrders: [{ version: extra.serviceVersion || 3, total: 12000, first: 6000, tail: 6000 }],
  businessFlow: {
    type: 'sales-offline-v1',
    completed: extra.completed === false ? null : {
      requestVersion: extra.requestVersion || 2,
      feedbackId: extra.feedbackId || null,
      at: extra.completedAt || '2026-10-22T02:00:00.000Z'
    },
    dispatch: extra.dispatch || null,
    feedbacks: extra.feedbacks || []
  }
});

const ready = () => ({ ready: true, first: { ready: true }, tail: { ready: true }, text: '首尾款均已到账', confirmedAt: '2026-10-22T03:00:00.000Z', confirmationVersion: 'receipt-set-v1' });
const readyAgain = () => ({ ...ready(), confirmedAt: '2026-10-23T03:00:00.000Z', confirmationVersion: 'receipt-set-v2' });
const denied = () => ({ ready: false, first: { ready: true }, tail: { ready: false }, text: '尾款未足额', confirmedAt: '' });
const allowCustomer = (row, current) => current.active && current.role === 'hq-design-admin' && row.designOrganizationId === current.companyId;

const base = customer('customer-a');
const rows = Notifications.list({ messageReads: {} }, { actor, customers: [base], releaseFor: ready, canView: allowCustomer });
assert.equal(rows.length, 1, '设计完成且总部首尾款足额实收时生成站内通知');
assert.equal(rows[0].route, 'design-dispatch');
assert.equal(rows[0].objectId, base.id);
assert.equal(rows[0].read, false);
assert.match(rows[0].title, /正式图纸可下发/);
assert(!/12000|6000|13800000000|private-wechat/.test(JSON.stringify(rows[0])), '通知不得包含金额、电话、微信或凭证');

assert.equal(Notifications.list({}, { actor, customers: [base], releaseFor: denied, canView: allowCustomer }).length, 0, '未足额不发通知');
assert.equal(Notifications.list({}, { actor, customers: [customer('not-complete', { completed: false })], releaseFor: ready, canView: allowCustomer }).length, 0, '设计尚未完成不发通知');
assert.equal(Notifications.list({}, { actor, customers: [customer('pending-change', { feedbacks: [{ id: 'feedback:1', status: 'pending' }] })], releaseFor: ready, canView: allowCustomer }).length, 0, '修改意见尚未完成时不得沿用上一版完成记录通知下发');
assert.equal(Notifications.list({}, { actor, customers: [customer('other-org', { designOrganizationId: 'other-headquarters' })], releaseFor: ready, canView: allowCustomer }).length, 0, '其他设计组织不可见');
assert.equal(Notifications.list({}, { actor: { ...actor, role: 'finance' }, customers: [base], releaseFor: ready, canView: allowCustomer }).length, 0, '切换非设计行政岗位后不可见');
assert.equal(Notifications.list({}, { actor: { ...actor, active: false }, customers: [base], releaseFor: ready, canView: allowCustomer }).length, 0, '停用人员不可见');

const state = { messageReads: {} };
Notifications.markRead(state, rows[0].id, { actor, customers: [base], releaseFor: ready, canView: allowCustomer });
assert.equal(Notifications.list(state, { actor, customers: [base], releaseFor: ready, canView: allowCustomer })[0].read, true, '已读状态按当前人员保存');
assert.equal(Notifications.list(state, { actor, customers: [base], releaseFor: denied, canView: allowCustomer }).length, 0, '退款或更正导致放行关闭时通知自动消失');
const reopened = Notifications.list(state, { actor, customers: [base], releaseFor: readyAgain, canView: allowCustomer })[0];
assert.notEqual(reopened.id, rows[0].id, '补款后再次足额必须生成新的确认版本通知');
assert.equal(reopened.read, false, '新一次足额通知不得继承旧通知已读状态');
assert.equal(Notifications.list(state, { actor: { ...actor, staffId: 'admin-b', personId: 'person-b', userId: 'demo-admin-b' }, customers: [base], releaseFor: ready, canView: allowCustomer })[0].read, false, '另一人的已读状态独立');
const readAllState = { messageReads: {} };
const readAllCustomers = [base, customer('customer-b', { name: '李女士' })];
Notifications.markAllRead(readAllState, { actor, customers: readAllCustomers, releaseFor: ready, canView: allowCustomer });
assert(Notifications.list(readAllState, { actor, customers: readAllCustomers, releaseFor: ready, canView: allowCustomer }).every(row => row.read), '全部已读一次保存当前人员的所有派生通知');

const modification = customer('customer-a', { feedbackId: 'feedback:1', completedAt: '2026-10-23T02:00:00.000Z' });
const modificationMessage = Notifications.list(state, { actor, customers: [modification], releaseFor: ready, canView: allowCustomer })[0];
assert.notEqual(modificationMessage.id, rows[0].id, '修改完成后的再次下发通知使用新的稳定ID');

const dispatched = customer('customer-a', { dispatch: { version: 1, at: '2026-10-22T04:00:00.000Z' } });
const historyMessage = Notifications.list({}, { actor, customers: [dispatched], releaseFor: ready, canView: allowCustomer })[0];
assert.equal(historyMessage.route, 'design-progress');
assert.match(historyMessage.title, /已下发/);
assert.doesNotMatch(historyMessage.description, /可下发/);

const accountSource = fs.readFileSync(path.resolve('miniprogram/utils/account.js'), 'utf8');
const pageSource = fs.readFileSync(path.resolve('miniprogram/pages/account/index.js'), 'utf8');
assert(accountSource.includes("require('./design-notifications')"), '账号消息列表需要合并派生通知');
assert(accountSource.includes('markMessageRead') && accountSource.includes('markAllMessagesRead'), '账号模型需统一处理静态与派生通知的已读状态');
assert(pageSource.includes("m.route==='design-dispatch'") && pageSource.includes("m.route==='design-progress'"), '消息点击需按当前合法状态进入下发或进度页');

console.log('PASS 设计到账通知：实时派生、组织隔离、隐私投影、稳定去重、人员独立已读与安全跳转契约');
if (process.argv.includes('--unit')) process.exit(0);

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const scene = JSON.parse(fs.readFileSync(path.resolve('qa/business-module-simplification-20261021/page-scenes.json')))
    .find(row => row.action === 'design-dispatch');
  assert(scene, '需要真实流程的到账后、下发前场景');
  for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
  for (const [key, value] of scene.before) wx.setStorageSync(key, value);

  A.mutate(account => A.loginDemo(account, 'hq-design-admin'));
  let account = A.load();
  let message = A.scoped(account, account.messages).find(row => row.route === 'design-dispatch' && row.objectId === scene.id);
  assert(message && !message.read, '总部实收足额后行政自动获得未读下发通知');

  wx.reLaunch({ url: '/pages/account/index?view=messages' });
  const page = getCurrentPages().at(-1);
  page.read({ currentTarget: { dataset: { id: message.id } } });
  const target = getCurrentPages().at(-1);
  assert.equal(target.view, 'dispatch', '通知点击后进入当前合法的正式图纸下发页');
  assert.equal(target.id, scene.id);
  account = A.load();
  message = A.scoped(account, account.messages).find(row => row.objectId === scene.id && row.route === 'design-dispatch');
  assert(message.read, '点击后仅标记当前人员的派生通知已读');

  const B = require('../miniprogram/utils/business');
  const M = require('../miniprogram/pages/admin/model');
  const root = B.clone(wx.getStorageSync(M.KEY));
  const headquarters = root.companyId === 'headquarters' ? root : root.books.headquarters;
  const tailReceipt = headquarters.receipts.find(row => row.status === '已核算' && row.source?.customerId === scene.id && row.source.stage === 'tail');
  assert(tailReceipt, '需要已确认的尾款实收记录');
  headquarters.refunds.push({ id: 'notification-refund-gap', receiptId: tailReceipt.id, status: '已核实', amount: 1, transaction: 'NOTIFICATION-REFUND-GAP' });
  wx.setStorageSync(M.KEY, root);
  account = A.load();
  assert(!A.scoped(account, account.messages).some(row => row.objectId === scene.id && row.route === 'design-dispatch'), '退款一分导致放行关闭后，旧通知不再作为可办事项展示');

  A.mutate(accountState => A.loginDemo(accountState, 'hq-finance'));
  let finance = M.load();
  const supplement = finance.receipts.find(row => row.status === '待登记' && row.source?.customerId === scene.id && row.source.stage === 'tail');
  assert(supplement && supplement.amount === 1, '应自动生成一分差额到账事项');
  M.mutate(state => M.apply(state, 'incoming-register', supplement.id, {
    amount: '0.01',
    date: A.localDate(),
    transaction: 'NOTIFICATION-REOPEN',
    file: { name: '补款到账凭据.pdf', sample: true },
    checked: true,
    note: '退款差额补款已实际到账',
    expectedSource: JSON.stringify(supplement.source),
    expectedAmount: String(supplement.amount)
  }));
  A.mutate(accountState => A.loginDemo(accountState, 'hq-design-admin'));
  account = A.load();
  const reopenedMessage = A.scoped(account, account.messages).find(row => row.objectId === scene.id && row.route === 'design-dispatch');
  assert(reopenedMessage, '补款再次足额后应重新生成行政下发通知');
  assert.notEqual(reopenedMessage.id, message.id, '新一次到账确认集合必须使用新通知ID');
  assert.equal(reopenedMessage.read, false, '补款再次足额后的通知必须恢复为未读');

  A.mutate(accountState => A.loginDemo(accountState, 'hq-finance'));
  account = A.load();
  assert(!A.scoped(account, account.messages).some(row => row.id === reopenedMessage.id), '总部财务岗位不可见设计行政派生通知');
  await runtime.disconnect();
  console.log('PASS 真实流程集成：足额通知已读后，退款关闭放行，补款再次足额产生新的未读通知');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
