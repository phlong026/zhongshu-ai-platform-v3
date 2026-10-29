// Store payment registration and headquarters bank receipts are separate facts.
const X = require('./access');
const O = require('./organization');
const PROTOCOL = 'sales-offline-v1';
const copy = require('./clone');
const isSource = source => source?.module === 'intercompany' && source.kind === 'design' && source.protocol === PROTOCOL;

function sourceKey(source) {
  return JSON.stringify(['module', 'kind', 'protocol', 'link', 'fromCompanyId', 'toCompanyId', 'customerId', 'projectId', 'stage', 'serviceVersion', 'requestVersion', 'originPaymentId', 'expected', 'expectedTransaction'].map(key => source?.[key]));
}

function payments(customer, stage, order) {
  const rows = require('./intercompany').stagePayments(customer, stage).filter(row => row.id && row.serviceVersion === order?.version && (!row.status || row.status === '已核实') && Number.isSafeInteger(row.amount) && row.amount > 0);
  return [...new Map(rows.map(row => [row.id, row])).values()];
}

function sources(customer, stage, order) {
  const from = customer.companyId || 'quanzhou';
  return payments(customer, stage, order).map(payment => ({
    module: 'intercompany', protocol: PROTOCOL, kind: 'design',
    link: ['design', from, customer.id, order.version, stage, payment.id].join(':'),
    fromCompanyId: from, toCompanyId: customer.designOrganizationId,
    fromStaffId: payment.custodianId || payment.registrarId,
    originPaymentId: payment.id, customerId: customer.id, projectId: customer.projectId,
    customer: customer.name, name: stage === 'first' ? '设计首款实际到账' : '设计尾款实际到账',
    stage, serviceVersion: order.version, requestVersion: order.requestVersion || customer.request?.version,
    expected: payment.amount, expectedTransaction: payment.transaction
  }));
}

function current(customer) {
  const order = customer.serviceOrders?.at(-1);
  return customer.businessFlow?.type === PROTOCOL && order && order.requestVersion === customer.request?.version && customer.businessFlow.requestVersion === customer.request?.version && require('./sales-flow').feeApproved(customer);
}

function transfers(companyId) {
  return ((wx.getStorageSync(require('./storage-keys').business) || {}).customers || [])
    .filter(customer => customer.designOrganizationId === companyId && (customer.companyId || 'quanzhou') !== companyId && current(customer) && !require('./design-checkout').is(customer))
    .flatMap(customer => ['first', 'tail'].flatMap(stage => sources(customer, stage, customer.serviceOrders.at(-1))));
}

function net(receipt, book) {
  if (receipt.status !== '已核算' || receipt.registrationMode !== 'record-only-v1' || !receipt.financialRegistrarId) return 0;
  const amount = receipt.revisions?.at(-1)?.amount ?? receipt.amount;
  if (!Number.isSafeInteger(amount) || amount <= 0) return 0;
  const refunded = (book.refunds || []).filter(row => row.receiptId === receipt.id && row.status === '已核实').reduce((sum, row) => sum + Math.max(0, Number(row.amount) || 0), 0);
  return Math.max(0, Math.min(amount - refunded, receipt.allocationCap ?? receipt.amount));
}

function matching(receipt, source) {
  return isSource(receipt.source) && receipt.companyId === source.toCompanyId && sourceKey(receipt.source) === sourceKey(source);
}

function incoming(book) {
  const stored = (book.receipts || []).filter(row => isSource(row.source) && row.status !== '待登记').map(copy);
  const pending = [];
  for (const source of transfers(book.companyId)) {
    const history = stored.filter(row => row.source.link === source.link);
    const received = history.filter(row => matching(row, source)).reduce((sum, row) => sum + net(row, book), 0);
    const remaining = source.expected - Math.min(source.expected, received);
    if (remaining > 0) pending.push({
      id: 'incoming:' + source.link + ':' + (history.length + 1), companyId: book.companyId,
      projectId: source.projectId, customer: source.customer, name: source.name,
      amount: remaining, expectedAmount: source.expected, expectedTransaction: source.expectedTransaction,
      status: '待登记', date: '', file: null, transaction: '', revisions: [], version: 1, source
    });
  }
  return stored.concat(pending);
}

function register(book, id, form) {
  const actor = X.context();
  X.assert(actor.active && actor.orgType === 'headquarters' && actor.companyId === book.companyId && O.has(actor.role, 'finance'), '请由收款总部的在岗财务确认到账');
  const row = book.receipts.find(row => row.id === id);
  X.assert(row && isSource(row.source) && row.source.toCompanyId === actor.companyId && row.status === '待登记', '原到账事项已办理或来源已变化，请刷新');
  let expected;
  try { expected = JSON.parse(form.expectedSource); } catch (_) { X.assert(false, '到账来源已变化，请刷新核对'); }
  const fresh = incoming(book).find(item => item.id === id && item.status === '待登记');
  X.assert(fresh && sourceKey(expected) === sourceKey(fresh.source) && sourceKey(row.source) === sourceKey(fresh.source) && String(form.expectedAmount) === String(fresh.amount), '原委托版本或剩余到账金额已变化，请刷新');
  const amount = Math.round(Number(form.amount) * 100);
  X.assert(/^\d+(\.\d{1,2})?$/.test(String(form.amount)) && Number.isSafeInteger(amount) && amount > 0 && amount <= fresh.amount, '实际到账金额须大于0、最多两位小数且不能超过本笔剩余金额');
  const date = form.date;
  X.assert(/^\d{4}-\d{2}-\d{2}$/.test(date || '') && !Number.isNaN(Date.parse(date)) && new Date(date).toISOString().slice(0, 10) === date && date <= require('./account').localDate(), '请填写有效的实际到账日期，不能晚于今天');
  X.assert(require('./sales-flow').file(form.file) && form.checked === true && String(form.transaction || '').trim(), '请补齐银行到账凭据、流水并确认已核对实际到账');
  const transaction = form.transaction.trim();
  X.assert(!book.receipts.some(item => item.id !== id && (item.bankTransaction || item.transaction) === transaction), '本组织银行到账流水已登记，不能重复确认');
  Object.assign(row, {
    amount, allocationCap: amount, unallocatedAmount: 0, date, file: copy(form.file), transaction,
    bankDate: date, bankFile: copy(form.file), bankTransaction: transaction,
    status: '已核算', registrationMode: 'record-only-v1', registeredAt: new Date().toISOString(),
    registrarId: actor.staffId, registrarPersonId: actor.personId, registrar: actor.name,
    financialRegistrarId: actor.staffId, financialRegistrarPersonId: actor.personId, custodianId: actor.staffId,
    note: String(form.note || '').trim(), version: (row.version || 1) + 1
  });
  return id;
}

function condition(customer, stage, book = X.rawBook(customer.designOrganizationId)) {
  const order = customer.serviceOrders?.at(-1);
  const expected = Math.round(Number(order?.[stage] || 0) * 100);
  const paid = payments(customer, stage, order).reduce((sum, row) => sum + row.amount, 0);
  let received = 0;
  const confirmed = [];
  if (order && book?.companyId === customer.designOrganizationId) for (const source of sources(customer, stage, order)) {
    const rows = (book.receipts || []).filter(row => matching(row, source) && net(row, book) > 0);
    received += Math.min(source.expected, rows.reduce((sum, row) => sum + net(row, book), 0));
    confirmed.push(...rows);
  }
  const ready = !!(current(customer) && expected > 0 && paid >= expected && received >= expected);
  const text = ready ? '总部财务已确认足额到账' : paid < expected ? '待加盟商登记足额付款' : received ? '总部已确认部分到账，待补齐差额' : '待总部财务确认到账';
  return { ready, expected, paid, received, registrationOnly: false, text, confirmedAt: confirmed.map(row => row.registeredAt || '').sort().at(-1) || '' };
}

function release(customer, book = X.rawBook(customer.designOrganizationId)) {
  const Checkout = require('./design-checkout');
  if (Checkout.is(customer)) {
    const payment = Checkout.order(customer), ready = Checkout.paid(customer);
    return { ready, confirmationVersion: payment ? payment.id + ':' + payment.status : 'missing-order', confirmedAt: payment?.paidAt || '', text: ready ? '设计费已一次性模拟支付' : '待业务完成设计费模拟支付' };
  }
  const first = condition(customer, 'first', book);
  const tail = condition(customer, 'tail', book);
  const order = customer.serviceOrders?.at(-1);
  const expected = order ? ['first', 'tail'].flatMap(stage => sources(customer, stage, order)) : [];
  // Use source record versions, without amounts or bank details, for notification deduplication and the release audit.
  const evidence = (book?.receipts || []).filter(row => row.status !== '待登记' && expected.some(source => matching(row, source))).map(row => [
    row.id, row.version || 1, (row.revisions || []).length,
    (book.refunds || []).filter(refund => refund.receiptId === row.id).map((refund, index) => [refund.id || index, refund.version || 1, refund.status]).sort((a, b) => String(a[0]).localeCompare(String(b[0])))
  ]).sort((a, b) => a[0].localeCompare(b[0]));
  return { ready: first.ready && tail.ready, first, tail, confirmationVersion: JSON.stringify(evidence), confirmedAt: [first.confirmedAt, tail.confirmedAt].sort().at(-1) || '', text: first.ready && tail.ready ? '总部财务已确认首尾款足额到账' : '待总部财务确认本版首尾款足额到账' };
}

module.exports = { isSource, transfers, incoming, register, condition, release, net };
