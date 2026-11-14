const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

function loadPut() {
  const file = path.join(ROOT, 'miniprogram/utils/b-asset-upload.js');
  delete require.cache[require.resolve(file)];
  return require(file);
}

function harness(response = { statusCode: 200, data: { code: 0 } }) {
  const originalWx = global.wx;
  const requests = [];
  global.wx = {
    request(options) {
      requests.push(options);
      if (response instanceof Error) options.fail({ errMsg: response.message });
      else options.success(response);
    }
  };
  const http = {
    base: () => 'https://api2.zszhj.com/admin-api',
    token: () => 'staff-token',
    currentPostId: () => 'post-9',
    tenantId: () => '1'
  };
  return { http, requests, restore: () => { global.wx = originalWx; } };
}

test('B proxy PUT resolves from API origin and carries platform identity', async t => {
  const h = harness();
  t.after(h.restore);
  const { putTicketBytes } = loadPut();
  await putTicketBytes({
    http: h.http,
    uploadUrl: '/admin-api/franchise/assets/123/content?token=opaque',
    bytes: Uint8Array.from([1, 2, 3]).buffer,
    mimeType: 'image/png',
    alive: () => true,
    label: '凭据'
  });
  assert.equal(h.requests[0].url, 'https://api2.zszhj.com/admin-api/franchise/assets/123/content?token=opaque');
  assert.equal(h.requests[0].header.Authorization, 'Bearer staff-token');
  assert.equal(h.requests[0].header['X-Post-Id'], 'post-9');
  assert.equal(h.requests[0].header['tenant-id'], '1');
});

test('HTTPS COS PUT sends original bytes without platform credentials', async t => {
  const h = harness({ statusCode: 204, data: '' });
  t.after(h.restore);
  const { putTicketBytes } = loadPut();
  const bytes = Uint8Array.from([137, 80, 78, 71]).buffer;
  await putTicketBytes({ http: h.http, uploadUrl: 'https://cos.example/signed', bytes,
    mimeType: 'image/png', alive: () => true, label: '凭据' });
  assert.equal(h.requests[0].data, bytes);
  assert.deepEqual(h.requests[0].header, { 'content-type': 'image/png' });
});

test('local placeholders and old A private endpoints fail without any request', async t => {
  const h = harness();
  t.after(h.restore);
  const { putTicketBytes } = loadPut();
  for (const uploadUrl of [
    'local://asset/123',
    '/platform/dev/design/assets/123/bytes',
    'https://api.example/platform/dev/design/assets/123/bytes',
    'https://api.example/app-api/design/v1/assets/123/content'
  ]) {
    await assert.rejects(putTicketBytes({ http: h.http, uploadUrl, bytes: new ArrayBuffer(1),
      mimeType: 'image/jpeg', alive: () => true, label: '凭据' }), /B 素材上传通道/);
  }
  assert.equal(h.requests.length, 0);
});

test('HTTP, B body rejection and session switch are explicit failures', async t => {
  const { putTicketBytes } = loadPut();
  const forbidden = harness({ statusCode: 403, data: '' });
  t.after(forbidden.restore);
  await assert.rejects(putTicketBytes({ http: forbidden.http, uploadUrl: 'https://cos.example/signed',
    bytes: new ArrayBuffer(1), mimeType: 'image/jpeg', alive: () => true, label: '工程凭据' }), /HTTP 403/);
  forbidden.restore();

  const rejected = harness({ statusCode: 200, data: { code: 1100000001, msg: '票据失效' } });
  t.after(rejected.restore);
  await assert.rejects(putTicketBytes({ http: rejected.http,
    uploadUrl: '/admin-api/franchise/assets/123/content?token=opaque', bytes: new ArrayBuffer(1),
    mimeType: 'image/jpeg', alive: () => true, label: '工程凭据' }), /票据失效/);
  rejected.restore();

  let current = true;
  const switched = harness();
  global.wx.request = options => {
    switched.requests.push(options);
    current = false;
    options.success({ statusCode: 200, data: { code: 0 } });
  };
  t.after(switched.restore);
  await assert.rejects(async () => putTicketBytes({ http: switched.http,
    uploadUrl: '/admin-api/franchise/assets/123/content?token=opaque', bytes: new ArrayBuffer(1),
    mimeType: 'image/jpeg', alive: () => current, label: '工程凭据' }), /身份已切换/);
  assert.equal(switched.requests.length, 1, 'PUT 响应返回后换岗应拒绝继续 complete');
});

test('B proxy requires an explicit CommonResult success body while COS may return an empty 2xx body', async t => {
  const { putTicketBytes } = loadPut();
  for (const data of ['', 'not-json', {}, { data: true }]) {
    const malformed = harness({ statusCode: 200, data });
    t.after(malformed.restore);
    await assert.rejects(putTicketBytes({ http: malformed.http,
      uploadUrl: '/admin-api/franchise/assets/123/content?token=opaque', bytes: new ArrayBuffer(1),
      mimeType: 'image/jpeg', alive: () => true, label: '工程凭据' }), /响应无效/);
    malformed.restore();
  }

  const cos = harness({ statusCode: 204, data: '' });
  t.after(cos.restore);
  await putTicketBytes({ http: cos.http, uploadUrl: 'https://cos.example/signed',
    bytes: new ArrayBuffer(1), mimeType: 'image/jpeg', alive: () => true, label: '工程凭据' });
});

test('finance and engineering upload chains use the B PUT helper and contain no A fallback', () => {
  const finance = fs.readFileSync(path.join(ROOT, 'miniprogram/api/finance.js'), 'utf8');
  const engineering = fs.readFileSync(path.join(ROOT, 'miniprogram/api/franchise/engineering.js'), 'utf8');
  for (const source of [finance, engineering]) {
    assert.match(source, /b-asset-upload/);
    assert.match(source, /sessionGuard\(\)/);
    assert.doesNotMatch(source, /platform\/dev\/design\/assets/);
    assert.doesNotMatch(source, /app-api\/design\/v1\/assets/);
  }
  assert.doesNotMatch(finance, /helperUser/);
});
