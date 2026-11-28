/**
 * 财务账簿访问层（B1 接入）：finance-desk 工作台对接 /franchise/finance/**。
 * 写操作自动携带 Idempotency-Key（键由调用方生成传入，同键同内容安全重试）。
 */
const http = require('./http');
const BAsset = require('../utils/b-asset-upload');

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

/** 作废付款（P1 批6；仅持 franchise:fin:payment-void 的岗位=finance-lead；body.reason 留痕；
 * 后端按「已作废」幂等、存在有效支出退回时报 1102000037；DELETE 端点不读 Idempotency-Key，
 * 头按既有写操作惯例携带，不构成幂等承诺。 */
function voidPayment(id, reason, idemKey) {
  return http.del('/franchise/finance/payments/' + id, { reason },
    { 'Idempotency-Key': idemKey || genIdemKey('payment-void') });
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
function originalDesignFees(commissionId, submissionVersion) {
  return http.get('/franchise/finance/design-fees/original', { commissionId, submissionVersion });
}
function designFees(pageNo = 1, pageSize = 20) {
  return http.get('/franchise/finance/design-fees', { pageNo, pageSize });
}
function projectLedger(projectId, pageNo = 1, pageSize = 20) {
  return http.get('/franchise/finance/projects/' + projectId + '/ledger', { pageNo, pageSize });
}
function projects(pageNo = 1, pageSize = 20, keyword = '') {
  return http.get('/franchise/finance/projects', { pageNo, pageSize, keyword });
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
 * → ticket → B 代理/COS 原始字节 PUT → complete ACCEPTED → 返回 assetId（作 voucherFileId）。
 */
async function uploadVoucher(filePath, fileName) {
  const alive = http.sessionGuard();
  const requireAlive = () => { if (!alive()) throw new Error('身份已切换，请重新选择文件'); };
  const fsData = await readFileBase64(filePath);
  requireAlive();
  const bytes = wx.base64ToArrayBuffer(fsData);
  const stat = wx.getFileSystemManager().statSync(filePath);
  const sizeBytes = stat.size || bytes.byteLength;
  const sha = sha256Hex(bytes);
  const mimeType = voucherMime(fileName || filePath, bytes);
  const ticket = await http.post('/franchise/assets/upload-tickets',
    { assetType: 'FIN_VOUCHER', mimeType, sizeBytes, sha256: sha });
  requireAlive();
  const assetId = ticket.assetId;
  await BAsset.putTicketBytes({ http, uploadUrl: ticket.uploadUrl, bytes,
    mimeType, alive, label: '凭证' });
  requireAlive();
  const completed = await http.post('/franchise/assets/' + assetId + '/complete');
  requireAlive();
  if (!completed || completed.uploadStatus !== 'ACCEPTED') {
    throw new Error('凭证未通过校验：' + voucherRejectText(completed && completed.rejectedReason));
  }
  return assetId;
}

/** sha256（二进制字符串、ArrayBuffer 或字节视图 → hex 小写；FIPS 180-4）。
 * 字符串调用方需先转 UTF-8 字节；文件上传直接传 readFile 返回的 ArrayBuffer。 */
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
  var binaryString = typeof ascii === 'string';
  var bytes = null;
  if (!binaryString) {
    if (ArrayBuffer.isView(ascii)) bytes = new Uint8Array(ascii.buffer, ascii.byteOffset, ascii.byteLength);
    else {
      try {
        // 原生品牌检查兼容跨环境ArrayBuffer。
        ArrayBuffer.prototype.slice.call(ascii, 0, 0);
        bytes = new Uint8Array(ascii);
      } catch (error) { /* 下方统一报告非法输入 */ }
    }
  }
  if (!binaryString && !bytes) throw new TypeError('SHA-256 需要二进制字节');
  var length = binaryString ? ascii.length : bytes.byteLength;
  // 有界字节数组避免大文件展开成 JavaScript 数字数组。
  var msg = new Uint8Array(Math.ceil((length + 9) / 64) * 64);
  var i;
  if (bytes) msg.set(bytes);
  else for (i = 0; i < length; i++) {
    var code = ascii.charCodeAt(i);
    if (code > 255) return '';
    msg[i] = code;
  }
  var bitLen = length * 8;
  msg[length] = 0x80;
  for (i = 0; i < 8; i++) msg[msg.length - 1 - i] = (bitLen / Math.pow(2, i * 8)) & 0xff;

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

/**
 * 凭据 MIME 嗅探：以字节魔数为准（2026-11-24 现场验收修复）。
 *
 * 微信 saveFile / 相册导出可能把图片重编码为 JPEG，却把临时路径后缀留成 .png；
 * 只按后缀猜 MIME 会让票据声明 png、实际字节 jpeg，complete 阶段被后端
 * AssetContentScanner 以 MAGIC_NUMBER 拒收（声明与实际不一致）。
 * 接受二进制字符串（base64ToBinary 的产物）或 ArrayBuffer / 字节视图。
 */
function sniffMime(bytes) {
  if (bytes == null) return '';
  // 统一成可按索引取字节的视图：二进制字符串直接取 charCode，
  // DataView 等非 Uint8Array 视图按 byteOffset/byteLength 重新包一层（DataView 不支持下标访问）。
  const view = typeof bytes === 'string' ? null
    : bytes instanceof Uint8Array ? bytes
    : ArrayBuffer.isView(bytes) ? new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    : new Uint8Array(bytes);
  const at = view ? i => view[i] : i => bytes.charCodeAt(i);
  const text = (start, length) => {
    let out = '';
    for (let i = 0; i < length; i++) out += String.fromCharCode(at(start + i));
    return out;
  };
  if (at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return 'image/jpeg';
  if (at(0) === 0x89 && text(1, 3) === 'PNG') return 'image/png';
  if (text(0, 5) === '%PDF-') return 'application/pdf';
  return '';
}

/** 凭据链路只接收 JPEG / PNG / PDF：字节可识别时字节优先，读不到字节才回落后缀。 */
function guessMime(filePath, bytes) {
  const actual = sniffMime(bytes);
  if (actual) return actual;
  const lower = String(filePath || '').toLowerCase();
  if (lower.endsWith('.pdf')) return 'application/pdf';
  if (lower.endsWith('.png')) return 'image/png';
  return 'image/jpeg';
}

const REJECT_FORMAT_LABELS = {
  'image/jpeg': 'JPG 图片', 'image/png': 'PNG 图片', 'application/pdf': 'PDF 文件',
  'image/gif': 'GIF 图片', 'image/bmp': 'BMP 图片', 'image/tiff': 'TIFF 图片'
};

const VOUCHER_FORMAT_HINT = '请上传 JPG、PNG 图片或 PDF 文件（HEIC、WebP、GIF 请先另存为 JPG）';
const VOUCHER_IMAGE_HINT = '请上传 JPG 或 PNG 图片（HEIC、WebP、GIF 请先另存为 JPG）';

/**
 * 凭据 MIME 定稿 + 前置拒绝（2026-11-24 加固）：字节可识别即采用；识别不出时只有
 * PDF 后缀（allowPdf 时）继续走票据，其余格式（HEIC/WebP/GIF/BMP…）在本地直接拒绝。
 * 这样不再"传上去才被后端 MAGIC_NUMBER 拒"，也不会把"实际 null"这种模糊结论给到一线。
 */
function voucherMime(filePath, bytes, options) {
  const allowPdf = !(options && options.allowPdf === false);
  const actual = sniffMime(bytes);
  if (actual) return actual;
  if (allowPdf && /\.pdf$/i.test(String(filePath || ''))) return 'application/pdf';
  const text = allowPdf ? VOUCHER_FORMAT_HINT : VOUCHER_IMAGE_HINT;
  const error = new Error(text);
  error.code = 0;
  error.msg = text;
  throw error;
}

/** 后端扫描器给的是 "MAGIC_NUMBER: 声明 image/png 实际 image/jpeg" 这类机器码，转成一线能看懂的话。 */
function voucherRejectText(reason) {
  const text = String(reason == null ? '' : reason).trim();
  if (!text) return '文件未通过校验，请更换后重试';
  const magic = text.match(/MAGIC_NUMBER:\s*声明\s*([\w/+.-]+)\s*实际\s*([\w/+.-]+)/);
  if (magic) {
    const actual = REJECT_FORMAT_LABELS[magic[2]];
    if (actual) return '这个文件实际是 ' + actual + '，和文件名后缀不是同一种格式。请直接用手机相机重拍，或把图片另存为标准 JPG 后再选择';
    return '这个文件的实际格式无法识别（可能已损坏或被改过名），请更换文件后重试';
  }
  if (text.indexOf('PIXEL_GUARD') >= 0) return '图片尺寸超出上限，请压缩后再选择';
  if (text.indexOf('DECODE') >= 0) return '文件已损坏或无法解析，请重新选择';
  if (text.indexOf('MALWARE') >= 0) return '文件未通过安全检测，请更换文件';
  return text;
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

function contractReceiptCorrection(receiptId) {
  return http.get('/franchise/sales/receipts/'+encodeURIComponent(receiptId)+'/correction');
}
function correctContractReceipt(body, key) {
  const {receiptId,...change}=body;
  return http.post('/franchise/sales/receipts/'+encodeURIComponent(receiptId)+'/correction',change,{'Idempotency-Key':key});
}

module.exports = {
  idemKey, receipts, payments, payables, refunds,
  registerReceipt, registerPayment, registerRefund, registerExpenseRefund, registerPayable,
  voidRefund, reconciliation,
  voidPayment,
  confirmBank, bankFlows, projectAccount, designFees, originalDesignFees, projectLedger, projects,
  uploadVoucher, reviseReceipt, revisions, contractReceiptCorrection, correctContractReceipt, sha256Hex, readFileBase64, guessMime, sniffMime, voucherMime, voucherRejectText
};
