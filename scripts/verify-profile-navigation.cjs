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

// ---- P1-1（2026-11-10）：home() 对 API 岗位直达专属工作台——不再 switchTab 落在中转 tab
// 等 onShow 二次导航（与登录/切岗 openApiHome 同型；真机 switchTab:fail timeout 同源隐患）。
for (const [role, expected] of [
  ['boss', { kind: 'reLaunch', url: '/pages/admin/home/index' }],
  ['owner', { kind: 'reLaunch', url: '/pages/admin/home/index' }],
  ['finance', { kind: 'reLaunch', url: '/pages/admin/finance-desk/index' }],
  ['engineer', { kind: 'reLaunch', url: '/pages/build/home/index' }],
  ['hq-design-admin', { kind: 'reLaunch', url: '/pages/workflow/commission/index?view=admin' }],
  ['sales', { kind: 'switchTab', url: '/pages/business/index' }],
]) {
  test(role + ': home() 直达岗位首页，不经中转 tab 二次导航', () => {
    reset(role);
    pages = [{ route: 'pages/workflow/index' }];
    navigation = [];
    require('../miniprogram/utils/page-navigation').home();
    assert.deepEqual(navigation, [expected]);
  });
}

test('无 API 会话：home() 保持 switchTab 本地首页', () => {
  reset(null);
  pages = [{ route: 'pages/workflow/index' }];
  navigation = [];
  require('../miniprogram/utils/page-navigation').home();
  assert.equal(navigation.length, 1);
  assert.equal(navigation[0].kind, 'switchTab');
});

test('我的页未读数响应带会话守卫：换会话后迟到响应不回写旧角标', async () => {
  reset('sales');
  const page = show('/pages/profile/index');
  settleNavigation();
  const unreadReq = requests.filter(r => r.url.endsWith('/platform/messages/unread-count')).at(-1);
  assert.ok(unreadReq, '未读数请求已发出');
  http.saveSession({ token: 'profile-navigation-fixture-next', postId: '42' }); // 新会话代次
  unreadReq.success({ statusCode: 200, data: { code: 0, data: { count: 7 } } });
  await new Promise(r => setImmediate(r)); // 等 promise 链落地
  assert.equal(page.data.unread, 0, '迟到响应不得更新未读角标');
});

test('未知岗位 roleCode：workbench-nav 回退本地导航并告警一次', () => {
  reset(null);
  http.saveSession({ token: 'profile-navigation-fixture', postId: '42' });
  auth.savePosts([{ postId: 42, orgId: 7, roleCode: 'brand-new-role', orgType: 'store', lead: false }]);
  const warnings = [];
  const originalWarn = console.warn;
  console.warn = (...args) => warnings.push(args.join(' '));
  try {
    const nav = require('../miniprogram/utils/workbench-nav')();
    assert.equal(nav.guest, true, '未知岗位回退本地(游客)导航');
    require('../miniprogram/utils/workbench-nav')(); // 第二次调用不重复告警
  } finally {
    console.warn = originalWarn;
  }
  assert.equal(warnings.filter(w => w.includes('brand-new-role')).length, 1, '同岗位只告警一次');
});
