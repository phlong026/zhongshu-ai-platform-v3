const api = require('../api/franchise/content');
const http = require('../api/http');
const finance = require('../api/finance');

/** 内容素材仅传原始字节；B代理请求带会话，COS直传不附带平台令牌。 */
module.exports = async function upload(path, mimeType, alive = () => true, options = {}) {
  const ensure = () => { if (!alive()) throw { cancelled: true }; };
  const bytes = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({ filePath: path, success: r => resolve(r.data), fail: reject }));
  ensure();
  const contractMax = (mimeType === 'audio/mpeg' ? 20 : 50) * 1024 * 1024;
  const maxBytes = Math.min(Number(options.maxBytes) || contractMax, contractMax);
  if (!bytes.byteLength || bytes.byteLength > maxBytes) throw Error('素材大小须在' + Math.floor(maxBytes / 1024 / 1024) + 'MB以内');
  const ticket = await api.assets.uploadTicket({ mimeType, sizeBytes: bytes.byteLength, sha256: finance.sha256Hex(bytes) });
  ensure();
  let url = String(ticket.uploadUrl || '');
  const header = { 'Content-Type': mimeType };
  if (/^\/admin-api\/franchise\/assets\/\d+\/content\?token=/.test(url)) {
    const origin = (http.base().match(/^https?:\/\/[^/]+/) || [])[0];
    if (!origin) throw Error('素材上传通道不可用');
    url = origin + url;
    header.Authorization = 'Bearer ' + http.token(); header['X-Post-Id'] = http.currentPostId(); header['tenant-id'] = http.tenantId();
  } else if (!/^https:\/\//.test(url)) throw Error('素材上传通道不可用');
  await new Promise((resolve, reject) => wx.request({ url, method: 'PUT', data: bytes, header, timeout: 60000,
    success: r => r.statusCode >= 200 && r.statusCode < 300 && !(r.data && r.data.code) ? resolve() : reject(Error('素材上传失败，请重试')),
    fail: reject }));
  ensure();
  const result = await api.assets.complete(ticket.assetId);
  ensure();
  if (!result || result.status !== 'ACCEPTED') throw Error('素材尚未通过校验，请重新上传');
  return String(ticket.assetId);
};
