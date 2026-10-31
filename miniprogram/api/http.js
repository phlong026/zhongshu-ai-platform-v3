/**
 * 后端 HTTP 访问层（M2.7 新增）：wx.request 的 Promise 封装。
 * 与离线模式并存——本文件不改动任何现有 utils，页面按模块逐步改调。
 * 认证：Authorization Bearer token；当前岗位：X-Post-Id 请求头；多租户：tenant-id 请求头（默认 1）。
 */
const TOKEN_KEY = 'zs-api-token-v1';
const POST_KEY = 'zs-api-post-v1';
const TENANT_KEY = 'zs-api-tenant-v1';
const DEFAULT_BASE = 'http://127.0.0.1:48080/admin-api';
const TIMEOUT = 15000;
let unauthorizedHandler = null;
let sessionGen = 0; // 会话代次：saveSession/clearSession 时 +1；401 处理前校验，防旧请求 401 清掉新会话

function base() {
  return (wx.getStorageSync('zs-api-base') || DEFAULT_BASE).replace(/\/$/, '');
}

function token() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function currentPostId() {
  return wx.getStorageSync(POST_KEY) || '';
}

function tenantId() {
  return wx.getStorageSync(TENANT_KEY) || '1';
}

function saveSession(next) {
  if (next.token) wx.setStorageSync(TOKEN_KEY, next.token);
  if (next.postId !== undefined) wx.setStorageSync(POST_KEY, next.postId);
  if (next.tenantId !== undefined) wx.setStorageSync(TENANT_KEY, String(next.tenantId));
  sessionGen += 1;
}

function clearSession() {
  wx.removeStorageSync(TOKEN_KEY);
  wx.removeStorageSync(POST_KEY);
  sessionGen += 1;
}

/** 当前会话代次（页面加载请求据此做有效性校验） */
function sessionGeneration() {
  return sessionGen;
}

/** 401 时的回调（登录页注册，用于引导重新登录）；不设置则只报错 */
function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

function request(method, path, data, extraHeader) {
  const requestSessionGen = sessionGen;
  return new Promise((resolve, reject) => {
    wx.request({
      url: base() + path,
      method,
      data,
      timeout: TIMEOUT,
      header: Object.assign(
        { 'content-type': 'application/json', 'tenant-id': tenantId() },
        token() ? { Authorization: 'Bearer ' + token() } : {},
        currentPostId() ? { 'X-Post-Id': currentPostId() } : {},
        extraHeader || {}
      ),
      success(res) {
        if (res.statusCode === 401) {
          // 仅当请求所属会话仍是当前会话时才清会话并通知；旧会话的 401 静默丢弃
          if (requestSessionGen === sessionGen) {
            clearSession();
            if (unauthorizedHandler) unauthorizedHandler();
            reject({ code: 401, message: '登录已过期，请重新登录' });
          } else {
            reject({ code: 401, silent: true, message: '旧会话请求已忽略' });
          }
          return;
        }
        const body = res.data;
        if (res.statusCode >= 200 && res.statusCode < 300 && body && body.code === 0) {
          resolve(body.data);
          return;
        }
        reject({
          code: body && body.code !== undefined ? body.code : res.statusCode,
          message: (body && body.msg) || ('请求失败（HTTP ' + res.statusCode + '）')
        });
      },
      fail(err) {
        reject({ code: -1, message: (err && err.errMsg) || '网络异常，请稍后重试' });
      }
    });
  });
}

module.exports = {
  TOKEN_KEY,
  POST_KEY,
  base,
  token,
  currentPostId,
  tenantId,
  sessionGeneration,
  saveSession,
  clearSession,
  onUnauthorized,
  get: (path, params) => request('GET', path, params),
  post: (path, data, extraHeader) => request('POST', path, data, extraHeader),
  put: (path, data, extraHeader) => request('PUT', path, data, extraHeader),
  del: (path, data, extraHeader) => request('DELETE', path, data, extraHeader)
};
