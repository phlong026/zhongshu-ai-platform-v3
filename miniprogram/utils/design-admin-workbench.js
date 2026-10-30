const Checkout = require('./design-checkout');
const Status = require('./status-labels').commission;
const TaskOrder = require('./task-order');
const FILTERS = ['全部', Status.pay, Status.designing, Status.modifying, Status.receiptWaiting, Status.dispatch, Status.handover, Status.receive, Status.dispatched];
const STATUS_ORDER = { [Status.modifying]:0, [Status.designing]:1, [Status.pay]:2, [Status.receiptWaiting]:3, [Status.dispatch]:4, [Status.handover]:5, [Status.receive]:6, [Status.dispatched]:7 };

function assertActor(actor) {
  if (!actor?.active || actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters') throw Error('当前岗位无设计行政权限');
}

function statusOf(customer, next) {
  const flow = customer.businessFlow || {};
  if (customer.acceptance) return Status.dispatched;
  if (next?.state && FILTERS.includes(next.state)) return next.state;
  if (customer.handover) return Status.receive;
  if (flow.dispatch) return Status.handover;
  if (Checkout.is(customer) && !Checkout.paid(customer) || next.waitingFirst) return Status.pay;
  if (flow.feedbacks?.at(-1)?.status === 'pending') return Status.modifying;
  if (!flow.completed) return Status.designing;
  if (next.view === 'dispatch') return Status.dispatch;
  return Status.receiptWaiting;
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
        tone: status === Status.dispatched || status === Status.dispatch ? 'done' : [Status.pay, Status.receiptWaiting, Status.handover, Status.receive].includes(status) ? 'waiting' : '',
        state: next.state,
        scope: customer.request?.scope || '随委托确认',
        detail: status === Status.modifying ? '客户新增修改意见 · 待继续设计' : status === Status.designing ? (Checkout.is(customer) ? '已一次性支付 · 核对需求与资料' : '设计首款已登记 · 核对需求与资料') : [Status.pay, Status.receiptWaiting].includes(status) ? (next.detail || next.roleState || next.state) + ' · 下一位：' + next.owner : status === Status.dispatch ? (Checkout.is(customer) ? '设计完成 · 上传并下发正式成果' : '总部已确认到账 · 登记正式成果下发') : status === Status.handover ? '正式成果已下发 · 待经办业务移交' : status === Status.receive ? '业务已移交 · 待工程负责人接收' : '正式成果与移交记录可查',
        nextOwner: next.owner || '',
        url: salesFlow.url(customer.id, 'progress'),
        actionUrl: salesFlow.url(customer.id, actionView),
        action: status === Status.modifying ? '处理修改' : status === Status.designing ? '查看委托' : [Status.pay, Status.receiptWaiting, Status.handover, Status.receive].includes(status) ? '查看进度' : status === Status.dispatch ? '上传并下发' : '查看记录',
        actionable: !!(next.actionable && ['complete', 'dispatch'].includes(next.view)),
        triggeredAt: (options.triggeredAtFor || salesFlow.triggeredAt)(customer, next.view),
        actorId: event?.actorId || '',
        recentAt: event?.at || '',
        recentWhen: event?.at ? eventTime(event.at) : '',
        recentText: event ? eventLabel(event.action) : ''
      };
    })
    .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.name.localeCompare(b.name, 'zh-CN'));
  const counts = Object.fromEntries(FILTERS.map(label => [label, label === '全部' ? rows.length : rows.filter(row => row.status === label).length]));
  const actionable = TaskOrder.newest(rows.filter(row => row.actionable));
  const todos = actionable.slice(0, 3).map((row, index) => ({ ...row, customerId: row.id, url: row.actionUrl, primary: index === 0 }));
  const recent = rows.filter(row => row.recentAt).sort((a, b) => b.recentAt.localeCompare(a.recentAt)).slice(0, 5);
  const metrics = [
    { label: '设计与修改', value: counts[Status.designing] + counts[Status.modifying], filter: '设计与修改', icon: '/assets/home-design-icon.png' },
    { label: Status.pay, value: counts[Status.pay], filter: Status.pay, icon: '/assets/money.png' },
    { label: Status.dispatch, value: counts[Status.dispatch], filter: Status.dispatch, icon: '/assets/contract.png' }
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
  const rawRows = ((wx.getStorageSync(require('./storage-keys').business) || {}).customers || []);
  const rawById = new Map(rawRows.map(customer => [`${customer.companyId || 'quanzhou'}:${customer.id}`, customer]));
  const companyNames = new Map([...new Set(projected.map(customer => customer.companyId || 'quanzhou'))].map(id => [id, companyName(id)]));
  const rawFor = customer => rawById.get(`${customer.companyId || 'quanzhou'}:${customer.id}`) || customer;
  return { ...build(projected, actor, { nextFor: customer => SalesFlow.nextRecord(rawFor(customer)), triggeredAtFor: (customer, view) => SalesFlow.triggeredAt(rawFor(customer), view), feeApproved: customer => SalesFlow.feeApproved(rawFor(customer)), companyNameFor: id => companyNames.get(id || 'quanzhou') || '加盟商名称待完善' }), actor, date: dateLabel() };
}

module.exports = { FILTERS, statusOf, build, filter, paginate, dateLabel, load };
