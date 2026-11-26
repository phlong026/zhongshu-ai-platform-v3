const { day } = require('../../utils/finance-date');
const copy = value => JSON.parse(JSON.stringify(value));
const id = value => typeof value === 'number' && !Number.isSafeInteger(value) ? '' : /^[1-9]\d{0,18}$/.test(String(value || ''))
  && (String(value).length < 19 || String(value) <= '9223372036854775807') ? String(value) : '';
const positive = value => Number.isSafeInteger(value) && value > 0;
const slots = [
  { slot: 'RESULT', key: 'resultAssetId', label: '外部设计成果' },
  { slot: 'DRAWING', key: 'drawingAssetId', label: '外部施工图' },
  { slot: 'REVIEW', key: 'reviewAssetId', label: '专业审核依据' },
  { slot: 'CONFIRMATION', key: 'confirmationAssetId', label: '客户确认凭据' }
];
const statusLabels = { EXTERNAL_PENDING: '等待外部成果', EXTERNAL_READY: '待移交工程', HANDOVER_SUBMITTED: '待工程接收', HANDOVER_RETURNED: '退回补充', ACCEPTED: '工程已接收' };
const packetLabels = { PENDING: '待接收', RETURNED: '已退回', ACCEPTED: '已接收' };
const triggers = { ON_SIGN: '签约时', BEFORE_START: '开工前', NODE_COMPLETED: '工程节点完成', ON_FINISH: '完工时', DATE: '约定日期', MANUAL: '手动登记', LEGACY_BASE_DONE: '原基础完工', LEGACY_TOPPING: '原封顶' };
const role = (post, roles, permission) => post?.orgType === 'store' && roles.includes(post.roleCode)
  && (!Array.isArray(post.permissions) || post.permissions.includes(permission));
const canSubmit = post => role(post, ['sales', 'sales-lead'], 'franchise:commission:submit');
const canDecide = post => role(post, ['engineer', 'project-manager'], 'franchise:commission:receive');
function plan(value, customerId, companyId) {
  if (value == null) return { source: 'NONE', sourceLabel: '尚未约定', rows: [], totalText: '', version: '' };
  if (id(value.customerId) !== customerId || id(value.companyId) !== companyId || !['DRAFT', 'CONTRACT'].includes(value.source)
      || !Number.isSafeInteger(value.revision) || value.revision < 0 || !Array.isArray(value.stages) || !value.stages.length || value.stages.length > 20
      || value.source === 'CONTRACT' && (!id(value.contractId) || !positive(value.contractPlanVersion))) throw Error('随包付款约定所属客户或版本不匹配');
  const total = Number(value.totalCents); if (!Number.isSafeInteger(total) || total <= 0) throw Error('随包付款约定金额无效');
  let sum = 0;
  const rows = value.stages.map((row, index) => {
    const cents = Number(row.amountCents); if (!Number.isSafeInteger(cents) || cents < 0) throw Error('付款节点金额无效'); sum += cents;
    const paid = row.collectedCents == null ? null : Number(row.collectedCents);
    if (paid != null && (!Number.isSafeInteger(paid) || paid < 0 || paid > cents || value.source !== 'CONTRACT')) throw Error('合同分期已收金额无效');
    return { ...(paid == null ? {} : { paidText: (paid / 100).toFixed(2), remainingText: ((cents - paid) / 100).toFixed(2) }), label: String(row.label || '第' + (index + 1) + '期'), sequence: row.sequence ?? index + 1, amountText: (cents / 100).toFixed(2),
      condition: (triggers[row.trigger] || '待核对条件') + (row.trigger === 'NODE_COMPLETED' && row.nodeIndex != null ? ' · 节点 ' + row.nodeIndex : ''),
      dueDate: day(row.dueDate), ratioText: row.ratioBps == null ? '' : (Number(row.ratioBps) / 100).toFixed(2).replace(/\.00$/, '') + '%' };
  });
  if (!Number.isSafeInteger(sum) || sum !== total) throw Error('付款节点合计与总额不一致');
  return { source: value.source, sourceLabel: value.source === 'CONTRACT' ? '正式合同快照' : '客户付款草案', rows,
    totalText: (total / 100).toFixed(2), version: value.source === 'CONTRACT' ? value.contractPlanVersion : value.revision };
}
function packet(value, customerId, companyId) {
  if (!value || !positive(value.version) || !positive(value.resultVersion) || !packetLabels[value.status] || !id(value.managerUserId)
      || !Array.isArray(value.files) || value.files.length !== 4 || new Set(value.files.map(file => file.slot)).size !== 4) throw Error('移交包记录无效');
  const files = slots.map(slot => {
    const file = value.files.find(item => item.slot === slot.slot); if (!id(file?.assetId)) throw Error('移交资料不完整');
    return { slot: slot.slot, assetId: id(file.assetId), title: String(file.title || slot.label), kind: 'packets', version: value.version };
  });
  return { ...value, managerUserId: id(value.managerUserId), statusLabel: packetLabels[value.status], files,
    confirmationDate: day(value.confirmationDate), createdOn: day(value.createdAt), decidedOn: day(value.decidedAt), plan: plan(value.paymentPlan, customerId, companyId) };
}
function detail(value, commissionId, companyId, post) {
  if (!value || id(value.commissionId) !== commissionId || !id(value.customerId) || value.mode !== 'EXTERNAL'
      || !Number.isSafeInteger(value.revision) || value.revision < 0 || !Array.isArray(value.packets) || !Array.isArray(value.allowedActions)
      || value.resultVersion != null && !positive(value.resultVersion) || value.projectId != null && !id(value.projectId)) throw Error('工程移交响应不匹配，请重新加载');
  const customerId = id(value.customerId), packets = value.packets.map(item => packet(item, customerId, companyId));
  if (new Set(packets.map(item => item.version)).size !== packets.length) throw Error('移交历史版本重复');
  const currentPacket = value.currentPacket == null ? null : packet(value.currentPacket, customerId, companyId);
  if (currentPacket && !packets.some(item => item.version === currentPacket.version && JSON.stringify(item) === JSON.stringify(currentPacket))) throw Error('当前移交与历史版本不匹配');
  const writer = canSubmit(post), decider = canDecide(post) && currentPacket?.managerUserId === id(post.userId)
    && currentPacket?.status === 'PENDING' && value.status === 'HANDOVER_SUBMITTED';
  return { commissionId, customerId, customerName: String(value.customerName || ''), revision: value.revision,
    status: value.status, statusLabel: statusLabels[value.status] || '待核对状态', resultVersion: value.resultVersion, projectId: id(value.projectId),
    canOpenProject: !!id(value.projectId) && role(post, ['engineer', 'project-manager', 'boss', 'owner', 'finance', 'finance-lead'], 'franchise:engineering:query'),
    canSubmit: writer && value.canSubmit === true && positive(value.resultVersion) && ['EXTERNAL_READY', 'HANDOVER_RETURNED'].includes(value.status) && value.allowedActions.includes('external-packet-submit'),
    canReceive: decider && value.canDecide === true && value.allowedActions.includes('external-packet-receive'),
    canReturn: decider && value.canDecide === true && value.allowedActions.includes('external-packet-return'),
    currentContractTerms: value.currentContractTerms ? plan(value.currentContractTerms, customerId, companyId) : null,
    submissionTerms: value.submissionTerms ? { plan: plan(value.submissionTerms.plan, customerId, companyId),
      checkedContract: value.submissionTerms.plan?.source === 'CONTRACT'
        ? { id: id(value.submissionTerms.plan.contractId), version: value.submissionTerms.plan.contractPlanVersion } : { id: null, version: null } } : null,
    currentPacket, packets: packets.sort((a, b) => b.version - a.version) };
}
function source(value, context, companyId) {
  const record = value?.currentResult, request = value?.currentRequest;
  if (!value || id(value.commissionId) !== context.commissionId || id(value.customerId) !== context.customerId || value.mode !== 'EXTERNAL'
      || !record || record.version !== context.resultVersion || !positive(request?.version) || record.data?.requestVersion !== request.version
      || !Array.isArray(record.attachments)) throw Error('外部成果版本已变化，请载入最新移交');
  const files = slots.slice(0, 3).map(slot => {
    const assetId = id(record.data[slot.key]); if (!assetId || !record.attachments.some(file => id(file.assetId) === assetId)) throw Error('外部成果资料不完整');
    return { slot: slot.slot, assetId, title: slot.label, kind: 'results', version: record.version };
  });
  return { resultVersion: record.version, requestVersion: request.version, drawingVersion: String(record.data.drawingVersion || ''),
    deliveredOn: day(record.data.deliveredOn), files, plan: context.submissionTerms?.plan || plan(request.paymentPlan, context.customerId, companyId) };
}
function receivers(values, post) {
  if (!Array.isArray(values)) throw Error('接收人列表无效，请重试');
  const seen = new Set();
  return values.filter(value => id(value?.userId) && id(value.userId) !== id(post.userId) && ['engineer', 'project-manager'].includes(value.roleCode)
    && value.active !== false && !seen.has(id(value.userId)) && seen.add(id(value.userId)))
    .map(value => ({ userId: id(value.userId), name: String(value.name || '工程人员 ' + value.userId) }));
}
function form(context) {
  return { confirmationDate: '', confirmationAssetId: '', note: '', managerUserId: '', confirmed: false, reason: '' };
}
function text(value, max, required, label) {
  const result = String(value || '').trim(); if (required && !result || result.length > max) throw Error(label + (required ? '必填，' : '') + '最多' + max + '字'); return result;
}
function payload(action, form, context, receivers, today) {
  if (action === 'submit') {
    if (!context.canSubmit) throw Error('当前委托不能提交工程移交');
    if (!id(form.confirmationAssetId)) throw Error('请上传客户确认凭据');
    const date = form.confirmationDate;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(date || '')) || day(date) !== date || date > today) throw Error('请选择不晚于今天的客户确认日期');
    if (!id(form.managerUserId) || !receivers.some(person => person.userId === id(form.managerUserId))) throw Error('请选择有效的工程接收人');
    if (form.confirmed !== true) throw Error('请确认已核对客户确认、成果资料及付款约定');
    return { revision: context.revision, resultVersion: context.resultVersion, confirmationAssetId: id(form.confirmationAssetId), confirmationDate: date,
      note: text(form.note, 1000, true, '交底说明'), managerUserId: id(form.managerUserId), confirmed: true, ...(context.submissionTerms ? { checkedContract: context.submissionTerms.checkedContract } : {}) };
  }
  if (!(action === 'receive' ? context.canReceive : action === 'return' && context.canReturn) || !context.currentPacket) throw Error('当前移交不能办理接收或退回');
  if (action === 'receive' && form.confirmed !== true) throw Error('请确认已核对本版移交资料');
  return { revision: context.revision, handoverVersion: context.currentPacket.version, reason: text(form.reason, 500, action === 'return', action === 'return' ? '退回原因' : '接收说明'), confirmed: action === 'receive' };
}
module.exports = { id, copy, slots, canSubmit, canDecide, detail, source, receivers, form, payload };
