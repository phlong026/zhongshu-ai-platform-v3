// Historical local admin fixture; never import from miniprogram production code.
const A = require('../../../miniprogram/utils/account');
const N = require('../../../miniprogram/utils/organization');
const X = require('../../../miniprogram/utils/access');

const clone = require('../../../miniprogram/utils/clone');
const ACTIONS = ['management-company', 'management-assignments', 'approval-settings'];
const ASSIGNMENTS = [
  { id: 'sales', label: '业务默认经办人', role: 'sales', orgTypes: ['store'] },
  { id: 'engineering', label: '工程默认经办人', role: 'engineer', orgTypes: ['store'] },
  { id: 'finance', label: '财务默认经办人', role: 'finance', orgTypes: ['store', 'headquarters'] },
  { id: 'design', label: '设计行政默认经办人', role: 'hq-design-admin', orgTypes: ['headquarters'] },
  { id: 'operations', label: '运营默认经办人', role: 'hq-operations', orgTypes: ['headquarters'] }
];
const FIXED_RULES = [
  { id: 'moneyApproval', label: '金额事项', value: '本组织老板审批' },
  { id: 'designReview', label: '设计成果及施工图', value: '线下专业审核，行政登记下发' },
  { id: 'financeRegister', label: '实际收付款', value: '财务登记实际收付，总部独立核实设计款到账' },
  { id: 'headquartersReceipt', label: '总部设计款到账', value: '总部财务独立核实，不由门店切换' }
];

function contextFor(state) {
  const actor = X.context();
  X.assert(actor.active && actor.companyId === state.companyId && ['boss', 'owner'].includes(actor.role), '当前组织管理身份无权查看配置');
  return actor;
}

function companyDefaults(company, settings, companyId) {
  const stored = settings.company || {};
  return {
    companyId,
    displayName: stored.displayName || company?.store || company?.name || '',
    contact: stored.contact || company?.contact || '',
    phone: stored.phone || company?.phone || '',
    address: stored.address || company?.address || ''
  };
}

function assignmentFields(state, actor, settings) {
  return ASSIGNMENTS.filter(field => field.orgTypes.includes(actor.orgType)).map(field => {
    const options = (state.staff || []).filter(person => person.active !== false && N.staffHas(person, field.role)).map(person => ({ id: person.id, name: person.name }));
    const current = settings.assignments?.[field.id];
    return { ...field, value: options.some(option => option.id === current) ? current : options[0]?.id || '', options };
  });
}

function load(input) {
  const state = input.state || input;
  const actor = contextFor(state);
  const settings = state.managementSettings || { version: 0, company: {}, assignments: {}, history: [], events: [] };
  const company = A.company(A.load());
  return {
    version: settings.version || 0,
    canEdit: actor.role === 'boss',
    company: companyDefaults(company, settings, state.companyId),
    assignmentFields: assignmentFields(state, actor, settings),
    approvals: { expenseLine: settings.approvals?.expenseLine ?? 5000 },
    fixedRules: FIXED_RULES,
    lastUpdated: settings.at ? A.displayTime(settings.at) : ''
  };
}

function validateCompany(form) {
  const displayName = String(form.displayName || '').trim();
  const contact = String(form.contact || '').trim();
  const phone = String(form.phone || '').trim();
  const address = String(form.address || '').trim();
  X.assert(displayName && displayName.length <= 50, '请填写50字以内的门店名称');
  X.assert(contact && contact.length <= 30, '请填写30字以内的联系人');
  X.assert(/^(1\d{10}|0\d{2,3}-?\d{7,8})$/.test(phone), '请输入正确的联系电话');
  X.assert(address && address.length <= 120, '请填写120字以内的门店地址');
  return { displayName, contact, phone, address };
}

function validateAssignments(state, actor, form, previous) {
  const values = { ...(previous || {}), ...(form.assignments || {}) };
  const fields = assignmentFields(state, actor, { assignments: previous || {} });
  const fieldById = Object.fromEntries(fields.map(field => [field.id, field]));
  for (const [id, personId] of Object.entries(values)) {
    const field = fieldById[id];
    X.assert(field, '当前组织不支持该职责分工');
    const person = (state.staff || []).find(row => row.id === personId);
    X.assert(person && person.active !== false, '请选择本组织在岗人员');
    X.assert(N.staffHas(person, field.role), '所选人员岗位与职责不匹配');
  }
  return values;
}

function validateApprovals(form) {
  const line = Math.round(Number(form.expenseLine));
  X.assert(Number.isFinite(line) && line >= 0 && line <= 100000000, '请填写有效的费用线金额（元），0 表示全部费用需老板确认');
  return { expenseLine: line };
}

function apply(state, action, id, form = {}) {
  if (!ACTIONS.includes(action)) return null;
  const actor = X.context();
  X.assert(actor.active && actor.role === 'boss' && actor.companyId === state.companyId && id === state.companyId, '仅本组织老板可以保存管理配置');
  const current = state.managementSettings || { version: 0, company: {}, assignments: {}, history: [], events: [] };
  X.assert(Number(form.expectedVersion || 0) === Number(current.version || 0), '管理配置已更新，请刷新后重试');
  const previous = current.version ? clone(current) : null;
  if (previous) { delete previous.history; delete previous.events; }
  const next = {
    version: (current.version || 0) + 1,
    company: clone(current.company || {}),
    assignments: clone(current.assignments || {}),
    approvals: clone(current.approvals || {}),
    history: [...(current.history || []), ...(previous ? [previous] : [])],
    events: clone(current.events || []),
    ...X.actor(actor)
  };
  if (action === 'management-company') next.company = validateCompany(form);
  else if (action === 'approval-settings') next.approvals = validateApprovals(form);
  else next.assignments = validateAssignments(state, actor, form, current.assignments);
  next.events.push({ action, version: next.version, ...X.actor(actor) });
  state.managementSettings = next;
  return { id };
}

const saveCompany = (state, id, form) => apply(state, 'management-company', id, form);
const saveAssignments = (state, id, form) => apply(state, 'management-assignments', id, form);

module.exports = { ACTIONS, ASSIGNMENTS, FIXED_RULES, load, saveCompany, saveAssignments, apply };
