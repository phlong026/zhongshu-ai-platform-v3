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
// M02-T01/I05：岗位失效错误码（后端 PlatformAuthzErrorCodes.POST_NOT_USABLE 1_103_000_002）
// ——token 仍有效而当前岗位被撤/组织停用，与账号失效（401）分属不同恢复路径
const POST_INVALID_CODE = 1103000002;
let unauthorizedHandler = null;
let postInvalidHandler = null;
let postInvalidRecovering = false; // 恢复流进行中：并发岗位失效去抖，恢复完成由 auth.recoverPostInvalid 复位
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

/** 岗位失效回调（M02-T01/I05：auth.recoverPostInvalid 注册；参数=失效的 postId） */
function onPostInvalid(handler) {
  postInvalidHandler = handler;
}

/** 恢复流结束复位（auth.recoverPostInvalid 在成功/失败两路都必须调用） */
function endPostInvalidRecovery() {
  postInvalidRecovering = false;
}

/** 会话已不可用通知（岗位失效无替代/恢复失败时由 auth 层调用，复用 401 的登录引导链） */
function notifySessionExpired() {
  if (unauthorizedHandler) unauthorizedHandler();
}

/** 账号失效统一处理（HTTP401 与 body401 同契约）：钱包域隔离、旧会话静默、当前会话清场+引导登录 */
function handleUnauthorized(requestSessionGen, opts, reject) {
  // 独立会话（如钱包 token）的 401 只报错，绝不动平台会话
  if (opts && opts.skipSessionGuard) {
    reject({ code: 401, message: '会话无效或已过期' });
    return;
  }
  // 仅当请求所属会话仍是当前会话时才清会话并通知；旧会话的 401 静默丢弃
  if (requestSessionGen === sessionGen) {
    clearSession();
    if (unauthorizedHandler) unauthorizedHandler();
    reject({ code: 401, message: '登录已过期，请重新登录' });
  } else {
    reject({ code: 401, silent: true, message: '旧会话请求已忽略' });
  }
}

function request(method, path, data, extraHeader, opts) {
  const requestSessionGen = sessionGen;
  return new Promise((resolve, reject) => {
    wx.request({
      url: (opts && opts.base ? opts.base : base()) + path,
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
          handleUnauthorized(requestSessionGen, opts, reject);
          return;
        }
        const body = res.data;
        // M02-T01/I02：后端认证入口以 HTTP200+body.code=401 返回认证失败（未设 HTTP 状态码）——与 HTTP401 同契约处理
        if (body && body.code === 401) {
          handleUnauthorized(requestSessionGen, opts, reject);
          return;
        }
        // M02-T01/I05：岗位失效（token 仍有效）——触发恢复流回调刷新任职；钱包域与旧会话不触发
        if (body && body.code === POST_INVALID_CODE) {
          if (!(opts && opts.skipSessionGuard) && requestSessionGen === sessionGen
              && postInvalidHandler && !postInvalidRecovering) {
            postInvalidRecovering = true;
            try {
              postInvalidHandler(currentPostId()); // 恢复流负责最终 endPostInvalidRecovery 复位
            } catch (e) {
              postInvalidRecovering = false; // handler 同步异常：复位去抖，后续失效可再触发（Codex R1 P2）
            }
          }
          reject({ code: body.code, postInvalid: true, message: (body && body.msg) || '工作身份无效或已被撤销，请重新选择' });
          return;
        }
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
  onPostInvalid,
  endPostInvalidRecovery,
  notifySessionExpired,
  request,
  get: (path, params) => request('GET', path, params),
  post: (path, data, extraHeader) => request('POST', path, data, extraHeader),
  put: (path, data, extraHeader) => request('PUT', path, data, extraHeader),
  del: (path, data, extraHeader) => request('DELETE', path, data, extraHeader)
};
