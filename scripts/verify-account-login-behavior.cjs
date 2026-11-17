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
    loginPolicy:async()=>({agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'}), appId: () => 'app', login: async () => ({accessToken:'wechat-token',userId:7}), accountMatches: async () => ({ items: [] }), accountLogin: async () => ({accessToken:'account-token',userId:7}),
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
        '../../utils/learning-catalog':require('../miniprogram/utils/learning-catalog'),
        '../../api/auth': auth, '../../utils/account-login': { defaultAccount: () => null, clearDefault() {}, saveDefault() {}, validNewPassword: () => true, consent: policy => ({accepted:true,agreementVersion:policy.agreementVersion,privacyVersion:policy.privacyVersion}), saveConsent:policy=>({accepted:true,agreementVersion:policy.agreementVersion,privacyVersion:policy.privacyVersion}), clearConsent(){}, ...overrides.accountLoginUtils },
        '../../api/http': http, '../../api/org': overrides.org || {}, '../../utils/organization': { roles: { boss: '老板', owner: '门店负责人', sales: '业务员', 'sales-lead': '业务负责人' }, templates: { store: ['boss', 'owner', 'sales', 'sales-lead'], headquarters: ['boss', 'owner', 'sales'] }, enabled: () => true, navigation: () => ({ tabs: [{ url: '/pages/admin/index', native: true }] }), apiContext: post => post, ...overrides.organization },
        '../../utils/sample-files': {}, './procurement': { products: [] }, '../../utils/account': account,
        '../../utils/platform-messages': { totalUnread: () => 0 }
      };
      if (id in modules) return modules[id];
      throw Error('Unexpected module ' + id);
    },
    wx, console, Date, Promise, setTimeout:overrides.setTimeout||setTimeout, clearTimeout:overrides.clearTimeout||clearTimeout, getApp: () => ({ globalData: { windowInfo: {} } })
  }, { filename: 'miniprogram/pages/account/index.js' });
  const page = { ...definition, data: structuredClone(definition.data), options: {}, setData(patch) { for (const [key, value] of Object.entries(patch)) setPath(this.data, key, value); } };
  page.data.view = 'login'; page.data.loginPolicy={agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'}; page.data.checked = true; page.data.form = {};
  return { page, auth, http, wx, saved, navigated };
}

test('切换登录方式会取消迟到的微信登录并复位忙碌态', async () => {
  const pending = deferred();
  const env = createPage({ auth: { login: () => pending.promise } });
  env.page.data.loginMode='wechat';
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
  env.page.onHide();
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

for (const roleCode of ['boss','owner','journey-sales','engineering','finance']) test(`${roleCode} 首次微信手机号授权自动进入后台账号，不需要密码或手动绑定`, async () => {
  const calls=[];let passwordCalls=0,bindCalls=0;
  const env=createPage({currentPost:{postId:'p1',orgId:'store-1',orgType:'store',roleCode},auth:{
    login:async options=>{calls.push(options);return options.phoneCode?{accessToken:'wx-token',userId:7}:{needPhone:true};},
    accountLogin:async()=>{passwordCalls++;},bindWechat:async()=>{bindCalls++;}
  }});
  env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();
  assert.equal(env.page.data.loginMode,'wechat');assert.equal(env.page.data.wechatPhoneRequired,true);
  assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);
  env.page.onWechatPhoneNumber({currentTarget:{dataset:{generation:env.page.data.wechatPhoneGeneration}},detail:{code:'verified-phone-code'}});
  await flush();await flush();
  assert.equal(calls.length,2);assert.equal(calls[1].phoneCode,'verified-phone-code');
  assert.equal(env.saved[0].userId,7);assert.equal(env.navigated.length,1);
  assert.equal(passwordCalls+bindCalls,0);assert.equal(env.page.data.wechatPhoneRequired,false);
});


test('离页取消迟到的手机号自动登录并清理临时账号交付数据', async () => {
 const pending=deferred();const env=createPage({auth:{login:()=>pending.promise}});
 env.page.data.loginMode='wechat';env.page.data.staffAccountDelivery={initialPassword:'Init123456'};
 env.page.wechatLogin();env.page.onUnload();pending.resolve({accessToken:'late-token',userId:7});await flush();
 assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);assert.equal(env.page.data.staffAccountDelivery,null);
});


test('拒绝手机号授权不登录、不回退密码；切换通道后的旧授权回调被忽略', async () => {
 let calls=0;const env=createPage({auth:{login:async()=>{calls++;return{needPhone:true};}}});
 env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();
 const generation=env.page.data.wechatPhoneGeneration;
 env.page.onWechatPhoneNumber({currentTarget:{dataset:{generation}},detail:{errMsg:'getPhoneNumber:fail user deny'}});
 assert.equal(calls,1);assert.equal(env.saved.length,0);assert.equal(env.page.data.loginMode,'wechat');
 assert.match(env.page.data.error,/未完成微信手机号授权/);
 env.page.selectLoginMode({currentTarget:{dataset:{mode:'account'}}});
 env.page.onWechatPhoneNumber({currentTarget:{dataset:{generation}},detail:{code:'late-code'}});
 assert.equal(calls,1);assert.equal(env.page.data.wechatPhoneRequired,false);
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


test('绑定后的微信登录凭据直接进入同一用户，不查账号、不输入密码、不再次绑定', async () => {
  let passwordCalls=0,bindCalls=0,matchCalls=0;
  const env=createPage({auth:{
    login:async()=>({accessToken:'wechat-token',userId:7,needPhone:'false',needBind:'false'}),
    accountLogin:async()=>{passwordCalls++;},accountMatches:async()=>{matchCalls++;},bindWechat:async()=>{bindCalls++;}
  }});
  env.page.data.loginMode='wechat';
  env.page.wechatLogin();await flush();await flush();
  assert.equal(env.saved[0].userId,7);assert.equal(env.navigated.length,1);
  assert.equal(env.page.data.loginMode,'wechat');assert.equal(passwordCalls+bindCalls+matchCalls,0);
});

test('有效微信令牌优先于旧版绑定提示，避免已登录结果退回密码通道', async () => {
  const env=createPage({auth:{login:async()=>({accessToken:'token',userId:7,needPhone:true,needBind:true})}});
  env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();await flush();
  assert.equal(env.navigated.length,1);assert.equal(env.page.data.loginMode,'wechat');
});

test('账号密码不调用微信绑定接口；首次微信手机号自动登录后下次直接登录同一用户', async () => {
 let bound=false,passwordCalls=0,bindCalls=0,phoneCalls=0;
 const env=createPage({auth:{
  accountLogin:async()=>{passwordCalls++;return{accessToken:'password-token',userId:7};},
  bindWechat:async()=>{bindCalls++;},
  login:async options=>{if(options.phoneCode){phoneCalls++;bound=true;}return bound?{accessToken:'wechat-token',userId:7}:{needPhone:true};}
 }});
 env.page.data.selectedAccount={accountId:'a',loginName:'test'};env.page.data.form.password='123456';
 env.page.accountLogin();await flush();await flush();assert.equal(env.navigated.length,1);
 env.page.onHide();env.page.selectLoginMode({currentTarget:{dataset:{mode:'wechat'}}});
 env.page.wechatLogin();await flush();
 env.page.onWechatPhoneNumber({currentTarget:{dataset:{generation:env.page.data.wechatPhoneGeneration}},detail:{code:'phone-code'}});
 await flush();await flush();assert.equal(env.navigated.length,2);
 env.page.onHide();env.page.wechatLogin();await flush();await flush();
 assert.equal(env.navigated.length,3);assert.equal(phoneCalls,1);assert.equal(passwordCalls,1);assert.equal(bindCalls,0);
 assert.deepEqual(env.saved.map(result=>result.userId),[7,7,7]);
});

test('手机号不匹配或重复时显示后台错误，不保存会话，不要求手动绑定', async () => {
 for (const code of [1104000003,1104000004]) {
  const env=createPage({auth:{login:async options=>{if(options.phoneCode)throw{code,message:'请管理员核对后台手机号'};return{needPhone:true};}}});
  env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();
  env.page.onWechatPhoneNumber({currentTarget:{dataset:{generation:env.page.data.wechatPhoneGeneration}},detail:{code:'phone-code'}});
  await flush();assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);
  assert.equal(env.page.data.loginMode,'wechat');assert.match(env.page.data.error,/管理员核对/);
 }
});

test('手机号授权后的慢登录在隐藏后取消，重复点击只提交一次', async () => {
 const pending=deferred();let calls=0;
 const env=createPage({auth:{login:options=>{calls++;return options.phoneCode?pending.promise:Promise.resolve({needPhone:true});}}});
 env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();
 const event={currentTarget:{dataset:{generation:env.page.data.wechatPhoneGeneration}},detail:{code:'phone-code'}};
 env.page.onWechatPhoneNumber(event);env.page.onWechatPhoneNumber(event);
 assert.equal(calls,2);env.page.onHide();pending.resolve({accessToken:'late',userId:7});await flush();
 assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);assert.equal(env.page.data.wechatPhoneRequired,false);
});


test('同一登录标签不会取消改密和密码输入', () => {
  const env=createPage();env.page.data.form.password='password';
  env.page.selectLoginMode({currentTarget:{dataset:{mode:'account'}}});
  assert.equal(env.page.data.form.password,'password');
});

test('微信未返回令牌不会保存残缺会话或回退密码登录', async () => {
  const env=createPage({auth:{login:async()=>({needPhone:'false',needBind:'false'})}});
  env.page.data.loginMode='wechat';env.page.wechatLogin();await flush();await flush();
  assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);
  assert.equal(env.page.data.loginMode,'wechat');assert.equal(env.page.data.loginBusy,false);
  assert.match(env.page.data.error,/未完成/);
});

for (const channel of ['account','wechat']) test(channel+' 未勾选先弹窗，同意后只恢复原登录通道',async()=>{
 let accepted=null,accountCalls=0,wechatCalls=0,received;
 const env=createPage({accountLoginUtils:{consent:()=>accepted,saveConsent:policy=>(accepted={accepted:true,agreementVersion:policy.agreementVersion,privacyVersion:policy.privacyVersion})},auth:{
  accountLogin:async(id,password,next,consent)=>{accountCalls++;received=consent;return{accessToken:'account-token',userId:7};},
  login:async options=>{wechatCalls++;received=options.consent;return{accessToken:'wechat-token',userId:7};}
 }});
 env.page.data.loginMode=channel;env.page.data.selectedAccount={accountId:'a',loginName:'lili'};env.page.data.form.password='123456';
 env.page[channel==='account'?'accountLogin':'wechatLogin']();
 assert.equal(env.page.data.consentOpen,true);assert.equal(env.page.data.consentChecked,false);assert.equal(accountCalls+wechatCalls,0);
 env.page.confirmLoginConsent();assert.equal(accountCalls+wechatCalls,0);
 env.page.toggleConsent();env.page.confirmLoginConsent();await flush();await flush();
 assert.equal(accountCalls,channel==='account'?1:0);assert.equal(wechatCalls,channel==='wechat'?1:0);
 assert.equal(received.accepted,true);assert.equal(received.agreementVersion,'2026-11-15');assert.equal(env.saved[0].userId,7);
 assert.equal(env.page.data.consentOpen,false);assert.equal(env.navigated.length,1);
});

test('弹窗原地阅读协议、取消及切换通道不会误登录或清空当前密码',()=>{
 const env=createPage({accountLoginUtils:{consent:()=>null}});env.page.data.selectedAccount={accountId:'a'};env.page.data.form.password='123456';
 env.page.accountLogin();env.page.readLoginDocument({currentTarget:{dataset:{document:'policy'}}});
 assert.equal(env.page.data.consentDocument,'policy');assert.equal(env.page.data.form.password,'123456');assert.equal(env.page.data.consentChecked,false);
 env.page.backToConsent();env.page.closeLoginConsent();assert.equal(env.page.data.form.password,'123456');assert.equal(env.saved.length,0);
 env.page.accountLogin();env.page.selectLoginMode({currentTarget:{dataset:{mode:'wechat'}}});env.page.confirmLoginConsent();
 assert.equal(env.page.data.consentOpen,false);assert.equal(env.saved.length,0);
});

test('协议版本变化会再次要求勾选，旧同意不能沿用',()=>{
 const env=createPage({accountLoginUtils:{consent:()=>null}});
 env.page.loginConsent={accepted:true,agreementVersion:'old',privacyVersion:'old'};env.page.data.loginPolicy={agreementVersion:'new',privacyVersion:'new'};
 env.page.data.selectedAccount={accountId:'a'};env.page.data.form.password='123456';env.page.accountLogin();
 assert.equal(env.page.data.consentOpen,true);assert.equal(env.page.data.consentChecked,false);assert.equal(env.saved.length,0);
});

test('登录设置请求失败可重试，离页后的迟到设置不会触发登录',async()=>{
 let tries=0;
 const env=createPage({auth:{loginPolicy:async()=>{if(++tries===1)throw Error('网络异常');return{agreementVersion:'v2',privacyVersion:'v2'};}},accountLoginUtils:{consent:()=>null}});
 env.page.data.loginPolicy=null;env.page.data.selectedAccount={accountId:'a'};env.page.data.form.password='123456';
 env.page.accountLogin();await flush();assert.equal(env.page.data.loginBusy,false);assert.match(env.page.data.error,/网络异常/);
 env.page.accountLogin();await flush();assert.equal(env.page.data.consentOpen,true);assert.equal(env.saved.length,0);
 const pending=deferred();const late=createPage({auth:{loginPolicy:()=>pending.promise},accountLoginUtils:{consent:()=>null}});
 late.page.data.loginPolicy=null;late.page.data.selectedAccount={accountId:'a'};late.page.data.form.password='123456';late.page.accountLogin();late.page.onHide();
 pending.resolve({agreementVersion:'v2',privacyVersion:'v2'});await flush();assert.equal(late.page.data.consentOpen,false);assert.equal(late.saved.length,0);
});

test('三个密码独立显示隐藏，离页后全部隐藏且密码清空',()=>{
 const env=createPage();Object.assign(env.page.data.form,{password:'123456',newPassword:'abcdef',confirmPassword:'abcdef'});
 for(const field of ['password','newPassword','confirmPassword'])env.page.togglePasswordVisibility({currentTarget:{dataset:{field}}});
 assert.equal(env.page.data.passwordVisible,true);assert.equal(env.page.data.newPasswordVisible,true);assert.equal(env.page.data.confirmPasswordVisible,true);
 env.page.togglePasswordVisibility({currentTarget:{dataset:{field:'password'}}});assert.equal(env.page.data.passwordVisible,false);assert.equal(env.page.data.form.password,'123456');
 env.page.onHide();assert.equal(env.page.data.newPasswordVisible,false);assert.equal(env.page.data.confirmPasswordVisible,false);assert.equal(env.page.data.form.password,'');
});

test('搜索候选和默认账号主要展示姓名岗位及后端简短组织信息',()=>{
 const {render}=require('./role-page-runtime.cjs');const allText=nodes=>nodes.map(n=>(n.text||'')+allText(n.children)).join('');const env=createPage();
 const item={accountId:'a',loginName:'lili',orgName:'合家美宅-江西南昌-高新区合家美宅-裴火荣',ownerName:'裴火荣',personName:'李丽',roleName:'业务员',shortOrgName:'合家美宅 · 江西南昌高新区'};
 Object.assign(env.page.data,{accountMatches:[item,{...item,accountId:'b',loginName:'lili2'}],selectedAccount:item,defaultAccount:item});
 const text=allText(render(path.resolve(__dirname,'../miniprogram/pages/account/index.wxml'),env.page.data));
 assert.match(text,/李丽/);assert.match(text,/业务员/);assert.match(text,/合家美宅 · 江西南昌高新区/);assert.ok(!text.includes(item.orgName));
});

test('两种真实 API 封装均携带新版协议版本，账号标识与密码原样提交',async()=>{
 const calls=[],module={exports:{}};const http={post:async(url,data)=>{calls.push({url,data});return{};},get:async url=>{calls.push({url});return{};},onUnauthorized(){},onPostInvalid(){}};
 vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../miniprogram/api/auth.js'),'utf8'),{module,require:()=>http,wx:{login:options=>options.success({code:'one-time-code'})}});
 const consent={accepted:true,agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'};
 await module.exports.loginPolicy();await module.exports.accountLogin('account-id','lili123','123456',consent);await module.exports.login({appid:'app',consent});
 assert.equal(calls[0].url,'/platform/auth/login-policy');assert.equal(calls[1].data.accountId,'account-id');assert.equal(calls[1].data.password,'lili123');assert.equal(calls[1].data.newPassword,'123456');
 for(const call of calls.slice(1)){assert.equal(call.data.loginVersion,2);assert.equal(call.data.consent,consent);}
 assert.equal(calls[2].data.loginCode,'one-time-code');
});

test('服务端要求重新同意时清除旧同意记录，下次重新拉取版本',async()=>{
 let cleared=0;const env=createPage({accountLoginUtils:{clearConsent:()=>cleared++},auth:{accountLogin:async()=>{throw{code:1104000016,message:'协议已更新'};}}});
 env.page.data.selectedAccount={accountId:'a'};env.page.data.form.password='123456';env.page.accountLogin();await flush();
 assert.equal(cleared,1);assert.equal(env.page.data.loginPolicy,null);assert.equal(env.page.loginConsent,null);assert.equal(env.page.data.loginBusy,false);assert.equal(env.saved.length,0);
});

function matchingClock() {
 let now=0,serial=0;const timers=new Map();
 return {
  setTimeout(fn,delay){const id=serial++;timers.set(id,{fn,at:now+delay});return id;},
  clearTimeout(id){timers.delete(id);},
  advance(ms){const target=now+ms;for(;;){const next=[...timers].filter(([,t])=>t.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;now=next[1].at;timers.delete(next[0]);next[1].fn();}now=target;}
 };
}
function typeAccount(env,value){env.page.input({currentTarget:{dataset:{key:'accountQuery'}},detail:{value}});}

test('用户名连续输入仅在停顿后匹配最后一次输入，短查询不请求接口', async () => {
 const clock=matchingClock(),queries=[];const env=createPage({...clock,auth:{accountMatches:async q=>{queries.push(q);return{items:[]};}}});
 typeAccount(env,'p');clock.advance(500);assert.equal(queries.length,0);
 typeAccount(env,'pe');clock.advance(150);typeAccount(env,'peihuo');clock.advance(299);
 assert.equal(queries.length,0);typeAccount(env,'peihuorong');clock.advance(300);await flush();
 assert.deepEqual(queries,['peihuorong']);assert.equal(env.page.data.matchBusy,false);
});

for(const account of [
 {accountId:'boss',loginName:'hjmz-jxnc-gaoxinquhejiameizhai',personName:'裴火荣',roleName:'老板',query:'裴火'},
 {accountId:'sales',loginName:'lili',personName:'李丽',roleName:'业务员',query:'li'}
])test('部分用户名唯一匹配后补全完整用户名，不重复匹配或触发登录：'+account.roleName, async () => {
 const clock=matchingClock(),queries=[];let logins=0;
 const env=createPage({...clock,auth:{accountMatches:async q=>{queries.push(q);return{items:[account]};},accountLogin:async()=>{logins++;}}});
 typeAccount(env,account.query);clock.advance(300);await flush();
 assert.equal(env.page.data.selectedAccount.accountId,account.accountId);
 assert.equal(env.page.data.form.accountQuery,account.loginName);
 clock.advance(500);await flush();assert.deepEqual(queries,[account.query]);assert.equal(logins,0);
 assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);
});

test('用户名多个匹配由用户选择，未选择不能登录，选择后使用对应账号', async () => {
 const clock=matchingClock();let submitted;
 const env=createPage({...clock,auth:{accountMatches:async()=>({items:[{accountId:'a',loginName:'lili'},{accountId:'b',loginName:'lili2'}]}),accountLogin:async id=>{submitted=id;return{accessToken:'token',userId:7};}}});
 typeAccount(env,'li');clock.advance(300);await flush();
 assert.equal(env.page.data.accountMatches.length,2);assert.equal(env.page.data.selectedAccount,null);
 assert.equal(env.page.data.form.accountQuery,'li');
 env.page.data.form.password='123456';env.page.accountLogin();assert.equal(submitted,undefined);
 env.page.selectAccount({currentTarget:{dataset:{accountId:'b'}}});
 assert.equal(env.page.data.form.accountQuery,'lili2');assert.equal(env.page.data.form.password,'');
 env.page.data.form.password='123456';env.page.accountLogin();await flush();await flush();
 assert.equal(submitted,'b');assert.equal(env.saved.length,1);
});

test('切换候选同步补全用户名，修改补全结果后清除原账号和密码并重新匹配', async () => {
 const clock=matchingClock(),queries=[];
 const accounts=[{accountId:'a',loginName:'lili'},{accountId:'b',loginName:'lili2'}];
 const env=createPage({...clock,auth:{accountMatches:async q=>{queries.push(q);return{items:accounts};}}});
 typeAccount(env,'li');clock.advance(300);await flush();
 env.page.selectAccount({currentTarget:{dataset:{accountId:'b'}}});
 assert.equal(env.page.data.form.accountQuery,'lili2');
 env.page.data.form.password='old-password';
 env.page.selectAccount({currentTarget:{dataset:{accountId:'a'}}});
 assert.equal(env.page.data.form.accountQuery,'lili');assert.equal(env.page.data.form.password,'');
 clock.advance(500);await flush();assert.deepEqual(queries,['li']);
 env.page.data.form.password='other-password';typeAccount(env,'lil');
 assert.equal(env.page.data.selectedAccount,null);assert.equal(env.page.data.form.password,'');
 assert.equal(env.page.data.accountMatches.length,0);assert.equal(env.page.data.form.accountQuery,'lil');
 clock.advance(300);await flush();assert.deepEqual(queries,['li','lil']);
 assert.equal(env.page.data.selectedAccount,null);assert.equal(env.page.data.form.accountQuery,'lil');
});

test('旧用户名匹配响应晚于新响应时，不能覆盖新选择', async () => {
 const clock=matchingClock(),first=deferred(),second=deferred();
 const env=createPage({...clock,auth:{accountMatches:q=>q==='first'?first.promise:second.promise}});
 typeAccount(env,'first');clock.advance(300);typeAccount(env,'second');clock.advance(300);
 second.resolve({items:[{accountId:'second',loginName:'second-full'}]});await flush();
 first.resolve({items:[{accountId:'first',loginName:'first'}]});await flush();
 assert.equal(env.page.data.selectedAccount.accountId,'second');assert.equal(env.page.data.form.accountQuery,'second-full');
});

test('清空用户名取消待执行匹配，并清除候选、选择和旧密码', async () => {
 const clock=matchingClock();let requests=0;
 const env=createPage({...clock,auth:{accountMatches:async()=>{requests++;return{items:[]};}}});
 env.page.data.selectedAccount={accountId:'old'};env.page.data.form.password='old-password';
 typeAccount(env,'username');clock.advance(100);typeAccount(env,'');clock.advance(500);await flush();
 assert.equal(requests,0);assert.equal(env.page.data.selectedAccount,null);assert.equal(env.page.data.accountMatches.length,0);
 assert.equal(env.page.data.form.password,'');assert.equal(env.page.data.matchBusy,false);
});

test('切换微信、隐藏或关闭页面取消待执行匹配', async () => {
 for(const action of ['wechat','hide','unload']) {
  const clock=matchingClock();let requests=0;
  const env=createPage({...clock,auth:{accountMatches:async()=>{requests++;return{items:[]};}}});
  typeAccount(env,'username');
  if(action==='wechat')env.page.selectLoginMode({currentTarget:{dataset:{mode:'wechat'}}});
  else if(action==='hide')env.page.onHide();else env.page.onUnload();
  clock.advance(500);await flush();assert.equal(requests,0);
 }
});

test('键盘搜索立即匹配并取消待执行查询，不重复提交', async () => {
 const clock=matchingClock();let requests=0;
 const env=createPage({...clock,auth:{accountMatches:async()=>{requests++;return{items:[{accountId:'a',loginName:'a'}]};}}});
 typeAccount(env,'username');env.page.findAccounts();await flush();clock.advance(500);await flush();
 assert.equal(requests,1);assert.equal(env.page.data.selectedAccount.accountId,'a');
});

test('自动匹配失败可用键盘重试，恢复正常匹配和忙碌状态', async () => {
 const clock=matchingClock();let requests=0;
 const env=createPage({...clock,auth:{accountMatches:async()=>{if(++requests===1)throw{message:'查询暂时不可用'};return{items:[{accountId:'a',loginName:'a'}]};}}});
 typeAccount(env,'username');clock.advance(300);await flush();
 assert.equal(env.page.data.matchBusy,false);assert.match(env.page.data.error,/暂时不可用/);
 env.page.findAccounts();await flush();assert.equal(env.page.data.selectedAccount.accountId,'a');assert.equal(env.page.data.error,'');
});


test('登录页已有有效会话时直接恢复岗位，关闭重开无需再次输入密码', async () => {
  let restored = 0;
  const env = createPage({ http: {}, auth: { restoreSession: async () => { restored++; return {postId:1,roleCode:'boss'}; } } });
  env.http.token = () => 'remembered-token';
  env.page.refresh = () => {};
  env.page.onShow();
  await flush(); await flush();
  assert.equal(restored, 1);
  assert.equal(env.navigated.length, 1);
  assert.equal(env.page.data.form.password || '', '');
});

for (const remember of [true, false]) test('账号登录提交记住选择：' + remember, async () => {
  let savedOptions;
  const env = createPage({ auth: { saveLogin(result, options) { savedOptions=options; } } });
  env.page.data.rememberDefault=remember;
  env.page.data.selectedAccount={accountId:'remember-test',loginName:'fixture'};
  env.page.data.form.password='fixture-password';
  env.page.accountLogin(); await flush(); await flush();
  assert.equal(savedOptions.remember, remember);
});
