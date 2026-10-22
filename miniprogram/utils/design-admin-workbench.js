const FILTERS = ['全部', '设计中', '修改中', '待财务通知', '待下发', '已下发'];
const STATUS_ORDER = { 修改中: 0, 设计中: 1, 待财务通知: 2, 待下发: 3, 已下发: 4 };

function assertActor(actor) {
  if (!actor?.active || actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters') throw Error('当前岗位无设计行政权限');
}

function statusOf(customer, next) {
  const flow = customer.businessFlow || {};
  if (flow.dispatch || customer.acceptance) return '已下发';
  if (flow.feedbacks?.at(-1)?.status === 'pending') return '修改中';
  if (!flow.completed) return '设计中';
  if (next.view === 'dispatch') return '待下发';
  return '待财务通知';
}

function latestActorEvent(customer, staffId) {
  return (customer.businessFlow?.events || [])
    .filter(event => event.actorId === staffId && ['design-complete', 'design-dispatch'].includes(event.action))
    .sort((a, b) => String(b.at || '').localeCompare(String(a.at || '')))[0] || null;
}

function eventLabel(action) {
  return action === 'design-dispatch' ? '成果已下发' : '已登记设计完成';
}

function eventTime(at) {
  const date = new Date(at);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function build(customers, actor, options = {}) {
  assertActor(actor);
  const nextFor = options.nextFor || (customer => require('./sales-flow').next(customer));
  const feeApproved = options.feeApproved || (customer => require('./sales-flow').feeApproved(require('./sales-flow').raw(customer)));
  const companyNameFor = options.companyNameFor || companyName;
  const salesFlow = options.salesFlow || require('./sales-flow');
  const rows = customers
    .filter(customer => customer?.businessFlow?.type === 'sales-offline-v1')
    .filter(customer => customer.designOrganizationId === actor.companyId && feeApproved(customer))
    .map(customer => {
      const next = nextFor(customer);
      const status = statusOf(customer, next);
      const company = companyNameFor(customer.companyId);
      const event = latestActorEvent(customer, actor.staffId);
      const actionView = next.actionable && ['complete', 'dispatch'].includes(next.view) ? next.view : 'progress';
      return {
        id: customer.id,
        projectId: customer.projectId || customer.id,
        name: customer.name,
        initial: String(customer.name || '项').slice(0, 1),
        city: customer.city || '',
        companyId: customer.companyId,
        company,
        owner: customer.owner || '未登记',
        status,
        tone: status === '已下发' || status === '待下发' ? 'done' : status === '待财务通知' ? 'waiting' : '',
        state: next.state,
        scope: customer.request?.scope || '随委托确认',
        detail: status === '修改中' ? '客户新增修改意见 · 待继续设计' : status === '设计中' ? '新委托 · 核对需求与资料' : status === '待财务通知' ? '设计已完成 · 等待财务放行' : status === '待下发' ? '设计已完成 · 核对财务通知后登记下发' : '正式成果已登记下发',
        url: salesFlow.url(customer.id, 'progress'),
        actionUrl: salesFlow.url(customer.id, actionView),
        action: status === '修改中' ? '处理修改' : status === '设计中' ? '查看委托' : status === '待财务通知' ? '查看进度' : status === '待下发' ? '登记下发' : '查看记录',
        actionable: !!(next.actionable && ['complete', 'dispatch'].includes(next.view)),
        actorId: event?.actorId || '',
        recentAt: event?.at || '',
        recentWhen: event?.at ? eventTime(event.at) : '',
        recentText: event ? eventLabel(event.action) : ''
      };
    })
    .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.name.localeCompare(b.name, 'zh-CN'));
  const counts = Object.fromEntries(FILTERS.map(label => [label, label === '全部' ? rows.length : rows.filter(row => row.status === label).length]));
  const actionable = rows.filter(row => row.actionable);
  const todos = actionable.slice(0, 3).map((row, index) => ({ ...row, customerId: row.id, url: row.actionUrl, primary: index === 0 }));
  const recent = rows.filter(row => row.recentAt).sort((a, b) => b.recentAt.localeCompare(a.recentAt)).slice(0, 5);
  const metrics = [
    { label: '设计与修改', value: counts['设计中'] + counts['修改中'], filter: '设计与修改', icon: '/assets/home-design-icon.png' },
    { label: '待财务通知', value: counts['待财务通知'], filter: '待财务通知', icon: '/assets/money.png' },
    { label: '待下发', value: counts['待下发'], filter: '待下发', icon: '/assets/contract.png' }
  ];
  return { rows, counts, todos, taskCount: actionable.length, recent, metrics, filters: FILTERS.map(label => ({ label, count: counts[label] })) };
}

function companyName(id) {
  const name = require('./finance-desk').companyName(id);
  return name === id ? '加盟商名称待完善' : name;
}

function filter(rows, keyword = '', status = '全部') {
  const query = String(keyword).trim().toLowerCase();
  return rows.filter(row => (status === '全部' || status === '设计与修改' && ['设计中', '修改中'].includes(row.status) || row.status === status) && (!query || [row.name, row.projectId, row.company, row.owner, row.city].join(' ').toLowerCase().includes(query)));
}

function paginate(rows, keyword = '', status = '全部', limit = 20) {
  const filtered = filter(rows, keyword, status);
  const size = Math.max(20, Number(limit) || 20);
  return { rows: filtered.slice(0, size), total: filtered.length, hasMore: filtered.length > size };
}

function dateLabel(date = require('./account').localDate()) {
  const value = new Date(date + 'T00:00:00');
  return `${value.getMonth() + 1}月${value.getDate()}日 周${'日一二三四五六'[value.getDay()]}`;
}

function load(actor = require('./access').context()) {
  assertActor(actor);
  const SalesFlow = require('./sales-flow');
  const projected = require('./business').load().customers;
  const rawRows = ((wx.getStorageSync('zhongshu-business-v3') || {}).customers || []);
  const rawById = new Map(rawRows.map(customer => [`${customer.companyId || 'quanzhou'}:${customer.id}`, customer]));
  const companyNames = new Map([...new Set(projected.map(customer => customer.companyId || 'quanzhou'))].map(id => [id, companyName(id)]));
  const rawFor = customer => rawById.get(`${customer.companyId || 'quanzhou'}:${customer.id}`) || customer;
  return { ...build(projected, actor, { nextFor: customer => SalesFlow.nextRecord(rawFor(customer)), feeApproved: customer => SalesFlow.feeApproved(rawFor(customer)), companyNameFor: id => companyNames.get(id || 'quanzhou') || '加盟商名称待完善' }), actor, date: dateLabel() };
}

module.exports = { FILTERS, statusOf, build, filter, paginate, dateLabel, load };
