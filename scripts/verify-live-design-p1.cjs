const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const ROOT = require('node:path').resolve(__dirname, '..');

function loadExplorePage({ token, post, mode }) {
  const redirects = [];
  let page;
  const source = fs.readFileSync(ROOT + '/miniprogram/pages/workflow/explore/index.js', 'utf8');
  const sandbox = {
    Page: definition => { page = definition; },
    wx: { redirectTo: options => { redirects.push(options.url); } },
    require(request) {
      if (request.endsWith('/api/http')) return { token: () => token };
      if (request.endsWith('/api/auth')) return { currentApiPost: () => post };
      if (request.endsWith('/utils/platform-design')) return { pageMode: () => mode };
      if (request === './platform-bridge') return target => { target.platTeardown = () => {}; target.platOnLoadInit = () => {}; };
      if (request.endsWith('/api/franchise/design')) return {};
      throw new Error('unexpected require: ' + request);
    }
  };
  vm.runInNewContext(source, sandbox, { filename: 'explore/index.js' });
  page.setData = () => {};
  return { page, redirects };
}

test('LIVE-06: authenticated boss is sent to access guidance and onShow stays safe', () => {
  const { page, redirects } = loadExplorePage({ token: 'token', post: { postId: 'boss-1', roleCode: 'boss' }, mode: 'demo' });
  page.onLoad({ view: 'inspiration' });
  assert.match(redirects[0] || '', /view=access/);
  assert.doesNotThrow(() => page.onShow());
});

test('LIVE-06: missing session is still sent to login', () => {
  const { page, redirects } = loadExplorePage({ token: '', post: null, mode: 'demo' });
  page.onLoad({ view: 'inspiration' });
  assert.match(redirects[0] || '', /view=login/);
});

function makeUploadHarness({ ticket, uploadTicket, putResponse, completeResponse, sessionGeneration }) {
  const originalWx = global.wx;
  const calls = { requests: [], complete: [] };
  const bytes = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10]).buffer;
  let page;
  global.wx = {
    chooseMedia(options) { options.success({ tempFiles: [{ tempFilePath: '/tmp/reference.png' }] }); },
    getFileSystemManager() {
      return { readFile(options) { options.success({ data: bytes }); } };
    },
    request(options) {
      calls.requests.push(options);
      options.success(putResponse || { statusCode: 200, data: '' });
    }
  };
  delete require.cache[require.resolve('../miniprogram/pages/workflow/explore/platform-bridge')];
  const attach = require('../miniprogram/pages/workflow/explore/platform-bridge');
  let generation = 1;
  const http = {
    base: () => 'https://api2.zszhj.com/admin-api',
    token: () => 'token', currentPostId: () => 'post-1', tenantId: () => '1',
    sessionGeneration: () => sessionGeneration ? sessionGeneration() : generation
  };
  page = {
    data: { form: { referenceAssetIds: [] } },
    __post: { postId: 'post-1' }, __postKey: 'post-1', __http: http,
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        if (key === 'form.referenceAssetIds') this.data.form.referenceAssetIds = value;
        else this.data[key] = value;
      }
    }
  };
  attach(page, {
    PF: {}, http,
    api: { assets: {
      uploadTicket: () => uploadTicket ? uploadTicket() : Promise.resolve(ticket),
      complete: id => { calls.complete.push(String(id)); return Promise.resolve(completeResponse || { uploadStatus: 'ACCEPTED' }); }
    } }
  });
  return {
    page, calls, restore() { global.wx = originalWx; },
    changeSession() { generation += 1; }
  };
}

async function flush() {
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
}

test('reference upload PUTs original bytes then completes the retained ticket', async t => {
  const harness = makeUploadHarness({ ticket: { assetId: '123', uploadUrl: 'https://cos.example/upload' } });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.equal(harness.calls.requests.length, 1);
  assert.equal(harness.calls.requests[0].data.byteLength, 8);
  assert.deepEqual(harness.calls.complete, ['123']);
  assert.deepEqual(harness.page.data.form.referenceAssetIds, ['123']);
});

test('reference upload resolves the independent-B authenticated proxy without duplicating /admin-api', async t => {
  const harness = makeUploadHarness({ ticket: { assetId: '124', uploadUrl: '/admin-api/franchise/assets/124/content?token=opaque' } });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.equal(harness.calls.requests[0].url, 'https://api2.zszhj.com/admin-api/franchise/assets/124/content?token=opaque');
  assert.equal(harness.calls.requests[0].header.Authorization, 'Bearer token');
  assert.deepEqual(harness.calls.complete, ['124']);
});

test('reference upload stops on PUT non-2xx and surfaces the failure', async t => {
  const harness = makeUploadHarness({
    ticket: { assetId: '125', uploadUrl: 'https://cos.example/upload' },
    putResponse: { statusCode: 403, data: '' }
  });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.deepEqual(harness.calls.complete, []);
  assert.match(harness.page.data.platformErr || '', /HTTP 403/);
});

test('reference upload rejects a completed asset that failed validation', async t => {
  const harness = makeUploadHarness({
    ticket: { assetId: '126', uploadUrl: 'https://cos.example/upload' },
    completeResponse: { uploadStatus: 'REJECTED', rejectedReason: 'SHA_MISMATCH' }
  });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.deepEqual(harness.page.data.form.referenceAssetIds, []);
  assert.match(harness.page.data.platformErr || '', /SHA_MISMATCH/);
});

test('reference upload does not fall back to the old A private asset endpoint', async t => {
  const harness = makeUploadHarness({ ticket: { assetId: '127', uploadUrl: 'local://user-references/127.jpg' } });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.equal(harness.calls.requests.length, 0);
  assert.deepEqual(harness.calls.complete, []);
  assert.match(harness.page.data.platformErr || '', /B 素材上传通道/);
});

test('reference upload rejects an absolute old A development endpoint too', async t => {
  const harness = makeUploadHarness({ ticket: { assetId: '129', uploadUrl: 'https://api.example/app-api/design/v1/assets/129/content' } });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  await flush();
  assert.equal(harness.calls.requests.length, 0);
  assert.deepEqual(harness.calls.complete, []);
  assert.match(harness.page.data.platformErr || '', /B 素材上传通道/);
});

test('reference upload stops before PUT when the session changes after ticket issue', async t => {
  let releaseTicket;
  const ticketPromise = new Promise(resolve => { releaseTicket = resolve; });
  const harness = makeUploadHarness({ uploadTicket: () => ticketPromise });
  t.after(() => harness.restore());
  harness.page.platAddReference();
  harness.changeSession();
  releaseTicket({ assetId: '128', uploadUrl: 'https://cos.example/upload' });
  await flush();
  assert.equal(harness.calls.requests.length, 0);
  assert.deepEqual(harness.calls.complete, []);
});
