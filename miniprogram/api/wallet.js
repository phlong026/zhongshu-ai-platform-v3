/**
 * 设计点钱包 API 层：充值套餐/订单/支付参数/点数账户与流水。
 *
 * 真实后端契约：monorepo yudao-module-commerce App*Controller（/app-api/design/v1/**，{code,data,msg}）。
 * 关键语义（与后端一一对应，勿改）：
 * - 身份：identity 模块 user_session Bearer（与平台员工会话相互独立）；
 *   由平台侧桥接端点 POST /platform/wallet/session 颁发（后端批次见 docs/backend-parity 钱包接入）。
 * - 建单：POST /recharge-orders?planId= + Idempotency-Key（幂等键=user_id+key）+ X-WX-Login-Code 头
 *   （虚拟支付签名用一次性 code，服务端换取即弃；禁止放 URL）。
 * - 成功页以服务端 paymentState/fulfillmentState 为准，前端 success 回调不作到账依据（红线 3）。
 * - 恢复支付：GET /recharge-orders/{orderId}/pay-params 重领签名，禁止重复建单（T13-24）。
 * 桥接端点未部署（404/501）或充值未开放（503）时 ensureSession 返回 null，页面据此降级。
 */
const http = require('./http');

const SESSION_KEY = 'zs-wallet-token-v1';
const API_BASE = '/design/v1';

function walletToken() {
  return wx.getStorageSync(SESSION_KEY) || '';
}

function saveWalletSession(token) {
  wx.setStorageSync(SESSION_KEY, token);
}

function clearWalletSession() {
  wx.removeStorageSync(SESSION_KEY);
}

/** 获取（或复用）钱包会话；平台未登录/桥接不可用返回 null */
function ensureSession(force) {
  const cached = walletToken();
  if (cached && !force) return Promise.resolve(cached);
  return http.request('POST', '/platform/wallet/session', null, null).then((data) => {
    if (data && data.token) {
      saveWalletSession(data.token);
      return data.token;
    }
    return null;
  }).catch((err) => {
    // 404/501=桥接未部署；503=充值未开放：均按「功能不可用」降级，不打扰用户
    if (err && (err.code === 404 || err.code === 501 || err.code === 503)) return null;
    throw err;
  });
}

/**
 * 钱包身份请求：Authorization 覆写为钱包 token、401 不清平台会话；
 * 钱包 token 失效时经桥接重取一次再重放（仅一次，防循环）。
 */
function walletRequest(method, path, data, extraHeader, retried) {
  return ensureSession(retried).then((token) => {
    if (!token) {
      throw { code: 'WALLET_UNAVAILABLE', message: '钱包功能未开通' };
    }
    const headers = Object.assign({}, extraHeader, { Authorization: 'Bearer ' + token });
    return http.request(method, path, data, headers, { skipSessionGuard: true });
  }).catch((err) => {
    if (err && err.code === 401 && !retried) {
      clearWalletSession();
      return walletRequest(method, path, data, extraHeader, true);
    }
    throw err;
  });
}

/** 充值套餐（enabled 项，按 sort 升序） */
function listPlans() {
  return walletRequest('GET', API_BASE + '/recharge-plans');
}

/**
 * 创建充值订单并返回支付参数。planId 必填；idemKey 幂等键；loginCode 为支付前
 * wx.login 一次性 code（虚拟支付签名必需，stub 通道忽略）。
 */
function createOrder(planId, idemKey, loginCode) {
  const headers = { 'Idempotency-Key': idemKey };
  if (loginCode) headers['X-WX-Login-Code'] = loginCode;
  return walletRequest('POST', API_BASE + '/recharge-orders?planId=' + encodeURIComponent(planId), null, headers);
}

/** 订单详情：支付状态与到账状态分列，成功页以此为准 */
function getOrder(orderId) {
  return walletRequest('GET', API_BASE + '/recharge-orders/' + encodeURIComponent(orderId));
}

/** 我的充值订单列表 */
function listOrders(pageNo, pageSize) {
  return walletRequest('GET', API_BASE + '/recharge-orders?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20));
}

/** 恢复流程：重领支付参数（仅 CREATED/PENDING；已支付/已关闭 409） */
function getPayParams(orderId, loginCode) {
  const headers = loginCode ? { 'X-WX-Login-Code': loginCode } : undefined;
  return walletRequest('GET', API_BASE + '/recharge-orders/' + encodeURIComponent(orderId) + '/pay-params', null, headers);
}

/** 点数账户：availablePoints/reservedPoints/allowedActions */
function pointAccount() {
  return walletRequest('GET', API_BASE + '/point-account');
}

/** 点数流水（只追加账本，倒序分页） */
function pointLedger(pageNo, pageSize) {
  return walletRequest('GET', API_BASE + '/point-ledger?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20));
}

module.exports = {
  SESSION_KEY,
  walletToken,
  saveWalletSession,
  clearWalletSession,
  ensureSession,
  listPlans,
  createOrder,
  getOrder,
  listOrders,
  getPayParams,
  pointAccount,
  pointLedger
};
