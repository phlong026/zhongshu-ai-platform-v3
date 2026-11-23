const http = require('../api/http');
const api = require('../api/franchise/content');

async function upload(filePath, alive, options = {}) {
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  const stage = value => { if (alive() && options.onStage) options.onStage(value); };
  stage('reading');
  const bytes = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({
    filePath, success: result => resolve(result.data), fail: () => reject(Error('素材读取失败，请重新选择'))
  }));
  if (!bytes.byteLength || bytes.byteLength > 50 * 1024 * 1024) throw Error('素材大小需在 50MB 以内');
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  const mimeType = require('./media-formats').mimeType(filePath, bytes, options);
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
  stage('uploading');
  await new Promise((resolve, reject) => wx.request({
    url, method: 'PUT', header, data: bytes, timeout: 60000,
    success: result => {
      if (result.statusCode < 200 || result.statusCode >= 300 ||
          (proxy && (!result.data || result.data.code !== 0))) return reject(Error('素材上传失败，请重试'));
      resolve();
    }, fail: () => reject(Error('素材上传网络失败，请重试'))
  }));
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  stage('processing');
  const completed = await complete(ticket.assetId, alive);
  if (!alive()) throw Error('工作身份已变化，请重新打开页面');
  if (!completed || completed.status !== 'ACCEPTED') throw Error('素材尚未通过处理，请稍后重试');
  stage('complete');
  return String(ticket.assetId);
}

async function complete(assetId, alive) {
  let networkRetries = 0;
  for (let attempt = 0; attempt < 60; attempt++) {
    if (!alive()) throw Error('工作身份已变化，请重新打开页面');
    try {
      const result = await api.assets.complete(assetId);
      if (result?.status === 'ACCEPTED') return result;
      if (result?.status === 'REJECTED') throw Error(result.message || result.reason || '素材无法解码或已损坏，请重新选择');
      if (!['PENDING', 'VALIDATING'].includes(result?.status)) throw Error('素材处理结果无效，请重试');
    } catch (error) {
      const transient = error && (error.code === -1 || error.code >= 500 && error.code < 600);
      if (!transient || ++networkRetries > 2 || !alive()) throw error;
    }
    // Completion is idempotent: a lost response resumes this asset, never uploads another copy.
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw Error('素材仍在处理中，请稍后重试');
}

async function source(assetId, alive, {type='image'}={}) {
  if(!alive())throw Error('工作身份已变化，请重新打开页面');
  const ticket=await api.assets.downloadUrl(String(assetId));
  if(!alive())throw Error('工作身份已变化，请重新打开页面');
  if(!ticket?.downloadUrl)throw Error('素材不可用，请重新选择');
  // Audio/video references need authorization, not another local copy just to show their names.
  if(type!=='image')return '';
  // The local selection can be HEIC or another source format; preview the accepted rendition.
  if(/^https:\/\//i.test(ticket.downloadUrl)&&!String(ticket.downloadUrl).startsWith(String(http.base()).replace(/\/admin-api\/?$/,'')+'/'))return ticket.downloadUrl;
  return (await require('./b-asset-download').downloadTicketFile({http,downloadUrl:ticket.downloadUrl,alive,label:'素材'})).tempFilePath;
}

module.exports = { upload, source };
