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
  transitions.push({ kind, url: options.url, options });
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
const storeProfileRoute = '/pages/account/store-profile/index';
const nativeRoutes = ['/pages/home/index', '/pages/business/index', '/pages/engineering/index', '/pages/management/index', '/pages/profile/index'];
for (const route of [...nativeRoutes, '/pages/creation/index', '/pages/design/index', '/pages/customers/index', '/pages/admin/profile/index', '/pages/account/sales/index', storeProfileRoute]) {
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
  auth.saveLogin({ accessToken: 'profile-navigation-fixture', userId: 701 });
  auth.savePosts([{ postId: 42, userId: 701, orgId: 7, roleCode: role,
    orgType: role === 'hq-design-admin' ? 'headquarters' : 'store', lead: role.endsWith('-lead') }]);
  auth.switchPost(42);
  account.mutate(state => { state.profile.name = '缓存姓名'; });
}

function show(route) {
  const parsed = new URL(route, 'https://fixture.invalid');
  route = parsed.pathname;
  const options = Object.fromEntries(parsed.searchParams);
  const definition = definitions.get(route);
  assert.ok(definition, 'unexpected destination: ' + route);
  let page = nativePages.get(route);
  const firstLoad = !page;
  if (!page) {
    page = { ...definition, route: route.slice(1), options,
      data: structuredClone(definition.data || {}), setData };
    if (nativeRoutes.includes(route)) {
      nativePages.set(route, page);
      page.bar = { ...registeredBar.methods, data: structuredClone(registeredBar.data),
        properties: { activeIndex: -1, nested: false }, setData };
    }
    page.getTabBar = () => page.bar || null;
  }
  if (pages.at(-1) !== page) pages.at(-1)?.onHide?.();
  pages = [page];
  if (firstLoad) page.onLoad?.(options);
  page.onShow?.();
  return page;
}

function settleNavigation() {
  for (let step = 0; transitions.length && step < 12; step++) {
    const next = transitions.shift();
    if (next.kind === 'reLaunch') {
      pages.at(-1)?.onUnload?.();
      nativePages.clear();
    }
    show(next.url);
    next.options.success?.();
    next.options.complete?.();
  }
  assert.equal(transitions.length, 0, 'profile navigation did not settle within 12 transitions');
  return pages.at(-1);
}

const originalStore = { orgId: '7', name: '青松建房', ownerName: '方明', address: '', version: 4, editOwnerName: true };
function profileRequest(method) {
  const req = requests.shift();
  assert.equal(req?.method, method);
  assert.ok(req.url.endsWith('/platform/org/store-profile'));
  return req;
}
function reply(req, data, code = 0, msg = '') {
  req.success({ statusCode: 200, data: { code, data: structuredClone(data), msg } });
}
function editProfile(page, field, value) {
  page.edit({ currentTarget: { dataset: { field } }, detail: { value } });
}

test('加盟商资料切回保留草稿，重名后可改名重试，版本过期才需重载', async () => {
  reset('boss');
  const page = show(storeProfileRoute);
  reply(profileRequest('GET'), originalStore);
  await new Promise(resolve => setImmediate(resolve));
  editProfile(page, 'name', '重名门店');
  page.onHide();
  page.onShow();
  assert.equal(requests.length, 0);
  assert.equal(page.data.profile.name, '重名门店');
  const address = '南宁市青秀区青松路8号';
  editProfile(page, 'address', address);
  const rejected = page.save();
  reply(profileRequest('PUT'), null, 1101000025, '组织名称已被使用，请更换名称');
  await rejected;
  assert.equal(page.data.stale, false);
  assert.equal(page.data.saving, false);
  assert.match(page.data.error, /更换名称/);
  assert.equal(page.data.profile.address, address);

  const name = '青松 建房（南宁店）';
  editProfile(page, 'name', name);
  const retry = page.save();
  const put = profileRequest('PUT');
  assert.deepEqual(put.data, { orgId: '7', version: 4, ownerName: '方明', name, address });
  page.onHide();
  reply(put, { ...originalStore, name, address, version: 5 });
  await retry;
  page.onShow();
  assert.equal(page.data.profile.version, 5);
  assert.equal(page.data.saving, false);
  assert.equal(auth.currentApiPost().orgName, name);
  assert.equal(requests.length, 0);

  editProfile(page, 'address', '过期草稿');
  const conflict = page.save();
  reply(profileRequest('PUT'), null, 409, '资料已变更，请重新载入');
  await conflict;
  page.onHide();
  page.onShow();
  assert.equal(page.data.stale, true);
  assert.match(page.data.error, /重新载入/);
  await page.save();
  assert.equal(requests.length, 0);
  const reload = page.load();
  reply(profileRequest('GET'), { ...originalStore, name, address, version: 6 });
  await reload;
  assert.equal(page.data.stale, false);
  assert.equal(page.data.profile.address, address);
  assert.equal(page.data.profile.version, 6);
});

test('切换门店立即清空草稿，旧身份的保存结果不能覆盖新门店', async () => {
  reset('boss');
  const page = show(storeProfileRoute);
  reply(profileRequest('GET'), originalStore);
  await new Promise(resolve => setImmediate(resolve));
  editProfile(page, 'address', '原门店草稿');
  const saving = page.save();
  const oldPut = profileRequest('PUT');
  page.onHide();
  auth.switchPost('43');
  const switched = page.onShow();
  assert.equal(page.data.profile, null);
  const newGet = profileRequest('GET');
  assert.equal(newGet.header['X-Post-Id'], '43');
  const otherStore = { ...originalStore, orgId: '8', name: '柳州门店', address: '柳州', version: 1 };
  reply(newGet, otherStore);
  await switched;
  reply(oldPut, { ...originalStore, name: '旧店回包', version: 5 });
  await saving;
  assert.deepEqual(page.data.profile, otherStore);
  assert.notEqual(auth.apiPosts()[0].orgName, '旧店回包');
  assert.deepEqual(toasts, []);
});

const roles = [
  ['boss', '老板'], ['owner', '门店负责人'], ['sales', '业务员'], ['sales-lead', '业务负责人'],
  ['engineer', '工程人员'], ['project-manager', '工程负责人'],
  ['finance', '财务专员'], ['finance-lead', '财务负责人'], ['hq-design-admin', '设计行政']
];

for (const [role, label] of roles) {
  test(role + ': 我的在请求未完成时直接显示岗位与同用户缓存资料，不离开原生页', () => {
    reset(role);
    const page = show('/pages/profile/index');
    settleNavigation();
    assert.equal(navigation.length, 0, 'native profile must not redirect into a legacy role shell');
    assert.equal(page.data.role, label);
    assert.equal(account.load().profile.apiUserId, '701', '缓存归属来自当前登录用户');
    assert.equal(page.data.name, '缓存姓名');
    assert.equal(page.data.loggedIn, true);
    assert.equal(page.data.showStoreServices, true);
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
  assert.equal(page.data.showStoreServices, true);
  assert.equal(page.bar.data.tabs.find(tab => tab.index === 4).text, '我的');
  assert.equal(page.bar.data.tabs.find(tab => tab.index === 4).url, '/pages/profile/index');
  assert.equal(navigation.length, 0);
  assert.equal(requests.length, 0);
});

test('门店登录后加盟商服务的两个入口仍直达对应展示页', () => {
  reset('boss');
  const page = show('/pages/profile/index');
  assert.equal(page.data.showStoreServices, true);
  for (const action of ['services', 'selection']) page.open({ currentTarget: { dataset: { action } } });
  assert.deepEqual(navigation, [
    { kind: 'navigateTo', url: '/pages/account/index?view=services' },
    { kind: 'navigateTo', url: '/pages/account/index?view=selection' }
  ]);
});

for (const role of ['hq-admin','hq-design-admin','hq-operations','hq-operations-lead','finance','finance-lead','boss']) {
  test('总部 '+role+'：全品牌地图仅开放管理员与财务，查看入口不依赖编辑权限', () => {
    reset('hq-design-admin');
    auth.savePosts([{postId:42,userId:701,orgId:7,orgType:'headquarters',roleCode:role,permissions:[]}]);
    auth.switchPost(42);
    const page=show('/pages/profile/index');
    const allowed=['hq-admin','finance','finance-lead'].includes(role);
    assert.equal(page.data.showStoreServices,!allowed);assert.equal(page.data.showAdminMap,allowed);assert.equal(page.data.showStoreMap,allowed);
    navigation=[];
    for(const action of ['services','selection','storeMap'])page.open({currentTarget:{dataset:{action}}});
    assert.deepEqual(navigation.map(row=>row.url),[
      ...(allowed?['/pages/account/store-map/index']:['/pages/account/index?view=services','/pages/account/index?view=selection'])
    ]);
  });
}

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
  const unreadReq = requests.filter(r => r.url.endsWith('/platform/messages/center/summary')).at(-1);
  assert.ok(unreadReq, '未读数请求已发出');
  http.saveSession({ token: 'profile-navigation-fixture-next', userId: 702, postId: '43' }); // 新会话代次
  unreadReq.success({ statusCode: 200, data: { code: 0, data: { count: 7 } } });
  await new Promise(r => setImmediate(r)); // 等 promise 链落地
  assert.equal(page.data.unread, 0, '迟到响应不得更新未读角标');
});

test('未知岗位 roleCode：workbench-nav 回退本地导航并告警一次', () => {
  reset(null);
  http.saveSession({ token: 'profile-navigation-fixture', userId: 701, postId: '42' });
  auth.savePosts([{ postId: 42, userId: 701, orgId: 7, roleCode: 'brand-new-role', orgType: 'store', lead: false }]);
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

// F01: exercise actual route callers, native wrappers and customer requests together.
const nav = require('../miniprogram/utils/page-navigation');
const tap = dataset => ({ currentTarget: { dataset } });
const customerRequests = () => requests.filter(request => request.url.endsWith('/franchise/customers'));
test('销售旧方案入口直接进入灵感检索，不再打开退役的原生方案页', () => {
  reset('sales');
  show('/pages/business/index');
  nav.open('/pages/design/index');
  assert.deepEqual(navigation, [{ kind: 'navigateTo', url: '/design-migration/pages/library/index' }]);

  reset('sales');
  show('/pages/design/index');
  assert.deepEqual(navigation, [{ kind: 'redirectTo', url: '/design-migration/pages/library/index' }]);
});

test('销售直达旧原生方案页回首页，工程与老板继续使用原工程入口', () => {
  reset('sales');
  const retired = show('/pages/engineering/index');
  assert.equal(retired.data.redirectingSales, true);
  const destination = settleNavigation();
  assert.equal(destination.route, 'pages/business/index');
  assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/business/index' }]);
  assert.equal(destination.bar.data.tabs.some(tab => tab.index === 6), false);

  reset('engineer');
  show('/pages/engineering/index');
  assert.deepEqual(navigation, [{ kind: 'reLaunch', url: '/pages/build/home/index' }]);

  reset('boss');
  const engineering = show('/pages/engineering/index');
  assert.equal(engineering.data.redirectingSales, false);
  assert.deepEqual(navigation, []);
  assert.ok(requests.some(request => request.url.includes('/franchise/engineering/projects')));
});

test('灵感首页入口可用，销售工具冷启动返回首页，预算入口行为保持原样', () => {
  reset('sales');
  const business = show('/pages/business/index');
  const inspiration = business.data.salesShortcuts.find(row => row.action === 'inspiration');
  assert.equal(inspiration.title, '灵感检索');
  business.openSalesShortcut(tap(inspiration));
  assert.deepEqual(navigation, [{ kind: 'navigateTo', url: '/design-migration/pages/library/index' }]);
  assert.equal(nav.parent({ route: 'pages/workflow/explore/index', data: { view: 'budget' } }), '/pages/business/index');
  assert.equal(business.data.salesShortcuts.find(row => row.action === 'quickBudget').url, '/pages/workflow/explore/index?view=budget');

  reset('hq-design-admin');
  assert.equal(nav.parent({ route: 'pages/workflow/explore/index', data: { view: 'inspiration' } }), '/pages/design/index');
});

async function finishCreationAuthorization() {
  for (const request of requests.filter(request => request.url.endsWith('/platform/posts/mine'))) {
    reply(request, auth.apiPosts());
  }
  await new Promise(resolve => setImmediate(resolve));
}

for (const role of ['sales', 'sales-lead']) {
  test(role + ': 首页内容创作快捷入口一次切到原生 Tab，无兼容中转页', async () => {
    reset(role);
    const business = show('/pages/business/index');
    requests = [];
    business.openSalesShortcut(tap({ action: 'remix' }));
    const creation = settleNavigation();
    await finishCreationAuthorization();
    assert.equal(creation.route, 'pages/home/index');
    assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/home/index' }]);
  });
}

test('客户统计入口在首次请求前应用筛选，只导航一次、只加载一次', () => {
  reset('sales');
  const business = show('/pages/business/index');
  requests = [];
  business.openSalesMetric(tap({ focus: 'due' }));
  const customers = settleNavigation();
  assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/management/index' }]);
  assert.equal(customers.data.tab, 'due');
  assert.equal(customerRequests().length, 1);
  assert.equal(customerRequests()[0].data.followFilter, 'DUE');
});

test('旧客户外部直链仍可兼容：参数在正常 onShow 前生效且不手动重复生命周期', () => {
  reset('sales');
  show('/pages/customers/index?focus=appointments&intent=commission');
  const customers = settleNavigation();
  assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/management/index' }]);
  assert.equal(customers.data.intent, 'commission');
  assert.equal(customers.data.tab, 'appointments');
  assert.equal(customerRequests().length, 1);
  assert.equal(customerRequests()[0].data.followFilter, 'APPOINTMENTS');
});

test('已激活客户 Tab 仍能消费一次新入口参数，无重新导航且不丢委托意图', () => {
  reset('sales');
  const customers = show('/pages/management/index');
  requests = [];
  nav.open('/pages/customers/index?focus=due&intent=commission');
  settleNavigation();
  assert.deepEqual(navigation, []);
  assert.equal(customers.data.tab, 'due');
  assert.equal(customers.data.intent, 'commission');
  assert.equal(customerRequests().length, 1);
  requests = [];
  nav.open('/pages/customers/index?focus=all');
  assert.equal(customers.data.tab, 'all');
  assert.equal(customers.data.intent, '');
  assert.equal(customerRequests().length, 1);
  assert.equal(customerRequests()[0].data.followFilter, undefined);
});

test('首页全部客户入口重访已有 Tab 会取消旧筛选', () => {
  reset('sales');
  const customers = show('/pages/management/index');
  customers.setData({ tab: 'due' });
  const business = show('/pages/business/index');
  requests = [];
  business.openSalesMetric(tap({ focus: 'all' }));
  assert.equal(settleNavigation(), customers);
  assert.equal(customers.data.tab, 'all');
  assert.equal(customerRequests().length, 1);
  assert.equal(customerRequests()[0].data.followFilter, undefined);
});

test('创作 editor 意图只消费一次，保留当前会话草稿且不通过 switchTab query 传参', async () => {
  reset('sales');
  const studio = require('../miniprogram/utils/studio');
  const draftKey = studio.scopedKey(require('../miniprogram/utils/storage-keys').creationDraft);
  wx.setStorageSync(draftKey, { text: '当前岗位的文案草稿' });
  show('/pages/business/index');
  requests = [];
  nav.open('/pages/creation/index?editor=1');
  const creation = settleNavigation();
  await finishCreationAuthorization();
  assert.deepEqual(navigation, [{ kind: 'switchTab', url: '/pages/home/index' }]);
  assert.equal(creation.data.sheet, 'editor');
  assert.equal(creation.data.editorText, '当前岗位的文案草稿');
  creation.setData({ sheet: '' });
  show('/pages/profile/index');
  show('/pages/home/index');
  await finishCreationAuthorization();
  assert.equal(creation.data.sheet, '', '返回页面不能再次打开已消费的编辑器');
  navigation = [];
  nav.open('/pages/creation/index?editor=1');
  assert.deepEqual(navigation, []);
  assert.equal(creation.data.sheet, 'editor', '当前 Tab 也可直接打开编辑器');
});

test('待处理入口不能跨会话、跨岗位或失败导航泄漏', () => {
  reset('sales');
  pages = [{ route: 'pages/business/index', options: {}, data: {} }];
  nav.open('/pages/customers/index?focus=due&intent=commission');
  auth.savePosts([{ postId: 43, userId: 702, orgId: 8, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ token: 'new-session', userId: 702, postId: '43' });
  const customers = settleNavigation();
  assert.equal(customers.data.tab, 'all');
  assert.equal(customers.data.intent, '');
  assert.equal(customerRequests().length, 1);
  reset('sales');
  pages = [{ route: 'pages/business/index', options: {}, data: {} }];
  failNavigation = true;
  nav.open('/pages/customers/index?focus=due&intent=commission');
  failNavigation = false;
  const retry = show('/pages/management/index');
  assert.equal(retry.data.tab, 'all');
  assert.equal(retry.data.intent, '');
});

test('已激活 Tab 换会话后重新初始化，再应用新会话意图', () => {
  reset('sales');
  const old = show('/pages/management/index');
  old.setData({ keyword: '旧账号搜索词' });
  auth.savePosts([{ postId: 43, userId: 702, orgId: 8, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ token: 'replacement-session', userId: 702, postId: '43' });
  requests = [];
  nav.open('/pages/customers/index?focus=due&intent=commission');
  const current = settleNavigation();
  assert.notEqual(current, old);
  assert.deepEqual(navigation, [{ kind: 'reLaunch', url: '/pages/management/index' }]);
  assert.equal(current.data.keyword, '');
  assert.equal(current.data.tab, 'due');
  assert.equal(current.data.intent, 'commission');
  assert.equal(customerRequests().length, 1);
  assert.equal(customerRequests()[0].header['X-Post-Id'], '43');
});

test('创作授权等待中离开页面会取消入口意图，之后返回不重放编辑器', async () => {
  reset('sales');
  show('/pages/business/index');
  requests = [];
  nav.open('/pages/creation/index?editor=1');
  const creation = settleNavigation();
  assert.notEqual(creation.data.sheet, 'editor', '授权完成前不应用编辑器意图');
  show('/pages/profile/index');
  await finishCreationAuthorization();
  show('/pages/home/index');
  await finishCreationAuthorization();
  assert.notEqual(creation.data.sheet, 'editor');
});

test('已有创作 Tab 换岗位后入口编辑器只读取新岗位草稿', async () => {
  reset('sales');
  const studio = require('../miniprogram/utils/studio');
  wx.setStorageSync(studio.scopedKey(require('../miniprogram/utils/storage-keys').creationDraft), { text: '旧岗位草稿' });
  const creation = show('/pages/home/index');
  await finishCreationAuthorization();
  show('/pages/business/index');
  auth.savePosts([{ postId: 43, userId: 702, orgId: 8, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ token: 'another-actor', userId: 702, postId: '43' });
  requests = [];
  nav.open('/pages/creation/index?editor=1');
  assert.equal(settleNavigation(), creation);
  await finishCreationAuthorization();
  assert.equal(creation.data.sheet, 'editor');
  assert.equal(creation.data.editorText, '');
  assert.equal(creation.data.draft, null);
});

test('创作授权挂起时换会话，新入口重新授权且旧请求不能阻断新编辑器', async () => {
  reset('sales');
  const oldPosts = auth.apiPosts();
  const oldPage = show('/pages/home/index');
  const oldRequest = requests.find(request => request.url.endsWith('/platform/posts/mine'));
  auth.savePosts([{ postId: 43, userId: 702, orgId: 8, roleCode: 'sales', orgType: 'store' }]);
  http.saveSession({ token: 'pending-replacement', userId: 702, postId: '43' });
  requests = [];
  nav.open('/pages/creation/index?editor=1');
  const current = settleNavigation();
  assert.deepEqual(navigation, [{ kind: 'reLaunch', url: '/pages/home/index' }]);
  assert.notEqual(current, oldPage);
  assert.equal(requests.filter(request => request.url.endsWith('/platform/posts/mine')).length, 1);
  reply(oldRequest, oldPosts);
  await new Promise(resolve => setImmediate(resolve));
  await finishCreationAuthorization();
  assert.equal(current.data.sheet, 'editor');
  assert.equal(auth.currentApiPost().postId, 43);
});

test('创作首次授权503后，再次打开同Tab入口会重试并应用新意图', async () => {
  reset('sales');
  const oldPage = show('/pages/home/index');
  const failed = requests.find(request => request.url.endsWith('/platform/posts/mine'));
  reply(failed, null, 503, '网络暂不可用');
  await new Promise(resolve => setImmediate(resolve));
  requests = [];
  nav.open('/pages/creation/index?editor=1');
  const current = settleNavigation();
  assert.deepEqual(navigation, [{ kind: 'reLaunch', url: '/pages/home/index' }]);
  assert.notEqual(current, oldPage);
  assert.equal(requests.filter(request => request.url.endsWith('/platform/posts/mine')).length, 1);
  await finishCreationAuthorization();
  assert.equal(current.data.sheet, 'editor');
});

test('同岗位编号更新角色而会话代次未变时，不重放旧角色入口', () => {
  reset('sales');
  pages = [{ route: 'pages/business/index', options: {}, data: {} }];
  nav.open('/pages/customers/index?focus=due&intent=commission');
  const generation = http.sessionGeneration();
  auth.savePosts(auth.apiPosts().map(post => ({ ...post, roleCode: 'sales-lead' })));
  assert.equal(http.sessionGeneration(), generation);
  const customers = settleNavigation();
  assert.equal(customers.data.isLead, true);
  assert.equal(customers.data.tab, 'all');
  assert.equal(customers.data.intent, '');
});

test('老板客户与创作独立页保持原路由，不错误映射成业务岗 Tab', () => {
  reset('boss');
  pages = [{ route: 'pages/profile/index', options: {}, data: {} }];
  nav.open('/pages/customers/index?focus=due');
  assert.deepEqual(navigation, [{ kind: 'navigateTo', url: '/pages/customers/index?focus=due' }]);
  const customers = settleNavigation();
  assert.equal(customers.route, 'pages/customers/index');
  assert.equal(customers.data.tab, 'due');
  navigation = [];
  nav.open('/pages/creation/index?editor=1');
  assert.deepEqual(navigation, [{ kind: 'navigateTo', url: '/pages/creation/index?editor=1' }]);
});

test('管理入口按独立的管理台配置复制，未配置时不拼接口地址', () => {
  reset('hq-admin');
  auth.savePosts([{postId:42,userId:701,orgId:7,orgType:'headquarters',roleCode:'hq-admin'}]);
  auth.switchPost(42);
  const env=require('../miniprogram/config/env'), original=env.ADMIN_WEB_BASE;
  const profile=require('../miniprogram/pages/profile/profile-page')(4);
  const copies=[];
  wx.showModal=options => options.success({confirm:true});
  wx.setClipboardData=options => copies.push(options.data);
  try {
    for (const [base, expected] of [
      ['https://same.example/admin/','https://same.example/admin'],
      ['https://admin.example','https://admin.example']
    ]) {
      env.ADMIN_WEB_BASE=base;
      for (const [label,suffix] of [['公告管理','/platform-announcements'],['加盟商名录','/platform-org/onboard']]) {
        profile.open({currentTarget:{dataset:{admin:label}}});
        assert.equal(copies.at(-1),expected+suffix);
      }
    }
    assert.equal(copies.length,4);
    env.ADMIN_WEB_BASE='';
    profile.open({currentTarget:{dataset:{admin:'公告管理'}}});
    assert.equal(copies.length,4);
    assert.match(toasts.at(-1),/管理台地址未配置/);
  } finally {
    env.ADMIN_WEB_BASE=original;
    delete wx.showModal;
    delete wx.setClipboardData;
  }
});
