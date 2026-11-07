/**
 * 财务账簿访问层（B1 接入）：finance-desk 工作台对接 /franchise/finance/**。
 * 写操作自动携带 Idempotency-Key（键由调用方生成传入，同键同内容安全重试）。
 */
const http = require('./http');

/** 生成幂等键：fin-{动作}-{uuid}（随机 16 位十六进制） */
function randomHex() {
  let hex = '';
  for (let i = 0; i < 16; i += 1) hex += Math.floor(Math.random() * 16).toString(16);
  return hex;
}

/** 生成幂等键：fin-{动作}-{uuid} */
function idemKey(action) {
  return 'fin-' + action + '-' + randomHex();
}
// M08-T01：模块级生成器别名——register* 的参数名 idemKey 会遮蔽同名模块函数（缺省键调用即 TypeError），
// 函数体回退统一走 genIdemKey。
const genIdemKey = idemKey;

/** 实收列表（本公司；status 可选："已登记" 等） */
function receipts(status) {
  return http.get('/franchise/finance/receipts', status ? { status } : undefined);
}

/** 付款列表 */
function payments(status) {
  return http.get('/franchise/finance/payments', status ? { status } : undefined);
}

/** 应付列表 */
function payables(status) {
  return http.get('/franchise/finance/payables', status ? { status } : undefined);
}

/** 退款列表 */
function refunds() {
  return http.get('/franchise/finance/refunds');
}

/** 登记实收（manual 来源；sourceType/designCommissionId/designStage 走 design 门禁） */
function registerReceipt(body, idemKey) {
  return http.post('/franchise/finance/receipts', body, { 'Idempotency-Key': idemKey || genIdemKey('receipt') });
}

/** 登记付款（payableId 必填） */
function registerPayment(body, idemKey) {
  return http.post('/franchise/finance/payments', body, { 'Idempotency-Key': idemKey || genIdemKey('payment') });
}

/** 登记退款（线下事实登记制：登记即已核实） */
function registerRefund(body, idemKey) {
  return http.post('/franchise/finance/refunds', body, { 'Idempotency-Key': idemKey || genIdemKey('refund') });
}

/** 登记支出退回（M08-T08：供应商/劳务退回，冲减已付现金；paymentId 关联原付款单） */
function registerExpenseRefund(body, idemKey) {
  return http.post('/franchise/finance/expense-refunds', body, { 'Idempotency-Key': idemKey || genIdemKey('expense-refund') });
}

/** 登记应付 */
function registerPayable(body, idemKey) {
  return http.post('/franchise/finance/payables', body, { 'Idempotency-Key': idemKey || genIdemKey('payable') });
}

/** 作废退款（M08-T08 更正链第一步；finance-lead/boss/owner；void_reason 必填留痕；重复作废幂等） */
function voidRefund(id, reason, idemKey) {
  return http.del('/franchise/finance/refunds/' + id, { reason },
    { 'Idempotency-Key': idemKey || genIdemKey('refund-void') });
}

/** 对账视图（批次#34：月度四口径 毛额/退款/净额/异常资金含待归月单列） */
function reconciliation(month) {
  return http.get('/franchise/finance/reconciliation', { month });
}

/** 到账确认（唯一入账；幂等重放返回首次 confirmId） */
function confirmBank(bankFlowId, receiptId, idemKey) {
  return http.post('/franchise/finance/bank-confirms', { bankFlowId, receiptId },
    { 'Idempotency-Key': idemKey || genIdemKey('confirm') });
}

/** 银行流水列表（待匹配在前） */
function bankFlows() {
  return http.get('/franchise/finance/bank-flows', { status: '待匹配' });
}

/** 项目账聚合 */
function projectAccount(projectId) {
  return http.get('/franchise/finance/project-accounts/' + projectId);
}


// ===================== M5 收口（21 号）：基-1 凭证上传 + 收款修订 =====================

/** 读文件为 base64（wxFileSystemManager；jpg/png/pdf ≤20MB 走 FIN_VOUCHER 免审） */
function readFileBase64(filePath) {
  return new Promise((resolve, reject) => {
    wx.getFileSystemManager().readFile({
      filePath, encoding: 'base64',
      success: res => resolve(res.data), fail: reject
    });
  });
}

/**
 * 基-1 票据链凭证上传（21 号 F1 / 14 文档 D-基1-2 切换）：FIN_VOUCHER（jpg/png/pdf ≤20MB 免审）
 * → ticket → dev bytes → complete ACCEPTED → 返回 assetId（作 voucherFileId）。
 * dev bytes 端点门=franchise:design:use（sales 等岗位代传；基-1 先例）。
 */
async function uploadVoucher(filePath, helperUser) {
  const helper = require('./franchise/engineering'); // 解码与工程凭据同一套；complete 校验的是文件字节
  const fsData = await readFileBase64(filePath);
  const binary = helper.base64ToBinary(fsData);
  const stat = wx.getFileSystemManager().statSync(filePath);
  const sizeBytes = stat.size || binary.length;
  const sha = sha256Hex(binary);
  const ticket = await http.post('/franchise/assets/upload-tickets',
    { assetType: 'FIN_VOUCHER', mimeType: guessMime(filePath), sizeBytes, sha256: sha });
  const assetId = ticket.assetId;
  if (ticket.uploadUrl && !/^local:\/\//.test(ticket.uploadUrl) && String(ticket.uploadUrl).indexOf('/platform/dev/') < 0) {
    await new Promise((resolve, reject) => {
      wx.request({
        url: ticket.uploadUrl, method: 'PUT',
        header: { 'content-type': guessMime(filePath) },
        data: wx.base64ToArrayBuffer(fsData),
        success: r => (r.statusCode >= 200 && r.statusCode < 300 ? resolve() : reject(new Error('凭证上传失败'))),
        fail: () => reject(new Error('凭证上传网络失败'))
      });
    });
  } else {
    // 开发态 bytes：若有 helperUser 用其 token 代传，否则本身份尝试
    const header = {
      'tenant-id': '1', 'content-type': 'application/json',
      Authorization: 'Bearer ' + (helperUser && helperUser.token ? helperUser.token : http.token())
    };
    if (helperUser && helperUser.post) header['X-Post-Id'] = String(helperUser.post);
    await new Promise((resolve, reject) => {
      wx.request({
        url: http.base() + '/platform/dev/design/assets/' + assetId + '/bytes',
        method: 'POST', data: { contentBase64: fsData }, header,
        success: r => (r.statusCode === 200 ? resolve() : reject(new Error('字节通道失败'))),
        fail: () => reject(new Error('字节通道网络失败'))
      });
    });
  }
  const completed = await http.post('/franchise/assets/' + assetId + '/complete');
  if (completed.uploadStatus !== 'ACCEPTED') throw new Error('凭证未通过校验');
  return assetId;
}

/** sha256（UTF-8 字节序列输入→hex 小写；纯 JS 直白 FIPS 180-4 实现，无高尔夫压缩——原转录版多处错误）。
 * M08-T03：以 NIST 标准向量（abc/空串/长串）验证。调用方先 encodeURIComponent 转 UTF-8 字节序列。 */
function sha256Hex(ascii) {
  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];
  var H = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  ];
  var rotr = function (x, n) { return (x >>> n) | (x << (32 - n)); };
  var msg = [];
  var i;
  for (i = 0; i < ascii.length; i++) {
    var code = ascii.charCodeAt(i);
    if (code > 255) return '';
    msg.push(code);
  }
  var bitLen = msg.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  for (i = 7; i >= 0; i--) msg.push((bitLen / Math.pow(2, i * 8)) & 0xff);

  for (var block = 0; block < msg.length / 64; block++) {
    var w = [];
    for (i = 0; i < 16; i++) {
      w[i] = (msg[block * 64 + i * 4] << 24) | (msg[block * 64 + i * 4 + 1] << 16)
           | (msg[block * 64 + i * 4 + 2] << 8) | msg[block * 64 + i * 4 + 3];
    }
    for (i = 16; i < 64; i++) {
      var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
    for (i = 0; i < 64; i++) {
      var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      var ch = (e & f) ^ (~e & g);
      var temp1 = (h + S1 + ch + K[i] + w[i]) | 0;
      var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      var maj = (a & b) ^ (a & c) ^ (b & c);
      var temp2 = (S0 + maj) | 0;
      h = g; g = f; f = e;
      e = (d + temp1) | 0;
      d = c; c = b; b = a;
      a = (temp1 + temp2) | 0;
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
  }
  var out = '';
  for (i = 0; i < 8; i++) {
    out += ((H[i] >>> 28) & 0xf).toString(16) + ((H[i] >>> 24) & 0xf).toString(16)
         + ((H[i] >>> 20) & 0xf).toString(16) + ((H[i] >>> 16) & 0xf).toString(16)
         + ((H[i] >>> 12) & 0xf).toString(16) + ((H[i] >>> 8) & 0xf).toString(16)
         + ((H[i] >>> 4) & 0xf).toString(16) + (H[i] & 0xf).toString(16);
  }
  return out;
}

function guessMime(filePath) {
  const lower = String(filePath).toLowerCase();
  if (lower.endsWith('.pdf')) return 'application/pdf';
  if (lower.endsWith('.png')) return 'image/png';
  return 'image/jpeg';
}

/** 收款修订（财务直接改+全留痕；baseRevisionVersion 乐观锁；business 来源/低于退款下限后端拒绝） */
function reviseReceipt(receiptId, baseRevisionVersion, newAmountCents, note, idemKey) {
  return http.post('/franchise/finance/receipts/' + receiptId + '/revision',
    { baseRevisionVersion, newAmountCents, note }, { 'Idempotency-Key': idemKey || ('fin-revision-' + receiptId + '-' + Date.now()) });
}

/** 修订历史（审计留痕） */
function revisions(receiptId) {
  return http.get('/franchise/finance/revisions', { receiptId });
}

module.exports = {
  idemKey, receipts, payments, payables, refunds,
  registerReceipt, registerPayment, registerRefund, registerExpenseRefund, registerPayable,
  voidRefund, reconciliation,
  confirmBank, bankFlows, projectAccount,
  uploadVoucher, reviseReceipt, revisions, sha256Hex, readFileBase64, guessMime
};
