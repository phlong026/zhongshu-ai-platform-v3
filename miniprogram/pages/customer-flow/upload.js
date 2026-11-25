const http = require('../../api/http');
const finance = require('../../api/finance');
const upload = require('../../utils/b-asset-upload');

// Keep the accepted ticket on the page so an interrupted completion retries the same asset.
async function run(pending, alive) {
  const check = () => { if (!alive()) throw Error('工作身份已变化，请重新选择资料'); };
  check();
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
  if (!pending.uploaded) {
    await upload.putTicketBytes({ http, uploadUrl: pending.ticket.uploadUrl, bytes: pending.bytes, mimeType: pending.mimeType, alive, label: '节点资料' });
    check(); pending.uploaded = true;
  }
  const accepted = await http.post('/franchise/assets/' + pending.ticket.assetId + '/complete');
  check();
  if (!accepted || accepted.uploadStatus !== 'ACCEPTED') throw Error('资料校验未通过，请重新选择');
  return { assetId: String(pending.ticket.assetId), title: String(pending.file.name || '现场照片'), mimeType: pending.mimeType };
}
module.exports = { run };
