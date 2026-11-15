const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const ROOT = path.resolve(__dirname, '..');
const financeFile = path.join(ROOT, 'miniprogram/pages/admin/finance-desk/design-finance.js');

function platformFinance() {
  const file = path.join(ROOT, 'miniprogram/utils/platform-finance.js');
  delete require.cache[require.resolve(file)];
  return require(file);
}

function setPath(target, key, value) {
  const parts = key.split('.');
  let cursor = target;
  for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]] = cursor[parts[i]] || {};
  cursor[parts.at(-1)] = value;
}

function financeRows() {
  return {
    payables: [
      { id: '9007199254740991', name: '主工程材料款', status: '进行中', totalCents: 21000 },
      { id: '9007199254740992', name: '另一应付', status: '进行中', totalCents: 9000 }
    ],
    payments: [
      { id: 'p-1', payableId: '9007199254740991', status: '已核实', amountCents: 10000, pendingLinkCents: 2000 },
      { id: 'p-void', payableId: '9007199254740991', status: '已作废', amountCents: 5000, pendingLinkCents: 0 },
      { id: 'p-2', payableId: '9007199254740992', status: '已核实', amountCents: 5000 },
      { id: 'p-other', payableId: '9007199254740993', status: '已核实', amountCents: 8800 }
    ],
    refunds: [
      { id: 'f-1', paymentId: 'p-1', refundDirection: 'expense', status: '已核实', amountCents: 3000 },
      { id: 'f-void', paymentId: 'p-1', refundDirection: 'expense', status: '已作废', amountCents: 1000 },
      { id: 'f-client', paymentId: 'p-1', refundDirection: 'client', status: '已核实', amountCents: 2000 },
      { id: 'f-2', paymentId: 'p-2', refundDirection: 'expense', status: '已核实', amountCents: 1000 },
      { id: 'f-other', paymentId: 'p-other', refundDirection: 'expense', status: '已核实', amountCents: 800 }
    ]
  };
}

function designFinanceHarness(api) {
  let generation = 1;
  const module = { exports: {} };
  const realRequire = createRequire(financeFile);
  const http = {
    requestGuard() { const current = generation; return () => current === generation; },
    sessionGuard() { const current = generation; return () => current === generation; }
  };
  vm.runInNewContext(fs.readFileSync(financeFile, 'utf8'), {
    module,
    require(request) { return request.endsWith('/api/http') ? http : realRequire(request); },
    wx: { showToast() {} }
  }, { filename: financeFile });
  const page = {
    data: { flowTab: 'payables', keyword: '', rows: [] },
    setData(patch) { for (const [key, value] of Object.entries(patch)) setPath(this.data, key, value); },
    loadPlatformFinance() {}
  };
  module.exports(page, api);
  return { page, invalidate() { generation += 1; } };
}

function financeDeskHarness(data) {
  const file = path.join(ROOT, 'miniprogram/pages/admin/finance-desk/index.js');
  const source = fs.readFileSync(file, 'utf8');
  let page;
  const api = {
    receipts: async () => [],
    payments: async () => data.payments,
    refunds: async () => data.refunds,
    bankFlows: async () => [],
    payables: async () => data.payables,
    reconciliation: async month => ({ month })
  };
  const http = {
    token: () => 'TEST ONLY', currentPostId: () => 'post-1', sessionGeneration: () => 1,
    requestGuard: () => () => true, get: async () => ({ nickname: 'TEST ONLY 财务' })
  };
  vm.runInNewContext(source, {
    Page(definition) { page = definition; },
    require(request) {
      if (request === '../../../utils/finance-desk') return { url: () => '' };
      if (request === '../../../utils/access') return { context: () => ({}) };
      if (request === '../../../utils/page-navigation') return {};
      if (request === '../../../api/auth') return { currentApiPost: () => ({ roleCode: 'finance', orgType: 'store', orgName: 'TEST ONLY 门店' }) };
      if (request === '../../../api/finance') return api;
      if (request === '../../../api/http') return http;
      if (request === '../../../utils/platform-finance') return platformFinance();
      if (request.endsWith('/platform-todos-page')) return { init() {}, refresh() {} };
      if (request.endsWith('/franchise/performance')) return { operating: {} };
      if (request.endsWith('/fr-web-link')) return {};
      if (request.endsWith('/view-container')) return { routeValue: value => value || '' };
      throw new Error('unexpected require: ' + request);
    },
    console, Date, Promise
  }, { filename: file });
  page.data = { ...page.data, view: 'desk' };
  page.setData = patch => { for (const [key, value] of Object.entries(patch)) setPath(page.data, key, value); };
  return page;
}

async function flush() {
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
}

test('payable progress derives exact net paid without relying on paidCents from payable rows', () => {
  const PF = platformFinance();
  const data = financeRows();
  const rows = PF.payableProgress(data.payables, data.payments, data.refunds);
  assert.deepEqual(rows.map(row => ({ id: row.id, paid: row.paidCents, remaining: row.remainingCents })), [
    { id: '9007199254740991', paid: 5000, remaining: 16000 },
    { id: '9007199254740992', paid: 4000, remaining: 5000 }
  ]);
});

test('no payment remains payable, full linked payment closes it, and unrelated facts never cross payable ids', () => {
  const PF = platformFinance();
  const payable = [{ id: '9007199254740991', status: '进行中', totalCents: 21000 }];
  assert.deepEqual(PF.payableProgress(payable, [], [])[0], {
    ...payable[0], paidCents: 0, remainingCents: 21000, canPay: true
  });
  const full = PF.payableProgress(payable, [
    { id: 'right', payableId: payable[0].id, status: '已核实', amountCents: 21000 },
    { id: 'other', payableId: '9007199254740992', status: '已核实', amountCents: 999999 }
  ], [{ id: 'other-refund', paymentId: 'other', refundDirection: 'expense', status: '已核实', amountCents: 1 }])[0];
  assert.equal(full.paidCents, 21000);
  assert.equal(full.remainingCents, 0);
  assert.equal(full.canPay, false);
});

test('malformed complete API data fails closed instead of inventing zero paid progress', () => {
  const PF = platformFinance();
  assert.throws(() => PF.payableProgress([], null, []), /付款数据/);
  assert.throws(() => PF.payableProgress([{ id: '1', totalCents: 100 }], [
    { id: 'p', payableId: '1', status: '已核实', amountCents: 100, pendingLinkCents: 101 }
  ], []), /待关联金额/);
});

test('payables source rows load payments and refunds once and show registration for a new payable', async () => {
  const data = financeRows();
  const calls = [];
  const api = {
    payables: async () => { calls.push('payables'); return data.payables; },
    payments: async () => { calls.push('payments'); return data.payments; },
    refunds: async () => { calls.push('refunds'); return data.refunds; }
  };
  const { page } = designFinanceHarness(api);
  await page.loadFinanceSourceRows();
  assert.deepEqual(calls.sort(), ['payables', 'payments', 'refunds']);
  assert.equal(page.data.rows[0].canPay, true);
  assert.equal(page.data.rows[0].amountText, '¥160.00');
  assert.match(page.data.rows[0].detail, /已付 ¥50\.00/);
});

test('source rows preserve their error state and late-session guard when supporting finance facts fail or arrive late', async () => {
  const data = financeRows();
  const failed = designFinanceHarness({
    payables: async () => data.payables,
    payments: async () => { throw new Error('TEST ONLY 付款列表失败'); },
    refunds: async () => data.refunds
  });
  await failed.page.loadFinanceSourceRows();
  assert.equal(failed.page.data.platformNotice, 'TEST ONLY 付款列表失败');
  let resolve;
  const late = designFinanceHarness({
    payables: () => new Promise(done => { resolve = done; }),
    payments: async () => [],
    refunds: async () => []
  });
  const loading = late.page.loadFinanceSourceRows();
  late.invalidate();
  resolve(data.payables);
  await loading;
  assert.deepEqual(late.page.data.rows, []);
});

test('full finance payable picker uses the same net progress and excludes fully paid options', async () => {
  const data = financeRows();
  const page = financeDeskHarness(data);
  page.loadPlatformFinance();
  await flush();
  assert.deepEqual(page.data.platformRegister.payables.map(row => ({
    id: row.id, paid: row.paidCents, remaining: row.remainingCents
  })), [
    { id: '9007199254740991', paid: 5000, remaining: 16000 },
    { id: '9007199254740992', paid: 4000, remaining: 5000 }
  ]);
  assert.match(page.data.platformRegister.payables[0].label, /未付 ¥160\.00/);

  const completed = financeDeskHarness({
    payables: [{ id: 'full', name: '已结清原单', status: '进行中', totalCents: 21000 }],
    payments: [{ id: 'payment-full', payableId: 'full', status: '已核实', amountCents: 21000 }],
    refunds: []
  });
  completed.loadPlatformFinance();
  await flush();
  assert.deepEqual(completed.data.platformRegister.payables, []);
});

test('full finance load exposes malformed progress as degraded instead of a false unpaid picker row', async () => {
  const page = financeDeskHarness({
    payables: [{ id: 'bad', status: '进行中', totalCents: 21000 }],
    payments: [{ id: 'bad-payment', payableId: 'bad', status: '已核实', amountCents: 100, pendingLinkCents: 101 }],
    refunds: []
  });
  page.loadPlatformFinance();
  await flush();
  assert.equal(page.data.platformFinance.status, 'degraded');
  assert.match(page.data.platformFinance.message, /待关联金额/);
  assert.equal(page.data.platformRegister, null);
});
