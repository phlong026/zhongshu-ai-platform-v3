const A = require('../../utils/account');
const C = require('../../utils/commission-attribution');
const F = require('../../utils/finance-source');
const N = require('../../utils/organization');
const X = require('../../utils/access');

const clone = require('../../utils/clone');
const DEPARTMENTS = [
  { id: 'business', label: '业务', roles: ['sales', 'sales-lead', 'hq-operations', 'hq-operations-lead', 'hq-design-admin'], primary: 'commissionAmount', metrics: [
    ['commissionAmount', '设计委托金额', '元'], ['collectionsAmount', '项目实际回款', '元'], ['effectiveCustomers', '有效跟进客户', '个'],
    ['activityTotal', '发布与有效线索', '条'], ['publications', '已登记发布', '条'], ['effectiveLeads', '有效线索', '条'],
    ['approvedDeliverables', '审核通过成果', '套'], ['onTimeDeliverables', '按期通过成果', '套'], ['overdueDeliverables', '逾期通过成果', '套']
  ] },
  { id: 'engineering', label: '工程', roles: ['engineer', 'project-manager'], primary: 'completedNodes', metrics: [['completedNodes', '完成节点', '个'], ['onTimeNodes', '按期完成节点', '个'], ['firstPassNodes', '首次验收通过', '个']] },
  { id: 'finance', label: '财务', roles: ['finance', 'finance-lead'], primary: 'registrations', metrics: [['registrations', '实际收付登记', '笔'], ['reviews', '独立复核', '笔'], ['fundChecks', '资金核对', '次']] }
];
const byId = Object.fromEntries(DEPARTMENTS.map(department => [department.id, department]));
const supported = new Set(DEPARTMENTS.map(department => department.id));
const legacyDepartmentForMetric = {
  commissionAmount: 'sales', collectionsAmount: 'sales', effectiveCustomers: 'sales',
  activityTotal: 'operations', publications: 'operations', effectiveLeads: 'operations',
  approvedDeliverables: 'design', onTimeDeliverables: 'design', overdueDeliverables: 'design'
};
const legacyPrimaryMetric = { sales: 'commissionAmount', operations: 'activityTotal', design: 'approvedDeliverables', engineering: 'completedNodes', finance: 'registrations' };
const canonicalDepartment = department => ['sales', 'operations', 'design'].includes(department) ? 'business' : department;
const inMonth = (value, period) => (String(value || '').includes('T') ? A.localDate(value) : String(value || '')).slice(0, 7) === period;
const sourceDate = source => source.date || source.accepted || source.completedAt || '';
const ratio = (value, target) => target > 0 ? Math.round(value / target * 1000) / 10 : null;
const receiptAmount = receipt => receipt.revisions?.length ? receipt.revisions.at(-1).amount : receipt.amount;
const realFollows = customer => (customer.follows || []).filter(follow => !String(follow.note || follow.content || '').startsWith('登记客资'));

function departmentsFor(person) {
  const departments = [];
  for (const role of person.roles || [person.roleId]) {
    const department = canonicalDepartment(N.teamForRole(role));
    if (supported.has(department) && !departments.some(row => row.id === department)) departments.push({ id: department, role });
  }
  return departments;
}

function primaryDepartment(snapshot, person) {
  return canonicalDepartment(snapshot.team?.find(member => member.id === person.id)?.department) || departmentsFor(person)[0]?.id || '';
}

function scalarMetricFor(snapshot, person) {
  const originalDepartment = snapshot.team?.find(member => member.id === person.id)?.department || N.teamForRole((person.roles || [person.roleId])[0]);
  return legacyPrimaryMetric[originalDepartment] || byId[primaryDepartment(snapshot, person)]?.primary || '';
}

function projectOwners(snapshot) {
  return new Map((snapshot.customers || []).filter(customer => customer.projectId).map(customer => [customer.projectId, C.owner(customer)]));
}

function facts(snapshot) {
  const period = snapshot.period;
  const rows = (snapshot.state.sources || []).filter(source => !source.excluded && inMonth(sourceDate(source), period));
  const result = {};
  const ensure = person => result[person] || (result[person] = {});
  const add = (person, department, metric, amount = 1) => {
    if (!person) return;
    const departmentFacts = ensure(person)[department] || (ensure(person)[department] = {});
    departmentFacts[metric] = (departmentFacts[metric] || 0) + amount;
  };
  for (const source of rows) {
    if (source.id?.startsWith('perf-commission:')) add(source.person, 'business', 'commissionAmount', Number(source.amount || 0) / 100);
    if (source.kind === 'node' && source.completed) {
      add(source.person, 'engineering', 'completedNodes');
      if (source.onTime) add(source.person, 'engineering', 'onTimeNodes');
      if (source.first) add(source.person, 'engineering', 'firstPassNodes');
    }
    if (source.kind === 'design') {
      add(source.person, 'business', 'approvedDeliverables');
      if (source.onTime === true || source.onTime === false) add(source.person, 'business', source.onTime ? 'onTimeDeliverables' : 'overdueDeliverables');
    }
    if (source.kind === 'publication') {
      add(source.person, 'business', 'activityTotal');
      add(source.person, 'business', 'publications');
    }
    if (source.kind === 'lead') {
      add(source.person, 'business', 'activityTotal');
      add(source.person, 'business', 'effectiveLeads');
    }
    if (source.kind === 'finance') add(source.person, 'finance', 'registrations');
    if (source.kind === 'finance-review') add(source.person, 'finance', 'reviews');
  }
  const owners = projectOwners(snapshot);
  for (const receipt of snapshot.state.receipts || []) {
    if (!['已核算', '已备案'].includes(receipt.status) || !inMonth(F.cashDate(receipt), period)) continue;
    add(owners.get(receipt.projectId), 'business', 'collectionsAmount', Number(receiptAmount(receipt) || 0) / 100);
  }
  const receipts = new Map((snapshot.state.receipts || []).map(receipt => [receipt.id, receipt]));
  for (const refund of snapshot.state.refunds || []) {
    if (refund.status !== '已核实' || !inMonth(F.cashDate(refund), period)) continue;
    const receipt = receipts.get(refund.receiptId);
    add(owners.get(receipt?.projectId || refund.projectId), 'business', 'collectionsAmount', -Number(refund.amount || 0) / 100);
  }
  for (const customer of snapshot.customers || []) {
    if (realFollows(customer).some(follow => inMonth(follow.date || follow.at, period))) add(C.owner(customer), 'business', 'effectiveCustomers');
  }
  for (const fund of snapshot.state.operations?.funds || []) if (inMonth(fund.date || fund.at, period)) add(fund.actorId, 'finance', 'fundChecks');
  return result;
}

function sourceRecords(snapshot, personId, department) {
  const kinds = {
    business: source => source.id?.startsWith('perf-commission:') || ['publication', 'lead', 'design'].includes(source.kind),
    engineering: source => source.kind === 'node',
    finance: source => ['finance', 'finance-review'].includes(source.kind)
  };
  const rows = (snapshot.state.sources || []).filter(source => source.person === personId && !source.excluded && inMonth(sourceDate(source), snapshot.period) && kinds[department]?.(source)).map(source => ({
    key: source.id,
    sourceId: source.id,
    name: [source.customer, source.name].filter(Boolean).join(' · '),
    meta: sourceDate(source),
    url: source.url || ''
  })).filter(row => row.url);
  if (department === 'finance') for (const fund of snapshot.state.operations?.funds || []) if (fund.actorId === personId && inMonth(fund.date || fund.at, snapshot.period)) rows.push({ key: fund.id, sourceId: fund.id, name: '资金核对', meta: fund.date || String(fund.at || '').slice(0, 10), url: '/pages/admin/operations/index?view=funds' });
  return rows.sort((a, b) => b.meta.localeCompare(a.meta)).slice(0, 5);
}

function targetFor(snapshot, person, department, metric) {
  const explicit = snapshot.goals.staffDepartmentGoals?.[person.id]?.[department]?.[metric];
  if (explicit !== undefined && explicit !== null && explicit !== '') return Number(explicit);
  if (department === 'business') {
    const legacyDepartment = legacyDepartmentForMetric[metric];
    const legacy = snapshot.goals.staffDepartmentGoals?.[person.id]?.[legacyDepartment]?.[metric];
    if (legacy !== undefined && legacy !== null && legacy !== '') return Number(legacy);
  }
  if (primaryDepartment(snapshot, person) === department && scalarMetricFor(snapshot, person) === metric) {
    const legacy = snapshot.goals.staff?.[person.id];
    if (legacy !== undefined && legacy !== null && legacy !== '') return Number(legacy);
  }
  return null;
}

function departmentTarget(snapshot, department, metric) {
  const explicit = snapshot.goals.departmentGoals?.[department]?.[metric];
  if (explicit !== undefined && explicit !== null && explicit !== '') return Number(explicit);
  if (department !== 'business') return null;
  const legacy = snapshot.goals.departmentGoals?.[legacyDepartmentForMetric[metric]]?.[metric];
  return legacy === undefined || legacy === null || legacy === '' ? null : Number(legacy);
}

function metricRow(definition, actual, target) {
  const [id, label, unit] = definition;
  const percent = ratio(actual, target);
  const money = unit === '元';
  const display = money ? (actual / 10000).toLocaleString('en-US', { maximumFractionDigits: 2 }) : String(actual);
  const targetDisplay = target === null ? '' : money ? (target / 10000).toLocaleString('en-US', { maximumFractionDigits: 2 }) : String(target);
  return { id, label, unit, displayUnit: money ? '万' : unit, actual, target, display, targetDisplay, percent, width: Math.min(100, percent || 0) };
}

function rowsWithoutDeadline(snapshot) { return (snapshot.state.sources||[]).filter(source=>source.kind==='design'&&!source.excluded&&source.onTime===null&&inMonth(sourceDate(source),snapshot.period)).length; }

function build(snapshot, selectedDepartment = 'all', expandedPerson = '') {
  const allFacts = facts(snapshot);
  const filterOptions = [{ id: 'all', label: '全部' }, ...DEPARTMENTS.map(({ id, label }) => ({ id, label }))];
  const visibleDepartments = selectedDepartment === 'all' ? DEPARTMENTS : DEPARTMENTS.filter(department => department.id === selectedDepartment);
  const activeStaff = (snapshot.staff || []).filter(person => person.active !== false);
  const groups = visibleDepartments.map(department => {
    const rows = activeStaff.filter(person => departmentsFor(person).some(row => row.id === department.id)).map(person => {
      const metrics = department.metrics.map(metric => metricRow(metric, Number(allFacts[person.id]?.[department.id]?.[metric[0]] || 0), targetFor(snapshot, person, department.id, metric[0])));
      const primary = metrics.find(metric => metric.id === department.primary);
      return {
        id: person.id,
        key: 'member:' + person.id + ':' + department.id,
        department: department.id,
        name: person.name,
        initial: person.name.slice(0, 1),
        role: (departmentsFor(person).find(row => row.id === department.id)?.role && N.roles[departmentsFor(person).find(row => row.id === department.id).role]) || department.label,
        metrics,
        percent: primary.percent,
        width: primary.width,
        expanded: expandedPerson === person.id,
        recentRecords: expandedPerson === person.id ? sourceRecords(snapshot, person.id, department.id) : [],
        url: '/pages/admin/detail/index?view=team&person=' + encodeURIComponent(person.id) + '&department=' + department.id
      };
    });
    const metrics = department.metrics.map(metric => metricRow(metric, rows.reduce((sum, row) => sum + row.metrics.find(item => item.id === metric[0]).actual, 0), departmentTarget(snapshot, department.id, metric[0])));
    return { key: department.id, label: department.label, metric: department.id === 'business' ? '运营、设计、销售统一考核' : department.metrics.map(metric => metric[1]).join(' · '), metrics, rows };
  }).filter(group => group.rows.length);
  const sales = snapshot.metrics?.[0] || { value: '0', target: '', percent: null, width: 0 };
  const engineering = snapshot.metrics?.[2] || { value: '0', target: '', percent: null, width: 0 };
  const collections = Number(F.summary(snapshot.state).income || 0) / 1000000;
  const collectionsTarget = snapshot.goals.collections ? snapshot.goals.collections / 10000 : null;
  const effective = (snapshot.customers || []).filter(customer => realFollows(customer).some(follow => inMonth(follow.date || follow.at, snapshot.period))).length;
  const companyTargets = [
    { ...sales, label: '设计委托金额', unit: '万' },
    { label: '回款金额', value: String(collections), target: collectionsTarget ? String(collectionsTarget) : '', unit: '万', percent: ratio(collections, collectionsTarget), width: Math.min(100, ratio(collections, collectionsTarget) || 0) },
    { label: '有效客户', value: String(effective), target: snapshot.goals.effective ? String(snapshot.goals.effective) : '', unit: '个', percent: ratio(effective, snapshot.goals.effective), width: Math.min(100, ratio(effective, snapshot.goals.effective) || 0) },
    { ...engineering, label: '工程节点', unit: '个' }
  ];
  return {
    targets: companyTargets,
    groups,
    departmentCards: groups.map(group => ({ key: group.key, label: group.label, metrics: group.metrics })),
    filterOptions,
    selectedDepartment: supported.has(selectedDepartment) ? selectedDepartment : 'all',
    expandedPerson,
    headcount: activeStaff.length,
    unassignedCommissions:(snapshot.commissions||[]).filter(customer=>!C.owner(customer)).length,
    undatedDesign:rowsWithoutDeadline(snapshot),
    cutoff: '截至' + Number(snapshot.today.slice(8)) + '日'
  };
}

function formFor(snapshot) {
  const departmentGoals = {};
  for (const department of DEPARTMENTS) departmentGoals[department.id] = Object.fromEntries(department.metrics.map(([metric]) => [metric, departmentTarget(snapshot, department.id, metric) ?? '']));
  const staffDepartmentGoals = {};
  for (const person of (snapshot.staff || []).filter(person => person.active !== false)) {
    const personGoals = {};
    for (const department of departmentsFor(person)) personGoals[department.id] = Object.fromEntries(byId[department.id].metrics.map(([metric]) => [metric, targetFor(snapshot, person, department.id, metric) ?? '']));
    if (Object.keys(personGoals).length) staffDepartmentGoals[person.id] = personGoals;
  }
  return {
    expectedVersion: snapshot.goals.version || 0,
    sales: snapshot.goals.sales ?? '',
    collections: snapshot.goals.collections ?? '',
    effective: snapshot.goals.effective ?? '',
    customers: snapshot.goals.customers ?? '',
    nodes: snapshot.goals.nodes ?? '',
    staff: clone(snapshot.goals.staff || {}),
    departmentGoals,
    staffDepartmentGoals
  };
}

function settingsFor(snapshot) {
  const form = formFor(snapshot);
  const companyFields = [
    ['sales', '设计委托金额', '元'],
    ['collections', '回款金额', '元'],
    ['effective', '有效客户', '个'],
    ['customers', '新增客户', '个'],
    ['nodes', '工程节点', '个']
  ].map(([id, label, unit]) => ({ id, label, unit, value: form[id] }));
  const departments = DEPARTMENTS.map(department => ({
    id: department.id,
    label: department.label,
    metrics: department.metrics.map(([id, label, unit]) => ({ id, label, unit, value: form.departmentGoals[department.id][id] }))
  }));
  const staff = (snapshot.staff || []).filter(person => person.active !== false).map(person => ({
    id: person.id,
    name: person.name,
    departments: departmentsFor(person).map(({ id }) => ({
      id,
      label: byId[id].label,
      metrics: byId[id].metrics.map(([metric, label, unit]) => ({ metric, label, unit, value: form.staffDepartmentGoals[person.id]?.[id]?.[metric] ?? '' }))
    }))
  })).filter(person => person.departments.length);
  return { form, companyFields, departments, staff };
}

function parseTarget(value, money = false) {
  if (value === '' || value === null || value === undefined) return null;
  const pattern = money ? /^\d+(\.\d{1,2})?$/ : /^\d+$/;
  X.assert(pattern.test(String(value)) && Number(value) > 0 && Number(value) <= (money ? 100000000 : 100000), money ? '金额目标须为正数、最多两位小数' : '数量目标须为正整数');
  return Number(value);
}

function validateDepartmentGoals(input) {
  const output = {};
  for (const [departmentId, values] of Object.entries(input || {})) {
    const department = byId[departmentId];
    X.assert(department, '不支持的绩效部门');
    const allowed = new Set(department.metrics.map(([metric]) => metric));
    output[departmentId] = {};
    for (const [metric, value] of Object.entries(values || {})) {
      X.assert(allowed.has(metric), '不支持的绩效指标');
      const parsed = parseTarget(value, metric.endsWith('Amount'));
      if (parsed !== null) output[departmentId][metric] = parsed;
    }
  }
  return output;
}

function validateStaffGoals(input, staffById) {
  const output = {};
  for (const [personId, values] of Object.entries(input || {})) {
    const person = staffById.get(personId);
    X.assert(person && person.active !== false, '目标人员不存在或已停用');
    const available = new Set(departmentsFor(person).map(row => row.id));
    for (const departmentId of Object.keys(values || {})) X.assert(available.has(departmentId), '人员未授权该绩效部门');
    const goals = validateDepartmentGoals(values);
    if (Object.keys(goals).length) output[personId] = goals;
  }
  return output;
}

function normalizedGoals(snapshot, form) {
  const staffById = new Map((snapshot.staff || []).map(person => [person.id, person]));
  const staff = {};
  for (const [personId, value] of Object.entries(form.staff || {})) {
    const person = staffById.get(personId);
    if (!person || person.active === false) continue;
    const parsed = parseTarget(value, scalarMetricFor(snapshot, person).endsWith('Amount'));
    if (parsed !== null) staff[personId] = parsed;
  }
  return {
    sales: parseTarget(form.sales, true),
    collections: parseTarget(form.collections, true),
    effective: parseTarget(form.effective),
    customers: parseTarget(form.customers),
    nodes: parseTarget(form.nodes),
    staff,
    departmentGoals: validateDepartmentGoals(form.departmentGoals),
    staffDepartmentGoals: validateStaffGoals(form.staffDepartmentGoals, staffById)
  };
}

function validateGoals(snapshot, form) {
  X.assert(Number(form.expectedVersion || 0) === Number(snapshot.goals.version || 0), '月度目标已更新，请刷新后重试');
  const goals = normalizedGoals(snapshot, form);
  const archived = (snapshot.state.archives || []).some(cycle => cycle.dynamic && cycle.period === snapshot.period && cycle.status === '已归档');
  if (archived) {
    const current = normalizedGoals(snapshot, formFor(snapshot));
    X.assert(JSON.stringify(goals) === JSON.stringify(current), '本周期已归档，请保留原目标版本');
  }
  return goals;
}

module.exports = { DEPARTMENTS, build, facts, formFor, settingsFor, validateGoals, departmentsFor };
