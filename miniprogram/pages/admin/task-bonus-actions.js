// Turn a verified performance archive into local payables. Actual payments stay in the existing finance flow.
const X = require('../../utils/access');
const O = require('../../utils/organization');
const F = require('../../utils/finance-source');
const Facts = require('./owner-performance').facts;
const Tasks = require('../../utils/performance-tasks').withFacts(Facts);

const ACTION = 'performance-bonus-register';
const stableId = (companyId, period, personId) => ['performance-bonus-payable', companyId, period, encodeURIComponent(personId)].join(':');
const batchReference = (companyId, period) => ['performance-bonus-batch', companyId, period].join(':');
const personOf = line => line.personId || line.assigneePersonId;

function groups(state, draft) {
  const grouped = new Map();
  for (const line of draft.lines || []) {
    const personId = personOf(line);
    X.assert(personId && Number.isInteger(line.amount) && line.amount > 0, '奖金归档的人员或金额不完整');
    const current = grouped.get(personId) || { personId, amount:0, taskIds:[], assigneeIds:[] };
    current.amount += line.amount;
    current.taskIds.push(line.taskId);
    if (line.assigneeId) current.assigneeIds.push(line.assigneeId);
    grouped.set(personId, current);
  }
  const rows = [...grouped.values()].map(group => {
    const person = (state.staff || []).find(row => (row.personId || row.id) === group.personId);
    X.assert(person, '奖金归属人员不存在，请先核对人员身份');
    return { ...group, name:person.name, taskIds:[...new Set(group.taskIds)].sort(), assigneeIds:[...new Set(group.assigneeIds)].sort() };
  });
  X.assert(rows.reduce((sum, row) => sum + row.amount, 0) === draft.total, '奖金草案汇总与归档总额不一致');
  return rows;
}

function expectedPayable(state, draft, group, actor) {
  const archive = {
    kind:'performance-task-bonus-v1', archiveId:draft.archiveId, draftKey:draft.idempotencyKey,
    companyId:state.companyId, period:draft.period, personId:group.personId,
    taskIds:group.taskIds, assigneeIds:group.assigneeIds,
    batchReference:batchReference(state.companyId, draft.period)
  };
  return {
    id:stableId(state.companyId, draft.period, group.personId), companyId:state.companyId, projectId:'company:' + state.companyId,
    customer:'公司绩效', name:'月度绩效奖金', stage:draft.period + ' 绩效奖金', total:group.amount, recipient:group.name,
    applicantId:actor.staffId, applicantPersonId:actor.personId, performanceArchive:archive, status:'待办理'
  };
}

function samePayload(payable, expected) {
  return payable.id === expected.id && payable.companyId === expected.companyId && payable.projectId === expected.projectId
    && payable.total === expected.total && payable.recipient === expected.recipient
    && JSON.stringify(payable.performanceArchive) === JSON.stringify(expected.performanceArchive);
}

function related(payable, archive) {
  const source = payable?.performanceArchive;
  return source?.kind === archive.kind && source.companyId === archive.companyId
    && source.period === archive.period && source.personId === archive.personId;
}

function apply(state, action, archiveId, form = {}) {
  if (action !== ACTION) return null;
  const actor = X.context();
  X.assert(actor.active && actor.companyId === state.companyId && (X.isBoss(actor) || O.has(actor.role, 'finance')), '仅本组织老板或财务可生成奖金应付');
  const draft = Tasks.payoutDraft(state, archiveId);
  X.assert(form.idempotencyKey === draft.idempotencyKey, '奖金归档版本已变化，请刷新后重试');
  const nextGroups = groups(state, draft);
  const nextPeople = new Set(nextGroups.map(group => group.personId));
  const stale = (state.payables || []).filter(row => row.performanceArchive?.kind === 'performance-task-bonus-v1'
    && row.performanceArchive.companyId === state.companyId && row.performanceArchive.period === draft.period
    && !nextPeople.has(row.performanceArchive.personId));
  X.assert(!stale.some(row => F.reserved(state, row.id) > 0), '旧版奖金应付已有付款或预留，不能核销，请人工对账');
  const ids = [];
  for (const group of nextGroups) {
    const expected = expectedPayable(state, draft, group, actor);
    const occupied = (state.payables || []).filter(row => related(row, expected.performanceArchive));
    const current = (state.payables || []).find(row => row.id === expected.id);
    if (current?.performanceArchive?.archiveId === draft.archiveId) {
      X.assert(samePayload(current, expected), '稳定应付编号对应内容不一致，请停止并核对');
      ids.push(current.id);
      continue;
    }
    X.assert(!current || related(current, expected.performanceArchive), '稳定应付编号已被其他事项占用');
    X.assert(!occupied.some(row => row.id !== expected.id), '同一人员月份存在其他奖金应付，请停止并核对');
    X.assert(!current || F.reserved(state, current.id) === 0, '旧版奖金应付已有付款或预留，不能覆盖，请人工对账');
    if (current) Object.assign(current, expected, { updatedAt:X.actor().at });
    else state.payables.push(expected);
    ids.push(expected.id);
  }
  if (stale.length) {
    const staleIds = new Set(stale.map(row => row.id));
    state.payables = state.payables.filter(row => !staleIds.has(row.id));
  }
  return { id:ids.length === 1 ? ids[0] : batchReference(state.companyId, draft.period), payableIds:ids };
}

function validatePayment(state, payable) {
  if (payable?.performanceArchive?.kind !== 'performance-task-bonus-v1') return true;
  const draft = Tasks.payoutDraft(state, payable.performanceArchive.archiveId);
  const group = groups(state, draft).find(row => row.personId === payable.performanceArchive.personId);
  X.assert(group, '当前奖金归档已无该人员应付，请停止付款并核对');
  const expected = expectedPayable(state, draft, group, X.context());
  X.assert(samePayload(payable, expected), '奖金应付金额、人员或归档内容已变化，请停止付款并核对');
  return true;
}

module.exports = { ACTION, apply, validatePayment, stableId, batchReference };
