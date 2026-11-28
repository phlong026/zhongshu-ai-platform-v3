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
  assert.match(index, /item\\.project_id/);
  assert.match(index, /item\\.book_id/);
  assert.match(journey, /更正归属/);
  assert.match(journey, /item\\.project_id/);
  assert.match(journey, /item\\.book_id/);
  assert.match(form, /当前归属/);
  assert.match(form, /更正依据/);
  assert.match(workbench, /originalScopeLabel/);
  assert.match(workbench, /currentScope/);
});
