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
  return http.post('/franchise/finance/receipts', body, { 'Idempotency-Key': idemKey || idemKey('receipt') });
}

/** 登记付款（payableId 必填） */
function registerPayment(body, idemKey) {
  return http.post('/franchise/finance/payments', body, { 'Idempotency-Key': idemKey || idemKey('payment') });
}

/** 登记退款（线下事实登记制：登记即已核实） */
function registerRefund(body, idemKey) {
  return http.post('/franchise/finance/refunds', body, { 'Idempotency-Key': idemKey || idemKey('refund') });
}

/** 登记应付 */
function registerPayable(body, idemKey) {
  return http.post('/franchise/finance/payables', body, { 'Idempotency-Key': idemKey || idemKey('payable') });
}

/** 到账确认（唯一入账；幂等重放返回首次 confirmId） */
function confirmBank(bankFlowId, receiptId, idemKey) {
  return http.post('/franchise/finance/bank-confirms', { bankFlowId, receiptId },
    { 'Idempotency-Key': idemKey || idemKey('confirm') });
}

/** 银行流水列表（待匹配在前） */
function bankFlows() {
  return http.get('/franchise/finance/bank-flows', { status: '待匹配' });
}

/** 项目账聚合 */
function projectAccount(projectId) {
  return http.get('/franchise/finance/project-accounts/' + projectId);
}

module.exports = {
  idemKey, receipts, payments, payables, refunds,
  registerReceipt, registerPayment, registerRefund, registerPayable,
  confirmBank, bankFlows, projectAccount
};

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
  const helper = require('./franchise/engineering'); // 仅复用 assets 端点（上传/完成）
  const fsData = await readFileBase64(filePath);
  const stat = wx.getFileSystemManager().statSync(filePath);
  const sizeBytes = stat.size || fsData.length;
  // sha256 由后端 dev bytes 通道之外另行校验，此处按基-1 惯例传内容哈希（简单 hash 不行——传 sha256）
  // sha256 行内实现（后端按 sha 幂等去重票据；内容变化即新票据）
  const sha = sha256Hex(unescape(encodeURIComponent(fsData)));
  const ticket = await http.post('/franchise/assets/upload-tickets',
    { assetType: 'FIN_VOUCHER', mimeType: guessMime(filePath), sizeBytes, sha256: sha });
  const assetId = ticket.assetId;
  // 字节通道：dev 端点需 design:use——若有 helperUser 用其 token 代传，否则本身份尝试
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
  const completed = await http.post('/franchise/assets/' + assetId + '/complete');
  if (completed.uploadStatus !== 'ACCEPTED') throw new Error('凭证未通过校验');
  return assetId;
}

/** sha256（base64 字符串输入→hex；纯 JS 实现避免新依赖） */
function sha256Hex(ascii) {
  function rightRotate(value, amount) { return (value >>> amount) | (value << (32 - amount)); }
  const mathPow = Math.pow, maxWord = mathPow(2, 32);
  let result = '';
  const words = [], asciiBitLength = ascii.length * 8;
  let hash = [], k = [], primeCounter = 0;
  const isComposite = {};
  for (let candidate = 2; primeCounter < 64; candidate++) {
    if (!isComposite[candidate]) {
      for (let i = 0; i < 313; i += candidate) isComposite[i + candidate] = candidate;
      hash[primeCounter] = (mathPow(candidate, 0.5) * maxWord) | 0;
      k[primeCounter++] = (mathPow(candidate, 1 / 3) * maxWord) | 0;
    }
  }
  ascii += '\x80';
  while (ascii.length % 64 - 56) ascii += '\x00';
  for (let i = 0; i < ascii.length; i++) {
    const j = ascii.charCodeAt(i);
    if (j >> 8) return '';
    words[i >> 2] |= j << ((3 - i) % 4) * 8;
  }
  words[words.length] = ((asciiBitLength / maxWord) | 0);
  words[words.length] = (asciiBitLength);
  for (let j = 0; j < words.length;) {
    const w = words.slice(j, j += 16);
    const oldHash = hash;
    hash = hash.slice(0, 8);
    for (let i = 0; i < 64; i++) {
      const w15 = w[i - 15], w2 = w[i - 2];
      const a = hash[0], e = hash[4];
      const temp1 = hash[7]
        + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25))
        + ((e & hash[5]) ^ ((~e) & hash[6])) + k[i]
        + (w[i] = (i < 16) ? w[i] : (w[i - 16] + (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3)) + w[i - 7] + (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))) | 0);
      const temp2 = (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) + ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));
      hash = [(temp2 + temp1) | 0].concat(hash).slice(0, 7);
    }
    for (let i = 0; i < 8; i++) hash[i] = (hash[i] + oldHash[i]) | 0;
  }
  for (let i = 0; i < 8; i++) {
    for (let j = 3; j + 1; j--) {
      const b = (hash[i] >> (j * 8)) & 255;
      result += ((b < 16) ? 0 : '') + b.toString(16);
    }
  }
  return result;
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
    { baseRevisionVersion, newAmountCents, note }, { 'Idempotency-Key': idemKey || idemKey('revision') });
}

/** 修订历史（审计留痕） */
function revisions(receiptId) {
  return http.get('/franchise/finance/revisions', { receiptId });
}

module.exports = {
  idemKey, receipts, payments, payables, refunds,
  registerReceipt, registerPayment, registerRefund, registerPayable,
  confirmBank, bankFlows, projectAccount,
  uploadVoucher, reviseReceipt, revisions
};
