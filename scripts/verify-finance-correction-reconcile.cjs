#!/usr/bin/env node
/**
 * 批次#34 · M08-T08 双端（二）：更正链双端接线 + 异常资金待办标签 + 对账视图 v3 验证。
 * 行为层 A：platform-actions fake page 实跑更正链——openCorrection 确认弹窗→作废（DELETE spy）→
 *           预填表单（金额/原因/日期/流水号/replacesId 原样）→submitRegister 透传 String replacesId；
 *           client refund 日期预填真实今日；取消更正清上下文；非已核实行拒绝。
 * 静态层：api 三方法（voidRefund/reconciliation/replacesId 透传）、对账卡四口径+待归月文案、
 *         作废按钮权限位（refundVoidable+refund sourceKind）、LABELS 待办标签、对账失败降级不拖垮台账。
 * 用法：node scripts/verify-finance-correction-reconcile.cjs
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const PF = require('../miniprogram/utils/platform-finance');
const PT = require('../miniprogram/utils/platform-todos');
const attach = require('../miniprogram/pages/admin/finance-desk/platform-actions');
const localDate = require('../miniprogram/utils/account').localDate;
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');

// wx 全局桩：showModal 捕获确认回调 / showToast 计数
const modalQueue = [];
const toasts = [];
globalThis.wx = {
  getStorageSync: () => '',
  showModal: opt => { modalQueue.push(opt); },
  showToast: opt => toasts.push(opt)
};
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

function fakePage(data) {
  const page = {
    data: Object.assign({ registerError: '', busy: false, refundVoidable: true }, data),
    setData(patch) { Object.assign(this.data, patch); },
    loadPlatformFinanceCalls: 0,
    loadPlatformFinance() { this.loadPlatformFinanceCalls += 1; }
  };
  const calls = { voids: [], refunds: [], expense: [], recon: [] };
  const api = {
    voidRefund(id, reason, idem) {
      calls.voids.push({ id, reason, idem }); return page.voidResult ? page.voidResult() : Promise.resolve({});
    },
    refunds() { return page.readRefunds(); },
    registerRefund(body, idem) { calls.refunds.push({ body, idem }); return Promise.resolve({refundId:'new-refund'}); },
    registerExpenseRefund(body, idem) { calls.expense.push({ body, idem }); return Promise.resolve({refundId:'new-expense-refund'}); },
    idemKey: type => 'idem-' + type
  };
  attach(page, api, PF);
  return { page, calls };
}

async function main() {
  const today = localDate();

  // A1：client 退款更正链全链——作废→预填→提交透传 replacesId
  {
    const { page, calls } = fakePage({bookEntryDetail:{id:'old'}});
    page._refundRows = [{ id: 511, refundDirection: 'client', receiptId: 9001, amountCents: 200000,
      reason: '录错金额', date: [2026,11,4], transactionNo: 'TXN-C9', status: '已核实' }];
    page.openCorrection({ currentTarget: { dataset: { id: 'f-511' } } });
    assert.equal(modalQueue.length, 1, 'A1 作废确认弹窗弹出');
    assert.ok(modalQueue[0].content.includes('2000.00'), '弹窗含原金额');
    modalQueue[0].success({ confirm: true });
    await settle();
    assert.equal(calls.voids.length, 1, '作废请求发出');
    assert.equal(String(calls.voids[0].id), '511', '作废 id 正确');
    assert.equal(calls.voids[0].reason, '更正重登', '作废原因留痕');
    assert.equal(page.data.registerType, 'refund', '作废后自动打开退款表单');
    assert.equal(page.data.bookEntryDetail,null,'更正表单不被旧详情遮挡');
    assert.equal(page.data.registerForm.replacesId, 511, '表单携 replacesId');
    assert.equal(page.data.registerForm.amount, '2000.00', '预填原金额');
    assert.equal(page.data.registerForm.date,'2026-11-04','后端数组日期统一为日期选择器支持的格式');
    assert.equal(page.data.registerForm.transactionNo, 'TXN-C9', '预填原流水号（同号经后端 owner 切换）');
    assert.equal(page.data.registerForm.date, '2026-11-04', '预填原日期');
    assert.equal(page.loadPlatformFinanceCalls, 1, '作废后刷新一次台账');
    page.submitRegister();
    await settle();
    assert.equal(calls.refunds.length, 1, '重登提交发出');
    assert.equal(calls.refunds[0].body.replacesId, '511', 'replacesId 雪花串透传');
    assert.equal(calls.refunds[0].body.receiptId, '9001', 'receiptId 雪花串透传');
    assert.equal(calls.refunds[0].body.date, '2026-11-04', '更正日期不覆盖');
    assert.equal(page.loadPlatformFinanceCalls, 2, '重登成功后再刷新一次');
  }

  // A2：expense 退回更正链——分流出 expense-refund 表单
  {
    const { page, calls } = fakePage({platformRegister:{expenseRefundable:[{id:7002,refundableCents:300000,fundingType:'EMPLOYEE'}]}});
    page._refundRows = [{ id: 622, refundDirection: 'expense', paymentId: 7002, amountCents: 300000,
      reason: '供应商退回', date: '', transactionNo: '', fundingType: 'COMPANY', status: '已核实' }];
    page.openCorrection({ currentTarget: { dataset: { id: 'f-622' } } });
    modalQueue.pop().success({ confirm: true });
    await settle();
    assert.equal(page.data.registerType, 'expense-refund', 'A2 分流出支出退回表单');
    assert.equal(page.data.registerForm.paymentId, 7002, '预填原付款单');
    assert.equal(page.data.registerForm.date, today, '空日期预填真实今日');
    page.submitRegister();
    await settle();
    assert.equal(calls.expense.length, 1, '支出退回重登发出');
    assert.equal(calls.expense[0].body.fundingType, 'COMPANY', '只改原因时不按原出资员工推断退回去向');
    assert.equal(calls.expense[0].body.replacesId, '622', '支出退回 replacesId 透传');
    assert.equal(calls.expense[0].body.date, today, '空日期兜底真实今日');
  }

  // A3：取消更正——清上下文不动作废
  {
    const { page, calls } = fakePage({});
    page._refundRows = [{ id: 633, refundDirection: 'client', receiptId: 1, amountCents: 100000,
      reason: '', date: '', transactionNo: '', status: '已核实' }];
    page.openCorrection({ currentTarget: { dataset: { id: 'f-633' } } });
    modalQueue.pop().success({ confirm: false });
    await settle();
    assert.equal(calls.voids.length, 0, '取消不动作废');
    assert.equal(page.data.registerType || '', '', '取消不打开表单');
  }

  // A4：非已核实行拒绝
  {
    const { page, calls } = fakePage({});
    page._refundRows = [{ id: 644, refundDirection: 'client', status: '已作废' }];
    page.openCorrection({ currentTarget: { dataset: { id: 'f-644' } } });
    assert.equal(calls.voids.length, 0, '已作废行不可再更正');
    assert.ok(page.data.registerError.includes('仅已核实'), '拒绝文案');
  }

  // 弱网：作废已落账、回执丢失；重新打开后，直接从同一原单续办。
  {
    const id = '998765432109876543';
    const row = { id, refundDirection: 'client', receiptId: '998765432109876544',
      amountCents: 12000, date: [2026, 11, 4], status: '已核实' };
    const previous = fakePage({});
    previous.page._refundRows = [row];
    previous.page.voidResult = () => Promise.reject(new Error('断网'));
    previous.page.openCorrection({ currentTarget: { dataset: { id: 'f-' + id } } });
    modalQueue.pop().success({ confirm: true });
    await settle();
    assert.equal(previous.calls.voids.length, 1);
    assert.equal(previous.page.loadPlatformFinanceCalls, 1, '回执不确定时回读台账');
    assert.equal(previous.page.data.registerType, undefined);

    const { page, calls } = fakePage({ view: 'desk' });
    const archived = { ...row, status: '已作废', voidReason: '更正重登', replacedById: null };
    page._refundRows = [archived, { ...archived, id: 'other', voidReason: '撤销误录' },
      { ...archived, id: 'old', replacedById: 'new' }, { ...archived, id: 'missing', receiptId: null }];
    const click = { currentTarget: { dataset: { id } } };
    page.readRefunds = async () => page._refundRows;
    assert.deepEqual(page.pendingRefundCorrections(page._refundRows, 'finance-lead').map(r => r.id), [id]);
    assert.deepEqual(page.pendingRefundCorrections(page._refundRows, 'finance'), []);
    assert.equal(PF.ledgerRows([], [], page._refundRows, '').allCount, 0, '待重登不进入有效流水');
    await page.resumeRefundCorrection(click);
    assert.equal(page.data.view, 'flows');
    assert.equal(page.data.registerType, 'refund');
    assert.equal(page.data.registerForm.receiptId, row.receiptId);
    await page.submitRegister();
    await settle();
    assert.equal(calls.voids.length, 0, '续办不再次作废');
    assert.equal(calls.refunds[0].body.replacesId, id);
  }
  {
    const row = { id: '744', refundDirection: 'expense', paymentId: '804', amountCents: 10000, fundingType: 'COMPANY',
      status: '已作废', voidReason: '更正重登' };
    const click = { currentTarget: { dataset: { id: row.id } } };
    const { page, calls } = fakePage({ refundVoidable: false });
    page._refundRows = [row];
    await page.resumeRefundCorrection(click); // 未授权的岗位不发送网络请求
    page.data.refundVoidable = true;
    page.readRefunds = async () => [{ ...row, replacedById: 'done' }];
    await page.resumeRefundCorrection(click);
    assert.equal(page.data.registerType, undefined, '另一财务已完成，不再打开表单');
    let give;
    page.readRefunds = () => new Promise(resolve => { give = resolve; });
    const pending = page.resumeRefundCorrection(click);
    page.wbGeneration = 1;
    give([row]);
    await pending;
    assert.equal(page.data.registerType, undefined, '旧岗位回执不能打开更正');
    page.readRefunds = async () => [row];
    await page.resumeRefundCorrection(click);
    assert.equal(page.data.registerType, 'expense-refund');
    assert.equal(page.data.registerForm.paymentId, row.paymentId);
    assert.equal(page.data.registerForm.refundFundingType, 'COMPANY', '续办保留原来实际退回的公司');
    const pick = value => page.registerInput({ currentTarget: { dataset: { field: 'refundFundingType' } }, detail: {value} });
    pick(2); assert.equal(page.data['registerForm.refundFundingType'], 'EMPLOYEE', '财务明确选择才切换');
    pick(0); assert.equal(page.data['registerForm.refundFundingType'], 'COMPANY', '沿用原退回而非原出资');
    // 仿小程序 setData 路径，把显式修改后的值提交到登记。
    pick(2);
    page.data.registerForm.refundFundingType = page.data['registerForm.refundFundingType'];
    await page.submitRegister();
    await settle();
    assert.equal(calls.expense[0].body.fundingType, 'EMPLOYEE');
    assert.equal(calls.voids.length, 0);
  }

  // A5：client refund openRegister 预填今日（批次#34 修复历史占位误导）
  {
    const page = { data: {}, setData(patch) { Object.assign(this.data, patch); } };
    attach(page, {}, PF);
    page.openRegister({ currentTarget: { dataset: { type: 'refund' } } });
    assert.equal(page.data.registerForm.date, today, 'client 退款表单预填真实今日');
    page.openRegister({ currentTarget: { dataset: { type: 'payable' } } });
    assert.equal(page.data.registerForm.date, undefined, '应付表单不预填日期');
  }

  // A6（R1 P2-1）：登记挂起中（registerBusy/busy）双向互斥——更正入口与提交入口互相堵
  {
    modalQueue.length = 0; // 清空前序用例弹窗，长度断言才有效
    const { page, calls } = fakePage({});
    page.data.registerBusy = true;
    page._refundRows = [{ id: 655, refundDirection: 'client', receiptId: 1, amountCents: 100000, status: '已核实' }];
    page.openCorrection({ currentTarget: { dataset: { id: 'f-655' } } });
    assert.equal(modalQueue.length, 0, '登记挂起中更正入口被堵（不弹窗）');
    page.data.registerBusy = false;
    page.data.busy = true; // 更正作废挂起中
    page.submitRegister();
    assert.equal(calls.refunds.length + calls.expense.length, 0, '更正挂起中提交入口被堵');
    page.data.busy = false;
    // 代次校验：旧登记成功回调不清新表单（模拟更正后旧回调迟到）
    const p2 = { data: { registerType: 'refund', registerForm: { receiptId: 1, replacesId: 9 }, registerBusy: true, registerError: '' },
      setData(patch) { Object.assign(this.data, patch); }, loadPlatformFinance() {} };
    let seq = 0;
    p2._registerSeq = 0;
    // 直接触发一次登记占位 seq，再人为推进代次模拟更正接管
    const calls2 = { refunds: [] };
    const api2 = { registerRefund() { calls2.refunds.push(1); return new Promise(() => {}); }, idemKey: t => 'i-' + t };
    attach(p2, api2, PF);
    p2._refundRows = [];
    p2.submitRegister(); // seq=1 挂起（永不 resolve）
    p2._registerSeq = 2; // 模拟更正链已接管代次
    await settle();
    assert.equal(p2.data.registerType, 'refund', '旧代次回调不清更正表单（挂起未回也无写场）');
    assert.equal(p2.data.registerBusy, true, '旧代次不复位 busy（新操作接管）');
  }

  // —— 静态层 ——

  const api = read('miniprogram/api/finance.js');
  assert.ok(api.includes("http.del('/franchise/finance/refunds/' + id"), 'voidRefund 走 DELETE /refunds/{id}');
  assert.ok(api.includes("http.get('/franchise/finance/reconciliation', { month })"), 'reconciliation 端点');
  assert.ok(/voidRefund, reconciliation,/.test(api), 'module.exports 导出双方法');

  const actions = read('miniprogram/pages/admin/finance-desk/platform-actions.js');
  assert.ok(actions.includes("type === 'expense-refund' || type === 'refund'"), '退款/支出退回统一预填今日');
  assert.ok(actions.includes('replacesId: form.replacesId ? String(form.replacesId) : undefined'),
    '两分支 replacesId 雪花串透传');
  assert.ok(actions.includes("financeApi.voidRefund(refundId, correctionReason"), '作废原因固定留痕');
  assert.ok(actions.includes('if (this.data.busy || this.data.registerBusy) return;'), 'R1 P2-1 更正入口双向互斥');
  assert.ok(actions.includes('if (this.data.registerBusy || this.data.busy) return;'), 'R1 P2-1 提交入口双向互斥');
  assert.ok(actions.includes('seq === this._registerSeq') && actions.includes('if (!alive()) return;'), 'R1 P2-1 成功回调代次校验');

  const indexJs = read('miniprogram/pages/admin/finance-desk/index.js');
  assert.ok(indexJs.includes("['finance-lead','boss','owner'].includes(post.roleCode)"), '作废权限位=lead/boss/owner');
  assert.ok(indexJs.includes('financeApi.reconciliation(month)'), '对账独立拉取');
  assert.ok(indexJs.indexOf('financeApi.reconciliation(month)') > indexJs.lastIndexOf('platformFinance:{status:\'degraded\''),
    'R1 P2-2 对账调用在台账 Promise.all 之后独立启动');
  assert.ok(indexJs.includes("reconciliation:{status:'degraded'"), '对账失败降级不拖垮台账');
  assert.ok(indexJs.includes('this._refundRows=refunds'), 'refundRows 备查供更正反查');

  const wxml = read('miniprogram/pages/admin/finance-desk/index.wxml');
  assert.equal(wxml.split("对账视图（{{reconciliation.month||'本月'}}）").length - 1, 1,
    'R3 P2-3 对账卡单份（两 ledger block 条件重叠，重复展示已消除）');
  assert.ok(wxml.includes('待归月单列，不计入净额'), '待归月语义文案');
  assert.ok(wxml.includes('毛额·已登记收款') && wxml.includes('净实收'), '四口径卡头');
  assert.equal(wxml.split('bindtap="openCorrection"').length - 1, 1, '作废重登按钮唯一（台账行模板）');
  assert.ok(wxml.includes('wx:if="{{refundVoidable&&item.sourceKind===\'refund\'&&item.status===\'已核实\'}}"'),
    '按钮三重条件（权限+方向+状态）');
  assert.equal(wxml.split("registerForm.date||'请选择日期'").length - 1, 2, '退款/支出退回日期占位四处统一');

  // 待办标签：异常资金 key 第二段映射
  assert.equal(PT.typeLabelOf('fr-fin:abnormal-link:1', true), '异常资金待关联', 'LABELS abnormal-link');
  assert.equal(PT.typeLabelOf('fr-fin:bank-confirm:2', true), '到账待确认', 'LABELS bank-confirm');

  console.log('verify-finance-correction-reconcile: 更正链、跨页恢复与对账接线全部 PASS');
}

main().catch(e => { console.error(e && e.message); process.exit(1); });
