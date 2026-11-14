const http = require('../api/http');
const api = require('../api/franchise/content');

const mimeTypes = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', mp4: 'video/mp4', mp3: 'audio/mpeg' };

async function upload(filePath, alive) {
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  const extension = String(filePath).split('?')[0].split('.').pop().toLowerCase();
  const mimeType = mimeTypes[extension];
  if (!mimeType) throw Error('素材暂仅支持 JPG、PNG、MP4、MP3，请重新选择');
  const bytes = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({
    filePath, success: result => resolve(result.data), fail: () => reject(Error('素材读取失败，请重新选择'))
  }));
  if (!bytes.byteLength || bytes.byteLength > 50 * 1024 * 1024) throw Error('素材大小需在 50MB 以内');
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  const sha256 = require('../api/finance').sha256Hex(bytes);
  const ticket = await api.assets.uploadTicket({ mimeType, sizeBytes: bytes.byteLength, sha256 });
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  if (!ticket || !ticket.assetId || !ticket.uploadUrl) throw Error('素材上传票据无效');
  let url = String(ticket.uploadUrl);
  const header = { 'content-type': mimeType };
  // B 的本地字节代理需要平台会话；签名 COS 地址只发送文件，不携带平台凭据。
  const proxyPath = '/admin-api/franchise/assets/' + ticket.assetId + '/content?';
  const proxy = url.startsWith(proxyPath);
  if (proxy) {
    const base = http.base();
    if (!base.endsWith('/admin-api')) throw Error('平台上传地址配置无效');
    url = base.slice(0, -'/admin-api'.length) + url;
    header.Authorization = 'Bearer ' + http.token();
    header['X-Post-Id'] = http.currentPostId();
    header['tenant-id'] = http.tenantId();
  } else if (!url.startsWith('https://')) throw Error('素材上传通道尚未配置');
  await new Promise((resolve, reject) => wx.request({
    url, method: 'PUT', header, data: bytes, timeout: 60000,
    success: result => {
      if (result.statusCode < 200 || result.statusCode >= 300 ||
          (proxy && (!result.data || result.data.code !== 0))) return reject(Error('素材上传失败，请重试'));
      resolve();
    }, fail: () => reject(Error('素材上传网络失败，请重试'))
  }));
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  const completed = await api.assets.complete(ticket.assetId);
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  if (!completed || completed.status !== 'ACCEPTED') throw Error('素材尚未通过处理，请稍后重试');
  return String(ticket.assetId);
}

module.exports = { upload };
