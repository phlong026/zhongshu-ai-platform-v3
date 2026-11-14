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
