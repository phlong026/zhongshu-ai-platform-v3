const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.js'), 'utf8');
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no; }); return { promise, resolve, reject }; };
const flush = () => new Promise(resolve => setImmediate(resolve));

test('员工账号开通 API 使用独立端点并原样提交岗位边界', async () => {
  let api, call;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/api/org.js'), 'utf8'), {
    require: () => ({ post: (url, payload) => { call = { url, payload }; return Promise.resolve({}); } }),
    module: { set exports(value) { api = value; } }
  });
  const payload = { orgId: 'store-1', name: '王五', mobile: '13800000000', roleCode: 'sales', teamId: 'team-1' };
  await api.openStaffAccount(payload);
  assert.equal(call.url, '/platform/org/staff-accounts');
  assert.equal(call.payload, payload);
});

function setPath(target, key, value) {
  const parts = key.split('.');
  let cursor = target;
  while (parts.length > 1) cursor = cursor[parts.shift()] || (cursor[parts[0]] = {});
  cursor[parts[0]] = value;
}

function createPage(overrides = {}) {
  let definition;
  const saved = [], navigated = [], storage = new Map();
  const currentPost = overrides.currentPost || { postId: 'p1', orgId: 'store-1', orgType: 'store', roleCode: 'boss' };
  const auth = {
    appId: () => 'app', login: async () => ({}), accountMatches: async () => ({ items: [] }), accountLogin: async () => ({}),
    saveLogin: result => saved.push(result), myPosts: async () => [currentPost], savePosts() {}, postLoadGuard: () => () => true,
    currentApiPost: () => currentPost, apiPosts: () => [currentPost], bindWechat: async () => ({}), logout() {}, ...overrides.auth
  };
  const http = { token: () => saved.length ? 'token' : '', saveSession() {}, sessionGuard: () => () => true, ...overrides.http };
  const wx = {
    getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
    login: options => options.success({ code: 'wx-code' }), showToast() {}, switchTab: options => navigated.push(options.url),
    reLaunch: options => navigated.push(options.url), redirectTo: options => navigated.push(options.url), ...overrides.wx
  };
  const account = { courseImage: '', services: [], chapters: [], courseChapters: {}, roles: {}, access: { home: () => 'profile', context: () => ({}) } };
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    require(id) {
      const modules = {
        '../../utils/view-container': { routeValue: value => value || '', fail(message) { this.setData({ error: message }); } },
        '../../api/auth': auth, '../../utils/account-login': { defaultAccount: () => null, clearDefault() {}, saveDefault() {}, validNewPassword: () => true, ...overrides.accountLoginUtils },
        '../../api/http': http, '../../api/org': overrides.org || {}, '../../utils/organization': { roles: { boss: '老板', owner: '门店负责人', sales: '业务员', 'sales-lead': '业务负责人' }, templates: { store: ['boss', 'owner', 'sales', 'sales-lead'], headquarters: ['boss', 'owner', 'sales'] }, enabled: () => true, navigation: () => ({ tabs: [{ url: '/pages/admin/index', native: true }] }), apiContext: post => post, ...overrides.organization },
        '../../utils/sample-files': {}, './procurement': { products: [] }, '../../utils/account': account,
        '../../utils/platform-messages': { totalUnread: () => 0 }
      };
      if (id in modules) return modules[id];
      throw Error('Unexpected module ' + id);
    },
    wx, console, Date, Promise, setTimeout, clearTimeout, getApp: () => ({ globalData: { windowInfo: {} } })
  }, { filename: 'miniprogram/pages/account/index.js' });
  const page = { ...definition, data: structuredClone(definition.data), options: {}, setData(patch) { for (const [key, value] of Object.entries(patch)) setPath(this.data, key, value); } };
  page.data.view = 'login'; page.data.checked = true; page.data.form = {};
  return { page, auth, http, wx, saved, navigated };
}

test('切换登录方式会取消迟到的微信登录并复位忙碌态', async () => {
  const pending = deferred();
  const env = createPage({ auth: { login: () => pending.promise } });
  env.page.wechatLogin();
  env.page.selectLoginMode({ currentTarget: { dataset: { mode: 'account' } } });
  pending.resolve({ accessToken: 'late-token' }); await flush(); await flush();
  assert.equal(env.saved.length, 0);
  assert.equal(env.navigated.length, 0);
  assert.equal(env.page.data.loginBusy, false);
});

test('修改查询会取消迟到匹配并复位查询忙碌态', async () => {
  const pending = deferred();
  const env = createPage({ auth: { accountMatches: () => pending.promise } });
  env.page.data.form.accountQuery = 'jxnc'; env.page.findAccounts();
  env.page.input({ currentTarget: { dataset: { key: 'accountQuery' } }, detail: { value: 'lizhong' } });
  pending.resolve({ items: [{ accountId: 'late' }] }); await flush();
  assert.equal(env.page.data.matchBusy, false);
  assert.equal(env.page.data.accountMatches.length, 0);
});

test('匹配结果被截断时要求缩小范围且不自动选择', async () => {
  const env = createPage({ auth: { accountMatches: async () => ({ items: [{ accountId: 'a', loginName: 'a' }], hasMore: true }) } });
  env.page.data.form.accountQuery = 'jxnc'; env.page.findAccounts(); await flush();
  assert.equal(env.page.data.accountMatchesHasMore, true);
  assert.equal(env.page.data.selectedAccount, null);
});

test('登录页onHide取消慢登录并清除密码', async () => {
  const pending = deferred();
  const env = createPage({ auth: { accountLogin: () => pending.promise } });
  env.page.data.selectedAccount = { accountId: 'a', loginName: 'a' }; env.page.data.form.password = 'pw';
  env.page.accountLogin(); env.page.onHide();
  pending.resolve({ accessToken: 'late-token' }); await flush(); await flush();
  assert.equal(env.saved.length, 0);
  assert.equal(env.navigated.length, 0);
  assert.equal(env.page.data.form.password, '');
});

test('重新选择候选会取消迟到的账号密码登录', async () => {
  const pending = deferred();
  const env = createPage({ auth: { accountLogin: () => pending.promise } });
  const first = { accountId: 'a', loginName: 'a' }, second = { accountId: 'b', loginName: 'b' };
  env.page.data.accountMatches = [first, second]; env.page.data.selectedAccount = first; env.page.data.form.password = 'pw';
  env.page.accountLogin();
  env.page.selectAccount({ currentTarget: { dataset: { accountId: 'b' } } });
  pending.resolve({ accessToken: 'late-account-token' }); await flush(); await flush();
  assert.equal(env.saved.length, 0);
  assert.equal(env.page.data.selectedAccount.accountId, 'b');
  assert.equal(env.page.data.loginBusy, false);
});

test('任职加载期间切换方式会清理临时平台会话并阻止跳转', async () => {
  const posts = deferred(); let loggedOut = 0;
  const env = createPage({ auth: { accountLogin: async () => ({ accessToken: 'token' }), myPosts: () => posts.promise, logout: () => { loggedOut += 1; } } });
  env.page.data.selectedAccount = { accountId: 'a', loginName: 'a' }; env.page.data.form.password = 'pw';
  env.page.accountLogin(); await flush();
  assert.equal(env.saved.length, 1);
  env.page.selectLoginMode({ currentTarget: { dataset: { mode: 'wechat' } } });
  posts.resolve([{ postId: 'p1', roleCode: 'boss' }]); await flush(); await flush();
  assert.equal(loggedOut, 1);
  assert.equal(env.navigated.length, 0);
  assert.equal(env.page.data.loginBusy, false);
});

test('清除默认账号会清掉待改密账号与全部密码', () => {
  const env = createPage();
  Object.assign(env.page.data, { defaultAccount: { accountId: 'a' }, selectedAccount: { accountId: 'a' }, passwordChangeRequired: true, pendingAccountLogin: { password: 'old' } });
  Object.assign(env.page.data.form, { password: 'old', newPassword: 'new1234567', confirmPassword: 'new1234567' });
  env.page.clearDefaultAccount();
  assert.equal(env.page.data.pendingAccountLogin, null);
  assert.equal(env.page.data.passwordChangeRequired, false);
  assert.equal(env.page.data.form.password, '');
  assert.equal(env.page.data.form.newPassword, '');
  assert.equal(env.page.data.form.confirmPassword, '');
});

test('平台账号没有任职时清会话并停留登录页', async () => {
  let loggedOut = 0, defaultSaves = 0;
  const env = createPage({ auth: { myPosts: async () => [], logout: () => { loggedOut += 1; } }, accountLoginUtils: { saveDefault: () => { defaultSaves += 1; } } });
  env.page.data.selectedAccount = { accountId: 'a', loginName: 'a' }; env.page.data.form.password = 'pw';
  env.page.accountLogin(); await flush(); await flush();
  assert.equal(loggedOut, 1);
  assert.equal(defaultSaves, 0);
  assert.equal(env.navigated.length, 0);
  assert.match(env.page.data.error, /任职/);
});

test('微信未绑定转账号登录，绑定失败保留会话并可重试成功', async () => {
  const bindCalls = []; let bindAttempt = 0;
  const env = createPage({ auth: {
    login: async () => ({ needBind: true }),
    accountLogin: async () => ({ accessToken: 'account-token', preferredPostId: 'p1' }),
    bindWechat: code => { bindCalls.push(code); bindAttempt += 1; return bindAttempt === 1 ? Promise.reject({ message: '绑定失败' }) : Promise.resolve({}); }
  } });
  env.page.wechatLogin(); await flush();
  assert.equal(env.page.data.bindWechatAfterLogin, true);
  env.page.data.selectedAccount = { accountId: 'a', loginName: 'a' }; env.page.data.form.password = 'pw';
  env.page.accountLogin(); await flush(); await flush(); await flush();
  assert.equal(env.saved.length, 1);
  assert.match(env.page.data.bindingError, /绑定失败/);
  assert.equal(env.navigated.length, 0);
  env.page.retryWechatBind(); await flush(); await flush();
  assert.deepEqual(bindCalls, ['wx-code', 'wx-code']);
  assert.equal(env.navigated.length, 1);
});

test('离页会取消迟到的微信绑定并清理临时账号交付数据', async () => {
  const binding = deferred(); let loggedOut = 0;
  const env = createPage({ auth: { bindWechat: () => binding.promise, logout: () => { loggedOut += 1; } }, http: { token: () => 'token' } });
  env.page.loginGeneration = 3; env.page.loginSessionGeneration = 3; env.page.data.staffAccountDelivery = { initialPassword: 'Init123456' };
  env.page.bindCurrentWechat(3); env.page.onUnload();
  binding.resolve({}); await flush(); await flush();
  assert.equal(loggedOut, 1);
  assert.equal(env.navigated.length, 0);
  assert.equal(env.page.data.staffAccountDelivery, null);
});

test('登录页onHide取消慢微信绑定且不跳转', async () => {
  const binding = deferred(); let loggedOut = 0;
  const env = createPage({ auth: { bindWechat: () => binding.promise, logout: () => { loggedOut += 1; } }, http: { token: () => 'token' } });
  env.page.loginGeneration = 5; env.page.loginSessionGeneration = 5;
  env.page.bindCurrentWechat(5); env.page.onHide();
  binding.resolve({}); await flush(); await flush();
  assert.equal(loggedOut, 1);
  assert.equal(env.navigated.length, 0);
});

test('门店老板开员工独立账号，只提交当前门店岗位并在内存展示一次性密码', async () => {
  let payload;
  const env = createPage({
    http: { token: () => 'token', sessionGuard: () => () => true },
    org: { openStaffAccount: body => { payload = body; return Promise.resolve({ accountId: 'u2', orgName: '南昌高新店', loginName: 'jxnc-gaoxin-wangwu', aliases: ['wangwu'], initialPassword: 'Init123456', reusedUser: false }); } }
  });
  env.page.data.view = 'api-members';
  env.page.data.form = { memberName: '王五', memberMobile: '13800000000', roleIndex: 0, teamIndex: 0 };
  env.page.data.apiRoleOptionsForAdd = [{ id: 'sales', name: '业务员' }];
  env.page.data.apiTeamOptions = [{ deptId: 'team-1', name: '业务部' }];
  env.page.handoverCtx = { postId: 'p1', orgId: 'store-1' };
  env.page.loadApiMembers = async () => {};
  env.page.addMember(); await flush(); await flush();
  assert.ok(payload, env.page.data.error || '员工账号接口未调用');
  assert.deepEqual({ ...payload }, { orgId: 'store-1', name: '王五', mobile: '13800000000', roleCode: 'sales', teamId: 'team-1' });
  assert.equal(env.page.data.staffAccountDelivery.loginName, 'jxnc-gaoxin-wangwu');
  assert.equal(env.page.data.staffAccountDelivery.initialPassword, 'Init123456');
});

test('成员页onHide废弃慢开户响应，返回后不能重看初始密码', async () => {
  const pending = deferred();
  const env = createPage({ http: { token: () => 'token', sessionGuard: () => () => true }, org: { openStaffAccount: () => pending.promise } });
  env.page.data.view = 'api-members'; env.page.handoverCtx = { postId: 'p1', orgId: 'store-1' };
  env.page.data.form = { memberName: '王五', memberMobile: '13800000000', roleIndex: 0, teamIndex: 0 };
  env.page.data.apiRoleOptionsForAdd = [{ id: 'sales', name: '业务员' }]; env.page.data.apiTeamOptions = [];
  env.page.addMember(); env.page.onHide();
  pending.resolve({ loginName: 'jxnc-wangwu', initialPassword: 'Init123456' }); await flush(); await flush();
  assert.equal(env.page.data.staffAccountDelivery, null);
  assert.equal(env.page.data.apiMembersBusy, false);
});

test('门店添加岗位不提供boss/owner，总部旧模板保持可用', async () => {
  const org = { orgTeams: async () => [], orgStaff: async () => [], handoverPending: async () => [] };
  const store = createPage({ http: { token: () => 'token', sessionGuard: () => () => true }, org });
  store.page.data.view = 'api-members'; await store.page.loadApiMembers();
  assert.deepEqual(Array.from(store.page.data.apiRoleOptionsForAdd, role => role.id), ['sales', 'sales-lead']);
  const hq = createPage({ currentPost: { postId: 'hq', orgId: 'hq', orgType: 'headquarters', roleCode: 'boss' }, http: { token: () => 'token', sessionGuard: () => () => true }, org });
  hq.page.data.view = 'api-members'; await hq.page.loadApiMembers();
  assert.equal(hq.page.data.apiRoleOptionsForAdd.some(role => role.id === 'boss'), true);
  assert.equal(hq.page.data.apiRoleOptionsForAdd.some(role => role.id === 'owner'), true);
});

test('账号交付仅在剪贴板成功后提示复制成功', () => {
  const toasts = [], failures = [];
  const env = createPage({ wx: { setClipboardData: options => options.fail(), showToast: options => toasts.push(options.title) } });
  env.page.fail = message => failures.push(message);
  env.page.data.staffAccountDelivery = { orgName: '南昌高新店', loginName: 'jxnc-gaoxin-wangwu', aliases: ['wangwu'], initialPassword: 'Init123456' };
  env.page.copyStaffAccount();
  assert.equal(toasts.length, 0);
  assert.match(failures[0], /复制失败/);
  env.wx.setClipboardData = options => options.success();
  env.page.copyStaffAccount();
  assert.equal(toasts.length, 1);
});
