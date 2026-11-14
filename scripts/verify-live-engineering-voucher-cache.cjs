const assert = require('node:assert/strict');
const test = require('node:test');

const storage = Object.create(null);
const files = Object.create(null);
let nextAssetId = 7000;
let ticketCalls = 0;

global.wx = {
  getStorageSync(key) { return storage[key] === undefined ? '' : storage[key]; },
  setStorageSync(key, value) { storage[key] = value; },
  removeStorageSync(key) { delete storage[key]; },
  getFileSystemManager() {
    return {
      readFile({ filePath, success }) { success({ data: files[filePath] || '' }); },
      statSync(filePath) { return { size: Buffer.from(files[filePath] || '', 'base64').length }; }
    };
  },
  base64ToArrayBuffer(value) {
    const bytes = Uint8Array.from(Buffer.from(value, 'base64'));
    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  },
  request(options) { options.success({ statusCode: 204, data: '' }); }
};

const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const engineering = require('../miniprogram/api/franchise/engineering');

http.post = async path => {
  if (path.endsWith('/upload-tickets')) {
    ticketCalls += 1;
    const assetId = ++nextAssetId;
    return { assetId, uploadUrl: 'https://cos.example/' + assetId };
  }
  if (path.endsWith('/complete')) {
    const assetId = Number(path.split('/').slice(-2)[0]);
    return { assetId, uploadStatus: 'ACCEPTED' };
  }
  throw new Error('unexpected POST ' + path);
};

function login(post, token) {
  auth.savePosts(post ? [post] : []);
  http.saveSession({ token: token || 'token', postId: post ? post.postId : '', tenantId: '1' });
}

test('engineering voucher cache is scoped to the exact authenticated post and session', async () => {
  const engineer = { postId: 'post-engineer', userId: 'user-9', orgId: 'org-1', roleCode: 'engineer' };
  const finance = { postId: 'post-finance', userId: 'user-9', orgId: 'org-1', roleCode: 'finance' };
  files['/tmp/same.jpg'] = 'YWJj';
  login(engineer, 'token-a');

  const first = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  const sameSession = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  assert.equal(sameSession, first, '同身份、同会话、同摘要应复用已完成资产');
  assert.equal(ticketCalls, 1);

  auth.savePosts([engineer, finance]);
  auth.switchPost(finance.postId);
  const afterPostSwitch = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  assert.notEqual(afterPostSwitch, first, '同组织换岗不得复用旧岗位资产');
  assert.equal(ticketCalls, 2);

  login(finance, 'token-b');
  const afterRelogin = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  assert.notEqual(afterRelogin, afterPostSwitch, '重新登录即使岗位相同也不得复用上一会话资产');
  assert.equal(ticketCalls, 3);

  login(null, 'token-without-post');
  const noPostFirst = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  const noPostSecond = await engineering.uploadVoucher('/tmp/same.jpg', 'ENG_DRAWING');
  assert.notEqual(noPostSecond, noPostFirst, '无法确认当前任职时不得查询或写入共享缓存');
  assert.equal(ticketCalls, 5);
});

test('engineering voucher cache key includes tenant, user, org, type, path and sha', async () => {
  const base = { postId: 'post-components', userId: 'user-a', orgId: 'org-a', roleCode: 'engineer' };
  const before = ticketCalls;
  files['/tmp/components.jpg'] = 'YWJj';
  files['/tmp/components-copy.jpg'] = 'Yg==';
  login(base, 'token-components');

  const original = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_DRAWING');
  files['/tmp/components.jpg'] = 'Yg==';
  const changedSha = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_DRAWING');
  assert.notEqual(changedSha, original);

  const changedType = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_SITE_PHOTO');
  assert.notEqual(changedType, changedSha);
  const changedPath = await engineering.uploadVoucher('/tmp/components-copy.jpg', 'ENG_DRAWING');
  assert.notEqual(changedPath, changedSha);

  storage['zs-api-tenant-v1'] = '2';
  const changedTenant = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_DRAWING');
  assert.notEqual(changedTenant, changedSha);

  storage['zs-api-tenant-v1'] = '1';
  auth.savePosts([{ ...base, userId: 'user-b' }]);
  const changedUser = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_DRAWING');
  assert.notEqual(changedUser, changedSha);

  auth.savePosts([{ ...base, orgId: 'org-b' }]);
  const changedOrg = await engineering.uploadVoucher('/tmp/components.jpg', 'ENG_DRAWING');
  assert.notEqual(changedOrg, changedSha);
  assert.equal(ticketCalls, before + 7, '任一缓存键分量变化都必须重新签票');
});
