const assert = require('node:assert/strict');
const path = require('node:path');

const root = path.join(__dirname, '..');
const storage = new Map();
let registeredPage;
let navigation = [];
let requests = [];
let modal;

global.Page = definition => { registeredPage = definition; };
global.Component = () => {};
global.getApp = () => ({ globalData: { windowInfo: { statusBarHeight: 20 } } });
global.getCurrentPages = () => [];
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  getStorageInfoSync: () => ({ keys: [...storage.keys()] }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
  getAccountInfoSync: () => ({ miniProgram: { appId: 'wx-test' } }),
  request: options => { requests.push(options); return { abort() {} }; },
  redirectTo: options => navigation.push({ kind: 'redirectTo', url: options.url }),
  switchTab: options => navigation.push({ kind: 'switchTab', url: options.url }),
  reLaunch: options => navigation.push({ kind: 'reLaunch', url: options.url }),
  navigateTo: options => navigation.push({ kind: 'navigateTo', url: options.url }),
  showToast() {},
  showModal(options) { modal = options; },
  pageScrollTo() {}
};

const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const account = require('../miniprogram/utils/account');
const storageKeys = require('../miniprogram/utils/storage-keys');

function loadPage(relativePath) {
  registeredPage = null;
  require(path.join(root, 'miniprogram', relativePath));
  assert.ok(registeredPage, relativePath + ' must register a page');
  return registeredPage;
}

const definitions = {
  account: loadPage('pages/account/index.js'),
  profile: loadPage('pages/profile/index.js'),
  engineering: loadPage('pages/engineering/index.js'),
  business: loadPage('pages/business/index.js'),
  finance: loadPage('pages/admin/finance-desk/index.js')
};

function setData(patch, callback) {
  for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.');
    let target = this.data;
    for (const part of parts.slice(0, -1)) target = target[part] ??= {};
    target[parts.at(-1)] = value;
  }
  callback?.();
}

function pageOf(definition) {
  return {
    ...definition,
    data: structuredClone(definition.data || {}),
    setData,
    getTabBar: () => null
  };
}

function reset(post) {
  storage.clear();
  navigation = [];
  requests = [];
  modal = null;
  if (!post) return;
  http.saveSession({ token: 'api-role-page-fixture', postId: post.postId });
  auth.savePosts([post]);
}

function post(roleCode, extra = {}) {
  return {
    postId: extra.postId || 42,
    orgId: extra.orgId || 7,
    orgName: extra.orgName === undefined ? '测试组织' : extra.orgName,
    orgType: extra.orgType || 'store',
    roleCode,
    lead: extra.lead === true
  };
}

function seedLocalRole(role, orgType = 'store') {
  const companyId = orgType === 'headquarters' ? 'headquarters' : 'legacy-store';
  account.save({
    ...account.load(),
    session: { loggedIn: true, staffId: 'legacy-user', userId: 'legacy-user', companyId, role, roleSchemaVersion: 5 },
    companies: [{ id: companyId, name: '旧本地组织', type: orgType, roles: [role] }]
  });
  storage.set(storageKeys.management, {
    companyId,
    staff: [{ id: 'legacy-user', active: true, roles: [role] }]
  });
}

// API-only accounts must pass protected account views; a missing API session must still be guarded.
for (const role of ['boss', 'owner', 'sales', 'sales-lead', 'engineer', 'project-manager', 'finance', 'finance-lead', 'hq-design-admin']) {
  for (const view of ['profile', 'messages', 'identity', 'devices', 'permissions', 'binding']) {
    reset(post(role, { orgType: role === 'hq-design-admin' ? 'headquarters' : 'store' }));
    const page = pageOf(definitions.account);
    page.onLoad({ view });
    assert.equal(navigation.length, 0, role + ' API session must enter ' + view);
    assert.equal(page.data.apiLoggedIn, true, role + ' must expose apiLoggedIn in ' + view);
  }
}

reset(null);
const guest = pageOf(definitions.account);
guest.onLoad({ view: 'identity' });
assert.equal(navigation[0]?.kind, 'redirectTo', 'guest identity remains guarded');
assert.match(navigation[0]?.url || '', /view=login/, 'guest is sent to login');

reset(post('sales'));
const apiLocalOrder = pageOf(definitions.account);
apiLocalOrder.onLoad({ view: 'feedback' });
assert.match(navigation[0]?.url || '', /view=access/, 'API role does not gain access to legacy local feedback records');

for (const role of ['sales', 'sales-lead', 'engineer', 'project-manager', 'finance', 'finance-lead', 'hq-design-admin']) {
  reset(post(role));
  const membersPage = pageOf(definitions.account);
  membersPage.onLoad({ view: 'api-members' });
  assert.match(navigation[0]?.url || '', /view=access/, role + ' cannot open the organization manager page directly');
  requests = [];
  membersPage.addMember();
  membersPage.revokeRow({ currentTarget: { dataset: { postId: '99', name: '示例' } } });
  membersPage.toggleActiveRow({ currentTarget: { dataset: { userId: '99', name: '示例', active: true } } });
  assert.equal(requests.length, 0, 'forbidden handlers must not call the organization API');
  assert.equal(modal, null, 'forbidden handlers must not show a management confirmation');
}

reset(post('boss'));
auth.savePosts([post('boss'), post('owner', { postId: 43, orgId: 8 })]);
const manager = pageOf(definitions.account);
manager.handoverCtx = { orgId: 7, postId: '42' };
manager.revokeRow({ currentTarget: { dataset: { postId: '99', name: '示例' } } });
auth.switchPost(43);
modal.success({ confirm: true });
assert.ok(requests.every(request => request.method === 'GET'), 'a confirmation opened under another post cannot revoke staff');

// Profile display and management entries must come from the current StaffPost, not a stale demo company.
reset(post('boss', { orgName: '南宁门店' }));
const bossProfile = pageOf(definitions.profile);
bossProfile.onShow();
assert.equal(bossProfile.data.company, '南宁门店');
assert.ok(bossProfile.data.managementEntries.some(row => row.text === '成员与权限'));
assert.deepEqual(bossProfile.data.managementEntries.map(({text,url})=>({text,url})), [
  { text: '加盟商资料', url: '/pages/account/store-profile/index' },
  { text: '成员与权限', url: '/pages/account/index?view=api-members' }
]);
assert.deepEqual(bossProfile.data.entries, []);

for (const [role, expected] of [
  ['sales-lead', [{ text: '客户与责任转交', url: '/pages/customers/index' }]],
  ['project-manager', [{ text: '工程项目', url: '/pages/build/projects/index' }]]
]) {
  reset(post(role, { lead: true }));
  const profile = pageOf(definitions.profile);
  profile.onShow();
  assert.deepEqual(profile.data.entries.map(({text,url})=>({text,url})), expected, role + ' must reach an implemented page with its required context');
}

reset(post('finance-lead', { lead: true }));
const financeLeadProfile = pageOf(definitions.profile);
financeLeadProfile.onShow();
assert.ok(financeLeadProfile.data.entries.every(row => !row.url.includes('/pages/admin/index?view=')),
  'finance lead must not enter the retired team alias which silently opens finance');

reset(post('sales', { orgName: '' }));
const salesProfile = pageOf(definitions.profile);
salesProfile.onShow();
assert.equal(salesProfile.data.company, '当前组织');
assert.deepEqual(salesProfile.data.managementEntries, [], 'ordinary API role gets no management entry');

// API roles must enter the same dedicated workbench as their custom tab definition.
const enabledRoles = [
  ['store', 'boss', '/pages/admin/home/index'],
  ['store', 'owner', '/pages/admin/home/index'],
  ['store', 'sales', '/pages/business/index'],
  ['store', 'sales-lead', '/pages/business/index'],
  ['store', 'engineer', '/pages/build/home/index'],
  ['store', 'project-manager', '/pages/build/home/index'],
  ['store', 'finance', '/pages/admin/finance-desk/index'],
  ['store', 'finance-lead', '/pages/admin/finance-desk/index'],
  ['headquarters', 'hq-design-admin', '/pages/business/index'],
  ['headquarters', 'hq-admin', '/pages/headquarters/index?section=overview'],
  ['headquarters', 'finance', '/pages/headquarters/index?section=finance'],
  ['headquarters', 'finance-lead', '/pages/headquarters/index?section=finance'],
  ['headquarters', 'boss', '/pages/admin/home/index']
];
for (const [orgType, roleCode, url] of enabledRoles) {
  const expected = { kind: url === '/pages/business/index' ? 'switchTab' : 'reLaunch', url };
  reset(post(roleCode, { orgType }));
  pageOf(definitions.account).goAfterLogin();
  assert.deepEqual(navigation, [expected], orgType + '/' + roleCode + ' login must reach its workbench in one navigation');

  reset(post('sales'));
  auth.savePosts([post('sales'), post(roleCode, { orgType, postId: 43 })]);
  const identityPage = pageOf(definitions.account);
  identityPage.refresh = () => {};
  identityPage.selectApiPost({ currentTarget: { dataset: { postId: '43' } } });
  assert.equal(String(auth.currentApiPost().postId), '43');
  assert.deepEqual(navigation, [expected], orgType + '/' + roleCode + ' post switch must not enter an intermediate tab');
}

reset(post('engineer'));
pageOf(definitions.engineering).onShow();
assert.deepEqual(navigation[0], { kind: 'reLaunch', url: '/pages/build/home/index' });

reset(post('sales'));
seedLocalRole('engineer');
pageOf(definitions.engineering).onShow();
assert.notEqual(navigation[0]?.url, '/pages/build/home/index', 'sales is not routed to engineering workbench');

reset(post('hq-design-admin', { orgType: 'headquarters' }));
seedLocalRole('engineer');
pageOf(definitions.business).onShow();
assert.notEqual(navigation[0]?.url, '/pages/workflow/commission/index?view=admin', 'design admin no longer routes to the legacy commission workbench');

reset(post('engineer'));
seedLocalRole('hq-design-admin', 'headquarters');
pageOf(definitions.business).onShow();
assert.notEqual(navigation[0]?.url, '/pages/workflow/commission/index?view=admin', 'API engineer overrides stale local design role');
assert.notEqual(navigation[0]?.url, '/pages/headquarters/index', 'stale local design role cannot route the API engineer to headquarters');

// Finance defaults depend on the API post; explicit route options still win.
reset(post('finance', { orgType: 'store' }));
const storeFinance = pageOf(definitions.finance);
storeFinance.onLoad({});
assert.equal(storeFinance.data.view, 'desk');
assert.equal(storeFinance.data.flowTab, 'receivables');

reset(post('finance', { orgType: 'headquarters' }));
const hqFinance = pageOf(definitions.finance);
hqFinance.onLoad({});
assert.equal(hqFinance.data.view, 'desk');
assert.equal(hqFinance.data.flowTab, 'payables');

const explicitFinance = pageOf(definitions.finance);
explicitFinance.onLoad({ view: 'flows', tab: 'ledger' });
assert.equal(explicitFinance.data.view, 'flows');
assert.equal(explicitFinance.data.flowTab, 'ledger');

reset(post('sales'));
const salesFinance = pageOf(definitions.finance);
salesFinance.onLoad({});
assert.equal(salesFinance.data.view, 'flows', 'non-finance API role is not presented with the finance desk');

console.log('verify-api-role-pages: API会话守卫、12岗位登录与切岗直达、财务默认态与组织展示全部通过');
