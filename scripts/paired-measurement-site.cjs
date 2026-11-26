const assert = require('node:assert/strict');
const { connect } = require('./role-page-runtime.cjs');
const auth = require('../miniprogram/api/auth');

module.exports = async function recordSiteObservations(fixture) {
  const runtime = await connect({lifecycles:true});
  const post = fixture.sales, notes = fixture.measurementSite;
  auth.saveLogin({accessToken:'paired-measurement',userId:post.userId});
  auth.savePosts([post]); auth.switchPost(post.postId);
  await runtime.callWxMethod('reLaunch',{url:'/pages/customer-flow/index?step=MEASUREMENT&customerId='+fixture.customerId});
  const page = getCurrentPages().at(-1), screen = await runtime.currentPage();
  for(let i=0;!page.data.step && !page.data.error && i<250;i++) await new Promise(resolve=>setTimeout(resolve,20));
  assert.equal(page.data.step?.status,'COMPLETED',page.data.error);
  assert.equal(page.data.form.fields.orientation,''); assert.equal(page.data.form.fields.siteElevationNote,'');
  if (fixture.legacyMeasurementNote) assert.equal(page.data.form.note,fixture.legacyMeasurementNote);
  let reopened;
  wx.showModal = options => { reopened=options.success({confirm:true,content:'复测现场朝向和道路高差'}); };
  await (await screen.$('.flow-reopen')).tap(); await reopened;
  assert.equal(page.data.step.canWrite,true,page.data.error);
  await (await screen.$('input[data-key="orientation"]')).input(notes.orientation);
  await (await screen.$('textarea[data-key="siteElevationNote"]')).input(notes.siteElevationNote);
  if (fixture.newMeasurementNote) await (await screen.$('textarea[data-key="note"]')).input(fixture.newMeasurementNote);
  assert.equal(page.data.form.fields.orientation,notes.orientation);
  assert.equal(page.data.form.fields.siteElevationNote,notes.siteElevationNote);
  assert.equal(page.data.form.fields.checked,false);
  await (await screen.$('switch[data-key="checked"]')).trigger('change',{value:true});
  await (await screen.$('button[data-action="complete"]')).tap();
  assert.equal(page.data.step.status,'COMPLETED',page.data.error);
  assert.equal(page.data.workflow.completedCount,7);
  await page.toggleHistory();
  const legacy = page.data.records.find(row=>row.id===fixture.measurementEventId);
  assert.ok(legacy); assert.equal(legacy.fields.orientation,undefined); assert.equal(legacy.fields.siteElevationNote,undefined);
  const source = page.data.records.find(row=>row.stepCode==='MEASUREMENT' && row.type==='SAVE' && row.id!==legacy.id);
  assert.ok(source); assert.equal(source.fields.orientation,notes.orientation); assert.equal(source.fields.siteElevationNote,notes.siteElevationNote);
  if (fixture.newMeasurementNote) {
    assert.equal(legacy.note,fixture.legacyMeasurementNote);
    assert.equal(source.note,fixture.newMeasurementNote);
  }
  page.viewRecord({currentTarget:{dataset:{id:source.id}}});
  assert.ok(page.data.activeRecord.details.some(row=>row.value===notes.siteElevationNote));
  return source.id;
};
