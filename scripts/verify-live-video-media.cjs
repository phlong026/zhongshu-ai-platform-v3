const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function load(modules, wx) {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/ai-video/index.js'), 'utf8'), {
    Page: value => { definition = value; }, require: id => {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (!(id in modules)) throw Error('Unexpected module ' + id);
      return modules[id];
    }, wx, getCurrentPages: () => [], console, Date, Promise
  });
  return { ...definition, actorScope: 'actor', data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); } };
}
test('selected frames are uploaded and included in the paid task; upload failure creates no task', async () => {
  const sent = [], uploaded = [];
  let fail = false;
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), mapReferences: () => [], degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => true },
    '../../utils/content-media': { upload: async file => {
      uploaded.push(file); if (fail) throw Error('upload failed'); return '9007199254740993';
    } },
    '../../api/franchise/content': { tasks: { create: async body => { sent.push(body); return { id: 'task' }; } } }
  }, { showToast() {}, navigateTo() {}, redirectTo() {} });
  Object.assign(p.data, { description: '庭院', firstFrame: '/tmp/first.jpg', lastFrame: '/tmp/last.jpg' });
  await p.generateOnline();
  assert.equal(uploaded.length, 2);
  assert.deepEqual(JSON.parse(JSON.stringify(sent[0].references)), [
    { role: 'first_frame', assetId: '9007199254740993' }, { role: 'last_frame', assetId: '9007199254740993' }
  ]);
  sent.length = 0; p.__videoSubmission = null; fail = true;
  await p.generateOnline();
  assert.equal(sent.length, 0);
});
test('reference media keeps asset ids and type roles; stale uploads never submit', async () => {
  let alive = true, sent = [], resolveUpload;
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), mapReferences: () => [], degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => alive },
    '../../utils/content-media': { upload: () => new Promise(resolve => { resolveUpload = resolve; }) },
    '../../api/franchise/content': { tasks: { create: async body => { sent.push(body); return { id: 'task' }; } } }
  }, { showToast() {}, navigateTo() {} });
  Object.assign(p.data, { description: '庭院', mode: 'reference', references: [{ type: 'video', path: '/tmp/r.mp4', name: '参考视频' }] });
  const pending = p.generateOnline();
  await Promise.resolve(); alive = false; resolveUpload('a'); await pending;
  assert.equal(sent.length, 0);
});

function uploader(ticketUrl, response = { statusCode: 200, data: '' }) {
  const requests = [], completed = [], tickets = [];
  let upload;
  const http = { base: () => 'https://platform.example/admin-api', token: () => 'private-token',
    currentPostId: () => 'post', tenantId: () => 'tenant' };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/content-media.js'), 'utf8'), {
    module: { set exports(value) { upload = value.upload; } },
    require: id => id === '../api/http' ? http : id === '../api/finance' ? require('../miniprogram/api/finance') : { assets: {
      uploadTicket: async body => { tickets.push(body); return { assetId: '9007199254740993', uploadUrl: ticketUrl }; },
      complete: async id => { completed.push(id); return { status: 'ACCEPTED' }; }
    } },
    wx: { getFileSystemManager: () => ({ readFile: o => o.success({ data: new ArrayBuffer(4) }) }),
      request: o => { requests.push(o); o.success(response); } }, ArrayBuffer, Promise
  });
  return { upload, requests, completed, tickets };
}

test('COS raw PUT sends bytes without platform credentials; B proxy keeps its single admin-api prefix', async () => {
  const cos = uploader('https://bucket.example/object?signed=true');
  assert.equal(await cos.upload('/tmp/a.png', () => true), '9007199254740993');
  assert.equal(cos.requests[0].method, 'PUT');
  assert.equal(cos.requests[0].data.byteLength, 4);
  assert.deepEqual(Object.keys(cos.requests[0].header), ['content-type']);
  assert.equal(cos.tickets[0].sha256, require('node:crypto').createHash('sha256').update(Buffer.alloc(4)).digest('hex'));
  assert.equal(cos.tickets[0].sizeBytes, 4);
  const proxy = uploader('/admin-api/franchise/assets/9007199254740993/content?token=ticket', { statusCode: 200, data: { code: 0 } });
  await proxy.upload('/tmp/a.jpg', () => true);
  assert.equal(proxy.requests[0].url, 'https://platform.example/admin-api/franchise/assets/9007199254740993/content?token=ticket');
  assert.equal(proxy.requests[0].header.Authorization, 'Bearer private-token');
  assert.equal(proxy.requests[0].header['X-Post-Id'], 'post');
  assert.equal(proxy.requests[0].header['tenant-id'], 'tenant');
});

test('task response loss retries the same payload and idempotency key without uploading again', async () => {
  const calls = [], uploads = [];
  const p = load({
    '../../utils/creation': {}, '../../utils/studio': { scope: () => 'actor' },
    '../../utils/platform-content': { mapVideoParameters: () => ({}), degradeMessage: e => e.message },
    '../../api/http': { token: () => 'session', sessionGuard: () => () => true },
    '../../utils/content-media': { upload: async file => { uploads.push(file); return 'asset'; } },
    '../../api/franchise/content': { tasks: { create: async (body, key) => {
      calls.push({ body, key }); if (calls.length === 1) throw Error('timeout'); return { id: 'task' };
    } } }
  }, { showToast() {}, navigateTo() {} });
  Object.assign(p.data, { description: '庭院', firstFrame: '/tmp/first.jpg' });
  await p.generateOnline(); await p.generateOnline();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].key, calls[1].key);
  assert.equal(calls[0].body, calls[1].body);
  assert.equal(uploads.length, 1);
});

test('upload HTTP/business failures and unsupported files never complete an asset', async () => {
  for (const [url, response] of [
    ['https://bucket.example/object', { statusCode: 403 }],
    ['/admin-api/franchise/assets/9007199254740993/content?token=t', { statusCode: 200, data: { code: 401 } }],
    ['local://unsupported', { statusCode: 200 }]
  ]) {
    const u = uploader(url, response);
    await assert.rejects(u.upload('/tmp/a.png', () => true));
    assert.equal(u.completed.length, 0);
  }
  const u = uploader('https://bucket.example/object');
  await assert.rejects(u.upload('/tmp/a.wav', () => true), /暂仅支持/);
  await assert.rejects(u.upload('/tmp/a.png', () => false), /工作身份/);
  assert.equal(u.requests.length, 0);
});

test('video picker indices resolve to selected parameter labels', () => {
  let content;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/platform-content.js'), 'utf8'), {
    require: () => ({}), module: { set exports(value) { content = value; } }
  });
  const value = content.mapVideoParameters([
    { key: 'resolution', options: ['768P', '2K'], value: 1 },
    { key: 'duration', options: ['8 秒', '10 秒'], value: 1 },
    { key: 'ratio', options: ['9:16', '16:9'], value: 1 }
  ]);
  assert.deepEqual(JSON.parse(JSON.stringify(value)), { resolution: '2K', duration: 10, ratio: '16:9' });
});
