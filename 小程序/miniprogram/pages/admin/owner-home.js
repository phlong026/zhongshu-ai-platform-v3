// Boss home: six monthly figures, grouped boss to-dos, business and engineering cards.
// Everything is read from the snapshot's original records; nothing is stored here.
const A = require('../../utils/account');
const Ops = require('../../utils/operations');

const DAY = 86400000;
const STALE_DAYS = 7;
const TODO_PREVIEW = 4;
const SITE_PREVIEW = 3;
const cover = '/pages/build/assets/house.jpg';
const inMonth = (value, period) => String(value || '').slice(0, 7) === period;
const daysBefore = (today, n) => A.localDate(new Date(today + 'T12:00:00').getTime() - n * DAY);
// Registration notes are written by the system, not a real conversation with the customer.
const realFollows = c => (c.follows || []).filter(f => !String(f.note || f.content || '').startsWith('登记客资'));
const target = p => p === null ? '目标待设置' : '目标完成 ' + p + '%';

function six(s) {
  const contacted = s.customers.filter(c => realFollows(c).some(f => inMonth(f.date, s.period)));
  const visited = s.customers.filter(c => (c.follows || []).some(f => f.method === '到店' && inMonth(f.date, s.period)));
  const quoting = s.customers.filter(c => !c.contract && (c.quotes || []).length).length;
  return [
    { label: '签约金额', value: s.sales, unit: '万', sub: target(s.metrics[0].percent) },
    { label: '回款金额', value: s.income, unit: '万', sub: s.reconcileCount ? '待核算 ' + s.reconcileCount + ' 笔' : '按有效核算' },
    { label: '签约单数', value: String(s.contracts.length), unit: '单', sub: '报价中 ' + quoting + ' 单' },
    { label: '新增线索', value: String(s.newCustomers.length), unit: '条', sub: target(s.metrics[1].percent) },
    { label: '有效客户', value: String(contacted.length), unit: '个', sub: '本月有跟进记录' },
    { label: '到店客户', value: String(visited.length), unit: '人', sub: '本月到店沟通' }
  ];
}

function moneyKind(t) {
  if (t.record === 'quote') return '正式报价';
  if (t.record === 'service') return '设计服务费';
  if (t.view === 'pay-approval') return '付款申请';
  return '金额审批';
}

function todos(s) {
  const money = s.approvals.map(t => ({ key: t.key, url: t.url, name: t.name, tag: moneyKind(t), detail: [t.amount, t.status].filter(Boolean).join(' · '), action: '去审批', group: 'money' }));
  const reviews = s.reviews.map(t => ({ key: t.key, url: t.url, name: t.name, tag: '结果复核', detail: t.status, action: '去复核', group: 'review' }));
  const all = [...money, ...reviews];
  return { all, groups: [{ key: 'all', label: '全部', count: all.length }, { key: 'money', label: '金额审批', count: money.length }, { key: 'review', label: '结果复核', count: reviews.length }] };
}

function business(s) {
  const open = s.customers.filter(c => !c.contract);
  const quoting = open.filter(c => (c.quotes || []).length);
  const designing = open.filter(c => !(c.quotes || []).length && ((c.budgets || []).length || c.request));
  const stale = open.filter(c => {
    const last = realFollows(c).map(f => String(f.date || '').slice(0, 10)).sort().at(-1) || String(c.createdAt || '').slice(0, 10);
    return last && last < daysBefore(s.today, STALE_DAYS);
  });
  const due = open.filter(c => { const next = String(realFollows(c).at(-1)?.appointment || '').slice(0, 10); return next && next <= s.today; });
  const owners = [...new Set(stale.map(c => c.owner).filter(Boolean))].join('、');
  return {
    stages: [
      { label: '跟进中', value: open.length - quoting.length - designing.length, unit: '人' },
      { label: '方案中', value: designing.length, unit: '单' },
      { label: '报价中', value: quoting.length, unit: '单' },
      { label: '已签约', value: s.customers.length - open.length, unit: '单' }
    ],
    stale: stale.length,
    notes: [
      { level: stale.length ? 'red' : 'green', text: stale.length ? stale.length + ' 个客户超 ' + STALE_DAYS + ' 天未跟进' + (owners ? ' · ' + owners : '') : '没有超 ' + STALE_DAYS + ' 天未跟进的客户' },
      { level: due.length ? 'amber' : 'green', text: '今日待跟进 ' + due.length + ' 人' }
    ]
  };
}

function payTags(s, p) {
  const id = p.projectId || p.id;
  const plan = Ops.receivable(s.state, id);
  if (!plan.installments.length) return [{ text: '收款约定待确认', level: 'gray' }];
  return plan.installments.map(i => i.remaining <= 0 ? { text: i.name + '已收', level: 'green' } : i.overdue ? { text: i.name + '逾期', level: 'red' } : { text: i.name + '待收', level: 'gray' });
}

function engineering(s) {
  const live = s.projects.filter(p => !p.archived);
  const active = live.filter(p => p.actualStart && !p.delivery);
  const late = p => (p.plan?.nodes || []).some(n => n.status !== '已完成' && n.end && n.end < s.today);
  const sites = active.map(p => {
    const nodes = p.plan?.nodes || [];
    const current = nodes.find(n => n.status !== '已完成');
    return {
      key: 'site:' + p.id, name: p.name || p.customer, image: p.image || cover, stage: current?.name || '施工收尾', late: late(p),
      nodes: nodes.map(n => n.status === '已完成' ? 'done' : n === current ? (late(p) ? 'late' : 'now') : ''),
      pays: payTags(s, p), url: '/pages/build/index?view=overview&id=' + encodeURIComponent(p.id)
    };
  }).sort((a, b) => Number(b.late) - Number(a.late));
  return {
    counts: [
      { label: '在建', value: active.length },
      { label: '待开工', value: live.filter(p => !p.actualStart && !p.delivery).length },
      { label: '本月交付', value: live.filter(p => p.delivery && inMonth(p.delivery.date || p.delivery.at, s.period)).length },
      { label: '延期', value: active.filter(late).length }
    ],
    sites
  };
}

function home(s) {
  const t = todos(s);
  const e = engineering(s);
  return { six: six(s), groups: t.groups, todos: t.all, business: business(s), engineering: { counts: e.counts, sites: e.sites.slice(0, SITE_PREVIEW) }, sites: e.sites };
}

const visibleTodos = (h, tab) => h.todos.filter(t => tab === 'all' || t.group === tab).slice(0, TODO_PREVIEW);

module.exports = { home, visibleTodos };
