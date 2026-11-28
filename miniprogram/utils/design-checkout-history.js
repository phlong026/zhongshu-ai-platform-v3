// Read-only compatibility for historical local demo records.
// No pricing, order creation, payment or storage mutation is exposed here.
const X = require('./access');
const MODE = 'area-full-demo-v1';

const is = customer => customer?.businessFlow?.paymentMode === MODE;
const order = customer => customer?.businessFlow?.paymentOrder || null;
const pricing = () => X.rawBook('headquarters')?.designAreaPrices?.at(-1) || null;

function authorized(customer) {
  if (!is(customer)) return false;
  const payment = order(customer);
  const price = X.rawBook('headquarters')?.designAreaPrices?.find(p => p.version === payment?.pricingVersion);
  const basis = customer.request?.basis;
  const service = customer.serviceOrders?.at(-1);
  const serviceMatches = !service || service.version === payment?.serviceVersion
    && service.requestVersion === payment.requestVersion
    && Math.round(service.total * 100) === payment.amountCents
    && service.first === 0 && service.tail === 0;
  return !!(payment?.simulated && price?.actorId
    && price.companyId === customer.designOrganizationId
    && payment.companyId === customer.companyId
    && payment.customerId === customer.id
    && payment.projectId === customer.projectId
    && payment.requestVersion === customer.request?.version
    && payment.requestVersion === customer.businessFlow.requestVersion
    && payment.serviceVersion === customer.businessFlow.authorization?.serviceVersion
    && serviceMatches
    && payment.budgetVersion === basis?.version
    && payment.area === basis?.area
    && Number.isFinite(payment.area) && payment.area > 0
    && payment.rateCents === price.rateCents
    && payment.scope === price.scope
    && payment.recipient === price.recipient
    && Number.isSafeInteger(payment.amountCents) && payment.amountCents > 0
    && payment.amountCents === Math.round(payment.area * price.rateCents));
}

function paid(customer) {
  const p = order(customer);
  return authorized(customer) && p.status === 'paid'
    && ['wechat', 'alipay'].includes(p.channel)
    && !!p.paidAt && !!p.paidById
    && p.transactionId === p.id + ':demo:' + p.channel;
}

module.exports = { MODE, is, order, pricing, authorized, paid };
