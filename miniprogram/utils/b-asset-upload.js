/** 独立 B 资产原始字节 PUT：仅接受 HTTPS 签名 URL 或 B 自有认证代理。 */
function uploadError(message) {
  const error = new Error(message);
  error.msg = message;
  return error;
}

function requireCurrentSession(alive) {
  if (typeof alive === 'function' && !alive()) throw uploadError('身份已切换，请重新选择文件');
}

function responseBody(data) {
  if (!data || typeof data !== 'string') return data;
  try { return JSON.parse(data); } catch (e) { return data; }
}

function putTicketBytes(options) {
  const opts = options || {};
  const http = opts.http;
  const rawUrl = String(opts.uploadUrl || '');
  const label = opts.label || '文件';
  requireCurrentSession(opts.alive);

  const oldPrivate = /\/(?:platform\/dev\/design\/assets|app-api\/design\/v1\/assets)\//.test(rawUrl);
  const cos = /^https:\/\//i.test(rawUrl);
  const proxy = /^\/admin-api\/franchise\/assets\/\d+\/content\?token=[^&#]+$/.test(rawUrl);
  if (oldPrivate || (!cos && !proxy)) {
    return Promise.reject(uploadError('当前环境未配置可用的 B 素材上传通道'));
  }

  let url = rawUrl;
  const header = { 'content-type': opts.mimeType || 'application/octet-stream' };
  if (proxy) {
    const origin = (String(http && http.base ? http.base() : '').match(/^https?:\/\/[^/]+/) || [])[0];
    if (!origin) return Promise.reject(uploadError('当前环境未配置可用的 B 素材上传通道'));
    url = origin + rawUrl;
    const token = http.token();
    const postId = http.currentPostId();
    if (token) header.Authorization = 'Bearer ' + token;
    if (postId) header['X-Post-Id'] = postId;
    header['tenant-id'] = http.tenantId();
  }
  requireCurrentSession(opts.alive);

  return new Promise((resolve, reject) => {
    wx.request({
      url,
      method: 'PUT',
      data: opts.bytes,
      timeout: 15000,
      header,
      success(response) {
        try { requireCurrentSession(opts.alive); } catch (error) { reject(error); return; }
        const body = responseBody(response && response.data);
        const httpOk = response && response.statusCode >= 200 && response.statusCode < 300;
        const proxyOk = body && typeof body === 'object' && body.code === 0;
        if (httpOk && (!proxy || proxyOk)) {
          resolve();
          return;
        }
        if (proxy && httpOk && (!body || typeof body !== 'object' || body.code === undefined)) {
          reject(uploadError(label + '上传失败：B 代理响应无效'));
          return;
        }
        reject(uploadError((body && body.msg) || label + '上传失败（HTTP ' + ((response && response.statusCode) || 0) + '）'));
      },
      fail(error) {
        try { requireCurrentSession(opts.alive); } catch (stale) { reject(stale); return; }
        reject(uploadError((error && error.errMsg) || label + '上传网络失败，请重试'));
      }
    });
  });
}

module.exports = { putTicketBytes };
