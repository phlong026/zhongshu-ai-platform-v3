const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const store = new Map();
const routes = [];
global.wx = {
  getStorageSync: key => store.get(key) || '',
  setStorageSync: (key, value) => store.set(key, value),
  removeStorageSync: key => store.delete(key),
  showToast() {},
  switchTab: ({url}) => routes.push(['switchTab', url]),
  reLaunch: ({url}) => routes.push(['reLaunch', url])
};
let definition;
global.Page = page => { definition = page; };
require('../miniprogram/pages/account/index');
const auth = require('../miniprogram/api/auth');
const navigation = require('../miniprogram/utils/workbench-nav');

async function verify() {
  const page = {
    ...definition, loginGeneration: 1, data: {view:'login', loginMode:'wechat'},
    setData(patch) { Object.assign(this.data, patch); }, refresh() {}
  };
  const sales = {postId:'p2', userId:9, orgId:151, orgType:'store', roleCode:'sales'};
  const boss = {...sales, postId:'p1', roleCode:'boss'};
  // Exercise the login page's actual storage, selection and landing behavior.
  auth.myPosts = async () => [null, sales, boss];
  await page.loadLoginSession({accessToken:'test-login', preferredPostId:boss.postId}, 1);
  assert.deepEqual(auth.apiPosts(), [sales, boss], '仅缓存服务端返回的有效任职');
  assert.equal(auth.currentApiPost().postId, boss.postId, '首选岗位生效');
  page.goAfterLogin(1);
  assert.deepEqual(routes.pop(), ['reLaunch','/pages/admin/home/index']);
  assert.deepEqual(navigation().tabs.map(tab => tab.key), ['owner-home','owner-business','owner-engineering','owner-profile']);

  const identity = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.wxml'), 'utf8');
  assert.match(identity, /data-post-id="{{item\.postId}}"/);
  page.selectApiPost({currentTarget:{dataset:{postId:sales.postId}}});
  assert.equal(auth.currentApiPost().postId, sales.postId);
  assert.deepEqual(routes.pop(), ['switchTab','/pages/business/index']);
  assert.deepEqual(navigation().tabs.map(tab => tab.key), ['business','creation','design','customers','profile']);

  await page.loadLoginSession({accessToken:'test-login', preferredPostId:'expired'}, 1);
  assert.equal(auth.currentApiPost().postId, sales.postId, '失效首选岗位回退到首个有效任职');
  page.logoutApi();
  assert.deepEqual(auth.apiPosts(), []);
  assert.equal(navigation().guest, true);
  auth.myPosts = async () => [null];
  await assert.rejects(page.loadLoginSession({accessToken:'no-post'}, 1));
  assert.equal(auth.currentApiPost(), null);
  assert.match(page.data.error, /暂无有效任职/);
  assert.equal(routes.length, 0, '无有效岗位时没有进入工作台');
  console.log('PASS 登录岗位缓存、首选与回退、业务及老板落地、身份切换和退出');
}
verify().catch(error => { console.error(error); process.exitCode = 1; });
