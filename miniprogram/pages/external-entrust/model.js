const { day } = require('../../utils/finance-date');
const copy = value => JSON.parse(JSON.stringify(value));
const id = value => typeof value === 'number' && !Number.isSafeInteger(value) ? '' : /^[1-9]\d{0,18}$/.test(String(value || ''))
  && (String(value).length < 19 || String(value) <= '9223372036854775807') ? String(value) : '';
const statusLabels = { NOT_REGISTERED: '待登记委托', EXTERNAL_PENDING: '等待外部成果', EXTERNAL_READY: '成果已登记',
  HANDOVER_SUBMITTED: '待工程接收', HANDOVER_RETURNED: '工程退回补充', ACCEPTED: '工程已接收',
  FEE_PENDING: '历史费用待审批', FEE_RETURNED: '历史费用已退回', DESIGNING: '历史设计中', DESIGN_COMPLETED: '历史设计完成', DISPATCHED: '历史成果已下发' };
const sourceLabels = { NONE: '尚未约定', DRAFT: '客户付款草案', CONTRACT: '正式合同' };
const triggerLabels = { ON_SIGN: '签约时', BEFORE_START: '开工前', NODE_COMPLETED: '工程节点完成', ON_FINISH: '完工时', DATE: '约定日期', MANUAL: '手动登记', LEGACY_BASE_DONE: '原基础完工', LEGACY_TOPPING: '原封顶' };
const slots = [{ key: 'resultAssetId', label: '外部设计成果' }, { key: 'drawingAssetId', label: '施工图文件' }, { key: 'reviewAssetId', label: '专业审核依据' }];
const requestFields = [
  { key: 'providerName', label: '外部服务方', max: 100, required: true, placeholder: '填写设计公司或服务方名称' },
  { key: 'providerReference', label: '外部订单 / 项目编号', max: 100, placeholder: '选填' },
  { key: 'text', label: '委托需求', kind: 'textarea', max: 2000, required: true, placeholder: '记录客户需求、测量情况和需调整的内容' },
  { key: 'scope', label: '服务范围', kind: 'textarea', max: 500, required: true, placeholder: '填写本次约定交付的内容' },
  { key: 'area', label: '建筑面积（㎡）', type: 'digit', max: 9, placeholder: '选填，最多两位小数' },
  { key: 'floors', label: '建筑层数（层）', type: 'number', max: 2, placeholder: '选填，1 至 20 层' }
];
const canWrite = post => post?.orgType === 'store' && ['sales', 'sales-lead'].includes(post.roleCode)
  && (!Array.isArray(post.permissions) || post.permissions.includes('franchise:commission:submit'));
function planRef(value) {
  if (!value || !['NONE', 'DRAFT', 'CONTRACT'].includes(value.source) || !Number.isSafeInteger(value.revision) || value.revision < 0
      || value.source === 'NONE' && (value.revision !== 0 || value.contractId != null || value.contractPlanVersion != null)
      || value.source === 'DRAFT' && (value.revision < 1 || value.contractId != null || value.contractPlanVersion != null)
      || value.source === 'CONTRACT' && (!id(value.contractId) || !Number.isSafeInteger(value.contractPlanVersion) || value.contractPlanVersion < 1)) throw Error('付款计划引用无效，请重新加载');
  return { source: value.source, revision: value.revision, contractId: value.contractId == null ? null : id(value.contractId), contractPlanVersion: value.contractPlanVersion ?? null };
}
const refKey = ref => JSON.stringify(planRef(ref));
function plan(value, customerId, companyId) {
  if (value == null) return { source: 'NONE', sourceLabel: sourceLabels.NONE, rows: [], totalText: '', version: '' };
  if (id(value.customerId) !== customerId || id(value.companyId) !== companyId || !['DRAFT', 'CONTRACT'].includes(value.source)
      || !Number.isSafeInteger(value.revision) || value.revision < 0 || !Array.isArray(value.stages) || value.stages.length < 1 || value.stages.length > 20) throw Error('付款计划所属客户或版本不匹配');
  const total = Number(value.totalCents);
  if (!Number.isSafeInteger(total) || total <= 0) throw Error('付款计划金额无效');
  let sum = 0;
  const rows = value.stages.map((row, index) => {
    const cents = Number(row.amountCents); if (!Number.isSafeInteger(cents) || cents < 0) throw Error('付款节点金额无效'); sum += cents;
    return { label: String(row.label || '第' + (index + 1) + '期'), amountText: (cents / 100).toFixed(2), sequence: row.sequence ?? index + 1,
      condition: (triggerLabels[row.trigger] || '待核对条件') + (row.trigger === 'NODE_COMPLETED' && row.nodeIndex != null ? ' · 节点 ' + row.nodeIndex : ''),
      dueDate: day(row.dueDate), ratioText: row.ratioBps == null ? '' : (Number(row.ratioBps) / 100).toFixed(2).replace(/\.00$/, '') + '%' };
  });
  if (sum !== total) throw Error('付款节点合计与总额不一致');
  return { source: value.source, sourceLabel: sourceLabels[value.source], revision: value.revision, rows, totalText: (total / 100).toFixed(2),
    version: value.source === 'CONTRACT' ? value.contractPlanVersion : value.revision, contractId: id(value.contractId) };
}
function files(values, kind, version) {
  if (!Array.isArray(values)) throw Error('附件记录无效');
  return values.map(file => { if (!id(file?.assetId)) throw Error('附件编号无效');
    return { assetId: id(file.assetId), title: String(file.title || '委托资料'), kind, version }; });
}
function measurement(value, customerId) {
  if (!value || !id(value.eventId) || id(value.customerId) !== customerId || !Number.isSafeInteger(value.revision) || value.revision < 1 ||
      !day(value.occurredOn) || value.fields?.checked !== true || !Array.isArray(value.attachments) || !value.attachments.length)
    throw Error('测量原单所属客户或版本不匹配，请重新读取');
  return { eventId: id(value.eventId), revision: value.revision, customerId, occurredOn: day(value.occurredOn),
    fields: copy(value.fields), attachments: files(value.attachments, '', 0), note: typeof value.note === 'string' ? value.note : '' };
}
function version(value, kind, customerId, companyId) {
  if (!value || !Number.isSafeInteger(value.version) || value.version < 1 || !value.data) throw Error('委托版本记录无效');
  const data = copy(value.data), request = kind === 'requests';
  const attachments = files(request ? data.attachments || [] : value.attachments || [], kind, value.version);
  if (request && data.measurementRef) {
    data.measurementRef = measurement(data.measurementRef, customerId);
    if (data.measurementRef.attachments.some(source => !attachments.some(file => file.assetId === source.assetId)))
      throw Error('委托版本与测量原件不匹配，请重新读取');
  }
  return { ...value, key: kind + '-' + value.version, kind, data, attachments, actorUserId: id(value.actorUserId),
    createdOn: day(value.createdAt), occurredOn: day(request ? data.entrustedOn : data.deliveredOn),
    title: request ? ({ REGISTER: '登记委托需求', UPDATE: '变更委托需求', CONVERT: '转入外部流程' }[value.action] || '委托需求') : '登记外部成果',
    description: request ? data.providerName : data.drawingVersion,
    reason: request ? data.reason : data.note,
    ...(request ? { plan: plan(value.paymentPlan, customerId, companyId) } : {}) };
}
function detail(value, customerId, companyId, post) {
  if (!value || id(value.customerId) !== customerId || !Number.isSafeInteger(value.revision) || value.revision < 0
      || !['EXTERNAL', 'LEGACY'].includes(value.mode) || value.commissionId != null && !id(value.commissionId)
      || !Array.isArray(value.requests) || !Array.isArray(value.results) || !Array.isArray(value.allowedActions)) throw Error('委托响应不匹配，请重新加载');
  const ref = planRef(value.currentPaymentPlanRef), currentPlan = plan(value.currentPaymentPlan, customerId, companyId);
  if (ref.source !== currentPlan.source || ref.source !== 'NONE' && ref.revision !== currentPlan.revision || ref.source === 'DRAFT' && ref.revision !== currentPlan.version
      || ref.source === 'CONTRACT' && (ref.contractId !== currentPlan.contractId || ref.contractPlanVersion !== currentPlan.version)) throw Error('付款计划引用与内容不一致');
  const requests = value.requests.map(v => version(v, 'requests', customerId, companyId));
  const results = value.results.map(v => version(v, 'results', customerId, companyId));
  if (new Set(requests.map(v => v.version)).size !== requests.length || new Set(results.map(v => v.version)).size !== results.length) throw Error('委托历史版本重复');
  const currentRequest = value.currentRequest == null ? null : version(value.currentRequest, 'requests', customerId, companyId);
  const currentResult = value.currentResult == null ? null : version(value.currentResult, 'results', customerId, companyId);
  if (currentRequest && !requests.some(v => v.version === currentRequest.version)
      || currentResult && (!results.some(v => v.version === currentResult.version) || currentResult.data.requestVersion !== currentRequest?.version)) throw Error('当前委托与成果版本不匹配');
  const writer = canWrite(post), allowed = action => value.allowedActions.includes(action);
  return { commissionId: id(value.commissionId), customerId, customerName: String(value.customerName || ''), mode: value.mode,
    status: String(value.status), statusLabel: statusLabels[value.status] || '待核对状态', revision: value.revision,
    canRegister: writer && value.canWrite === true && allowed('external-entrust-register'),
    canResults: writer && value.canWrite === true && !!currentRequest && allowed('external-results-register'),
    canConvert: writer && value.canConvert === true && value.mode === 'LEGACY' && allowed('external-entrust-convert'),
    canHandover: writer && !!currentResult && allowed('external-packet-submit'),
    request: currentRequest, result: currentResult, requests, results, currentPlan, planRef: ref,
    hasLegacy: value.mode === 'LEGACY' || requests.some(item => item.action === 'CONVERT'),
    history: [...requests, ...results].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)) || b.version - a.version) };
}
function requestForm(context) {
  const source = context.request?.data || {};
  const measured = new Set((source.measurementRef?.attachments || []).map(file => file.assetId));
  return { entrustedOn: day(source.entrustedOn), providerName: source.providerName || '', providerReference: source.providerReference || '',
    text: source.text || '', scope: source.scope || '', area: source.area == null ? '' : String(source.area), floors: source.floors == null ? '' : String(source.floors),
    attachments: copy((context.request?.attachments || []).filter(file => !measured.has(file.assetId))),
    measurementRef: source.measurementRef ? copy(source.measurementRef) : null, reason: '' };
}
function resultForm(context) {
  const source = context.result?.data || {};
  return { deliveredOn: day(source.deliveredOn), drawingVersion: source.drawingVersion || '', note: '',
    ...Object.fromEntries(slots.map(slot => [slot.key, id(source[slot.key])])) };
}
function string(value, max, required, label) {
  const result = String(value || '').trim(); if (required && !result || result.length > max) throw Error(label + (required ? '必填，' : '') + '最多' + max + '字'); return result;
}
function date(value, today, label) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || '')) || day(value) !== value || !day(today) || value > today) throw Error('请选择不晚于今天的' + label); return value;
}
function payload(action, form, context, today) {
  if (action === 'results') {
    if (!context.canResults || !context.request) throw Error('当前委托不能登记成果');
    const result = { revision: context.revision, requestVersion: context.request.version, deliveredOn: date(form.deliveredOn, today, '实际交付日期'),
      drawingVersion: string(form.drawingVersion, 64, true, '图纸版本'), note: string(form.note, 1000, !!context.result, context.result ? '成果更正原因' : '成果说明') };
    for (const slot of slots) { if (!id(form[slot.key])) throw Error('请上传' + slot.label); result[slot.key] = id(form[slot.key]); }
    return result;
  }
  if (!(action === 'convert' ? context.canConvert : action === 'register' && context.canRegister)) throw Error('当前委托不能登记或转换');
  const area = String(form.area || '').trim(), floors = String(form.floors || '').trim();
  if (area && (!/^\d+(?:\.\d{1,2})?$/.test(area) || Number(area) <= 0 || Number(area) > 999999.99)) throw Error('建筑面积须大于0且不超过999999.99㎡，最多两位小数');
  if (floors && (!/^\d+$/.test(floors) || Number(floors) < 1 || Number(floors) > 20)) throw Error('层数须为1至20的整数');
  const attachments = files(form.attachments || [], 'requests', 0).map(file => ({ assetId: file.assetId, title: string(file.title, 180, true, '附件名称') }));
  if (attachments.length > 20 || new Set(attachments.map(v => v.assetId)).size !== attachments.length) throw Error('最多20份委托资料，同一附件不能重复');
  const selected = form.measurementRef ? measurement(form.measurementRef, context.customerId) : null;
  if (selected && new Set([...attachments, ...selected.attachments].map(file => file.assetId)).size > 20)
    throw Error('委托附件与测量原件合计最多20份');
  return { revision: context.revision, entrustedOn: date(form.entrustedOn, today, '实际委托日期'), providerName: string(form.providerName, 100, true, '外部服务方'),
    providerReference: string(form.providerReference, 100, false, '外部编号'), text: string(form.text, 2000, true, '委托需求'), scope: string(form.scope, 500, true, '服务范围'),
    area: area ? Number(area) : null, floors: floors ? Number(floors) : null, attachments, paymentPlanRef: planRef(context.planRef),
    reason: string(form.reason, 500, action === 'convert' || !!context.request, '变更原因'),
    ...(selected ? { measurementRef: { eventId: selected.eventId, revision: selected.revision,
      ...(form.measurementSelected ? { includeSourceNote: true } : {}) } } :
      context.request?.data.measurementRef ? { removeMeasurement: true } : {}) };
}
module.exports = { id, copy, canWrite, planRef, refKey, detail, requestForm, resultForm, payload, measurement, slots, requestFields };
