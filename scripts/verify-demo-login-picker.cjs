const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');

const accounts = [
  { id: 'sales-dual', label: '业务', roles: ['sales', 'sales-lead'], companyId: 'quanzhou', home: 'business', route: 'pages/business/index' },
  { id: 'engineering-dual', label: '工程', roles: ['engineer', 'project-manager'], companyId: 'quanzhou', home: 'engineering', route: 'pages/build/home/index' },
  { id: 'finance-dual', label: '财务', roles: ['finance', 'finance-lead'], companyId: 'quanzhou', home: 'management', route: 'pages/management/index' },
  { id: 'boss', label: '老板', roles: ['boss', 'owner'], companyId: 'quanzhou', home: 'management', route: 'pages/admin/home/index' },
  { id: 'hq-design-admin', label: '总部设计', roles: ['hq-design-admin'], companyId: 'headquarters', home: 'business', route: 'pages/workflow/commission/index' },
  { id: 'hq-finance-dual', label: '总部财务', roles: ['finance', 'finance-lead'], companyId: 'headquarters', home: 'management', route: 'pages/management/index' }
];

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const Demo = require('../miniprogram/utils/demo-users');
  const checks = [];
  const page = () => getCurrentPages().at(-1);
  const open = () => runtime.callWxMethod('reLaunch', { url: '/pages/account/index?view=login' });
  const pass = name => { checks.push({ name, result: 'passed' }); console.log('PASS ' + name); };
  const control = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '页面存在控件 ' + selector);
    return element;
  };

  await open();
  const visible = page().data.demoUsers;
  assert.deepEqual(visible, accounts.map(({ id, label }) => ({ id, label })));
  assert.equal(page().data.selectedDemoUser, 'sales-dual');
  assert.equal(page().data.demoUserLabel, '业务');
  pass('登录列表按顺序仅显示业务、工程、财务、老板、总部设计、总部财务，默认业务');

  const fixturesBefore = JSON.parse(JSON.stringify(A.demoUsers));
  for (const account of accounts) {
    await open();
    const index = page().data.demoUsers.findIndex(person => person.id === account.id);
    await (await control('.demo-user-picker')).trigger('change', { value: String(index) });
    assert.equal(page().data.selectedDemoUser, account.id);
    assert.equal(page().data.demoUserLabel, account.label);
    assert((await (await control('.demo-user-picker')).text()).includes(account.label));
    await (await control('.agreement-row .check-row')).tap();
    await (await control('.page-actions .solid')).tap();
    assert.equal(page().data.modal.type, 'login-demo');
    await (await control('.sheet .solid')).tap();
    const state = A.load();
    const fixture = Demo.people.find(person => person.id === account.id);
    assert.equal(state.session.staffId, fixture.staffId);
    assert.equal(state.session.companyId, account.companyId);
    assert.deepEqual(state.companies[0].roles, account.roles);
    assert.equal(A.access.home(), account.home);
    assert.equal(page().route, account.route);

    for (const role of account.roles) {
      await runtime.callWxMethod('redirectTo', { url: '/pages/account/index?view=identity' });
      assert.deepEqual(page().data.roleOptions.map(option => option.id), account.roles);
      await (await control('[data-role="' + role + '"]')).tap();
      await (await control('.page-actions .solid')).tap();
      assert.equal(A.access.context().staffId, fixture.staffId);
      assert.equal(A.access.context().role, role);
      assert.equal(A.access.home(), account.home);
      assert.equal(page().route, account.route);
    }
    pass(account.label + '可通过登录控件进入，组织与全部已授权岗位正确');
  }

  assert.deepEqual(A.demoUsers, fixturesBefore);
  A.mutate(state => A.loginDemo(state, 'zhang'));
  assert.equal(A.access.context().staffId, 'zhang');
  await open();
  assert(!page().data.demoUsers.some(person => person.id === 'zhang'));
  pass('隐藏人员仍保留内部测试数据，重新打开登录列表仍仅显示六人');

  const key = 'zhongshu-management-v1';
  const original = wx.getStorageSync(key);
  const legacy = JSON.parse(JSON.stringify(original));
  legacy.schemaVersion = 7;
  const boss = legacy.staff.find(person => person.id === 'zhao-boss');
  boss.roles = ['boss'];
  boss.active = false;
  boss.name = '保留原姓名';
  boss.note = '保留原人员记录';
  legacy.staff.find(person => person.id === 'zhang').active = false;
  wx.setStorageSync(key, legacy);
  const migrated = Demo.ensure();
  assert.deepEqual(migrated.staff.find(person => person.id === 'zhao-boss'), { ...boss, roles: ['boss', 'owner'] });
  assert.deepEqual(migrated.work, legacy.work);
  assert.deepEqual(migrated.books, legacy.books);
  assert.equal(migrated.staff.find(person => person.id === 'zhang').active, false);
  assert.equal(migrated.staff.find(person => person.id === 'zhao-boss').active, false);
  assert.throws(() => Demo.login(A.seed(), 'boss'), /停用/);
  const snapshot = wx.getStorageSync(key);
  Demo.ensure();
  assert.deepEqual(wx.getStorageSync(key), snapshot);
  pass('已有缓存补齐老板的门店负责人身份，姓名、停用状态、历史业务及总部数据不变，迁移可重复执行');

  const revoked = wx.getStorageSync(key);
  revoked.staff.find(person => person.id === 'zhao-boss').roles = ['boss'];
  wx.setStorageSync(key, revoked);
  Demo.ensure();
  assert.deepEqual(wx.getStorageSync(key), revoked);
  const future = JSON.parse(JSON.stringify(revoked));
  future.schemaVersion = 9;
  wx.setStorageSync(key, future);
  Demo.ensure();
  assert.deepEqual(wx.getStorageSync(key), future);
  const legacyRevoked = JSON.parse(JSON.stringify(legacy));
  legacyRevoked.staff.find(person => person.id === 'zhao-boss').roles = [];
  wx.setStorageSync(key, legacyRevoked);
  assert.deepEqual(Demo.ensure().staff.find(person => person.id === 'zhao-boss').roles, []);
  wx.setStorageSync(key, original);
  pass('已撤回岗位与迁移后的手动调整不覆盖，未来缓存版本不降级或补权');

  const out = path.resolve(process.env.SALES_QA_DIR || 'qa/demo-login-picker-20261023');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'passed', visibleAccounts: visible, checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
