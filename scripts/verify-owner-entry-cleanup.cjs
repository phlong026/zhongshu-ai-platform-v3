const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const open = async url => { await runtime.callWxMethod('reLaunch', { url }); return runtime.currentPage(); };
  A.mutate(state => A.loginDemo(state, 'boss'));
  let page = await open('/pages/admin/profile/index');
  const data = getCurrentPages().at(-1).data;
  assert.deepEqual(data.managementEntries.map(row => row.text), ['成员与权限', '审批设置', '我的规矩', '门店资料', '操作记录'], '老板门店管理只保留五个独立设置入口');
  assert(!data.entries.some(row => row.text === '管理模拟联调'), '老板我的不显示管理模拟联调');
  const task = await page.$('.quick-card [data-action="performance"]');
  assert(task && (await task.text()).includes('绩效任务'));
  await task.tap();
  assert.equal(getCurrentPages().at(-1).route, 'pages/admin/tasks/index');
  assert.equal(getCurrentPages().at(-1).data.view, 'team');

  page = await open('/pages/admin/profile/index');
  await (await page.$('[data-url="/pages/admin/detail/index?view=rules"]')).tap();
  assert(await (await runtime.currentPage()).$('[bindtap="openDesignPolicy"]'), '标准设计费用仍从我的规矩进入');
  await (await (await runtime.currentPage()).$('[bindtap="openDesignPolicy"]')).tap();
  assert.equal(getCurrentPages().at(-1).data.view, 'policy');

  page = await open('/pages/admin/home/index');
  assert.equal(await page.$('[data-action="performance"]'), null, '首页不重复展示绩效任务快捷入口');
  assert.equal(await page.$('[data-action="reports"]'), null, '首页不展示经营明细快捷入口');
  assert(await page.$('.home-six-item'), '首页经营统计仍可查看');
  assert(await page.$('.home-ledger-month'), '本月收付款摘要仍保留');
  await (await page.$('[data-url="/pages/customers/index"]')).tap();
  assert.equal(getCurrentPages().at(-1).route, 'pages/customers/index', '首页客户与跟进复用客户列表');
  page = await open('/pages/admin/home/index');
  await (await page.$('[data-url="/pages/build/projects/index"]')).tap();
  assert.equal(getCurrentPages().at(-1).route, 'pages/build/projects/index', '首页工程项目复用工程项目列表');
  page = await open('/pages/admin/detail/index?view=targets');
  assert.equal(await page.$('[data-action="tasks"]'), null, '历史目标页不再重复任务入口');
  assert.equal(await page.$('[data-action="commissions"]'), null, '历史目标页不再展示老板已取消的提成入口');
  page = await open('/pages/admin/operations/index?view=desk');
  assert.equal(await page.$('[data-url="/pages/admin/tasks/index"]'), null, '资金与往来不重复任务入口');
  console.log('PASS 老板我的入口去重、规矩保留费用维护、首页摘要与原单入口保留');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
