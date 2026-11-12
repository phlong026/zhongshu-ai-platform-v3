/** B 钱包视图回归：金额、角标、支付栏保持原 UI；输入按 platform-wallet 冻结 DTO。 */
const view = require('../miniprogram/utils/wallet-view');
let passed = 0;
let failed = 0;
function ok(cond, name) {
  if (cond) { passed += 1; console.log('  ok - ' + name); } else { failed += 1; console.log('  FAIL - ' + name); }
}

ok(view.formatThousands(1860) === '1,860', '千分位：1860 → 1,860');
ok(view.formatThousands(-120) === '-120', '负数点数');
ok(view.formatThousands(1200) === '1,200', '千分位：1200 → 1,200');
ok(view.formatYuan(10000) === '¥100', '分转元：整元无小数');
ok(view.formatYuan(10050) === '¥100.50', '分转元：保留两位');
const bonus = view.bonusInfo(1000, 200);
ok(bonus && bonus.label === '+赠 200' && bonus.percent === 20, '赠送信息');
ok(view.bonusInfo(100, 0) === null, '无赠送');
const plans = [
  { id: '1', priceFen: 1000, basePoints: 100, bonusPoints: 0 },
  { id: '9007199254740999', priceFen: 10000, basePoints: 1000, bonusPoints: 200 },
  { id: '5', priceFen: 50000, basePoints: 5000, bonusPoints: 2000 }
];
const views = view.plansView(plans);
ok(views[0].ribbon === null && views[1].ribbon === null && views[2].ribbon === '最划算', '未下发推荐字段不虚构推荐');
ok(views[1].priceText === '¥100' && views[1].pointsText === '1,200', '套餐卡金额和点数');
ok(views[1].planId === '9007199254740999', '字符串ID不丢精度');
ok(view.ribbonFor({ basePoints: 100, bonusPoints: 0 }, [{ basePoints: 100, bonusPoints: 0 }]) === null, '无赠送不造角标');
const bar = view.payBar(plans, 1);
ok(bar.amountText === '¥100' && bar.pointsText === '1,200', '支付栏');
ok(view.payBar(plans, -1).amountText === '¥--', '未选中占位');
const cap = { virtualPayment: true };
ok(view.payRoute({ payParams: { paySig: 'x', signData: 'y', mode: 'short_series_goods', signature: 'z' } }, cap) === 'virtual', '完整参数走虚拟支付');
ok(view.payRoute({ payParams: { paySig: 'x', signData: 'y', mode: 'short_series_goods', signature: 'z' } }, {}) === 'virtual-unsupported', '缺能力明确不支持');
ok(view.payRoute({ payParams: { paySig: 'x' } }, cap) === 'unavailable', '参数不全禁止调支付');
ok(view.payRoute({}, cap) === 'unavailable', '无参数不能伪装支付成功');
ok(view.orderStatusText({ creditState: 'CREDITED' }) === '已到账', 'CREDITED 已到账');
ok(view.orderStatusText({ status: 'SUCCEEDED', creditState: 'PENDING' }) === '待入账', '支付与到账分开');
ok(view.orderStatusText({ status: 'CREATED', creditState: 'NOT_READY' }) === '待支付', 'CREATED 待支付');
ok(view.orderStatusText({ status: 'FAILED' }) === '支付失败', 'FAILED 支付失败');
ok(view.orderStatusText({ status: 'CLOSED' }) === '已关闭', 'CLOSED 已关闭');
ok(view.orderStatusText({ status: 'UNKNOWN' }) === '支付结果确认中', 'UNKNOWN 不认定失败');
ok(view.orderStatusText({ status: 'SUCCEEDED', creditState: 'BLOCKED' }) === '入账已冻结', 'BLOCKED 冻结');
const order = view.orderView({ id: '1002', orderNo: 'PFW1002', priceFen: 10000, basePoints: 1000, bonusPoints: 200, status: 'SUCCEEDED', creditState: 'PENDING', createTime: '2026-11-01T10:20:30' });
ok(order.orderId === '1002' && order.amountText === '¥100' && order.pointsText === '1,200', '订单金额和点数');
ok(order.orderNo === 'PFW1002', '显示业务单号');
ok(order.statusText === '待入账' && !order.credited && !order.closable, '已支付不可续付');
const created = view.orderView({ id: '1003', priceFen: 1000, basePoints: 100, status: 'CREATED', creditState: 'NOT_READY' });
ok(created.statusText === '待支付' && created.closable, '待支付可继续');
const ledger = view.ledgerView({ id: '9007199254740999', type: 'RECHARGE_BONUS_CREDIT', delta: 200, availableAfter: 1200, createTime: '2026-11-01T10:20:30' });
ok(ledger.title === '充值赠送' && ledger.deltaText === '+200' && ledger.positive && ledger.balanceText === '1,200', '流水赠送及可用余额');
ok(ledger.id === '9007199254740999', '流水字符串ID');
const debit = view.ledgerView({ type: 'FLAT_GENERATION_DEBIT', delta: -30, availableAfter: 1170 });
ok(debit.title === '平面方案生成' && debit.deltaText === '-30' && !debit.positive, '流水扣减');
ok(view.formatTime('2026-11-01T10:20:30') === '11-01 10:20', '时间文案');
ok(view.cardState(false, 'post', { availablePoints: 1 }) === 'hidden', '未登录隐藏');
ok(view.cardState(true, '', { availablePoints: 1 }) === 'hidden', '无岗位隐藏');
ok(view.cardState(true, 'post', { availablePoints: 1 }, new Error('x')) === 'hidden', '查询失败隐藏');
ok(view.cardState(true, 'post', {}) === 'hidden', '账户缺字段隐藏');
ok(view.cardState(true, 'post', { availablePoints: 1860 }) === 'ready', '正常显示');
console.log('\n共 ' + (passed + failed) + ' 项：通过 ' + passed + '，失败 ' + failed);
process.exit(failed ? 1 : 0);
