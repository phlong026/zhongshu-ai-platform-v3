/** B 组织钱包：复用 ADMIN Authorization、X-Post-Id 和 CommonResult，不另换 token。 */
const http = require('./http');
const API_BASE = '/platform/wallet';
const STALE = 'WALLET_STALE';

function fingerprint() {
  return http.sessionGeneration() + ':' + http.currentPostId() + ':' + http.tenantId();
}

function stale() {
  return { code: STALE, message: '身份已切换，本次操作已取消' };
}

function isStale(err) {
  return !!err && err.code === STALE;
}

function errorMessage(err) {
  const messages = {
    401: '登录已过期，请重新登录',
    403: '当前岗位无钱包操作权限',
    404: '钱包服务暂不可用',
    409: '支付尝试已使用或仍在处理中，请先查询原订单',
    503: '支付渠道暂不可用，请稍后查看原订单',
    1120000001: '钱包或微信付款身份不可用，请联系管理员',
    1120000002: '请求与原订单不一致，请查看原订单',
    1120000004: '订单当前状态不允许支付，请查看充值明细',
    1120000005: '支付参数无效，请重新尝试',
  };
  return (err && err.message) || messages[err && err.code] || '钱包请求失败，请重试';
}

function walletRequest(method, path, data, fp) {
  const expected = fp || fingerprint();
  if (expected !== fingerprint()) return Promise.reject(stale());
  if (!http.token()) return Promise.reject({ code: 401, message: '请先登录后查看钱包' });
  if (!http.currentPostId()) return Promise.reject({ code: 403, message: '请先选择工作身份' });
  return http.request(method, API_BASE + path, data).then((result) => {
    if (fingerprint() !== expected) throw stale();
    return result;
  }, (err) => {
    // 当前 401 已由公共传输层清理会话；保留原错误。旧会话 401 绝不影响新身份。
    const expired = err && err.code === 401 && !err.silent && !http.token();
    if (fingerprint() !== expected && !expired) throw stale();
    console.warn('[wallet] 请求失败', method, path, err && err.code);
    throw err;
  });
}

function listPlans(fp) {
  return walletRequest('GET', '/recharge-plans', null, fp);
}

function createOrder(planId, idempotencyKey, fp) {
  return walletRequest('POST', '/recharge-orders', { planId, idempotencyKey }, fp);
}

function getOrder(orderId, fp) {
  return walletRequest('GET', '/recharge-orders/' + encodeURIComponent(orderId), null, fp);
}

function listOrders(pageNo, pageSize, fp) {
  return walletRequest('GET', '/recharge-orders?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20), null, fp);
}

/** 每次调用由页面提供 fresh wx.login code 和独立 attempt key，绝不自动重放。 */
function getPayParams(orderId, code, idempotencyKey, fp) {
  return walletRequest('POST', '/recharge-orders/' + encodeURIComponent(orderId) + '/pay-params', { code, idempotencyKey }, fp);
}

function pointAccount(fp) {
  return walletRequest('GET', '/account', null, fp);
}

function pointLedger(pageNo, pageSize, fp) {
  return walletRequest('GET', '/ledger?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20), null, fp);
}

module.exports = {
  STALE, isStale, fingerprint, errorMessage,
  listPlans, createOrder, getOrder, listOrders, getPayParams, pointAccount, pointLedger,
};
