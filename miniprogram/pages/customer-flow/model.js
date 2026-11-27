const houseSelection = require('./house-selection');
const field = (key, label, type = 'text', extra = {}) => ({ key, label, type,
  maxLength: ['requirements', 'feedback'].includes(key) ? 4000 : 1000, ...extra });
const STEPS = [
  { code: 'INVITATION', name: '邀约进店', fields: [
    field('contactMethod', '邀约方式', 'pick', { options: ['电话', '微信', '当面沟通', '其他'].map(value => ({ value, label: value })) }),
    field('invitationResult', '邀约结果', 'pick', { options: [{ value: 'PENDING', label: '待客户确认' }, { value: 'CONFIRMED', label: '客户已确认' }, { value: 'DECLINED', label: '暂不进店' }] }),
    field('appointmentAt', '约定到店', 'datetime')
  ] },
  { code: 'INTRODUCTION', name: '店面讲解', fields: [
    field('receptionist', '接待人'), field('requirements', '建房需求', 'textarea'), field('feedback', '讲解反馈', 'textarea')
  ] },
  { code: 'HOUSE_SELECTION', name: '户型选择', fields: [
    field('selectionMode', '选型方式', 'pick', { options: houseSelection.options }),
    field('customDirection', '实际定制方向', 'textarea'),
    field('referenceCaseId', '参考户型', 'reference'), field('referenceCaseName', '户型名称', 'hidden'),
    field('selectionResult', '选型结果', 'textarea'), field('versionLabel', '确认版本'), field('confirmed', '客户已确认选型', 'boolean')
  ] },
  { code: 'SITE_VISIT', name: '带看工地', fields: [
    field('siteName', '工地／案例名称'), field('address', '工地地址'), field('feedback', '客户反馈', 'textarea')
  ] },
  { code: 'MEASUREMENT', name: '现场测量', fields: [
    field('address', '实测地址'), field('measurer', '测量人'), field('width', '面宽', 'number', { unit: 'm' }),
    field('depth', '进深', 'number', { unit: 'm' }), field('area', '建筑面积', 'number', { unit: '㎡' }),
    field('floors', '层数', 'integer', { unit: '层' }), field('orientation', '实测朝向（选填）'),
    field('siteElevationNote', '影响设计的现场高差或说明（选填）', 'textarea'), field('checked', '测量资料已核对', 'boolean'),
    field('designContractAssetId', '设计合同', 'hidden'), field('designContractName', '设计合同名称', 'hidden')
  ] },
  { code: 'QUOTATION', name: '别墅报价', fields: [
    field('quoteId', '正式报价原单', 'hidden'),
    field('amountCents', '正式报价金额', 'money', { unit: '元' }), field('scope', '报价范围', 'textarea'),
    field('versionLabel', '报价版本'), field('result', '沟通结果', 'textarea'), field('communicated', '已向客户说明报价', 'boolean'),
    field('designFileAssetId', '设计文件', 'hidden'), field('designFileName', '设计文件名称', 'hidden')
  ] },
  { code: 'CONTRACT', name: '合同签约', fields: [] },
  { code: 'START', name: '开工大吉', fields: [] }
];
const STATUS = { TODO: '待办理', DRAFT: '已记录', COMPLETED: '已完成' };
const FLOW_STATUS = { ACTIVE: '跟进中', PAUSED: '已暂停', LOST: '已流失', COMPLETED: '已开工' };
/** 时间线上的动作：八步事件、补充记录、客资再次咨询、升级前的普通跟进。 */
const EVENT = { SAVE: '保存记录', COMPLETE: '完成步骤', SKIP: '已跳过带看', REOPEN: '重新办理', STATUS: '更新状态', SUPPLEMENT: '补充记录', CONSULT: '再次咨询', LEGACY: '跟进记录', VISIT: '实际接待' };
const QUOTE_SNAPSHOT_FIELDS = new Set(['amountCents', 'scope', 'versionLabel']);
/** 专用资料槽位：05 步设计合同、06 步设计文件。文件引用存在步骤 fields 里，与通用附件列表分开。 */
const SLOTS = {
  designContract: { code: 'MEASUREMENT', asset: 'designContractAssetId', name: 'designContractName', fallback: '设计合同' },
  designFile: { code: 'QUOTATION', asset: 'designFileAssetId', name: 'designFileName', fallback: '设计文件' }
};
/** 已登记的槽位文件转成附件形状，供预览和历史查看复用附件渲染。 */
function slotFiles(source = {}) {
  const fields = source.fields || {};
  return Object.entries(SLOTS).flatMap(([, slot]) => {
    const assetId = id(fields[slot.asset]);
    return assetId ? [{ assetId, title: String(fields[slot.name] || slot.fallback), mimeType: '' }] : [];
  });
}
const definition = code => STEPS.find(step => step.code === code);
function id(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const text = String(value == null ? '' : value);
  return /^[1-9]\d{0,18}$/.test(text) ? text : '';
}
function date(value) {
  if (Array.isArray(value)) return value.length === 3 ? value.map((n, i) => i ? String(n).padStart(2, '0') : n).join('-') : '';
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value) ? value.slice(0, 10) : '';
}
const today = () => require('../../utils/finance-date').day(Date.now());
function dateTime(value) {
  if (!value) return '';
  const timestamp = typeof value === 'number' ? (Math.abs(value) < 1e12 ? value * 1000 : value) : Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ') : '';
}
function fromCents(value) {
  const text = String(value == null ? '' : value);
  if (!/^\d+$/.test(text)) return '';
  const padded = text.replace(/^0+(?=\d)/, '').padStart(3, '0');
  return padded.slice(0, -2) + '.' + padded.slice(-2);
}
function toCents(value) {
  const text = String(value == null ? '' : value).trim();
  if (!text) return '';
  if (!/^\d{1,16}(\.\d{1,2})?$/.test(text)) throw Error('报价金额须为不超过两位小数的正数');
  const [whole, decimals = ''] = text.split('.');
  return (whole + decimals.padEnd(2, '0')).replace(/^0+(?=\d)/, '');
}
function attachments(rows) {
  return (Array.isArray(rows) ? rows : []).map(row => ({ assetId: id(row.assetId), title: String(row.title || row.name || '附件'), mimeType: row.mimeType || '' }));
}
/** 下次跟进日期是客户层面的约定：本步已约就用本步的；没约时沿用客户当前还没过期的约定，过期的留空让经办人重新约。 */
function nextFollowOnOf(step = {}, current) {
  const own = date(step.nextFollowOn), existing = date(current), day = today();
  return own || (existing && existing >= day ? existing : '');
}
function form(step = {}, currentNextFollowOn) {
  const def = definition(step.code), source = step.fields || {}, fields = {};
  for (const row of def ? def.fields : []) fields[row.key] = row.type === 'boolean' ? source[row.key] === true
    : row.type === 'money' ? fromCents(source[row.key]) : String(source[row.key] == null ? '' : source[row.key]);
  const appointment = String(source.appointmentAt || '');
  // 升级前第 1 步的“下次联系”不再是表单项；已保存的值原样带回，不因本版的一次保存而丢失。
  const legacyNextContactOn = step.code === 'INVITATION' && /^\d{4}-\d{2}-\d{2}$/.test(String(source.nextContactOn || ''))
    ? String(source.nextContactOn) : '';
  return { occurredOn: date(step.occurredOn), nextFollowOn: nextFollowOnOf(step, currentNextFollowOn), note: String(step.note || ''), fields,
    legacyNextContactOn,
    appointmentDate: date(appointment), appointmentTime: /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(appointment) ? appointment.slice(11, 16) : '',
    attachments: attachments(step.attachments) };
}
function fieldRows(code, value) {
  const linked = code === 'QUOTATION' && id(value.fields.quoteId);
  return houseSelection.fields(definition(code), value).filter(row => row.type !== 'hidden' &&
    (!linked || (!QUOTE_SNAPSHOT_FIELDS.has(row.key) && !['communicated', 'result'].includes(row.key)))).map(row => ({ ...row,
    value: value.fields[row.key], optionLabel: row.options?.find(o => o.value === value.fields[row.key])?.label || '请选择',
    optionIndex: Math.max(0, row.options?.findIndex(o => o.value === value.fields[row.key]) || 0) }));
}
function payload(code, value, revision) {
  const def = definition(code);
  if (!def || ['CONTRACT', 'START'].includes(code)) throw Error('签约与开工由业务事实同步');
  const fields = {};
  for (const row of houseSelection.fields(def, value, true)) {
    if (code === 'QUOTATION' && id(value.fields.quoteId) && QUOTE_SNAPSHOT_FIELDS.has(row.key)) continue;
    const input = value.fields[row.key];
    fields[row.key] = row.type === 'boolean' ? input === true : row.type === 'money' ? toCents(input) : String(input == null ? '' : input).trim();
  }
  if (code === 'INVITATION') {
    fields.appointmentAt = value.appointmentDate && value.appointmentTime ? value.appointmentDate + 'T' + value.appointmentTime : '';
    if (/^\d{4}-\d{2}-\d{2}$/.test(String(value.legacyNextContactOn || ''))) fields.nextContactOn = value.legacyNextContactOn;
  }
  const files = attachments(value.attachments);
  if (files.some(file => !file.assetId)) throw Error('资料尚未上传完成，请重试');
  return { revision, occurredOn: value.occurredOn || null, nextFollowOn: value.nextFollowOn || null, note: value.note.trim(), fields,
    attachments: files.map(({ assetId, title }) => ({ assetId, title })) };
}
function quoteOptions(rows, customerId, companyId) {
  if (!Array.isArray(rows)) throw Error('报价列表格式有误，请重试');
  return rows.flatMap(row => {
    const quoteId = id(row.id), amount = fromCents(row.totalCents);
    if (!quoteId || id(row.customerId) !== customerId || id(row.companyId) !== companyId || !amount || amount === '0.00') return [];
    const price = String(row.priceVersion || '').trim();
    const version = Number.isSafeInteger(row.budgetVersion) && row.budgetVersion > 0
      ? 'V' + row.budgetVersion + (price ? ' · ' + price : '') : price || '报价 #' + quoteId;
    const createdOn = Array.isArray(row.createTime) ? date(row.createTime.slice(0, 3)) : dateTime(row.createTime).slice(0, 10);
    return [{ id: quoteId, amount, version, label: [createdOn, version, '¥' + amount].filter(Boolean).join(' · ') }];
  });
}
function workflow(response, customerId) {
  if (!response || id(response.customerId) !== customerId) throw Error('客户流程响应不匹配，请重新加载');
  if (!Array.isArray(response.steps) || response.steps.length !== 8 || new Set(response.steps.map(s => s.code)).size !== 8
    || STEPS.some(s => !response.steps.some(row => row.code === s.code))) throw Error('客户步骤数据不完整，请重试');
  if (!Number.isInteger(response.revision) || response.revision < 0 || !Number.isInteger(response.completedCount)
    || response.completedCount < 0 || response.completedCount > 8 || !Number.isFinite(response.progressPercent)
    || response.progressPercent < 0 || response.progressPercent > 100) throw Error('流程进度数据异常，请重试');
  const steps = STEPS.map((def, index) => {
    const s = response.steps.find(row => row.code === def.code);
    const skipped = def.code === 'SITE_VISIT' && s.status === 'SKIPPED';
    const finished = s.status === 'COMPLETED' || skipped;
    return { ...s, skipped, finished, name: def.name, number: String(index + 1).padStart(2, '0'),
      statusLabel: skipped ? '已跳过' : def.code === 'SITE_VISIT' && finished ? '已带看' : STATUS[s.status] || '待核对',
      attachments: attachments(s.attachments), completionErrors: Array.isArray(s.completionErrors) ? s.completionErrors : [],
      canWrite: response.canWrite === true && s.canWrite === true && index < 6,
      canReopen: response.canWrite === true && s.canReopen === true && finished && index < 6 };
  });
  return { ...response, steps, statusLabel: FLOW_STATUS[response.status] || '待核对',
    currentStepName: definition(response.currentStepCode)?.name || (response.completedCount === 8 ? '已开工' : '待办理') };
}
function records(rows) {
  return (Array.isArray(rows) ? rows : []).map(row => ({ ...row, id: String(row.id), stepName: definition(row.stepCode)?.name || '跟进记录',
    actorName: row.actorName || (row.type === 'SKIP' ? row.actorUserId : ''),
    actionLabel: EVENT[row.type] || '更新记录', createdText: dateTime(row.createdAt), occurredText: date(row.occurredOn),
    attachments: attachments(row.attachments) }));
}
function facts(step) {
  const f = step.fields || {};
  if (step.skipped) return [{label: '跳过原因', value: step.note},
    {label: '经办人', value: f.visitSkip?.actorName || f.visitSkip?.actorUserId},
    {label: '办理时间', value: dateTime(step.completedAt)}];
  if (step.code === 'CONTRACT') return [{ label: '合同编号', value: f.contractNo || f.contractId },
    { label: '签约日期', value: date(f.signedDate) }, { label: '合同总额', value: fromCents(f.totalCents) ? '¥' + fromCents(f.totalCents) : '' }];
  if (step.code === 'START') return [{ label: '工程编号', value: f.projectId }, { label: '实际开工', value: date(f.actualStartAt) },
    { label: '工程负责人', value: f.managerName || (f.managerUserId ? '编号 ' + f.managerUserId : '') }];
  return [];
}
function errorText(error) { return String(error && (error.msg || error.message) || '操作未完成，请重试'); }
module.exports = { houseSelection, STEPS, SLOTS, definition, id, date, dateTime, fromCents, toCents, form, fieldRows, payload, quoteOptions, slotFiles, workflow, records, facts, errorText };
