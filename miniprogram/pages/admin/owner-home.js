// Boss home reads the current business, finance and engineering facts.
const A = require('../../utils/account');
const B = require('../../utils/business');
const Ops = require('../../utils/operations');
const Handover = require('../../utils/management-handover');

const DAY = 86400000;
const STALE_DAYS = 7;
const TODO_PREVIEW = 4;
const SITE_PREVIEW = 3;
const cover = '/pages/build/assets/house.jpg';
const inMonth = (value, period) => (String(value || '').includes('T') ? A.localDate(value) : String(value || '')).slice(0, 7) === period;
const daysBefore = (today, n) => A.localDate(new Date(today + 'T12:00:00').getTime() - n * DAY);
const realFollows = customer => (customer.follows || []).filter(follow => !String(follow.note || follow.content || '').startsWith('登记客资'));
const followedIn = (customer, period, predicate = () => true) => realFollows(customer).some(follow => predicate(follow) && inMonth(follow.date || follow.at, period));
const target = percent => percent === null ? '目标待设置' : '目标完成 ' + percent + '%';
const detail = (view, extra = {}) => '/pages/admin/detail/index?view=' + view + Object.entries(extra).map(([key, value]) => '&' + key + '=' + encodeURIComponent(value)).join('');
const customerUrl = id => '/pages/workflow/index?view=customer&id=' + encodeURIComponent(id);
const commissionUrl = id => '/pages/workflow/commission/index?view=progress&id=' + encodeURIComponent(id);
const money = cents => '¥ ' + (Number(cents || 0) / 100).toLocaleString('en-US', { maximumFractionDigits: 2 });

function metricRows(snapshot, metric) {
  const customerRow = (customer, status, amount) => ({
    key: metric + ':' + customer.id,
    name: customer.name,
    status,
    url: metric === 'sales' || metric === 'commissions' ? commissionUrl(customer.id) : customerUrl(customer.id),
    ...(amount === undefined ? {} : { amount })
  });
  if (metric === 'sales' || metric === 'commissions') return snapshot.commissions.map(customer => {
    const amount = Math.round(Number(customer.serviceOrders?.at(-1)?.total || 0) * 100);
    return customerRow(customer, '设计委托 · ' + money(amount), amount);
  });
  if (metric === 'income') return (snapshot.state.receipts || []).filter(row => ['已核算', '已备案'].includes(row.status) && inMonth(row.bankDate || row.date, snapshot.period)).map(row => {
    const amount = Number(row.revisions?.at(-1)?.amount ?? row.amount ?? 0);
    return { key:'income:' + row.id, name:(row.customer ? row.customer + ' · ' : '') + row.name, status:(row.bankDate || row.date) + ' · ' + money(amount), amount, url:'/pages/admin/index?view=receipt&id=' + encodeURIComponent(row.id) };
  });
  if (metric === 'new-customers') return snapshot.newCustomers.map(customer => customerRow(customer, '本月登记客户'));
  if (metric === 'effective') return snapshot.customers.filter(customer => followedIn(customer, snapshot.period)).map(customer => customerRow(customer, '本月有真实跟进'));
  if (metric === 'visits') return snapshot.customers.filter(customer => followedIn(customer, snapshot.period, follow => follow.method === '到店')).map(customer => customerRow(customer, '本月实际到店'));
  return [];
}

function appointmentRows(snapshot) {
  return snapshot.customers.flatMap(customer => realFollows(customer).filter(follow => follow.appointment && inMonth(follow.appointment, snapshot.period) && [follow.appointmentType, follow.appointmentMethod, follow.method].includes('到店')).map((follow, index) => ({
    key:'appointment:' + customer.id + ':' + index,
    name:customer.name,
    status:String(follow.appointment).slice(0, 16) + ' · 预约到店',
    url:customerUrl(customer.id)
  })));
}

function acquisitionRows(snapshot) {
  return (snapshot.state.sources || []).filter(row => ['publication', 'lead'].includes(row.kind) && !row.excluded && inMonth(row.date, snapshot.period)).map(row => ({
    key:'acquisition:' + row.id,
    name:row.customer || row.name,
    status:row.kind === 'publication' ? '内容已发布' : '有效咨询已转客户',
    url:row.url
  }));
}

function six(snapshot) {
  const contacted = snapshot.customers.filter(customer => followedIn(customer, snapshot.period));
  const visited = snapshot.customers.filter(customer => followedIn(customer, snapshot.period, follow => follow.method === '到店'));
  const quoting = snapshot.customers.filter(customer => (customer.quotes || []).length && !customer.businessFlow?.submittedAt).length;
  return [
    { key:'metric:sales', metric:'sales', label: '设计委托金额', value: snapshot.sales, unit: '万', sub: target(snapshot.metrics[0].percent), url:detail('metric',{ metric:'sales' }) },
    { key:'metric:income', metric:'income', label: '回款金额', value: snapshot.income, unit: '万', sub: snapshot.reconcileCount ? '待核算 ' + snapshot.reconcileCount + ' 笔' : '按有效到账统计', url:detail('metric',{ metric:'income' }) },
    { key:'metric:commissions', metric:'commissions', label: '设计委托单数', value: String(snapshot.commissions.length), unit: '单', sub: '报价中 ' + quoting + ' 单', url:detail('metric',{ metric:'commissions' }) },
    { key:'metric:new-customers', metric:'new-customers', label: '新增线索', value: String(snapshot.newCustomers.length), unit: '条', sub: target(snapshot.metrics[1].percent), url:detail('metric',{ metric:'new-customers' }) },
    { key:'metric:effective', metric:'effective', label: '有效客户', value: String(contacted.length), unit: '个', sub: '本月有真实跟进', url:detail('metric',{ metric:'effective' }) },
    { key:'metric:visits', metric:'visits', label: '到店客户', value: String(visited.length), unit: '人', sub: '本月到店沟通', url:detail('metric',{ metric:'visits' }) }
  ];
}

function moneyKind(task) {
  if (task.record === 'quote') return '正式报价';
  if (task.record === 'service') return '设计服务费';
  if (task.view === 'pay-approval') return '付款申请';
  return '金额审批';
}

function handovers(snapshot) {
  const active = new Map((snapshot.state.staff || snapshot.staff || []).map(person => [person.id, person.active !== false]));
  return (snapshot.state.work || []).filter(work => {
    if (!['customer-responsibility', 'project-responsibility'].includes(work.kind)) return false;
    const finished = /已归档|已交付/.test(work.stage || '');
    if (finished && !work.handoverRequired) return false;
    const transfer = (snapshot.state.transfers || []).filter(row => row.workId === work.id && row.to === work.owner).sort((a, b) => String(a.effectiveAt || '').localeCompare(String(b.effectiveAt || ''))).at(-1);
    const markedAt = work.handoverRequiredAt || work.handoverMarkedAt || '';
    const explicit = Handover.pending(snapshot.state, work) || work.handoverRequired === true && (!transfer || markedAt && String(transfer.effectiveAt || '') < markedAt);
    return explicit || active.get(work.owner) === false;
  }).map(work => ({
    key:'handover:' + work.id,
    url:'/pages/admin/index?view=transfer&id=' + encodeURIComponent(work.id),
    name:work.customer + ' · ' + work.name,
    tag:'责任交接',
    detail:(snapshot.staff.find(person => person.id === work.owner)?.name || work.owner || '原负责人待核对') + ' · ' + (work.stage || '待办理'),
    action:'去交接',
    group:'handover'
  }));
}

function todos(snapshot) {
  const money = snapshot.approvals.map(task => ({ key: task.key, url: task.url, name: task.name, tag: moneyKind(task), detail: [task.amount, task.status].filter(Boolean).join(' · '), action: '去审批', group: 'money' }));
  const reviews = snapshot.reviews.map(task => ({ key: task.key, url: task.url, name: task.name, tag: '结果复核', detail: task.status, action: '去复核', group: 'review' }));
  const handover = handovers(snapshot);
  const all = [...money, ...reviews, ...handover];
  return { all, groups: [{ key: 'all', label: '全部', count: all.length }, { key: 'money', label: '金额审批', count: money.length }, { key: 'review', label: '结果复核', count: reviews.length }, { key:'handover', label:'责任交接', count:handover.length }] };
}

function business(snapshot) {
  const counts = B.STAGES.map(label => ({ label, value: 0, unit: '个' }));
  for (const customer of snapshot.customers) counts[Math.min(B.STAGES.length - 1, B.stageIndex(customer))].value += 1;
  const unfinished = snapshot.customers.filter(customer => B.stageIndex(customer) < B.STAGES.length - 1);
  const stale = unfinished.filter(customer => {
    const last = realFollows(customer).map(follow => String(follow.date || '').slice(0, 10)).sort().at(-1) || String(customer.createdAt || '').slice(0, 10);
    return last && last < daysBefore(snapshot.today, STALE_DAYS);
  });
  const due = unfinished.filter(customer => {
    const next = String(realFollows(customer).at(-1)?.appointment || '').slice(0, 10);
    return next && next <= snapshot.today;
  });
  const owners = [...new Set(stale.map(customer => customer.owner).filter(Boolean))].join('、');
  const appointments = appointmentRows(snapshot), acquisition = acquisitionRows(snapshot);
  return {
    stages: counts,
    stale: stale.length,
    appointments:{ key:'appointments', label:'预约到店', value:appointments.length, unit:'项', url:detail('appointments') },
    acquisition:{ key:'acquisition', label:'内容获客', value:acquisition.length, unit:'项', url:detail('acquisition') },
    notes: [
      { level: stale.length ? 'red' : 'green', text: stale.length ? stale.length + ' 个客户超 ' + STALE_DAYS + ' 天未跟进' + (owners ? ' · ' + owners : '') : '没有超 ' + STALE_DAYS + ' 天未跟进的客户' },
      { level: due.length ? 'amber' : 'green', text: '今日待跟进 ' + due.length + ' 人' }
    ]
  };
}

function payTags(snapshot, project) {
  const id = project.projectId || project.id;
  const plan = Ops.receivable(snapshot.state, id);
  if (!plan.installments.length) return [{ text: '收款约定待确认', level: 'gray' }];
  return plan.installments.map(item => item.remaining <= 0 ? { text: item.name + '已收', level: 'green' } : item.overdue ? { text: item.name + '逾期', level: 'red' } : { text: item.name + '待收', level: 'gray' });
}

function engineering(snapshot) {
  const live = snapshot.projects.filter(project => !project.archived);
  const active = live.filter(project => project.actualStart && !project.delivery);
  const late = project => (project.plan?.nodes || []).some(node => node.status !== '已完成' && node.end && node.end < snapshot.today);
  const sites = active.map(project => {
    const nodes = project.plan?.nodes || [];
    const current = nodes.find(node => node.status !== '已完成');
    const delayed = late(project);
    return {
      key: 'site:' + project.id,
      name: project.name || project.customer,
      image: project.image || cover,
      stage: current?.name || '施工收尾',
      late: delayed,
      nodes: nodes.map(node => node.status === '已完成' ? 'done' : node === current ? (delayed ? 'late' : 'now') : ''),
      pays: payTags(snapshot, project),
      url: '/pages/build/index?view=overview&id=' + encodeURIComponent(project.id)
    };
  }).sort((a, b) => Number(b.late) - Number(a.late));
  return {
    counts: [
      { label: '在建', value: active.length },
      { label: '待开工', value: live.filter(project => !project.actualStart && !project.delivery).length },
      { label: '本月交付', value: live.filter(project => project.delivery && inMonth(project.delivery.date || project.delivery.at, snapshot.period)).length },
      { label: '延期', value: active.filter(late).length }
    ],
    sites
  };
}

function home(snapshot) {
  const todo = todos(snapshot);
  const construction = engineering(snapshot);
  return { six: six(snapshot), groups: todo.groups, todos: todo.all.slice(0, TODO_PREVIEW), business: business(snapshot), engineering: { counts: construction.counts, sites: construction.sites.slice(0, SITE_PREVIEW) } };
}

const todoRows = (snapshot, tab = 'all') => todos(snapshot).all.filter(todo => tab === 'all' || todo.group === tab);
const visibleTodos = (snapshot, tab) => todoRows(snapshot, tab).slice(0, TODO_PREVIEW);
function rows(view, snapshot, options = {}) {
  if (view === 'metric') return metricRows(snapshot, options.metric || '');
  if (view === 'appointments') return appointmentRows(snapshot);
  if (view === 'acquisition') return acquisitionRows(snapshot);
  return null;
}

module.exports = { home, todoRows, visibleTodos, rows };
