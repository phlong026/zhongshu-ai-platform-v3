const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const clone = value => JSON.parse(JSON.stringify(value));
  const keys = require('../miniprogram/utils/storage-keys');
  wx.setStorageSync(keys.management, require('../miniprogram/utils/management-seed')());
  wx.setStorageSync(keys.business, { version:1, customers:[] });
  wx.setStorageSync(keys.engineering, { version:1, projects:[] });
  wx.setStorageSync(keys.growth, { version:1, contents:[{ id:'ui-initialize-source', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:'2026-09-20', pack:{title:'九月真实发布'} }], leads:[], events:[] });
  wx.removeStorageSync(keys.performanceTasks);

  const Account = require('../miniprogram/utils/account');
  const Model = require('../miniprogram/pages/admin/model');
  const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(require('../miniprogram/pages/admin/owner-performance').facts);
  const login = (id, role) => Account.mutate(account => {
    Account.loginDemo(account, id);
    if (role) Account.switchIdentity(account, account.session.companyId, role);
  });
  const open = url => { wx.reLaunch({ url }); return getCurrentPages().at(-1); };
  const control = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '页面存在控件 ' + selector);
    return element;
  };

  login('boss', 'boss');
  let page = open('/pages/admin/performance/index');
  assert.equal(page.route, 'pages/admin/tasks/index', '当前绩效入口必须进入任务单主链');
  assert.equal(page.data.error, '');
  assert.match(page.data.period, /^\d{4}-\d{2}$/);
  assert.equal(page.data.view, 'team');
  assert.deepEqual(page.data.tabs.map(tab => tab.label), ['团队任务', '我的任务', '任务归档']);
  assert.equal(await (await runtime.currentPage()).$('[data-key="bonus"]'), null, '任务派发不得录入奖金');
  const taskPageText = await (await runtime.currentPage()).$('.task-content');
  assert.doesNotMatch(await taskPageText.text(), /奖金|应付|办理付款/, '绩效任务页不得出现薪酬、奖金或付款办理');
  assert(await (await runtime.currentPage()).$('.initialize-previous'), '老板首次进入月份可一键按上月实绩初始化');
  page.month({ detail:{ value:'2026-12' } });
  await (await control('.initialize-previous')).tap();
  assert.match(page.data.initializationHint, /上月暂无/);
  assert.equal(Tasks.list(Model.load(), { scope:'company', period:'2026-12' }).length, 0, '无事实空态不创建 0 目标任务');
  page.month({ detail:{ value:Account.localDate().slice(0, 7) } });
  const period = page.data.period, due = page.data.periodEnd;
  await (await control('.initialize-previous')).tap();
  const initializedRows = Tasks.list(Model.load(), { scope:'company', period });
  const suggested = initializedRows.find(row => row.type === 'auto:publications' && row.assigneePersonId === 'finance-dual');
  assert(suggested?.suggestion?.sources.some(source => source.id === 'perf-publication:ui-initialize-source'), '一键初始化须保留上月原单依据：' + JSON.stringify(initializedRows));
  assert(await (await runtime.currentPage()).$('.suggestion-basis'), '页面须显示建议任务的上月实绩依据');
  const growthAfterInitialization = clone(wx.getStorageSync(keys.growth));
  growthAfterInitialization.contents = growthAfterInitialization.contents.filter(row => row.id !== 'ui-initialize-source');
  wx.setStorageSync(keys.growth, growthAfterInitialization);
  page.submitTask({ detail:{ value:{ title:'财务月度核对', assigneeId:'finance-dual', due, type:'manual', target:'', note:'按原账簿完成核对' } } });
  const task = Tasks.list(Model.load(), { scope:'company', period }).find(row => row.title === '财务月度核对');
  assert(task);

  login('finance-dual', 'finance');
  open('/pages/profile/index');
  assert((await (await control('.quick-card [data-action="performance"]')).text()).includes('我的任务'));
  await (await control('.quick-card [data-action="performance"]')).tap();
  page = getCurrentPages().at(-1);
  assert.equal(page.route, 'pages/admin/tasks/index');
  assert.equal(page.data.view, 'mine');
  assert.deepEqual(page.data.tabs.map(tab => tab.label), ['我的任务'], '财务员工不新增组织归档权限');
  assert.equal(await (await runtime.currentPage()).$('.initialize-previous'), null, '员工页不得显示组织任务初始化');
  await (await control('.task-actions button[data-id="' + task.id + '"]')).tap();
  page.completeManual({ detail:{ value:{ note:'已完成月度核对' } } });
  assert.equal(Tasks.list(Model.load(), { scope:'mine', period }).find(row => row.id === task.id).status, '已完成');
  for (const view of ['archive', 'bonus']) {
    page = open('/pages/admin/tasks/index?view=' + view);
    assert.equal(page.data.view, 'mine', '财务员工不得通过参数进入组织归档');
  }

  login('boss', 'boss');
  page = open('/pages/admin/tasks/index?view=team');
  const payablesBefore = clone(Model.load().payables || []);
  page.archive();
  assert.equal(page.data.view, 'archive');
  assert.equal(page.data.archive.latest.revision, 1);
  assert(page.data.archive.latest.lines.some(row => row.id === task.id && row.status === '已完成'));
  assert.doesNotMatch(await (await control('.task-content')).text(), /奖金|应付|办理付款/, '任务归档页不得恢复金钱化信息');
  assert.equal(typeof page.generatePayables, 'undefined', '任务页不得保留生成应付方法');
  assert.equal(typeof page.openPay, 'undefined', '任务页不得保留付款入口');
  assert.equal(await (await runtime.currentPage()).$('.generate-payables'), null);
  assert.deepEqual(Model.load().payables || [], payablesBefore, '任务归档不得派生财务应付');

  const growth = clone(wx.getStorageSync(keys.growth));
  growth.contents.push({ id:'ui-source-change', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:period + '-20', pack:{title:'来源变化'} });
  wx.setStorageSync(keys.growth, growth);
  page = open('/pages/admin/tasks/index?view=archive');
  assert.equal(page.data.archive.needsRevision, true);
  await (await control('.warning button[bindtap="revise"]')).tap();
  assert.equal(page.data.archive.latest.revision, 2);
  assert.equal(page.data.archive.needsRevision, false);
  assert.deepEqual(Model.load().payables || [], payablesBefore, '归档修订仍不得产生金钱记录');

  page = open('/pages/admin/performance/index?history=1');
  assert.equal(page.route, 'pages/admin/performance/index');
  assert(page.data.desk.performance, '历史指标显式入口仍可查看');
  page = open('/pages/admin/detail/index?view=targets');
  assert.equal(await (await runtime.currentPage()).$('.owner-save'), null, '旧目标页不得保留保存入口');
  assert.equal(await (await runtime.currentPage()).$('[data-action="payroll"]'), null, '旧目标页不得新建薪酬规则');
  page.savePerformanceGoals();
  assert.match(page.data.error, /只读|绩效任务/);

  console.log('PASS 绩效任务派发、本人凭证、真实事实与非金钱化归档修订');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
