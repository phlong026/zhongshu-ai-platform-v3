// Boss home reads the current business, finance and engineering facts.
const A = require('../../utils/account');
const B = require('../../utils/business');
const Ops = require('../../utils/operations');
const X = require('../../utils/access');
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
const latestFollow = customer => realFollows(customer).at(-1);
const isVisitAppointment = follow => !!follow?.appointment && ([follow.appointmentType, follow.appointmentMethod].includes('到店') || /到店/.test(follow.next || ''));
const isUpcomingVisit = (customer, snapshot) => {
  const follow = latestFollow(customer);
  const day = String(follow?.appointment || '').slice(0, 10);
  return day && day >= snapshot.today && inMonth(day, snapshot.period) && customer.status !== '已到店' && isVisitAppointment(follow);
};
const detail = metric => '/pages/admin/detail/index?view=stat&id=' + encodeURIComponent(metric);
const oldDetail = (view, extra = {}) => '/pages/admin/detail/index?view=' + view + Object.entries(extra).map(([key, value]) => '&' + key + '=' + encodeURIComponent(value)).join('');
const customerUrl = id => '/pages/workflow/index?view=customer&id=' + encodeURIComponent(id);
const commissionUrl = id => '/pages/workflow/commission/index?view=progress&id=' + encodeURIComponent(id);
const money = cents => '¥ ' + (Number(cents || 0) / 100).toLocaleString('en-US', { maximumFractionDigits: 2 });
const staffName = (snapshot, id) => (snapshot.state.staff || snapshot.staff || []).find(person => person.id === id)?.name || id || '待配置负责人';
const yuan = value => '¥ ' + Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 2 });

function growthFacts(snapshot) {
  const growth = wx.getStorageSync('zhongshu-growth-v1') || {};
  const companyId = snapshot.actor?.companyId || snapshot.state.companyId;
  const sameCompany = row => !companyId || row.companyId === companyId;
  const contents = (growth.contents || []).filter(row => sameCompany(row) && row.status === '演示已发布' && inMonth(row.publishedAt || row.linkRecordedAt, snapshot.period));
  const leads = (growth.leads || []).filter(row => sameCompany(row) && row.status !== '无效' && inMonth(row.at || row.createdAt, snapshot.period));
  return { contents, leads, contentLeads: leads.filter(row => row.contentId) };
}

function statRows(snapshot, metric) {
  const customerRow = (customer, status) => ({
    key: metric + ':' + customer.id,
    name: customer.name,
    status,
    url: '/pages/workflow/index?view=customer&id=' + encodeURIComponent(customer.id)
  });
  if (metric === 'commission-amount' || metric === 'commission-count') {
    return snapshot.commissions.map(customer => {
      const order = customer.serviceOrders.at(-1);
      return {
        ...customerRow(customer, [order.id || '设计委托原单', String(customer.businessFlow.submittedAt || '').slice(0, 10), staffName(snapshot, X.ownerId(customer))].filter(Boolean).join(' · ')),
        amount: yuan(order.total),
        url: '/pages/workflow/commission/index?view=progress&id=' + encodeURIComponent(customer.id)
      };
    });
  }
  if (metric === 'income') {
    return snapshot.state.receipts
      .filter(row => ['已核算', '已备案'].includes(row.status) && inMonth(row.bankDate || row.date, snapshot.period))
      .map(row => ({
        key: metric + ':' + row.id,
        name: [row.customer, row.name].filter(Boolean).join(' · '),
        status: [row.id, row.bankDate || row.date, row.registrar || staffName(snapshot, row.financialRegistrarId || row.registrarId)].filter(Boolean).join(' · '),
        amount: yuan((row.revisions?.at(-1)?.amount ?? row.amount ?? 0) / 100),
        url: '/pages/admin/index?view=receipt&id=' + encodeURIComponent(row.id)
      }));
  }
  if (metric === 'new-leads') {
    return growthFacts(snapshot).leads.map(row => ({
      key: metric + ':' + row.id,
      name: row.name,
      status: [row.source || '直接来访', staffName(snapshot, row.ownerId || row.registrarId), String(row.at || row.createdAt || '').slice(0, 10)].filter(Boolean).join(' · '),
      url: '/pages/growth/index?view=lead&id=' + encodeURIComponent(row.id)
    }));
  }
  if (metric === 'effective-customers') {
    return snapshot.customers.filter(customer => realFollows(customer).some(follow => inMonth(follow.date || follow.at, snapshot.period))).map(customer => {
      const follow = realFollows(customer).filter(item => inMonth(item.date || item.at, snapshot.period)).at(-1);
      return customerRow(customer, [staffName(snapshot, X.ownerId(customer)), follow?.date || follow?.at, follow?.method + '沟通'].filter(Boolean).join(' · '));
    });
  }
  if (metric === 'visited-customers') {
    return snapshot.customers.filter(customer => realFollows(customer).some(follow => follow.method === '到店' && inMonth(follow.date || follow.at, snapshot.period))).map(customer => {
      const follow = realFollows(customer).filter(item => item.method === '到店' && inMonth(item.date || item.at, snapshot.period)).at(-1);
      return customerRow(customer, [staffName(snapshot, X.ownerId(customer)), follow?.date || follow?.at, '到店沟通'].filter(Boolean).join(' · '));
    });
  }
  if (metric === 'stale-customers') {
    return snapshot.customers.filter(customer => {
      if (B.stageIndex(customer) >= B.STAGES.length - 1) return false;
      const last = realFollows(customer).map(follow => String(follow.date || follow.at || '').slice(0, 10)).sort().at(-1) || String(customer.createdAt || '').slice(0, 10);
      return last && last < daysBefore(snapshot.today, STALE_DAYS);
    }).map(customer => customerRow(customer, [staffName(snapshot, X.ownerId(customer)), '超过 ' + STALE_DAYS + ' 天未跟进'].join(' · ')));
  }
  if (metric === 'appointment-followups') {
    return snapshot.customers.filter(customer => {
      if (B.stageIndex(customer) >= B.STAGES.length - 1) return false;
      const last = latestFollow(customer);
      const due = String(last?.appointment || '').slice(0, 10);
      return (due && due <= snapshot.today) || isUpcomingVisit(customer, snapshot);
    }).map(customer => {
      const follow = latestFollow(customer);
      return customerRow(customer, [staffName(snapshot, X.ownerId(customer)), follow?.appointment || '待跟进', follow?.next].filter(Boolean).join(' · '));
    });
  }
  if (metric === 'content-acquisition') {
    const growth = growthFacts(snapshot);
    const contents = growth.contents.map(row => ({
      key: metric + ':content:' + row.id,
      name: row.pack?.title || row.title || '已发布内容',
      status: ['发布内容', staffName(snapshot, row.authorId), String(row.publishedAt || row.linkRecordedAt || '').slice(0, 10)].filter(Boolean).join(' · '),
      url: '/pages/growth/index?view=content&id=' + encodeURIComponent(row.id)
    }));
    const leads = growth.contentLeads.map(row => ({
      key: metric + ':lead:' + row.id,
      name: row.name,
      status: ['内容获客', staffName(snapshot, row.ownerId || row.registrarId), String(row.at || row.createdAt || '').slice(0, 10)].filter(Boolean).join(' · '),
      url: '/pages/growth/index?view=lead&id=' + encodeURIComponent(row.id)
    }));
    return [...contents, ...leads];
  }
  return [];
}

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
  return snapshot.customers.flatMap(customer => realFollows(customer).filter(follow => isVisitAppointment(follow) && inMonth(follow.appointment, snapshot.period)).map((follow, index) => ({
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
  const contacted = snapshot.customers.filter(customer => realFollows(customer).some(follow => inMonth(follow.date || follow.at, snapshot.period)));
  const visited = snapshot.customers.filter(customer => realFollows(customer).some(follow => follow.method === '到店' && inMonth(follow.date || follow.at, snapshot.period)));
  const growth = growthFacts(snapshot);
  const quoting = snapshot.customers.filter(customer => (customer.quotes || []).length && !customer.businessFlow?.submittedAt).length;
  return [
    { key: 'stat:commission-amount', metric: 'commission-amount', url: detail('commission-amount'), label: '设计委托金额', value: snapshot.sales, unit: '万', sub: target(snapshot.metrics[0].percent) },
    { key: 'stat:income', metric: 'income', url: detail('income'), label: '回款金额', value: snapshot.income, unit: '万', sub: snapshot.reconcileCount ? '待核算 ' + snapshot.reconcileCount + ' 笔' : '按有效到账统计' },
    { key: 'stat:commission-count', metric: 'commission-count', url: detail('commission-count'), label: '设计委托单数', value: String(snapshot.commissions.length), unit: '单', sub: '报价中 ' + quoting + ' 单' },
    { key: 'stat:new-leads', metric: 'new-leads', url: detail('new-leads'), label: '新增线索', value: String(growth.leads.length), unit: '条', sub: '内容获客 ' + growth.contentLeads.length + ' 条' },
    { key: 'stat:effective-customers', metric: 'effective-customers', url: detail('effective-customers'), label: '有效客户', value: String(contacted.length), unit: '个', sub: '本月有真实跟进' },
    { key: 'stat:visited-customers', metric: 'visited-customers', url: detail('visited-customers'), label: '到店客户', value: String(visited.length), unit: '人', sub: '本月到店沟通' }
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
    if (!Handover.transferable(work)) return false;
    const finished = /已归档|已交付/.test(work.stage || '');
    if (finished && !work.handoverRequired) return false;
    const transfer = (snapshot.state.transfers || []).filter(row => row.workId === work.id && row.to === work.owner).sort((a, b) => String(a.effectiveAt || '').localeCompare(String(b.effectiveAt || ''))).at(-1);
    const markedAt = work.handoverRequiredAt || work.handoverMarkedAt || '';
    const explicit = Handover.pending(snapshot.state, work) || work.handoverRequired === true && (!transfer || markedAt && String(transfer.effectiveAt || '') < markedAt);
    return explicit || active.get(work.owner) === false;
  }).map(work => ({
    key:'handover:' + work.id,
    url:'/pages/admin/index?view=transfer&id=' + encodeURIComponent(work.id),
    name:[work.customer, work.name].filter(Boolean).join(' · '),
    tag:'责任交接',
    detail:((snapshot.state.staff || snapshot.staff || []).find(person => person.id === work.owner)?.name || work.owner || '原负责人待核对') + ' · ' + (work.stage || '待办理'),
    action:'去交接',
    group:'handover'
  }));
}

function todos(snapshot) {
  const money = snapshot.approvals.map(task => ({ key: task.key, url: task.url, name: task.name, tag: moneyKind(task), detail: [task.amount, task.status].filter(Boolean).join(' · '), action: '去审批', group: 'money' }));
  const reviews = snapshot.reviews.map(task => ({ key: task.key, url: task.url, name: task.name, tag: '结果复核', detail: task.status, action: '去复核', group: 'review' }));
  const handover = handovers(snapshot);
  const all = [...money, ...reviews, ...handover];
  return { all, groups: [{ key: 'all', label: '全部', count: all.length }, { key: 'money', label: '金额审批', count: money.length }, { key: 'review', label: '结果复核', count: reviews.length }, { key: 'handover', label: '责任交接', count: handover.length }] };
}

function business(snapshot) {
  const counts = B.STAGES.map(label => ({ label, value: 0, unit: '个' }));
  for (const customer of snapshot.customers) counts[Math.min(B.STAGES.length - 1, B.stageIndex(customer))].value += 1;
  const unfinished = snapshot.customers.filter(customer => B.stageIndex(customer) < B.STAGES.length - 1);
  const stale = unfinished.filter(customer => {
    const last = realFollows(customer).map(follow => String(follow.date || follow.at || '').slice(0, 10)).sort().at(-1) || String(customer.createdAt || '').slice(0, 10);
    return last && last < daysBefore(snapshot.today, STALE_DAYS);
  });
  const due = unfinished.filter(customer => {
    const next = String(realFollows(customer).at(-1)?.appointment || '').slice(0, 10);
    return next && next <= snapshot.today;
  });
  const appointments = unfinished.filter(customer => isUpcomingVisit(customer, snapshot));
  const growth = growthFacts(snapshot);
  const oldAppointments = appointmentRows(snapshot), acquisition = acquisitionRows(snapshot);
  const owners = [...new Set(stale.map(customer => customer.owner).filter(Boolean))].join('、');
  return {
    stages: counts,
    stale: stale.length,
    appointments:{ key:'appointments', label:'预约到店', value:oldAppointments.length, unit:'项', url:oldDetail('appointments') },
    acquisition:{ key:'acquisition', label:'内容获客', value:acquisition.length, unit:'项', url:oldDetail('acquisition') },
    notes: [
      { key: 'attention:stale', url: detail('stale-customers'), level: stale.length ? 'red' : 'green', text: stale.length ? stale.length + ' 个客户超 ' + STALE_DAYS + ' 天未跟进' + (owners ? ' · ' + owners : '') : '没有超 ' + STALE_DAYS + ' 天未跟进的客户' },
      { key: 'attention:appointments', url: detail('appointment-followups'), level: due.length || appointments.length ? 'amber' : 'green', text: '今日待跟进 ' + due.length + ' 人 · 预约到店 ' + appointments.length + ' 人' },
      { key: 'attention:content', url: detail('content-acquisition'), level: 'green', text: '本月发布内容 ' + growth.contents.length + ' 条 · 内容获客 ' + growth.contentLeads.length + ' 条' }
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
  if (view === 'stat') return statRows(snapshot, typeof options === 'string' ? options : options.id || options.metric || '');
  if (view === 'metric') return metricRows(snapshot, options.metric || '');
  if (view === 'appointments') return appointmentRows(snapshot);
  if (view === 'acquisition') return acquisitionRows(snapshot);
  return null;
}

module.exports = { home, todoRows, visibleTodos, rows, statRows };
