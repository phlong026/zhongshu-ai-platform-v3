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

/** 登记金额：元字符串 → 分（严格十进制，拒绝逗号/超两位小数/科学计数法/非正数/超上限；非法 → null） */
function toCents(input) {
  const s = String(input == null ? '' : input).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const cents = Number(Math.round(parseFloat(s) * 100));
  if (!Number.isSafeInteger(cents) || cents <= 0 || cents > 1000000000000) return null;
  return cents;
}

/** 台账行合并（收款+/付款−/退款−），月度过滤。
 * M08-T05/R04：业务发生日期为空 = 日期待核 → date 标记「待归月」单列展示，
 * 不回退 create_time（R04 禁止以录入时间冒充发生日期），且 monthOf 永不匹配任何月——
 * 不进入月度过滤与指标净额；核实补正后经修订链填入真实日期即回归月度。 */
function ledgerRows(receipts, payments, refunds, month) {
  const monthOf = r => String(r.receivedDate || r.payDate || r.date || '').slice(0, 7);
  const rows = [];
  for (const r of receipts || []) {
    if (r.status !== '已登记') continue;
    const date = r.receivedDate || '待归月';
    rows.push({ key: 'r-' + r.id, type: '收款', sign: '+', name: r.name, amountCents: r.amountCents,
      date, pendingDate: !r.receivedDate, status: r.status, id: r.id,
      revisionVersion: r.revisionVersion });
  }
  for (const p of payments || []) {
    if (p.status !== '已核实') continue;
    const date = p.payDate || '待归月';
    rows.push({ key: 'p-' + p.id, type: '付款', sign: '−', name: '付款', amountCents: p.amountCents,
      date, pendingDate: !p.payDate, status: p.status, id: p.id });
  }
  for (const rf of refunds || []) {
    if (rf.status !== '已核实') continue;
    const date = rf.date || '待归月';
    rows.push({ key: 'f-' + rf.id, type: '退款', sign: '−', name: '客户退款', amountCents: rf.amountCents,
      date, pendingDate: !rf.date, status: rf.status, id: rf.id });
  }
  const filtered = month ? rows.filter(r => monthOf(r) === month) : rows;
  return { rows: filtered, allCount: rows.length };
}

/** 指标三卡（本月口径；作废不参与）。M08-T05/R04：空业务日期不回退 createTime——
 * 单列待归月，不进已核实月度净额（与 ledgerRows 的 monthOf 语义一致）。 */
function metrics(receipts, payments, refunds, month) {
  const inMonth = (r, field) => String(r[field] || '').slice(0, 7) === month;
  const received = (receipts || []).filter(r => r.status === '已登记' && inMonth(r, 'receivedDate'))
    .reduce((a, r) => a + (r.amountCents || 0), 0);
  const paid = (payments || []).filter(p => p.status === '已核实' && inMonth(p, 'payDate'))
    .reduce((a, p) => a + (p.amountCents || 0), 0);
  const refunded = (refunds || []).filter(r => r.status === '已核实' && inMonth(r, 'date'))
    .reduce((a, r) => a + (r.amountCents || 0), 0);
  return { received: yuan(received), paid: yuan(paid), refunded: yuan(refunded) };
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
    1102000018: '没有该操作权限'
  };
  if (code && map[code]) return map[code];
  if (code === 403) return '没有该操作权限';
  return (err && err.message) || '操作失败，请重试';
}

module.exports = { pageMode, yuan, toCents, ledgerRows, metrics, filterSealedEntries, errorMessage, SEALED };
