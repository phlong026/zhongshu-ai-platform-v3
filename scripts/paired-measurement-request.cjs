// Runs the native external-entrust page with the HTTP transport supplied by its PostgreSQL fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const visibleText = nodes => nodes.map(node => (node.text || '') + visibleText(node.children || [])).join('');

module.exports = async function registerMeasurement(fixture, fields, api, transferCustomer) {
  const modulePath = path.resolve(__dirname, '../miniprogram/pages/external-entrust/index.js');
  const nativeRequire = createRequire(modulePath);
  const post = fixture.sales;
  let pageDefinition;
  const registrations = [];
  const pageApi = {...api, register: async (...args) => {
    registrations.push(structuredClone(args));
    const result = await api.register(...args);
    if (fixture.transfer) throw Error('Receipt lost after PostgreSQL committed the native command');
    return result;
  }};
  const overrides = {
    '../../api/http': {token: () => 'fixture', currentPostId: () => post.postId},
    '../../api/auth': {currentApiPost: () => post},
    '../../utils/api-scope': () => JSON.stringify([post.orgId,post.postId]),
    '../../utils/platform-crm': {commandKey: () => 'measurement-native-' + fixture.customerId},
    './api': pageApi
  };
  vm.runInNewContext(fs.readFileSync(modulePath, 'utf8'), {
    Page: page => {pageDefinition = page;}, require: name => overrides[name] || nativeRequire(name),
    wx: {showToast(){}}, console
  }, {filename:modulePath});
  const page = {...pageDefinition, data: structuredClone(pageDefinition.data), setData(patch) { Object.assign(this.data,patch); }};
  page.onLoad({customerId:fixture.customerId});
  await page.onShow();
  assert.equal(page.data.editing,'register');
  const template = path.resolve(path.dirname(modulePath),'index.wxml');
  assert.ok(visibleText(render(template,page.data)).includes('选择已核对的测量记录'));
  for (const [key,value] of Object.entries(fields)) {
    page.input({currentTarget:{dataset:{key}},detail:{value}});
  }
  await page.loadMeasurements();
  const item = page.data.measurements.find(row => row.eventId === fixture.measurementEventId);
  assert.ok(item);
  assert.equal(item.attachments[0].assetId,fixture.measurementAssetId);
  page.chooseMeasurement({currentTarget:{dataset:{id:item.eventId}}});
  assert.equal(page.data.form.attachments.length,0);
  assert.equal(page.data.form.measurementRef.eventId,fixture.measurementEventId);
  if (fixture.measurementSite) {
    const selected = visibleText(render(template,page.data));
    for (const [key,value] of Object.entries(fixture.measurementSite)) {
      assert.equal(item.fields[key],value); assert.ok(selected.includes(value));
    }
  }
  await page.submit();
  if (fixture.transfer) {
    assert.equal(page.data.uncertain,true);
    page.onHide(); assert.equal(page.data.form,null);
    await page.onShow();
    assert.equal(page.data.form.measurementRef.eventId,item.eventId);
    await page.runPending();
    assert.equal(page.data.uncertain,true);
    assert.equal(registrations.length,2);
    assert.deepEqual(registrations[0],registrations[1]);
    page.onHide();
    const moved = await transferCustomer(fixture.transfer.targetId,fixture.transfer.revision);
    await page.onShow();
    assert.equal(page.data.ready,false); assert.equal(page.data.form,null); assert.equal(page.data.context,null);
    assert.equal(page.data.uncertain,false); assert.equal(page._pending,null);
    assert.ok(!visibleText(render(template,page.data)).includes(item.fields.address));
    await page.runPending(); assert.equal(registrations.length,2);
    await transferCustomer(post.userId,Number(moved.customer.revision));
    await page.onShow();
  }
  assert.equal(page.data.error,'');
  assert.equal(page.data.context.request.data.measurementRef.eventId,item.eventId);
  assert.equal(page.data.context.request.attachments[0].assetId,fixture.measurementAssetId);
  page.changeTab({currentTarget:{dataset:{tab:'history'}}});
  page.toggleHistory({currentTarget:{dataset:{key:'requests-1'}}});
  assert.ok(visibleText(render(template,page.data)).includes(item.eventId));
  if (fixture.measurementSite) {
    const history = visibleText(render(template,page.data));
    for (const value of Object.values(fixture.measurementSite)) assert.ok(history.includes(value));
  }
  return api.get(fixture.customerId);
};
