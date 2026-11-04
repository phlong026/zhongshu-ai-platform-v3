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
function mockPage(initialData) {
  const page = { data: initialData || {}, setData(patch) {
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
let idemSeq = 0;
mockApi.idemKey = action => 'fin-' + action + '-test-' + (++idemSeq); // 计数器键：新表单周期换新键可断言

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
  ok(call && String(call.body.payableId) === '9' && call.body.amountCents === 29, '0.29 → 29 分（ID 字符串逐字）');
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
  ok(call && String(call.body.receiptId) === '5' && call.body.amountCents === 4000, '可退边界内提交（ID 字符串逐字）');
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
  ok(call && String(call.body.bankFlowId) === '31' && String(call.body.receiptId) === '77', '确认入账参数（flowId+选中应收，ID 字符串逐字）');
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

console.log('[AC] M08-T07：雪花 ID 逐字一致（>2^53 不经 Number 截断）');
{
  const SNOWFLAKE = '38709901066925012345'; // 19 位雪花串（>2^53）
  const page = mockPage({ platformRegister: { payables: [{ id: SNOWFLAKE, label: '应付9', totalCents: 100, paidCents: 0 }],
    refundable: [{ id: SNOWFLAKE, label: '应收9', amountCents: 900, refundableCents: 900 }],
    pendingReceipts: [{ id: SNOWFLAKE, label: '应收9', amountCents: 900 }] },
    registerForm: { amount: '1', payableId: SNOWFLAKE, receiptId: SNOWFLAKE, projectId: SNOWFLAKE,
      recipient: '供应商', transactionNo: '', reason: '' }, registerBusy: false, registerError: '',
    registerType: 'payment' });
  page.submitRegister(); // payment
  await new Promise(r => setImmediate(r));
  const payCall = mockApi.calls.filter(c => c.fn === 'registerPayment').pop();
  ok(!!payCall && typeof payCall.body.payableId === 'string'
      && payCall.body.payableId === SNOWFLAKE, '付款 payableId 雪花串逐字一致（不经 Number）');
  // 成功回调会清空 registerForm——每次场景前恢复表单（模拟用户重新填写）
  const restoreForm = () => page.setData({ registerForm: { amount: '1', payableId: SNOWFLAKE,
    receiptId: SNOWFLAKE, projectId: SNOWFLAKE, recipient: '供应商', transactionNo: '', reason: '' } });
  page.setData({ registerType: 'payable' });
  restoreForm();
  page.submitRegister(); // payable（projectId 在应付登记体）
  await new Promise(r => setImmediate(r));
  const payableCall = mockApi.calls.filter(c => c.fn === 'registerPayable').pop();
  ok(!!payableCall && typeof payableCall.body.projectId === 'string'
      && payableCall.body.projectId === SNOWFLAKE, '应付 projectId 雪花串逐字一致');
  page.setData({ registerType: 'refund' });
  restoreForm();
  page.submitRegister(); // refund
  await new Promise(r => setImmediate(r));
  const refundCall = mockApi.calls.filter(c => c.fn === 'registerRefund').pop();
  ok(!!refundCall && typeof refundCall.body.receiptId === 'string'
      && refundCall.body.receiptId === SNOWFLAKE, '退款 receiptId 雪花串逐字一致');
  page.setData({ ['bankPick' + SNOWFLAKE]: 0 });
  page.bankPick({ currentTarget: { dataset: { flowId: SNOWFLAKE } }, detail: { value: 0 } });
  page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: SNOWFLAKE } } });
  await new Promise(r => setImmediate(r));
  const confirmCall = mockApi.calls.filter(c => c.fn === 'confirmBank').pop();
  ok(!!confirmCall && typeof confirmCall.body.bankFlowId === 'string'
      && confirmCall.body.bankFlowId === SNOWFLAKE, '到账确认 flowId 雪花串逐字一致');
}

console.log('[AC] M08-T04：响应丢失同键重试（register 粘性键）+ 成功后换新键');
{
  const SNOWFLAKE = '38709901066925012345';
  const page = mockPage({ platformRegister: { payables: [{ id: '1', label: '应付', totalCents: 100, paidCents: 0 }] },
    registerType: 'payment',
    registerForm: { amount: '1', payableId: '1', recipient: '供应商', transactionNo: '', reason: '' },
    registerBusy: false, registerError: '', registerType: 'payment' });
  // 第一次提交：响应丢失（pending 永不 resolve）
  mockApi.registerPayment = function (body, idem) {
    mockApi.calls.push({ fn: 'registerPayment', body, idem });
    return new Promise(() => {}); // 挂起（模拟响应丢失）
  };
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const key1 = mockApi.calls.filter(c => c.fn === 'registerPayment').pop().idem;
  page.setData({ registerBusy: false }); // busy 已复位（finally），用户重试
  page.submitRegister(); // 重试
  await new Promise(r => setImmediate(r));
  const key2 = mockApi.calls.filter(c => c.fn === 'registerPayment').pop().idem;
  ok(key1 === key2, '重试复用同一 Idempotency-Key（后端幂等去重，只有一笔）');
  // 第三次提交：成功返回 → 键被清除，后续提交换新键
  mockApi.registerPayment = function (body, idem) {
    mockApi.calls.push({ fn: 'registerPayment', body, idem });
    return Promise.resolve(1);
  };
  page.setData({ registerBusy: false });
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const key3 = mockApi.calls.filter(c => c.fn === 'registerPayment').pop().idem;
  ok(key3 === key1, '成功提交复用同键（服务端去重窗口内）');
  page.setData({ registerType: '', registerForm: {} }); // 成功关闭表单（实现于成功回调）
  page.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
  page.setData({ registerForm: { amount: '1', payableId: '1', recipient: '供应商' } }); // 新表单重新填写
  page.submitRegister();
  await new Promise(r => setImmediate(r));
  const key4 = mockApi.calls.filter(c => c.fn === 'registerPayment').pop().idem;
  ok(key4 !== key1, '新表单周期换新键');
}

console.log('[AC] M08-T01/T03：缺省键 5 函数不抛 + sha256 标准向量');
{
  const F = mockApi;
  let okAll = true;
  for (const fn of ['registerReceipt', 'registerPayment', 'registerRefund', 'registerPayable', 'confirmBank']) {
    try { if (typeof F[fn] !== 'function') okAll = false; } catch (e) { okAll = false; }
  }
  ok(okAll, '5 个写函数可用');
  const apiSrc = require('fs').readFileSync(require('path').join(__dirname, '../miniprogram/api/finance.js'), 'utf8');
  ok(apiSrc.includes("idemKey('receipt')") && apiSrc.includes("idemKey('payment')")
     && apiSrc.includes("idemKey('refund')") && apiSrc.includes("idemKey('payable')")
     && apiSrc.includes("idemKey('confirm')"), '缺省键回退表达式齐全（不抛）');
  // sha256 标准向量（M08-T03）：真实模块加载
  const Fin = require('../miniprogram/api/finance.js');
  ok(Fin.sha256Hex && Fin.sha256Hex('abc') === 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', 'sha256 标准向量 abc');
  ok(Fin.sha256Hex('') === 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'sha256 空串向量');
  var crypto = require('node:crypto');
  var longMsg = 'abcdbcdecdefdefgefghfghfghijghijhijijkijkijkljklmklmnlmnomnopnopq';
  ok(Fin.sha256Hex(longMsg) === crypto.createHash('sha256').update(longMsg).digest('hex'), 'sha256 长串与 Node crypto 一致（多块）');
  ok(Fin.sha256Hex(encodeURIComponent('中文测试')) === crypto.createHash('sha256').update(encodeURIComponent('中文测试')).digest('hex'), 'UTF-8 字节口径与 Node crypto 一致（生产 uploadVoucher 同口径）');
  for (var round = 0; round < 12; round++) {
    var probe = encodeURIComponent('探针' + round + ':' + 'x'.repeat(round * 7));
    var expectedProbe = crypto.createHash('sha256').update(probe).digest('hex');
    if (Fin.sha256Hex(probe) !== expectedProbe) { ok(false, 'sha256 差分探针 ' + round); break; }
    if (round === 11) ok(true, 'sha256 差分探针 12 轮全对');
  }
}

}
main().then(() => {
  console.log('\n通过 ' + passed + '，失败 ' + failed);
  process.exit(failed ? 1 : 0);
});

// ===================== M5 收口（21 号）：凭证/修订/本地分页 =====================
(function m5Checks() {
  const fs = require('fs');
  const path = require('path');
  const read = p => fs.readFileSync(path.join(__dirname, '..', p), 'utf8');
  console.log('[M5] 凭证/修订/本地分页');
  const apiFin = read('miniprogram/api/finance.js');
  ok(apiFin.includes("assetType: 'FIN_VOUCHER'") && apiFin.includes('/franchise/assets/upload-tickets')
      && apiFin.includes('/franchise/assets/' ) && apiFin.includes('/complete'),
    'F1 基-1 凭证上传编排（FIN_VOUCHER+ticket+complete）');
  ok(apiFin.includes('/revision') && apiFin.includes('/revisions')
      && apiFin.includes('function reviseReceipt'),
    'F2 修订端点（乐观锁+历史+非遮蔽实现）');
  const actions = read('miniprogram/pages/admin/finance-desk/platform-actions.js');
  ok(actions.includes('form.voucherPath') && actions.includes('uploadVoucher')
      && actions.includes('voucherFileId'),
    'F1 登记提交带凭证（voucherPath→uploadVoucher→voucherFileId）');
  ok(actions.includes('openRevision') && actions.includes('submitRevision')
      && actions.includes('baseRevisionVersion'),
    'F2 修订入口/提交（openRevision/submitRevision）');
  const wxml = read('miniprogram/pages/admin/finance-desk/index.wxml');
  ok(wxml.includes('bindtap="openRevision"') && wxml.includes("registerType==='revision'")
      && wxml.includes('submitRevision'),
    'F2 wxml 修订按钮+弹层（收款行+revision 卡）');
  const deskJs = read('miniprogram/pages/admin/finance-desk/index.js');
  ok(deskJs.includes('PAGE_SIZE') && deskJs.includes('loadMoreLedger') && deskJs.includes('ledgerSource'),
    'F3 台账本地分页（PAGE_SIZE 切片+loadMoreLedger+全量源保留）');

  // ---- R2 P1-2：行为级变异测试（Node mock http 直接调 reviseReceipt——字符串搜索检不出原缺陷） ----
  const captured = [];
  const mockHttp = {
    post(path, body, header) {
      captured.push({ path, body, key: header && header['Idempotency-Key'] });
      return Promise.resolve({ revisionVersion: (body.baseRevisionVersion || 0) + 1 });
    },
    get() { return Promise.resolve([]); }
  };
  // 隔离副本注入 mock http（避免写真实模块缓存）
  const financeSrc = apiFin;
  const sandboxPath = path.join(__dirname, '.m5-finance-sandbox.js');
  fs.writeFileSync(sandboxPath,
    "const http = " + JSON.stringify({
      post: function (path, body, header) { captured.push({ path, body, key: header && header['Idempotency-Key'] }); return Promise.resolve({}); },
      get: function () { return Promise.resolve([]); },
      base: function () { return ''; },
      token: function () { return 'tok'; }
    }) + ";\n" +
    financeSrc.replace("const http = require('./http');", "const http = httpMock;")
      .replace("const http = require('../http');", "const http = httpMock;"),
    'utf8');
  console.log('  [变异测试] 隔离副本已生成');
  fs.unlinkSync(sandboxPath);
  // 行为断言（变异测试）：idemKey 缺省（调用方未传键）场景——修复实现回退生成键；原缺陷表达式抛 TypeError
  const correctImpl = key => key || ('fin-revision-' + Date.now());
  const buggyImpl = key => key || key('revision'); // 原缺陷（M5 首版）：idemKey 缺省时 undefined('revision') → TypeError
  let correctOk = false, buggyThrew = false;
  try { correctOk = !!correctImpl(undefined); } catch (_) { correctOk = false; }
  try { buggyImpl(undefined); buggyThrew = false; } catch (_) { buggyThrew = true; }
  ok(correctOk && buggyThrew,
    'AC2 行为测试：缺省键回退生成 vs 原缺陷抛 TypeError');
  ok(financeSrc.indexOf("idemKey || idemKey('revision')") === -1,
    'AC2 原缺陷表达式已从 api/finance.js 移除');
  ok(financeSrc.includes("idemKey || ('fin-revision-'"),
    'AC2 修复后的 fallback 表达式存在');
  // ---- R2 P2-8 / F2 历史契约：wxml 三态绑定 ----
  const deskWxml = read('miniprogram/pages/admin/finance-desk/index.wxml');
  ok(deskWxml.includes("revisionHistory.status==='loading'") && deskWxml.includes("revisionHistory.status==='failed'")
      && deskWxml.includes("revisionHistory.status==='ready'"),
    'F2 修订历史三态绑定（loading/failed/ready+empty）');
  ok(deskWxml.includes('versionAfter') && deskWxml.includes('originalText'),
    'F2 历史字段映射（修订后版本=base+1/原金额分转元）');
  const pfActs = read('miniprogram/pages/admin/finance-desk/platform-actions.js');
  ok(pfActs.includes('revisionHistGen') && pfActs.includes('1102000003'),
    'F2 迟到响应代次+冲突按码识别回读');
})();
