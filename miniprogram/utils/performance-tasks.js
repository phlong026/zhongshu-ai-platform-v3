const A = require('./account');
const X = require('./access');
const P = require('./performance');
const clone = require('./clone');

const KEY = require('./storage-keys').performanceTasks;
const TYPES = [
  { id: 'manual', label: '手动任务', unit: '', manual: true },
  { id: 'auto:collectionsAmount', label: '回款金额', unit: '元', money: true },
  { id: 'auto:contract', label: '签约金额', unit: '元', money: true },
  { id: 'auto:completedNodes', label: '完成节点', unit: '个' },
  { id: 'auto:onTimeNodes', label: '按期节点', unit: '个' },
  { id: 'auto:publications', label: '内容发布', unit: '条' },
  { id: 'auto:effectiveLeads', label: '有效线索', unit: '条' },
  { id: 'auto:qualifiedDeliveries', label: '到货合格批次', unit: '批' }
];
const typeById = Object.fromEntries(TYPES.map(type => [type.id, type]));
let sequence = 0;

const validPeriod = value => /^\d{4}-(0[1-9]|1[0-2])$/.test(String(value || ''));
function validDay(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) return false;
  const date = new Date(value + 'T00:00:00.000Z');
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
const now = () => new Date().toISOString();

function store() {
  const value = wx.getStorageSync(KEY);
  return value?.version === 1 && value.companies ? clone(value) : { version: 1, companies: {} };
}

function save(value) {
  wx.setStorageSync(KEY, value);
}

function actorFor(state) {
  const actor = X.context();
  X.assert(actor.active && actor.companyId === state.companyId, '当前身份无权访问本组织绩效任务');
  return actor;
}

function companyOf(value, companyId) {
  return value.companies[companyId] || (value.companies[companyId] = { periods: {} });
}

function cycleOf(company, period) {
  return company.periods[period] || (company.periods[period] = { tasks: [], archives: [], commands: {} });
}

function readCycle(companyId, period) {
  return store().companies[companyId]?.periods?.[period] || { tasks: [], archives: [], commands: {} };
}

function payloadOf(input) {
  const value = clone(input || {});
  delete value.idempotencyKey;
  return JSON.stringify(value);
}

function transact(state, period, input, command, actor, validate, mutation) {
  const value = store(), company = companyOf(value, state.companyId), cycle = cycleOf(company, period);
  validate(cycle);
  const rawKey = String(input?.idempotencyKey || '').trim();
  const key = rawKey ? [command, actor.personId, rawKey].join(':') : '';
  const payload = payloadOf(input);
  if (key && cycle.commands[key]) {
    X.assert(cycle.commands[key].payload === payload, '同一幂等键不能用于不同操作');
    return clone(cycle.commands[key].result);
  }
  const result = mutation(cycle);
  if (key) cycle.commands[key] = { payload, result: clone(result), at: now() };
  save(value);
  return clone(result);
}

function staffFor(state, id) {
  const person = (state.staff || []).find(row => row.id === id);
  X.assert(person && person.active !== false, '只能选择本组织在岗人员');
  return person;
}

function parseTarget(type, value) {
  if (type.manual) return null;
  const pattern = type.money ? /^\d+(\.\d{1,2})?$/ : /^\d+$/;
  X.assert(pattern.test(String(value || '')) && Number(value) > 0, type.money ? '自动金额目标须为正数，最多两位小数' : '自动数量目标须为正整数');
  return Number(value);
}

function periodSources(state, period) {
  return P.sourceRows({ ...state, period });
}

function factSnapshot(state, period, sources) {
  const business = wx.getStorageSync(require('./storage-keys').business) || {};
  return {
    period,
    state: { ...state, sources },
    customers: (business.customers || []).filter(customer => (customer.companyId || 'quanzhou') === state.companyId),
    staff: state.staff || []
  };
}

const taskPersonId = (state, task) => task.assigneePersonId || (state.staff || []).find(person => person.id === task.assigneeId)?.personId || task.assigneeId;
function assignmentEvidence(task) {
  const row = (task.history || []).filter(entry => ['create', 'reassign'].includes(entry.action)).slice(-1)[0];
  if (!row?.to || !row?.toPersonId) return null;
  return { staffIds:new Set((task.assigneeStaffIds || []).concat(row.to)), personId:row.toPersonId };
}
function sourceMatchesTask(source, task) {
  const personId = task.assigneePersonId || task.assigneeId;
  if (source.personId) return source.personId === personId;
  if (source.person === personId) return true;
  return false;
}
const sourceLink = source => ({ id: source.id, name: [source.customer, source.name].filter(Boolean).join(' · '), date: source.date || source.accepted || '', url: source.url || '' });

function sourceMatchesType(source, type) {
  if (type === 'auto:contract') return source.id?.startsWith('perf-contract:');
  if (type === 'auto:completedNodes') return source.kind === 'node' && source.completed;
  if (type === 'auto:onTimeNodes') return source.kind === 'node' && source.completed && source.onTime;
  if (type === 'auto:publications') return source.kind === 'publication';
  if (type === 'auto:effectiveLeads') return source.kind === 'lead';
  if (type === 'auto:qualifiedDeliveries') return source.kind === 'material';
  return false;
}

function collectionOwner(customer) {
  const C = require('./commission-attribution'), order = customer.serviceOrders?.at(-1), staffId = C.owner(customer);
  const submitted = Date.parse(customer.businessFlow?.submittedAt || '');
  const transfer = (customer.responsibilityHistory || []).filter(row => row.from === staffId && row.fromPersonId
    && Number.isFinite(submitted) && Date.parse(row.effectiveAt || row.at || '') >= submitted)
    .sort((left, right) => Date.parse(left.effectiveAt || left.at) - Date.parse(right.effectiveAt || right.at))[0];
  return { person:staffId, personId:order?.responsiblePersonId || customer.businessFlow?.responsiblePersonId || transfer?.fromPersonId || '' };
}

function collectionSources(state, period, task, snapshot) {
  const F = require('./finance-source');
  const owners = new Map((snapshot.customers || []).filter(customer => customer.projectId).map(customer => [customer.projectId, collectionOwner(customer)]));
  const assignment = assignmentEvidence(task), unresolved = owner => !!owner?.person && !owner.personId
    && owner.person !== (task.assigneePersonId || task.assigneeId) && assignment?.staffIds.has(owner.person);
  const inMonth = value => (String(value || '').includes('T') ? A.localDate(value) : String(value || '')).slice(0, 7) === period;
  const receiptAmount = receipt => receipt.revisions?.length ? receipt.revisions.at(-1).amount : receipt.amount;
  const eligibleReceipts = (state.receipts || []).filter(receipt => ['已核算', '已备案'].includes(receipt.status) && inMonth(F.cashDate(receipt)));
  const receipts = eligibleReceipts.filter(receipt => sourceMatchesTask(owners.get(receipt.projectId) || {}, task)).map(receipt => {
    const amount = Number(receiptAmount(receipt) || 0) / 100;
    return { id: receipt.id, name: (receipt.customer || '项目') + ' · 实际回款', date: F.cashDate(receipt), amount, amountText: amount + '元', url: '/pages/admin/index?view=receipt&id=' + encodeURIComponent(receipt.id) };
  });
  const receiptById = new Map((state.receipts || []).map(receipt => [receipt.id, receipt]));
  const eligibleRefunds = (state.refunds || []).filter(refund => refund.status === '已核实' && inMonth(F.cashDate(refund)));
  const refunds = eligibleRefunds.filter(refund => {
    const receipt = receiptById.get(refund.receiptId);
    return sourceMatchesTask(owners.get(receipt?.projectId || refund.projectId) || {}, task);
  }).map(refund => {
    const amount = -Number(refund.amount || 0) / 100;
    return { id: refund.id, name: (refund.customer || '项目') + ' · 已核实退款', date: F.cashDate(refund), amount, amountText: amount + '元', url: '/pages/admin/index?view=refund-result&id=' + encodeURIComponent(refund.id) };
  });
  const unresolvedSources = eligibleReceipts.filter(receipt => unresolved(owners.get(receipt.projectId))).map(receipt => ({
    id:receipt.id, name:(receipt.customer || '项目') + ' · 回款责任人待核对', date:F.cashDate(receipt), url:'/pages/admin/index?view=receipt&id=' + encodeURIComponent(receipt.id)
  })).concat(eligibleRefunds.filter(refund => {
    const receipt = receiptById.get(refund.receiptId);
    return unresolved(owners.get(receipt?.projectId || refund.projectId));
  }).map(refund => ({ id:refund.id, name:(refund.customer || '项目') + ' · 退款责任人待核对', date:F.cashDate(refund), url:'/pages/admin/index?view=refund-result&id=' + encodeURIComponent(refund.id) })));
  return { sources:receipts.concat(refunds), unresolvedSources };
}

function automaticEvidence(state, period, task, factsProvider) {
  if (task.type === 'manual') return { value: 0, sources: [], unresolvedSources: [] };
  const sources = periodSources(state, period);
  const relevant = sources.filter(source => sourceMatchesType(source, task.type) && !source.excluded);
  const own = relevant.filter(source => sourceMatchesTask(source, task));
  const assignment = assignmentEvidence(task);
  const unresolvedSources = relevant.filter(source => !source.personId && source.person !== (task.assigneePersonId || task.assigneeId)
    && assignment?.staffIds.has(source.person));
  const matched = own;
  if (task.type === 'auto:collectionsAmount') {
    X.assert(typeof factsProvider === 'function', '绩效事实提供器未注入');
    const snapshot = factSnapshot(state, period, sources), facts = factsProvider(snapshot);
    X.assert(facts && typeof facts === 'object', '绩效事实提供器返回无效');
    const evidence = collectionSources(state, period, task, snapshot);
    return { value:evidence.sources.reduce((sum, source) => sum + source.amount, 0), ...evidence };
  }
  const links = matched.map(source => {
    const link = sourceLink(source);
    if (task.type !== 'auto:contract') return link;
    const amount = Number(source.amount || 0) / 100;
    return { ...link, amount, amountText: amount + '元' };
  });
  return { value: task.type === 'auto:contract' ? links.reduce((sum, source) => sum + source.amount, 0) : links.length, sources: links, unresolvedSources: unresolvedSources.map(sourceLink) };
}

const automaticValue = (state, period, task, factsProvider) => automaticEvidence(state, period, task, factsProvider).value;

function resolved(state, task, factsProvider) {
  const evidence = automaticEvidence(state, task.period, task, factsProvider), collected = evidence.value;
  const completed = task.type === 'manual' ? !!task.manualDone : collected >= task.target;
  const attributionPending = !completed && evidence.unresolvedSources.length > 0;
  return { ...clone(task), collected, sourceIds: evidence.sources.map(source => source.id), sources: evidence.sources, attributionPending, attributionSources:evidence.unresolvedSources, status: completed ? '已完成' : attributionPending ? '归属待核对' : '进行中' };
}

function sourceSignature(state, period, tasks, factsProvider) {
  const sources = periodSources(state, period);
  const F = require('./finance-source'), inPeriod = row => String(F.cashDate(row) || '').slice(0, 7) === period;
  const receipts = (state.receipts || []).filter(inPeriod).map(row => [row.id, row.projectId, row.status, row.amount, row.bankDate || row.date, row.revisions?.at(-1)?.amount || '']).sort();
  const refunds = (state.refunds || []).filter(inPeriod).map(row => [row.id, row.receiptId, row.projectId, row.status, row.amount, row.bankDate || row.date]).sort();
  const values = tasks.map(task => [task.id, automaticValue(state, period, task, factsProvider)]).sort();
  const sourcePeople = sources.map(source => [source.id, source.person, source.personId || '']).sort((left, right) => left[0].localeCompare(right[0]));
  return JSON.stringify([P.signature(sources), sourcePeople, receipts, refunds, values]);
}

function latestArchive(cycle) {
  return (cycle.archives || []).slice().sort((left, right) => right.revision - left.revision)[0] || null;
}

function create(state, input) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可派发绩效任务');
  const period = String(input.period || state.period || '');
  X.assert(validPeriod(period), '请选择有效月份');
  const type = typeById[input.type];
  X.assert(type, '不支持的绩效任务类型');
  const title = String(input.title || '').trim();
  X.assert(title && title.length <= 60, '任务标题为必填且最多 60 字');
  X.assert(validDay(input.due) && input.due.slice(0, 7) === period, '截止日期须在任务月份内');
  const assignee = staffFor(state, input.assigneeId);
  const target = parseTarget(type, input.target);
  return transact(state, period, input, 'create', actor, () => {}, cycle => {
    X.assert(!latestArchive(cycle), '本月任务已归档，不能再新增');
    if (!type.manual) X.assert(!cycle.tasks.some(task => task.type === type.id && taskPersonId(state, task) === (assignee.personId || assignee.id)
      && task.title === title && task.target === target && task.due === input.due), '相同自动任务已存在，请勿重复派发');
    const at = now(), id = 'performance-task:' + state.companyId + ':' + period + ':' + Date.now() + ':' + (++sequence);
    const assigneePersonId = assignee.personId || assignee.id;
    const task = {
      id, period, title, assigneeId: assignee.id, assigneePersonId, assigneeStaffIds:(state.staff || []).filter(person => (person.personId || person.id) === assigneePersonId).map(person => person.id), assigneeName: assignee.name, due: input.due, type: type.id, target,
      manualDone: null,
      history: [{ action: 'create', to: assignee.id, toPersonId: assignee.personId || assignee.id, note: String(input.note || '').trim(), ...X.actor(), at }],
      createdAt: at, createdBy: actor.staffId
    };
    cycle.tasks.push(task);
    return task;
  });
}

function reassign(state, id, input) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可改派绩效任务');
  const assignee = staffFor(state, input.assigneeId);
  const periods = store().companies[state.companyId]?.periods || {};
  const period = Object.keys(periods).find(key => periods[key].tasks?.some(task => task.id === id));
  X.assert(period, '绩效任务不存在');
  return transact(state, period, input, 'reassign', actor, cycle => {
    X.assert(cycle.tasks.some(row => row.id === id), '绩效任务不存在');
  }, cycle => {
    X.assert(!latestArchive(cycle), '本月任务已归档，不能改派');
    const task = cycle.tasks.find(row => row.id === id);
    if (task.assigneeId === assignee.id) return task;
    const from = task.assigneeId, fromPersonId = task.assigneePersonId;
    task.assigneeId = assignee.id;
    task.assigneePersonId = assignee.personId || assignee.id;
    task.assigneeStaffIds = (state.staff || []).filter(person => (person.personId || person.id) === task.assigneePersonId).map(person => person.id);
    task.assigneeName = assignee.name;
    task.manualDone = null;
    task.history.push({ action: 'reassign', from, fromPersonId, to: assignee.id, toPersonId: task.assigneePersonId, note: String(input.note || '').trim(), ...X.actor() });
    return task;
  });
}

function completeManual(state, id, input) {
  const actor = actorFor(state);
  const periods = store().companies[state.companyId]?.periods || {};
  const period = Object.keys(periods).find(key => periods[key].tasks?.some(task => task.id === id));
  X.assert(period, '绩效任务不存在');
  const note = String(input.note || '').trim(), photos = (input.photos || []).filter(Boolean);
  X.assert(note && note.length <= 500, '请填写手动完成说明，最多 500 字');
  X.assert(photos.length <= 6, '完成照片最多 6 张');
  return transact(state, period, input, 'complete-manual', actor, cycle => {
    const task = cycle.tasks.find(row => row.id === id);
    X.assert(task?.type === 'manual' && taskPersonId(state, task) === actor.personId, '只能提交本人的手动任务');
  }, cycle => {
    X.assert(!latestArchive(cycle), '本月任务已归档，不能补写完成凭证');
    const task = cycle.tasks.find(row => row.id === id);
    const previous = clone(task.manualDone);
    task.manualDone = { at: now(), note, photos: clone(photos), actorId: actor.staffId, personId: actor.personId };
    task.history.push({ action: 'manual-complete', note, photoCount: photos.length, previous, ...X.actor() });
    return task;
  });
}

function list(state, options = {}, factsProvider) {
  const actor = actorFor(state), period = String(options.period || state.period || '');
  X.assert(validPeriod(period), '请选择有效月份');
  const cycle = readCycle(state.companyId, period), archive = latestArchive(cycle);
  let rows = archive ? clone(archive.tasks).map(task => ({ ...task, frozen: true })) : (cycle.tasks || []).map(task => resolved(state, task, factsProvider));
  if (options.scope === 'company') X.assert(X.isBoss(actor), '只有本组织老板可查看组织任务');
  else rows = rows.filter(task => taskPersonId(state, task) === actor.personId);
  return rows.sort((left, right) => left.due.localeCompare(right.due));
}

function archiveRecord(state, period, cycle, note, revision, supersedes, factsProvider) {
  const baseTasks = revision === 1 ? cycle.tasks : latestArchive(cycle).tasks;
  const tasks = baseTasks.map(task => resolved(state, task, factsProvider));
  const source = sourceSignature(state, period, baseTasks, factsProvider);
  const id = 'performance-task-archive:' + state.companyId + ':' + period + ':r' + revision;
  return {
    id, companyId: state.companyId, period, revision, supersedes, status: '已归档',
    tasks, sourceSignature: source,
    note: String(note || '').trim(), ...X.actor()
  };
}

function archive(state, period, input = {}, factsProvider) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可归档绩效任务');
  X.assert(validPeriod(period), '请选择有效月份');
  return transact(state, period, input, 'archive', actor, () => {}, cycle => {
    X.assert(!latestArchive(cycle), '本月已归档');
    X.assert(cycle.tasks.length > 0, '本月暂无可归档绩效任务');
    const record = archiveRecord(state, period, cycle, input.note, 1, '', factsProvider);
    cycle.archives.push(record);
    return record;
  });
}

function reviseArchive(state, period, input = {}, factsProvider) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可修订归档');
  return transact(state, period, input, 'revise-archive', actor, cycle => {
    X.assert(latestArchive(cycle), '本月尚未归档');
  }, cycle => {
    const previous = latestArchive(cycle);
    X.assert(sourceSignature(state, period, previous.tasks, factsProvider) !== previous.sourceSignature, '原业务来源未变化，无需修订');
    const record = archiveRecord(state, period, cycle, input.note, previous.revision + 1, previous.id, factsProvider);
    cycle.archives.push(record);
    return record;
  });
}

function archiveSummary(state, period, factsProvider) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可查看任务归档');
  const cycle = readCycle(state.companyId, period), latest = latestArchive(cycle);
  const needsRevision = !!latest && sourceSignature(state, period, latest.tasks, factsProvider) !== latest.sourceSignature;
  return { period, latest: clone(latest), archives: clone(cycle.archives || []), needsRevision };
}

const previousPeriod = period => {
  const [year, month] = period.split('-').map(Number);
  return new Date(Date.UTC(year, month - 2, 1)).toISOString().slice(0, 7);
};
const periodEnd = period => {
  const [year, month] = period.split('-').map(Number);
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
};

function initializePrevious(state, input, factsProvider) {
  const actor = actorFor(state);
  X.assert(X.isBoss(actor), '只有本组织老板可按上月实绩初始化任务');
  const period = String(input.period || state.period || '');
  X.assert(validPeriod(period), '请选择有效月份');
  X.assert(!latestArchive(readCycle(state.companyId, period)), '本月任务已归档，不能重新初始化');
  const sourcePeriod = previousPeriod(period), due = periodEnd(period);
  const people = new Map();
  for (const staff of state.staff || []) {
    if (staff.active === false) continue;
    const personId = staff.personId || staff.id;
    const person = people.get(personId) || { personId, assignee: staff, staffIds: [] };
    person.staffIds.push(staff.id);
    people.set(personId, person);
  }
  const candidates = [];
  for (const person of people.values()) {
    for (const type of TYPES.filter(row => !row.manual)) {
      const basisTask = {
        type:type.id, period:sourcePeriod, assigneeId:person.assignee.id, assigneePersonId:person.personId, assigneeStaffIds:person.staffIds,
        history:[{ action:'create', to:person.assignee.id, toPersonId:person.personId }]
      };
      const evidence = automaticEvidence(state, sourcePeriod, basisTask, factsProvider);
      if (!(evidence.value > 0) || !evidence.sources.length) continue;
      candidates.push({ person, type, evidence });
    }
  }
  return transact(state, period, input, 'initialize-previous', actor, cycle => {
    X.assert(!latestArchive(cycle), '本月任务已归档，不能重新初始化');
  }, cycle => {
    const at = now(), created = [];
    for (const candidate of candidates) {
      const { person, type, evidence } = candidate;
      if (cycle.tasks.some(task => taskPersonId(state, task) === person.personId && task.type === type.id)) continue;
      const task = {
        id:['performance-task', state.companyId, period, 'suggested', person.personId, type.id].join(':'),
        period, title:type.label + '·上月实绩建议', assigneeId:person.assignee.id, assigneePersonId:person.personId, assigneeStaffIds:clone(person.staffIds), assigneeName:person.assignee.name,
        due, type:type.id, target:evidence.value, manualDone:null,
        suggestion:{ period:sourcePeriod, value:evidence.value, unit:type.unit, sourceIds:evidence.sources.map(source => source.id), sources:clone(evidence.sources) },
        history:[{ action:'create', to:person.assignee.id, toPersonId:person.personId, note:'按 ' + sourcePeriod + ' 真实实绩 ' + evidence.value + type.unit + ' 生成建议', ...X.actor(), at }],
        createdAt:at, createdBy:actor.staffId
      };
      created.push(task);
    }
    cycle.tasks.push(...created);
    return { period, sourcePeriod, created };
  });
}

function previousActual(state, input, factsProvider) {
  const actor = actorFor(state);
  const previous = previousPeriod(String(input.period || state.period));
  const assignee = staffFor(state, input.assigneeId);
  X.assert(X.isBoss(actor) || (assignee.personId || assignee.id) === actor.personId, '只有老板可读取其他人员的上月参考，本人仅可查看自己的记录');
  const task = { type: input.type, assigneeId: input.assigneeId, assigneePersonId: assignee.personId || assignee.id, period: previous };
  return { period: previous, value: automaticValue(state, previous, task, factsProvider), unit: typeById[input.type]?.unit || '' };
}

function withFacts(factsProvider) {
  X.assert(typeof factsProvider === 'function', '绩效事实提供器未注入');
  return {
    KEY, TYPES, create, reassign, completeManual,
    list: (state, options) => list(state, options, factsProvider),
    archive: (state, period, input) => archive(state, period, input, factsProvider),
    reviseArchive: (state, period, input) => reviseArchive(state, period, input, factsProvider),
    archiveSummary: (state, period) => archiveSummary(state, period, factsProvider),
    initializePrevious: (state, input) => initializePrevious(state, input, factsProvider),
    previousActual: (state, input) => previousActual(state, input, factsProvider)
  };
}

module.exports = { KEY, TYPES, withFacts };
