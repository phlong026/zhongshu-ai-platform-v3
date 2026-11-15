/** B 短票据代理带当前身份；外部 HTTPS 签名地址不携带平台凭据。 */
function downloadError(message) {
  const error = new Error(message);
  error.msg = message;
  return error;
}

function requireSession(alive) {
  if (typeof alive === 'function' && !alive()) throw downloadError('身份已切换，请重新查看文件');
}

function responsePrefix(path) {
  try {
    const fs = wx.getFileSystemManager();
    const size = fs.statSync(path).size;
    return fs.readFileSync(path, 'utf8', 0, Math.min(size, 4096)).trim();
  } catch (error) {
    throw downloadError('无法读取下载文件，请重试');
  }
}

async function downloadTicketFile(options) {
  const opts = options || {};
  requireSession(opts.alive);
  const rawUrl = String(opts.downloadUrl || '');
  const http = opts.http;
  const origin = (String(http && http.base ? http.base() : '').match(/^https?:\/\/[^/]+/) || [])[0];
  const path = origin && rawUrl.startsWith(origin + '/') ? rawUrl.slice(origin.length) : rawUrl;
  const proxy = /^\/admin-api\/franchise\/assets\/\d+\/content\?token=[^&#]+$/.test(path);
  const legacy = /\/(?:platform\/dev\/design\/assets|app-api\/design\/v1\/assets)\//.test(rawUrl);
  if (legacy || (proxy && !origin) || (!proxy && (!/^https:\/\//i.test(rawUrl) || rawUrl.startsWith(origin + '/')))) {
    throw downloadError('当前环境未配置可用的 B 文件下载通道');
  }
  const header = {};
  if (proxy) {
    const token = http.token();
    const postId = http.currentPostId();
    if (!token || !postId) throw downloadError('请先登录并选择当前岗位');
    header.Authorization = 'Bearer ' + token;
    header['X-Post-Id'] = postId;
    header['tenant-id'] = http.tenantId();
  }
  return new Promise((resolve, reject) => {
    let contentType = '';
    const task = wx.downloadFile({
      url: proxy ? origin + path : rawUrl,
      header,
      timeout: 15000,
      success(response) {
        try {
          requireSession(opts.alive);
          if (!response || response.statusCode !== 200 || !response.tempFilePath) {
            throw downloadError((opts.label || '文件') + '下载失败（HTTP ' + ((response && response.statusCode) || 0) + '）');
          }
          const prefix = responsePrefix(response.tempFilePath);
          if (/json/i.test(contentType) || /^[\[{]/.test(prefix)) {
            let body;
            try { body = JSON.parse(prefix); } catch (error) { /* 错误响应可超过前缀上限。 */ }
            throw downloadError(body && body.msg || '服务端未返回可查看的文件，请重试');
          }
          requireSession(opts.alive);
          resolve({tempFilePath:response.tempFilePath, statusCode:response.statusCode, contentType});
        } catch (error) { reject(error); }
      },
      fail(error) {
        try { requireSession(opts.alive); } catch (stale) { reject(stale); return; }
        reject(downloadError(error && error.errMsg || '文件下载失败，请重试'));
      }
    });
    if (task && typeof task.onHeadersReceived === 'function') {
      task.onHeadersReceived(response => {
        const headers = response && response.header || {};
        const key = Object.keys(headers).find(name => name.toLowerCase() === 'content-type');
        contentType = key ? String(headers[key]) : '';
      });
    }
  });
}

module.exports = {downloadTicketFile};
