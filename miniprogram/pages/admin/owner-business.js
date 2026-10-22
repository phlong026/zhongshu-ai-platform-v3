// Boss 经营 and 绩效 views only aggregate current V3 source records.
const A = require('../../utils/account');
const X = require('../../utils/access');
const N = require('../../utils/organization');
const F = require('../../utils/finance-source');
const P = require('../../utils/performance');
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
  const place = id => snapshot.customers.find(customer => customer.projectId === id)?.city || '';
  const rows = snapshot.operating.rows.filter(row => !row.operatingOnly && row.remaining > 0).flatMap(row => {
    const meta = text => [place(row.id), text].filter(Boolean).join(' · ');
    if (!row.plan) return [{ key: 'ar:' + row.id, sourceId: row.id, amountCents: row.remaining, url: W.detail('receivable', { id: row.id }), name: row.name + ' · 未收款', meta: meta('收款计划待确认'), amount: wan(row.remaining), tag: '计划待确认', tone: 'later', due: '', overdue: false }];
    return row.installments.filter(item => item.remaining > 0).map(item => ({ key: 'ar:' + row.id + ':' + item.id, sourceId: row.id + ':' + item.id, amountCents: item.remaining, url: W.detail('receivable', { id: row.id + ':' + item.id }), name: row.name + ' · ' + item.name, meta: meta('约定 ' + md(item.due)), amount: wan(item.remaining), tag: item.overdue ? '逾期 ' + daysBetween(item.due, snapshot.today) + ' 天' : '未到期', tone: item.overdue ? 'overdue' : tone(item.due, snapshot.today), due: item.due, overdue: item.overdue }));
  }).sort((a, b) => Number(b.overdue) - Number(a.overdue) || (a.due || '9999').localeCompare(b.due || '9999'));
  return { total: wan(snapshot.operating.receivable), overdue: wan(snapshot.operating.overdue), count: rows.length, rows };
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

function business(snapshot) {
  const previous = previousPeriod(snapshot.period);
  const previousLabel = Number(previous.slice(5)) + '月';
  const currentSummary = F.summary(snapshot.state);
  const previousSummary = F.summary({ ...snapshot.state, period: previous });
  const fund = (snapshot.state.operations?.funds || []).slice(-1)[0];
  const historical = snapshot.period !== snapshot.today.slice(0, 7);
  const currentReceivable = historical ? { total: '0', overdue: '0', count: 0, rows: [] } : receivable(snapshot);
  const currentPayable = historical ? { total: '0', dueSoon: '0', count: 0, rows: [] } : payable(snapshot);
  return {
    historical,
    summary: { income: wan(currentSummary.income), paid: wan(currentSummary.paid), net: wan(currentSummary.net), incomeChange: change(currentSummary.income, previousSummary.income, previousLabel), paidChange: change(currentSummary.paid, previousSummary.paid, previousLabel) },
    balance: historical ? { value: null, label: '历史月不混入当前余额', note: '' } : snapshot.operating.cash === null || !fund ? { value: null, label: '待财务登记', note: '' } : { value: wan(snapshot.operating.cash), label: '', note: (fund.by || '财务') + ' ' + md(fund.date) + '核对' },
    receivable: { ...currentReceivable, rows: currentReceivable.rows.slice(0, PREVIEW_ROWS) },
    payable: { ...currentPayable, rows: currentPayable.rows.slice(0, PREVIEW_ROWS) },
    pending: historical ? { reconcile: 0, review: 0 } : { reconcile: snapshot.reconcileCount, review: snapshot.reviewCount }
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
  if (view === 'receivables') return receivable(snapshot).rows;
  if (view === 'payables') return payable(snapshot).rows;
  const model = forView(view, snapshot);
  if (model.business) return [...model.business.receivable.rows, ...model.business.payable.rows];
  if (model.performance) return model.performance.groups.flatMap(group => group.rows);
  return [];
}

function recordFor(view, snapshot, id) {
  if (view === 'payable') {
    const payable = snapshot.state.payables.find(row => row.id === id);
    if (!payable) return null;
    const paid = F.paid(snapshot.state, payable.id);
    return { id: payable.id, name: (payable.customer || '公司') + ' · ' + (payable.stage || payable.name), amount: Math.max(0, payable.total - paid), total: payable.total, paid, status: payable.status, due: payable.due || '未约定', source: payable.recipient || payable.name || '原付款事项' };
  }
  if (view === 'receivable') {
    const match = snapshot.operating.rows.flatMap(project => (project.installments || []).map(installment => ({ project, installment, id: project.id + ':' + installment.id }))).find(row => row.id === id);
    const project = match?.project || snapshot.operating.rows.find(row => row.id === id);
    if (!project) return null;
    const installment = match?.installment || null;
    return { id, name: project.name + (installment ? ' · ' + installment.name : ' · 合同未收'), amount: installment ? installment.remaining : project.remaining, total: installment ? installment.amount : project.total, paid: installment ? installment.paid : project.received, status: installment?.overdue ? '已逾期' : project.unplanned ? '收款计划待确认' : '待收款', due: installment?.due || '未约定', source: '建造合同收款约定' };
  }
  return null;
}

module.exports = { forView, rows, recordFor, business, performance, previousPeriod };
