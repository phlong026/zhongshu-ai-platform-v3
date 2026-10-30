const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const Engineering = require('../miniprogram/pages/build/model');
  const Keys = require('../miniprogram/utils/storage-keys');
  const checks = [];
  let nativeFixtureRows = [];
  const page = () => getCurrentPages().at(-1);
  const pass = name => {
    checks.push({ name, result: 'passed' });
    console.log('PASS ' + name);
  };

  Account.mutate(state => Account.loginDemo(state, 'boss'));

  const yesterday = Account.localDate(Date.now() - 86400000);
  const business = Business.initial();
  business.customers.push({
    ...business.customers[0],
    id: 'owner-visible-customer',
    projectId: 'owner-visible-project',
    name: '老板可见客户',
    companyId: 'quanzhou',
    ownerId: 'sales-other',
    owner: '王业务',
    follows: [{ date: yesterday, method: '微信', content: '已确认需求', appointment: yesterday + ' 10:00', next: '电话回访' }]
  }, {
    ...business.customers[0],
    id: 'foreign-customer',
    projectId: 'foreign-project',
    name: '外店客户',
    companyId: 'other-store',
    ownerId: 'foreign-sales',
    owner: '外店业务'
  });
  wx.setStorageSync(Keys.business, business);

  await runtime.callWxMethod('reLaunch', { url: '/pages/business/index' });
  assert.equal(page().data.isBossBiz, true);
  const workbenchDue = page().data.bossBiz.due;
  const businessPage = await runtime.currentPage();
  const customerEntry = await businessPage.$('.boss-biz-entry');
  assert(customerEntry, '老板业务工作台应保留客户列表入口');
  await customerEntry.tap();
  assert.equal(page().route, 'pages/customers/index', '老板应停留在复用的客户列表页');
  assert.equal(page().data.isBossView, true);
  assert.equal(page().data.navigationIndex, 11, '老板客户下钻页应选中老板业务导航');
  assert.equal(page().data.canRegister, false);
  assert(page().data.customers.some(row => row.id === 'owner-visible-customer'), '老板应看到本店其他业务员的客户');
  assert(!page().data.customers.some(row => row.id === 'foreign-customer'), '老板不得看到外店客户');
  const visibleCustomer = page().data.customers.find(row => row.id === 'owner-visible-customer');
  assert.match(visibleCustomer.detail, /负责人\s*王业务/);
  assert.equal(visibleCustomer.canFollow, false, '老板查看不应扩大业务跟进写权限');
  assert.equal(visibleCustomer.action, '看记录');
  assert.equal(visibleCustomer.cue, '逾期 1 天', '老板应看到真实逾期状态');
  assert.equal(visibleCustomer.cueTone, 'tone-danger');
  assert.equal(visibleCustomer.rank, 0);
  assert.equal(page().data.tabs.find(tab => tab.key === 'due').dot, true);
  assert.equal(workbenchDue, page().data.tabs.find(tab => tab.key === 'due').count, '业务工作台与客户列表应共用待跟进口径');
  assert(page().data.tabs.find(tab => tab.key === 'due').count > 0, '老板只读时仍应看到本店待跟进数量');
  page().selectTab({ currentTarget: { dataset: { tab: 'due' } } });
  assert(page().data.filteredCustomers.some(row => row.id === 'owner-visible-customer'));
  const customerPage = await runtime.currentPage();
  assert(await customerPage.$('brand-header[back="true"]'), '老板客户下钻页应提供返回入口');
  assert.equal(await customerPage.$('button[bindtap="register"]'), null, '老板客户列表不应出现登记客户按钮');
  pass('老板复用客户列表查看本店全部负责人跟进情况，不跨店且不扩写权限');

  const engineering = Engineering.initial();
  const baseProject = engineering.projects[0];
  engineering.projects.push({
    ...baseProject,
    id: 'owner-visible-engineering',
    projectId: 'owner-visible-engineering-project',
    name: '老板可见工程',
    companyId: 'quanzhou',
    managerId: 'engineering-other',
    manager: '王工程'
  }, {
    ...baseProject,
    id: 'foreign-engineering',
    projectId: 'foreign-engineering-project',
    name: '外店工程',
    companyId: 'other-store',
    managerId: 'foreign-engineer',
    manager: '外店工程师'
  });
  wx.setStorageSync(Keys.engineering, engineering);
  nativeFixtureRows = wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).sort().map(key => [key, wx.getStorageSync(key)]);

  await runtime.callWxMethod('reLaunch', { url: '/pages/engineering/index' });
  assert.equal(page().data.isBossEng, true);
  const engineeringPage = await runtime.currentPage();
  const projectsEntry = await engineeringPage.$('.guest-section-link[data-action="projects"]');
  assert(projectsEntry, '老板工程工作台应提供全部项目入口');
  await projectsEntry.tap();
  assert.equal(page().route, 'pages/build/projects/index', '工程进度应复用工程项目列表页');
  assert.equal(page().data.readOnly, true);
  assert.equal(page().data.navigationIndex, 12, '老板项目下钻页应选中老板工程导航');
  assert.equal(page().data.canCreate, false);
  assert(page().data.cards.some(row => row.id === 'owner-visible-engineering'), '老板应看到本店其他工程人员的项目');
  assert(!page().data.cards.some(row => row.id === 'foreign-engineering'), '老板不得看到外店工程');
  const projectsPage = await runtime.currentPage();
  assert(await projectsPage.$('brand-header[back="true"]'), '老板项目下钻页应提供返回入口');
  assert.equal(await projectsPage.$('button[bindtap="openCreate"]'), null, '老板项目列表不应出现新增项目按钮');
  pass('老板工程入口复用项目列表查看本店全部进度，不跨店且不扩建档权限');

  for (const [action, expectedRoute, destination] of [
    ['schedule', 'pages/build/projects/index', 'schedule'],
    ['materials', 'pages/build/materials/index', ''],
    ['payments', 'pages/build/projects/index', 'payments']
  ]) {
    await runtime.callWxMethod('reLaunch', { url: '/pages/engineering/index' });
    const shortcut = await (await runtime.currentPage()).$('.guest-shortcut-grid button[data-action="' + action + '"]');
    assert(shortcut, '老板工程工作台缺少 ' + action + ' 快捷入口');
    await shortcut.tap();
    assert.equal(page().route, expectedRoute, action + ' 应复用工程工作台已有页面');
    assert.equal(page().data.destination || '', destination);
    if (action === 'materials') {
      assert.equal(page().data.navigationIndex, 12, '老板材料下钻页应选中老板工程导航');
      assert(await (await runtime.currentPage()).$('brand-header[back="true"]'), '老板材料下钻页应提供返回入口');
      const visibleIds = new Set([...(page().data.projects || []).map(row => row.id), ...(page().data.groups || []).map(row => row.id), page().data.p?.id].filter(Boolean));
      assert(visibleIds.has('owner-visible-engineering'), '老板材料页应看到本店工程');
      assert(!visibleIds.has('foreign-engineering'), '老板材料页不得看到外店工程');
      assert.equal(page().data.canEdit, false, '老板不得获得工程材料编辑权限');
      assert((page().data.groups || []).every(group => !group.canPlan), '老板不得提交材料计划');
    }
  }
  pass('老板施工计划、材料计划和节点款项均复用工程工作台页面');

  Account.mutate(state => Account.loginDemo(state, 'zhang'));
  await runtime.callWxMethod('reLaunch', { url: '/pages/customers/index' });
  assert.equal(page().data.navigationIndex, 7, '业务员客户根页仍选中客户导航');
  assert.equal(await (await runtime.currentPage()).$('brand-header[back="true"]'), null, '业务员客户根页不应显示返回按钮');
  Account.mutate(state => Account.loginDemo(state, 'engineering-dual'));
  await runtime.callWxMethod('reLaunch', { url: '/pages/build/projects/index' });
  assert.equal(page().data.navigationIndex, 8, '工程人员项目根页仍选中项目导航');
  assert.equal(await (await runtime.currentPage()).$('brand-header[back="true"]'), null, '工程人员项目根页不应显示返回按钮');
  await runtime.callWxMethod('reLaunch', { url: '/pages/build/materials/index' });
  assert.equal(page().data.navigationIndex, 9, '工程人员材料根页仍选中材料导航');
  assert.equal(await (await runtime.currentPage()).$('brand-header[back="true"]'), null, '工程人员材料根页不应显示返回按钮');
  pass('复用下钻页按老板业务/工程选中导航并可返回，员工根页保持原导航');

  const out = path.resolve('qa/owner-workspace-reuse-20261030');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'node-results.json'), JSON.stringify({
    result: 'passed',
    kind: 'role-page-runtime Page/WXML handlers with real scoped models',
    checks
  }, null, 2));
  fs.writeFileSync(path.join(out, 'native-fixture.json'), JSON.stringify(nativeFixtureRows, null, 2));
  fs.writeFileSync(path.join(out, 'reuse-audit.json'), JSON.stringify({
    result: 'passed',
    verifiedRoutes: {
      customers: '/pages/customers/index',
      projects: '/pages/build/projects/index',
      schedule: '/pages/build/projects/index?destination=schedule',
      materials: '/pages/build/materials/index',
      payments: '/pages/build/projects/index?destination=payments'
    },
    scope: '老板只读复用本店客户、项目和材料数据；原经办人写权限不扩大'
  }, null, 2));
  await runtime.disconnect();
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
