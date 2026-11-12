const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const mini = path.resolve(__dirname, '../miniprogram');
const clone = value => value === undefined ? value : JSON.parse(JSON.stringify(value));

function boot(storage = new Map()) {
  for (const file of Object.keys(require.cache)) if (file.startsWith(mini + path.sep)) delete require.cache[file];
  const calls = [], toasts = [], timers = [];
  global.wx = {
    getStorageSync: key => clone(storage.get(key)),
    setStorageSync: (key, value) => storage.set(key, clone(value)),
    removeStorageSync: key => storage.delete(key),
    request: options => calls.push(options),
    showToast: value => toasts.push(value),
    showModal: options => { wx.modal = options; },
    reLaunch() {}
  };
  const http = require('../miniprogram/api/http');
  const auth = require('../miniprogram/api/auth');
  if (!storage.has(http.TOKEN_KEY)) {
    http.saveSession({ token: 'test-session-only', tenantId: '11', postId: '101' });
    auth.savePosts([{ postId: '101', userId: '201', orgId: '301', roleCode: 'sales' }]);
  }
  storage.set('zs-api-base', 'https://b.invalid/admin-api');
  const api = require('../miniprogram/api/franchise/design');
  const page = {
    data: { form: { width: '10', depth: '12', floors: '2', style: '现代简约', count: '2', resolution: '2K', note: '私有设计正文', material: 'WHITE_STUCCO', color: 'BLACK', roof: '坡屋顶', palette: '灰色' }, kind: 'plan', platformMode: true },
    __http: http, __post: auth.currentApiPost(), __postKey: '101',
    setData(values) {
      for (const [key, value] of Object.entries(values)) {
        const parts = key.split('.'); let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
        target[parts.at(-1)] = value;
      }
    }
  };
  require('../miniprogram/pages/workflow/explore/platform-bridge')(page, { http, api, PF: require('../miniprogram/utils/platform-design') });
  page.platPoll = (...args) => timers.push(args);
  return { storage, calls, toasts, timers, http, auth, api, page };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
const success = (call, data = {}) => call.success({ statusCode: 200, data: { code: 0, data } });
const timeout = call => call.fail({ errMsg: 'request:fail timeout' });
const keyOf = call => call.header['Idempotency-Key'];
const invoke = fn => Promise.resolve().then(fn);
const writes = [
  ['favorite', api => api.cases.favorite('9'), 'PUT', '/cases/9/favorite'],
  ['unfavorite', api => api.cases.unfavorite('9'), 'DELETE', '/cases/9/favorite'],
  ['upload', api => api.assets.uploadTicket({ assetType: 'USER_SKETCH', mimeType: 'image/jpeg', sizeBytes: 3, sha256: 'abc' }), 'POST', '/assets/upload-tickets'],
  ['complete', api => api.assets.complete('9'), 'POST', '/assets/9/complete'],
  ['create', api => api.projects.create({ sourceType: 'SELF_UPLOAD', line: 'FLAT', requirementInputs: { note: '私有设计正文' } }), 'POST', '/projects'],
  ['flat', api => api.projects.createFlatJob('9', { count: 2, resolution: '2K' }), 'POST', '/projects/9/flat-jobs'],
  ['elevation', api => api.projects.createElevationJob('9', { count: 2, color: 'BLACK' }), 'POST', '/projects/9/elevation-jobs'],
  ['cancel', api => api.projects.cancelJob('9'), 'POST', '/ai-jobs/9/cancellation-requests'],
  ['polish', api => api.projects.notePolish({ note: '私有设计正文', line: 'FLAT' }), 'POST', '/note-polish'],
  ['selectFlat', api => api.projects.selectFlat('9', { jobId: '7', candidateId: '8' }), 'POST', '/projects/9/flat-selections'],
  ['selectElevation', api => api.projects.selectElevation('9', { jobId: '7', candidateId: '8' }), 'POST', '/projects/9/elevation-selections'],
  ['revision', api => api.projects.revision('9', { reason: '私有设计正文' }), 'POST', '/projects/9/revision-requests'],
  ['budget', api => api.budget.estimate('9', { buildingArea: 120, regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' }), 'POST', '/projects/9/budget-estimates'],
  ['save', api => api.projects.saveToCustomer('9', { customerId: '8', kind: 'PLAN', resultVersionId: '7' }), 'POST', '/projects/9/save-to-customer']
];

for (const [name, call, method, route] of writes) test(name + ': timeout/restart retains key; confirmed completion starts a new operation', async () => {
  let h = boot();
  let pending = invoke(() => call(h.api));
  const failed = assert.rejects(pending, err => err.code === -1);
  await flush();
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].url, 'https://b.invalid/admin-api/franchise/design' + route);
  assert.equal(h.calls[0].method, method);
  const key = keyOf(h.calls[0]);
  assert.ok(key, 'every business write sends a key');
  timeout(h.calls[0]); await failed;
  const stored = JSON.stringify([...h.storage].filter(([k]) => k.startsWith('zs-design-')));
  assert.ok(stored.includes(key), 'persist before dispatch');
  assert.ok(!stored.includes('私有设计正文') && !stored.includes('test-session-only'));
  h = boot(h.storage);
  pending = invoke(() => call(h.api)); await flush();
  assert.equal(keyOf(h.calls[0]), key);
  success(h.calls[0]); await pending;
  pending = invoke(() => call(h.api)); await flush();
  assert.notEqual(keyOf(h.calls[1]), key);
  success(h.calls[1]); await pending;
});

test('read routes and download credentials do not acquire business keys', async () => {
  const h = boot();
  const reads = [() => h.api.cases.page({ limit: 20 }), () => h.api.cases.styles(), () => h.api.cases.detail('9'), () => h.api.cases.favorites(), () => h.api.projects.list({}), () => h.api.projects.detail('9', '7'), () => h.api.projects.aiJob('7'), () => h.api.projects.resultVersions('9'), () => h.api.budget.ruleInfo('3301', 'BRICK', 'A'), () => h.api.assets.downloadUrl('9')];
  for (const call of reads) {
    const pending = call(); await flush();
    const request = h.calls.at(-1);
    assert.equal(keyOf(request), undefined);
    success(request); await pending;
  }
});

test('same explicit key with different payload rejects before network, including after reload', async () => {
  let h = boot();
  let pending = h.api.projects.revision('9', { reason: '原需求' }, 'stable-user-key');
  await flush(); success(h.calls[0]); await pending;
  h = boot(h.storage);
  await assert.rejects(invoke(() => h.api.projects.revision('9', { reason: '另一需求' }, 'stable-user-key')), err => err.code === 'DESIGN_INTENT_CONFLICT');
  assert.equal(h.calls.length, 0);
});

test('write intents are isolated by tenant/org/user/post; late responses cannot clear old intent', async () => {
  const h = boot();
  const pending = h.api.projects.notePolish({ note: '原需求', line: 'FLAT' });
  const rejected = assert.rejects(pending, err => err.silent === true);
  await flush(); const oldKey = keyOf(h.calls[0]);
  h.http.saveSession({ tenantId: '12', postId: '102' });
  h.auth.savePosts([{ postId: '102', userId: '202', orgId: '302', roleCode: 'sales' }]);
  const next = h.api.projects.notePolish({ note: '原需求', line: 'FLAT' });
  await flush(); assert.notEqual(keyOf(h.calls[1]), oldKey);
  success(h.calls[0], { polished: '旧结果' }); await rejected;
  success(h.calls[1]); await next;
  h.http.saveSession({ tenantId: '11', postId: '101' });
  h.auth.savePosts([{ postId: '101', userId: '201', orgId: '301', roleCode: 'sales' }]);
  const retry = h.api.projects.notePolish({ note: '原需求', line: 'FLAT' });
  await flush(); assert.equal(keyOf(h.calls[2]), oldKey); success(h.calls[2]); await retry;
});

test('storage failure prevents dispatch; uncertain HTTP error retains original intent', async () => {
  const h = boot(); const write = wx.setStorageSync;
  wx.setStorageSync = () => { throw new Error('storage full'); };
  await assert.rejects(invoke(() => h.api.projects.cancelJob('9')), err => err.code === 'DESIGN_INTENT_STORAGE');
  assert.equal(h.calls.length, 0); wx.setStorageSync = write;
  const pending = h.api.projects.cancelJob('9'); const rejected = assert.rejects(pending);
  await flush(); h.calls[0].success({ statusCode: 503, data: { code: 503, msg: 'unavailable' } }); await rejected;
  const retry = h.api.projects.cancelJob('9'); await flush(); assert.equal(keyOf(h.calls[1]), keyOf(h.calls[0])); success(h.calls[1]); await retry;
});

test('concurrent identical writes coalesce, canonical payload matches, changed payload is refused', async () => {
  const h = boot();
  const body = { note: '中文需求', line: 'FLAT' };
  const one = h.api.projects.notePolish(body), two = h.api.projects.notePolish({ line: 'FLAT', note: '中文需求' });
  body.note = '界面修改'; await flush();
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].data.note, '中文需求');
  await assert.rejects(h.api.projects.notePolish({ note: '其他需求', line: 'FLAT' }), err => err.code === 'DESIGN_INTENT_CONFLICT');
  assert.equal(h.calls.length, 1);
  success(h.calls[0], { polished: '结果' }); await Promise.all([one, two]);
});

for (const dimension of ['tenant', 'orgId', 'userId', 'postId']) test('identity isolation independently includes ' + dimension, async () => {
  const h = boot(); const first = h.api.projects.cancelJob('9'); const failed = assert.rejects(first);
  await flush(); const key = keyOf(h.calls[0]); timeout(h.calls[0]); await failed;
  const post = { ...h.auth.currentApiPost() };
  if (dimension === 'tenant') h.http.saveSession({ tenantId: '12' });
  else { post[dimension] = '999'; h.auth.savePosts([post]); h.http.saveSession({ postId: post.postId }); }
  const next = h.api.projects.cancelJob('9'); await flush();
  assert.notEqual(keyOf(h.calls[1]), key); success(h.calls[1]); await next;
});

test('missing or mismatched trusted identity refuses a write before network', async () => {
  const h = boot(); h.auth.savePosts([{ postId: '101', orgId: '301', roleCode: 'sales' }]);
  await assert.rejects(h.api.projects.cancelJob('9'), err => err.code === 'DESIGN_IDENTITY_REQUIRED');
  assert.equal(h.calls.length, 0);
});

test('late generation response does not continue under a switched identity', async () => {
  const h = boot(); h.page.platGenerate('plan'); await flush();
  h.http.saveSession({ postId: '102' });
  h.auth.savePosts([{ postId: '102', orgId: '301', userId: '201', roleCode: 'sales' }]);
  const before = clone(h.page.data), saved = clone([...h.storage]);
  success(h.calls[0], { projectId: '91' }); await flush();
  assert.deepEqual(h.page.data, before); assert.deepEqual([...h.storage], saved);
  assert.equal(h.calls.length, 1); assert.equal(h.page.__platProjectId, undefined);
  h.page.platNotePolish(); h.page.platGenerate('plan'); await flush(); assert.equal(h.calls.length, 1);
});

test('facade project freezes all references in the B project DTO; pending payload cannot change', async () => {
  let h = boot(); h.page.data.form.referenceAssetIds = ['83']; h.page.data.form.entranceOrientation = 'SOUTH';
  h.page.platGenerate('facade'); await flush();
  assert.deepEqual(h.calls[0].data.requirementInputs.referenceAssetIds, ['83']);
  const key = keyOf(h.calls[0]); timeout(h.calls[0]); await flush();
  h = boot(h.storage); h.page.data.form.referenceAssetIds = ['84']; h.page.data.form.entranceOrientation = 'SOUTH';
  h.page.platGenerate('facade'); await flush();
  assert.equal(h.calls.length, 0); assert.ok(h.page.data.platformErr.includes('原操作'));
  h.page.data.form.referenceAssetIds = ['83']; h.page.platGenerate('facade'); await flush();
  assert.equal(keyOf(h.calls[0]), key); success(h.calls[0], { projectId: '91' }); await flush();
  assert.deepEqual(Object.keys(h.calls[1].data).sort(), ['color', 'count', 'material', 'resolution', 'roofType', 'styleCode']);
  success(h.calls[1], { jobId: '92' }); await flush();
});

test('page generation resumes the original project and job after timeout and restart', async () => {
  let h = boot(); h.page.platGenerate('plan'); await flush();
  assert.equal(h.calls.length, 1); success(h.calls[0], { projectId: '91' }); await flush();
  assert.equal(h.calls.length, 2); const jobKey = keyOf(h.calls[1]); timeout(h.calls[1]); await flush();
  h = boot(h.storage); h.page.platGenerate('plan'); await flush();
  assert.equal(h.calls.length, 1);
  assert.ok(h.calls[0].url.endsWith('/projects/91/flat-jobs'), 'must not create another project');
  assert.equal(keyOf(h.calls[0]), jobKey); success(h.calls[0], { jobId: '92' }); await flush();
  assert.equal(h.page.__platJobId, '92'); assert.equal(h.timers.length, 1);
  h.page.setData({ busy: false }); h.page.platGenerate('plan'); await flush();
  assert.ok(h.calls[1].url.endsWith('/projects')); success(h.calls[1], { projectId: '93' }); await flush();
  success(h.calls[2], { jobId: '94' }); await flush();
  assert.notEqual(keyOf(h.calls[2]), jobKey);
});

test('page budget resumes its original project after restart', async () => {
  let h = boot();
  Object.assign(h.page.data.form, { area: '120', regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush(); success(h.calls[0], { projectId: '91' }); await flush();
  const key = keyOf(h.calls[1]); timeout(h.calls[1]); await flush();
  h = boot(h.storage);
  Object.assign(h.page.data.form, { area: '120', regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush();
  assert.ok(h.calls[0].url.endsWith('/projects/91/budget-estimates'));
  assert.equal(keyOf(h.calls[0]), key);
  success(h.calls[0], { estimateId: '92', totalLowCents: 10000, totalHighCents: 20000 }); await flush();
  assert.equal(h.page.data.platBudget.estimateId, '92');
});

test('reference upload retries the same ticket and completion without persisting signed URL', async () => {
  const h = boot();
  wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/reference.jpg' }] });
  wx.getFileSystemManager = () => ({ readFile: options => options.success({ data: 'YWJj' }) });
  h.page.platAddReference(); await flush();
  assert.equal(h.calls[0].data.sha256, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  const ticketKey = keyOf(h.calls[0]); success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/file?signature=test-only' }); await flush();
  assert.equal(h.calls[1].method, 'PUT'); assert.equal(h.calls[1].timeout, 15000);
  h.calls[1].success({ statusCode: 200 }); await flush();
  assert.ok(h.calls[2].url.endsWith('/assets/91/complete'));
  const completeKey = keyOf(h.calls[2]); timeout(h.calls[2]); await flush();
  h.page.platAddReference(); await flush();
  assert.ok(h.calls[3].url.endsWith('/assets/91/complete'), 'confirmed uploaded bytes do not need another ticket');
  assert.equal(keyOf(h.calls[3]), completeKey);
  success(h.calls[3], { uploadStatus: 'ACCEPTED' }); await flush();
  assert.deepEqual(h.page.data.form.referenceAssetIds, ['91']);
  const stored = JSON.stringify([...h.storage].filter(([k]) => k.startsWith('zs-design-')));
  assert.ok(stored.includes(ticketKey)); assert.ok(!stored.includes('signature=') && !stored.includes('/tmp/reference.jpg'));
});

function selectReference() {
  wx.chooseMedia = options => options.success({ tempFiles: [{ tempFilePath: '/tmp/reference.jpg' }] });
  wx.getFileSystemManager = () => ({ readFile: options => options.success({ data: 'YWJj' }) });
}
const expiredPut = call => call.success({ statusCode: 403, data: '<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>' });

test('definite expired upload requires explicit restart consent and survives restart/cancel', async () => {
  let h = boot(); selectReference(); h.page.platAddReference(); await flush();
  const oldKey = keyOf(h.calls[0]);
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/expired' }); await flush();
  expiredPut(h.calls[1]); await flush();
  assert.equal(wx.modal.confirmText, '重新上传');
  const original = clone([...h.storage]); wx.modal.success({ confirm: false }); await flush();
  assert.deepEqual([...h.storage], original); assert.equal(h.calls.length, 2);
  h = boot(h.storage); selectReference(); h.page.platAddReference(); await flush();
  assert.equal(h.calls.length, 0, 'known expired ticket is not PUT again');
  assert.equal(wx.modal.confirmText, '重新上传');
  wx.modal.success({ confirm: true }); await flush();
  assert.equal(h.calls.length, 1); assert.notEqual(keyOf(h.calls[0]), oldKey);
  success(h.calls[0], { assetId: '92', uploadUrl: 'https://upload.invalid/fresh' }); await flush();
  h.calls[1].success({ statusCode: 200 }); await flush();
  success(h.calls[2], { uploadStatus: 'ACCEPTED' }); await flush();
  assert.deepEqual(h.page.data.form.referenceAssetIds, ['92']);
});

test('ordinary HTTP403 never enables a new upload intent', async () => {
  const h = boot(); selectReference(); h.page.platAddReference(); await flush();
  const oldKey = keyOf(h.calls[0]);
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/denied' }); await flush();
  h.calls[1].success({ statusCode: 403, data: '<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>' }); await flush();
  assert.equal(wx.modal, undefined); h.page.platAddReference(); await flush();
  assert.equal(keyOf(h.calls[2]), oldKey);
  success(h.calls[2], { assetId: '91', uploadUrl: 'https://upload.invalid/denied' }); await flush();
  expiredPut(h.calls[3]); await flush(); assert.equal(wx.modal, undefined);
});

test('unknown PUT survives restart; a subsequent expiry cannot discard its original key', async () => {
  let h = boot(); selectReference(); h.page.platAddReference(); await flush();
  const oldKey = keyOf(h.calls[0]);
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/file' }); await flush();
  timeout(h.calls[1]); await flush();
  h = boot(h.storage); selectReference(); h.page.platAddReference(); await flush();
  assert.equal(keyOf(h.calls[0]), oldKey);
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/expired' }); await flush();
  expiredPut(h.calls[1]); await flush(); assert.equal(wx.modal, undefined);
  assert.ok(JSON.stringify([...h.storage]).includes(oldKey));
});

test('confirmed PUT survives restart and COMPLETE_UPLOAD UNKNOWN/PENDING keeps the same key', async () => {
  let h = boot(); selectReference(); h.page.platAddReference(); await flush();
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/file' }); await flush();
  h.calls[1].success({ statusCode: 200 }); await flush(); const key = keyOf(h.calls[2]);
  h.calls[2].success({ statusCode: 200, data: { code: 1112100002, msg: 'processing', data: null } }); await flush();
  h = boot(h.storage); selectReference(); h.page.platAddReference(); await flush();
  assert.equal(h.calls.length, 1); assert.ok(h.calls[0].url.endsWith('/assets/91/complete')); assert.equal(keyOf(h.calls[0]), key);
  success(h.calls[0], { uploadStatus: 'PENDING' }); await flush();
  assert.equal(wx.modal, undefined); assert.equal(h.page.data.form.referenceAssetIds, undefined);
  h.page.platAddReference(); await flush(); assert.equal(keyOf(h.calls[1]), key);
  success(h.calls[1], { uploadStatus: 'ACCEPTED' }); await flush(); assert.deepEqual(h.page.data.form.referenceAssetIds, ['91']);
});

test('switching identity before upload restart confirmation cannot clear the original intent', async () => {
  const h = boot(); selectReference(); h.page.platAddReference(); await flush();
  success(h.calls[0], { assetId: '91', uploadUrl: 'https://upload.invalid/expired' }); await flush(); expiredPut(h.calls[1]); await flush();
  assert.ok(wx.modal); h.http.saveSession({ postId: '102' });
  const original = clone([...h.storage]); wx.modal.success({ confirm: true }); await flush();
  assert.deepEqual([...h.storage], original); assert.equal(h.calls.length, 2);
});

function realExplore(storage, view) {
  const h = boot(storage);
  let definition;
  global.Page = value => { definition = value; };
  require('../miniprogram/pages/workflow/explore/index');
  delete global.Page;
  h.page = { ...definition, data: clone(definition.data), setData: h.page.setData };
  h.page.onLoad({ view });
  h.page.platPoll = (...args) => h.timers.push(args);
  assert.deepEqual(h.page.data.form, {}, 'real onLoad starts with an empty form');
  return h;
}
function enterFields(page, values) {
  for (const [key, value] of Object.entries(values)) page.input({ currentTarget: { dataset: { key } }, detail: { value } });
}

test('real empty-form restart plus same-image selection restores a pending facade reference and original job key', async () => {
  const fields = { width: '10', depth: '12', floors: '2', style: '现代简约', count: '2', resolution: '2K', note: '真实用户原始要求', material: 'WHITE_STUCCO', color: 'BLACK', roof: '坡屋顶', palette: '灰色' };
  let h = realExplore(undefined, 'ai-facade'); enterFields(h.page, fields); selectReference();
  h.page.addReference(); await flush();
  success(h.calls[0], { assetId: '83', uploadUrl: 'https://upload.invalid/reference' }); await flush();
  h.calls[1].success({ statusCode: 200 }); await flush(); success(h.calls[2], { uploadStatus: 'ACCEPTED' }); await flush();
  h.page.generate(); await flush(); success(h.calls[3], { projectId: '91' }); await flush();
  const jobKey = keyOf(h.calls[4]); timeout(h.calls[4]); await flush();
  h.page.onUnload(); h = realExplore(h.storage, 'ai-facade'); enterFields(h.page, fields); selectReference();
  h.page.addReference(); await flush();
  // 原缺陷会真的建票/上传出另一资产；完成该链再断言，不能向表单注入旧83掩盖它。
  if (h.calls.length) {
    success(h.calls[0], { assetId: '84', uploadUrl: 'https://upload.invalid/new-reference' }); await flush();
    h.calls[1].success({ statusCode: 200 }); await flush(); success(h.calls[2], { uploadStatus: 'ACCEPTED' }); await flush();
  }
  assert.deepEqual(h.page.data.form.referenceAssetIds, ['83']);
  const before = h.calls.length; h.page.generate(); await flush();
  assert.equal(h.calls.length, before + 1); assert.ok(h.calls[before].url.endsWith('/projects/91/elevation-jobs'));
  assert.equal(keyOf(h.calls[before]), jobKey); success(h.calls[before], { jobId: '92' }); await flush();
  assert.equal(h.page.__platJobId, '92');
  const stored = JSON.stringify([...h.storage].filter(([key]) => key.startsWith('zs-design-')));
  assert.ok(!stored.includes(fields.note) && !stored.includes('test-session-only') && !stored.includes('upload.invalid'));
});

test('facade reference recovery survives interruption immediately after the first parent intent write', async () => {
  let h = realExplore(undefined, 'ai-facade');
  const fields = { width: '10', depth: '12', floors: '2', style: '现代简约', count: '2', resolution: '2K', material: 'WHITE_STUCCO', color: 'BLACK', roof: '坡屋顶', palette: '灰色' };
  enterFields(h.page, fields); selectReference(); h.page.addReference(); await flush();
  success(h.calls[0], { assetId: '83', uploadUrl: 'https://upload.invalid/reference' }); await flush();
  h.calls[1].success({ statusCode: 200 }); await flush(); success(h.calls[2], { uploadStatus: 'ACCEPTED' }); await flush();
  const intents = require('../miniprogram/utils/design-intent');
  const parentKey = 'zs-design-intent-v1:' + intents.context() + ':' + intents.digest(['action', 'generate-facade']);
  const write = wx.setStorageSync;
  wx.setStorageSync = (key, value) => { write(key, value); if (key === parentKey) throw new Error('interrupted after durable write'); };
  h.page.generate(); await flush(); assert.equal(h.calls.length, 3, 'no project was dispatched before interruption');
  h = realExplore(h.storage, 'ai-facade'); enterFields(h.page, fields); selectReference(); h.page.addReference(); await flush();
  assert.equal(h.calls.length, 0, 'the first persisted parent already contains the accepted reference');
  assert.deepEqual(h.page.data.form.referenceAssetIds, ['83']);
  h.page.generate(); await flush(); success(h.calls[0], { projectId: '91' }); await flush(); success(h.calls[1], { jobId: '92' }); await flush();
});

test('terminal no-budget-rule rejection permits corrected inputs with a new key and reuses its project across restart', async () => {
  let h = boot(); Object.assign(h.page.data.form, { area: '120', regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush(); success(h.calls[0], { projectId: '91' }); await flush();
  const oldKey = keyOf(h.calls[1]);
  h.calls[1].success({ statusCode: 200, data: { code: 1071000002, msg: '该地区/结构/材料等级暂无生效预算规则' } }); await flush();
  h = realExplore(h.storage, 'budget');
  enterFields(h.page, { width: '10', depth: '12', floors: '2', style: '现代简约', area: '120', regionCode: '3302', structureType: 'FRAME', materialGrade: 'A' });
  success(h.calls[0], { ruleVersionId: '71', lowCentsPerSqm: 100, highCentsPerSqm: 200 }); await flush();
  h.page.calculate(); await flush();
  assert.equal(h.calls.length, 2, 'corrected budget should issue one write after rule metadata');
  assert.ok(h.calls[1].url.endsWith('/projects/91/budget-estimates')); assert.notEqual(keyOf(h.calls[1]), oldKey);
  assert.equal(h.calls[1].data.regionCode, '3302'); assert.equal(h.calls[1].data.structureType, 'FRAME');
  success(h.calls[1], { estimateId: '92', totalLowCents: 10000, totalHighCents: 20000 }); await flush();
  assert.equal(h.page.data.platBudget.estimateId, '92');
});

for (const code of [1112100002, 409, -1]) test('budget error ' + code + ' cannot release a pending intent or change its payload', async () => {
  const h = boot(); Object.assign(h.page.data.form, { area: '120', regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush(); success(h.calls[0], { projectId: '91' }); await flush();
  const key = keyOf(h.calls[1]);
  if (code === -1) timeout(h.calls[1]); else h.calls[1].success({ statusCode: 200, data: { code, msg: 'pending or conflict' } });
  await flush(); h.page.data.form.regionCode = '3302'; h.page.platCalculate(); await flush();
  assert.equal(h.calls.length, 2); assert.ok(h.page.data.platformErr.includes('原操作'));
  h.page.data.form.regionCode = '3301'; h.page.platCalculate(); await flush();
  assert.equal(keyOf(h.calls[2]), key); success(h.calls[2], { estimateId: '92', totalLowCents: 10000, totalHighCents: 20000 }); await flush();
});

test('1071000002 from another operation cannot terminate its intent', async () => {
  const h = boot(); const pending = h.api.projects.createFlatJob('91', { count: 2 });
  const failed = assert.rejects(pending, err => err.code === 1071000002 && !err.designTerminalRejected);
  await flush(); const key = keyOf(h.calls[0]);
  h.calls[0].success({ statusCode: 200, data: { code: 1071000002, msg: 'other operation validation' } }); await failed;
  await assert.rejects(h.api.projects.createFlatJob('91', { count: 4 }), err => err.code === 'DESIGN_INTENT_CONFLICT');
  assert.equal(h.calls.length, 1);
  const retry = h.api.projects.createFlatJob('91', { count: 2 }); await flush(); assert.equal(keyOf(h.calls[1]), key);
  success(h.calls[1], { jobId: '92' }); await retry;
});

test('terminal budget rejection cannot reuse a project with changed frozen dimensions, even if recreation times out', async () => {
  let h = boot(); Object.assign(h.page.data.form, { area: '120', regionCode: '3301', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush(); success(h.calls[0], { projectId: '91' }); await flush();
  h.calls[1].success({ statusCode: 200, data: { code: 1071000002, msg: 'no rule' } }); await flush();
  h.page.data.form.width = '11'; h.page.data.form.regionCode = '3302'; h.page.platCalculate(); await flush();
  assert.ok(h.calls[2].url.endsWith('/projects')); const newKey = keyOf(h.calls[2]); timeout(h.calls[2]); await flush();
  h = boot(h.storage); Object.assign(h.page.data.form, { width: '11', area: '120', regionCode: '3302', structureType: 'BRICK', materialGrade: 'A' });
  h.page.platCalculate(); await flush();
  assert.ok(h.calls[0].url.endsWith('/projects')); assert.equal(keyOf(h.calls[0]), newKey);
  success(h.calls[0], { projectId: '93' }); await flush(); assert.ok(h.calls[1].url.endsWith('/projects/93/budget-estimates'));
  success(h.calls[1], { estimateId: '94', totalLowCents: 10000, totalHighCents: 20000 }); await flush();
});

test('explicit rejected budget key remains bound to its original payload', async () => {
  const h = boot(); const pending = h.api.budget.estimate('91', { regionCode: '3301' }, 'explicit-budget-key');
  const failed = assert.rejects(pending, err => err.designTerminalRejected === true);
  await flush(); h.calls[0].success({ statusCode: 200, data: { code: 1071000002, msg: 'no rule' } }); await failed;
  await assert.rejects(h.api.budget.estimate('91', { regionCode: '3302' }, 'explicit-budget-key'), err => err.code === 'DESIGN_INTENT_CONFLICT');
  assert.equal(h.calls.length, 1);
});

test('page polish uses the real API; selection/favorite late replies cannot update UI after logout', async () => {
  const h = boot(); h.page.platNotePolish(); await flush();
  assert.ok(h.calls[0].url.endsWith('/note-polish')); success(h.calls[0], { polished: '润色结果' }); await flush();
  assert.equal(h.page.data.form.note, '润色结果');
  h.page.data.platResult = { kind: 'plan', projectId: '91', jobId: '92', candidates: [{ candidateId: '93' }], selected: 0 };
  h.page.platSelectCandidate({ currentTarget: { dataset: { index: 0 } } }); await flush();
  h.http.clearSession(); const before = clone(h.page.data), toastCount = h.toasts.length;
  success(h.calls[1], { resultVersionId: '94' }); await flush();
  assert.deepEqual(h.page.data, before); assert.equal(h.toasts.length, toastCount);
});
