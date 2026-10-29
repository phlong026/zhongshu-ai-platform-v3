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
  page.submitTask({ detail:{ value:{ title:'财务月度核对', assigneeId:'finance-dual', due, type:'manual', target:'', bonus:'30', note:'按原账簿完成核对' } } });
  const task = Tasks.list(Model.load(), { scope:'company', period }).find(row => row.title === '财务月度核对');
  assert(task);

  login('finance-dual', 'finance');
  open('/pages/profile/index');
  assert((await (await control('.quick-card [data-action="performance"]')).text()).includes('我的任务'));
  await (await control('.quick-card [data-action="performance"]')).tap();
  page = getCurrentPages().at(-1);
  assert.equal(page.route, 'pages/admin/tasks/index');
  assert.equal(page.data.view, 'mine');
  assert.equal(await (await runtime.currentPage()).$('.initialize-previous'), null, '员工页不得显示组织任务初始化');
  await (await control('.task-actions button[data-id="' + task.id + '"]')).tap();
  page.completeManual({ detail:{ value:{ note:'已完成月度核对' } } });
  assert.equal(Tasks.list(Model.load(), { scope:'mine', period }).find(row => row.id === task.id).status, '已完成');

  login('boss', 'boss');
  page = open('/pages/admin/tasks/index?view=team');
  page.archive();
  assert.equal(page.data.view, 'bonus');
  await (await control('.generate-payables')).tap();
  assert.equal(page.data.payableRows.length, 1);
  assert.equal(page.data.payableRows[0].remaining, '30');

  login('finance-dual', 'finance');
  page = open('/pages/admin/tasks/index?view=bonus');
  assert.equal(page.data.payableRows.length, 1);
  await (await control('.bonus-payable [data-url="' + page.data.payableRows[0].url + '"]')).tap();
  assert.equal(getCurrentPages().at(-1).route, 'pages/admin/index');
  assert.equal(getCurrentPages().at(-1).data.view, 'pay');

  const growth = clone(wx.getStorageSync(keys.growth));
  growth.contents.push({ id:'ui-source-change', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:period + '-20', pack:{title:'来源变化'} });
  wx.setStorageSync(keys.growth, growth);
  page = open('/pages/admin/tasks/index?view=bonus');
  assert.equal(page.data.bonus.needsRevision, true);
  assert.equal(await (await runtime.currentPage()).$('.generate-payables'), null, '原单变化时不得继续生成应付');

  login('boss', 'boss');
  const septemberGrowth = clone(wx.getStorageSync(keys.growth));
  septemberGrowth.contents.push({ id:'ui-september-source', companyId:'quanzhou', status:'演示已发布', authorId:'finance-dual', publishedAt:'2026-09-20', pack:{title:'九月发布'} });
  wx.setStorageSync(keys.growth, septemberGrowth);
  Tasks.create(Model.load(), { period:'2026-09', title:'九月发布奖金', assigneeId:'finance-dual', due:'2026-09-30', type:'auto:publications', target:'1', bonus:'15', idempotencyKey:'ui:september-task' });
  const septemberArchive = Tasks.archive(Model.load(), '2026-09', { note:'九月奖金归档', idempotencyKey:'ui:september-archive' });
  const septemberDraft = Tasks.payoutDraft(Model.load(), septemberArchive.id);
  const septemberPayableId = Model.mutate(state => Model.apply(state, 'performance-bonus-register', septemberArchive.id, { idempotencyKey:septemberDraft.idempotencyKey }));
  const withoutSeptemberSource = clone(wx.getStorageSync(keys.growth));
  withoutSeptemberSource.contents = withoutSeptemberSource.contents.filter(row => row.id !== 'ui-september-source');
  wx.setStorageSync(keys.growth, withoutSeptemberSource);
  Tasks.reviseArchive(Model.load(), '2026-09', { note:'原发布记录撤销', idempotencyKey:'ui:september-archive:r2' });
  page = open('/pages/admin/tasks/index?view=bonus&period=2026-09');
  assert.equal(page.data.payoutDraft.total, 0);
  assert.equal(page.data.canGeneratePayables, true, '零奖金修订必须保留旧应付核销入口');
  assert((await (await control('.generate-payables')).text()).includes('核销'));
  await (await control('.generate-payables')).tap();
  assert(!Model.load().payables.some(row => row.id === septemberPayableId), '页面核销动作应移除旧版未付应付');

  page = open('/pages/admin/performance/index?history=1');
  assert.equal(page.route, 'pages/admin/performance/index');
  assert(page.data.desk.performance, '历史指标显式入口仍可查看');
  page = open('/pages/admin/detail/index?view=targets');
  assert.equal(await (await runtime.currentPage()).$('.owner-save'), null, '旧目标页不得保留保存入口');
  assert.equal(await (await runtime.currentPage()).$('[data-action="payroll"]'), null, '旧目标页不得新建薪酬规则');
  page.savePerformanceGoals();
  assert.match(page.data.error, /只读|绩效任务/);

  console.log('PASS 绩效任务三角色页面事件、真实应付衔接与旧入口只读退役');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
