// Regression: role redirects must not keep the native profile in its initial empty state.
const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');

const storage = new Map();
const definitions = new Map();
const nativePages = new Map();
let registeredPage, registeredBar, pages, transitions, navigation, requests, toasts;
let failNavigation = false;

global.Page = definition => { registeredPage = definition; };
global.Component = definition => { registeredBar = definition; };
global.getApp = () => ({ globalData: {} });
global.getCurrentPages = () => pages;
function navigate(kind, options) {
  navigation.push({ kind, url: options.url });
  if (failNavigation) return options.fail?.({ errMsg: 'fixture: navigation failed' });
  transitions.push(options.url);
}
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  getStorageInfoSync: () => ({ keys: [...storage.keys()] }),
  request: options => requests.push(options), // Keep responses pending; no real network.
  switchTab: options => navigate('switchTab', options),
  redirectTo: options => navigate('redirectTo', options),
  navigateTo: options => navigate('navigateTo', options),
  reLaunch: options => navigate('reLaunch', options),
  showToast: options => toasts.push(options.title)
};

const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const account = require('../miniprogram/utils/account');
for (const route of ['/pages/profile/index', '/pages/admin/profile/index', '/pages/account/sales/index']) {
  require(path.join(__dirname, '../miniprogram', route + '.js'));
  definitions.set(route, registeredPage);
}
require('../miniprogram/custom-tab-bar/index');

function setData(patch, callback) {
  for (const [key, value] of Object.entries(patch)) {
    const keys = key.split('.');
    let target = this.data;
    for (const part of keys.slice(0, -1)) target = target[part] ??= {};
    target[keys.at(-1)] = value;
  }
  callback?.();
}

function reset(role) {
  storage.clear();
  nativePages.clear();
  pages = []; transitions = []; navigation = []; requests = []; toasts = [];
  failNavigation = false;
  if (!role) return;
  http.saveSession({ token: 'profile-navigation-fixture', postId: '42' });
  auth.savePosts([{ postId: 42, orgId: 7, roleCode: role,
    orgType: role === 'hq-design-admin' ? 'headquarters' : 'store', lead: role.endsWith('-lead') }]);
  account.mutate(state => { state.profile.name = '缓存姓名'; });
}

function show(route) {
  const definition = definitions.get(route);
  assert.ok(definition, 'unexpected destination: ' + route);
  let page = nativePages.get(route);
  const firstLoad = !page;
  if (!page) {
    page = { ...definition, route: route.slice(1), options: {},
      data: structuredClone(definition.data || {}), setData };
    if (route === '/pages/profile/index') {
      nativePages.set(route, page);
      page.bar = { ...registeredBar.methods, data: structuredClone(registeredBar.data),
        properties: { activeIndex: -1, nested: false }, setData };
    }
    page.getTabBar = () => page.bar || null;
  }
  pages = [page];
  if (firstLoad) page.onLoad?.({});
  page.onShow?.();
  return page;
}

function settleNavigation() {
  for (let step = 0; transitions.length && step < 12; step++) show(transitions.shift());
  assert.equal(transitions.length, 0, 'profile navigation did not settle within 12 transitions');
  return pages.at(-1);
}

const roles = [
  ['boss', '老板'], ['owner', '门店负责人'], ['sales', '业务员'], ['sales-lead', '业务负责人'],
  ['engineer', '工程人员'], ['project-manager', '工程负责人'],
  ['finance', '财务专员'], ['finance-lead', '财务负责人'], ['hq-design-admin', '设计行政']
];

for (const [role, label] of roles) {
  test(role + ': 我的在请求未完成时直接显示岗位与缓存资料，不离开原生页', () => {
    reset(role);
    const page = show('/pages/profile/index');
    settleNavigation();
    assert.equal(navigation.length, 0, 'native profile must not redirect into a legacy role shell');
    assert.equal(page.data.role, label);
    assert.equal(page.data.name, '缓存姓名');
    assert.equal(page.data.loggedIn, true);
    assert.equal(page.bar.data.selected, 4);
    assert.ok(requests.some(request => request.url.endsWith('/platform/profile/mine')));
  });
}

for (const role of ['boss', 'owner', 'sales', null]) {
  for (const route of ['/pages/admin/profile/index', '/pages/account/sales/index']) {
    test((role || 'guest') + ': 旧入口 ' + route + ' 单向回到原生页', () => {
      reset(role);
      show(route);
      const page = settleNavigation();
      assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/profile/index' }]);
      assert.equal(page.route, 'pages/profile/index');
      assert.equal(page.data.loggedIn, !!role);
    });
  }
}

for (const role of ['boss', 'owner', 'sales']) {
  test(role + ': 连续往返与重复点击当前我的项，不累积页面或再次重定向', () => {
    reset(role);
    const original = show('/pages/profile/index');
    settleNavigation();
    for (let i = 0; i < 20; i++) {
      pages = [{ route: 'pages/business/index' }];
      original.bar.refresh();
      navigation = [];
      original.bar.switchTab({ currentTarget: { dataset: { index: 4 } } });
      const current = settleNavigation();
      assert.equal(current, original, 'reuse the native profile instance');
      assert.equal(pages.length, 1);
      assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/profile/index' }]);
      navigation = [];
      current.bar.switchTab({ currentTarget: { dataset: { index: 4 } } });
      assert.equal(navigation.length, 0, 'tapping the selected profile must not navigate again');
    }
  });
}

test('游客直达我的显示登录引导，不触发资料请求', () => {
  reset(null);
  const page = show('/pages/profile/index');
  settleNavigation();
  assert.equal(page.data.loggedIn, false);
  assert.equal(navigation.length, 0);
  assert.equal(requests.length, 0);
});

for (const route of ['/pages/admin/profile/index', '/pages/account/sales/index']) {
  test(route + ': 兼容入口跳转失败时提示重试并记录错误', () => {
    reset('boss');
    failNavigation = true;
    const errors = [];
    const originalError = console.error;
    console.error = (...args) => errors.push(args);
    try {
      show(route);
    } finally {
      console.error = originalError;
    }
    assert.equal(navigation.length, 1);
    assert.ok(toasts.some(message => message.includes('重试')));
    assert.equal(errors.length, 1);
    assert.equal(transitions.length, 0);
  });
}
