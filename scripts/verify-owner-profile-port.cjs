const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const control = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '页面存在控件 ' + selector);
    return element;
  };
  const open = async () => runtime.callWxMethod('reLaunch', { url: '/pages/admin/profile/index' });
  A.mutate(state => A.loginDemo(state, 'boss'));
  await open();
  const settings = await control('.quick-card [data-action="performance"]');
  assert((await settings.text()).includes('绩效任务'));
  await settings.tap();
  assert.equal(getCurrentPages().at(-1).route, 'pages/admin/tasks/index');
  assert.equal(getCurrentPages().at(-1).data.view, 'team');
  pass('老板复用统一我的模板，绩效任务进入新周期任务单');

  await open();
  const team = await control('[data-url="/pages/admin/detail/index?view=team"]');
  assert((await team.text()).includes('我的团队'));
  await team.tap();
  assert.equal(getCurrentPages().at(-1).data.view, 'team');
  pass('我的团队可查看本组织人员与绩效');

  const targets = [
    ['/pages/admin/index?view=staff', '成员与权限'],
    ['/pages/admin/detail/index?view=settings', '审批设置'],
    ['/pages/admin/detail/index?view=handover', '工程负责人交接'],
    ['/pages/admin/detail/index?view=company', '门店资料']
  ];
  for (const [url, label] of targets) {
    await open();
    const row = await control('.owner-management [data-url="' + url + '"]');
    assert((await row.text()).includes(label));
    await row.tap();
    const page = getCurrentPages().at(-1);
    assert(!page.data.error, page.data.error);
    assert(!page.data.missing, label + '有权打开原页面');
    if (label === '审批设置') {
      const text = await (await control('.owner-content')).text();
      assert(text.includes('财务登记实际收付'));
      assert(!text.includes('分人独立复核'));
    }
  }
  pass('门店管理四项入口使用现有组织权限及原页面');

  A.mutate(state => A.switchIdentity(state, state.session.companyId, 'owner'));
  await open();
  assert.equal(await (await runtime.currentPage()).$('[data-url="/pages/workflow/commission/index?view=policy"]'), null);
  pass('门店负责人保留管理入口，标准设计费用授权仅向老板显示');

  for (const id of ['sales-dual', 'engineering-dual', 'finance-dual', 'hq-design-admin', 'hq-finance-dual']) {
    A.mutate(state => A.loginDemo(state, id));
    await runtime.callWxMethod('reLaunch', { url: '/pages/profile/index' });
    const button = await control('.quick-card [data-action="performance"]');
    assert((await button.text()).includes('我的任务'));
    assert.equal(await (await runtime.currentPage()).$('.owner-management'), null);
    await button.tap();
    assert.equal(getCurrentPages().at(-1).route, 'pages/admin/tasks/index');
    assert.equal(getCurrentPages().at(-1).data.view, 'mine');
  }
  pass('其他五类人员统一进入我的任务且不显示老板门店管理');

  const out = path.resolve('qa/owner-workbench-port-20261023');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'profile-results.json'), JSON.stringify({ result: 'passed', checks }, null, 2));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
