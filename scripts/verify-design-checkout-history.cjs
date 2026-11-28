'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const helperPath = path.resolve('miniprogram/utils/design-checkout-history.js');
const oldPath = path.resolve('miniprogram/utils/design-checkout.js');
const History = require('../miniprogram/utils/design-checkout-history');

test('historical checkout compatibility exposes read-only facts and no write commands', () => {
  assert.equal(fs.existsSync(oldPath), false);
  assert.deepEqual(Object.keys(History).sort(), ['MODE', 'authorized', 'is', 'order', 'paid', 'pricing']);
  assert.equal(History.create, undefined);
  assert.equal(History.pay, undefined);
  assert.equal(History.savePricing, undefined);
  assert.equal(History.quote, undefined);
  const customer = { id:'customer-1', businessFlow:{paymentMode:History.MODE,paymentOrder:{id:'legacy-1',status:'paid'}} };
  assert.equal(History.is(customer), true);
  assert.equal(History.order(customer).id, 'legacy-1');
  assert.equal(History.is({businessFlow:{paymentMode:'other'}}), false);
});

test('all remaining historical read models use the read-only adapter, never the retired writer', () => {
  const files = [
    'commission-history.js','design-admin-workbench.js','design-notifications.js',
    'design-receipts.js','finance-desk.js','finance-ledger.js','finance-source.js'
  ];
  for (const file of files) {
    const source = fs.readFileSync(path.resolve('miniprogram/utils',file),'utf8');
    assert.ok(source.includes('design-checkout-history'),file+' must use read-only adapter');
    assert.ok(!source.includes("require('./design-checkout')"),file+' must not use retired writer');
  }
});
