const http = require('../api/http');
const Finance = require('../api/finance');
const Upload = require('./b-asset-upload');

async function upload(file, assetType, alive) {
  const requireAlive = () => { if (!alive()) throw Error('身份或页面已变化，请重新选择文件'); };
  if (!['DESIGN_DELIVERABLE', 'CONTRACT_ATTACHMENT'].includes(assetType)) throw Error('附件用途不正确');
  if (Number(file.size) > 20 * 1024 * 1024) throw Error('文件不能超过20MB');
  if (!/\.(pdf|png|jpe?g)$/i.test(file.name || file.path)) throw Error('请上传 PDF、PNG 或 JPG');
  const base64 = await Finance.readFileBase64(file.path);
  requireAlive();
  const bytes = wx.base64ToArrayBuffer(base64);
  // MIME 以字节魔数为准；后缀合法但内容不是 PDF/JPG/PNG（如改名文件）在此直接拒绝
  const mimeType = Finance.voucherMime(file.name || file.path, bytes);
  if (!bytes.byteLength || bytes.byteLength > 20 * 1024 * 1024) throw Error('文件须大于0且不超过20MB');
  const ticket = await http.post('/franchise/assets/upload-tickets', { assetType, mimeType, sizeBytes: bytes.byteLength, sha256: Finance.sha256Hex(bytes) });
  requireAlive();
  await Upload.putTicketBytes({ http, uploadUrl: ticket.uploadUrl, bytes, mimeType, alive, label: '委托附件' });
  requireAlive();
  const completed = await http.post('/franchise/assets/' + ticket.assetId + '/complete');
  requireAlive();
  if (!completed || completed.uploadStatus !== 'ACCEPTED') throw Error('附件校验未通过，请重新选择');
  return { assetId: String(ticket.assetId), name: file.name, mimeType, size: bytes.byteLength };
}
module.exports = { upload };
