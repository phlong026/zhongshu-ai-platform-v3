const {day:financeDay}=require('./finance-date');
/**
 * 平台财务视图模型（纯函数，零 wx/Storage 依赖——隔离验收依赖此约束）。
 * 五态判定 + 台账合并 + 指标聚合 + 登记表单映射 + 封存入口过滤。
 * 契约见 docs/backend-parity/03-finance-desk接入-需求与验收.md。
 */
const FIN_ROLES = ['finance', 'finance-lead'];

/** 五态判定 */
function pageMode(post) {
  if (!post || !post.roleCode) return 'guest';
  return FIN_ROLES.includes(post.roleCode) ? 'finance' : 'non-finance';
}

/** 金额：分 → 元两位小数字符串（null/非法 → ''） */
function yuan(cents) {
  if (typeof cents !== 'number' || !Number.isSafeInteger(cents) || cents <= 0) return '';
  return (cents / 100).toFixed(2);
}

/** API 财务工作台快捷入口：登记类直接打开真实 API 表单，流水入口只切平台台账。 */
function shortcuts(headquarters) {
  const ledger = { label: '收支流水', icon: '/assets/book.png', url: '/pages/admin/finance-desk/index?view=flows&tab=ledger' };
  const entry = (label, type) => ({ label, type, icon: '/assets/money.png', url: '' });
  return headquarters
    ? [entry('确认到账', 'receipt'), entry('登记退款', 'refund'), ledger]
    : [entry('收款登记', 'receipt'), entry('付款登记', 'payment'), entry('报销办理', 'reimbursement'), ledger];
}

/** 登记金额：元字符串 → 分（严格十进制，拒绝逗号/超两位小数/科学计数法/非正数/超上限；非法 → null） */
function toCents(input) {
  const s = String(input == null ? '' : input).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const cents = Number(Math.round(parseFloat(s) * 100));
  if (!Number.isSafeInteger(cents) || cents <= 0 || cents > 1000000000000) return null;
  return cents;
}

/** 台账行合并（收款+/付款−/客户退款−/支出退回+），月度过滤。
 * M08-T05/R04：业务发生日期为空 = 日期待核 → date 标记「待归月」单列展示，
 * 不回退 create_time（R04 禁止以录入时间冒充发生日期），且 monthOf 永不匹配任何月——
 * 不进入月度过滤与指标净额；核实补正后经修订链填入真实日期即回归月度。
 * M08-T08：退款按方向分流——client=客户退款（流出−）；expense=支出退回（供应商/劳务回流+）。 */
function ledgerRows(receipts, payments, refunds, month) {
  const monthOf = r => financeDay(r.receivedDate || r.payDate || r.date).slice(0, 7);
  const rows = [];
  for (const r of receipts || []) {
    if (r.status !== '已登记') continue;
    const date = financeDay(r.receivedDate) || '待归月';
    rows.push({ key: 'r-' + r.id, type: '收款', sign: '+', name: r.name, amountCents: r.amountCents,
      date, pendingDate: !r.receivedDate, status: r.status, id: r.id,
      revisionVersion: r.revisionVersion });
  }
  for (const p of payments || []) {
    if (p.status !== '已核实') continue;
    const date = financeDay(p.payDate) || '待归月';
    rows.push({ key: 'p-' + p.id, type: p.fundingType === 'EMPLOYEE' ? '员工垫付' : '付款', sign: '−', name: '付款', amountCents: p.amountCents,
      date, pendingDate: !p.payDate, status: p.status, id: p.id });
  }
  for (const rf of refunds || []) {
    if (rf.status !== '已核实') continue;
    const expense = rf.refundDirection === 'expense';
    const date = financeDay(rf.date) || '待归月';
    rows.push({ key: 'f-' + rf.id, type: expense ? '支出退回' : '退款', sign: expense ? '+' : '−',
      name: expense ? (rf.fundingType==='EMPLOYEE'?'退回员工个人':'支出退回') : '客户退款', amountCents: rf.amountCents,
      date, pendingDate: !rf.date, status: rf.status, id: rf.id });
  }
  const filtered = month ? rows.filter(r => monthOf(r) === month) : rows;
  return { rows: filtered, allCount: rows.length };
}

/** 指标三卡（本月口径；作废不参与）。M08-T05/R04：空业务日期不回退 createTime——
 * 单列待归月，不进已核实月度净额（与 ledgerRows 的 monthOf 语义一致）。
 * M08-T08：refunded 只计 client 方向——expense 支出退回是付款回流，不属于对客退款统计。 */
function metrics(receipts, payments, refunds, month) {
  const inMonth = (r, field) => !!month && financeDay(r[field]).slice(0, 7) === month; // month 空串护栏（Codex R1 边界）
  const received = (receipts || []).filter(r => r.status === '已登记' && inMonth(r, 'receivedDate'))
    .reduce((a, r) => a + (r.amountCents || 0), 0);
  const paid = (payments || []).filter(p => p.status === '已核实' && p.fundingType !== 'EMPLOYEE' && inMonth(p, 'payDate'))
    .reduce((a, p) => a + (p.amountCents || 0), 0);
  const refunded = (refunds || []).filter(r => r.status === '已核实' && r.refundDirection !== 'expense' && inMonth(r, 'date'))
    .reduce((a, r) => a + (r.amountCents || 0), 0);
  const expenseReturned = (refunds || []).filter(r => r.status === '已核实' && r.refundDirection === 'expense' && r.fundingType !== 'EMPLOYEE' && inMonth(r, 'date')).reduce((a,r)=>a+(r.amountCents||0),0);
  const metricYuan = cents => (cents / 100).toFixed(2);
  return {
    received: metricYuan(received),
    paid: metricYuan(paid),
    refunded: metricYuan(refunded), expenseReturned: metricYuan(expenseReturned), net: metricYuan(received + expenseReturned - paid - refunded)
  };
}

/**
 * 应付有效已付投影。payables DTO 不带 paidCents，必须从完整付款/退款事实按 payableId 精确聚合：
 * Σ(已核实付款 amount - pendingLink) - Σ(对应已核实支出退回)。
 * 与后端 EngineeringLedgerQueryAdapter / design settlement 的有效账口径一致；作废记录不参与。
 */
function canAssignPayable(payable) {
  const sourceType = payable && (payable.sourceType ?? payable.source_type);
  const sourceRef = payable && (payable.sourceRef ?? payable.source_ref);
  const status = payable && payable.status;
  // Manual entries can be initially assigned or corrected; the server rejects any upstream engineering source.
  return !!payable && sourceType === 'manual' && (sourceRef == null || sourceRef === '')
    && status !== '已作废' && !payable.deleted;
}

function payableProgress(payables, payments, refunds) {
  if (!Array.isArray(payables)) throw new Error('应付数据不完整，请刷新重试');
  if (!Array.isArray(payments)) throw new Error('付款数据不完整，请刷新重试');
  if (!Array.isArray(refunds)) throw new Error('退款数据不完整，请刷新重试');
  const cents = (value, label, allowZero) => {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < 0 || (!allowZero && number === 0)) {
      throw new Error(label + '金额无效，请刷新重试');
    }
    return number;
  };
  const allPayments = new Map();
  const activePayments = new Map();
  const paidByPayable = new Map();
  for (const payment of payments) {
    if (!payment || payment.id == null) throw new Error('付款数据缺少编号，请刷新重试');
    const paymentId = String(payment.id);
    if (allPayments.has(paymentId)) throw new Error('付款数据重复，请刷新重试');
    allPayments.set(paymentId, payment);
    if (payment.status !== '已核实') continue;
    if (payment.payableId == null) throw new Error('付款数据缺少应付编号，请刷新重试');
    const amount = cents(payment.amountCents, '付款', false);
    const pending = payment.pendingLinkCents == null ? 0 : cents(payment.pendingLinkCents, '待关联', true);
    if (pending > amount) throw new Error('付款待关联金额超过付款金额，请刷新重试');
    const payableId = String(payment.payableId);
    activePayments.set(paymentId, { payableId, pendingRemaining:pending });
    paidByPayable.set(payableId, (paidByPayable.get(payableId) || 0) + amount - pending);
  }
  for (const refund of refunds) {
    if (!refund || refund.status !== '已核实' || refund.refundDirection !== 'expense') continue;
    if (refund.paymentId == null) throw new Error('支出退回缺少付款编号，请刷新重试');
    const paymentId = String(refund.paymentId);
    if (!allPayments.has(paymentId)) throw new Error('支出退回对应付款不存在，请刷新重试');
    const payment = activePayments.get(paymentId);
    if (!payment) continue;
    const amount = cents(refund.amountCents, '支出退回', false);
    const returnedUnallocated=Math.min(amount,payment.pendingRemaining);
    payment.pendingRemaining-=returnedUnallocated;
    paidByPayable.set(payment.payableId, (paidByPayable.get(payment.payableId) || 0) - (amount-returnedUnallocated));
  }
  return payables.map(payable => {
    if (!payable || payable.id == null) throw new Error('应付数据缺少编号，请刷新重试');
    let totalCents = cents(payable.totalCents, '应付', true);
    if(payable.sourceType==='reimbursement'){
      const origin=allPayments.get(String(payable.sourceRef));
      totalCents=origin&&origin.status==='已核实'?origin.amountCents-(refunds||[]).filter(r=>r.status==='已核实'&&r.refundDirection==='expense'&&r.fundingType==='EMPLOYEE'&&String(r.paymentId)===String(origin.id)).reduce((n,r)=>n+r.amountCents,0):0;
    }
    const paidCents = paidByPayable.get(String(payable.id)) || 0;
    const remainingCents = Math.max(0, totalCents - paidCents);
    return { ...payable, totalCents, paidCents, remainingCents,
      canPay: payable.status === '进行中' && remainingCents > 0, canAssign: canAssignPayable(payable) };
  });
}

/** 封存入口过滤：返回新数组（不修改原配置），API 态移除禁入清单入口 */
const SEALED = ['verify', 'correction', 'correction-request', 'correction-approve', 'correction-request-supplement',
  'receipt-supplement', 'payment-supplement', 'payment-review', 'payment-return', 'receipt-return',
  'reconcile', 'finance-transfer', 'cross-evidence', 'cross-query', 'cross-reply', 'cross-attach',
  'refund-request', 'refund-approve', 'refund-reject', 'refund-request-supplement', 'refund-review',
  'refund-return', 'refund-execute', 'performance-appeal-resolve', 'cost-confirm', 'project-close'];
function filterSealedEntries(entries) {
  return (entries || []).filter(e => !SEALED.includes(e && e.action) && !SEALED.includes(e && e.view));
}

/** 错误码 → 提示文案 */
function errorMessage(err) {
  const code = err && err.code;
  const map = {
    1102000005: '超过剩余可退金额，请刷新余额',
    1102000001: '流水号已登记过，请核对原记录',
    1102000009: '流水号已被其他记录占用',
    1102000010: '累计付款超出应付金额',
    1102000006: '仅已登记的收款可以登记退款',
    1102000011: '付款必须关联应付记录',
    1102000013: '应付记录不存在，请刷新选择',
    1102000003: '数据已变化，请刷新后重试',
    1102000016: '流水金额与应收金额不一致',
    1102000017: '应收状态已变化，请刷新后重试',
    1102000018: '没有该操作权限',
    1102000012: '付款记录不存在，请刷新列表',
    1102000030: '支出退回关联付款单不存在，请刷新选择',
    1102000031: '支出退回金额超过该笔付款的剩余可退回金额，请刷新余额',
    1102000032: '仅已核实的付款可以登记支出退回',
    1102000037: '该付款存在有效支出退回，请先作废退回记录'
  };
  if (code && map[code]) return map[code];
  if (code === 403) return '没有该操作权限';
  return (err && err.message) || '操作失败，请重试';
}

// ===================== 付款单撤销（P1 批6） =====================
/** 付款作废角色位：后端岗位矩阵 V20261101.852 仅 finance-lead 持 franchise:fin:payment-void
 * （boss/owner 只持 refund-void）；小程序 current-context 白名单不暴露该码，故按 roleCode 判定。 */
const PAYMENT_VOID_ROLE = 'finance-lead';
const VOID_REASON_MAX = 200;

/** 台账行是否显示「撤销」：角色位 + 行来源=付款 + 状态=已核实 */
function canVoidPayment(roleCode, sourceKind, status) {
  return roleCode === PAYMENT_VOID_ROLE && sourceKind === 'payment' && status === '已核实';
}

/** 撤销原因必填 + 长度上限（后端 void_reason 仅留痕不做长度校验，前端自守） */
function voidReasonError(reason) {
  const value = String(reason == null ? '' : reason).trim();
  if (!value) return '请填写撤销原因';
  if (value.length > VOID_REASON_MAX) return '撤销原因请控制在 ' + VOID_REASON_MAX + ' 字以内';
  return '';
}

/** 撤销成功后的台账联动提示（作废释放额度：台账行消失、应付未付余额恢复） */
function voidNoticeText(row) {
  const cents = row && Number(row.amountCents);
  const amount = Number.isFinite(cents) && cents > 0 ? '¥' + (cents / 100).toFixed(2) : '';
  return '付款已撤销' + (amount ? '（' + amount + '）' : '') + '：该笔作废留痕、不再计入实付，对应应付未付余额同步恢复；台账已刷新。';
}

module.exports = {
  pageMode, yuan, toCents, ledgerRows, metrics, payableProgress, canAssignPayable, shortcuts, filterSealedEntries, errorMessage,
  PAYMENT_VOID_ROLE, VOID_REASON_MAX, canVoidPayment, voidReasonError, voidNoticeText,
  SEALED
};
