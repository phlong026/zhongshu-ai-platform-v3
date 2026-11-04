'use strict';
// Read-only reproduction: execute the real page action with synthetic IDs and mocked APIs.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const sourcePath = '/Users/honor.pei/Documents/企业FDE项目/zhongshu-ai-platform-v3/miniprogram/pages/admin/finance-desk/platform-actions.js';
const source = fs.readFileSync(sourcePath, 'utf8');
const selectedId = '1980000000000000001';
const cases = [];
async function reproduce(kind) {
  let captured;
  const capture = method => (...args) => {
    captured = { method, args };
    return Promise.resolve({});
  };
  const financeApi = {
    idemKey: () => 'synthetic-stable-key',
    registerPayment: capture('registerPayment'),
    registerRefund: capture('registerRefund'),
    registerPayable: capture('registerPayable'),
    confirmBank: capture('confirmBank')
  };
  const form = {
    amount: '100', payableId: selectedId, receiptId: selectedId,
    recipient: 'Synthetic supplier', projectId: selectedId, reason: 'Synthetic refund'
  };
  const page = {
    data: {
      registerType: kind, registerForm: form,
      platformRegister: { pendingReceipts: [{ id: 'synthetic-receipt' }] },
      ['bankPick' + selectedId]: 0
    },
    setData(value) { Object.assign(this.data, value); },
    loadPlatformFinance() {}
  };
  const sandbox = { module: { exports: {} }, wx: { showToast() {} } };
  vm.runInNewContext(source, sandbox, { filename: sourcePath });
  sandbox.module.exports(page, financeApi, {
    toCents: () => 10000, errorMessage: error => error.message
  });
  if (kind === 'bank') {
    page.confirmBankSubmitInner({ currentTarget: { dataset: { flowId: selectedId } } });
  } else {
    page.submitRegister();
  }
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(captured, 'Real action must reach the mocked API');
  const field = kind === 'payment' ? 'payableId' : kind === 'refund' ? 'receiptId' : 'projectId';
  const sentId = kind === 'bank' ? captured.args[0] : captured.args[0][field];
  const actualWireId = String(sentId);
  const precisionLost = actualWireId !== selectedId;
  assert.equal(precisionLost, true, 'Expected current source to reproduce precision loss');
  cases.push({ kind, field: kind === 'bank' ? 'bankFlowId' : field,
    inputId: selectedId, wireId: actualWireId, valueType: typeof sentId, precisionLost });
}
(async () => {
  for (const kind of ['payment', 'refund', 'payable', 'bank']) await reproduce(kind);
  process.stdout.write(JSON.stringify({
    date: '2026-11-03', sourcePath, expected: 'Identifiers preserve their decimal digits',
    mode: 'pure VM with synthetic data; no HTTP or database',
    result: 'reproduced', cases
  }, null, 2) + '\n');
})().catch(error => {
  process.stderr.write(String(error.stack || error) + '\n');
  process.exitCode = 1;
});
