/**
 * B2 板块 C 验收：finance-desk F1-F7 接线（需求文档 03 §五/§六/§七/§八）。
 * 纯 Node（零 wx）：platform-actions 逻辑注入 mock page + PF 纯函数 + F7 封存过滤。
 */
const path = require('path');
// mock wx（platform-actions 直接引用 wx.showToast）
global.wx = { toasts: [], showToast(t) { this.toasts.push(t); } };
const PF = require('../miniprogram/utils/platform-finance');
const attach = require('../miniprogram/pages/admin/finance-desk/platform-actions');

let passed = 0;
let failed = 0;
function ok(cond, name) {
  if (cond) { passed += 1; console.log('  ok - ' + name); } else { failed += 1; console.log('  FAIL - ' + name); }
}

/** mock page + mock financeApi */
function mockPage() {
  const page = { data: {}, setData(patch) {
    // 支持 'a.b' 路径键（真机 setData 语义）
    for (const [k, v] of Object.entries(patch)) {
      if (k.includes('.')) {
        const parts = k.split('.');
        let obj = this.data;
        for (let i = 0; i < parts.length - 1; i++) {
          if (typeof obj[parts[i]] !== 'object' || obj[parts[i]] === null) obj[parts[i]] = {};
          obj = obj[parts[i]];
        }
        obj[parts[parts.length - 1]] = v;
      } else { this.data[k] = v; }
    }
  },
    loadPlatformFinance() { /* mock：成功回调里刷新 */ } };
  attach(page, mockApi, PF);
  return page;
}
const mockApi = { calls: [] };
['registerReceipt', 'registerPayment', 'registerRefund', 'registerPayable', 'confirmBank'].forEach(fn => {
  mockApi[fn] = function (body, idemOrReceipt) {
  // confirmBank(flowId, receiptId) 两参数；register*(body, idemKey)
  const entry = fn === 'confirmBank'
    ? { fn, body: { bankFlowId: body, receiptId: idemOrReceipt } }
    : { fn, body, idem: idemOrReceipt };
  mockApi.calls.push(entry);
  return Promise.resolve({ id: 1 });
};
});
mockApi.idemKey = action => 'fin-' + action + '-test';

async function main() {
console.log('[AC] F2 收款登记：金额校验/bank 必流水号/提交体');
{
  const page = mockPage();
  page.openRegister({ currentTarget: { dataset: { type: 'receipt' } } });
  ok(page.data.registerType === 'receipt', 'openRegister 进入收款表单');
  page.setData({ registerForm: { name: '装修款', amount: 'abc' } });
  page.submitRegister();
  ok(page.data.registerError === '请输入正确的金额（最多两位小数）', '非法金额拦截（零请求）');
  ok(mockApi.calls.length === 0, '非法金额零请求');
  page.setData({ registerForm: { name: '装修款', amount: '12.5', method: 'bank', transactionNo: '' } });
  page.submitRegister();
  ok(page.data.registerError === '银行收款须填流水号', 'bank 缺流水号拦截');
  page.setData({ registerForm: { name: '装修款', amount: '12.5', method: 'bank', transactionNo: 'TX001' } });
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const call = mockApi.calls.find(c => c.fn === 'registerReceipt');
  ok(call && call.body.sourceType === 'manual' && call.body.amountCents === 1250
    && call.body.transactionNo === 'TX001', '提交体：manual/分金额/流水号');
  ok(/^fin-receipt-/.test(call.idem), '幂等键 fin-receipt-*');
  ok(page.data.registerType === '', '成功后表单关闭');
}

console.log('[AC] F3 付款登记：payableId 必选 + 金额换算');
{
  const page = mockPage();
  page.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
  page.setData({ registerForm: { amount: '100' } });
  page.submitRegister();
  ok(page.data.registerError === '请选择关联应付', '缺应付拦截');
  page.setData({ platformRegister: { payables: [{ id: 9, label: '应付9', totalCents: 10000, paidCents: 0 }], refundable: [], pendingReceipts: [] } });
  page.registerPick({ currentTarget: { dataset: { field: 'payableId' } }, detail: { value: '0' } });
  ok(page.data.registerForm.payableId === 9, 'picker 写入 payableId');
  page.setData({ registerForm: Object.assign({}, page.data.registerForm, { amount: '0.29' }) });
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const call = mockApi.calls.find(c => c.fn === 'registerPayment');
  ok(call && call.body.payableId === 9 && call.body.amountCents === 29, '0.29 → 29 分');
}

console.log('[AC] F4 退款登记：可退余额本地预校验');
{
  const page = mockPage();
  page.openRegister({ currentTarget: { dataset: { type: 'refund' } } });
  page.setData({ platformRegister: { payables: [], refundable: [
    { id: 5, label: '收款5', amountCents: 10000, refundableCents: 4000 }], pendingReceipts: [] } });
  page.registerPick({ currentTarget: { dataset: { field: 'receiptId' } }, detail: { value: '0' } });
  page.setData({ registerForm: Object.assign({}, page.data.registerForm, { amount: '40.01' }) });
  page.submitRegister();
  ok(page.data.registerError === '超过剩余可退金额，请刷新余额', '超可退余额本地拦截（零请求）');
  ok(!mockApi.calls.some(c => c.fn === 'registerRefund'), '超额零请求');
  page.setData({ registerForm: Object.assign({}, page.data.registerForm, { amount: '40' }) });
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const call = mockApi.calls.find(c => c.fn === 'registerRefund');
  ok(call && call.body.receiptId === 5 && call.body.amountCents === 4000, '可退边界内提交');
}

console.log('[AC] F5 应付登记：收款方必填');
{
  const page = mockPage();
  page.openRegister({ currentTarget: { dataset: { type: 'payable' } } });
  page.setData({ registerForm: { amount: '50', recipient: '' } });
  page.submitRegister();
  ok(page.data.registerError === '请输入收款方', '缺收款方拦截');
  page.setData({ registerForm: { amount: '50', recipient: '材料商', stage: '主体' } });
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const call = mockApi.calls.find(c => c.fn === 'registerPayable');
  ok(call && call.body.recipient === '材料商' && call.body.totalCents === 5000 && call.body.stage === '主体', '应付提交体');
}

console.log('[AC] F6 到账确认：必选应收/提交参数');
{
  const page = mockPage();
  page.setData({ platformRegister: { payables: [], refundable: [], pendingReceipts: [
    { id: 77, label: '应收77', amountCents: 5000 }] }, busy: false });
  page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 31 } } });
  ok(page.data.busy === false && !mockApi.calls.some(c => c.fn === 'confirmBank'), '未选应收不发起（零请求）');
  page.bankPick({ currentTarget: { dataset: { flowId: 31 } }, detail: { value: '0' } });
  page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: 31 } } });
  await new Promise(r => setImmediate(r));
  const call = mockApi.calls.find(c => c.fn === 'confirmBank');
  ok(call && call.body.bankFlowId === 31 && call.body.receiptId === 77, '确认入账参数（flowId+选中应收）');
}

console.log('[AC] F7 封存过滤：SEALED 全集不出现（返回新对象）');
{
  const entries = PF.SEALED.map(action => ({ action })).concat([{ action: 'receipt-register' }]);
  const filtered = PF.filterSealedEntries(entries);
  ok(filtered.length === 1 && filtered[0].action === 'receipt-register', '封存全集被过滤、正常入口保留');
  ok(filtered !== entries, '返回新数组（不改原配置）');
  const ledgerRows = PF.ledgerRows(
    [{ id: 1, name: 'a', amountCents: 1, status: '已登记', receivedDate: '2026-11-01' }],
    [{ id: 2, name: 'b', amountCents: 2, status: '已核实', payDate: '2026-11-01' }], [], '');
  ok(ledgerRows.rows.every(r => ['收款', '付款', '退款'].includes(r.type)), 'F1 台账三分类（收+/付−/退−）');
  ok(!ledgerRows.rows.some(r => PF.SEALED.includes(r.action)), '台账行无封存动作');
}

console.log('[AC] F1 台账映射：金额与符号');
{
  const rows = PF.ledgerRows(
    [{ id: 1, name: '交易客户甲·首款回款', amountCents: 5604000, status: '已登记', receivedDate: '2026-11-01', createTime: '2026-11-01T10:00:00+08' }],
    [{ id: 2, amountCents: 1000, status: '已核实', payDate: '2026-11-02' }],
    [{ id: 3, amountCents: 500, status: '已核实', date: '2026-11-03' }], '');
  const r = rows.rows[0];
  ok(r.sign === '+' && r.type === '收款' && r.amountCents === 5604000, 'business 回款行（+/收款）');
  ok(rows.rows[1].sign === '−' && rows.rows[2].sign === '−', '付款/退款负向');
}

console.log('[AC] F6 补：PENDING 状态映射与错误码');
{
  ok(PF.errorMessage({ code: 1102000016 }).indexOf('不一致') >= 0, '016 文案');
  ok(PF.errorMessage({ code: 1102000018 }).indexOf('权限') >= 0, '018 文案');
  ok(PF.errorMessage({ code: 1102000005 }).indexOf('可退') >= 0, '005 文案');
}

}
main().then(() => {
  console.log('\n通过 ' + passed + '，失败 ' + failed);
  process.exit(failed ? 1 : 0);
});
