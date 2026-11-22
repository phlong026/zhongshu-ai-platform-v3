#!/usr/bin/env node
/**
 * 批次#33 · M08-T08 双端（一）：支出退回方向 v3 接线验证（R2 版）。
 * 行为层 A：platform-finance 纯函数——ledgerRows 方向分流（expense=支出退回 + / client=退款 −）、
 *           metrics.refunded 只计 client、待归月语义、errorMessage 新错误码文案。
 * 行为层 B（R2 P2-3）：platform-actions 注入 fake page 实跑 submitRegister——
 *           上限拦截不发起请求、雪花串请求体、日期兜底真实今日、粘性幂等键。
 * 静态层：端点/白名单/选项源/双卡入口/选项 label 可区分（R2 P2-2）。
 * 用法：node scripts/verify-finance-expense-refund.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const PF = require('../miniprogram/utils/platform-finance');
const attach = require('../miniprogram/pages/admin/finance-desk/platform-actions');
const localDate = require('../miniprogram/utils/account').localDate;
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');

// —— 行为层 A：PF 纯函数（Node 直调） ——

const receipts = [{ id: 101, name: '首款', amountCents: 1000000, status: '已登记', receivedDate: '2026-11-02' }];
const payments = [
  { id: 201, amountCents: 500000, status: '已核实', payDate: '2026-11-03' },
  { id: 202, amountCents: 300000, status: '已核实', payDate: '' } // 日期待核 → 待归月
];
const refunds = [
  { id: 301, amountCents: 200000, status: '已核实', date: '2026-11-04', refundDirection: 'client', receiptId: 101 },
  { id: 302, amountCents: 100000, status: '已核实', date: '2026-11-05', refundDirection: 'expense', paymentId: 201 },
  { id: 303, amountCents: 50000, status: '已核实', date: '', refundDirection: 'expense', paymentId: 202 } // R2：空日期 expense
];

const led = PF.ledgerRows(receipts, payments, refunds, '2026-11');
const expenseRow = led.rows.find(r => r.key === 'f-302');
const clientRow = led.rows.find(r => r.key === 'f-301');
assert.equal(expenseRow.type, '支出退回', 'expense 行台账类型=支出退回');
assert.equal(expenseRow.sign, '+', 'expense 行是资金回流（+）');
assert.equal(expenseRow.name, '支出退回', 'expense 行名称=支出退回');
assert.equal(clientRow.type, '退款', 'client 行台账类型不变=退款');
assert.equal(clientRow.sign, '−', 'client 行仍是流出（−）');
assert.ok(!led.rows.some(r => r.key === 'p-202'), '待归月行不进月度过滤（R04：不冒充本月发生）');
assert.ok(!led.rows.some(r => r.key === 'f-303'), '空日期 expense 行不进月度过滤');

const ledAll = PF.ledgerRows(receipts, payments, refunds, '');
const expense303 = ledAll.rows.find(r => r.key === 'f-303');
assert.ok(ledAll.rows.find(r => r.key === 'f-302'), '全量台账保留支出退回行');
assert.ok(ledAll.rows.find(r => r.key === 'p-202' && r.date === '待归月'), '付款日期待核仍单列待归月');
assert.ok(expense303 && expense303.date === '待归月' && expense303.sign === '+',
  '空日期 expense 行归待归月且为回流（R2 P2-3 新增 fixture）');

const m = PF.metrics(receipts, payments, refunds, '2026-11');
assert.equal(m.refunded, '2000.00', '指标退款卡只计 client 方向（expense 回流不污染）');
assert.equal(m.paid, '5000.00', '指标已付口径不变（回流冲减归对账视图）');
assert.equal(m.received, '10000.00', '指标实收口径不变');

assert.ok(PF.errorMessage({ code: 1102000031 }).includes('剩余可退回'), '错误码 1102000031 文案映射');
assert.equal(PF.errorMessage({ code: 1102000032 }), '仅已核实的付款可以登记支出退回', '错误码 1102000032 文案映射');
assert.equal(PF.errorMessage({ code: 1102000030 }), '支出退回关联付款单不存在，请刷新选择', '错误码 1102000030 文案映射');

// —— 行为层 B：platform-actions fake page 实跑 submitRegister（R2 P2-3：非静态恒真） ——

// R2 P2：wx 全局桩——成功回调链（wx.showToast）真实走通，不再因 wx 未定义被 catch 吞掉
const toastCalls = [];
globalThis.wx = {
  getStorageSync: () => '',
  showToast: opt => toastCalls.push(opt),
  chooseMessageFile: () => { throw new Error('本测试不应触达选择文件'); }
};
const settle = () => new Promise(resolve => setTimeout(resolve, 0)); // 等完整微任务链（call→成功→finally）

function fakePage(registerForm, expenseRefundable) {
  const page = {
    data: { registerType: 'expense-refund', registerForm, registerError: '', registerBusy: false,
      platformRegister: { expenseRefundable: expenseRefundable || [] } },
    setData(patch) { Object.assign(this.data, patch); },
    loadPlatformFinanceCalls: 0,
    loadPlatformFinance() { this.loadPlatformFinanceCalls += 1; }
  };
  const calls = { expense: [], refund: [], uploads: 0 };
  const api = {
    registerExpenseRefund(body, idem) { calls.expense.push({ body, idem }); return Promise.resolve({refundId:'new-expense-refund'}); },
    registerRefund(body, idem) { calls.refund.push({ body, idem }); return Promise.resolve({refundId:'new-refund'}); },
    idemKey: type => 'idem-' + type
  };
  attach(page, api, PF);
  return { page, calls };
}

const today = localDate();

// B1：超上限 → 拦截且不发起请求
{
  const { page, calls } = fakePage({ paymentId: '201', amount: '400' }, [{ id: '201', refundableCents: 30000 }]);
  page.submitRegister();
  assert.equal(calls.expense.length, 0, '超上限不发起请求');
  assert.ok(page.data.registerError.includes('剩余可退回'), '超上限错误文案');
}

// B2：未选付款单 → 拦截
{
  const { page, calls } = fakePage({ amount: '100' }, []);
  page.submitRegister();
  assert.equal(calls.expense.length, 0, '未选付款单不发起请求');
  assert.ok(page.data.registerError.includes('请选择关联付款单'), '未选付款单错误文案');
}

// B3：合法提交 → 雪花串 paymentId + 日期兜底真实今日 + 幂等键 + 成功链完整收尾（R2 P2：wx 桩走通）
async function behaviorB3() {
  const { page, calls } = fakePage({ paymentId: '9007199254740993', amount: '300.00', reason: ' 供应商退回 ', date: '' },
    [{ id: '9007199254740993', refundableCents: 50000 }]);
  page.submitRegister();
  assert.equal(page.data.registerBusy, true, '提交期 busy 置位');
  await Promise.resolve(); // withVoucher.resolve→call 的第一个微任务
  assert.equal(calls.expense.length, 1, '合法提交发起一次请求');
  assert.equal(calls.expense[0].body.paymentId, '9007199254740993', 'paymentId 雪花串传输');
  assert.equal(calls.expense[0].body.amountCents, 30000, '元→分换算');
  assert.equal(calls.expense[0].body.reason, '供应商退回', 'reason 去首尾空白');
  assert.equal(calls.expense[0].body.date, today, '空日期兜底真实今日（R04 必填）');
  assert.ok(String(calls.expense[0].idem).startsWith('idem-expense-refund'), '幂等键命名空间独立');
  await settle(); // 等成功回调+finally 完整收尾
  assert.equal(page.data.registerType, '', '成功后表单关闭');
  assert.ok(page.data.registerForm && !Object.keys(page.data.registerForm).length, '成功后表单清空');
  assert.equal(page.registerIdem, null, '成功后粘性幂等键清空（下次换新键）');
  assert.equal(page.loadPlatformFinanceCalls, 1, '成功后刷新一次台账');
  assert.equal(page.data.registerBusy, false, 'busy 复位');
  assert.equal(page.data.paymentNotice, '支出退回已登记，单号 new-expense-refund', '成功提示保留可核对的原单号');
}

// B4：预填日期保留用户选择 + 成功链收尾
async function behaviorB4() {
  const { page, calls } = fakePage({ paymentId: '201', amount: '100', date: '2026-11-01' },
    [{ id: '201', refundableCents: 50000 }]);
  page.submitRegister();
  await Promise.resolve();
  assert.equal(calls.expense.length, 1, 'B4 发起请求');
  assert.equal(calls.expense[0].body.date, '2026-11-01', '用户选择的日期不被覆盖');
  await settle();
  assert.equal(page.data.registerType, '', 'B4 成功后表单关闭');
  assert.equal(page.data.registerBusy, false, 'B4 busy 复位');
}

// —— 静态层（异步行为完成后执行） ——

async function main() {
  await behaviorB3();
  await behaviorB4();

  // B5：openRegister 预填今日（R2 P1）
  {
    const page = { data: {}, setData(patch) { Object.assign(this.data, patch); } };
    attach(page, {}, PF);
    page.openRegister({ currentTarget: { dataset: { type: 'expense-refund' } } });
    assert.equal(page.data.registerType, 'expense-refund', 'B5 打开支出退回表单');
    assert.equal(page.data.registerForm.date, today, '打开表单即预填真实今日');
    const other = { data: {}, setData(patch) { Object.assign(this.data, patch); } };
    attach(other, {}, PF);
    other.openRegister({ currentTarget: { dataset: { type: 'receipt' } } });
    assert.equal(other.data.registerForm.date, undefined, '其他表单不预填日期');
  }

  const api = read('miniprogram/api/finance.js');
  assert.ok(api.includes("http.post('/franchise/finance/expense-refunds'"), 'api 走 expense-refunds 端点');
  assert.ok(api.includes("genIdemKey('expense-refund')"), '幂等键命名空间独立');
  assert.ok(/registerReceipt, registerPayment, registerRefund, registerExpenseRefund, registerPayable/.test(api),
    'module.exports 导出 registerExpenseRefund');

  const actions = read('miniprogram/pages/admin/finance-desk/platform-actions.js');
  assert.ok(actions.includes("'receipt', 'payment', 'refund', 'expense-refund', 'payable'"), 'openRegister 白名单放行支出退回');
  assert.ok(actions.includes("field === 'paymentId' ? (reg.expenseRefundable || [])"), 'registerPick 支出退回选项源');
  assert.ok(actions.includes("if (src && amount > src.refundableCents) return fail('超过该付款单剩余可退回金额，请刷新余额');"),
    '提交前端上限校验（后端行锁内复核兜底）');
  assert.ok(actions.includes('call = financeApi.registerExpenseRefund'), '提交走 registerExpenseRefund');
  assert.ok(actions.includes('paymentId: String(form.paymentId)'), 'paymentId 雪花串传输（>2^53 精度防护）');

  const indexJs = read('miniprogram/pages/admin/finance-desk/index.js');
  assert.ok(indexJs.includes("r.refundDirection==='expense'"), '退回余额按 paymentId 聚合且只计 expense 方向');
  assert.ok(indexJs.includes('expenseRefundable:expenseRefundable'), 'platformRegister 注入 expenseRefundable 选项');
  assert.ok(indexJs.includes("r.refundDirection!=='expense'"), '可退余额不含 expense 方向');
  assert.ok(indexJs.includes("label:'付款#'+p.id+' ¥'+PF.yuan(p.amountCents)+(p.payDate?' · '+p.payDate:'')"),
    '付款选项 label 含 id+日期可区分（R2 P2-2）');

  const wxml = read('miniprogram/pages/admin/finance-desk/index.wxml');
  assert.equal(wxml.split('data-type="expense-refund"').length - 1, 1, '总部保留统一退回入口；加盟商沿原付款办理');
  assert.ok(wxml.includes("registerType==='expense-refund'?'登记退款'"), '支出退回使用统一退款表单标题');
  assert.ok(wxml.includes('range="{{platformRegister.expenseRefundable}}"'), '付款单选择器绑定 expenseRefundable');
  assert.ok(wxml.includes('data-field="paymentId" bindchange="registerPick"'), 'picker 走 paymentId 字段');
  assert.equal(wxml.split("registerForm.date||'请选择日期'").length - 1, 2, '退款/支出退回日期占位四处统一（批次#34 扩 client 退款）');

  assert.ok(read('miniprogram/pages/admin/finance-desk/project-books.wxml').includes('bindtap="refundBookEntry"'));
  assert.ok(read('miniprogram/pages/admin/finance-desk/journey-forms.wxml').includes('journeyRefundOptions'));
  console.log('verify-finance-expense-refund: 行为层 A 14 断言 + 行为层 B 14 断言 + 静态层 14 断言 全部 PASS');
}

main().catch(e => { console.error(e.message); process.exit(1); });
