const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const Allocation = require('../miniprogram/pages/admin/finance-desk/allocation-candidates');

const customerId = '9007199254740995';
const contractId = '9007199254740993';
const projectId = '9007199254741001';
const projectBookId = '9007199254741005';
const preBookId = '9007199254741007';
const commissionId = '9007199254741011';
const stage = {
  id: '9007199254741021', contract_id: contractId, customer_id: customerId,
  customer_name: '张先生', amount_cents: 100000, collected_cents: 20000
};
const targetProject = {
  scope: 'project:' + projectId, project_id: projectId, sale_contract_id: contractId,
  customer_id: customerId, commission_id: commissionId, book_id: projectBookId, name: '张先生一号工程'
};
const preBook = {
  scope: 'book:' + preBookId, book_id: preBookId, customer_id: customerId,
  commission_id: commissionId, name: '前期设计定金账'
};
const otherProject = {
  scope: 'project:9007199254741002', project_id: '9007199254741002',
  sale_contract_id: '9007199254740994', customer_id: customerId,
  commission_id: '9007199254741012', book_id: '9007199254741008', name: '张先生二号工程'
};
const receipt = (id, extra) => ({
  id, status: '已登记', sourceType: 'manual', sourceRef: null,
  amountCents: 20000, customerId, projectId: null, bookId: null,
  name: '前期定金', receivedDate: '2026-11-28', ...(extra || {})
});
const run = (receipts, refunds = [], books = [targetProject, preBook, otherProject], targetStage = stage) =>
  Allocation.candidates({ stageId: targetStage.id, stages: [targetStage], receipts, refunds, books });

test('only same-customer and same-project manual receipts or valid unassigned prepayments are offered', () => {
  const rows = [
    receipt('same-project', { projectId }),
    receipt('linked-book', { bookId: projectBookId }),
    receipt('preliminary-book', { bookId: preBookId }),
    receipt('same-customer-unlinked', { customerId }),
    receipt('unassigned', { customerId: null }),
    receipt('wrong-customer', { customerId: 'other' }),
    receipt('wrong-project', { projectId: otherProject.project_id }),
    receipt('wrong-book', { bookId: otherProject.book_id }),
    receipt('already-allocated', { sourceType: 'business', sourceRef: 'sale-allocation:1' }),
    receipt('has-source-ref', { sourceRef: 'legacy-source' }),
    receipt('not-registered', { status: '待登记' }),
    receipt('not-manual', { sourceType: 'design' })
  ];
  const result = run(rows);
  assert.equal(result.blocked, false);
  assert.deepEqual(result.items.map(row => row.id).sort(), [
    'linked-book', 'preliminary-book', 'same-customer-unlinked', 'same-project', 'unassigned'
  ]);
  assert.equal(result.items.find(row => row.id === 'unassigned').originLabel, '未关联客户/工程（请核对凭证）');
  assert.equal(result.items.find(row => row.id === 'preliminary-book').originLabel, '前期账本 · 前期设计定金账');
});

test('another contract allocation excludes only the affected book, not a same-contract allocation', () => {
  const cleanBook = { ...preBook, scope: 'book:9007199254741098', book_id: '9007199254741098', name: '其他未占用账本' };
  const old = (bookId, allocatedContract) => receipt('9007199254741211', {
    bookId, sourceType: 'business', sourceRef: 'sale-allocation:' + allocatedContract + ':9007199254741311:9007199254741211'
  });
  const pending = [
    receipt('pending-same-book', { bookId: preBookId }),
    receipt('pending-clean-book', { bookId: cleanBook.book_id }),
    receipt('pending-no-book')
  ];
  const books = [targetProject, preBook, cleanBook];
  const other = run([old(preBookId, otherProject.sale_contract_id), ...pending], [], books);
  assert.deepEqual(other.items.map(row => row.id).sort(), ['pending-clean-book', 'pending-no-book']);
  const same = run([old(preBookId, contractId), ...pending], [], books);
  assert.deepEqual(same.items.map(row => row.id).sort(), ['pending-clean-book', 'pending-no-book', 'pending-same-book']);
});

test('verified refunds reduce the available amount; fully refunded and over-stage amounts are excluded', () => {
  const rows = [
    receipt('partial', { amountCents: 50000 }),
    receipt('full', { amountCents: 30000 }),
    receipt('over-stage', { amountCents: 100000 })
  ];
  const refunds = [
    { receiptId: 'partial', amountCents: 20000, status: '已核实' },
    { receiptId: 'full', amountCents: 30000, status: '已核实' },
    { receiptId: 'over-stage', amountCents: 10000, status: '已核实' },
    { receiptId: 'partial', amountCents: 10000, status: '已作废' }
  ];
  const result = run(rows, refunds);
  assert.deepEqual(result.items.map(row => row.id), ['partial']);
  assert.equal(result.items[0].availableAmountCents, 30000);
  assert.equal(result.items[0].refundedAmountCents, 20000);
});

test('unlinked commission books cannot be offered when their commission belongs to another project', () => {
  const wrongCommissionBook = {
    scope: 'book:9007199254741031', book_id: '9007199254741031',
    customer_id: customerId, commission_id: otherProject.commission_id, name: '其他委托账'
  };
  const result = run([receipt('wrong-commission', { bookId: wrongCommissionBook.book_id })],
    [], [targetProject, preBook, otherProject, wrongCommissionBook]);
  assert.equal(result.items.length, 0);
});

test('a contract with multiple projects is blocked instead of guessing which project owns the deposit', () => {
  const second = { ...targetProject, scope: 'project:9007199254741032', project_id: '9007199254741032' };
  const result = run([receipt('candidate')], [], [targetProject, second, preBook]);
  assert.equal(result.blocked, true);
  assert.match(result.hint, /多个工程/);
  assert.equal(result.items.length, 0);
});

test('a fully collected stage is not eligible for allocation', () => {
  const settled = { ...stage, amount_cents: 100000, collected_cents: 100000 };
  const result = run([receipt('candidate')], [], [targetProject], settled);
  assert.equal(result.blocked, false);
  assert.equal(result.items.length, 0);
  assert.match(result.hint, /已收齐/);
});

test('unlinked prepayment is accepted before engineering is filed, but a project-bound receipt is not', () => {
  const noProjectStage = { ...stage, contract_id: '9007199254741099' };
  const noProjectBook = { scope: 'book:9007199254741101', book_id: '9007199254741101',
    customer_id: customerId, commission_id: null, name: '客户前期账' };
  const result = run([
    receipt('unlinked-book', { bookId: noProjectBook.book_id }),
    receipt('project-bound', { projectId })
  ], [], [noProjectBook, targetProject], noProjectStage);
  assert.deepEqual(result.items.map(row => row.id), ['unlinked-book']);
});

test('unsafe numeric IDs fail closed instead of being rounded into another receipt', () => {
  const unsafeId = 9007199254740993;
  const result = run([receipt(unsafeId)]);
  assert.equal(result.items.length, 0);
});

test('unsafe refund receipt identity blocks the selector rather than undercounting refunds', () => {
  const unsafeReceiptId = 9007199254740993;
  const result = run([receipt('safe-id')], [{ receiptId: unsafeReceiptId, amountCents: 10000, status: '已核实' }]);
  assert.equal(result.blocked, true);
  assert.match(result.hint, /标识无法安全识别/);
});

test('front-end selector is wired to the domain filter and the form exposes loading, empty and retry states', () => {
  const workbench = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js', 'utf8');
  const wxml = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.wxml', 'utf8');
  const journey = fs.readFileSync('miniprogram/pages/admin/finance-desk/journey.wxml', 'utf8');
  assert.match(workbench, /AllocationCandidates\.candidates/);
  assert.match(workbench, /api\.refunds\(\)/);
  assert.match(wxml, /allocationReceiptsLoading/);
  assert.match(wxml, /allocationReceiptsError/);
  assert.match(wxml, /allocationReceiptHint/);
  assert.match(journey, /wx:if="\{\{item\.amount_cents>item\.collected_cents\}\}" class="fin-text" data-id="\{\{item\.id\}\}" bindtap="openReceiptAllocation"/);
});

test('opening the real workbench form loads only current-contract candidates and keeps the exact receipt ID', async () => {
  const source = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js', 'utf8');
  const BookView = require('../miniprogram/utils/finance-workbench-view');
  const FinanceDate = require('../miniprogram/utils/finance-date');
  const Money = require('../miniprogram/utils/platform-finance');
  const Accounting = require('../miniprogram/pages/admin/accounting/model');
  const http = { sessionGuard: () => () => true, requestGuard: () => () => true };
  const auth = { currentApiPost: () => ({ roleCode: 'finance' }) };
  const modules = {
    '../../../utils/finance-workbench-view': BookView,
    '../../../utils/finance-date': FinanceDate,
    '../../../api/http': http,
    '../../../api/auth': auth,
    '../../../utils/platform-finance': Money,
    '../accounting/model': Accounting,
    './allocation-candidates': Allocation
  };
  const mod = { exports: {} };
  vm.runInNewContext(source, {
    module: mod,
    require: name => {
      if (!(name in modules)) throw new Error('Unexpected dependency ' + name);
      return modules[name];
    },
    wx: { showToast() {}, pageScrollTo() {} },
    console
  });
  const api = {
    idemKey: prefix => 'test-' + prefix,
    receipts: async () => [receipt('receipt-1', { amountCents: 50000 })],
    refunds: async () => [{ receiptId: 'receipt-1', amountCents: 10000, status: '已核实' }]
  };
  const page = {
    data: {
      wbForm: null, wbBusy: false, paymentPending: false,
      financialStages: [{ ...stage, id: 'stage-1', amount_cents: 100000, collected_cents: 0 }],
      workbenchBooks: [targetProject], allocationReceipts: []
    },
    __financeReadEpoch: 1,
    setData(patch) {
      Object.keys(patch).forEach(key => {
        const parts = key.split('.');
        let target = this.data;
        parts.slice(0, -1).forEach(part => { target = target[part] || (target[part] = {}); });
        target[parts[parts.length - 1]] = patch[key];
      });
    }
  };
  mod.exports(page, api);
  await page.openReceiptAllocation({ currentTarget: { dataset: { id: 'stage-1' } } });
  assert.equal(page.data.wbForm.stageId, 'stage-1');
  assert.equal(page.data.allocationReceipts.length, 1);
  assert.equal(page.data.allocationReceipts[0].id, 'receipt-1');
  assert.equal(page.data.allocationReceipts[0].availableAmountCents, 40000);
  page.wbPick({ currentTarget: { dataset: { field: 'receiptId' } }, detail: { value: '0' } });
  assert.equal(page.data.wbForm.receiptId, 'receipt-1');
});
