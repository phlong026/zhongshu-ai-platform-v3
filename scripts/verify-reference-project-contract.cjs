const test = require('node:test');
const assert = require('node:assert/strict');
const attach = require('./fixtures/legacy-explore-platform-bridge.cjs');
const flush = () => new Promise(resolve => setImmediate(resolve));
function harness(error) {
  const calls = [];
  const http = { sessionGeneration: () => 1 };
  const page = { data: { form: { width: 10, depth: 10, floors: 2, count: '2', resolution: '2K', palette: '米白', material: '石材', color: '米白', referenceAssetIds: ['123'] } },
    __http: http, __post: {postId: '11'}, __postKey: '11',
    setData(patch) { Object.assign(this.data, patch); } };
  attach(page, { http, PF: { requirementInputs: () => ({inputs: {width: 10}}), estimateText: () => '', styleCode: () => 'modern', roofCode: () => 'flat', errorMessageDesign: e => e.message }, api: { projects: {
    create(body, key) { calls.push({kind: 'project', body, key}); return error && error.phase !== 'job' ? Promise.reject(error) : Promise.resolve({projectId: '501'}); },
    createElevationJob(id, body, key) { calls.push({kind: 'job', id, body, key}); return error && error.phase === 'job' ? Promise.reject(error) : Promise.resolve({jobId: '601'}); }
  } } });
  page.platPoll = () => {};
  return {page, calls};
}
test('reference IDs are frozen in the project; paid job carries no mutable reference override', async () => {
  const {page, calls} = harness(); page.platGenerate('facade'); await flush(); await flush();
  assert.deepEqual(calls[0].body.requirementInputs.referenceAssetIds, ['123']);
  assert.equal(calls[1].body.requirementInputs, undefined);
  assert.equal(calls[1].id, '501');
});
test('uncertain project retry keeps the business key; changing references gets a new key', async () => {
  const {page, calls} = harness({code: 1112100002, message: 'pending'});
  page.platGenerate('facade'); await flush(); await flush();
  page.platGenerate('facade'); await flush(); await flush();
  assert.equal(calls[0].key, calls[1].key);
  page.data.form.referenceAssetIds = ['124'];
  page.platGenerate('facade'); await flush(); await flush();
  assert.notEqual(calls[2].key, calls[1].key);
});

test('uncertain paid job retries the same frozen project and job keys', async () => {
  const {page, calls} = harness({phase: 'job', code: 1112100002, message: 'pending'});
  page.platGenerate('facade'); await flush(); await flush();
  page.platGenerate('facade'); await flush(); await flush();
  assert.equal(calls[0].key, calls[2].key);
  assert.equal(calls[1].key, calls[3].key);
  assert.equal(calls[1].id, calls[3].id);
});

test('failed or missing result download tickets remain visible errors', async () => {
  for (const downloadUrl of [() => Promise.reject(new Error('download denied')), () => Promise.resolve({})]) {
    const page = {data: {}, setData() {}};
    attach(page, {http: {}, PF: {}, api: {assets: {downloadUrl}}});
    await assert.rejects(page.platFetchImages(['901']));
  }
});

test('public case and generated result with identical IDs use explicit separate readers', async () => {
  const calls = [];
  const page = {data: {}, setData() {}};
  attach(page, {http: {}, PF: {}, api: {
    assets: {downloadUrl(id) {calls.push(['result', id]); return Promise.resolve({downloadUrl: 'https://result.example/901'});}},
    cases: {downloadUrl(caseId, id) {calls.push(['case', caseId, id]); return Promise.resolve({downloadUrl: 'https://public.example/901'});}}
  }});
  assert.deepEqual(await page.platFetchImages(['901']), ['https://result.example/901']);
  assert.deepEqual(await page.platFetchImages(['901'], '19'), ['https://public.example/901']);
  assert.deepEqual(calls, [['result','901'],['case','19','901']]);
});

test('five public case images stay below the case admission limit and preserve order', async () => {
  let active = 0, peak = 0;
  const page = {data: {}, setData() {}};
  attach(page, {http: {}, PF: {}, api: {cases: {async downloadUrl(caseId, id) {
    active++; peak = Math.max(peak, active);
    try { if (active > 4) throw new Error('self throttled'); await flush(); return {downloadUrl: 'https://public.example/' + id}; }
    finally { active--; }
  }}}});
  const ids = ['1','2','3','4','5'];
  assert.deepEqual(await page.platFetchImages(ids, '19'), ids.map(id => 'https://public.example/' + id));
  assert.ok(peak <= 4);
});

test('page refresh reports download permission and server failures instead of still generating', async () => {
  const PF = require('../miniprogram/utils/platform-design');
  for (const failure of [{code: 403}, {code: 500, message: '图片服务暂不可用'}]) {
    const page = {data: {}, __http: {sessionGeneration: () => 1}, __post: {postId: '11'}, __postKey: '11',
      __platJobId: '601', __platProjectId: '501', setData(p) {Object.assign(this.data, p);} };
    attach(page, {http: page.__http, PF, api: {projects: {detail: () => Promise.resolve({candidates: [{assetId: '901'}]})},
      assets: {downloadUrl: () => Promise.reject(failure)}}});
    page.platRefreshJob(); await flush(); await flush();
    assert.equal(page.data.platformErr, PF.errorMessageDesign(failure));
    assert.notEqual(page.data.platformErr, PF.STILL_RUNNING_MESSAGE);
  }
});
