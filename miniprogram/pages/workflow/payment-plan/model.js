const { toCents } = require('../../../utils/platform-finance');
const { day } = require('../../../utils/finance-date');
const MAX_CENTS = 1000000000000;
const TRIGGERS = [
  { value: 'ON_SIGN', label: '签约时' }, { value: 'BEFORE_START', label: '开工前' },
  { value: 'NODE_COMPLETED', label: '工程节点完成' }, { value: 'ON_FINISH', label: '工程完工' },
  { value: 'DATE', label: '指定日期' }, { value: 'MANUAL', label: '手动确认' }
];
const LEGACY_TRIGGERS = { LEGACY_BASE_DONE: '基础完工（原约定）', LEGACY_TOPPING: '主体封顶（原约定）' };
const STATUS = { WAIT_SIGN: '待签订', SIGNED: '已签订', VOIDED: '已作废' };
function id(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const text = String(value == null ? '' : value);
  return /^[1-9]\d{0,18}$/.test(text) ? text : '';
}
function cents(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 && n <= MAX_CENTS ? n : null;
}
const money = value => cents(value) == null ? '' : (Number(value) / 100).toFixed(2);
function bps(value) {
  const s = String(value == null ? '' : value).trim();
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(s)) return null;
  const n = Math.round(Number(s) * 100);
  return n > 0 && n <= 10000 ? n : null;
}
// Splitting the quotient keeps the multiplication below MAX_SAFE_INTEGER at the API's amount ceiling.
const ratioAmount = (total, ratio) => Math.floor(total / 10000) * ratio + Math.floor(total % 10000 * ratio / 10000);
const triggerLabel = value => TRIGGERS.find(t => t.value === value)?.label || LEGACY_TRIGGERS[value] || '待核对';
function emptyRow(key) {
  return { key, stageId: null, label: '', amount: '', ratio: '', dueDate: '', trigger: 'MANUAL', nodeIndex: '', collectedCents: 0 };
}
function initial(totalCents) {
  return { total: money(totalCents), mode: 'RATIO', signedDate: '', reason: '',
    rows: [{ ...emptyRow('new-1'), label: '第1期', ratio: '100' }] };
}
function stage(row, index) {
  const stageId = id(row.stageId == null ? row.id : row.stageId);
  if (!stageId || cents(row.amountCents ?? row.amount_cents) == null) throw Error('付款节点数据不完整，请重新加载');
  return { key: stageId, stageId, label: String(row.label || row.stage || ''),
    amount: money(row.amountCents ?? row.amount_cents), ratio: row.ratioBps == null && row.ratio_bps == null ? '' : money(row.ratioBps ?? row.ratio_bps),
    dueDate: day(row.dueDate ?? row.due_date), trigger: row.trigger || row.trigger_type || '',
    legacyTrigger: LEGACY_TRIGGERS[row.trigger || row.trigger_type] ? row.trigger || row.trigger_type : '',
    nodeIndex: String(row.nodeIndex ?? row.trigger_node_index ?? ''), sequence: row.sequence ?? index + 1,
    collectedCents: cents(row.collectedCents) || 0, hasRecords: row.hasRecords === true || cents(row.grossCents) > 0,
    planVersion: row.planVersion ?? row.plan_version };
}
function detail(response, contractId, companyId) {
  const c = response?.contract;
  if (!c || id(c.id) !== contractId || id(c.companyId) !== companyId || !id(c.customerId)
      || cents(c.totalCents) == null || !Number.isSafeInteger(c.revision) || c.revision < 0
      || !Array.isArray(response.stages) || response.stages.length > 20) throw Error('合同付款计划响应不匹配，请重新加载');
  const rows = response.stages.map(stage).sort((a, b) => a.sequence - b.sequence);
  if (new Set(rows.map(r => r.stageId)).size !== rows.length) throw Error('付款节点编号重复，请重新加载');
  const form = { total: money(c.totalCents), mode: rows.length && rows.every(r => !!r.ratio) ? 'RATIO' : 'AMOUNT',
    signedDate: day(c.signedDate), reason: '', rows };
  return { contractId, customerId: id(c.customerId), customerName: String(response.customerName || ''), status: c.status,
    statusLabel: STATUS[c.status] || '待核对', revision: c.revision, planVersion: c.paymentPlanVersion,
    totalText: money(c.totalCents), canEdit: response.canEdit === true && ['WAIT_SIGN', 'SIGNED'].includes(c.status), form };
}
function draft(response, customerId) {
  if (!response || id(response.customerId) !== customerId || !Number.isSafeInteger(response.revision) || response.revision < 0
      || !Array.isArray(response.stages) || response.stages.length > 20
      || response.revision > 0 && (!response.stages.length || !cents(response.totalCents))) throw Error('客户付款草案响应不匹配，请重新加载');
  const rows = response.stages.map((row, index) => {
    if (row.stageId != null || row.id != null || cents(row.amountCents) == null) throw Error('付款草案节点无效，请重新加载');
    return { ...emptyRow('draft-' + index), label: String(row.label || ''), amount: money(row.amountCents),
      ratio: row.ratioBps == null ? '' : money(row.ratioBps), dueDate: day(row.dueDate), trigger: row.trigger || '',
      nodeIndex: String(row.nodeIndex ?? ''), sequence: row.sequence ?? index + 1 };
  }).sort((a, b) => a.sequence - b.sequence);
  const form = rows.length ? { total: money(response.totalCents), mode: rows.every(r => !!r.ratio) ? 'RATIO' : 'AMOUNT',
    signedDate: '', reason: String(response.note || ''), rows } : initial(null);
  return { draft: true, customerId, revision: response.revision, planVersion: response.revision,
    statusLabel: response.revision ? '已保存草案' : '待填写草案', totalText: money(response.totalCents), canEdit: true, form };
}
function preview(form) {
  const total = toCents(form.total), ratioMode = form.mode === 'RATIO', errors = [], rows = [];
  if (!total) errors.push('请填写有效合同总额，最多两位小数');
  if (!['RATIO', 'AMOUNT'].includes(form.mode)) errors.push('请选择金额或比例方式');
  if (!Array.isArray(form.rows) || form.rows.length < 1 || form.rows.length > 20) errors.push('付款计划须包含1至20期');
  let sum = 0, sumBps = 0;
  for (const [index, row] of (form.rows || []).entries()) {
    const label = String(row.label || '').trim(), ratio = bps(row.ratio);
    const triggerChoices = row.stageId && LEGACY_TRIGGERS[row.legacyTrigger] ? [...TRIGGERS, { value: row.legacyTrigger, label: LEGACY_TRIGGERS[row.legacyTrigger] }] : TRIGGERS;
    let amount = ratioMode ? total && ratio ? ratioAmount(total, ratio) : null : toCents(row.amount);
    if (ratioMode && total && ratio && index === form.rows.length - 1) amount = total - sum;
    if (!label || label.length > 64 || /[\x00-\x1f\x7f]/.test(label)) errors.push('第' + (index + 1) + '期名称须为1至64字');
    if (ratioMode && !ratio) errors.push('第' + (index + 1) + '期比例须大于0且不超过100%，最多两位小数');
    if (!amount || amount < 0 || total && amount > total) errors.push('第' + (index + 1) + '期金额须大于0且不超过总额');
    if (amount != null && amount < Number(row.collectedCents || 0)) errors.push('第' + (index + 1) + '期金额不能低于已确认净收款');
    if (!triggerChoices.some(t => t.value === row.trigger)) errors.push('请选择第' + (index + 1) + '期到期条件');
    if (row.trigger === 'DATE' && !day(row.dueDate)) errors.push('请选择第' + (index + 1) + '期到期日期');
    if (row.trigger === 'NODE_COMPLETED' && (!/^\d{1,3}$/.test(String(row.nodeIndex)) || Number(row.nodeIndex) < 1 || Number(row.nodeIndex) > 100)) errors.push('第' + (index + 1) + '期工程节点序号须为1至100');
    if (row.dueDate && !day(row.dueDate)) errors.push('第' + (index + 1) + '期到期日期无效');
    sum += amount || 0; sumBps += ratio || 0;
    rows.push({ ...row, number: index + 1, amountText: amount >= 0 ? money(amount) : '', collectedText: money(row.collectedCents || 0),
      triggerLabel: triggerLabel(row.trigger), triggerChoices, triggerIndex: Math.max(0, triggerChoices.findIndex(t => t.value === row.trigger)),
      amountCents: amount, ratioBps: ratio, removable: form.rows.length > 1 && !row.hasRecords && !row.collectedCents });
  }
  if (ratioMode && sumBps !== 10000) errors.push('付款比例之和须为100%');
  if (!ratioMode && total && sum !== total) errors.push('各期金额之和须等于合同总额');
  const difference = ratioMode ? 10000 - sumBps : total == null ? 0 : total - sum;
  return { rows, valid: errors.length === 0, errors, totalCents: total, totalText: money(total),
    allocatedText: (ratioMode ? (sumBps / 100).toFixed(2) + '%' : '¥' + money(sum)),
    balanceText: difference === 0 ? '已分配完整' : (difference > 0 ? '还差 ' : '超出 ') + (Math.abs(difference) / 100).toFixed(2) + (ratioMode ? '%' : ' 元') };
}
function payload(form, context) {
  const summary = preview(form);
  if (!summary.valid) throw Error(summary.errors[0]);
  const stages = summary.rows.map(row => ({ stageId: row.stageId || null, label: row.label.trim(),
    ...(form.mode === 'RATIO' ? { ratioBps: row.ratioBps } : { amountCents: row.amountCents }),
    dueDate: row.dueDate || null, trigger: row.trigger, nodeIndex: row.trigger === 'NODE_COMPLETED' ? Number(row.nodeIndex) : null }));
  if (stages.some(row => row.stageId && !id(row.stageId)) || new Set(stages.filter(r => r.stageId).map(r => r.stageId)).size !== stages.filter(r => r.stageId).length) throw Error('付款节点编号无效或重复，请重新加载');
  if (context.draft) {
    if (!id(context.customerId) || !Number.isSafeInteger(context.revision) || context.revision < 0) throw Error('付款草案版本无效，请重新加载');
    if (stages.some(row => row.stageId)) throw Error('付款草案不能引用正式收款节点');
    const note = String(form.reason || '').trim();
    if (!note || note.length > 500) throw Error('请填写付款草案说明，不超过500字');
    return { revision: context.revision, totalCents: summary.totalCents, stages, note };
  }
  if (context.contractId) {
    if (!Number.isSafeInteger(context.revision) || context.revision < 0) throw Error('合同版本无效，请重新加载');
    const reason = String(form.reason || '').trim();
    if (!reason || reason.length > 500) throw Error('请填写调整原因，不超过500字');
    return { totalCents: summary.totalCents, stages, revision: context.revision, reason };
  }
  if (!id(context.customerId)) throw Error('客户编号无效');
  return { customerId: context.customerId, totalCents: summary.totalCents,
    paymentStages: stages.map(row => ({ ...row, stageId: null })),
    ...(form.signedDate ? { signedDate: form.signedDate } : {}), ...(context.requirementId ? { requirementId: context.requirementId } : {}) };
}
function versions(value) {
  if (!Array.isArray(value)) throw Error('付款计划历史数据不完整');
  return value.map(v => ({ version: v.version, revision: v.contractRevision ?? v.contract_revision,
    totalText: money(v.totalCents ?? v.total_cents), reason: String(v.reason || ''), createdOn: day(v.createdAt ?? v.created_at),
    actorName: String(v.actorName || ''), rows: (Array.isArray(v.stages) ? v.stages : []).map((row, index) => ({ ...stage(row, index),
      amountText: money(row.amountCents ?? row.amount_cents), triggerLabel: triggerLabel(row.trigger || row.trigger_type) })) }));
}
module.exports = { TRIGGERS, id, money, bps, ratioAmount, emptyRow, initial, detail, draft, preview, payload, versions };
