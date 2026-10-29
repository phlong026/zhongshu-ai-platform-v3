const Access = require('./access');

const copy = require('./clone');
const localTime = value => {
  const time = Date.parse(value || '');
  if (Number.isNaN(time)) return '';
  return new Date(time + 8 * 3600000).toISOString().slice(0, 16).replace('T', ' ');
};

function readKey(actor) {
  return `${actor.companyId}:${actor.personId || actor.staffId}`;
}

function notificationId(customer, release = {}) {
  const order = customer.serviceOrders?.at(-1);
  const completed = customer.businessFlow?.completed || {};
  const completion = completed.feedbackId || completed.at || completed.date || 'completion';
  return ['design-release-ready', customer.designOrganizationId, customer.id, customer.request?.version || 0, order?.version || 0, completion, release.confirmationVersion || ''].join(':');
}

function currentCompletion(customer) {
  const completed = customer.businessFlow?.completed;
  const feedback = customer.businessFlow?.feedbacks?.at(-1);
  if (!completed || feedback?.status === 'pending') return false;
  return !feedback || completed.feedbackId === feedback.id;
}

function paymentNotificationId(customer, order) {
  return ['design-payment-copy', customer.id, order.id, order.paidAt || '', order.status].join(':');
}

const money = require('./money').money;

function paymentCopies(customers, actor, reads, checkout) {
  if (!['finance', 'finance-lead'].includes(actor.role)) return [];
  return customers.flatMap(customer => {
    if (!checkout.is(customer) || !checkout.paid(customer)) return [];
    const order = checkout.order(customer);
    if (!order) return [];
    const storeCopy = actor.orgType === 'store' && actor.companyId === customer.companyId;
    const headquartersCopy = actor.orgType === 'headquarters' && actor.companyId === customer.designOrganizationId;
    if (!storeCopy && !headquartersCopy) return [];
    const id = paymentNotificationId(customer, order);
    const channel = order.channel === 'alipay' ? '支付宝' : '微信支付';
    return [{
      id,
      companyId: actor.companyId,
      userId: actor.userId,
      personId: actor.personId || actor.staffId,
      type: '业务',
      title: '设计费模拟支付已完成',
      description: `${customer.name}项目按 ${order.area}㎡ × ${money(order.rateCents)}/㎡ 计费，已通过${channel}模拟一次性支付 ${money(order.amountCents)}，已同步门店与总部财务。`,
      date: localTime(order.paidAt),
      read: reads.has(id),
      route: 'messages',
      objectId: customer.id,
      derived: true,
      simulated: true,
      payment: { amount: money(order.amountCents), channel: order.channel, channelName: channel, method: order.paymentMethod || (order.channel === 'alipay' ? '余额' : '零钱'), customerName: customer.name, area: order.area, rate: money(order.rateCents), recipient: order.recipient || '众墅之家总部', paidAt: localTime(order.paidAt), number: 'DS' + String(order.createdAt || '').replace(/\D/g, '').slice(0, 14) + customer.id.slice(-4).toUpperCase() }
    }];
  });
}

function list(state, options = {}) {
  const actor = options.actor || Access.context(state);
  if (!actor.active) return [];

  const customers = options.customers || ((wx.getStorageSync('zhongshu-business-v3') || {}).customers || []);
  const releaseFor = options.releaseFor || require('./design-receipts').release;
  const canView = options.canView || ((customer, current) => Access.customer(customer, current));
  const checkout = options.checkout || require('./design-checkout');
  const reads = new Set(state.messageReads?.[readKey(actor)] || []);
  const copies = paymentCopies(customers, actor, reads, checkout);
  if (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters') {
    return copies.sort((a, b) => String(b.date).localeCompare(String(a.date)));
  }

  const releases = customers
    .filter(customer => customer?.businessFlow?.type === 'sales-offline-v1')
    .filter(customer => customer.designOrganizationId === actor.companyId && canView(customer, actor))
    .flatMap(customer => {
      if (!currentCompletion(customer)) return [];
      const release = releaseFor(customer);
      if (!release.ready) return [];
      const dispatched = !!customer.businessFlow.dispatch;
      const id = notificationId(customer, release);
      return [{
        id,
        companyId: actor.companyId,
        userId: actor.userId,
        personId: actor.personId || actor.staffId,
        type: '业务',
        title: dispatched ? '正式图纸已下发' : '正式图纸可下发',
        description: dispatched
          ? `${customer.name}项目本版成果已登记下发，可查看办理记录。`
          : checkout.is(customer)
            ? `${customer.name}项目设计费已一次性模拟支付，可上传并下发正式图纸。`
            : `${customer.name}项目首尾款已由总部财务确认到账，可登记下发正式图纸。`,
        date: localTime(release.confirmedAt || customer.businessFlow.completed.at),
        read: reads.has(id),
        route: dispatched ? 'design-progress' : 'design-dispatch',
        objectId: customer.id,
        derived: true
      }];
    });
  return releases.concat(copies).sort((a, b) => String(b.date).localeCompare(String(a.date)));
}

function markRead(state, id, options = {}) {
  const actor = options.actor || Access.context(state);
  const message = list(state, options).find(row => row.id === id);
  if (!message) throw Error('消息不存在或当前岗位无权查看');
  state.messageReads = state.messageReads || {};
  const key = readKey(actor);
  state.messageReads[key] = [...new Set([...(state.messageReads[key] || []), id])];
  return copy(message);
}

function markAllRead(state, options = {}) {
  const actor = options.actor || Access.context(state);
  const ids = list(state, options).map(message => message.id);
  if (!ids.length) return;
  state.messageReads = state.messageReads || {};
  const key = readKey(actor);
  state.messageReads[key] = [...new Set([...(state.messageReads[key] || []), ...ids])];
}

module.exports = { list, markRead, markAllRead, readKey, notificationId, paymentNotificationId, currentCompletion };
