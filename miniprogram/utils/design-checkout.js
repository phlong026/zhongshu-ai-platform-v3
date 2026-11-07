// Experience-only checkout. Order amounts come from a versioned HQ price, never form totals.
const X = require('./access');
const MODE = 'area-full-demo-v1';
const is = customer => customer?.businessFlow?.paymentMode === MODE;
const order = customer => customer?.businessFlow?.paymentOrder || null;
const pricing = () => X.rawBook('headquarters')?.designAreaPrices?.at(-1) || null;
const copy = require('./clone');

function assertDemo() {
  X.assert(X.context().demo && !require('./sample-files').release(), '请使用体验账号进行模拟支付，正式版不支持此操作');
}

function savePricing(form) {
  const actor = X.context();
  X.assert(actor.active && actor.orgType === 'headquarters' && actor.role === 'hq-design-admin', '请由总部设计人员设置设计费计价');
  const rate = String(form.rate || '');
  X.assert(/^\d+(\.\d{1,2})?$/.test(rate) && Number(rate) > 0 && Number(rate) <= 100000, '请填写有效设计费单价，最多两位小数');
  X.assert(String(form.scope || '').trim() && String(form.recipient || '').trim(), '请填写计价交付范围和总部收款方');
  const root = copy(wx.getStorageSync(require('./storage-keys').management));
  const book = root?.companyId === actor.companyId ? root : root?.books?.[actor.companyId];
  X.assert(book, '总部组织账簿尚未初始化');
  const rows = book.designAreaPrices || (book.designAreaPrices = []);
  const row = { version: rows.length + 1, rateCents: Math.round(Number(rate) * 100), scope: form.scope.trim(), recipient: form.recipient.trim(), active: form.active !== false, companyId: actor.companyId, actorId: actor.staffId, personId: actor.personId, at: new Date().toISOString() };
  rows.push(row);
  wx.setStorageSync(require('./storage-keys').management, root);
  return copy(row);
}

function quote(customer) {
  const price = pricing();
  X.assert(price?.active, '请先由总部设计人员在“我的”设置并启用设计费计价');
  const basis = require('./design-coordination').basis(customer);
  X.assert(basis, '请先完成本项目参考预算');
  const amountCents = Math.round(basis.area * price.rateCents);
  X.assert(Number.isFinite(basis.area) && Number.isSafeInteger(amountCents) && amountCents > 0 && amountCents <= 10000000000, '面积或设计费金额超出有效范围');
  return { area: basis.area, rateCents: price.rateCents, amountCents, pricingVersion: price.version, scope: price.scope, recipient: price.recipient };
}

function create(customer, input, requestVersion, serviceVersion, at) {
  assertDemo();
  const q = quote(customer);
  X.assert(input.__pricingVersion === q.pricingVersion, '设计费计价已更新，请刷新后核对本版金额');
  X.assert(String(input.scope || '').trim() === q.scope, '交付范围须与总部计价规则一致，请重新核对');
  return { ...q, id: ['design-order', customer.companyId, customer.id, serviceVersion].join(':'), companyId: customer.companyId, customerId: customer.id, projectId: customer.projectId, requestVersion, serviceVersion, budgetVersion: customer.budgets.at(-1).version, status: 'pending', createdAt: at, simulated: true };
}

function authorized(customer) {
  if (!is(customer)) return false;
  const payment = order(customer);
  const price = X.rawBook('headquarters')?.designAreaPrices?.find(p => p.version === payment?.pricingVersion);
  const basis = customer.request?.basis;
  const service = customer.serviceOrders?.at(-1);
  const serviceMatches = !service || service.version === payment?.serviceVersion && service.requestVersion === payment.requestVersion && Math.round(service.total * 100) === payment.amountCents && service.first === 0 && service.tail === 0;
  return !!(payment?.simulated && price?.actorId && price.companyId === customer.designOrganizationId && payment.companyId === customer.companyId && payment.customerId === customer.id && payment.projectId === customer.projectId && payment.requestVersion === customer.request?.version && payment.requestVersion === customer.businessFlow.requestVersion && payment.serviceVersion === customer.businessFlow.authorization?.serviceVersion && serviceMatches && payment.budgetVersion === basis?.version && payment.area === basis?.area && Number.isFinite(payment.area) && payment.area > 0 && payment.rateCents === price.rateCents && payment.scope === price.scope && payment.recipient === price.recipient && Number.isSafeInteger(payment.amountCents) && payment.amountCents > 0 && payment.amountCents === Math.round(payment.area * price.rateCents));
}

function paid(customer) {
  const p = order(customer);
  return authorized(customer) && p.status === 'paid' && ['wechat', 'alipay'].includes(p.channel) && !!p.paidAt && !!p.paidById && p.transactionId === p.id + ':demo:' + p.channel;
}

function pay(customer, input, at) {
  assertDemo();
  const p = order(customer);
  X.assert(authorized(customer) && p?.id === input.orderId, '支付订单或委托版本已变化，请刷新');
  X.assert(!paid(customer), '本订单已完成模拟支付，请查看原记录');
  X.assert(['wechat', 'alipay'].includes(input.channel), '请选择微信或支付宝模拟支付');
  X.assert(input.outcome === 'success', input.outcome === 'cancelled' ? '已取消模拟支付，可沿原订单重试' : '模拟支付失败，请沿原订单重试');
  const methods = input.channel === 'alipay' ? ['余额', '余额宝'] : ['零钱', '零钱通'];
  const paymentMethod = input.paymentMethod || methods[0];
  X.assert(methods.includes(paymentMethod), '请重新选择模拟付款方式');
  return { ...copy(p), status: 'paid', paymentMethod, channel: input.channel, paidAt: at, paidById: X.context().staffId, paidByPersonId: X.context().personId, transactionId: p.id + ':demo:' + input.channel };
}

module.exports = { MODE, is, order, pricing, savePricing, quote, create, authorized, paid, pay };
