/**
 * 设计点钱包 API 层：充值套餐/订单/支付参数/点数账户与流水（09 钱包会话桥接契约）。
 *
 * 真实后端契约：monorepo yudao-module-commerce App*Controller（前缀 /app-api，路径 /design/v1/…，{code,data,msg}）。
 * 身份：identity user_session Bearer——由平台桥接端点 POST /platform/wallet/session 颁发
 * （须带 X-WX-Login-Code 一次性 code；09 文档 §三）。关键语义：
 * - 前缀：钱包业务走 /app-api（与平台 /admin-api 体系独立），经 walletBase() 解析；
 * - 串账防护：缓存条目 {fp, token}（fp=会话代次+岗位）；walletRequest 冻结发起时指纹，
 *   于缓存写入/token 交付/401 重铸后/业务响应交付前四时机复核，失配抛 WALLET_STALE
 *   （消费者须静默终止：不更新页面、不重试、不继续支付）；
 * - 401 区分来源：桥接 401=平台会话失效（上抛走平台登录）；钱包业务 401=token 失效（仅重铸一次重放）；
 * - 建单/恢复支付须带一次性 wx.login code（X-WX-Login-Code 头，换取即弃）。
 * 桥接未部署（404/501）或充值未开放（503）或岗位无权（403）→ ensureSession 返回 null，页面据此降级。
 */
const http = require('./http');

const SESSION_KEY = 'zs-wallet-token-v1';
const STALE = 'WALLET_STALE';

/** 钱包业务基址：/app-api 体系（真实 commerce App 端点），可经 zs-wallet-base 覆写 */
function walletBase() {
  const stored = wx.getStorageSync('zs-wallet-base');
  if (stored) return String(stored).replace(/\/$/, '');
  return http.base().replace('/admin-api', '/app-api');
}

/** 串账指纹：会话代次 + 当前岗位 */
function fingerprint() {
  return http.sessionGeneration() + ':' + (http.currentPostId() || '');
}

function walletToken() {
  return cachedSession();
}

function saveWalletSession(token, fp) {
  // 只在指纹未变时落缓存：迟到响应不得把旧组织的 token 配上新岗位
  if (fingerprint() !== fp) return;
  wx.setStorageSync(SESSION_KEY, JSON.stringify({ fp: fp, token: token }));
}

function clearWalletSession() {
  wx.removeStorageSync(SESSION_KEY);
}

function cachedSession() {
  try {
    const raw = wx.getStorageSync(SESSION_KEY);
    if (!raw) return '';
    const parsed = JSON.parse(raw);
    return parsed && parsed.fp === fingerprint() ? parsed.token : '';
  } catch (e) {
    return '';
  }
}

/** 桥接失败冷却：任何失败后 60s 内不再打桥接（联合调试修复 2026-11-08——
 * 真机页面跳转各触发一次桥接，无冷却时连续失败会打爆端点限流（429）并造成跳转卡死） */
let bridgeCooldownUntil = 0;
/** 单飞通道：跳转链上多组件并发触发桥接时共享同一 promise——
 * 微信对短时重复 wx.login 返回同一 code（一次性），并发各自 wx.login 必致第二个请求
 * 以 40029（code 已使用）失败（联合调试实证 2026-11-09，后端 issueSession 每 60s 周期成对报错） */
let bridgeInFlight = null;

/** 桥接换卡：code 经 X-WX-Login-Code 头上送；404/501/503/403/429/业务错=降级 null（含冷却），401=平台会话失效上抛 */
function bridgeSession(code, fp) {
  if (Date.now() < bridgeCooldownUntil) return Promise.resolve(null);
  return http.request('POST', '/platform/wallet/session', null,
      code ? { 'X-WX-Login-Code': code } : null)
    .then((data) => {
      if (data && data.token) {
        saveWalletSession(data.token, fp);
        return cachedSession();
      }
      return null;
    })
    .catch((err) => {
      if (err && err.code === 401) throw err; // 平台会话失效：交给平台登录流程
      // 404/501/503/403=功能不可用；429=限流；其余业务错（如凭证交换失败）——统一降级并冷却 60s
      bridgeCooldownUntil = Date.now() + 60000;
      return null;
    });
}

/** 取（或复用）钱包会话；平台未登录/桥接不可用返回 null（先 wx.login 取一次性 code）。
 * 冷却检查前置（冷却期内不浪费 wx.login），并发触发走单飞共享同一桥接。 */
function ensureSession(force) {
  const cached = cachedSession();
  if (cached && !force) return Promise.resolve(cached);
  if (Date.now() < bridgeCooldownUntil) return Promise.resolve(null);
  if (bridgeInFlight) return bridgeInFlight;
  const fp = fingerprint();
  const loginStep = typeof wx.login !== 'function'
    ? Promise.resolve(null)
    : new Promise((resolve) => {
        wx.login({
          success: (res) => resolve(res && res.code),
          fail: () => resolve(null)
        });
      });
  bridgeInFlight = loginStep.then((code) => bridgeSession(code, fp))
    .then((token) => { bridgeInFlight = null; return token; },
          (err) => { bridgeInFlight = null; throw err; });
  return bridgeInFlight;
}

/**
 * 钱包身份请求：Authorization 覆写为钱包 token、401 不清平台会话；
 * 指纹四时机复核（含业务响应交付前），失配抛 WALLET_STALE 供消费者静默终止。
 * opts.fp 用于支付意图冻结：调用方（confirmPay/continuePay）在点击时冻结并全程透传。
 */
function walletRequest(method, path, data, extraHeader, state) {
  state = state || { fp: fingerprint(), retried: false };
  return ensureSession(state.retried).then((token) => {
    if (!token) throw { code: 'WALLET_UNAVAILABLE', message: '钱包功能未开通' };
    if (fingerprint() !== state.fp) throw stale();
    const headers = Object.assign({}, extraHeader, { Authorization: 'Bearer ' + token });
    return http.request(method, path, data, headers, { skipSessionGuard: true, base: walletBase() });
  }).then((result) => {
    if (fingerprint() !== state.fp) throw stale(); // 业务响应交付前复核
    return result;
  }, (err) => {
    if (fingerprint() !== state.fp) throw stale();
    if (err && err.code === 401 && !state.retried) {
      // 钱包 token 失效：仅重铸一次重放（不清平台会话）
      clearWalletSession();
      return walletRequest(method, path, data, extraHeader, { fp: state.fp, retried: true });
    }
    throw err;
  });
}

function stale() {
  return { code: STALE, message: '身份已切换，本次操作已取消' };
}

function isStale(err) {
  return !!err && err.code === STALE;
}

const API_BASE = '/design/v1';

/** 充值套餐（enabled 项，按 sort 升序） */
function listPlans(fp) {
  return walletRequest('GET', API_BASE + '/recharge-plans', null, null, fpState(fp));
}

/**
 * 创建充值订单并返回支付参数。idemKey 幂等键；loginCode 为支付前 wx.login 一次性 code。
 * fp 为支付意图冻结指纹（confirmPay 点击时冻结，贯穿 wx.login 全程）。
 */
function createOrder(planId, idemKey, loginCode, fp) {
  const headers = { 'Idempotency-Key': idemKey };
  if (loginCode) headers['X-WX-Login-Code'] = loginCode;
  return walletRequest('POST', API_BASE + '/recharge-orders?planId=' + encodeURIComponent(planId),
      null, headers, fpState(fp));
}

/** 订单详情：支付状态与到账状态分列，成功页以此为准 */
function getOrder(orderId, fp) {
  return walletRequest('GET', API_BASE + '/recharge-orders/' + encodeURIComponent(orderId), null, null, fpState(fp));
}

/** 我的充值订单列表 */
function listOrders(pageNo, pageSize, fp) {
  return walletRequest('GET',
      API_BASE + '/recharge-orders?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20), null, null, fpState(fp));
}

/** 恢复流程：重领支付参数（仅 CREATED/PENDING；已支付/已关闭 409） */
function getPayParams(orderId, loginCode, fp) {
  const headers = loginCode ? { 'X-WX-Login-Code': loginCode } : undefined;
  return walletRequest('GET', API_BASE + '/recharge-orders/' + encodeURIComponent(orderId) + '/pay-params',
      null, headers, fpState(fp));
}

/** 点数账户：availablePoints/reservedPoints/allowedActions */
function pointAccount(fp) {
  return walletRequest('GET', API_BASE + '/point-account', null, null, fpState(fp));
}

/** 点数流水（只追加账本，倒序分页） */
function pointLedger(pageNo, pageSize, fp) {
  return walletRequest('GET',
      API_BASE + '/point-ledger?pageNo=' + (pageNo || 1) + '&pageSize=' + (pageSize || 20), null, null, fpState(fp));
}

function fpState(fp) {
  return fp ? { fp: fp, retried: false } : null;
}

module.exports = {
  SESSION_KEY,
  STALE,
  isStale,
  fingerprint,
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
