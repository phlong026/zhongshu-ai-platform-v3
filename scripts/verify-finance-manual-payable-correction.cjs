'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const PF = require('../miniprogram/utils/platform-finance');

test('unassigned and already assigned manual payables can enter audited attribution correction', () => {
  assert.equal(PF.canAssignPayable({ sourceType: 'manual', status: '进行中' }), true);
  assert.equal(PF.canAssignPayable({ sourceType: 'manual', projectId: '101', status: '进行中' }), true);
  assert.equal(PF.canAssignPayable({ source_type: 'manual', book_id: '202', status: '进行中' }), true);
});

test('upstream, voided, deleted or source-referenced payables cannot be reassigned from the finance UI', () => {
  assert.equal(PF.canAssignPayable({ sourceType: 'engineering', projectId: '101', status: '进行中' }), false);
  assert.equal(PF.canAssignPayable({ sourceType: 'manual', sourceRef: 'engineering:101', status: '进行中' }), false);
  assert.equal(PF.canAssignPayable({ sourceType: 'manual', status: '已作废' }), false);
  assert.equal(PF.canAssignPayable({ sourceType: 'manual', deleted: true, status: '进行中' }), false);
});

test('finance UI labels an existing attribution as correction and displays the old scope before changing it', () => {
  const index = fs.readFileSync('miniprogram/pages/admin/finance-desk/index.wxml', 'utf8');
  const journey = fs.readFileSync('miniprogram/pages/admin/finance-desk/journey.wxml', 'utf8');
  const form = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.wxml', 'utf8');
  const workbench = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js', 'utf8');
  assert.match(index, /更正归属/);
  assert.match(journey, /更正归属/);
  assert.ok(journey.includes('item.project_id') && journey.includes('item.book_id'));
  assert.ok(form.includes('当前归属') && form.includes('更正依据'));
  assert.match(form, /当前归属/);
  assert.match(form, /更正依据/);
  assert.match(workbench, /originalScopeLabel/);
  assert.match(workbench, /currentScope/);
});

test('changing the target keeps the original project and book captured at open time', async () => {
  const vm = require('node:vm');
  const source = fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js', 'utf8');
  const requests = [];
  const http = { sessionGuard: () => () => true, post: async (endpoint, body, headers) => {
    requests.push([endpoint, body, headers]);
    throw { code: 1099000001 };
  }};
  const deps = { '../../../api/http': http, '../../../utils/platform-finance': PF };
  const mod = { exports: {} };
  vm.runInNewContext(source, { module: mod, require: name => deps[name] || {} });
  const page = { data: { workbenchBooks: [] }, __financeFacts: { payables: [
    { id: '1', sourceType: 'manual', projectId: '9007199254740993', bookId: '9007199254740995' },
    { id: '2', source_type: 'manual', project_id: null, book_id: null }
  ]}, setData(patch) { Object.assign(this.data, patch); } };
  mod.exports(page, { idemKey: () => 'same-operation-key' });
  for (const [id, original] of [['1', '9007199254740993:9007199254740995'], ['2', ':']]) {
    page.assignPayableBook({ currentTarget: { dataset: { id } } });
    page.data.wbForm.scope = 'project:321';
    page.data.wbForm.reason = '核对原单';
    await page.submitWorkbench();
    const [endpoint, body, headers] = requests.pop();
    assert.equal(endpoint, '/franchise/finance/workbench/payables/' + id + '/assign');
    assert.equal(body.expectedScope, original);
    assert.equal(body.scope, 'project:321');
    assert.equal(headers['Idempotency-Key'], 'same-operation-key');
    assert.match(page.data.wbError, /归属已变化.*刷新/);
  }
});
