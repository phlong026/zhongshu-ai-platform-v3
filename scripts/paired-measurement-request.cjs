// Runs the native external-entrust page with the HTTP transport supplied by its PostgreSQL fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { render } = require('./role-page-runtime.cjs');
const visibleText = nodes => nodes.map(node => (node.text || '') + visibleText(node.children || [])).join('');

module.exports = async function registerMeasurement(fixture, fields, api) {
  const modulePath = path.resolve(__dirname, '../miniprogram/pages/external-entrust/index.js');
  const nativeRequire = createRequire(modulePath);
  const post = fixture.sales;
  let pageDefinition;
  const overrides = {
    '../../api/http': {token: () => 'fixture', currentPostId: () => post.postId},
    '../../api/auth': {currentApiPost: () => post},
    '../../utils/api-scope': () => JSON.stringify([post.orgId,post.postId]),
    '../../utils/platform-crm': {commandKey: () => 'measurement-native-' + fixture.customerId},
    './api': api
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
  await page.submit();
  assert.equal(page.data.error,'');
  assert.equal(page.data.context.request.data.measurementRef.eventId,item.eventId);
  assert.equal(page.data.context.request.attachments[0].assetId,fixture.measurementAssetId);
  page.changeTab({currentTarget:{dataset:{tab:'history'}}});
  page.toggleHistory({currentTarget:{dataset:{key:'requests-1'}}});
  assert.ok(visibleText(render(template,page.data)).includes(item.eventId));
  return api.get(fixture.customerId);
};
