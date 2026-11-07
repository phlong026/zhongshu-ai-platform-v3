/**
 * 设计点钱包视图纯函数：充值套餐卡片/底部支付栏/流水文案/订单状态文案/支付路由判定。
 * 全部无副作用（零 wx、零网络），供页面与 verify 脚本共用（金额单位全程「分」，点数「个」）。
 */

const LEDGER_TITLES = {
  RECHARGE_BASE_CREDIT: '充值到账',
  RECHARGE_BONUS_CREDIT: '充值赠送',
  FLAT_GENERATION_DEBIT: '平面方案生成',
  ELEVATION_GENERATION_DEBIT: '立面方案生成',
  BUDGET_ESTIMATE_DEBIT: '预算估算',
  AI_PROMPT_DEBIT: 'AI 提问',
  TASK_SETTLEMENT_REFUND: '任务结算退回',
  MANUAL_CREDIT: '手工调入',
  MANUAL_DEBIT: '手工调出',
  RECHARGE_BASE_REVERSAL: '充值冲正',
  RECHARGE_BONUS_REVERSAL: '赠送冲正'
};

/** 1,860 千分位（不依赖 toLocaleString，测试环境确定性一致） */
function formatThousands(n) {
  const sign = n < 0 ? '-' : '';
  const digits = String(Math.abs(Math.trunc(Number(n) || 0)));
  return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** 分 → 展示金额；整元不带小数，其余保留两位 */
function formatYuan(cents) {
  const value = Number(cents) || 0;
  const yuan = value / 100;
  if (value % 100 === 0) return '¥' + formatThousands(yuan);
  return '¥' + yuan.toFixed(2);
}

/** 赠送信息：bonus>0 返回 {label, percent}，否则 null（percent 按基础点取整） */
function bonusInfo(basePoints, bonusPoints) {
  const base = Number(basePoints) || 0;
  const bonus = Number(bonusPoints) || 0;
  if (bonus <= 0 || base <= 0) return null;
  return {
    label: '+赠 ' + formatThousands(bonus),
    percent: Math.min(999, Math.round((bonus / base) * 100))
  };
}

/**
 * 套餐角标：后台 recommended 标记优先显示「推荐」；赠送比例最高且未被推荐占用的显示「最划算」。
 * 数据未达标（无赠送/单套餐）不造角标。
 */
function ribbonFor(plan, plans) {
  if (plan.recommended) return '推荐';
  const best = (plans || []).reduce((acc, item) => {
    const info = bonusInfo(item.basePoints, item.bonusPoints);
    return info && (!acc || info.percent > acc.percent) ? info : acc;
  }, null);
  const mine = bonusInfo(plan.basePoints, plan.bonusPoints);
  if (mine && best && mine.percent === best.percent && (plans || []).length > 1) return '最划算';
  return null;
}

/** 套餐卡片视图（排序按后台 sort 升序，稳定） */
function plansView(plans) {
  return (plans || []).slice()
    .sort((a, b) => (Number(a.sort) || 0) - (Number(b.sort) || 0))
    .map((p) => ({
      planId: String(p.planId),
      name: p.name || '',
      priceText: formatYuan(p.amountCents),
      pointsText: formatThousands((Number(p.basePoints) || 0) + (Number(p.bonusPoints) || 0)),
      bonus: bonusInfo(p.basePoints, p.bonusPoints),
      ribbon: ribbonFor(p, plans)
    }));
}

/** 选中套餐后的底部支付栏：{amountText, pointsText}；未选中返回占位 */
function payBar(plans, selectedIdx) {
  const view = plansView(plans);
  const picked = view[Number(selectedIdx)];
  if (!picked) return { amountText: '¥--', pointsText: '--' };
  return { amountText: picked.priceText, pointsText: picked.pointsText };
}

/**
 * 支付路由判定（后端契约：payParams.paySig=虚拟支付；payParams.state=SESSION_CODE_REQUIRED 表示
 * 建单时缺一次性 code 无法签名）。客户端能力不足（基础库不支持 requestVirtualPayment）回退
 * 商户号微信支付；两者皆缺或 stub 无 payParams 直接进查单轮询。
 */
function payRoute(order, capability) {
  const cap = capability || {};
  const params = order && order.payParams;
  if (params && params.state === 'SESSION_CODE_REQUIRED') return 'session-code-required';
  if (params && params.paySig && cap.virtualPayment) return 'virtual';
  if (params && params.paySig && !cap.virtualPayment && cap.wechatPayment) return 'virtual-unsupported';
  if (params && params.package) return 'wechat';
  return 'poll';
}

/** 订单状态文案（paymentState + fulfillmentState 两套状态联合表达） */
function orderStatusText(order) {
  const pay = order && order.paymentState;
  const fulfill = order && order.fulfillmentState;
  if (fulfill === 'CREDITED') return '已到账';
  if (pay === 'SUCCEEDED') return '到账中';
  if (pay === 'FAILED') return '支付失败';
  if (pay === 'CLOSED') return '已关闭';
  if (pay === 'PENDING') return '支付确认中';
  return '待支付';
}

/** 流水文案：type→中文标题、delta→带符号点数 */
function ledgerView(item) {
  const delta = Number(item.delta) || 0;
  return {
    title: LEDGER_TITLES[item.type] || item.type || '点数变动',
    deltaText: (delta > 0 ? '+' : '') + formatThousands(delta),
    positive: delta > 0,
    balanceText: formatThousands(Number(item.balanceAfter) || 0),
    timeText: formatTime(item.createdAt)
  };
}

/** 订单列表项视图 */
function orderView(order) {
  const total = (Number(order.basePoints) || 0) + (Number(order.bonusPoints) || 0);
  const bonus = bonusInfo(order.basePoints, order.bonusPoints);
  return {
    orderId: String(order.orderId),
    amountText: formatYuan(order.amountCents),
    pointsText: formatThousands(total),
    bonusText: bonus ? bonus.label : '',
    statusText: orderStatusText(order),
    credited: order.fulfillmentState === 'CREDITED',
    closable: order.paymentState === 'CREATED' || order.paymentState === 'PENDING',
    timeText: formatTime(order.createdAt)
  };
}

/** ISO/时间戳 → 'MM-DD hh:mm'（createdAt 后端为无时区 LocalTime 序列化，按字面截取） */
function formatTime(value) {
  if (!value) return '';
  const text = String(value);
  const m = text.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  if (m) return m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5];
  return text;
}

/**
 * 资产卡可见性状态机（组件与 verify 共用）：
 * hidden=不渲染（未登录/无钱包会话/查询失败）；ready=正常展示余额。
 */
function cardState(platformLoggedIn, walletToken, account, accountError) {
  if (!platformLoggedIn) return 'hidden';
  if (!walletToken) return 'hidden';
  if (accountError) return 'hidden';
  if (!account || account.availablePoints === undefined || account.availablePoints === null) return 'hidden';
  return 'ready';
}

module.exports = {
  formatThousands,
  formatYuan,
  bonusInfo,
  ribbonFor,
  plansView,
  payBar,
  payRoute,
  orderStatusText,
  ledgerView,
  orderView,
  formatTime,
  cardState
};
