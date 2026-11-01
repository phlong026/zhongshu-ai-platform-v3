/**
 * 设计点钱包·前后端支付联动测试（HTTP 全链路）：
 * 启动本地钱包服务（server/wallet，镜像 monorepo commerce 模块真实契约），
 * 经小程序真实传输层（api/http.js + api/wallet.js）驱动：
 * 桥接会话 → 套餐 → 建单(幂等/一次性code) → 支付参数 → dev 结算(模拟微信回调) → 到账 → 流水 → 恢复支付拒绝。
 */
const nodeHttp = require('http');
const server = require('../server/wallet/server.cjs');

let passed = 0;
let failed = 0;
function ok(cond, name) {
  if (cond) { passed += 1; console.log('  ok - ' + name); } else { failed += 1; console.log('  FAIL - ' + name); }
}

// ---- wx shim：storage + request → 本地 node http ----
const store = {};
global.wx = {
  getStorageSync(k) { return store[k] !== undefined ? store[k] : ''; },
  setStorageSync(k, v) { store[k] = v; },
  removeStorageSync(k) { delete store[k]; },
  showToast() {},
  navigateTo() {},
  login(opts) { opts.success && opts.success({ code: 'shim-login-code' }); },
  request(opts) {
    const u = new URL(opts.url);
    const req = nodeHttp.request({
      hostname: u.hostname,
      port: u.port,
      path: u.pathname + u.search,
      method: opts.method,
      headers: opts.header
    }, (res) => {
      let raw = '';
      res.on('data', (c) => { raw += c; });
      res.on('end', () => {
        let data = null;
        try { data = JSON.parse(raw); } catch (e) { /* 非 JSON 按原样上抛 */ }
        opts.success && opts.success({ statusCode: res.statusCode, data: data });
      });
    });
    req.on('error', (e) => opts.fail && opts.fail({ errMsg: 'request:fail ' + e.message }));
    if (opts.data) req.write(typeof opts.data === 'string' ? opts.data : JSON.stringify(opts.data));
    req.end();
  }
};

const http = require('../miniprogram/api/http');
const wallet = require('../miniprogram/api/wallet');
const view = require('../miniprogram/utils/wallet-view');

function devSettle(orderId) {
  return new Promise((resolve, reject) => {
    const req = nodeHttp.request({
      hostname: '127.0.0.1',
      port: addr.port,
      path: '/app-api/design/v1/dev/settle',
      method: 'POST',
      headers: { 'content-type': 'application/json', Authorization: 'Bearer ' + server.WALLET_TOKEN }
    }, (res) => {
      let raw = '';
      res.on('data', (c) => { raw += c; });
      res.on('end', () => resolve(JSON.parse(raw)));
    });
    req.on('error', reject);
    req.write(JSON.stringify({ orderId: String(orderId) }));
    req.end();
  });
}

let addr = null;

async function main() {
  addr = await server.listen(0, '127.0.0.1');
  // 平台会话已登录；指向本地钱包服务
  wx.setStorageSync(http.TOKEN_KEY, 'platform-demo-token');
  wx.setStorageSync('zs-api-base', 'http://127.0.0.1:' + addr.port + '/admin-api');

  // 1. 桥接会话
  const token = await wallet.ensureSession();
  ok(token === server.WALLET_TOKEN, '桥接会话：平台 Bearer 换取钱包 token');

  // 2. 套餐
  const plans = await wallet.listPlans();
  ok(Array.isArray(plans) && plans.length === 6, '套餐列表：6 档');
  ok(plans.every((p) => p.planId && p.amountCents > 0 && p.basePoints >= 0), '套餐字段：planId/amountCents/basePoints 齐备');
  const views = view.plansView(plans);
  const recIdx = views.findIndex((v) => v.ribbon === '推荐');
  ok(recIdx >= 0 && views[recIdx].priceText === '¥100' && views[recIdx].pointsText === '1,200', '默认选中推荐位 ¥100/1,200');

  // 3. 建单（含一次性 code）
  const idem1 = 'recharge-test-1';
  const order1 = await wallet.createOrder('3', idem1, 'wx-code-1');
  ok(String(order1.orderId) && order1.paymentState === 'CREATED', '建单：CREATED');
  ok(order1.payParams && order1.payParams.paySig && order1.payParams.mode === 'short_series_goods', '支付参数：paySig + mode 下发');
  const signData = JSON.parse(order1.payParams.signData);
  ok(signData.outTradeNo === String(order1.orderId) && signData.goodsPrice === 10000, 'signData：订单号/金额与订单一致（分）');

  // 4. 幂等：同键同单，不同键新单
  const order1again = await wallet.createOrder('3', idem1, 'wx-code-1');
  ok(String(order1again.orderId) === String(order1.orderId), '幂等：同 Idempotency-Key 返回同订单');
  const order2 = await wallet.createOrder('3', 'recharge-test-2', 'wx-code-2');
  ok(String(order2.orderId) !== String(order1.orderId), '不同幂等键 → 新订单');

  // 5. 恢复支付：重领签名
  const reParams = await wallet.getPayParams(String(order2.orderId), 'wx-code-3');
  ok(reParams && reParams.paySig, '恢复支付：pay-params 重领签名');

  // 6. 结算（模拟微信发货回调后服务端到账）
  const settleRes = await devSettle(order2.orderId);
  ok(settleRes.data && settleRes.data.fulfillmentState === 'CREDITED', 'dev 结算：fulfillment=CREDITED');
  const settleRes2 = await devSettle(order2.orderId);
  ok(settleRes2.data && settleRes2.data.fulfillmentState === 'CREDITED', 'dev 结算幂等：重复结算不重复入账');

  // 7. 订单详情以服务端状态为准
  const paid = await wallet.getOrder(String(order2.orderId));
  ok(paid.paymentState === 'SUCCEEDED' && paid.fulfillmentState === 'CREDITED', '订单状态：SUCCEEDED + CREDITED');
  ok(view.orderStatusText(paid) === '已到账', '状态文案：已到账');

  // 8. 点数账户与流水（plan3 = 1000 + 200）
  const account = await wallet.pointAccount();
  ok(account.availablePoints === 1200, '点数账户：到账 1,200');
  const ledger = await wallet.pointLedger(1, 20);
  ok(ledger.total === 2 && ledger.list.length === 2, '流水：基础/赠送两笔');
  const types = ledger.list.map((l) => l.type).sort();
  ok(types[0] === 'RECHARGE_BASE_CREDIT' && types[1] === 'RECHARGE_BONUS_CREDIT', '流水类型：RECHARGE_BASE/BONUS_CREDIT');
  const ledgerViews = ledger.list.map(view.ledgerView);
  ok(ledgerViews[0].deltaText === '+200' && ledgerViews[0].balanceText === '1,200', '流水视图：赠送 +200 余额 1,200');

  // 9. 已支付订单禁止重领签名（409）
  let rejected = false;
  await wallet.getPayParams(String(order2.orderId), 'wx-code-4').catch((err) => { rejected = err.code === 409; });
  ok(rejected, '已支付订单重领签名 → 409');

  // 10. 钱包 token 失效：自动经桥接重取一次并重放成功
  wallet.saveWalletSession('expired-token');
  const after = await wallet.getOrder(String(order1.orderId));
  ok(after && String(after.orderId) === String(order1.orderId), 'token 失效自愈：401 → 重取会话 → 重放');

  // 11. 钱包 401 不清平台会话（skipSessionGuard）
  ok(wx.getStorageSync(http.TOKEN_KEY) === 'platform-demo-token', '平台会话未被钱包 401 误清');

  // 11b. 桥接缺 X-WX-Login-Code → 1_104_000_001（09 文档 §三）
  let missingCode = null;
  await http.request('POST', '/platform/wallet/session', null, null, { skipSessionGuard: true })
    .catch((err) => { missingCode = err.code; });
  ok(missingCode === 1_104_000_001, '桥接缺一次性 code → 1_104_000_001');

  // 11c. 缓存条目带指纹 {fp, token}：切岗（postId 变化）后旧缓存失效、自动重铸
  const rawCache = JSON.parse(wx.getStorageSync(wallet.SESSION_KEY));
  ok(typeof rawCache.fp === 'string' && rawCache.fp.indexOf(':') > 0 && rawCache.token === server.WALLET_TOKEN,
      '缓存条目：{fp, token}（fp=会话代次+岗位）');
  wx.setStorageSync(http.POST_KEY, '999'); // 模拟切岗
  ok(wallet.walletToken() === '', '切岗后旧组织缓存即失效');
  const reTok = await wallet.ensureSession();
  ok(reTok === server.WALLET_TOKEN, '切岗后 ensureSession 自动重铸');
  wx.setStorageSync(http.POST_KEY, ''); // 复位

  // 12. 桥接端点未部署（404）→ ensureSession 返回 null（页面据此降级，不报错打扰）
  const dead = nodeHttp.createServer((req2, res2) => { res2.writeHead(404, { 'Content-Type': 'application/json' }); res2.end('{"code":404,"msg":"not found"}'); });
  await new Promise((r) => dead.listen(0, '127.0.0.1', r));
  const deadPort = dead.address().port;
  wx.setStorageSync('zs-api-base', 'http://127.0.0.1:' + deadPort + '/admin-api');
  wallet.clearWalletSession();
  const none = await wallet.ensureSession();
  ok(none === null, '桥接未部署(404) → null（降级不报错）');
  wx.setStorageSync('zs-api-base', 'http://127.0.0.1:' + addr.port + '/admin-api');
  dead.close();

  console.log('\n共 ' + (passed + failed) + ' 项：通过 ' + passed + '，失败 ' + failed);
  server.close();
  process.exit(failed ? 1 : 0);
}

main().catch((err) => {
  console.error('联动测试异常中断：', err);
  server.close();
  process.exit(1);
});
