// Explicit handover flags live beside the management ledger because project work is rebuilt on every load.
const X = require('./access');

const clone = value => JSON.parse(JSON.stringify(value));
const kinds = ['customer-responsibility', 'project-responsibility'];

function flags(s) {
  s.managementHandoverFlags = s.managementHandoverFlags || {};
  return s.managementHandoverFlags;
}

function workFor(s, workId) {
  return (s.work || []).find(row => row.id === workId);
}

function sourceFor(work) {
  if (work.kind === 'customer-responsibility') {
    return (wx.getStorageSync('zhongshu-business-v3')?.customers || []).find(row => row.id === work.customerId && row.projectId === work.projectId);
  }
  return (wx.getStorageSync('zhongshu-engineering-v1')?.projects || []).find(row => row.id === work.engineeringId && (row.projectId || row.id) === work.projectId);
}

function transferable(work) {
  return !!work && (kinds.includes(work.kind) || !work.kind && (['内容任务','方案沟通'].includes(work.name) || String(work.name || '').includes('跟进')));
}

function validWork(s, work) {
  if (!transferable(work) || !(s.staff || []).some(person => person.id === work.owner)) return false;
  if (!work.kind) return true;
  const source = sourceFor(work);
  return !!source && (source.companyId || 'quanzhou') === s.companyId;
}

function latestTransfer(s, work) {
  return (s.transfers || []).filter(row => row.workId === work.id && row.to === work.owner && row.from !== row.to && row.effectiveAt).sort((a,b) => String(a.effectiveAt).localeCompare(String(b.effectiveAt))).at(-1) || null;
}

function afterFloor(value, floor) {
  if (!floor || String(value) > String(floor)) return value;
  const stamp = Date.parse(floor);
  return Number.isNaN(stamp) ? value : new Date(stamp + 1).toISOString();
}

function load(s, workId) {
  const current = (s.managementHandoverFlags || {})[workId];
  if (!current) return null;
  const work = workFor(s, workId), transfer = work && latestTransfer(s, work);
  if (current.active && transfer && String(transfer.effectiveAt) >= String(current.markedAt)) {
    return { ...clone(current), active:false, closedAt:transfer.effectiveAt, closedByTransferId:transfer.id, closedTo:transfer.to };
  }
  return clone(current);
}

function pending(s, work) {
  return load(s, work.id)?.active === true;
}

function overlay(s) {
  for (const work of s.work || []) {
    const flag = load(s, work.id);
    if (!flag) continue;
    work.handoverRequired = flag.active;
    work.handoverRequiredAt = flag.markedAt;
    work.handoverReason = flag.reason;
    if (!flag.active) {
      work.handoverClosedAt = flag.closedAt;
      work.handoverClosedByTransferId = flag.closedByTransferId;
    }
  }
  return s;
}

function closeTransferred(s, transfer) {
  const work = workFor(s, transfer?.workId), current = work && flags(s)[work.id];
  const persisted = (s.transfers || []).find(row => row.id === transfer?.id);
  if (!current?.active || !persisted || persisted.to !== work.owner || persisted.from === persisted.to || !persisted.effectiveAt || String(persisted.effectiveAt) < String(current.markedAt)) return false;
  current.active = false;
  current.closedAt = persisted.effectiveAt;
  current.closedByTransferId = persisted.id;
  current.closedTo = persisted.to;
  current.history.push({ action:'close', version:current.version, transferId:persisted.id, from:persisted.from, to:persisted.to, at:persisted.effectiveAt, actorId:persisted.actorId, personId:persisted.personId, userId:persisted.userId, role:persisted.role, by:persisted.by });
  s.events = s.events || [];
  s.events.push({ action:'handover-close', id:work.id, transferId:persisted.id, from:persisted.from, to:persisted.to, effectiveAt:persisted.effectiveAt, actorId:persisted.actorId, personId:persisted.personId, userId:persisted.userId, companyId:s.companyId, role:persisted.role, by:persisted.by, at:persisted.effectiveAt });
  return true;
}

function apply(s, action, workId, form = {}) {
  if (action !== 'handover-mark') return null;
  const actor = X.context();
  X.assert(actor.active && actor.companyId === s.companyId, '当前人员或组织未授权');
  X.assert(X.isBoss(actor), '仅本组织老板或门店负责人可以明确标记需交接责任');
  const work = workFor(s, workId);
  X.assert(validWork(s, work), '仅能标记本组织有效内部责任事项');
  const reason = String(form.reason || form.note || '').trim();
  X.assert(form.checked && reason, '请填写交接原因并完成核对');

  const transfer = latestTransfer(s, work);
  if (transfer) closeTransferred(s, transfer);
  const current = flags(s)[workId];
  X.assert(!current?.active, '该责任事项已标记待交接');
  const identity = X.actor(actor), version = (current?.version || 0) + 1;
  const markedAt = afterFloor(identity.at, transfer?.effectiveAt);
  const mark = { action:'mark', version, reason, actorId:identity.actorId, personId:identity.personId, userId:identity.userId, companyId:identity.companyId, role:identity.role, by:identity.by, at:markedAt };
  flags(s)[workId] = {
    id:'management-handover:' + workId, workId, companyId:s.companyId, kind:work.kind, customerId:work.customerId || '', engineeringId:work.engineeringId || '', projectId:work.projectId,
    active:true, reason, markedAt, version, actorId:identity.actorId, personId:identity.personId, userId:identity.userId, role:identity.role, by:identity.by,
    history:[...(current?.history || []), mark]
  };
  s.events = s.events || [];
  s.events.push({ action:'handover-mark', id:workId, text:'明确标记需交接责任', reason, version, ...identity, at:markedAt });
  return { id:workId };
}

module.exports = { apply, load, overlay, pending, closeTransferred, transferable };
