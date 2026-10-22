// Boss 经营 and 绩效 views only aggregate current V3 source records.
const A = require('../../utils/account');
const X = require('../../utils/access');
const N = require('../../utils/organization');
const F = require('../../utils/finance-source');
const P = require('../../utils/performance');
const R = require('../../utils/operating-report');
const W = require('./owner-model');

const DAY = 86400000;
const SOON_DAYS = 7;
const DEPARTMENTS = [['operations', '运营', '获客'], ['sales', '业务', '设计委托'], ['design', '设计', '成果通过'], ['engineering', '工程', '节点完成'], ['finance', '财务', '登记复核']];
const PAYABLE_TAGS = { '待办理': '待付款', '部分已付': '待付款', '待复核': '待付款复核', '需补充': '需补充', '付款需补充': '需补充' };
const PREVIEW_ROWS = 3;
const wan = cents => (Number(cents || 0) / 1000000).toLocaleString('en-US', { maximumFractionDigits: 2 });
const md = date => date ? Number(date.slice(5, 7)) + '月' + Number(date.slice(8, 10)) + '日' : '';
const addDays = (date, n) => A.localDate(new Date(date + 'T12:00:00').getTime() + n * DAY);
const daysBetween = (from, to) => Math.round((Date.parse(to) - Date.parse(from)) / DAY);
const tone = (due, today) => !due ? 'later' : due < today ? 'overdue' : due <= addDays(today, SOON_DAYS) ? 'soon' : 'later';
const inMonth = (value, period) => String(value || '').slice(0, 7) === period;
const realFollows = customer => (customer.follows || []).filter(follow => !String(follow.note || follow.content || '').startsWith('登记客资'));

function previousPeriod(period) {
  const [year, month] = period.split('-').map(Number);
  return month === 1 ? year - 1 + '-12' : year + '-' + String(month - 1).padStart(2, '0');
}

function change(current, previous, previousLabel) {
  if (!previous) return '上月无记录';
  const percent = Math.round((current - previous) / previous * 1000) / 10;
  return '较' + previousLabel + ' ' + (percent > 0 ? '+' : '') + percent + '%';
}

function receivable(snapshot) {
  const report = R.analytics(snapshot.state, snapshot.period, snapshot.today);
  const rows = report.receivableRows.map(row => {
    const customer = snapshot.customers.find(customer => customer.projectId === row.projectId);
    const overdue = !!row.due && row.due < snapshot.today;
    return { key:'ar:' + row.id, sourceId:row.id, amountCents:row.amountCents, url:W.detail('receivable', {id:row.id}),
      name:(customer?.name || row.projectId) + ' · ' + row.installment,
      meta:[customer?.city, row.due ? '约定 ' + md(row.due) : '收款计划待确认'].filter(Boolean).join(' · '),
      amount:wan(row.amountCents), tag:overdue ? '逾期 ' + daysBetween(row.due, snapshot.today) + ' 天' : row.due ? '未到期' : '计划待确认',
      tone:tone(row.due, snapshot.today), due:row.due, overdue };
  }).sort((a,b)=>Number(b.overdue)-Number(a.overdue)||(a.due||'9999').localeCompare(b.due||'9999'));
  return { total:wan(report.receivableAging.totalCents), overdue:wan(rows.filter(row=>row.overdue).reduce((sum,row)=>sum+row.amountCents,0)), count:rows.length, rows };
}

function payable(snapshot) {
  const soon = addDays(snapshot.today, SOON_DAYS);
  const open = snapshot.state.payables
    .filter(payable => payable.approvalValid && payable.status !== '已付清')
    .map(payable => ({ payable, remaining: Math.max(0, payable.total - F.paid(snapshot.state, payable.id)) }))
    .filter(item => item.remaining > 0);
  const rows = open.sort((a, b) => (a.payable.due || '9999').localeCompare(b.payable.due || '9999')).map(({ payable, remaining }) => ({ key: 'ap:' + payable.id, sourceId: payable.id, amountCents: remaining, url: W.detail('payable', { id: payable.id }), name: (payable.customer || '公司') + ' · ' + (payable.stage || payable.name), due: payable.due ? '到期 ' + md(payable.due) : '到期日待登记', amount: wan(remaining), tag: PAYABLE_TAGS[payable.status] || payable.status, tone: tone(payable.due, snapshot.today) }));
  return { total: wan(open.reduce((sum, item) => sum + item.remaining, 0)), dueSoon: wan(open.filter(item => item.payable.due && item.payable.due >= snapshot.today && item.payable.due <= soon).reduce((sum, item) => sum + item.remaining, 0)), count: rows.length, rows };
}

function analyticRows(rows, total) {
  return rows.map(row => ({ ...row, value: wan(row.amountCents), percent: total ? Math.round(row.amountCents * 100 / total) : 0 }));
}

function analyticsView(report, factsAvailable) {
  const maximum = Math.max(1, ...report.trend.flatMap(row => [row.incomeCents, row.paidCents]));
  const percent = basisPoints => basisPoints === null ? null : (basisPoints / 100).toLocaleString('en-US', { maximumFractionDigits: 1 }) + '%';
  return {
    trend: report.trend.map(row => ({ ...row, label: Number(row.period.slice(5)) + '月', income: wan(row.incomeCents), paid: wan(row.paidCents), incomeWidth: Math.round(row.incomeCents * 100 / maximum), paidWidth: Math.round(row.paidCents * 100 / maximum) })),
    incomeComposition: analyticRows(report.incomeComposition.rows, report.incomeComposition.totalCents),
    paidComposition: analyticRows(report.paidComposition.rows, report.paidComposition.totalCents),
    receivableAging: factsAvailable ? analyticRows(report.receivableAging.buckets, report.receivableAging.totalCents) : [],
    payableAging: factsAvailable ? analyticRows(report.payableAging.buckets, report.payableAging.totalCents) : [],
    collectionRate: factsAvailable && report.collectionRate.available ? { value: percent(report.collectionRate.basisPoints), received: wan(report.collectionRate.numeratorCents), due: wan(report.collectionRate.denominatorCents), available: true } : { value: null, note: factsAvailable ? '待确认本月到期收款计划' : '该月未归档', available: false },
    coverage: factsAvailable && report.coverage.available ? { value: (report.coverage.milli / 1000).toLocaleString('en-US', { maximumFractionDigits: 2 }) + '倍', cash: wan(report.coverage.cashCents), payable: wan(report.coverage.payableCents), available: true } : { value: null, note: factsAvailable && report.coverage.cashCents === null ? '待财务核对账户余额' : factsAvailable ? '暂无已批准未付应付' : '该月未归档', available: false },
    profit: factsAvailable && report.profit.available ? { value: wan(report.profit.profitCents), margin: percent(report.profit.marginBasisPoints), available: true } : { value: null, note: factsAvailable ? '成本待完善' : '该月未归档', available: false },
    acquisition: factsAvailable && report.acquisition.available ? { value: (report.acquisition.costCents / 100).toLocaleString('en-US', { maximumFractionDigits: 2 }), promotion: wan(report.acquisition.promotionCents), customers: report.acquisition.effectiveCustomers, available: true } : { value: null, note: factsAvailable && !report.acquisition.promotionCents ? '推广费待登记' : factsAvailable ? '暂无有效客户' : '该月未归档', available: false }
  };
}

function frozenReceivable(snapshot, report) {
  const name = id => snapshot.customers.find(customer => customer.projectId === id)?.name || id;
  const rows = report.receivableRows.map(row => ({ key: 'ar:' + row.id, sourceId: row.id, amountCents: row.amountCents, url: W.detail('receivable', { id: row.id }), name: name(row.projectId) + ' · ' + row.installment, meta: row.due ? '约定 ' + md(row.due) : '收款计划待确认', amount: wan(row.amountCents), tag: row.due && row.due < report.asOf ? '月末逾期' : row.due ? '月末未到期' : '计划待确认', tone: row.due && row.due < report.asOf ? 'overdue' : 'later', due: row.due, overdue: !!row.due && row.due < report.asOf }));
  return { total: wan(report.receivableAging.totalCents), overdue: wan(report.receivableAging.buckets.filter(row => row.key.startsWith('overdue')).reduce((total, row) => total + row.amountCents, 0)), count: rows.length, rows };
}

function frozenPayable(report) {
  const rows = report.payableRows.map(row => ({ key: 'ap:' + row.id, sourceId: row.id, amountCents: row.amountCents, url: W.detail('payable', { id: row.id }), name: (row.source.customer || '公司') + ' · ' + (row.source.stage || row.source.name), due: row.due ? '到期 ' + md(row.due) : '到期日待登记', amount: wan(row.amountCents), tag: '月末未付', tone: row.due && row.due < report.asOf ? 'overdue' : 'later' }));
  return { total: wan(report.payableAging.totalCents), dueSoon: '0', count: rows.length, rows };
}

function business(snapshot) {
  const previous = previousPeriod(snapshot.period);
  const previousLabel = Number(previous.slice(5)) + '月';
  const recalculatedSummary = F.summary(snapshot.state);
  const recalculatedPrevious = F.summary({ ...snapshot.state, period: previous });
  const fund = (snapshot.state.operations?.funds || []).slice(-1)[0];
  const historical = snapshot.period !== snapshot.today.slice(0, 7);
  const archived = R.currentSnapshot(snapshot.state, snapshot.period);
  const liveReport = R.analytics(snapshot.state, snapshot.period, snapshot.today);
  const report = archived?.report || liveReport;
  const archivedFlow = archived && report.trend.find(row => row.period === snapshot.period);
  const archivedPrevious = archived && report.trend.find(row => row.period === previous);
  const currentSummary = archivedFlow ? { income: archivedFlow.incomeCents, paid: archivedFlow.paidCents, net: archivedFlow.netCents } : recalculatedSummary;
  const previousSummary = archivedPrevious ? { income: archivedPrevious.incomeCents, paid: archivedPrevious.paidCents, net: archivedPrevious.netCents } : recalculatedPrevious;
  const currentReceivable = historical ? archived ? frozenReceivable(snapshot, report) : { total: '0', overdue: '0', count: 0, rows: [] } : receivable(snapshot);
  const currentPayable = historical ? archived ? frozenPayable(report) : { total: '0', dueSoon: '0', count: 0, rows: [] } : payable(snapshot);
  const sourceChanged = !!archived && archived.sourceSignature !== R.sourceSignature(snapshot.state, snapshot.period);
  return {
    historical,
    summary: { income: wan(currentSummary.income), paid: wan(currentSummary.paid), net: wan(currentSummary.net), incomeChange: change(currentSummary.income, previousSummary.income, previousLabel), paidChange: change(currentSummary.paid, previousSummary.paid, previousLabel) },
    balance: historical ? archived && report.coverage.cashCents !== null ? { value: wan(report.coverage.cashCents), label: '', note: '月末已结账' } : { value: null, label: '本月尚未结账', note: '' } : snapshot.operating.cash === null || !fund ? { value: null, label: '待财务登记', note: '' } : { value: wan(snapshot.operating.cash), label: '', note: (fund.by || '财务') + ' ' + md(fund.date) + '核对' },
    receivable: { ...currentReceivable, rows: currentReceivable.rows.slice(0, PREVIEW_ROWS) },
    payable: { ...currentPayable, rows: currentPayable.rows.slice(0, PREVIEW_ROWS) },
    pending: historical ? { reconcile: 0, review: 0 } : { reconcile: snapshot.reconcileCount, review: snapshot.reviewCount },
    analytics: analyticsView(report, !historical || !!archived),
    snapshotInfo: { available: !!archived, version: archived?.version || 0, sourceChanged, canArchive: historical, note: archived ? sourceChanged ? '账目有调整，请重新核对' : '本月已结账' : historical ? '结账后可查看月末余额及未结款项' : '' }
  };
}

function target(label, value, goal, unit) {
  const percent = W.ratio(Number(value), goal);
  return { label, value: String(value), target: goal ? String(goal) : '', unit, percent, width: Math.min(100, percent || 0) };
}

function moneyTarget(label, cents, goal) {
  const percent = W.ratio(cents / 100, goal);
  return { label, value: wan(cents), target: goal ? wan(goal * 100) : '', unit: '万', percent, width: Math.min(100, percent || 0) };
}

function person(snapshot, staff, role, department, collectionsByOwner) {
  const member = snapshot.team.find(item => item.id === staff.id);
  const totals = P.totals(snapshot.state.sources.filter(row => row.person === staff.id && !row.excluded));
  const primary = member?.department === department;
  const targetValue = primary ? member.target : '';
  const of = (value, goal) => value + (goal ? '/' + goal : '');
  const results = {
    sales: () => '委托 ' + of(snapshot.commissions.filter(customer => X.ownerId(customer) === staff.id).reduce((sum, customer) => sum + Number(customer.serviceOrders.at(-1).total || 0), 0) / 10000, targetValue) + '万 · 回款 ' + (collectionsByOwner.get(staff.id) || 0).toLocaleString('en-US', { maximumFractionDigits: 2 }) + '万',
    engineering: () => '节点 ' + of(totals.nodes, targetValue) + ' · ' + snapshot.projects.filter(project => project.managerId === staff.id && project.actualStart && !project.delivery && !project.archived).length + ' 个工地',
    design: () => '成果通过 ' + of(totals.design, targetValue) + ' 套',
    finance: () => '登记复核 ' + of(totals.finance + totals.financeReview, targetValue) + ' 笔',
    operations: () => '发布与线索 ' + of(totals.publications + totals.leads, targetValue) + ' 条'
  };
  const percent = primary ? member.percent : null;
  return { id: staff.id, department, key: 'member:' + staff.id + ':' + department, name: staff.name, initial: staff.name.slice(0, 1), role: N.roles[role] || '', result: results[department](), percent, width: Math.min(100, percent || 0), url: W.detail('team', { person: staff.id, department }) };
}

function performance(snapshot) {
  const effective = snapshot.customers.filter(customer => realFollows(customer).some(follow => inMonth(follow.date, snapshot.period))).length;
  const targets = [
    { ...snapshot.metrics[0], label: '设计委托金额' },
    moneyTarget('回款金额', F.summary(snapshot.state).income, snapshot.goals.collections),
    target('有效客户', effective, snapshot.goals.effective, '个'),
    { ...snapshot.metrics[2], label: '工程节点', unit: '个' }
  ];
  const supported = new Set(DEPARTMENTS.map(([key]) => key));
  const projectIncome = new Map((snapshot.projectRows || []).map(row => [row.id, Number(String(row.income || 0).replace(/,/g, ''))]));
  const collectionsByOwner = new Map();
  for (const customer of snapshot.customers) {
    const owner = X.ownerId(customer);
    if (owner) collectionsByOwner.set(owner, (collectionsByOwner.get(owner) || 0) + (projectIncome.get(customer.projectId) || 0));
  }
  const staff = snapshot.staff.flatMap(person => {
    const byDepartment = new Map();
    for (const role of (person.roles || [person.roleId]).filter(Boolean)) {
      const department = N.teamForRole(role);
      if (supported.has(department) && !byDepartment.has(department)) byDepartment.set(department, role);
    }
    return [...byDepartment].map(([department, role]) => ({ person, role, department }));
  });
  const groups = DEPARTMENTS.map(([key, label, metric]) => ({ key, label, metric, rows: staff.filter(item => item.department === key).map(item => person(snapshot, item.person, item.role, key, collectionsByOwner)) })).filter(group => group.rows.length);
  return { targets, groups, headcount: snapshot.staff.length, cutoff: '截至' + Number(snapshot.today.slice(8)) + '日' };
}

function forView(view, snapshot) {
  if (view === 'finance') return { business: business(snapshot) };
  if (view === 'performance') return { performance: performance(snapshot) };
  if (view === 'profile') return { course: { image: A.courseImage, progress: A.progress(A.load(), 'ai-video') } };
  return {};
}

function rows(view, snapshot) {
  if (view === 'receivables' || view === 'payables') {
    const historical = snapshot.period !== snapshot.today.slice(0, 7);
    const archived = historical && R.currentSnapshot(snapshot.state, snapshot.period);
    if (historical) return archived ? (view === 'receivables' ? frozenReceivable(snapshot, archived.report) : frozenPayable(archived.report)).rows : [];
    return view === 'receivables' ? receivable(snapshot).rows : payable(snapshot).rows;
  }
  const model = forView(view, snapshot);
  if (model.business) return [...model.business.receivable.rows, ...model.business.payable.rows];
  if (model.performance) return model.performance.groups.flatMap(group => group.rows);
  return [];
}

function recordFor(view, snapshot, id) {
  const historical = snapshot.period !== snapshot.today.slice(0, 7);
  const archived = historical && R.currentSnapshot(snapshot.state, snapshot.period);
  if (historical && !archived) return null;
  if (view === 'payable') {
    const frozen = archived?.report?.payableRows?.find(row => row.id === id);
    if (frozen) return { id: frozen.id, name: (frozen.source.customer || '公司') + ' · ' + (frozen.source.stage || frozen.source.name), amount: frozen.amountCents, total: frozen.totalCents, paid: frozen.paidCents, status: '月末未付', due: frozen.due || '未约定', source: frozen.source.recipient || frozen.source.name || '原付款事项' };
    if (historical) return null;
    const payable = snapshot.state.payables.find(row => row.id === id);
    if (!payable) return null;
    const paid = F.paid(snapshot.state, payable.id);
    return { id: payable.id, name: (payable.customer || '公司') + ' · ' + (payable.stage || payable.name), amount: Math.max(0, payable.total - paid), total: payable.total, paid, status: payable.status, due: payable.due || '未约定', source: payable.recipient || payable.name || '原付款事项' };
  }
  if (view === 'receivable') {
    const frozen = archived?.report?.receivableRows?.find(row => row.id === id);
    if (frozen) {
      const name = snapshot.customers.find(customer => customer.projectId === frozen.projectId)?.name || frozen.projectId;
      const reminder = { ready: false, text: '', file: null, reason: '请切换到当前月份后催款' };
      return { id, name: name + ' · ' + frozen.installment, amount: frozen.amountCents, total: frozen.totalCents, paid: frozen.paidCents, status: frozen.due && frozen.due < archived.asOf ? '月末已逾期' : '月末待收款', due: frozen.due || '未约定', source: '月度结账 · 建造合同收款约定', reminder };
    }
    if (historical) return null;
    const row = R.analytics(snapshot.state, snapshot.period, snapshot.today).receivableRows.find(row=>row.id===id);
    if (!row) return null;
    const customer = snapshot.customers.find(customer=>customer.projectId===row.projectId);
    const name = customer?.name || row.projectId;
    const reminder = R.reminder({projectId:row.projectId, customer:name, installment:row.installment, remainingCents:row.amountCents, due:row.due}, snapshot.today);
    return { id, name:name + ' · ' + row.installment, amount:row.amountCents, total:row.totalCents, paid:row.paidCents,
      status:row.due && row.due<snapshot.today ? '已逾期' : row.due ? '待收款' : '收款计划待确认', due:row.due || '未约定', source:'建造合同收款约定', reminder };
  }
  return null;
}

module.exports = { forView, rows, recordFor, business, performance, previousPeriod };
