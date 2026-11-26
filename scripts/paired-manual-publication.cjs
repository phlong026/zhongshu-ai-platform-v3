// Native publication page against the paired Spring controllers and their disposable PostgreSQL database.
const assert = require('node:assert/strict');
const { createInterface } = require('node:readline');
const fixture = JSON.parse(process.argv[2]);
const input = createInterface({ input: process.stdin });
const responses = input[Symbol.asyncIterator]();
const workId = String(fixture.workId);
const storage = new Map([
  ['zs-api-token-v1', 'fixture'], ['zs-api-tenant-v1', String(fixture.sales.tenantId || '1')],
  ['zs-api-user-v1', String(fixture.sales.userId)], ['zs-api-post-v1', String(fixture.sales.postId)],
  ['zs-api-posts-v1', [fixture.sales]]
]);
global.wx = {
  getStorageSync: key => structuredClone(storage.get(key)),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key), showToast() {}, reLaunch() {}
};
const http = require('../miniprogram/api/http');
const api = require('../miniprogram/api/franchise/content');
const studio = require('../miniprogram/utils/studio');
let definition;
global.Page = value => { definition = value; };
require('../miniprogram/pages/studio/index');

async function request(method, path, body, headers = {}) {
  assert.doesNotMatch(path, /\/franchise\/content\/covers(?:[/?]|$)/);
  if (method === 'POST' && path === '/franchise/content/publications') {
    assert.equal(body.workId, workId);
    assert.equal(body.publisherAccountId, String(fixture.accountId));
    assert.equal(Object.hasOwn(body, 'coverAssetId'), false);
  }
  process.stdout.write(JSON.stringify({ actor: 'sales', method, path, body, key: headers['Idempotency-Key'] }) + '\n');
  const reply = await responses.next();
  assert.equal(reply.done, false, 'Missing backend reply');
  const result = JSON.parse(reply.value);
  if (result.code !== 0) throw Object.assign(Error(result.msg || 'Backend rejected the request'), { code: result.code });
  return result.data;
}
http.get = (path, query) => request('GET', path + (query ? '?' + new URLSearchParams(query) : ''));
http.post = (path, body, headers) => request('POST', path, body, headers);

async function scenario() {
  const page = { ...definition, data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } };
  const platformIndex = studio.publicationPlatforms.findIndex(row => row.platform === fixture.platform);
  assert.ok(platformIndex >= 0);
  const state = studio.read();
  state.publishDrafts[workId] = { workId, selectedAccount: platformIndex };
  studio.write(state);
  const closedKey = 'zs-publication-cover-generation-v1:' + encodeURIComponent(JSON.stringify([
    http.tenantId(), http.currentUserId(), String(fixture.sales.orgId), String(fixture.sales.postId)
  ])) + ':' + workId;
  storage.set(closedKey, { workId, key: 'old-cover-job', result: {status:'READY', assetId:fixture.historicalCoverAssetId} });
  page.onLoad({ view: 'publish', work: workId });
  page.onShow();
  const deadline = Date.now() + 12000;
  while ((page.data.workDetailLoading || page.data.publisherAccountsLoading) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.equal(page.data.workDetailFailed, false); assert.equal(page.data.workDetailLoading, false);
  assert.equal(page.data.publisherAccountsLoaded, true, page.data.publisherAccountsError);
  page.selectPublisherAccount({ currentTarget: { dataset: { id: String(fixture.accountId) } } });
  assert.equal(page.data.publisherAccountId, String(fixture.accountId));
  const publishedUrl = 'https://channels.weixin.qq.com/platform/post/acceptance-' + workId;
  page.inputPublishedUrl({ detail: { value: publishedUrl } });
  await page.submitLinkAndLead();
  assert.equal(page.data.linkSaved, true, page.data.operationError);
  const publicationId = page.data.linkRecordId;
  const records = (await api.publications.page()).map(row => page.mapApiPublication(row));
  const current = records.find(row => row.id === publicationId);
  assert.equal(current.status, 'published'); assert.equal(current.publishedUrl, publishedUrl);
  assert.equal(current.coverAssetId, ''); assert.equal(current.workId, workId);
  const history = records.find(row => row.id === String(fixture.historicalPublicationId));
  assert.equal(history.workId, workId); assert.equal(history.status, 'published');
  assert.equal(history.coverAssetId, String(fixture.historicalCoverAssetId)); assert.equal(history.publishedUrl, fixture.historicalUrl);
  const ticket = await api.assets.downloadUrl(history.coverAssetId);
  assert.ok(ticket.downloadUrl);
  process.stdout.write(JSON.stringify({ done: true, publicationId, publishedUrl, historicalDownloadUrl: ticket.downloadUrl }) + '\n');
}
scenario().catch(error => { console.error(error); process.exitCode = 1; })
  .finally(() => { input.close(); process.stdin.destroy(); });
