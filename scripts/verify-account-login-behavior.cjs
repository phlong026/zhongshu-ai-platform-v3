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
    loginPolicy:async()=>({agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'}), appId: () => 'app', login: async () => ({accessToken:'wechat-token',userId:7}), accountMatches: async () => ({ items: [] }), accountLoginByName: async loginName => ({accessToken:'account-token',userId:7,accountId:'verified-account',loginName}), accountLogin:async accountId=>({accessToken:'account-token',userId:7,accountId,loginName:'fixture'}),
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

test('输入姓名、简称或完整账号均不触发匿名候选查询，也不自动补全', async () => {
  let probes=0,logins=0,timers=0;
  const env=createPage({setTimeout:()=>{timers++;},auth:{accountMatches:async()=>{probes++;return{items:[{accountId:'private',ownerName:'私有姓名'}]};},accountLoginByName:async()=>{logins++;}}});
  for(const value of ['李','李丽','hjmz','hjmz-complete']) {
    env.page.input({currentTarget:{dataset:{key:'accountQuery'}},detail:{value}});await flush();
    assert.equal(env.page.data.form.accountQuery,value);
  }
  assert.equal(probes,0);assert.equal(logins,0);assert.equal(timers,0);assert.equal(env.saved.length,0);
});

test('账号和密码都必填，但不需要选择任何匿名候选', () => {
  let calls=0;const env=createPage({auth:{accountLoginByName:async()=>{calls++;}}});
  env.page.accountLogin();assert.match(env.page.data.error,/完整账号/);
  env.page.data.form.accountQuery='complete-account';env.page.accountLogin();assert.match(env.page.data.error,/请输入密码/);
  assert.equal(calls,0);
});

test('登录页onHide取消慢登录并清除密码', async () => {
  const pending = deferred();
  const env = createPage({ auth: { accountLoginByName: () => pending.promise } });
  env.page.data.form.accountQuery='a'; env.page.data.form.password = 'pw';
  env.page.accountLogin(); env.page.onHide();
  pending.resolve({ accessToken: 'late-token' }); await flush(); await flush();
  assert.equal(env.saved.length, 0);
  assert.equal(env.navigated.length, 0);
  assert.equal(env.page.data.form.password, '');
});

test('修改完整账号会取消迟到的账号密码登录并清除旧密码', async () => {
  const pending=deferred(),env=createPage({auth:{accountLoginByName:()=>pending.promise}});
  env.page.data.form={accountQuery:'first-account',password:'pw'};env.page.accountLogin();
  env.page.input({currentTarget:{dataset:{key:'accountQuery'}},detail:{value:'second-account'}});
  pending.resolve({accessToken:'late-account-token',accountId:'old',loginName:'first-account'});await flush();await flush();
  assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);assert.equal(env.page.data.loginBusy,false);
  assert.equal(env.page.data.form.accountQuery,'second-account');assert.equal(env.page.data.form.password,'');
});

test('任职加载期间切换方式会清理临时平台会话并阻止跳转', async () => {
  const posts = deferred(); let loggedOut = 0;
  const env = createPage({ auth: { accountLoginByName: async () => ({ accessToken: 'token' }), myPosts: () => posts.promise, logout: () => { loggedOut += 1; } } });
  env.page.data.form.accountQuery='a'; env.page.data.form.password = 'pw';
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
  Object.assign(env.page.data, { defaultAccount: { accountId: 'a' }, passwordChangeRequired: true, pendingAccountLogin: { password: 'old' } });
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
  env.page.data.form.accountQuery='a'; env.page.data.form.password = 'pw';
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
    accountLoginByName:async()=>{passwordCalls++;},bindWechat:async()=>{bindCalls++;}
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

async function createMemberPage(rows, overrides = {}) {
  const commands = [];
  const org = {
    orgTeams: async () => [], orgStaff: async () => rows, handoverPending: async () => [],
    handoverPreview: async () => [{ domain: 'franchise-crm', label: '客户', inFlight: 1, transferred: 0, status: 'PENDING_UNASSIGNED' }],
    revokePost: async postId => { commands.push({ action: 'revoke', postId }); },
    setStaffStatus: async userId => { commands.push({ action: 'suspend', userId }); },
    staffHandover: async payload => { commands.push({ action: 'handover', ...JSON.parse(JSON.stringify(payload)) }); return { items: [] }; },
    staffEmergencyRevoke: async payload => { commands.push({ action: 'emergency', ...payload }); return { items: [] }; }
  };
  const env = createPage({ http: { token: () => 'token', sessionGuard: () => () => true }, org,
    wx: { showModal: options => options.success({ confirm: true }) }, ...overrides });
  env.page.data.view = 'api-members';
  await env.page.loadApiMembers();
  return { ...env, org, commands };
}

for (const roleCode of ['boss', 'owner']) test('门店 '+roleCode+' 兼岗也不能通过四个成员入口撤权，页面说明由总部办理', async () => {
  const rows = [
    { postId: 'employee-post', userId: 'u1', name: '兼岗成员', roleCode: 'sales', active: true },
    { postId: 'reserved-post', userId: 'u1', name: '兼岗成员', roleCode, active: true },
    { postId: 'receiver-post', userId: 'u2', name: '员工', roleCode: 'sales', active: true }
  ];
  const env = await createMemberPage(rows);
  assert.equal(env.page.data.apiStaffRows[0].revokeBlockedReason, '', '单独撤普通岗位仍可办理');
  assert.match(env.page.data.apiStaffRows[0].suspendBlockedReason, /总部/);
  assert.match(env.page.data.apiStaffRows[1].revokeBlockedReason, /总部/);
  env.page.revokeRow({ currentTarget: { dataset: { postId: 'reserved-post', name: '兼岗成员' } } });
  env.page.toggleActiveRow({ currentTarget: { dataset: { userId: 'u1', name: '兼岗成员', active: true } } });
  await env.page.pickHandoverLeaver({ detail: { value: 0 } });
  env.page.pickHandoverTarget({ currentTarget: { dataset: { domain: 'franchise-crm' } }, detail: { value: 0 } });
  assert.match(env.page.data.handoverSource.removalBlockedReason, /总部/);
  env.page.confirmHandover();
  env.page.confirmEmergency();
  assert.deepEqual(env.commands, [], '受保护身份不会发出普通撤权命令');
  assert.match(env.page.data.error, /总部/);

  env.page.revokeRow({ currentTarget: { dataset: { postId: 'employee-post', name: '兼岗成员' } } });
  await flush();
  assert.deepEqual(env.commands, [{ action: 'revoke', postId: 'employee-post' }]);
});

test('老板仍可作为普通员工的交接接收人，总部成员页保留自身任职操作', async () => {
  const rows = [
    { postId: 'boss-post', userId: 'u1', name: '老板', roleCode: 'boss', active: true },
    { postId: 'sales-post', userId: 'u2', name: '员工', roleCode: 'sales', active: true }
  ];
  const store = await createMemberPage(rows);
  await store.page.pickHandoverLeaver({ detail: { value: 1 } });
  store.page.pickHandoverTarget({ currentTarget: { dataset: { domain: 'franchise-crm' } }, detail: { value: 0 } });
  store.page.confirmHandover();
  await flush();
  assert.deepEqual(store.commands, [{ action: 'handover', orgId: 'store-1', fromUserId: 'u2', receivers: { 'franchise-crm': 'u1' } }]);

  const hq = await createMemberPage(rows, { currentPost: { postId: 'hq', orgId: 'hq', orgType: 'headquarters', roleCode: 'boss' } });
  assert.equal(hq.page.data.apiStaffRows[0].revokeBlockedReason, '');
  hq.page.revokeRow({ currentTarget: { dataset: { postId: 'boss-post', name: '总部成员' } } });
  await flush();
  assert.deepEqual(hq.commands, [{ action: 'revoke', postId: 'boss-post' }]);
});

test('弹窗确认期间成员新增总部保留身份时，停用和紧急撤权须重新检查', async () => {
  for (const method of ['toggleActiveRow', 'confirmEmergency']) {
    let modal;
    const rows = [
      { postId: 'sales-post', userId: 'u1', name: '员工甲', roleCode: 'sales', active: true },
      { postId: 'receiver-post', userId: 'u2', name: '员工乙', roleCode: 'sales', active: true }
    ];
    const env = await createMemberPage(rows, { wx: { showModal: options => { modal = options; } } });
    await env.page.pickHandoverLeaver({ detail: { value: 0 } });
    env.page[method]({ currentTarget: { dataset: { userId: 'u1', name: '员工甲', active: true } } });
    assert.ok(modal);
    rows.push({ postId: 'new-boss', userId: 'u1', name: '员工甲', roleCode: 'boss', active: true });
    await env.page.loadApiMembers();
    modal.success({ confirm: true });
    assert.deepEqual(env.commands, []);
    assert.match(env.page.data.error, /总部/);
  }
});

test('最后管理岗位由后端实时判断，页面显示明确拒绝原因并保留成员', async () => {
  const env = await createMemberPage([{ postId: 'manager-post', userId: 'u1', name: '管理员', roleCode: 'custom-manager', active: true }]);
  env.org.revokePost = async () => { throw new Error('不能移除本组织最后一个有效管理岗位，请先安排接任管理人员'); };
  env.page.revokeRow({ currentTarget: { dataset: { postId: 'manager-post', name: '管理员' } } });
  await flush();
  assert.match(env.page.data.error, /最后一个有效管理岗位/);
  assert.equal(env.page.data.apiStaffRows.length, 1);
  const wxml = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.wxml'), 'utf8');
  assert.ok(wxml.includes('wx:if="{{!item.revokeBlockedReason}}"'));
  assert.ok(wxml.includes('wx:if="{{!item.suspendBlockedReason}}"'));
  assert.ok(wxml.includes('disabled="{{handoverBusy||apiMembersBusy||handoverSource.removalBlockedReason}}"'));
});

test('普通停用复用紧急撤权并显示四域待分配，待核域不会显示已交接', async () => {
  const rows = [{ postId: 'employee', userId: 'u1', name: '员工', roleCode: 'sales', active: true }];
  const env = await createMemberPage(rows);
  const domains = ['客户', '设计委托', '工程', '账款'];
  let legacyCalls = 0, payload;
  env.org.setStaffStatus = async () => { legacyCalls++; };
  env.org.staffEmergencyRevoke = async value => {
    payload = value;
    env.org.orgStaff = async () => [];
    env.org.handoverPending = async () => domains.map((domainLabel, index) => ({ id: index, fromUserId: 'u1', domainLabel, inFlight: index === 3 ? -1 : 1 }));
    return { mode: 'EMERGENCY', items: domains.map((label, index) => ({ label, inFlight: index === 3 ? -1 : 1, transferred: 0, status: index === 3 ? 'SCAN_FAILED' : 'PENDING_UNASSIGNED' })) };
  };
  env.page.toggleActiveRow({ currentTarget: { dataset: { userId: 'u1', name: '员工', active: true } } });
  await flush(); await flush();
  assert.equal(legacyCalls, 0);
  assert.equal(payload.orgId, 'store-1');
  assert.equal(payload.userId, 'u1');
  assert.equal(env.page.data.handoverResult.items.length, 4);
  assert.match(env.page.data.handoverResult.title, /停用与待交接/);
  assert.equal(env.page.data.handoverResult.items[3].statusText, '扫描失败（待核）');
  assert.equal(env.page.data.apiPendingRows[3].inFlightText, '数量待核');
  assert.equal(env.page.data.handoverBusy, false);
});

test('组织共享财务在真实清单模板中显示继续处理，不渲染零项交接或补交接提示', () => {
  const env = createPage();
  const render = require('./role-page-runtime.cjs').render;
  const wxmlFile = path.join(__dirname, '../miniprogram/pages/account/index.wxml');
  const textOf = nodes => nodes.map(node => (node.text || '') + textOf(node.children || [])).join('');
  for (const mode of ['NORMAL', 'EMERGENCY']) {
    const result = env.page.handoverResultView({ mode, items: [
      { domain: 'franchise-finance', label: '账款', inFlight: 0, transferred: 0, status: 'ORGANIZATION_SHARED' }
    ] }, '原员工', '接收人');
    assert.equal(result.items[0].rawStatus, 'ORGANIZATION_SHARED');
    assert.equal(result.items[0].statusText, '组织共享');
    assert.equal(result.items[0].inFlightText, '组织共享事项，需财务岗继续处理');
    assert.equal(result.items[0].transferredText, '');
    assert.equal(result.openCount, 0);
    const visible = textOf(render(wxmlFile, { ...env.page.data, view: 'api-members', apiCanManage: true, handoverResult: result }));
    assert.match(visible, /组织共享事项，需财务岗继续处理/);
    assert.doesNotMatch(visible, /在途 0 项|已转移 0 项|无在途事项|存在转待分配事项时/);
  }
  const wxml = fs.readFileSync(wxmlFile, 'utf8');
  assert.doesNotMatch(wxml, /移交客户、委托、工程和账款/);
  assert.match(wxml, /组织共享账款由财务岗继续处理/);
});

test('混合交接清单仅把待分配域计入补交接，保留已交接和待核数量', () => {
  const result = createPage().page.handoverResultView({ items: [
    { label: '客户', inFlight: 2, transferred: 2, status: 'HANDED_OVER' },
    { label: '设计委托', inFlight: -1, transferred: 0, status: 'SCAN_FAILED' },
    { label: '工程', inFlight: 3, transferred: 0, status: 'PENDING_UNASSIGNED' },
    { label: '账款', inFlight: 0, transferred: 0, status: 'ORGANIZATION_SHARED' }
  ] }, '原员工', '接收人');
  assert.equal(result.openCount, 1);
  assert.equal(result.items[0].transferredText, '已转移 2 项');
  assert.equal(result.items[1].inFlightText, '数量待核');
  assert.equal(result.items[1].statusText, '扫描失败（待核）');
  assert.equal(result.items[2].statusText, '转待分配');
  assert.equal(result.items[3].statusText, '组织共享');
});

test('正常交接、紧急撤权和停用返回共享财务时不创建待分配提示', async () => {
  for (const method of ['confirmHandover', 'confirmEmergency', 'toggleActiveRow']) {
    const rows = [
      { postId: 'old-finance', userId: 'u1', name: '原财务', roleCode: 'finance', active: true },
      { postId: 'new-finance', userId: 'u2', name: '在岗财务', roleCode: 'finance', active: true }
    ];
    const env = await createMemberPage(rows), notices = [], calls = [];
    env.wx.showToast = value => notices.push(value.title);
    const response = async payload => {
      calls.push(payload);
      env.org.orgStaff = async () => rows.slice(1);
      return { mode: method === 'confirmHandover' ? 'NORMAL' : 'EMERGENCY', items: [
        { domain: 'franchise-finance', label: '账款', inFlight: 0, transferred: 0, status: 'ORGANIZATION_SHARED' }
      ] };
    };
    env.org.staffHandover = response; env.org.staffEmergencyRevoke = response;
    env.org.handoverPreview = async () => [{ domain: 'franchise-finance', label: '账款', inFlight: 0, transferred: 0, status: 'ORGANIZATION_SHARED' }];
    await env.page.pickHandoverLeaver({ detail: { value: 0 } });
    env.page.pickHandoverTarget({ currentTarget: { dataset: { domain: 'franchise-crm' } }, detail: { value: 0 } });
    env.page[method]({ currentTarget: { dataset: { userId: 'u1', name: '原财务', active: true } } });
    await flush(); await flush();
    assert.equal(calls.length, 1);
    assert.equal(env.page.data.handoverResult.items[0].rawStatus, 'ORGANIZATION_SHARED');
    assert.equal(env.page.data.handoverResult.openCount, 0);
    assert.equal(env.page.data.apiPendingRows.length, 0);
    assert.equal(env.page.data.handoverBusy, false);
    assert.equal(env.page.data.apiStaffRows.length, 1);
    assert.equal(notices.some(title => /待分配|已转移 0 项/.test(title)), false);
  }
});

test('原员工分页检索只使用当前组织，零待分配的历史来源可以被选择并提交补交接', async () => {
  const env = await createMemberPage([{ postId: 'boss', userId: 'u1', name: '老板', roleCode: 'boss', active: true }]);
  const searches = [];
  env.org.handoverSources = async (orgId, keyword, pageNo) => {
    searches.push({ orgId, keyword, pageNo });
    return { items: [{ userId: pageNo === 1 ? 'former1' : 'former2', name: '原员工', mobile: '13800000001' }], hasMore: pageNo === 1 };
  };
  env.page.inputHandoverSearch({ detail: { value: ' 原员工 ' } });
  await env.page.searchHandoverHistory();
  await env.page.searchHandoverHistory({ currentTarget: { dataset: { more: true } } });
  assert.deepEqual(searches, [{ orgId: 'store-1', keyword: '原员工', pageNo: 1 }, { orgId: 'store-1', keyword: '原员工', pageNo: 2 }]);
  assert.equal(env.page.data.handoverSearchRows.length, 2);
  assert.equal(env.page.data.handoverSearchHasMore, false);
  await env.page.selectHandoverHistory({ currentTarget: { dataset: { userId: 'former2' } } });
  assert.equal(env.page.data.handoverSource.fromHistory, true);
  await env.page.loadApiMembers();
  assert.equal(env.page.data.handoverSource.userId, 'former2', '无pending的人工选中历史来源在同组织刷新后仍可办理');
  env.page.pickHandoverTarget({ currentTarget: { dataset: { domain: 'franchise-crm' } }, detail: { value: 0 } });
  env.page.confirmHandover();
  await flush();
  assert.deepEqual(env.commands, [{ action: 'handover', orgId: 'store-1', fromUserId: 'former2', receivers: { 'franchise-crm': 'u1' } }]);
});

test('改查找词或离页后，迟到的历史员工响应不会写回候选与忙碌态', async () => {
  for (const leave of [false, true]) {
    const env = await createMemberPage([]);
    const response = deferred();
    env.org.handoverSources = () => response.promise;
    env.page.inputHandoverSearch({ detail: { value: '旧查询' } });
    const request = env.page.searchHandoverHistory();
    if (leave) env.page.onHide();
    else env.page.inputHandoverSearch({ detail: { value: '新查询' } });
    response.resolve({ items: [{ userId: 'old', name: '旧员工' }], hasMore: false });
    await request;
    assert.equal(env.page.data.handoverSearchRows.length, 0);
    assert.equal(env.page.data.handoverSearchBusy, false);
    assert.equal(env.page.data.handoverSearchQuery, leave ? '' : '新查询');
  }
});

test('单岗已撤销但复核待确认时保留服务端说明并刷新待核清单', async () => {
  const env = await createMemberPage([{ postId: 'employee', userId: 'u1', name: '员工', roleCode: 'sales', active: true }]);
  const notices = [];
  env.wx.showToast = value => notices.push(value.title);
  env.org.revokePost = async () => {
    env.org.orgStaff = async () => [];
    env.org.handoverPending = async () => [{ id: 1, fromUserId: 'u1', domainLabel: '客户', inFlight: -1 }];
    throw new Error('岗位已撤销，但在途复核未完成，请核对待分配清单或查找原员工补交接');
  };
  env.page.revokeRow({ currentTarget: { dataset: { postId: 'employee', name: '员工' } } });
  await flush(); await flush();
  assert.match(env.page.data.error, /岗位已撤销.*复核未完成/);
  assert.equal(env.page.data.apiStaffRows.length, 0);
  assert.equal(env.page.data.apiPendingRows[0].inFlightText, '数量待核');
  assert.deepEqual(notices, []);
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
    accountLoginByName:async()=>{passwordCalls++;},accountMatches:async()=>{matchCalls++;},bindWechat:async()=>{bindCalls++;}
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
  accountLoginByName:async()=>{passwordCalls++;return{accessToken:'password-token',userId:7};},
  bindWechat:async()=>{bindCalls++;},
  login:async options=>{if(options.phoneCode){phoneCalls++;bound=true;}return bound?{accessToken:'wechat-token',userId:7}:{needPhone:true};}
 }});
 env.page.data.form.accountQuery='test';env.page.data.form.password='123456';
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
  accountLoginByName:async(id,password,next,consent)=>{accountCalls++;received=consent;return{accessToken:'account-token',userId:7};},
  login:async options=>{wechatCalls++;received=options.consent;return{accessToken:'wechat-token',userId:7};}
 }});
 env.page.data.loginMode=channel;env.page.data.form.accountQuery='lili';env.page.data.form.password='123456';
 env.page[channel==='account'?'accountLogin':'wechatLogin']();
 assert.equal(env.page.data.consentOpen,true);assert.equal(env.page.data.consentChecked,false);assert.equal(accountCalls+wechatCalls,0);
 env.page.confirmLoginConsent();assert.equal(accountCalls+wechatCalls,0);
 env.page.toggleConsent();env.page.confirmLoginConsent();await flush();await flush();
 assert.equal(accountCalls,channel==='account'?1:0);assert.equal(wechatCalls,channel==='wechat'?1:0);
 assert.equal(received.accepted,true);assert.equal(received.agreementVersion,'2026-11-15');assert.equal(env.saved[0].userId,7);
 assert.equal(env.page.data.consentOpen,false);assert.equal(env.navigated.length,1);
});

test('弹窗原地阅读协议、取消及切换通道不会误登录或清空当前密码',()=>{
 const env=createPage({accountLoginUtils:{consent:()=>null}});env.page.data.form.accountQuery='fixture';env.page.data.form.password='123456';
 env.page.accountLogin();env.page.readLoginDocument({currentTarget:{dataset:{document:'policy'}}});
 assert.equal(env.page.data.consentDocument,'policy');assert.equal(env.page.data.form.password,'123456');assert.equal(env.page.data.consentChecked,false);
 env.page.backToConsent();env.page.closeLoginConsent();assert.equal(env.page.data.form.password,'123456');assert.equal(env.saved.length,0);
 env.page.accountLogin();env.page.selectLoginMode({currentTarget:{dataset:{mode:'wechat'}}});env.page.confirmLoginConsent();
 assert.equal(env.page.data.consentOpen,false);assert.equal(env.saved.length,0);
});

test('协议版本变化会再次要求勾选，旧同意不能沿用',()=>{
 const env=createPage({accountLoginUtils:{consent:()=>null}});
 env.page.loginConsent={accepted:true,agreementVersion:'old',privacyVersion:'old'};env.page.data.loginPolicy={agreementVersion:'new',privacyVersion:'new'};
 env.page.data.form.accountQuery='fixture';env.page.data.form.password='123456';env.page.accountLogin();
 assert.equal(env.page.data.consentOpen,true);assert.equal(env.page.data.consentChecked,false);assert.equal(env.saved.length,0);
});

test('登录设置请求失败可重试，离页后的迟到设置不会触发登录',async()=>{
 let tries=0;
 const env=createPage({auth:{loginPolicy:async()=>{if(++tries===1)throw Error('网络异常');return{agreementVersion:'v2',privacyVersion:'v2'};}},accountLoginUtils:{consent:()=>null}});
 env.page.data.loginPolicy=null;env.page.data.form.accountQuery='fixture';env.page.data.form.password='123456';
 env.page.accountLogin();await flush();assert.equal(env.page.data.loginBusy,false);assert.match(env.page.data.error,/网络异常/);
 env.page.accountLogin();await flush();assert.equal(env.page.data.consentOpen,true);assert.equal(env.saved.length,0);
 const pending=deferred();const late=createPage({auth:{loginPolicy:()=>pending.promise},accountLoginUtils:{consent:()=>null}});
 late.page.data.loginPolicy=null;late.page.data.form.accountQuery='fixture';late.page.data.form.password='123456';late.page.accountLogin();late.page.onHide();
 pending.resolve({agreementVersion:'v2',privacyVersion:'v2'});await flush();assert.equal(late.page.data.consentOpen,false);assert.equal(late.saved.length,0);
});

test('三个密码独立显示隐藏，离页后全部隐藏且密码清空',()=>{
 const env=createPage();Object.assign(env.page.data.form,{password:'123456',newPassword:'abcdef',confirmPassword:'abcdef'});
 for(const field of ['password','newPassword','confirmPassword'])env.page.togglePasswordVisibility({currentTarget:{dataset:{field}}});
 assert.equal(env.page.data.passwordVisible,true);assert.equal(env.page.data.newPasswordVisible,true);assert.equal(env.page.data.confirmPasswordVisible,true);
 env.page.togglePasswordVisibility({currentTarget:{dataset:{field:'password'}}});assert.equal(env.page.data.passwordVisible,false);assert.equal(env.page.data.form.password,'123456');
 env.page.onHide();assert.equal(env.page.data.newPasswordVisible,false);assert.equal(env.page.data.confirmPasswordVisible,false);assert.equal(env.page.data.form.password,'');
});

test('登录页面不渲染旧候选缓存中的人员、岗位和公司资料',()=>{
 const {render}=require('./role-page-runtime.cjs'),allText=nodes=>nodes.map(n=>(n.text||'')+allText(n.children)).join('');
 const env=createPage(),item={accountId:'a',loginName:'lili',orgName:'私有门店全名',ownerName:'私有老板',personName:'私有人员',roleName:'私有岗位',shortOrgName:'私有地区'};
 Object.assign(env.page.data,{accountMatches:[item,{...item,accountId:'b'}],selectedAccount:item,defaultAccount:item});
 const text=allText(render(path.resolve(__dirname,'../miniprogram/pages/account/index.wxml'),env.page.data));
 for(const value of ['私有门店全名','私有老板','私有人员','私有岗位','私有地区']) assert(!text.includes(value),value);
});

test('完整账号、已验证账号改密和微信 API 均携带协议版本且不请求匿名候选',async()=>{
 const calls=[],module={exports:{}};const http={post:async(url,data)=>{calls.push({url,data});return{};},get:async url=>{calls.push({url});return{};},onUnauthorized(){},onPostInvalid(){}};
 vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../miniprogram/api/auth.js'),'utf8'),{module,require:()=>http,wx:{login:options=>options.success({code:'one-time-code'})}});
 const consent={accepted:true,agreementVersion:'2026-11-15',privacyVersion:'2026-11-15'};
 await module.exports.loginPolicy();await module.exports.accountLogin('account-id','lili123','123456',consent);await module.exports.login({appid:'app',consent});
 await module.exports.accountLoginByName('  complete-account  ','secret-password',null,consent);
 assert.equal(calls[0].url,'/platform/auth/login-policy');assert.equal(calls[1].data.accountId,'account-id');assert.equal(calls[1].data.password,'lili123');assert.equal(calls[1].data.newPassword,'123456');
 for(const call of calls.slice(1)){assert.equal(call.data.loginVersion,2);assert.equal(call.data.consent,consent);}
 assert.equal(calls[2].data.loginCode,'one-time-code');
 assert.equal(calls[3].url,'/platform/auth/account/login');assert.equal(calls[3].data.loginName,'complete-account');
 assert.equal(calls[3].data.password,'secret-password');assert.equal(calls[3].data.accountId,undefined);
 assert.equal(calls.filter(call=>call.url.includes('/matches')).length,0);
});

test('服务端要求重新同意时清除旧同意记录，下次重新拉取版本',async()=>{
 let cleared=0;const env=createPage({accountLoginUtils:{clearConsent:()=>cleared++},auth:{accountLoginByName:async()=>{throw{code:1104000016,message:'协议已更新'};}}});
 env.page.data.form.accountQuery='fixture';env.page.data.form.password='123456';env.page.accountLogin();await flush();
 assert.equal(cleared,1);assert.equal(env.page.data.loginPolicy,null);assert.equal(env.page.loginConsent,null);assert.equal(env.page.data.loginBusy,false);assert.equal(env.saved.length,0);
});

function typeAccount(env,value){env.page.input({currentTarget:{dataset:{key:'accountQuery'}},detail:{value}});}

test('完整账号与密码一次提交，记住的账号只能来自验证后的响应',async()=>{
 const calls=[],remembered=[],pending=deferred();
 const env=createPage({auth:{accountLoginByName:(...args)=>{calls.push(args);return pending.promise;}},accountLoginUtils:{saveDefault:account=>remembered.push(account)}});
 typeAccount(env,'  complete-account  ');env.page.data.form.password='correct-password';env.page.accountLogin();
 assert.equal(calls.length,1);assert.equal(calls[0][0],'complete-account');assert.equal(calls[0][1],'correct-password');
 assert.equal(remembered.length,0);assert.equal(env.saved.length,0);
 pending.resolve({accessToken:'verified-token',userId:7,accountId:'verified-id',loginName:'complete-account',orgName:'不需缓存的公司',ownerName:'不需缓存的姓名'});
 await flush();await flush();assert.equal(env.saved.length,1);
 assert.deepEqual({...remembered[0]},{accountId:'verified-id',loginName:'complete-account'});
});

test('首次密码验证后仅用服务端已验证账号改密，成功前不保存会话或默认账号',async()=>{
 const calls=[],remembered=[];
 const env=createPage({auth:{
  accountLoginByName:async(...args)=>{calls.push({channel:'name',args});return{requirePasswordChange:true,accountId:'verified-id',loginName:'complete-account'};},
  accountLogin:async(...args)=>{calls.push({channel:'id',args});return{accessToken:'token',userId:7,accountId:'verified-id',loginName:'complete-account'};}
 },accountLoginUtils:{saveDefault:account=>remembered.push(account)}});
 typeAccount(env,'complete-account');env.page.data.form.password='initial-password';env.page.accountLogin();await flush();
 assert.equal(env.saved.length,0);assert.equal(remembered.length,0);assert.equal(env.page.data.passwordChangeRequired,true);
 env.page.data.form.newPassword='new-password';env.page.data.form.confirmPassword='new-password';env.page.submitNewPassword();await flush();await flush();
 assert.equal(calls.length,2);assert.equal(calls[1].channel,'id');assert.equal(calls[1].args[0],'verified-id');
 assert.equal(calls[1].args[1],'initial-password');assert.equal(calls[1].args[2],'new-password');assert.equal(env.saved.length,1);
 assert.equal(remembered[0].accountId,'verified-id');
});

test('缺失已验证账号的改密响应不能接受，切换输入也会清除改密上下文',async()=>{
 const env=createPage({auth:{accountLoginByName:async()=>({requirePasswordChange:true})}});
 typeAccount(env,'complete-account');env.page.data.form.password='initial-password';env.page.accountLogin();await flush();
 assert.equal(env.page.data.passwordChangeRequired,false);assert.equal(env.page.data.pendingAccountLogin,null);assert.equal(env.saved.length,0);
 env.page.data.passwordChangeRequired=true;env.page.data.pendingAccountLogin={account:{accountId:'verified-id'},password:'old'};
 env.page.data.form.newPassword='new';typeAccount(env,'new-account');
 assert.equal(env.page.data.pendingAccountLogin,null);assert.equal(env.page.data.passwordChangeRequired,false);
 assert.equal(env.page.data.form.password,'');assert.equal(env.page.data.form.newPassword,'');
});

test('登录失败可直接重试，忙碌期间重复点击只提交一次',async()=>{
 let calls=0;const pending=deferred(),env=createPage({auth:{accountLoginByName:()=>{calls++;return calls===1?pending.promise:Promise.resolve({accessToken:'retry-token',userId:7,accountId:'verified',loginName:'complete-account'});}}});
 typeAccount(env,'complete-account');env.page.data.form.password='password';env.page.accountLogin();env.page.accountLogin();assert.equal(calls,1);
 pending.reject({code:401,message:'内部账号状态'});await flush();assert.equal(env.page.data.loginBusy,false);assert(!env.page.data.error.includes('内部账号状态'));
 env.page.accountLogin();await flush();await flush();assert.equal(calls,2);assert.equal(env.saved.length,1);
});

test('清空完整账号取消登录上下文，之后不以旧默认账号继续提交',async()=>{
 let calls=0;const env=createPage({auth:{accountLoginByName:async()=>{calls++;}}});
 env.page.data.defaultAccount={accountId:'old-id',loginName:'old-account'};
 typeAccount(env,'old-account');env.page.data.form.password='old-password';typeAccount(env,'');env.page.accountLogin();
 assert.equal(calls,0);assert.equal(env.page.data.form.password,'');assert.match(env.page.data.error,/完整账号/);
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
  env.page.data.form.accountQuery='fixture';
  env.page.data.form.password='fixture-password';
  env.page.accountLogin(); await flush(); await flush();
  assert.equal(savedOptions.remember, remember);
});


const loginRender = page => {
 const {render}=require('./role-page-runtime.cjs');
 const flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
 return flatten(render(path.join(__dirname,'../miniprogram/pages/account/index.wxml'),page.data));
};
const nodeText=node=>(node.text||'')+(node.children||[]).map(nodeText).join('');

test('密码登录失败统一提供恢复说明，不透露账号存在、停用或初始凭据状态',async()=>{
 const errors=[
  {code:1104000011,message:'账号或密码不正确'},
  {code:401,message:'账号不存在'},
  {code:403,message:'员工已停用'},
  {code:1104000021,message:'初始密码已失效'},
  {code:-1,message:'请求超时'}
 ];
 const messages=[];
 for(const error of errors){
  const env=createPage({auth:{accountLoginByName:async()=>{throw error;}}});
  const account={loginName:'same-account'};env.page.data.form.accountQuery=account.loginName;
  env.page.submitAccountLogin(account,'old-initial-password');await flush();
  assert.equal(env.page.data.loginBusy,false);assert.equal(env.page.data.passwordLoginRecovery,true);
  assert.equal(env.saved.length,0);assert.equal(env.navigated.length,0);
  const nodes=loginRender(env.page);
  assert.ok(nodes.some(n=>n.tag==='text'&&/微信登录.*管理员.*核实身份.*重置密码/.test(nodeText(n))));
  assert.ok(nodes.some(n=>n.tag==='button'&&n.attrs?.bindtap==='selectLoginMode'&&n.attrs?.['data-mode']==='wechat'&&nodeText(n)==='微信登录'));
  messages.push(env.page.data.error);
 }
 assert.equal(new Set(messages).size,1);
 assert.doesNotMatch(messages[0],/不存在|停用|失效|过期/);
});

test('密码失败的微信入口切换已有通道并清除旧密码和恢复状态',async()=>{
 const env=createPage({auth:{accountLoginByName:async()=>{throw{code:1104000011,message:'账号或密码不正确'};}}});
 env.page.data.form.accountQuery='fixture';env.page.data.form.password='old';env.page.accountLogin();await flush();
 const button=loginRender(env.page).find(n=>n.tag==='button'&&n.attrs?.['data-mode']==='wechat'&&nodeText(n)==='微信登录');
 assert.ok(button);
 env.page[button.attrs.bindtap]({currentTarget:{dataset:{mode:button.attrs['data-mode']}}});
 assert.equal(env.page.data.loginMode,'wechat');assert.equal(env.page.data.form.password,'');
 assert.equal(env.page.data.passwordLoginRecovery,false);assert.equal(env.page.data.error,'');
 assert.equal(env.saved.length,0);
});

test('切换账号后，旧密码请求的迟到失败不能恢复上一账号的提示',async()=>{
 const pending=deferred(),env=createPage({auth:{accountLoginByName:()=>pending.promise}});
 env.page.data.form.accountQuery='fixture';env.page.data.form.password='old';env.page.accountLogin();
 env.page.input({currentTarget:{dataset:{key:'accountQuery'}},detail:{value:'new-account'}});
 pending.reject({code:1104000011,message:'旧账号失败'});await flush();
 assert.equal(env.page.data.error,'');assert.equal(!!env.page.data.passwordLoginRecovery,false);
 env.page.onHide();
});
