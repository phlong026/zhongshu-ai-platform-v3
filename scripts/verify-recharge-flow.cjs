/** B 钱包合同与页面行为测试。仅使用内存 wx 传输替身，不调用渠道，不代表真实支付验收。 */
const assert = require('node:assert/strict');
const { test, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../miniprogram');
const prefix = '/admin-api/platform/wallet';
const plan = { id: '9007199254740999', name: '设计点', priceFen: 10000, basePoints: 1000, bonusPoints: 200 };
const order = { id: '9007199254741001', orderNo: 'PFW9007199254741001', status: 'CREATED', creditState: 'NOT_READY', priceFen: 10000, basePoints: 1000, bonusPoints: 200, createTime: '2026-11-11T10:20:30' };
const params = { signData: '{ "order": "PFW9007199254741001" }', mode: 'short_series_goods', paySig: 'fixture-pay-signature', signature: 'fixture-signature' };
let http, wallet, requests, logins, payments, toasts, storage, responder, timers;
const flush = () => new Promise((resolve) => setImmediate(resolve));
function reply(req, data, code = 0, statusCode = 200, msg = '') {
  req.success({ statusCode, data: { code, data, msg } });
}
function defaultReply(req) {
  const url = new URL(req.url);
  if (url.pathname.endsWith('/session')) return reply(req, { token: 'legacy-fixture' });
  if (url.pathname.endsWith('/recharge-plans')) return reply(req, [plan]);
  if (url.pathname.endsWith('/account')) return reply(req, { walletId: '91', orgId: '92', availablePoints: 1860, reservedPoints: 0, status: 'ACTIVE', version: 1 });
  if (url.pathname.endsWith('/ledger')) return reply(req, { list: [], total: 0 });
  if (url.pathname.endsWith('/pay-params')) return reply(req, params);
  if (url.pathname.endsWith('/recharge-orders')) return reply(req, req.method === 'POST' ? order : { list: [order], total: 1 });
  return reply(req, order);
}
function instance(relative, component = false) {
  const filename = path.join(root, relative);
  let definition;
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    require: createRequire(filename), wx: global.wx, console,
    Page(value) { definition = value; }, Component(value) { definition = value; },
    setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {},
  }, { filename });
  const item = { ...(component ? definition.methods : definition), data: JSON.parse(JSON.stringify(definition.data)), updates: [] };
  item.setData = function (patch) { this.updates.push(patch); Object.assign(this.data, patch); };
  if (component) Object.assign(item, definition.lifetimes, { show: definition.pageLifetimes.show });
  return item;
}
async function recharge() {
  const page = instance('pages/wallet/recharge/index.js');
  page.onLoad();
  await flush();
  return page;
}
async function records() {
  const page = instance('pages/wallet/records/index.js');
  page.onLoad();
  await flush();
  return page;
}
beforeEach(() => {
  requests = []; logins = []; payments = []; toasts = []; timers = []; storage = {};
  responder = defaultReply;
  global.wx = {
    getStorageSync(key) { return storage[key] || ''; },
    setStorageSync(key, value) { storage[key] = value; },
    removeStorageSync(key) { delete storage[key]; },
    request(req) { requests.push(req); responder(req); },
    login(opts) { logins.push(opts); opts.success({ code: 'fresh-code-' + logins.length }); },
    requestVirtualPayment(opts) { payments.push(opts); },
    showToast(opts) { toasts.push(opts.title); }, navigateTo() {}, navigateBack() {},
  };
  for (const name of ['api/http.js', 'api/wallet.js']) delete require.cache[require.resolve(path.join(root, name))];
  http = require(path.join(root, 'api/http.js'));
  wallet = require(path.join(root, 'api/wallet.js'));
  wx.setStorageSync('zs-api-base', 'http://wallet.test/admin-api');
  http.saveSession({ token: 'admin-fixture', postId: 'post-A', tenantId: '1' });
});

test('账户、套餐、流水只用 ADMIN 与岗位，无换 token/微信登录/组织参数', async () => {
  wx.setStorageSync('zs-wallet-base', 'https://legacy.invalid/app-api');
  wx.setStorageSync('zs-wallet-token-v1', 'obsolete');
  await wallet.pointAccount(); await wallet.listPlans(); await wallet.pointLedger(2, 20);
  assert.deepEqual(requests.map((r) => r.url), [prefix + '/account', prefix + '/recharge-plans', prefix + '/ledger?pageNo=2&pageSize=20'].map((p) => 'http://wallet.test' + p));
  for (const req of requests) {
    assert.equal(req.header.Authorization, 'Bearer admin-fixture');
    assert.equal(req.header['X-Post-Id'], 'post-A');
    assert.equal(req.timeout, 15000);
    assert.equal(req.data, null);
  }
  assert.equal(logins.length, 0);
  assert.equal(wallet.ensureSession, undefined);
});

test('订单创建和支付参数分开，字符串 ID 及两种键按冻结 JSON 合同发送', async () => {
  const result = await wallet.createOrder(plan.id, 'stable-order-key');
  await wallet.getPayParams(result.id, 'one-use-code', 'fresh-attempt-key');
  await wallet.getOrder(order.id);
  await wallet.listOrders(2, 20);
  assert.equal(requests.length, 4);
  assert.deepEqual(requests[0].data, { planId: plan.id, idempotencyKey: 'stable-order-key' });
  assert.equal(requests[0].url, 'http://wallet.test' + prefix + '/recharge-orders');
  assert.equal(requests[1].method, 'POST');
  assert.equal(requests[1].url, 'http://wallet.test' + prefix + '/recharge-orders/' + order.id + '/pay-params');
  assert.deepEqual(requests[1].data, { code: 'one-use-code', idempotencyKey: 'fresh-attempt-key' });
  assert.equal(requests[2].url.endsWith('/' + order.id), true);
  assert.equal(requests[3].url.endsWith('?pageNo=2&pageSize=20'), true);
});

for (const change of ['切岗', '退出', '换账号']) {
  for (const outcome of ['成功', '失败']) {
    test(change + '后迟到' + outcome + '失效，不能重放', async () => {
      const pending = [];
      responder = (req) => pending.push(req);
      const result = wallet.pointAccount().then((value) => ({ value }), (err) => ({ err }));
      await flush();
      if (change === '退出') http.clearSession();
      else http.saveSession({ token: change === '换账号' ? 'admin-next' : 'admin-fixture', postId: change === '换账号' ? 'post-A' : 'post-B' });
      if (outcome === '成功') reply(pending[0], { availablePoints: 9999 });
      else pending[0].fail({ errMsg: 'request:fail timeout' });
      assert.equal((await result).err.code, 'WALLET_STALE');
      assert.equal(requests.length, 1);
    });
  }
}

test('旧岗位401不清理新会话；当前401走平台会话失效，不换 token 重放', async () => {
  let held;
  responder = (req) => { held = req; };
  const old = wallet.pointAccount().catch((err) => err);
  await flush();
  http.saveSession({ token: 'new-admin', postId: 'post-B' });
  reply(held, null, 401, 401);
  assert.equal((await old).code, 'WALLET_STALE');
  assert.equal(http.token(), 'new-admin');
  let expired = 0;
  http.onUnauthorized(() => { expired += 1; });
  const current = wallet.pointAccount().catch((err) => err);
  await flush(); reply(held, null, 401, 200);
  assert.equal((await current).code, 401);
  assert.equal(expired, 1);
  assert.equal(http.token(), '');
  assert.equal(requests.length, 2);
});

test('岗位失效复用平台恢复处理，403/503保留明确错误', async () => {
  let revoked = 0;
  http.onPostInvalid(() => { revoked += 1; });
  responder = (req) => reply(req, null, 1103000002, 200, '岗位已撤销');
  await assert.rejects(wallet.pointAccount(), (e) => e.postInvalid === true);
  assert.equal(revoked, 1);
  for (const code of [403, 503]) {
    responder = (req) => reply(req, null, code, code, '服务明确拒绝');
    await assert.rejects(wallet.pointAccount(), (e) => e.code === code && e.message === '服务明确拒绝');
  }
});

test('资产卡真正请求余额，重复刷新及组件卸载不接收旧结果', async () => {
  const held = [];
  responder = (req) => held.push(req);
  const card = instance('components/point-asset-card/index.js', true);
  card.attached(); await flush();
  assert.equal(held[0].url.endsWith('/account'), true);
  card.refresh(); await flush();
  reply(held[1], { availablePoints: 1860 }); await flush();
  reply(held[0], { availablePoints: 9 }); await flush();
  assert.equal(card.data.visible, true);
  assert.equal(card.data.balanceText, '1,860');
  card.refresh(); await flush(); card.detached();
  const count = card.updates.length;
  reply(held[2], { availablePoints: 99 }); await flush();
  assert.equal(card.updates.length, count);
});

test('充值页使用B套餐金额和字符串ID，清晰展示加载失败并可重试', async () => {
  const page = await recharge();
  assert.equal(page.data.plans[0].planId, plan.id);
  assert.equal(page.data.payAmountText, '¥100');
  assert.equal(page.data.balanceText, '1,860');
  responder = (req) => reply(req, null, 403, 403, '当前岗位无钱包权限');
  page.load(); await flush();
  assert.equal(page.data.loadState, 'failed');
  assert.match(page.data.errorText, /权限/);
  assert.equal(page.data.plans.length, 0);
  responder = defaultReply; page.load(); await flush();
  assert.equal(page.data.loadState, 'ready');
});

test('充值页卸载、身份切换和重叠刷新不回填旧数据', async () => {
  const page = await recharge();
  const held = []; responder = (req) => held.push(req);
  page.load(); page.load(); await flush();
  for (const req of held.slice(2)) defaultReply(req);
  await flush(); const count = page.updates.length;
  for (const req of held.slice(0, 2)) defaultReply(req);
  await flush(); assert.equal(page.updates.length, count);
  held.length = 0; page.load(); await flush(); page.onUnload();
  const after = page.updates.length;
  for (const req of held) defaultReply(req);
  await flush(); assert.equal(page.updates.length, after);
});

test('两个列表独立分页，并发加载不互相阻塞，不越过最后一页', async () => {
  const held = [];
  responder = (req) => held.push(req);
  const page = await records();
  assert.equal(held.length, 2);
  const orders = held.find((r) => r.url.includes('/recharge-orders'));
  const ledger = held.find((r) => r.url.includes('/ledger'));
  reply(orders, { list: [order], total: 2 }); await flush();
  page.loadOrders(false); await flush();
  assert.equal(held.length, 3);
  assert.equal(held[2].url.endsWith('pageNo=2&pageSize=20'), true);
  reply(held[2], { list: [{ ...order, id: 'next' }], total: 2 });
  reply(ledger, { list: [{ id: 'l1', type: 'MANUAL_CREDIT', delta: 9, availableAfter: 100, createTime: '2026-11-11T10:20:00' }], total: 1 });
  await flush();
  assert.equal(page.data.orders.length, 2);
  assert.equal(page.data.ledger[0].balanceText, '100');
  assert.equal(page.data.ledger[0].timeText, '11-11 10:20');
  page.loadOrders(false); page.loadLedger(false); await flush();
  assert.equal(held.length, 3);
});

test('列表失败有错误态且重试原页；同列表重复触底只请求一次', async () => {
  let fail = true;
  responder = (req) => {
    if (req.url.includes('/ledger')) return fail ? reply(req, null, 503, 503, '钱包暂不可用') : reply(req, { list: [], total: 0 });
    defaultReply(req);
  };
  const page = await records();
  assert.match(page.data.ledgerError, /暂不可用/);
  assert.equal(page.data.ledgerPageNo, 0);
  fail = false; page.loadLedger(false); await flush();
  assert.equal(page.data.ledgerError, ''); assert.equal(page.data.ledgerPageNo, 1);
  const held = []; responder = (req) => held.push(req);
  page.setData({ ordersTotal: 3 }); page.loadOrders(false); page.loadOrders(false);
  assert.equal(held.length, 1);
});

test('列表迟到错误不能影响新岗位列表或卸载后的页面', async () => {
  const page = await records();
  const held = []; responder = (req) => held.push(req);
  page.loadOrders(true); await flush();
  http.saveSession({ postId: 'post-B' }); page.onShow(); await flush();
  const count = page.updates.length;
  reply(held[0], null, 503, 503); await flush();
  assert.equal(page.updates.length, count);
  page.onUnload(); const final = page.updates.length;
  for (const req of held.slice(1)) defaultReply(req);
  await flush(); assert.equal(page.updates.length, final);
});

test('建单不消耗code，pay-params每次fresh登录和独立attempt，恢复不新建单', async () => {
  const page = await recharge();
  requests.length = 0; logins.length = 0;
  page.confirmPay(); await flush();
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].url.endsWith('/recharge-orders'), true);
  assert.equal(requests[0].data.code, undefined);
  assert.equal(logins.length, 1); assert.equal(payments.length, 1);
  const firstAttempt = requests[1].data.idempotencyKey;
  assert.notEqual(firstAttempt, requests[0].data.idempotencyKey);
  assert.equal(payments[0].signData, params.signData);
  payments[0].fail({ errMsg: 'cancel' }); await flush();
  page.continuePay(); await flush();
  const attempts = requests.filter((r) => r.url.endsWith('/pay-params'));
  assert.equal(attempts.length, 2);
  assert.equal(logins.length, 2);
  assert.notEqual(attempts[1].data.code, attempts[0].data.code);
  assert.notEqual(attempts[1].data.idempotencyKey, firstAttempt);
  assert.equal(requests.filter((r) => r.method === 'POST' && r.url.endsWith('/recharge-orders')).length, 1);
});

test('建单超时重试沿用原键和套餐，不因再次点击或换套餐创建新意图', async () => {
  const page = await recharge();
  const creates = [];
  responder = (req) => {
    if (req.method === 'POST' && req.url.endsWith('/recharge-orders')) { creates.push(req); return req.fail({ errMsg: 'request:fail timeout' }); }
    defaultReply(req);
  };
  page.confirmPay(); await flush(); page.confirmPay(); await flush();
  assert.equal(creates.length, 2);
  assert.equal(creates[0].data.idempotencyKey, creates[1].data.idempotencyKey);
  page.setData({ plans: [{ planId: 'other' }], selectedIdx: 0 });
  page.confirmPay(); await flush();
  assert.equal(creates[2].data.planId, plan.id);
  assert.equal(creates[2].data.idempotencyKey, creates[0].data.idempotencyKey);
  assert.equal(logins.length, 0);
});

test('pay-params超时/503不伪造成功，不新建单，恢复先查单', async () => {
  const page = await recharge();
  responder = (req) => req.url.endsWith('/pay-params') ? reply(req, null, 503, 503, '支付渠道尚未启用') : defaultReply(req);
  page.confirmPay(); await flush();
  assert.equal(payments.length, 0);
  assert.notEqual(page.data.resultState, 'done');
  assert.match(page.data.resultText, /尚未启用/);
  const start = requests.length;
  page.continuePay(); await flush();
  assert.equal(requests[start].method, 'GET');
  assert.equal(requests[start].url.endsWith('/' + order.id), true);
  assert.equal(requests.filter((r) => r.method === 'POST' && r.url.endsWith('/recharge-orders')).length, 1);
});

test('微信登录空code不调用支付参数，迟到登录不能对新身份支付', async () => {
  const page = await recharge();
  let login;
  wx.login = (opts) => { login = opts; };
  page.confirmPay(); await flush();
  login.success({ code: '' }); await flush();
  assert.equal(requests.some((r) => r.url.endsWith('/pay-params')), false);
  page.continuePay(); await flush();
  http.saveSession({ postId: 'post-B' });
  const count = page.updates.length;
  login.success({ code: 'late-code' }); await flush();
  assert.equal(payments.length, 0);
  assert.equal(page.updates.length, count);
});

test('渠道回调不能代表到账：SUCCEEDED/PENDING待入账，CREDITED才完成，BLOCKED冻结', async () => {
  const page = await recharge();
  page.confirmPay(); await flush();
  responder = (req) => req.url.endsWith('/' + order.id) ? reply(req, { ...order, status: 'SUCCEEDED', creditState: 'PENDING' }) : defaultReply(req);
  payments[0].success(); await flush();
  assert.equal(page.data.resultState, 'processing');
  assert.match(page.data.resultText, /入账|到账确认/);
  responder = (req) => req.url.endsWith('/' + order.id) ? reply(req, { ...order, status: 'SUCCEEDED', creditState: 'CREDITED' }) : defaultReply(req);
  timers.pop()(); await flush();
  assert.equal(page.data.resultState, 'done');
  responder = (req) => reply(req, { ...order, status: 'SUCCEEDED', creditState: 'BLOCKED' });
  page.startPolling(order.id); await flush();
  assert.notEqual(page.data.resultState, 'done'); assert.match(page.data.resultText, /冻结/);
  assert.equal(page.data.canContinuePay, false);
});

test('支付回调在卸载后无页面更新、查单或重试；缺支付参数明确失败', async () => {
  const page = await recharge();
  page.confirmPay(); await flush();
  assert.equal(payments.length, 1);
  page.onUnload(); const count = requests.length, updates = page.updates.length;
  payments[0].success(); payments[0].fail({ errMsg: 'late' }); await flush();
  assert.equal(requests.length, count); assert.equal(page.updates.length, updates);
  const other = await recharge();
  responder = (req) => req.url.endsWith('/pay-params') ? reply(req, {}) : defaultReply(req);
  other.confirmPay(); await flush();
  assert.notEqual(other.data.resultState, 'processing'); assert.notEqual(other.data.resultState, 'done');
  assert.match(other.data.resultText, /参数|不可用/);
});

test('建单结果未知时重进页面继续原键；订单已知时重进页面先查原单', async () => {
  const first = await recharge();
  const creates = [];
  responder = (req) => {
    if (req.method === 'POST' && req.url.endsWith('/recharge-orders')) {
      creates.push(req);
      if (creates.length === 1) return req.fail({ errMsg: 'request:fail timeout' });
    }
    defaultReply(req);
  };
  first.confirmPay(); await flush(); first.onUnload();
  const second = await recharge();
  second.confirmPay(); await flush();
  assert.equal(creates.length, 2);
  assert.deepEqual(creates[1].data, creates[0].data);
  second.onUnload();
  const third = await recharge();
  const start = requests.length;
  third.confirmPay(); await flush();
  assert.equal(requests[start].method, 'GET');
  assert.equal(requests[start].url.endsWith('/' + order.id), true);
  assert.equal(creates.length, 2);
  const saved = JSON.stringify(storage);
  assert.equal(saved.includes('fresh-code'), false);
  assert.equal(saved.includes(params.paySig), false);
});

test('恢复原单不展示默认套餐价格；建单结果未知不猜报价，已知单用订单快照', async () => {
  const cheap = { ...plan, id: 'cheap', priceFen: 5000, basePoints: 500, bonusPoints: 0 };
  responder = (req) => req.url.endsWith('/recharge-plans') ? reply(req, [cheap, plan]) : defaultReply(req);
  const first = await recharge();
  first.tapPlan({ currentTarget: { dataset: { idx: 0 } } });
  const create = [];
  responder = (req) => {
    if (req.method === 'POST' && req.url.endsWith('/recharge-orders')) { create.push(req); return req.fail({ errMsg: 'timeout' }); }
    if (req.url.endsWith('/recharge-plans')) return reply(req, [cheap, plan]);
    defaultReply(req);
  };
  first.confirmPay(); await flush(); first.onUnload();
  const second = await recharge();
  assert.equal(second.data.selectedIdx, 0);
  assert.equal(second.data.payAmountText, '待确认');
  responder = (req) => {
    if (req.url.endsWith('/recharge-plans')) return reply(req, [cheap, plan]);
    if (req.url.endsWith('/recharge-orders')) return reply(req, { ...order, priceFen: 5000, basePoints: 500, bonusPoints: 0 });
    if (req.url.endsWith('/' + order.id)) return reply(req, { ...order, priceFen: 5000, basePoints: 500, bonusPoints: 0 });
    defaultReply(req);
  };
  second.confirmPay(); await flush(); second.onUnload();
  const third = await recharge();
  assert.equal(third.data.payAmountText, '¥50');
  assert.equal(third.data.payPointsText, '500');
});

test('建单006明确套餐不可用且未落单，清除意图后可以重选套餐', async () => {
  const other = { ...plan, id: 'other-plan', priceFen: 2000, basePoints: 200, bonusPoints: 0 };
  const creates = [];
  responder = (req) => {
    if (req.url.endsWith('/recharge-plans')) return reply(req, [plan, other]);
    if (req.method === 'POST' && req.url.endsWith('/recharge-orders')) {
      creates.push(req);
      if (creates.length === 1) return reply(req, null, 1120000006, 200, '充值套餐不可用，请重新选择');
    }
    defaultReply(req);
  };
  const page = await recharge();
  page.confirmPay(); await flush();
  assert.equal(page.data.hasPendingOrder, false);
  assert.equal(wx.getStorageSync('zs-wallet-intent-v2:1:post-A'), '');
  assert.match(page.data.resultText, /套餐不可用/);
  page.dismissResult();
  page.tapPlan({ currentTarget: { dataset: { idx: 1 } } });
  page.confirmPay(); await flush();
  assert.equal(creates[1].data.planId, other.id);
  assert.notEqual(creates[1].data.idempotencyKey, creates[0].data.idempotencyKey);
});

for (const code of [1120000005, 409, 503, -1]) {
  test('建单' + code + '不证明无落单，保留套餐与稳定key', async () => {
    const page = await recharge();
    const creates = [];
    responder = (req) => {
      if (req.method === 'POST' && req.url.endsWith('/recharge-orders')) {
        creates.push(req);
        if (code === -1) return req.fail({ errMsg: 'request:fail timeout' });
        return reply(req, null, code, 200, '请求未完成');
      }
      defaultReply(req);
    };
    page.confirmPay(); await flush();
    const saved = { ...wx.getStorageSync('zs-wallet-intent-v2:1:post-A') };
    assert.equal(page.data.hasPendingOrder, true);
    page.dismissResult(); page.confirmPay(); await flush();
    assert.deepEqual(creates[1].data, creates[0].data);
    assert.deepEqual({ ...wx.getStorageSync('zs-wallet-intent-v2:1:post-A') }, saved);
  });
}

for (const code of [1120000005, 1120000006, 409]) {
  test('支付参数' + code + '不清原单，恢复仅换code和attempt', async () => {
    const page = await recharge();
    responder = (req) => req.url.endsWith('/pay-params') ? reply(req, null, code, 200, '支付参数未就绪') : defaultReply(req);
    page.confirmPay(); await flush();
    const saved = { ...wx.getStorageSync('zs-wallet-intent-v2:1:post-A') };
    assert.equal(saved.orderId, order.id);
    assert.equal(page.data.hasPendingOrder, true);
    page.continuePay(); await flush();
    const attempts = requests.filter((r) => r.url.endsWith('/pay-params'));
    assert.equal(attempts.length, 2);
    assert.notEqual(attempts[0].data.code, attempts[1].data.code);
    assert.notEqual(attempts[0].data.idempotencyKey, attempts[1].data.idempotencyKey);
    assert.deepEqual({ ...wx.getStorageSync('zs-wallet-intent-v2:1:post-A') }, saved);
    assert.equal(requests.filter((r) => r.method === 'POST' && r.url.endsWith('/recharge-orders')).length, 1);
  });
}

test('本地持久化失败阻止建单，避免失去稳定key；当前原单仍可保留', async () => {
  const page = await recharge();
  wx.setStorageSync = () => { throw new Error('disk full'); };
  page.confirmPay(); await flush();
  assert.equal(requests.some((r) => r.method === 'POST'), false);
  assert.equal(page.data.paying, false);
  assert.match(page.data.resultText, /保存/);
});

for (const stage of ['建单', '支付参数']) {
  for (const outcome of ['成功', '失败']) {
    test(stage + '迟到' + outcome + '不能在切岗后回填或调用微信支付', async () => {
      const page = await recharge();
      let held;
      responder = (req) => {
        if (req.method === 'POST' && (stage === '建单' ? req.url.endsWith('/recharge-orders') : req.url.endsWith('/pay-params'))) held = req;
        else defaultReply(req);
      };
      page.confirmPay(); await flush();
      assert.ok(held);
      http.saveSession({ postId: 'post-B' });
      page.onShow(); await flush();
      const updates = page.updates.length;
      if (outcome === '成功') reply(held, stage === '建单' ? order : params);
      else held.fail({ errMsg: 'request:fail timeout' });
      await flush();
      assert.equal(payments.length, 0);
      assert.equal(page.updates.length, updates);
      assert.equal(page.data.resultOrderId, '');
      assert.equal(page.data.hasPendingOrder, false);
    });
  }
}

test('支付参数超时后不自动重试，原单查失败也不重建或重新取code', async () => {
  const page = await recharge();
  responder = (req) => req.url.endsWith('/pay-params') ? req.fail({ errMsg: 'request:fail timeout' }) : defaultReply(req);
  page.confirmPay(); await flush();
  const posts = requests.filter((r) => r.method === 'POST').length;
  assert.equal(posts, 2);
  assert.equal(timers.length, 0);
  assert.equal(page.data.canContinuePay, true);
  responder = (req) => req.fail({ errMsg: 'request:fail timeout' });
  page.continuePay(); await flush();
  assert.equal(logins.length, 1);
  assert.equal(requests.filter((r) => r.method === 'POST').length, posts);
  assert.equal(payments.length, 0);
});

test('微信登录失败后保留原单，明确 CLOSED/BLOCKED 不可再次支付', async () => {
  const page = await recharge();
  wx.login = (opts) => opts.fail({ errMsg: 'private auth failure' });
  page.confirmPay(); await flush();
  assert.match(page.data.resultText, /微信登录失败/);
  assert.equal(requests.some((r) => r.url.endsWith('/pay-params')), false);
  assert.equal(page.data.canContinuePay, true);
  responder = (req) => reply(req, { ...order, status: 'CLOSED' });
  page.continuePay(); await flush();
  assert.equal(page.data.canContinuePay, false);
  assert.equal(page.data.hasPendingOrder, false);
  assert.equal(payments.length, 0);
});
