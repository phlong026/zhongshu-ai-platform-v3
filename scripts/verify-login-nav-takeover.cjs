// 导航接管验证：后端岗位（posts/mine roleCode）驱动全站 Tab 与落地页，未登录后端时回退 demo。
// 用法：node scripts/verify-login-nav-takeover.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

const org = read('miniprogram/utils/organization.js');
const nav = read('miniprogram/utils/workbench-nav.js');
const auth = read('miniprogram/api/auth.js');
const page = read('miniprogram/pages/account/index.js');
const wxml = read('miniprogram/pages/account/index.wxml');

// 1) 静态契约
assert(org.includes('const apiContext=p=>p?{loggedIn:true,active:true,role:p.roleCode,orgType:p.orgType'), 'organization.apiContext 把 StaffPost 映射为导航上下文');
assert(nav.includes('currentApiPost()') && nav.includes('O.apiContext(post)'), 'workbench-nav 后端岗位优先');
assert(nav.includes("require('./access').context()"), 'workbench-nav 未登录后端时回退 demo 上下文');
assert(auth.includes('zs-api-posts-v1') && auth.includes('function savePosts') && auth.includes('function currentApiPost'), 'auth 层缓存任职列表并提供当前岗位解析');
assert(auth.includes('wx.removeStorageSync(POSTS_KEY)'), '后端登出清理任职缓存');
assert(page.includes('auth.savePosts(posts)'), '登录成功后保存任职列表');
assert(page.includes('O.home(O.apiContext(post))'), '登录落地页按后端岗位计算');
assert(page.includes('selectApiPost') && page.includes('logoutApi'), '身份页支持切换后端岗位与退出后端账号');
assert(wxml.includes('apiLoggedIn') && wxml.includes('data-post-id="{{item.postId}}"'), '身份页渲染后端授权岗位列表');

// 2) 行为断言：mock wx storage 后驱动导航
const store = {};
global.wx = {
  getStorageSync: k => (k in store ? store[k] : ''),
  setStorageSync: (k, v) => { store[k] = v; },
  removeStorageSync: k => { delete store[k]; },
  getAccountInfoSync: () => ({ miniProgram: { appId: 'wx-test' } })
};
store['zs-api-token-v1'] = 't1';
const authApi = require(path.join(root, 'miniprogram/api/auth.js'));
const workbenchNav = require(path.join(root, 'miniprogram/utils/workbench-nav.js'));
const posts = [
  { postId: 'p1', userId: 9, orgId: 151, orgType: 'store', roleCode: 'boss', teamId: null, lead: false },
  { postId: 'p2', userId: 9, orgId: 151, orgType: 'store', roleCode: 'sales', teamId: 152, lead: false }
];
authApi.savePosts(posts);
authApi.switchPost('p1');
let navBoss = workbenchNav();
assert.deepEqual(navBoss.tabs.map(t => t.key), ['owner-home', 'owner-business', 'owner-engineering', 'owner-profile'], '老板岗位显示工作台/业务/工程/我的');
assert.equal(navBoss.homeTab, 'management', '老板落地页为管理工作台');
authApi.switchPost('p2');
let navSales = workbenchNav();
assert.deepEqual(navSales.tabs.map(t => t.key), ['business', 'creation', 'design', 'customers', 'profile'], '业务岗位显示创作/方案/客户导航');
assert.equal(navSales.homeTab, 'business', '业务落地页为业务工作台');
// 未缓存任职（如 myPosts 失败）→ 回退 demo：guest 态
authApi.logout();
let navGuest = workbenchNav();
assert.equal(navGuest.guest, true, '无后端岗位时回退 demo 上下文（未登录为 guest 态）');

console.log('verify-login-nav-takeover: 全部断言通过（后端岗位接管导航/落地页/身份切换，回退逻辑完好）');
