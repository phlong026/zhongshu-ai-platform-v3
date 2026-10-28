const Ledger = require('./finance-ledger');

const copy = value => JSON.parse(JSON.stringify(value));
const businessCustomers = () => ((wx.getStorageSync('zhongshu-business-v3') || {}).customers || []);
const paidDate = value => {
  const time = Date.parse(value || '');
  return Number.isNaN(time) ? '' : new Date(time + 8 * 3600000).toISOString().slice(0, 10);
};

function checkoutRecords(state, options = {}) {
  const checkout = options.checkout || require('./design-checkout');
  const customers = options.customers || businessCustomers();
  const rows = customers.filter(customer => checkout.is(customer));
  if (!rows.length) return state;

  const customerIds = new Set(rows.map(customer => customer.id));
  const oldDesignRow = row => {
    const source = row.source || {};
    const customerId = source.id || source.customerId;
    return customerIds.has(customerId)
      && (source.module === 'design-checkout'
        || source.kind === 'design'
        || source.module === 'business' && ['first', 'tail'].includes(source.stage));
  };
  state.payables = (state.payables || []).filter(row => !oldDesignRow(row));
  state.payments = (state.payments || []).filter(row => !oldDesignRow(row));
  state.receipts = (state.receipts || []).filter(row => !oldDesignRow(row));

  for (const customer of rows) {
    if (!checkout.paid(customer)) continue;
    const order = checkout.order(customer);
    if (!order) continue;
    const date = paidDate(order.paidAt);
    const channel = order.channel === 'alipay' ? '支付宝' : '微信支付';
    const common = {
      projectId: order.projectId || customer.projectId,
      customer: customer.name,
      name: '一次性设计服务费（体验模拟）',
      amount: order.amountCents,
      date,
      simulated: true,
      readOnly: true,
      affectsFinance: false,
      registrationMode: 'simulated-online-v1',
      source: {
        module: 'design-checkout',
        kind: 'full-payment-copy',
        orderId: order.id,
        id: customer.id,
        customerId: customer.id,
        requestVersion: order.requestVersion,
        serviceVersion: order.serviceVersion,
        fromCompanyId: customer.companyId,
        toCompanyId: customer.designOrganizationId,
        channel: order.channel,
        simulated: true
      }
    };
    if (state.companyId === customer.companyId) {
      state.payments.push({
        ...copy(common),
        id: `design-demo-payment:${order.id}:${state.companyId}`,
        companyId: state.companyId,
        status: '体验模拟',
        transaction: `体验模拟 · ${channel}`
      });
    }
    if (state.companyId === customer.designOrganizationId) {
      state.receipts.push({
        ...copy(common),
        id: `design-demo-receipt:${order.id}:${state.companyId}`,
        companyId: state.companyId,
        status: '体验模拟',
        bankDate: date,
        transaction: `体验模拟 · ${channel}`
      });
    }
  }
  return state;
}

function merge(base, options) {
  const state = Ledger.merge(base);
  require('./operations').init(state); // 合并即完成一次性迁移（历史待批分期自动生效）
  return checkoutRecords(state, options);
}

module.exports = { ...Ledger, merge, checkoutRecords };
