// Boss 经营 and 绩效 views, read from the snapshot's original records.
// Missing facts stay "待登记/待设置"; nothing here is stored or estimated.
const A = require('../../utils/account');
const X = require('../../utils/access');
const N = require('../../utils/organization');
const F = require('../../utils/finance-source');
const P = require('../../utils/performance');
const W = require('./owner-model');

const DAY = 86400000;
const SOON_DAYS = 7;
const DEPARTMENTS = [['operations', '运营', '获客'], ['sales', '业务', '签约'], ['design', '设计', '方案通过'], ['engineering', '工程', '节点完成'], ['finance', '财务', '按时登记']];
const PAYABLE_TAGS = { '待审批':'待审批', '费用待老板审批':'待审批', '待复核':'待付款复核', '待办理':'待付款', '部分已付':'待付款', '需补充':'需补充', '付款需补充':'需补充' };
const wan = cents => (Number(cents || 0) / 1000000).toLocaleString('en-US', { maximumFractionDigits: 2 });
const md = date => Number(date.slice(5, 7)) + '月' + Number(date.slice(8, 10)) + '日';
const addDays = (date, n) => A.localDate(new Date(date + 'T12:00:00').getTime() + n * DAY);
const daysBetween = (from, to) => Math.round((Date.parse(to) - Date.parse(from)) / DAY);
const tone = (due, today) => !due ? 'later' : due < today ? 'overdue' : due <= addDays(today, SOON_DAYS) ? 'soon' : 'later';
const inMonth = (value, period) => String(value || '').slice(0, 7) === period;
// Registration notes are written by the system, not a real conversation with the customer (same rule as the home page).
const realFollows = c => (c.follows || []).filter(f => !String(f.note || f.content || '').startsWith('登记客资'));

function previousPeriod(period) { const [y, m] = period.split('-').map(Number); return m === 1 ? (y - 1) + '-12' : y + '-' + String(m - 1).padStart(2, '0'); }
function change(current, previous, prevLabel) {
  if (!previous) return '上月无记录';
  const pct = Math.round((current - previous) / previous * 1000) / 10;
  return '较' + prevLabel + ' ' + (pct > 0 ? '+' : '') + pct + '%';
}

function receivable(s) {
  const today = s.today, place = id => s.customers.find(c => c.projectId === id)?.city || '';
  const rows = s.operating.rows.filter(r => !r.operatingOnly && r.remaining > 0).flatMap(r => {
    const url = W.admin('finance', r.id), meta = text => [place(r.id), text].filter(Boolean).join(' · ');
    if (!r.plan) return [{ key:'ar:' + r.id, url, name:r.name + ' · 合同未收', meta:meta('收款计划待确认'), amount:wan(r.remaining), tag:'计划待确认', tone:'later', due:'', overdue:false }];
    return r.installments.filter(i => i.remaining > 0).map(i => ({ key:'ar:' + r.id + ':' + i.id, url, name:r.name + ' · ' + i.name, meta:meta('约定 ' + md(i.due)), amount:wan(i.remaining), tag:i.overdue ? '逾期 ' + daysBetween(i.due, today) + ' 天' : '未到期', tone:i.overdue ? 'overdue' : tone(i.due, today), due:i.due, overdue:i.overdue }));
  }).sort((a, b) => Number(b.overdue) - Number(a.overdue) || (a.due || '9999').localeCompare(b.due || '9999'));
  return { total:wan(s.operating.receivable), overdue:wan(s.operating.overdue), count:rows.length, rows };
}

function payable(s) {
  const today = s.today, soon = addDays(today, SOON_DAYS);
  const open = s.state.payables.map(q => ({ q, rest:Math.max(0, q.total - F.paid(s.state, q.id)) })).filter(x => x.rest > 0);
  const rows = open.sort((a, b) => (a.q.due || '9999').localeCompare(b.q.due || '9999')).map(({ q, rest }) => ({ key:'ap:' + q.id, url:W.admin('finance', q.projectId), name:(q.customer || '公司') + ' · ' + (q.stage || q.name), due:q.due ? '到期 ' + md(q.due) : '到期日待登记', amount:wan(rest), tag:PAYABLE_TAGS[q.status] || q.status, tone:tone(q.due, today) }));
  return { total:wan(open.reduce((n, x) => n + x.rest, 0)), dueSoon:wan(open.filter(x => x.q.due && x.q.due <= soon).reduce((n, x) => n + x.rest, 0)), count:rows.length, rows };
}

function business(s) {
  const prev = previousPeriod(s.period), prevLabel = Number(prev.slice(5)) + '月';
  const now = F.summary(s.state), before = F.summary({ ...s.state, period:prev });
  const fund = (s.state.operations?.funds || []).slice(-1)[0];
  return {
    summary:{ income:wan(now.income), paid:wan(now.paid), net:wan(now.net), incomeChange:change(now.income, before.income, prevLabel), paidChange:change(now.paid, before.paid, prevLabel) },
    // Balance only exists after finance confirms an opening balance; otherwise it is never inferred.
    balance:s.operating.cash === null || !fund ? { value:null, label:'待财务登记', note:'' } : { value:wan(s.operating.cash), label:'', note:(fund.by || '财务') + ' ' + md(fund.date) + '核对' },
    receivable:receivable(s), payable:payable(s),
    pending:{ reconcile:s.reconcileCount, review:s.reviewCount }
  };
}

function target(label, value, goal, unit) { const percent = W.ratio(Number(value), goal); return { label, value:String(value), target:goal ? String(goal) : '', unit, percent, width:Math.min(100, percent || 0) }; }
function moneyTarget(label, cents, goal) { const percent = W.ratio(cents / 100, goal); return { label, value:wan(cents), target:goal ? wan(goal * 100) : '', unit:'万', percent, width:Math.min(100, percent || 0) }; }

function person(s, p, role, dept) {
  const member = s.team.find(t => t.id === p.id), totals = P.totals(s.state.sources.filter(r => r.person === p.id && !r.excluded));
  const of = (value, goal) => value + (goal ? '/' + goal : '');
  const results = {
    sales:() => '签约 ' + of(member?.value || '0', member?.target) + '万 · 回款 ' + wan(s.customers.filter(c => X.ownerId(c) === p.id).reduce((n, c) => n + F.summary(s.state, c.projectId).income, 0)) + '万',
    engineering:() => '节点 ' + of(member?.value || '0', member?.target) + ' · ' + s.projects.filter(x => x.managerId === p.id && x.actualStart && !x.delivery && !x.archived).length + ' 个工地',
    design:() => '方案通过 ' + totals.design + ' 套',
    finance:() => '登记 ' + totals.finance + ' 笔 · 复核 ' + totals.financeReview + ' 笔',
    operations:() => '发布 ' + totals.publications + ' 条 · 线索 ' + totals.leads + ' 条'
  };
  const percent = member ? member.percent : null;
  return { id:p.id, key:'member:' + p.id, name:p.name, initial:p.name.slice(0, 1), role:N.roles[role] || '', result:results[dept](), percent, width:Math.min(100, percent || 0), url:W.detail('team', { person:p.id }) };
}

function performance(s) {
  const g = s.goals, effective = s.customers.filter(c => realFollows(c).some(f => inMonth(f.date, s.period))).length;
  const sales = s.metrics[0], nodes = s.metrics[2];
  const targets = [
    { ...sales, label:'签约金额' },
    moneyTarget('回款金额', F.summary(s.state).income, g.collections),
    target('有效客户', effective, g.effective, '个'),
    { ...nodes, label:'工程节点', unit:'个' }
  ];
  const staff = s.staff.map(p => { const role = (p.roles || [p.roleId]).filter(Boolean)[0] || ''; return { p, role, dept:role ? N.teamForRole(role) : '' }; });
  const groups = DEPARTMENTS.map(([key, label, metric]) => ({ key, label, metric, rows:staff.filter(x => x.dept === key).map(x => person(s, x.p, x.role, key)) })).filter(group => group.rows.length);
  return { targets, groups, headcount:s.staff.length, cutoff:'截至' + Number(s.today.slice(8)) + '日' };
}

function forView(view, s) {
  if (view === 'finance') return { business:business(s) };
  if (view === 'performance') return { performance:performance(s) };
  if (view === 'profile') return { course:{ image:A.courseImage, progress:A.progress(A.load(), 'ai-video') } };
  return {};
}
// Rows the page can open by key; URLs always come from this view model, never from the tap.
function rows(view, s) {
  const v = forView(view, s);
  if (v.business) return [...v.business.receivable.rows, ...v.business.payable.rows];
  if (v.performance) return v.performance.groups.flatMap(g => g.rows);
  return [];
}
module.exports = { forView, rows, business, performance, previousPeriod };
