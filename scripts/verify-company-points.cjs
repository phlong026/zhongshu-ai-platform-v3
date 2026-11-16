const assert = require('node:assert/strict');
const test = require('node:test');

const storage = {};
const requests = [];
global.wx = {
  getStorageSync(key) { return storage[key] || ''; },
  setStorageSync(key, value) { storage[key] = value; },
  removeStorageSync(key) { delete storage[key]; },
  request(options) {
    requests.push(options);
    options.success({ statusCode: 200, data: { code: 0, data: {} } });
  }
};

const http = require('../miniprogram/api/http');
const points = require('../miniprogram/api/company-points');

test('overlapping company point reads share in-flight requests without caching completed balances', async () => {
  const originalRequest = wx.request, captured = [];
  wx.request = options => { captured.push(options); };
  http.saveSession({ token: 'unit-token', postId: 'unit-post', tenantId: 'unit-tenant' });
  try {
    const a = points.summary(), b = points.summary();
    const c = points.myUsage({ page: 1, size: 1 }), d = points.myUsage({ page: 1, size: 1 });
    assert.equal(captured.length, 2);
    captured[0].success({ statusCode: 200, data: { code: 0, data: { balance: 10 } } });
    captured[1].success({ statusCode: 200, data: { code: 0, data: { used: 2 } } });
    assert.deepEqual(await Promise.all([a,b,c,d]), [{ balance: 10 },{ balance: 10 },{ used: 2 },{ used: 2 }]);
    const fresh = points.summary();
    assert.equal(captured.length, 3);
    captured[2].success({ statusCode: 200, data: { code: 0, data: { balance: 9 } } });
    assert.equal((await fresh).balance, 9);
  } finally { wx.request = originalRequest; }
});

test('in-flight company point reads separate queries and posts and retry after failure', async () => {
  const originalRequest = wx.request, captured = [];
  wx.request = options => { captured.push(options); };
  http.saveSession({ token: 'unit-token', postId: 'unit-old', tenantId: 'unit-tenant' });
  try {
    const old = points.summary();
    const rejected = assert.rejects(old, error => error.code === points.STALE);
    http.saveSession({ token: 'unit-token', postId: 'unit-new', tenantId: 'unit-tenant' });
    const next = points.summary(), pageOne = points.myUsage({ page: 1 }), pageTwo = points.myUsage({ page: 2 });
    assert.equal(captured.length, 4);
    captured[0].success({ statusCode: 200, data: { code: 0, data: { balance: 999 } } });
    captured[1].success({ statusCode: 200, data: { code: 0, data: { balance: 7 } } });
    captured[2].success({ statusCode: 200, data: { code: 0, data: { used: 1 } } });
    captured[3].success({ statusCode: 200, data: { code: 0, data: { used: 2 } } });
    await rejected;
    assert.deepEqual(await Promise.all([next,pageOne,pageTwo]), [{ balance: 7 },{ used: 1 },{ used: 2 }]);
    const failed = points.summary();
    captured[4].success({ statusCode: 200, data: { code: 500, msg: 'unit failure' } });
    await assert.rejects(failed);
    const retry = points.summary();
    assert.equal(captured.length, 6);
    captured[5].success({ statusCode: 200, data: { code: 0, data: { balance: 6 } } });
    assert.equal((await retry).balance, 6);
  } finally { wx.request = originalRequest; }
});

test('company point API uses the platform session and current post', async () => {
  http.saveSession({ token: 'platform-token', postId: 'post-9', tenantId: 'tenant-2' });
  await points.summary();
  await points.myUsage({ month: '2026-11', page: 2, size: 10 });

  assert.equal(requests[0].url.endsWith('/admin-api/franchise/company-points/summary'), true);
  assert.equal(requests[0].header.Authorization, 'Bearer platform-token');
  assert.equal(requests[0].header['X-Post-Id'], 'post-9');
  assert.equal(requests[1].url.includes('/franchise/company-points/my-usage?month=2026-11&page=2&size=10'), true);
});

test('late company point response is rejected after a post switch', async () => {
  let resolveRequest;
  wx.request = (options) => { resolveRequest = options.success; };
  http.saveSession({ token: 'platform-token', postId: 'post-a', tenantId: 'tenant-2' });
  const pending = points.summary();
  http.saveSession({ token: 'platform-token', postId: 'post-b', tenantId: 'tenant-2' });
  resolveRequest({ statusCode: 200, data: { code: 0, data: { balance: 99 } } });
  await assert.rejects(pending, (error) => error && error.code === points.STALE);
});

test('company point formatter preserves BIGINT values exactly and rejects unsafe numbers', () => {
  assert.equal(points.formatCredits('9223372036854775807'), '9,223,372,036,854,775,807');
  assert.equal(points.formatCredits('0001200'), '1,200');
  assert.equal(points.formatCredits(9007199254740991), '9,007,199,254,740,991');
  assert.throws(() => points.formatCredits(9007199254740992), /INVALID_CREDIT_VALUE/);
  assert.throws(() => points.formatCredits('12.5'), /INVALID_CREDIT_VALUE/);
  assert.throws(() => points.formatCredits(' 12'), /INVALID_CREDIT_VALUE/);
});

test('point asset card keeps visible loading, error and ready states without wallet navigation', () => {
  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');

  assert.equal(definition.data.state, 'loading');
  assert.equal(typeof definition.methods.retry, 'function');
  assert.equal('goToRecharge' in definition.methods, false);
  assert.equal('goToRecords' in definition.methods, false);
});

test('point asset card renders active company balance and personal monthly usage', async () => {
  points.summary = () => Promise.resolve({ balance: '9223372036854775807', status: 'ACTIVE' });
  points.myUsage = () => Promise.resolve({ used: '345' });
  http.saveSession({ token: 'platform-token', postId: 'post-ready', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(instance.data.state, 'ready');
  assert.equal(instance.data.balanceText, '9,223,372,036,854,775,807');
  assert.equal(instance.data.usedText, '345');
});

test('point asset card shows suspended accounts explicitly and rejects unknown status', async () => {
  points.summary = () => Promise.resolve({ balance: '100', status: 'SUSPENDED' });
  points.myUsage = () => Promise.resolve({ used: '20' });
  http.saveSession({ token: 'platform-token', postId: 'post-suspended', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(instance.data.state, 'suspended');
  assert.equal(instance.data.balanceLabel, '账户余额');

  points.summary = () => Promise.resolve({ balance: '100', status: 'FUTURE_STATE' });
  const originalWarn = console.warn;
  console.warn = () => {};
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  console.warn = originalWarn;
  assert.equal(instance.data.state, 'error');
});

test('point asset card exposes a retryable error without showing backend details', async () => {
  points.summary = () => Promise.reject({ code: 500, message: 'database detail' });
  points.myUsage = () => Promise.resolve({ used: 1 });
  http.saveSession({ token: 'platform-token', postId: 'post-error', tenantId: 'tenant-2' });

  let definition;
  global.Component = (value) => { definition = value; };
  delete require.cache[require.resolve('../miniprogram/components/point-asset-card/index')];
  require('../miniprogram/components/point-asset-card/index');
  const instance = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data),
    setData(next) { Object.assign(this.data, next); }
  });
  const originalWarn = console.warn;
  console.warn = () => {};
  instance.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  console.warn = originalWarn;

  assert.equal(instance.data.state, 'error');
  assert.equal(instance.data.errorMessage, '积分信息加载失败，请稍后重试');
});
