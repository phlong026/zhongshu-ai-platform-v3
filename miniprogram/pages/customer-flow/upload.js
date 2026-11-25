const http = require('../../api/http');
const finance = require('../../api/finance');
const upload = require('../../utils/b-asset-upload');

// Confirm the original asset after an uncertain PUT; proxy upload tickets can only be consumed once.
async function run(pending, alive) {
  const check = () => { if (!alive()) throw Error('工作身份已变化，请重新选择资料'); };
  check();
  if (pending.rejected) throw Error('资料验收未通过，请重新选择');
  if (!pending.bytes) {
    const path = pending.file.path || pending.file.tempFilePath;
    if (!path || Number(pending.file.size) > 30 * 1024 * 1024) throw Error('请选择不超过30MB的 PDF、JPG 或 PNG');
    const encoded = await finance.readFileBase64(path);
    check();
    const bytes = wx.base64ToArrayBuffer(encoded);
    if (!bytes.byteLength || bytes.byteLength > 30 * 1024 * 1024) throw Error('文件须大于0且不超过30MB');
    const mimeType = finance.voucherMime(pending.file.name || path, bytes);
    pending.bytes = bytes; pending.mimeType = mimeType;
  }
  if (!pending.ticket) {
    const ticket = await http.post('/franchise/assets/upload-tickets', { assetType: 'BUSINESS_DOCUMENT', mimeType: pending.mimeType,
      sizeBytes: pending.bytes.byteLength, sha256: finance.sha256Hex(pending.bytes) });
    check();
    if (!ticket || !ticket.assetId) throw Error('未取得上传凭据，请重试');
    pending.ticket = ticket;
  }
  if (!pending.putAttempted) {
    pending.putAttempted = true;
    try {
      await upload.putTicketBytes({ http, uploadUrl: pending.ticket.uploadUrl, bytes: pending.bytes, mimeType: pending.mimeType, alive, label: '节点资料' });
      check(); pending.uploaded = true;
    } catch (error) { check(); }
  }
  let accepted;
  try { accepted = await http.post('/franchise/assets/' + pending.ticket.assetId + '/complete'); check(); }
  catch (error) {
    check();
    if (Number(error?.code) === 1071000001 && /尚未上传完成/.test(String(error.msg || error.message || ''))) pending.restartRequired = true;
    throw error;
  }
  if (!accepted || accepted.uploadStatus !== 'ACCEPTED') {
    pending.rejected = accepted?.uploadStatus === 'REJECTED';
    throw Error(pending.rejected ? '资料验收未通过，请重新选择' : '资料正在验收，请稍后重试核对');
  }
  return { assetId: String(pending.ticket.assetId), title: String(pending.file.name || '现场照片'), mimeType: pending.mimeType };
}
module.exports = { run };
