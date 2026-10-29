const A = require('./account');
const X = require('./access');
const O = require('./organization');
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
  return company.periods[period] || (company.periods[period] = { tasks: [], archives: [], payoutLinks: [], commands: {} });
}

function readCycle(companyId, period) {
  return store().companies[companyId]?.periods?.[period] || { tasks: [], archives: [], payoutLinks: [], commands: {} };
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

function parseBonus(value) {
  if (value === '' || value === null || value === undefined) return 0;
  X.assert(/^\d+(\.\d{1,2})?$/.test(String(value || '')) && Number(value) >= 0 && Number(value) <= 1000000, '奖金须为非负数，最多两位小数');
  return Math.round(Number(value) * 100);
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
  const assignment = assignmentEvidence(task);
  return !!assignment && assignment.personId === personId && assignment.staffIds.has(source.person);
}
const sourceLink = source => ({ id: source.id, name: [source.customer, source.name].filter(Boolean).join(' · '), date: source.date || source.accepted || '', url: source.url || '' });

function collectionSources(state, period, task, snapshot) {
  const F = require('./finance-source'), C = require('./commission-attribution');
  const owners = new Map((snapshot.customers || []).filter(customer => customer.projectId).map(customer => [customer.projectId, C.owner(customer)]));
  const samePerson = staffId => sourceMatchesTask({ person:staffId }, task);
  const inMonth = value => (String(value || '').includes('T') ? A.localDate(value) : String(value || '')).slice(0, 7) === period;
  const receiptAmount = receipt => receipt.revisions?.length ? receipt.revisions.at(-1).amount : receipt.amount;
  const receipts = (state.receipts || []).filter(receipt => ['已核算', '已备案'].includes(receipt.status) && inMonth(F.cashDate(receipt)) && samePerson(owners.get(receipt.projectId))).map(receipt => {
    const amount = Number(receiptAmount(receipt) || 0) / 100;
    return { id: receipt.id, name: (receipt.customer || '项目') + ' · 实际回款', date: F.cashDate(receipt), amount, amountText: amount + '元', url: '/pages/admin/index?view=receipt&id=' + encodeURIComponent(receipt.id) };
  });
  const receiptById = new Map((state.receipts || []).map(receipt => [receipt.id, receipt]));
  const refunds = (state.refunds || []).filter(refund => refund.status === '已核实' && inMonth(F.cashDate(refund))).filter(refund => {
    const receipt = receiptById.get(refund.receiptId);
    return samePerson(owners.get(receipt?.projectId || refund.projectId));
  }).map(refund => {
    const amount = -Number(refund.amount || 0) / 100;
    return { id: refund.id, name: (refund.customer || '项目') + ' · 已核实退款', date: F.cashDate(refund), amount, amountText: amount + '元', url: '/pages/admin/index?view=refund-result&id=' + encodeURIComponent(refund.id) };
  });
  return receipts.concat(refunds);
}

function automaticEvidence(state, period, task, factsProvider) {
  if (task.type === 'manual') return { value: 0, sources: [] };
  const sources = periodSources(state, period);
  const own = sources.filter(source => sourceMatchesTask(source, task) && !source.excluded);
  let matched = [];
  if (task.type === 'auto:contract') matched = own.filter(source => source.id?.startsWith('perf-contract:'));
  if (task.type === 'auto:completedNodes') matched = own.filter(source => source.kind === 'node' && source.completed);
  if (task.type === 'auto:onTimeNodes') matched = own.filter(source => source.kind === 'node' && source.completed && source.onTime);
  if (task.type === 'auto:publications') matched = own.filter(source => source.kind === 'publication');
  if (task.type === 'auto:effectiveLeads') matched = own.filter(source => source.kind === 'lead');
  if (task.type === 'auto:qualifiedDeliveries') matched = own.filter(source => source.kind === 'material');
  if (task.type === 'auto:collectionsAmount') {
    X.assert(typeof factsProvider === 'function', '绩效事实提供器未注入');
    const snapshot = factSnapshot(state, period, sources), facts = factsProvider(snapshot);
    const value = Object.entries(facts).filter(([staffId]) => sourceMatchesTask({ person:staffId }, task)).reduce((sum, [, departments]) => sum + Number(departments.business?.collectionsAmount || 0), 0);
    return { value, sources: collectionSources(state, period, task, snapshot) };
  }
  const links = matched.map(source => {
    const link = sourceLink(source);
    if (task.type !== 'auto:contract') return link;
    const amount = Number(source.amount || 0) / 100;
    return { ...link, amount, amountText: amount + '元' };
  });
  return { value: task.type === 'auto:contract' ? links.reduce((sum, source) => sum + source.amount, 0) : links.length, sources: links };
}

const automaticValue = (state, period, task, factsProvider) => automaticEvidence(state, period, task, factsProvider).value;

function resolved(state, task, factsProvider) {
  const evidence = automaticEvidence(state, task.period, task, factsProvider), collected = evidence.value;
  const completed = task.type === 'manual' ? !!task.manualDone : collected >= task.target;
  return { ...clone(task), collected, sourceIds: evidence.sources.map(source => source.id), sources: evidence.sources, status: completed ? '已完成' : '进行中', bonusAwarded: completed ? task.bonus : 0 };
}

function sourceSignature(state, period, tasks, factsProvider) {
  const sources = periodSources(state, period);
  const receipts = (state.receipts || []).map(row => [row.id, row.projectId, row.status, row.amount, row.bankDate || row.date, row.revisions?.at(-1)?.amount || '']).sort();
  const refunds = (state.refunds || []).map(row => [row.id, row.receiptId, row.projectId, row.status, row.amount, row.bankDate || row.date]).sort();
  const values = tasks.map(task => [task.id, automaticValue(state, period, task, factsProvider)]).sort();
  return JSON.stringify([P.signature(sources), receipts, refunds, values]);
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
  const target = parseTarget(type, input.target), bonus = parseBonus(input.bonus);
  return transact(state, period, input, 'create', actor, () => {}, cycle => {
    X.assert(!latestArchive(cycle), '本月任务已归档，不能再新增');
    const at = now(), id = 'performance-task:' + state.companyId + ':' + period + ':' + Date.now() + ':' + (++sequence);
    const assigneePersonId = assignee.personId || assignee.id;
    const task = {
      id, period, title, assigneeId: assignee.id, assigneePersonId, assigneeStaffIds:(state.staff || []).filter(person => (person.personId || person.id) === assigneePersonId).map(person => person.id), assigneeName: assignee.name, due: input.due, type: type.id, target, bonus,
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
    tasks, sourceSignature: source, bonusTotal: tasks.reduce((sum, task) => sum + task.bonusAwarded, 0),
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

function canSeeBonus(actor) {
  return X.isBoss(actor) || O.has(actor.role, 'finance');
}

function bonusSummary(state, period, factsProvider) {
  const actor = actorFor(state);
  X.assert(canSeeBonus(actor), '奖金归档仅限本组织老板和财务查看');
  const cycle = readCycle(state.companyId, period), latest = latestArchive(cycle);
  const needsRevision = !!latest && sourceSignature(state, period, latest.tasks, factsProvider) !== latest.sourceSignature;
  return { period, latest: clone(latest), archives: clone(cycle.archives || []), payoutLinks: clone(cycle.payoutLinks || []), needsRevision };
}

function findArchive(state, id) {
  const periods = store().companies[state.companyId]?.periods || {};
  for (const [period, cycle] of Object.entries(periods)) {
    const archive = (cycle.archives || []).find(row => row.id === id);
    if (archive) return { period, cycle, archive };
  }
  return null;
}

function payoutDraft(state, archiveId, factsProvider) {
  const actor = actorFor(state);
  X.assert(canSeeBonus(actor), '奖金应付草案仅限本组织老板和财务查看');
  const found = findArchive(state, archiveId);
  X.assert(found, '绩效归档不存在');
  const { archive } = found;
  X.assert(latestArchive(found.cycle)?.id === archive.id, '请使用最新归档修订版生成奖金应付草案');
  X.assert(sourceSignature(state, archive.period, archive.tasks, factsProvider) === archive.sourceSignature, '原业务事实已变化，请先修订归档');
  return {
    idempotencyKey: 'performance-task-bonus:' + archive.id,
    sourceType: 'performance-task-bonus-v1', companyId: state.companyId, period: archive.period, archiveId: archive.id,
    total: archive.bonusTotal,
    lines: archive.tasks.filter(task => task.bonusAwarded > 0).map(task => ({ taskId: task.id, assigneeId: task.assigneeId, assigneePersonId: task.assigneePersonId, title: task.title, amount: task.bonusAwarded }))
  };
}

function linkPayout(state, archiveId, input, factsProvider) {
  const actor = actorFor(state);
  X.assert(canSeeBonus(actor), '奖金衔接仅限本组织老板和财务办理');
  const found = findArchive(state, archiveId);
  X.assert(found, '绩效归档不存在');
  X.assert(latestArchive(found.cycle)?.id === archiveId, '请关联最新归档修订版');
  X.assert(sourceSignature(state, found.archive.period, found.archive.tasks, factsProvider) === found.archive.sourceSignature, '原业务事实已变化，请先修订归档');
  const referenceId = String(input.referenceId || '').trim(), expectedKey = 'performance-task-bonus:' + archiveId;
  X.assert(referenceId, '请提供已创建的外部应付引用');
  X.assert(input.idempotencyKey === expectedKey, '奖金衔接键与归档版本不匹配');
  const value = store(), cycle = value.companies[state.companyId].periods[found.period];
  const existing = (cycle.payoutLinks || []).find(row => row.archiveId === archiveId || row.idempotencyKey === expectedKey);
  if (existing) {
    X.assert(existing.referenceId === referenceId, '该归档奖金已关联其他应付记录');
    return clone(existing);
  }
  const link = { archiveId, idempotencyKey: expectedKey, referenceId, ...X.actor() };
  cycle.payoutLinks = (cycle.payoutLinks || []).concat(link);
  save(value);
  return clone(link);
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
        due, type:type.id, target:evidence.value, bonus:0, manualDone:null,
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
  actorFor(state);
  const previous = previousPeriod(String(input.period || state.period));
  const assignee = staffFor(state, input.assigneeId);
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
    bonusSummary: (state, period) => bonusSummary(state, period, factsProvider),
    payoutDraft: (state, archiveId) => payoutDraft(state, archiveId, factsProvider),
    linkPayout: (state, archiveId, input) => linkPayout(state, archiveId, input, factsProvider),
    initializePrevious: (state, input) => initializePrevious(state, input, factsProvider),
    previousActual: (state, input) => previousActual(state, input, factsProvider)
  };
}

module.exports = { KEY, TYPES, withFacts };
