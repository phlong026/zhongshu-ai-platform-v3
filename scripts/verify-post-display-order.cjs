const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const organization = require(path.join(root, 'miniprogram/utils/organization'));

const sixPosts = [
  { postId: '2121622126905532417', roleCode: 'finance', orgType: 'headquarters' },
  { postId: '2121620216580415490', roleCode: 'hq-design-admin', orgType: 'headquarters' },
  { postId: '2121620613810364417', roleCode: 'owner', orgType: 'store' },
  { postId: '2121622126125391874', roleCode: 'finance-lead', orgType: 'store' },
  { postId: '2121622125286531073', roleCode: 'project-manager', orgType: 'store' },
  { postId: '2121621022180384770', roleCode: 'sales-lead', orgType: 'store' }
];
const sixSnapshot = sixPosts.slice();
const sortedSix = organization.sortPostsForDisplay(sixPosts);
assert.deepEqual(sortedSix.map(post => post.postId), [
  '2121621022180384770', '2121622125286531073', '2121622126125391874',
  '2121620613810364417', '2121620216580415490', '2121622126905532417'
]);
assert.deepEqual(sixPosts, sixSnapshot, '展示排序不得改写 auth 缓存数组');
assert.equal(sortedSix[4], sixPosts[1], '展示排序保留原岗位对象与 postId 类型');

const sevenPosts = sixPosts.concat({ postId: 'hq-ops-disabled', roleCode: 'hq-operations', orgType: 'headquarters' });
assert.deepEqual(organization.sortPostsForDisplay(sevenPosts).map(post => post.postId), [
  '2121621022180384770', '2121622125286531073', '2121622126125391874',
  '2121620613810364417', '2121620216580415490', 'hq-ops-disabled', '2121622126905532417'
], '已授权但当前未启用的总部运营岗仅排序，不过滤不启用');

const stableUnknown = [
  { postId: 'unknown-a', roleCode: 'custom-a', orgType: 'store' },
  { postId: 'sales-a', roleCode: 'sales', orgType: 'store' },
  { postId: 'unknown-b', roleCode: 'boss', orgType: 'headquarters' },
  { postId: 'sales-b', roleCode: 'sales-lead', orgType: 'store' },
  { postId: 'unknown-c', roleCode: 'custom-c', orgType: 'headquarters' }
];
assert.deepEqual(organization.sortPostsForDisplay(stableUnknown).map(post => post.postId), [
  'sales-a', 'sales-b', 'unknown-a', 'unknown-b', 'unknown-c'
], '同组与未知岗位均保持后端原相对顺序');

let definition;
const switched = [];
const entered = [];
let currentPost = sixPosts[0];
const auth = {
  apiPosts: () => sixPosts,
  currentApiPost: () => currentPost,
  switchPost: postId => switched.push(postId)
};
function requirePage(name) {
  if(name==='../../utils/learning-catalog')return require('../miniprogram/utils/learning-catalog');
  if (name === '../../utils/view-container') return { routeValue: value => value || '', fail() {} };
  if (name === '../../api/auth') return auth;
  if (name === '../../utils/account-login') return { defaultAccount: () => null };
  if (name === '../../api/http') return { token: () => 'token' };
  if (name === '../../api/org') return {};
  if (name === '../../utils/organization') return organization;
  if (name === '../../utils/sample-files') return { displayFile: value => value };
  if (name === './procurement') return { products: [] };
  if (name === '../../utils/account') return { courseImage: '', services: [], chapters: [], roles: {} };
  if (name === '../../utils/platform-messages') return {};
  throw new Error('Unexpected module: ' + name);
}
vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages/account/index.js'), 'utf8'), {
  require: requirePage,
  Page: value => { definition = value; },
  wx: {
    showToast() {},
    switchTab: options => entered.push(options.url),
    reLaunch: options => entered.push(options.url)
  },
  console,
  Date,
  Math,
  Promise,
  setInterval,
  clearInterval,
  encodeURIComponent
}, { filename: 'miniprogram/pages/account/index.js' });

const page = { ...definition, data: {}, refresh() {}, setData(patch) { Object.assign(this.data, patch); } };
assert.equal(page.displayRole(null, 'missing-legacy-role'), '', '未登录且旧角色未知时向渲染层提供空字符串');
const options = page.apiPostOptions();
assert.deepEqual(Array.from(options, option => option.postId), [
  '2121621022180384770', '2121622125286531073', '2121622126125391874',
  '2121620613810364417', '2121620216580415490', '2121622126905532417'
]);
assert.equal(options.at(-1).selected, true, '选中态仍按当前原 postId 计算');
assert.deepEqual(sixPosts, sixSnapshot, '账户页生成展示选项也不得重排 auth 缓存');

page.selectApiPost({ currentTarget: { dataset: { postId: '2121620216580415490' } } });
assert.deepEqual(switched, [], '设计行政岗位已退役，enabled 白名单拒绝切岗');
assert.equal(entered.length, 0, '被拒岗位不进入任何工作台');

console.log('PASS 六岗位按业务、工程、财务、门店老板、设计行政、总部财务展示');
console.log('PASS 总部运营仅参与展示排序，未知岗位稳定置后');
console.log('PASS 输入不变、postId 原样保留，切换仍使用原 postId');
