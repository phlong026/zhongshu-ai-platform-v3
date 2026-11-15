const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const filename = path.resolve(__dirname, '../miniprogram/pages/admin/finance-desk/design-finance.js');
function harness(api) {
  let generation = 0;
  const module = { exports: {} }, realRequire = createRequire(filename);
  const http = { sessionGuard: () => { const g = generation; return () => g === generation; }, requestGuard: () => http.sessionGuard() };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: n => n.endsWith('/api/http') ? http : realRequire(n), wx: { showToast() {} } });
  const page = { data: {}, setData(patch) { Object.assign(this.data, patch); }, loadPlatformFinance() {} };
  module.exports(page, api);
  return { page, invalidate() { generation++; } };
}
test('refresh button event reloads page1; loading more uses server page2 without truncating totals', async () => {
  const pages = [], h = harness({ designFees: async pageNo => { pages.push(pageNo); return { list: [], total: 41 }; } });
  await h.page.loadDesignFees({ currentTarget: {} }); await h.page.loadDesignFees(true);
  assert.deepEqual(pages, [1, 2]); assert.equal(h.page.data.designFeeTotal, 41);
});
test('late design fees from old post do not fill current page', async () => {
  let resolve; const h = harness({ designFees: () => new Promise(r => { resolve = r; }) });
  const loading = h.page.loadDesignFees(); h.invalidate(); resolve({ list: [], total: 77 }); await loading;
  assert.equal(h.page.data.designFeeTotal, undefined);
});
test('failed partial receipt keeps actual input and retries with the same business key', async () => {
  const bodies = [], keys = [], h = harness({ idemKey: () => 'stable-test-key', registerReceipt: async (body, key) => { bodies.push(body); keys.push(key); throw Error('TEST ONLY network unavailable'); } });
  const row = { key: 'c:1:FULL', commissionId: '9007199254740999', stage: 'FULL', payments: [{ paymentId: '9007199254740998', receivableCents: 1000, canReceive: true }] };
  h.page.data.designFeeRows = [row];
  h.page.openDesignReceipt({ currentTarget: { dataset: { key: row.key, paymentId: row.payments[0].paymentId } } });
  h.page.data.designReceiptForm = { amount: '1.23', receivedDate: '2020-01-01', transactionNo: 'TEST ONLY' };
  await h.page.submitDesignReceipt(); await h.page.submitDesignReceipt();
  assert.equal(h.page.data.designReceiptForm.amount, '1.23'); assert.equal(bodies[0].amountCents, 123);
  assert.equal(bodies[0].sourceType, 'design'); assert.equal(bodies[0].originPaymentId, '9007199254740998');
  assert.deepEqual(keys, ['stable-test-key', 'stable-test-key']);
});
