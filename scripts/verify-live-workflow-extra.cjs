const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = process.env.ZS_WORKFLOW_EXTRA_ROOT || path.join(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

function loadPage(relative, requires, wx = {}) {
  let definition;
  vm.runInNewContext(read(relative), {
    Page: value => { definition = value; },
    require: request => {
      if (!(request in requires)) throw new Error('未声明的测试依赖：' + request);
      return requires[request];
    },
    wx,
    console,
    setTimeout,
    clearTimeout
  }, { filename: relative });
  return definition;
}

function instanceOf(definition) {
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); }
  };
}

const crmPath = path.join(root, 'miniprogram/utils/platform-crm.js');
delete require.cache[require.resolve(crmPath)];
const CRM = require(crmPath);
const mapped = CRM.detailView({
  customer: { id: '9', name: '客户', ownerUserId: '123', status: 'REGISTERED', revision: 1 },
  follows: [], schemes: [
    { id: 'reference-1', title: '参考方案', stage: 'reference' },
    { id: 'formal-1', title: '正式方案', stage: 'formal' }
  ]
}, { canWrite: false });
assert.equal(String(mapped.customer.ownerUserId), '123', '详情映射必须保留 ownerUserId，不能再用空 owner 掩盖负责人');
assert.equal(mapped.schemesList.find(item => item.id === 'reference-1').formal, false, '参考方案不得误标为正式');
assert.equal(mapped.schemesList.find(item => item.id === 'formal-1').formal, true, '客户详情回读必须恢复服务端 formal 阶段');

const http = {
  token: () => 'token',
  sessionGeneration: () => 1
};
const workflow = loadPage('miniprogram/pages/workflow/index.js', {
  '../../utils/organization': { roles: { boss: '老板', sales: '业务员', 'sales-lead': '业务负责人' } },
  '../../utils/view-container': { routeValue: value => value || '', fail() {} },
  '../../utils/platform-crm': CRM,
  '../../api/http': http,
  '../../api/franchise/crm': {},
  './views': { titles: { transfer: '责任转交' } }
});
const workflowPage = instanceOf(workflow);
const bossPost = { postId: 'boss-post', orgId: 'store-1', userId: '900', roleCode: 'boss' };
workflowPage.platformLoad({ view: 'transfer', id: 'customer-1' }, bossPost);
assert.equal(workflowPage.data.role, '老板', '详情/转交直达时必须立即写入当前岗位中文名');

workflowPage.platformSession = () => ({ postId: 'sales-post', orgId: 'store-1', userId: '901', roleCode: 'sales' });
workflowPage.data.view = 'unsupported-test-view';
workflowPage.id = '';
workflowPage.refresh();
assert.equal(workflowPage.data.role, '业务员', '同页切岗必须同步刷新当前岗位中文名');
assert.equal(workflowPage.__transferStaff, null, '同页切岗必须清除旧岗位负责人名册');
workflowPage.__post = bossPost;
workflowPage.__postKey = 'boss-post';
workflowPage.data.role = '老板';
workflowPage.id = 'customer-1';

workflowPage.data.customer = { ownerUserId: '123' };
workflowPage.__transferStaff = {
  postKey: 'boss-post',
  rows: [
    { userId: '123', name: '张经理', roleCode: 'boss', active: true },
    { userId: '123', name: '张经理', roleCode: 'sales-lead', active: true },
    { userId: '123', name: '张经理', roleCode: 'sales', active: true }
  ]
};
workflowPage.reconcileTransferOwner();
assert.equal(workflowPage.data.customer.ownerDisplay, '张经理', '负责人必须显示名册真实姓名');
assert.equal(workflowPage.data.customer.ownerRoleName, '业务员', '多岗位账号按后端 canonical 规则优先 sales');

workflowPage.data.customer = { ownerUserId: '123', ownerDisplay: '未修改', ownerRoleName: '未修改' };
workflowPage.__transferStaff = { postKey: 'old-post', rows: [{ userId: '123', name: '旧会话', roleCode: 'sales', active: true }] };
workflowPage.reconcileTransferOwner();
assert.equal(workflowPage.data.customer.ownerDisplay, '未修改', '切岗后的旧名册不得覆盖当前页面');

workflowPage.data.customer = { ownerUserId: '123' };
workflowPage.__transferStaff = { postKey: 'boss-post', rows: [{ userId: '123', name: '张经理', roleCode: 'boss', active: true }] };
workflowPage.reconcileTransferOwner();
assert.equal(workflowPage.data.customer.ownerDisplay, '负责人待核', '缺少有效业务任职时不得猜测负责人岗位');

const workflowWxml = read('miniprogram/pages/workflow/index.wxml');
assert(/transfer-customer[\s\S]{0,1200}customer\.ownerDisplay\|\|'负责人待核'/.test(workflowWxml), 'API 转交视图必须使用已核对负责人展示值');
const workflowSource = read('miniprogram/pages/workflow/index.js');
assert(/formal:\s*!!\(this\.__promoted\[s2\.id\]\s*\|\|\s*s2\.formal\)/.test(workflowSource), '详情刷新必须保留服务端 formal 阶段，本会话晋级只能叠加');

let currentPost;
let detailGets = 0;
let redirects = [];
const commissionHttp = {
  get:async()=>({nickname:'设计同事'}),
  token: () => 'token',
  sessionGeneration: () => 1,
  requestGuard: () => () => true
};
const commissionAuth = { currentApiPost: () => currentPost };
const commissionApi = {
  commissions: {
    get: () => { detailGets += 1; return new Promise(() => {}); },
    page: () => new Promise(() => {})
  },
  policies: { current: () => new Promise(() => {}) },
  pricing: { current: () => new Promise(() => {}) }
};
const commission = loadPage('miniprogram/pages/workflow/commission/index.js', {
  '../../../utils/page-navigation': {},
  '../../../utils/money': {},
  '../../../api/http': commissionHttp,
  '../../../api/auth': commissionAuth,
  '../../../api/franchise/commission': commissionApi,
  './presentation': require(path.join(root,'miniprogram/pages/workflow/commission/presentation.js')),
  './design-brief': require(path.join(root,'miniprogram/pages/workflow/commission/design-brief.js')),
  '../../../utils/organization': { apiContext: post => ({ role: post.roleCode, orgType: post.orgType, name: post.name || '' }) },
  '../../../utils/view-container': { makeFormHandlers: () => ({}) }
}, { redirectTo: options => redirects.push(options.url) });

function commissionPage(options, post) {
  currentPost = post;
  const page = instanceOf(commission);
  page.onLoad(options);
  page.refresh(false);
  return page;
}

for (const roleCode of ['boss', 'sales', 'engineer', 'finance']) {
  redirects = [];
  const bare = commissionPage({}, { postId: roleCode, orgId: 'store-1', orgType: 'store', roleCode });
  assert.equal(redirects.length, 0, roleCode + ' 裸设计委托路由不得跳到绩效提成或其他业务页');
  assert.equal(bare.data.error, '缺少委托或客户编号，请从客户或项目列表进入');
  assert.equal(detailGets, 0, roleCode + ' 裸设计委托路由不得请求空 commission id');
}

const missingId = commissionPage({ view: 'progress' }, { postId: '1', orgId: 'store-1', orgType: 'store', roleCode: 'boss' });
assert.equal(missingId.data.error, '缺少委托或客户编号，请从客户或项目列表进入', '显式详情视图缺 id 必须显示受控错误');
assert.equal(detailGets, 0, '显式详情视图缺 id 不得请求 API');

for (const view of ['policy', 'pricing']) {
  const headquarters = view !== 'policy';
  const page = commissionPage({ view }, headquarters
    ? { postId: view, orgId: 'hq', orgType: 'headquarters', roleCode: 'hq-design-admin' }
    : { postId: view, orgId: 'store-1', orgType: 'store', roleCode: 'boss' });
  assert.notEqual(page.data.error, '缺少委托或客户编号，请从客户或项目列表进入', view + ' 无 id 合法入口不得被详情门禁拦截');
  assert.equal(detailGets, 0, view + ' 无 id 合法入口不得误发详情请求');
}

for (const view of ['admin','projects']) {
  const retired = commissionPage({view}, {postId:'sales',orgId:'store-1',orgType:'store',roleCode:'sales'});
  assert.match(retired.data.error,/旧设计工作台已停用/);
  assert.equal(detailGets,0,'retired workbench must not request arbitrary commission details');
}

commissionPage({ view: 'progress', id: 'commission-9' }, { postId: '1', orgId: 'store-1', orgType: 'store', roleCode: 'boss' });
assert.equal(detailGets, 1, '合法带 id 详情仍应请求原详情 API');

console.log('workflow 额外 P2：负责人/岗位与无 id 路由行为通过');
