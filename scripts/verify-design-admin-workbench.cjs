const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const W = require('../miniprogram/utils/design-admin-workbench');
const O = require('../miniprogram/utils/organization');

const actor = { active: true, role: 'hq-design-admin', orgType: 'headquarters', companyId: 'headquarters', staffId: 'admin-a', name: '设计行政' };
const commission = (id, state, extra = {}) => ({
  id,
  projectId: 'project-' + id,
  companyId: extra.companyId || 'store-a',
  designOrganizationId: extra.designOrganizationId || 'headquarters',
  name: extra.name || id,
  city: extra.city || '武汉市',
  owner: extra.owner || '张业务',
  request: { version: 1, scope: '全案设计', text: '两层乡墅设计' },
  serviceOrders: [{ version: 1 }],
  businessFlow: {
    type: 'sales-offline-v1',
    completed: extra.completed,
    dispatch: extra.dispatch,
    feedbacks: extra.feedbacks || [],
    events: extra.events || []
  },
  acceptance: extra.acceptance,
  __state: state,
  __feeApproved: extra.feeApproved !== false
});

const rows = [
  commission('陈先生', '线下设计中', { events: [{ action: 'design-complete', actorId: 'other-admin', at: '2026-10-21T09:00:00.000Z' }] }),
  commission('王先生', '线下修改中', { city: '黄冈市', feedbacks: [{ status: 'pending' }] }),
  commission('赵先生', '待财务通知', { completed: { actorId: 'admin-a' }, events: [{ action: 'design-complete', actorId: 'admin-a', at: '2026-10-22T01:40:00.000Z' }] }),
  commission('李女士', '待正式成果下发', { owner: '陈业务', completed: { actorId: 'admin-a' } }),
  commission('周先生', '待收图并移交', { dispatch: { actorId: 'admin-a', date: '2026-10-20' }, events: [{ action: 'design-dispatch', actorId: 'admin-a', at: '2026-10-20T09:00:00.000Z' }] }),
  commission('孙先生', '业务移交完成', { dispatch: { actorId: 'admin-a', date: '2026-10-19' }, acceptance: { date: '2026-10-21' } }),
  commission('未授权费用', '费用待老板审批', { feeApproved: false }),
  commission('其他总部', '线下设计中', { designOrganizationId: 'other-headquarters' })
];

const nextFor = c => ({
  state: c.__state,
  view: c.__state === '线下设计中' || c.__state === '线下修改中' ? 'complete' : c.__state === '待正式成果下发' ? 'dispatch' : 'progress',
  actionable: ['线下设计中', '线下修改中', '待正式成果下发'].includes(c.__state)
});
const projected = W.build(rows, actor, { nextFor, feeApproved: c => c.__feeApproved, companyNameFor: id => id === 'store-a' ? '武汉江夏店' : id });

assert.deepEqual(projected.counts, { 全部: 6, 设计中: 1, 修改中: 1, 待财务通知: 1, 待下发: 1, 已下发: 2 });
assert.equal(projected.rows.length, 6, '费用未授权和其他设计组织的委托不得进入工作台');
assert.equal(projected.rows.find(r => r.id === '赵先生').status, '待财务通知');
assert.equal(projected.rows.find(r => r.id === '李女士').status, '待下发');
assert.equal(projected.rows.find(r => r.id === '孙先生').status, '已下发');
assert.equal(W.filter(projected.rows, '武汉江夏', '全部').length, 6, '支持加盟商搜索');
assert.equal(W.filter(projected.rows, 'project-李女士', '全部').length, 1, '支持项目编号搜索');
assert.equal(W.filter(projected.rows, '陈业务', '全部').length, 1, '支持经办业务搜索');
assert.equal(W.filter(projected.rows, '', '已下发').length, 2, '已完成和已接收历史仍可查询');
assert.equal(W.filter(projected.rows, '', '设计与修改').length, 2, '设计与修改指标进入组合筛选，不遗漏设计中项目');
assert.equal(projected.recent.length, 2, '最近办理只取当前人员实际动作');
assert(projected.recent.every(r => r.actorId === actor.staffId));
assert.deepEqual(projected.metrics.map(r => r.label), ['设计与修改', '待财务通知', '待下发']);
assert(projected.todos[0].primary, '第一条待办使用主操作，其余为轻操作');
assert(projected.todos.slice(1).every(r => !r.primary));
assert(projected.todos.every(r => r.customerId === r.id), '首页待办保留 customerId 主链契约');
assert(projected.todos.every(r => r.url === r.actionUrl), '首页待办 url 指向当前可办理动作');
assert(!projected.todos.some(r => r.status === '待财务通知'), '等待财务通知不是行政今日可办动作');
const crowded = W.build(rows.concat(commission('何先生', '线下设计中')), actor, { nextFor, feeApproved: c => c.__feeApproved, companyNameFor: id => id });
assert.equal(crowded.taskCount, 4, '首页标题显示全部本人可办数');
assert.equal(crowded.todos.length, 3, '首页今日待办只预览三条');
assert.equal(projected.metrics[0].filter, '设计与修改');
assert.equal(projected.rows.find(r => r.status === '待下发').detail, '设计已完成 · 核对财务通知后登记下发');
assert.throws(() => W.build(rows, { ...actor, role: 'finance' }, { nextFor, feeApproved: () => true }), /设计行政权限/);

const manyRows = Array.from({ length: 105 }, (_, index) => ({ ...projected.rows[index % projected.rows.length], id: `bulk-${index}`, name: `批量项目${String(index).padStart(3, '0')}`, projectId: `project-bulk-${index}` }));
const firstPage = W.paginate(manyRows, '', '全部', 20);
assert.equal(firstPage.rows.length, 20, '项目首屏只渲染20条');
assert.equal(firstPage.total, 105, '分页不影响筛选总数');
assert(firstPage.hasMore);
const secondPage = W.paginate(manyRows, '', '全部', 40);
assert.equal(secondPage.rows.length, 40, '加载更多每次增加20条');
assert.equal(new Set(secondPage.rows.map(row => row.id)).size, 40, '增量分页不得重复项目');
const resetPage = W.paginate(manyRows, '批量项目010', '全部', 20);
assert.equal(resetPage.rows.length, 1, '搜索变化后重置到首屏并应用当前搜索');
assert.equal(resetPage.total, 1);

const tabs = O.navigation({ ...actor, loggedIn: true }).tabs;
assert.deepEqual(tabs.map(t => [t.index, t.text]), [[19, '首页'], [20, '项目'], [4, '我的']]);
assert.equal(tabs[0].key, 'business', '设计行政首页沿用组织首页键');
assert.equal(tabs[1].url, '/pages/workflow/commission/index?view=projects');

const js = fs.readFileSync(path.resolve('miniprogram/pages/workflow/commission/index.js'), 'utf8');
const wxml = fs.readFileSync(path.resolve('miniprogram/pages/workflow/commission/index.wxml'), 'utf8');
const wxss = fs.readFileSync(path.resolve('miniprogram/pages/workflow/commission/index.wxss'), 'utf8');
assert(js.includes('switchWorkspaceTab'), '同页 tab 切换需保留各 tab 搜索状态');
assert(wxml.includes("view==='projects'"), '项目 tab 应使用真实项目视图');
assert(!/commission-action-panel[^}]*position\s*:\s*fixed/s.test(wxss), '不得新增悬浮底部操作区');

console.log('PASS 设计行政工作台：岗位隔离、组织隔离、状态统计、搜索筛选、历史、本人最近办理、三 Tab 与非悬浮操作区');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const SF = require('../miniprogram/utils/sales-flow');
  const scene = JSON.parse(fs.readFileSync(path.resolve('qa/business-module-simplification-20261021/page-scenes.json'))).find(row => row.action === 'design-complete');
  for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
  for (const [key, value] of scene.before) wx.setStorageSync(key, value);
  const state = wx.getStorageSync(B.KEY);
  const source = state.customers.find(customer => customer.id === scene.id);
  const preApproval = B.clone(source);
  Object.assign(preApproval, { id: source.id + '-preapproval', projectId: source.projectId + '-preapproval', name: '费用待审批客户' });
  preApproval.businessFlow.authorization = { kind: 'exception', serviceVersion: source.serviceOrders.at(-1).version };
  preApproval.moneyApprovals = [];
  const otherDesignOrg = B.clone(source);
  Object.assign(otherDesignOrg, { id: source.id + '-other-org', projectId: source.projectId + '-other-org', name: '其他总部客户', designOrganizationId: 'other-headquarters' });
  state.customers.push(preApproval, otherDesignOrg);
  wx.setStorageSync(B.KEY, state);

  A.mutate(account => A.loginDemo(account, 'hq-design-admin'));
  const visible = B.load().customers.find(customer => customer.id === scene.id);
  assert(visible, '总部设计行政可读取已授权委托投影');
  assert.deepEqual(visible.serviceOrders, []);
  assert.deepEqual(visible.designPayments, []);
  assert.equal(visible.phone, '');
  assert.equal(visible.wechat, '');
  assert.deepEqual(SF.nextRecord(source), SF.next(visible), '纯读 nextRecord 与现有 next 状态语义保持一致');

  wx.reLaunch({ url: '/pages/workflow/commission/index?view=admin' });
  let page = getCurrentPages().at(-1);
  assert.equal(page.data.error, '');
  assert(page.data.projectRows.some(row => row.id === scene.id), '真实授权委托进入行政工作台');
  assert(page.data.projectRows.every(row => row.company !== 'quanzhou'), '产品页面不得展示内部组织ID');
  assert(!page.data.projectRows.some(row => row.id === preApproval.id), '费用未授权委托不进入行政工作台');
  assert(!page.data.projectRows.some(row => row.id === otherDesignOrg.id), '其他设计组织委托不进入行政工作台');
  assert(page.data.projectRows.every(row => !('phone' in row) && !('wechat' in row) && !('serviceOrders' in row) && !('designPayments' in row)), '页面投影不输出客户联系方式或财务明细');

  assert.equal(page.switchWorkspaceTab({ key: 'business', index: 1 }), false, '不得截获销售首页 Tab');
  page.switchWorkspaceTab({ key: 'design-admin-projects', index: 20 });
  assert.equal(page.data.view, 'projects');
  page.searchProjects({ detail: { value: source.projectId } });
  assert.equal(page.data.projectRows.length, 1);
  page.switchWorkspaceTab({ key: 'business', index: 19 });
  page.switchWorkspaceTab({ key: 'design-admin-projects', index: 20 });
  assert.equal(page.data.keyword, source.projectId, '同页切换保留项目搜索条件');

  const Admin = require('../miniprogram/utils/design-admin-workbench');
  const realLoad = Admin.load;
  const desk = realLoad();
  let loadCalls = 0;
  Admin.load = () => {
    loadCalls += 1;
    return { ...desk, rows: manyRows, counts: { ...desk.counts, 全部: manyRows.length } };
  };
  page.setData({ keyword: '', projectFilter: '全部' });
  page.onShow();
  assert.equal(loadCalls, 1, 'onShow只加载一次当前工作台');
  assert.equal(page.data.projectRows.length, 20, '真实页面首屏只显示20条');
  assert.equal(page.data.projectTotal, 105, '真实页面总数不受分页影响');
  page.loadMoreProjects();
  assert.equal(page.data.projectRows.length, 40, '真实页面每次增量20条');
  assert.equal(new Set(page.data.projectRows.map(row => row.id)).size, 40);
  page.searchProjects({ detail: { value: '批量项目010' } });
  assert.equal(page.data.projectRows.length, 1);
  assert.equal(page.data.projectTotal, 1);
  assert.equal(loadCalls, 1, '搜索只处理当前工作台缓存');
  page.searchProjects({ detail: { value: '' } });
  assert.equal(page.data.projectRows.length, 20, '搜索清空后分页重置为20条');
  page.selectProjectFilter({ currentTarget: { dataset: { filter: '已下发' } } });
  assert(page.data.projectRows.length <= 20, '筛选变化重置分页');
  assert.equal(loadCalls, 1, '筛选与加载更多不得重复加载工作台');
  page.switchWorkspaceTab({ key: 'business', index: 19 });
  page.switchWorkspaceTab({ key: 'design-admin-projects', index: 20 });
  assert.equal(loadCalls, 1, '同页Tab切换复用当前onShow缓存');
  page.onShow();
  assert.equal(loadCalls, 2, '再次onShow重新加载工作台');
  Admin.load = realLoad;

  A.mutate(account => A.loginDemo(account, 'hq-finance'));
  page.onShow();
  assert.match(page.data.error, /设计行政权限/);
  assert.equal(page.data.projectRows.length, 0);
  await runtime.disconnect();
  console.log('PASS 真实页面集成：岗位与组织隔离、隐私投影、105条分页、缓存搜索筛选、Tab状态保留');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
