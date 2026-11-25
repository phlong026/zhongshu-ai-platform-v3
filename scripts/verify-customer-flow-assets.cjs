const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const copy = value => JSON.parse(JSON.stringify(value));
function load(file, requires, wx = {}) {
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), { module, exports: module.exports, require: name => requires[name], wx });
  return module.exports;
}

test('客户流程API契约在写入携带原幂等键，下载限定客户/步骤/资产', async () => {
  const calls = [], http = Object.fromEntries(['get', 'post', 'put'].map(method => [method, (...args) => { calls.push({ method, args: copy(args) }); return Promise.resolve({}); }]));
  const api = load('miniprogram/pages/customer-flow/api.js', { '../../api/http': http });
  await api.get('42'); await api.save('42', 'QUOTATION', { revision: 4 }, 'same-save-key');
  await api.complete('42', 'QUOTATION', { revision: 5 }, 'same-complete-key');
  await api.reopen('42', 'QUOTATION', { revision: 6, reason: '客户调整报价' }, 'reopen-key');
  await api.records('42', 2); await api.download('42', 'QUOTATION', '900719925474099999');
  assert.deepEqual(calls[1], { method: 'put', args: ['/franchise/customers/42/workflow/steps/QUOTATION', { revision: 4 }, { 'Idempotency-Key': 'same-save-key' }] });
  assert.equal(calls[2].args[0], '/franchise/customers/42/workflow/steps/QUOTATION/complete');
  assert.equal(calls[2].args[2]['Idempotency-Key'], 'same-complete-key');
  assert.equal(calls[3].args[1].reason, '客户调整报价');
  assert.deepEqual(calls[4].args, ['/franchise/customers/42/workflow/records', { pageNo: 2, pageSize: 20 }]);
  assert.deepEqual(calls[5].args, ['/franchise/customers/42/workflow/steps/QUOTATION/assets/900719925474099999/download-tickets', {}]);
});

function uploadHarness(options = {}) {
  const finance = require('../miniprogram/api/finance'), posts = [];
  let reads = 0, puts = 0, alive = true;
  const data = options.data || Buffer.from('%PDF-1.4\nfixture document');
  const http = { post: async (url, body) => {
    posts.push({ url, body });
    if (options.post) return options.post(url, body, posts);
    return url.endsWith('upload-tickets') ? { assetId: '900719925474099999', uploadUrl: 'https://upload.test/file' } : { uploadStatus: 'ACCEPTED' };
  } };
  const api = load('miniprogram/pages/customer-flow/upload.js', {
    '../../api/http': http,
    '../../api/finance': { readFileBase64: async () => { reads++; return data.toString('base64'); }, sha256Hex: finance.sha256Hex, voucherMime: finance.voucherMime },
    '../../utils/b-asset-upload': { putTicketBytes: async args => { puts++; if (options.put) await options.put(args); } }
  }, { base64ToArrayBuffer: value => Uint8Array.from(Buffer.from(value, 'base64')).buffer });
  return { api, posts, counts: () => ({ reads, puts }), alive: () => alive, switchSession: () => alive = false };
}

test('业务资料完整校验后才能入表，complete超时重试复用原资产和已上传字节', async () => {
  let completes = 0;
  const h = uploadHarness({ post: async url => {
    if (url.endsWith('upload-tickets')) return { assetId: '900719925474099999', uploadUrl: 'https://upload.test/file' };
    if (++completes === 1) throw { code: -1, message: 'complete timeout' };
    return { uploadStatus: 'ACCEPTED' };
  } });
  const pending = { file: { path: '/local.pdf', name: '报价V2.pdf', size: 25 } };
  await assert.rejects(h.api.run(pending, h.alive));
  assert.equal(pending.uploaded, true); assert.equal(pending.ticket.assetId, '900719925474099999');
  const file = await h.api.run(pending, h.alive);
  assert.deepEqual(copy(file), { assetId: '900719925474099999', title: '报价V2.pdf', mimeType: 'application/pdf' });
  assert.deepEqual(h.counts(), { reads: 1, puts: 1 });
  assert.equal(h.posts.filter(p => p.url.endsWith('upload-tickets')).length, 1);
  assert.equal(h.posts[0].body.assetType, 'BUSINESS_DOCUMENT'); assert.equal(h.posts[0].body.sizeBytes, 25);
  assert.match(h.posts[0].body.sha256, /^[a-f0-9]{64}$/);
  assert.equal(h.posts[1].url, h.posts[2].url);
});

test('超大/空/不支持的资料在拿票前拒绝，重试不能绕过MIME校验', async () => {
  const h = uploadHarness({ data: Buffer.from('RIFF unsupported.webp') });
  const pending = { file: { path: '/photo.webp', name: 'photo.webp', size: 20 } };
  await assert.rejects(h.api.run(pending, h.alive), /JPG/);
  await assert.rejects(h.api.run(pending, h.alive), /JPG/);
  assert.equal(h.posts.length, 0); assert.equal(pending.bytes, undefined);
  const large = uploadHarness();
  await assert.rejects(large.api.run({ file: { path: '/large.pdf', size: 30 * 1024 * 1024 + 1 } }, large.alive), /30MB/);
  assert.equal(large.counts().reads, 0); assert.equal(large.posts.length, 0);
  const empty = uploadHarness({ data: Buffer.alloc(0) });
  await assert.rejects(empty.api.run({ file: { path: '/empty.pdf' } }, empty.alive), /大于0/);
  assert.equal(empty.posts.length, 0);
});

test('被拒绝的资产或切岗后完成的上传都不能返回可关联文件', async () => {
  const reject = uploadHarness({ post: async url => url.endsWith('upload-tickets') ? { assetId: '88', uploadUrl: 'https://upload.test/file' } : { uploadStatus: 'REJECTED' } });
  await assert.rejects(reject.api.run({ file: { path: '/file.pdf' } }, reject.alive), /未通过/);
  const stale = uploadHarness({ put: async () => stale.switchSession() });
  await assert.rejects(stale.api.run({ file: { path: '/file.pdf' } }, stale.alive), /身份/);
  assert.equal(stale.posts.filter(p => p.url.endsWith('/complete')).length, 0);
});

test('上传票据响应损坏后重试能重新取票，不陷入无效票据循环', async () => {
  let ticketAttempts = 0;
  const h = uploadHarness({ post: async url => url.endsWith('upload-tickets')
    ? (++ticketAttempts === 1 ? {} : { assetId: '88', uploadUrl: 'https://upload.test/file' }) : { uploadStatus: 'ACCEPTED' } });
  const pending = { file: { path: '/file.pdf' } };
  await assert.rejects(h.api.run(pending, h.alive), /上传凭据/);
  assert.equal(pending.ticket, undefined); assert.equal((await h.api.run(pending, h.alive)).assetId, '88');
  assert.equal(ticketAttempts, 2);
});
