const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function page(file, modules, wx = {}) {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'), {
    Page(value) { definition = value; },
    require(id) {
      if (id in modules) return modules[id];
      throw Error('Unexpected module ' + id);
    },
    wx, console, Date, Promise, setTimeout, clearTimeout,
    getApp: () => ({ globalData: { windowInfo: { statusBarHeight: 20 } } })
  }, { filename: file });
  return { ...definition, data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('LIVE-08 successful customer refresh clears loading text and preserves a real conflict hint', async () => {
  const http = { sessionGeneration: () => 1 };
  const p = page('miniprogram/pages/workflow/index.js', {
    '../../utils/organization': {}, '../../utils/view-container': {}, '../../api/http': http,
    '../../utils/platform-crm': {}, './views': { titles: {} }
  });
  Object.assign(p, { __post: {}, __postKey: 'p', __http: http,
    __pf: { can: () => true, today: () => '', detailView: () => ({ customer: { name: '测试客户' } }), maskPhone: () => '' },
    __crmApi: { customers: { get: async () => ({}) } } });
  p.loadPlatformDetail('customer');
  await flush();
  assert.equal(p.data.platformStatus, 'ready');
  assert.equal(p.data.error, '');
  p.data.error = '资料已更新，请核对后再提交';
  p.loadPlatformDetail('customer');
  await flush();
  assert.equal(p.data.error, '资料已更新，请核对后再提交');
});

test('LIVE-12 commission list omits absent filters without losing a supplied search', async () => {
  const calls = [];
  let api;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/api/franchise/commission.js'), 'utf8'), {
    require: () => ({ get: (url, data) => calls.push({ url, data }) }), module: { set exports(value) { api = value; } }
  });
  api.commissions.page(1, 20);
  api.commissions.page(1, 20, '张');
  assert.equal(Object.hasOwn(calls[0].data, 'keyword'), false);
  assert.equal(calls[1].data.keyword, '张');
});

test('LIVE-11 expired materials entry returns to login with its destination and no misleading error', () => {
  let token = '', redirects = [], errors = [];
  const p = page('miniprogram/pages/build/materials/index.js', {
    '../../../api/http': { token: () => token }
  }, { redirectTo: options => redirects.push(options) });
  p.onLoad({ id: '9007199254740993', nodeId: 'node-2' });
  assert.match(redirects[0].url, /^\/pages\/account\/index\?view=login&return=/);
  assert.match(decodeURIComponent(redirects[0].url), /9007199254740993/);
  token = 'session'; redirects = [];
  p.retry();
  assert.match(redirects[0].url, /\/pages\/build\/record\/index/);
  token = '';
  redirects[0].fail({ errMsg: 'redirect interrupted by login recovery' });
  assert.equal(p.data.error, '');
  assert.match(redirects.at(-1).url, /view=login/);
});

test('LIVE-07 API employees may open their account information without demo login', () => {
  const redirects = [];
  const state = { session: { loggedIn: false }, profile: {}, drafts: {}, messages: [], feedback: [] };
  const post = { postId: 'p', orgId: 'o', roleCode: 'sales' };
  const account = { load: () => state, company: () => ({ roles: [] }), access: { context: () => ({}) },
    permitted: () => false, services: [{}], order() {}, visible: () => ({}),
    displayOrder() {}, scoped: () => [], roles: {}, courseChapters: {}, chapters: [] };
  const p = page('miniprogram/pages/account/index.js', {
    '../../utils/view-container': { routeValue: v => v || '' },
    '../../api/auth': { currentApiPost: () => post, apiPosts: () => [post] },
    '../../api/http': { token: () => 'session' }, '../../api/org': {},
    '../../utils/organization': { roles: {}, has: () => false }, '../../utils/sample-files': {},
    './procurement': {}, '../../utils/account': account,
    '../../utils/platform-messages': { totalUnread: () => 0 }
  }, { redirectTo: options => redirects.push(options.url) });
  p.options = {}; p.refreshProcurement = () => {}; p.filter = () => {};
  for (const view of ['devices', 'permissions', 'binding', 'personal-performance']) {
    p.data.view = view; p.refresh();
    assert.equal(redirects.some(url => url.includes('view=access')), false, view);
  }
  assert.match(redirects.at(-1), /tasks\/index\?view=mine/);
});

test('LIVE-11 shared expiration recovery opens login instead of the default learning view', () => {
  let unauthorized, target;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/api/auth.js'), 'utf8'), {
    require(id) {
      if (id === './http') return { onUnauthorized: handler => { unauthorized = handler; }, onPostInvalid() {} };
      throw Error('Unexpected module ' + id);
    },
    module: { exports: {} },
    getCurrentPages: () => [{ route: 'pages/build/materials/index' }],
    wx: { reLaunch: options => { target = options.url; } }
  });
  unauthorized();
  assert.match(target, /view=login/);
});
