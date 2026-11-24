const assert = require('node:assert/strict');
const test = require('node:test');
const pending = require('../miniprogram/pages/studio/publication-pending');

function fixture(options = {}) {
  const storage = options.storage || new Map(), calls = [], routes = [], drafts = [], toasts = [];
  let actor = { tenant: '1', user: '10', org: '20', post: '30', live: true };
  const page = () => ({
    actorScope: 'post-30', __closed: false,
    data: { view: 'publish', workId: '80', title: '原标题', publishText: '原文案', tags: '',
      publicationAccount: '门店账号', selectedAccount: 0, publishSubmitting: false },
    setData(patch) { Object.assign(this.data, patch); },
    persistEditor() { drafts.push(structuredClone(this.data)); },
    go(view, suffix) { routes.push([view, suffix]); }
  });
  const env = {
    wx: {
      getStorageSync: key => storage.get(key),
      setStorageSync(key, value) { if (options.failStorage) throw Error('存储空间不足'); storage.set(key, structuredClone(value)); },
      removeStorageSync: key => storage.delete(key),
      showToast: value => toasts.push(value.title)
    },
    http: { token: () => actor.live ? 'token' : '', currentUserId: () => actor.user,
      tenantId: () => actor.tenant, sessionGuard: () => () => actor.live },
    get post() { return { orgId: actor.org, postId: actor.post }; },
    studio: { scope: () => 'post-' + actor.post, publicationPlatforms: [{ platform: 'douyin' }, { platform: 'channels' }] },
    validate: () => '', message: error => error.message,
    api: { publications: { create: async (body, key) => {
      calls.push({ body: structuredClone(body), key });
      return options.create ? options.create(body, key, calls.length) : { id: '91' };
    } } }
  };
  return { page, env, storage, calls, routes, drafts, toasts, switchActor: patch => Object.assign(actor, patch) };
}

test('closing and reopening replays the durable request while preserving newly edited input', async () => {
  const f = fixture({ create: async (body, key, n) => { if (n === 1) throw { code: -1 }; return { id: '91' }; } });
  const first = f.page();
  await pending.submit(first, f.env);
  first.__closed = true;
  const reopened = f.page();
  reopened.data.title = '重新编辑的标题';
  pending.restore(reopened, f.env);
  assert.equal(reopened.data.publicationPending, true);
  await pending.submit(reopened, f.env);
  assert.equal(f.calls.length, 1, 'new input must not silently replace or replay the pending request');
  await pending.resume(reopened, f.env);
  assert.deepEqual(f.calls[1], f.calls[0]);
  assert.equal(f.drafts.at(-1).title, '重新编辑的标题');
  assert.deepEqual(f.routes, [['receipt', '&record=91']]);
});

test('pending requests are isolated across user, tenant, organization and post', async () => {
  const f = fixture({ create: async () => { throw { code: -1 }; } });
  await pending.submit(f.page(), f.env);
  for (const field of ['tenant', 'user', 'org', 'post']) {
    const original = { tenant: '1', user: '10', org: '20', post: '30' }[field];
    f.switchActor({ [field]: '999' });
    assert.equal(pending.read(f.env, '80'), null, field);
    f.switchActor({ [field]: original });
  }
  assert.ok(pending.read(f.env, '80'));
});

test('storage failure stops creation before any server mutation', async () => {
  const f = fixture({ failStorage: true });
  await pending.submit(f.page(), f.env);
  assert.equal(f.calls.length, 0);
  assert.match(f.toasts[0], /存储/);
});

test('a success arriving after page exit is recoverable without creating again', async () => {
  let resolve;
  const f = fixture({ create: () => new Promise(done => { resolve = done; }) });
  const first = f.page(), flight = pending.submit(first, f.env);
  first.__closed = true;
  resolve({ id: '92' });
  await flight;
  assert.equal(f.routes.length, 0);
  const reopened = f.page();
  pending.restore(reopened, f.env);
  await pending.resume(reopened, f.env);
  assert.equal(f.calls.length, 1);
  assert.deepEqual(f.routes, [['receipt', '&record=92']]);
});

test('a duplicate response offers the existing record and preserves the edited draft', async () => {
  const f = fixture({ create: async () => { throw { code: 1114000013, data: {
    existingPublicationId: '93', status: 'published', sameInput: false } }; } });
  const page = f.page();
  await pending.submit(page, f.env);
  assert.equal(page.data.publicationExistingId, '93');
  assert.equal(page.data.publicationExistingStatus, 'published');
  assert.equal(page.data.publicationPending, false);
  assert.equal(f.routes.length, 0);
  assert.equal(f.drafts[0].title, '原标题');
  assert.equal(pending.read(f.env, '80'), null);
});

test('a definitive validation failure allows a corrected request with a fresh key', async () => {
  const f = fixture({ create: async (body, key, n) => { if (n === 1) throw { code: 400, message: '请修改标题' }; return { id: '94' }; } });
  const page = f.page();
  await pending.submit(page, f.env);
  page.data.title = '修改后的标题';
  await pending.submit(page, f.env);
  assert.notEqual(f.calls[0].key, f.calls[1].key);
  assert.equal(f.calls[1].body.title, '修改后的标题');
});

for (const status of ['published', 'scheduled']) {
  test('a ' + status + ' duplicate is viewable without an incorrect link-entry prompt', async () => {
    const f = fixture({ create: async () => { throw { code: 1114000013, data: {
      existingPublicationId: '95', status, sameInput: true } }; } });
    const page = f.page();
    await pending.submit(page, f.env);
    assert.match(page.data.operationError, /查看/);
    assert.doesNotMatch(page.data.operationError, /填写/);
  });
}
