module.exports = function putObject(ticket, bytes, mime) {
  const platform = require('../../api/http'), session = require('./request'), expected = session.captureSession();
  let url = String(ticket.uploadUrl || '');
  const header = { 'Content-Type': mime };
  if (/^\/admin-api\/franchise\/assets\/\d+\/content\?token=/.test(url)) {
    const origin = (platform.base().match(/^https?:\/\/[^/]+/) || [])[0];
    if (!origin) return Promise.reject({ msg: '上传通道未配置' });
    url = origin + url;
    header.Authorization = 'Bearer ' + platform.token(); header['X-Post-Id'] = platform.currentPostId(); header['tenant-id'] = platform.tenantId();
  } else if (!/^https:\/\//.test(url)) return Promise.reject({ msg: '上传通道不可用' });
  return new Promise((resolve, reject) => wx.request({ url, method: 'PUT', data: bytes, header, timeout: 60000,
    success: response => !session.isSameSession(expected) ? reject({ msg: '工作身份已更新' })
      : response.statusCode >= 200 && response.statusCode < 300 && !(response.data && response.data.code) ? resolve() : reject({ msg: '图片上传失败，请重试' }),
    fail: () => reject({ msg: '图片上传失败，请检查网络' }) }));
};
