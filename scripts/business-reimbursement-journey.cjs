// Legacy admin fixture checks preserve historical behavior; they do not prove the current online workflow.
const assert = require('node:assert/strict');

const Account = require('../miniprogram/utils/account');
const Finance = require('../miniprogram/utils/finance-source');
const Management = require('./fixtures/legacy-admin/model');

function restore(rows) {
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  rows.forEach(([key, value]) => wx.setStorageSync(key, value));
}

async function settle({ runtime, rows, customerId, projectId }) {
  restore(rows);
  Account.mutate(state => Account.loginDemo(state, 'finance-dual'));
  Account.mutate(state => Account.switchIdentity(state, state.session.companyId, 'finance'));

  const before = Management.load();
  const payable = before.payables.find(row => row.source?.kind === 'reimbursement'
    && row.source.id === customerId && row.projectId === projectId);
  assert(payable, '门店财务必须能找到同客户同项目的业务垫付报销原单');
  const remaining = payable.total - Finance.paid(before, payable.id);
  assert(remaining > 0, '业务垫付报销原单必须存在准确待付余额');

  const url = '/pages/admin/index?view=pay&id=' + encodeURIComponent(payable.id);
  await runtime.callWxMethod('reLaunch', { url });
  const page = getCurrentPages().at(-1);
  assert.equal(page.data.action, 'pay', '原报销待办必须进入实际付款登记页');
  assert(!page.data.error, page.data.error);
  const date = Account.localDate();
  const transaction = 'REIMBURSE-' + customerId;
  const file = { name: '业务垫付设计费报销付款凭据.pdf', sample: true };
  page.setData({ form: { ...page.data.form, amount: String(remaining / 100), date, transaction, file, checked: true } });

  const submit = await (await runtime.currentPage()).$('[data-handler="submit"]');
  assert(submit, '付款原单必须渲染提交按钮');
  await submit.tap();
  let current = getCurrentPages().at(-1);
  assert(!current.data.error, current.data.error);
  if (current.data.modal?.action) {
    const confirm = await (await runtime.currentPage()).$('[data-handler="confirm"]');
    assert(confirm, '付款确认弹窗必须渲染确认按钮');
    await confirm.tap();
    current = getCurrentPages().at(-1);
    assert(!current.data.error, current.data.error);
  }

  const after = Management.load();
  const closed = after.payables.find(row => row.id === payable.id);
  const payment = after.payments.find(row => row.payableId === payable.id && row.transaction === transaction);
  assert.equal(closed.status, '已付清', '业务垫付报销原单必须在付款后结束');
  assert(payment && payment.amount === remaining && payment.status === '已核实', '付款必须按准确剩余金额登记并核实');
  assert.equal(payment.projectId, projectId);
  assert(!Finance.tasks(after).some(task => task.id === payable.id), '已付清报销不得继续出现在财务待办');

  return {
    rows: wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]),
    evidence: { payableId: payable.id, paymentId: payment.id, projectId, customerId, amount: payment.amount, transaction, status: closed.status },
    nativeSequence: { login: 'finance-dual', role: 'finance', url, form: { amount: String(remaining / 100), date, transaction, file, checked: true }, submit: '[data-handler="submit"]', confirm: '[data-handler="confirm"]' }
  };
}

module.exports = { settle };
