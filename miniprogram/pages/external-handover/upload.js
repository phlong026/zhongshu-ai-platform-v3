const http = require('../../api/http');
const files = require('../../api/finance');
const { putTicketBytes } = require('../../utils/b-asset-upload');
const { id } = require('./model');
const MAX_BYTES = 30 * 1024 * 1024;
async function run(pending, alive) {
  const check = () => { if (!alive()) throw Error('工作身份已变化，请重新选择客户确认凭据'); };
  check();
  if (pending.rejected) throw Error('文件验收未通过，请重新选择');
  if (!pending.bytes) {
    const path = pending.file.path || pending.file.tempFilePath;
    if (!path || Number(pending.file.size) > MAX_BYTES) throw Error('请选择不超过30MB的 PDF、JPG 或 PNG');
    const encoded = await files.readFileBase64(path); check();
    const bytes = wx.base64ToArrayBuffer(encoded);
    if (!bytes.byteLength || bytes.byteLength > MAX_BYTES) throw Error('文件须大于0且不超过30MB');
    pending.mimeType = files.voucherMime(pending.file.name || path, bytes); pending.bytes = bytes;
  }
  if (!pending.ticket) {
    const ticket = await http.post('/franchise/assets/upload-tickets', { assetType: 'BUSINESS_DOCUMENT', mimeType: pending.mimeType,
      sizeBytes: pending.bytes.byteLength, sha256: files.sha256Hex(pending.bytes) }); check();
    if (!id(ticket?.assetId)) throw Error('未取得有效上传凭据，请重试');
    pending.ticket = ticket;
  }
  if (!pending.putAttempted) {
    pending.putAttempted = true;
    try {
      await putTicketBytes({ http, uploadUrl: pending.ticket.uploadUrl, bytes: pending.bytes, mimeType: pending.mimeType, alive, label: '客户确认凭据' });
      check(); pending.uploaded = true;
    } catch (error) { check(); /* A lost PUT response may already have consumed the one-use ticket. Confirm before retrying. */ }
  }
  let result;
  try { result = await http.post('/franchise/assets/' + encodeURIComponent(pending.ticket.assetId) + '/complete'); check(); }
  catch (error) {
    check();
    if (Number(error?.code) === 1071000001 && /尚未上传完成/.test(String(error.msg || error.message || ''))) pending.restartRequired = true;
    throw error;
  }
  if (result?.uploadStatus !== 'ACCEPTED') {
    pending.rejected = result?.uploadStatus === 'REJECTED';
    throw Error(pending.rejected ? '客户确认凭据验收未通过，请重新选择' : '客户确认凭据正在验收，请稍后重试核对');
  }
  return { assetId: id(pending.ticket.assetId), fileName: String(pending.file.name || '现场照片').slice(0, 180), mimeType: pending.mimeType };
}
module.exports = { run };
