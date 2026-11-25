const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), { createRequire } = require('node:module');
const finance = require('../miniprogram/api/finance');
function harness(options = {}) {
  const filename = path.resolve(__dirname, '../miniprogram/pages/external-handover/upload.js'), real = createRequire(filename), module = { exports: {} };
  let current = true, reads = 0, puts = 0; const posts = [], bytes = options.data || Buffer.from('%PDF-1.7\nexternal document');
  const http = { post: async (...args) => { posts.push(args); return options.post ? options.post(...args) : args[0].endsWith('upload-tickets') ? { assetId: '900719925474099999', uploadUrl: 'https://store.test/one-use' } : { uploadStatus: 'ACCEPTED' }; } };
  const mocks = { '../../api/http': http, '../../api/finance': { ...finance, readFileBase64: async () => { reads++; return bytes.toString('base64'); } },
    '../../utils/b-asset-upload': { putTicketBytes: async value => { puts++; if (options.put) await options.put(value); } } };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, require: name => name in mocks ? mocks[name] : real(name), wx: { base64ToArrayBuffer: value => { const data = Buffer.from(value, 'base64'); return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength); } } }, { filename });
  return { api: module.exports, posts, alive: () => current, revoke: () => { current = false; }, counts: () => ({ reads, puts }) };
}
test('handover confirmation uploads only BUSINESS_DOCUMENT with measured bytes/digest and string asset ID', async () => {
  const h = harness(), file = await h.api.run({ file: { path: '/external.pdf', name: '测量资料.pdf' } }, h.alive);
  assert.equal(h.posts[0][0], '/franchise/assets/upload-tickets'); assert.equal(h.posts[0][1].assetType, 'BUSINESS_DOCUMENT');
  assert.equal(h.posts[0][1].mimeType, 'application/pdf'); assert.equal(h.posts[0][1].sizeBytes, Buffer.byteLength('%PDF-1.7\nexternal document'));
  assert.match(h.posts[0][1].sha256, /^[a-f0-9]{64}$/); assert.equal(file.assetId, '900719925474099999'); assert.equal(h.posts[1][0], '/franchise/assets/900719925474099999/complete');
});
test('stored PUT with lost response confirms the original asset and never consumes the ticket twice', async () => {
  let stored = false; const h = harness({ put: async () => { assert.equal(stored, false); stored = true; throw Error('response lost'); },
    post: async url => url.endsWith('upload-tickets') ? { assetId: '88', uploadUrl: 'https://store.test/one-use' } : (assert.equal(stored, true), { uploadStatus: 'ACCEPTED' }) });
  assert.equal((await h.api.run({ file: { path: '/external.pdf' } }, h.alive)).assetId, '88'); assert.deepEqual(h.counts(), { reads: 1, puts: 1 });
});
test('truly missing bytes require explicit new ticket; retries only complete original asset and retain bytes', async () => {
  let tickets = 0, stored = false;
  const h = harness({ put: async () => { if (tickets === 1) throw Error('network offline'); stored = true; }, post: async url => {
    if (url.endsWith('upload-tickets')) return { assetId: String(87 + ++tickets), uploadUrl: 'https://store.test/' + tickets };
    if (!stored) throw { code: 1071000001, msg: '资产尚未上传完成，请重试' }; return { uploadStatus: 'ACCEPTED' };
  } });
  const pending = { file: { path: '/external.pdf' } }; await assert.rejects(h.api.run(pending, h.alive)); assert.equal(pending.restartRequired, true);
  await assert.rejects(h.api.run(pending, h.alive)); assert.equal(tickets, 1); assert.equal(h.counts().puts, 1);
  const replacement = { file: pending.file, bytes: pending.bytes, mimeType: pending.mimeType };
  assert.equal((await h.api.run(replacement, h.alive)).assetId, '89'); assert.deepEqual(h.counts(), { reads: 1, puts: 2 });
});
test('complete network failure and pending scan retain asset without re-PUT or requesting extra tickets', async () => {
  let completed = 0; const h = harness({ post: async url => {
    if (url.endsWith('upload-tickets')) return { assetId: '88', uploadUrl: 'https://store.test/one-use' };
    if (++completed === 1) throw Error('offline'); return { uploadStatus: completed === 2 ? 'PENDING' : 'ACCEPTED' };
  } }); const pending = { file: { path: '/external.pdf' } };
  await assert.rejects(h.api.run(pending, h.alive)); await assert.rejects(h.api.run(pending, h.alive), /正在验收/);
  assert.equal((await h.api.run(pending, h.alive)).assetId, '88'); assert.deepEqual(h.counts(), { reads: 1, puts: 1 }); assert.equal(h.posts.filter(args => args[0].endsWith('upload-tickets')).length, 1);
});
test('30MiB, non-empty allowed formats and rejected scan are enforced before associating document', async () => {
  const large = harness(); await assert.rejects(large.api.run({ file: { path: '/large.pdf', size: 30 * 1024 * 1024 + 1 } }, large.alive), /30MB/); assert.equal(large.posts.length, 0);
  const empty = harness({ data: Buffer.alloc(0) }); await assert.rejects(empty.api.run({ file: { path: '/empty.pdf' } }, empty.alive), /大于0/); assert.equal(empty.posts.length, 0);
  const fake = harness({ data: Buffer.from('not an image') }); await assert.rejects(fake.api.run({ file: { path: '/fake.png' } }, fake.alive)); assert.equal(fake.posts.length, 0);
  const rejected = harness({ post: async url => url.endsWith('upload-tickets') ? { assetId: '88', uploadUrl: 'https://store.test/one-use' } : { uploadStatus: 'REJECTED' } });
  const pending = { file: { path: '/external.pdf' } }; await assert.rejects(rejected.api.run(pending, rejected.alive), /未通过/); await assert.rejects(rejected.api.run(pending, rejected.alive), /重新选择/);
  assert.equal(rejected.posts.length, 2); assert.equal(pending.rejected, true);
});
test('session change during file I/O or PUT prevents ticket/complete calls for another identity', async () => {
  const h = harness({ put: async () => h.revoke() }); await assert.rejects(h.api.run({ file: { path: '/external.pdf' } }, h.alive), /身份/);
  assert.equal(h.posts.length, 1); const before = harness(); before.revoke(); await assert.rejects(before.api.run({ file: { path: '/external.pdf' } }, before.alive), /身份/); assert.equal(before.posts.length, 0);
});
