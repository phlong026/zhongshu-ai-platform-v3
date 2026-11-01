/**
 * 设计点钱包本地服务（开发/联动测试用，非正式后端）。
 *
 * 镜像 monorepo yudao-module-commerce 的 App*Controller 真实契约：
 * - POST /admin-api/platform/wallet/session   平台→钱包会话桥接（演示实现：任意平台 Bearer 可换）
 * - GET  /admin-api/design/v1/recharge-plans  套餐列表
 * - POST /admin-api/design/v1/recharge-orders?planId=  建单（Idempotency-Key + X-WX-Login-Code）
 * - GET  /admin-api/design/v1/recharge-orders(/{id})   订单列表/详情
 * - GET  /admin-api/design/v1/recharge-orders/{id}/pay-params 恢复支付（仅 CREATED/PENDING）
 * - GET  /admin-api/design/v1/point-account | point-ledger    点数账户/流水
 * - POST /admin-api/design/v1/dev/settle      【仅本地】模拟微信发货推送后的到账结算
 *
 * 与真实后端的差异（刻意为之，仅限本地）：无鉴权发卡中心、签名值为演示串、
 * dev/settle 代替微信回调。生产链路（真机 requestVirtualPayment → 微信回调）以
 * monorepo 后端 + MP 后台凭据为准。
 */
const http = require('http');
const crypto = require('crypto');

const WALLET_TOKEN = 'wallet-demo-session-1';
const RECHARGE_ENABLED = true;

// 套餐种子（amountCents 分；基础点/赠送点与 recharge_plan 表同构；¥500 赠送比例最高=最划算）
const PLANS = [
  { planId: '1', name: '体验包', amountCents: 1000, basePoints: 100, bonusPoints: 0, recommended: false, sort: 1 },
  { planId: '2', name: '轻量包', amountCents: 5000, basePoints: 500, bonusPoints: 50, recommended: false, sort: 2 },
  { planId: '3', name: '标准包', amountCents: 10000, basePoints: 1000, bonusPoints: 200, recommended: true, sort: 3 },
  { planId: '4', name: '进阶包', amountCents: 20000, basePoints: 2000, bonusPoints: 500, recommended: false, sort: 4 },
  { planId: '5', name: '旗舰包', amountCents: 50000, basePoints: 5000, bonusPoints: 2000, recommended: false, sort: 5 },
  { planId: '6', name: '团队包', amountCents: 100000, basePoints: 10000, bonusPoints: 3000, recommended: false, sort: 6 }
];

const state = {
  orderSeq: 1001,
  orders: [], // {orderId,userId,planId,amountCents,basePoints,bonusPoints,paymentState,fulfillmentState,payParams,idempotencyKey,createdAt,paidAt}
  ledger: [],
  ledgerSeq: 1,
  account: { availablePoints: 0, reservedPoints: 0 }
};

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

function ok(res, data) {
  json(res, 200, { code: 0, data: data === undefined ? null : data });
}

function fail(res, status, code, msg) {
  json(res, status, { code: code, msg: msg });
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw)); } catch (e) { resolve({}); }
    });
  });
}

function bearerOf(req) {
  const auth = req.headers['authorization'] || '';
  return auth.startsWith('Bearer ') ? auth.slice(7) : '';
}

function planById(planId) {
  return PLANS.find((p) => String(p.planId) === String(planId)) || null;
}

function orderVo(order) {
  const vo = {
    orderId: order.orderId,
    planId: order.planId,
    amountCents: order.amountCents,
    basePoints: order.basePoints,
    bonusPoints: order.bonusPoints,
    paymentState: order.paymentState,
    fulfillmentState: order.fulfillmentState,
    refund: null,
    allowedActions: RECHARGE_ENABLED ? ['RECHARGE', 'VIEW_LEDGER'] : ['VIEW_LEDGER'],
    createdAt: order.createdAt,
    paidAt: order.paidAt || null
  };
  // 已支付订单不再下发签名参数（与后端「重领 pay-params 409」语义一致）
  if (order.paymentState === 'CREATED' || order.paymentState === 'PENDING') vo.payParams = order.payParams;
  return vo;
}

function demoPayParams(order) {
  const signData = JSON.stringify({
    offerId: 'offer-demo',
    buyQuantity: 1,
    env: 0,
    currencyType: 'CNY',
    productId: 'product-' + order.planId,
    goodsPrice: order.amountCents,
    outTradeNo: order.orderId,
    attach: order.orderId
  });
  const paySig = 'demo-' + crypto.createHash('sha256').update('requestVirtualPayment&' + signData).digest('hex').slice(0, 32);
  const signature = 'demo-' + crypto.createHash('sha256').update('sessionkey&' + signData).digest('hex').slice(0, 32);
  return { signData: signData, mode: 'short_series_goods', paySig: paySig, signature: signature };
}

function createOrder(userId, plan, idempotencyKey, loginCode) {
  // 幂等：同 (userId, Idempotency-Key) 返回既有订单（与后端幂等键语义一致）
  const hit = state.orders.find((o) => o.userId === userId && o.idempotencyKey === idempotencyKey);
  if (hit) return hit;
  const order = {
    orderId: String(state.orderSeq++),
    userId: userId,
    planId: plan.planId,
    amountCents: plan.amountCents,
    basePoints: plan.basePoints,
    bonusPoints: plan.bonusPoints,
    paymentState: 'CREATED',
    fulfillmentState: 'NOT_READY',
    payParams: loginCode ? demoPayParams({ planId: plan.planId, amountCents: plan.amountCents, orderId: '' }) : { state: 'SESSION_CODE_REQUIRED' },
    idempotencyKey: idempotencyKey,
    createdAt: new Date().toISOString()
  };
  // signData 里的 outTradeNo 依赖订单号：建单后重签一次（本地演示；真实链路服务端一次成形）
  if (loginCode) order.payParams = demoPayParams(order);
  state.orders.push(order);
  return order;
}

function settle(orderId) {
  const order = state.orders.find((o) => o.orderId === String(orderId));
  if (!order) return null;
  if (order.fulfillmentState === 'CREDITED') return order; // 幂等：已到账不重复入账
  order.paymentState = 'SUCCEEDED';
  order.fulfillmentState = 'CREDITED';
  order.paidAt = new Date().toISOString();
  const credit = (type, delta) => {
    state.account.availablePoints += delta;
    state.ledger.unshift({
      ledgerId: String(state.ledgerSeq++),
      userId: order.userId,
      type: type,
      delta: delta,
      balanceAfter: state.account.availablePoints,
      reservedAfter: state.account.reservedPoints,
      bizType: 'RECHARGE',
      bizId: order.orderId,
      createdAt: new Date().toISOString()
    });
  };
  credit('RECHARGE_BASE_CREDIT', order.basePoints);
  if (order.bonusPoints > 0) credit('RECHARGE_BONUS_CREDIT', order.bonusPoints);
  return order;
}

function route(req, res, url) {
  const path = url.pathname;

  // 平台→钱包会话桥接（演示实现：持有任意平台 Bearer 即可换取钱包会话）
  if (req.method === 'POST' && path === '/admin-api/platform/wallet/session') {
    if (!bearerOf(req)) return fail(res, 401, 401, '缺少平台会话');
    return ok(res, { token: WALLET_TOKEN, expiresIn: 3600 });
  }

  if (!path.startsWith('/admin-api/design/v1/')) return fail(res, 404, 404, '接口不存在');

  // ---- 用户域端点：需要钱包会话 ----
  if (bearerOf(req) !== WALLET_TOKEN) return fail(res, 401, 401, '会话无效或已过期');
  const USER_ID = 8601;

  if (req.method === 'GET' && path === '/admin-api/design/v1/recharge-plans') {
    return ok(res, PLANS.filter((p) => p.sort > 0));
  }

  if (req.method === 'POST' && path === '/admin-api/design/v1/recharge-orders') {
    if (!RECHARGE_ENABLED) return fail(res, 503, 503, '充值功能暂未开放');
    const plan = planById(url.searchParams.get('planId'));
    if (!plan) return fail(res, 404, 404, '套餐不存在');
    const order = createOrder(USER_ID, plan, req.headers['idempotency-key'] || '', req.headers['x-wx-login-code'] || '');
    return ok(res, orderVo(order));
  }

  let m = path.match(/^\/admin-api\/design\/v1\/recharge-orders\/(\d+)\/pay-params$/);
  if (req.method === 'GET' && m) {
    if (!RECHARGE_ENABLED) return fail(res, 503, 503, '充值功能暂未开放');
    const order = state.orders.find((o) => o.orderId === m[1] && o.userId === USER_ID);
    if (!order) return fail(res, 403, 403, '订单不存在或无权访问');
    if (order.paymentState !== 'CREATED' && order.paymentState !== 'PENDING') {
      return fail(res, 409, 409, '订单暂不可支付，请刷新订单状态后重试');
    }
    const code = req.headers['x-wx-login-code'] || '';
    return ok(res, code ? demoPayParams(order) : { state: 'SESSION_CODE_REQUIRED' });
  }

  m = path.match(/^\/admin-api\/design\/v1\/recharge-orders\/(\d+)$/);
  if (req.method === 'GET' && m) {
    const order = state.orders.find((o) => o.orderId === m[1] && o.userId === USER_ID);
    if (!order) return fail(res, 403, 403, '订单不存在或无权访问');
    return ok(res, orderVo(order));
  }

  if (req.method === 'GET' && path === '/admin-api/design/v1/recharge-orders') {
    const mine = state.orders.filter((o) => o.userId === USER_ID);
    const pageNo = Number(url.searchParams.get('pageNo')) || 1;
    const pageSize = Number(url.searchParams.get('pageSize')) || 20;
    return ok(res, {
      total: mine.length,
      list: mine.slice((pageNo - 1) * pageSize, pageNo * pageSize).map(orderVo)
    });
  }

  if (req.method === 'GET' && path === '/admin-api/design/v1/point-account') {
    return ok(res, {
      availablePoints: state.account.availablePoints,
      reservedPoints: state.account.reservedPoints,
      allowedActions: RECHARGE_ENABLED ? ['RECHARGE', 'VIEW_LEDGER'] : ['VIEW_LEDGER']
    });
  }

  if (req.method === 'GET' && path === '/admin-api/design/v1/point-ledger') {
    const pageNo = Number(url.searchParams.get('pageNo')) || 1;
    const pageSize = Number(url.searchParams.get('pageSize')) || 20;
    return ok(res, {
      total: state.ledger.length,
      list: state.ledger.slice((pageNo - 1) * pageSize, pageNo * pageSize)
    });
  }

  if (req.method === 'POST' && path === '/admin-api/design/v1/dev/settle') {
    return readBody(req).then((body) => {
      const order = settle(body.orderId);
      if (!order) return fail(res, 404, 404, '订单不存在');
      return ok(res, { orderId: order.orderId, paymentState: order.paymentState, fulfillmentState: order.fulfillmentState });
    });
  }

  return fail(res, 404, 404, '接口不存在');
}

let httpServer = null;

module.exports = {
  WALLET_TOKEN,
  listen(port = 0, host = '127.0.0.1') {
    return new Promise((resolve, reject) => {
      httpServer = http.createServer((req, res) => {
        try {
          route(req, res, new URL(req.url, 'http://localhost'));
        } catch (err) {
          fail(res, 500, 500, '服务内部错误');
        }
      });
      httpServer.once('error', reject);
      httpServer.listen(port, host, () => {
        httpServer.off('error', reject);
        resolve(httpServer.address());
      });
    });
  },
  close() {
    if (httpServer) httpServer.close();
  },
  __state: state
};
