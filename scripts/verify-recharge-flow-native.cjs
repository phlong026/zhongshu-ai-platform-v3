/**
 * 设计点钱包·纯逻辑验收（零 wx、零网络）：
 * wallet-view 纯函数——套餐视图/角标/支付栏/支付路由/订单与流水文案/资产卡状态机。
 * 契约对照：yudao-module-commerce AppRecharge*Controller VO 字段。
 */
const view = require('../miniprogram/utils/wallet-view');

let passed = 0;
let failed = 0;
function ok(cond, name) {
  if (cond) { passed += 1; console.log('  ok - ' + name); } else { failed += 1; console.log('  FAIL - ' + name); }
}

// ---- 金额/点数格式 ----
ok(view.formatThousands(1860) === '1,860', '千分位：1860 → 1,860');
ok(view.formatThousands(-120) === '-120', '负数点数不带千分位干扰');
ok(view.formatThousands(1200) === '1,200', '千分位：1200 → 1,200');
ok(view.formatYuan(10000) === '¥100', '分转元：10000 → ¥100（整元无小数）');
ok(view.formatYuan(10050) === '¥100.50', '分转元：10050 → ¥100.50');

// ---- 赠送信息 ----
const bonus = view.bonusInfo(1000, 200);
ok(bonus && bonus.label === '+赠 200' && bonus.percent === 20, '赠送信息：+赠 200 · 20%');
ok(view.bonusInfo(100, 0) === null, '无赠送返回 null');

// ---- 角标规则：后台推荐优先；赠送比例最高且未被推荐占用的为最划算 ----
const plans = [
  { planId: '1', amountCents: 1000, basePoints: 100, bonusPoints: 0, recommended: false, sort: 1 },
  { planId: '3', amountCents: 10000, basePoints: 1000, bonusPoints: 200, recommended: true, sort: 3 },
  { planId: '5', amountCents: 50000, basePoints: 5000, bonusPoints: 2000, recommended: false, sort: 5 }
];
const views = view.plansView(plans);
ok(views[0].ribbon === null && views[1].ribbon === '推荐' && views[2].ribbon === '最划算', '角标：推荐/最划算 各归其位');
ok(views[1].priceText === '¥100' && views[1].pointsText === '1,200', '套餐卡：¥100 到账 1,200');
ok(view.ribbonFor({ recommended: false, basePoints: 100, bonusPoints: 0 }, [{ basePoints: 100, bonusPoints: 0 }]) === null, '单套餐无赠送不造角标');

// ---- 底部支付栏 ----
const bar = view.payBar(plans, 1);
ok(bar.amountText === '¥100' && bar.pointsText === '1,200', '支付栏：选中推荐位 ¥100/1,200');
ok(view.payBar(plans, -1).amountText === '¥--', '未选中占位 ¥--');

// ---- 支付路由（payRoute）----
const cap = { virtualPayment: true, wechatPayment: true };
ok(view.payRoute({ payParams: { state: 'SESSION_CODE_REQUIRED' } }, cap) === 'session-code-required', '缺一次性 code → session-code-required');
ok(view.payRoute({ payParams: { paySig: 'x', signData: 'y', mode: 'short_series_goods', signature: 'z' } }, cap) === 'virtual', 'paySig+虚拟支付能力 → virtual');
ok(view.payRoute({ payParams: { paySig: 'x' } }, { virtualPayment: false, wechatPayment: true }) === 'virtual-unsupported', '基础库不支持虚拟支付 → virtual-unsupported');
ok(view.payRoute({ payParams: { package: 'prepay_x' } }, { virtualPayment: false, wechatPayment: true }) === 'wechat', '商户号通道参数 → wechat');
ok(view.payRoute({}, cap) === 'poll', 'stub 无支付参数 → 直接查单轮询');

// ---- 订单状态文案（paymentState × fulfillmentState）----
ok(view.orderStatusText({ fulfillmentState: 'CREDITED' }) === '已到账', 'CREDITED → 已到账');
ok(view.orderStatusText({ paymentState: 'SUCCEEDED', fulfillmentState: 'PENDING' }) === '到账中', '已支付未到账 → 到账中');
ok(view.orderStatusText({ paymentState: 'CREATED', fulfillmentState: 'NOT_READY' }) === '待支付', 'CREATED → 待支付');
ok(view.orderStatusText({ paymentState: 'FAILED' }) === '支付失败', 'FAILED → 支付失败');
ok(view.orderStatusText({ paymentState: 'CLOSED' }) === '已关闭', 'CLOSED → 已关闭');

// ---- 订单/流水视图 ----
const orderVo = { orderId: 1002, amountCents: 10000, basePoints: 1000, bonusPoints: 200, paymentState: 'SUCCEEDED', fulfillmentState: 'PENDING', createdAt: '2026-11-01T10:20:30' };
const order = view.orderView(orderVo);
ok(order.orderId === '1002' && order.amountText === '¥100' && order.pointsText === '1,200', '订单视图：金额/点数');
ok(order.statusText === '到账中' && !order.credited && !order.closable, '订单视图：已付订单不可继续支付');
const created = view.orderView({ orderId: 1003, amountCents: 1000, basePoints: 100, bonusPoints: 0, paymentState: 'CREATED', fulfillmentState: 'NOT_READY', createdAt: '2026-11-01T09:00:00' });
ok(created.statusText === '待支付' && created.closable, '订单视图：待支付订单可继续支付');
const ledger = view.ledgerView({ type: 'RECHARGE_BONUS_CREDIT', delta: 200, balanceAfter: 1200, createdAt: '2026-11-01T10:20:30' });
ok(ledger.title === '充值赠送' && ledger.deltaText === '+200' && ledger.positive && ledger.balanceText === '1,200', '流水视图：赠送入账');
const debit = view.ledgerView({ type: 'FLAT_GENERATION_DEBIT', delta: -30, balanceAfter: 1170 });
ok(debit.title === '平面方案生成' && debit.deltaText === '-30' && !debit.positive, '流水视图：生成扣减');
ok(view.formatTime('2026-11-01T10:20:30') === '11-01 10:20', '时间文案：MM-DD hh:mm');

// ---- 资产卡状态机 ----
ok(view.cardState(false, 't', { availablePoints: 1 }) === 'hidden', '未登录 → hidden');
ok(view.cardState(true, '', { availablePoints: 1 }) === 'hidden', '无钱包会话 → hidden');
ok(view.cardState(true, 't', { availablePoints: 1 }, new Error('x')) === 'hidden', '查询失败 → hidden');
ok(view.cardState(true, 't', {}) === 'hidden', '账户缺字段 → hidden');
ok(view.cardState(true, 't', { availablePoints: 1860 }) === 'ready', '正常 → ready');

console.log('\n共 ' + (passed + failed) + ' 项：通过 ' + passed + '，失败 ' + failed);
process.exit(failed ? 1 : 0);
