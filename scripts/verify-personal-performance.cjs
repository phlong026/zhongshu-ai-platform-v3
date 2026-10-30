const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const keys = require('../miniprogram/utils/storage-keys');
  const Account = require('../miniprogram/utils/account');
  const Model = require('../miniprogram/pages/admin/model');
  const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(require('../miniprogram/pages/admin/owner-performance').facts);
  const period = Account.localDate().slice(0, 7);
  const periodEnd = new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
  const login = (id, role) => Account.mutate(state => {
    Account.loginDemo(state, id);
    if (role) Account.switchIdentity(state, state.session.companyId, role);
  });
  const open = url => { wx.reLaunch({ url }); return getCurrentPages().at(-1); };
  const storageEntries = () => wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]);

  try {
    const management = require('../miniprogram/utils/management-seed')();
    management.period = '2026-09';
    wx.setStorageSync(keys.management, management);
    wx.setStorageSync(keys.business, { version: 1, customers: [] });
    wx.setStorageSync(keys.engineering, { version: 1, projects: [] });
    wx.setStorageSync(keys.growth, { version: 1, contents: [], leads: [], events: [] });
    wx.removeStorageSync(keys.performanceTasks);

    login('boss', 'boss');
    const task = Tasks.create(Model.load(), {
      period, title: '李业务本月签约目标', assigneeId: 'sales-dual', due: periodEnd,
      type: 'auto:contract', target: '16000', idempotencyKey: 'personal-performance:li-contract'
    });
    const publication75 = createTask(Tasks, Model, period, periodEnd, 'auto:publications', '内容发布目标', '4', 'publication-75');
    const publication50 = createTask(Tasks, Model, period, periodEnd, 'auto:publications', '内容发布进阶目标', '6', 'publication-50');
    const publicationOver = createTask(Tasks, Model, period, periodEnd, 'auto:publications', '内容发布保底目标', '1', 'publication-over');
    const leadTask = createTask(Tasks, Model, period, periodEnd, 'auto:effectiveLeads', '有效线索目标', '1', 'lead');
    const manualPending = createTask(Tasks, Model, period, periodEnd, 'manual', '客户回访说明', '', 'manual-pending');
    const manualDone = createTask(Tasks, Model, period, periodEnd, 'manual', '成交经验复盘', '', 'manual-done');

    let page = open('/pages/admin/tasks/index?view=team&period=' + period);
    assert.equal(page.data.canArchive, true, '老板已有任务时归档按钮可见');
    page.setData({ initializationHint: '旧初始化提示' });
    Account.mutate(state => Account.logout(state));
    page.onShow();
    expectCleared(page, '老板注销后');

    login('boss', 'boss');
    page = open('/pages/admin/tasks/index?view=team&period=' + period);
    const actor = require('../miniprogram/utils/access').context();
    const disabled = wx.getStorageSync(keys.management);
    disabled.staff.find(person => person.id === actor.staffId).active = false;
    wx.setStorageSync(keys.management, disabled);
    page.onShow();
    expectCleared(page, '老板被停用后');
    disabled.staff.find(person => person.id === actor.staffId).active = true;
    wx.setStorageSync(keys.management, disabled);

    login('sales-dual', 'sales');
    page = open('/pages/admin/tasks/index?view=mine');
    assert.equal(page.data.period, period, '普通个人入口默认当前月，不使用陈旧账期');
    assert.equal(page.data.myName, '李业务');
    assert.equal(page.data.title, '李业务 · 我的绩效');
    assert.equal(page.data.goalRows.length, 7);
    expectGoal(goal(page, task.id), { targetText: '16000元', collectedText: '0元', percent: 0, progressRight: 0, progressLeft: 0 });
    expectGoal(goal(page, manualPending.id), { percent: 0, progressRight: 0, progressLeft: 0 });

    Tasks.completeManual(Model.load(), manualDone.id, { note: '已完成当月成交复盘', photos: [], idempotencyKey: 'personal-performance:manual-done' });

    wx.setStorageSync(keys.business, { version: 1, customers: [{
      id: 'personal-performance-customer', companyId: 'quanzhou', projectId: 'personal-performance-project', name: '李业务签约客户', ownerId: 'sales-dual',
      contract: { file: { name: '签约合同.pdf' }, actorId: 'sales-dual', personId: 'sales-dual', ownerAtSigning: 'sales-dual', date: period + '-20', total: 4000 }
    }] });
    wx.setStorageSync(keys.growth, {
      version: 1,
      contents: [1, 2, 3].map(index => ({ id: 'personal-content-' + index, companyId: 'quanzhou', status: '演示已发布', authorId: 'sales-dual', authorPersonId: 'sales-dual', publishedAt: period + '-2' + index, pack: { title: '李业务内容' + index } })),
      leads: [{ id: 'personal-lead', companyId: 'quanzhou', status: '已转客户', ownerId: 'sales-dual', ownerPersonId: 'sales-dual', name: '有效线索' }],
      events: [{ id: 'personal-lead', type: 'lead-convert', at: period + '-24T10:00:00+08:00' }]
    });
    page.refresh();
    expectGoal(goal(page, task.id), { targetText: '16000元', collectedText: '4000元', percent: 25, progressRight: 90, progressLeft: 0 });
    expectGoal(goal(page, publication75.id), { targetText: '4条', collectedText: '3条', percent: 75, progressRight: 180, progressLeft: 90 });
    expectGoal(goal(page, publication50.id), { targetText: '6条', collectedText: '3条', percent: 50, progressRight: 180, progressLeft: 0 });
    expectGoal(goal(page, publicationOver.id), { targetText: '1条', collectedText: '3条', percent: 300, progressRight: 180, progressLeft: 180 });
    expectGoal(goal(page, leadTask.id), { percent: 100, progressRight: 180, progressLeft: 180 });
    expectGoal(goal(page, manualPending.id), { percent: 0, progressRight: 0, progressLeft: 0 });
    expectGoal(goal(page, manualDone.id), { percent: 100, progressRight: 180, progressLeft: 180 });
    assert(goal(page, task.id).sources.some(source => source.id === 'perf-contract:personal-performance-customer' && source.url.includes('personal-performance-customer')), '真实签约完成后需保留原单下钻');
    const nativeEntries = storageEntries();

    page.month({ detail: { value: '2026-09' } });
    assert.equal(page.data.period, '2026-09');
    assert.deepEqual(page.data.goalRows, [], '切换到无目标月份显示空态');
    page.month({ detail: { value: period } });
    assert.equal(page.data.goalRows[0].id, task.id);

    page = open('/pages/account/index?view=personal-performance&period=' + period);
    assert.equal(page.route, 'pages/admin/tasks/index');
    assert.equal(page.data.view, 'mine');
    assert.equal(page.data.period, period, '旧显式月份入口应保留月份');

    Account.mutate(state => {
      state.messages.unshift({ id: 'legacy-performance-message', companyId: state.session.companyId, userId: state.session.userId, type: '系统', title: '九月绩效', description: '查看历史目标', route: 'personal-performance', objectId: '2026-09', date: '2026-10-01', read: false });
    });
    page = open('/pages/account/index?view=messages');
    page.read({ currentTarget: { dataset: { id: 'legacy-performance-message' } } });
    page = getCurrentPages().at(-1);
    assert.equal(page.route, 'pages/admin/tasks/index');
    assert.equal(page.data.period, '2026-09', '旧绩效消息 objectId 应转为历史月份');

    login('manager-wang', 'engineer');
    page.onShow();
    assert.equal(page.data.period, period, '历史消息页切换真实人员后应回当前月');
    assert.equal(page.initialPeriod, '', '历史入口月份不得跨真实人员保留');
    assert.equal(page.data.myName, '王工程师');
    assert.deepEqual(page.data.goalRows, []);

    login('sales-dual', 'sales');
    page = open('/pages/admin/tasks/index?view=mine');
    assert.equal(page.data.goalRows[0].id, task.id);
    login('manager-wang', 'engineer');
    page.onShow();
    assert.equal(page.data.myName, '王工程师');
    assert.deepEqual(page.data.rows, []);
    assert.deepEqual(page.data.goalRows, [], '切换真实人身份后不得残留李业务目标');

    login('sales-dual', 'sales');
    page.onShow();
    assert.equal(page.data.goalRows.length, 7);

    const redirectTo = wx.redirectTo, redirects = [];
    wx.redirectTo = options => redirects.push(options);
    page = open('/pages/account/index?view=personal-performance&period=' + period);
    page.onShow();
    assert.equal(redirects.length, 1, '异步跳转未完成时 onLoad/onShow 只能发起一次');
    redirects[0].fail({ errMsg:'redirectTo:fail timeout' });
    assert.match(page.data.error, /绩效页面打开失败/, '跳转失败应给出可见错误');
    page.refresh();
    assert.equal(redirects.length, 2, '跳转失败后应允许重试');
    wx.redirectTo = redirectTo;

    page = open('/pages/admin/tasks/index?view=mine');
    Account.mutate(state => Account.logout(state));
    page.onShow();
    assert(page.data.error, '异常或登录失效时应显示加载错误');
    assert.deepEqual(page.data.rows, []);
    assert.deepEqual(page.data.goalRows, [], '加载异常后不得保留上一个真实人的目标');
    page = open('/pages/account/index?view=personal-performance&period=2026-09');
    assert.equal(page.route, 'pages/account/index');
    assert.equal(page.data.view, 'login', '游客访问旧绩效入口仍应先通过登录守卫');
    assert(decodeURIComponent(page.options.return).includes('period=2026-09'), '游客登录前也应保留旧消息的显式月份');
    page.setData({ selectedDemoUser: 'sales-dual' });
    page.loginDemo();
    page = getCurrentPages().at(-1);
    assert.equal(page.route, 'pages/admin/tasks/index');
    assert.equal(page.data.period, '2026-09', '登录后回到统一绩效页并保留历史月份');

    const accountWxml = fs.readFileSync('miniprogram/pages/account/index.wxml', 'utf8');
    assert(!accountWxml.includes('与工程组王经理') && !accountWxml.includes("view==='personal-performance'"), '旧写死绩效占位块应删除');

    const outDir = path.resolve('qa/personal-performance-20261030');
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(outDir, 'fixture.json'), JSON.stringify({
      route: '/pages/admin/tasks/index?view=mine', period, taskId: task.id,
      expected: { myName: '李业务', targetText: '16000元', collectedText: '4000元', percent: 25, publicationTarget: '4条', publicationActual: '3条', publicationPercent: 75 },
      rows: nativeEntries
    }, null, 2) + '\n');

    console.log('PASS 老板派发目标、李业务实绩更新、身份隔离与旧入口统一');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });

function expectGoal(actual, expected) {
  for (const [key, value] of Object.entries(expected)) assert.equal(actual[key], value, key);
}

function goal(page, id) {
  const row = page.data.goalRows.find(item => item.id === id);
  assert(row, '缺少绩效目标 ' + id);
  return row;
}

function createTask(Tasks, Model, period, due, type, title, target, key) {
  return Tasks.create(Model.load(), { period, title, assigneeId: 'sales-dual', due, type, target, idempotencyKey: 'personal-performance:' + key });
}

function expectCleared(page, label) {
  assert(page.data.error, label + '应显示无效身份错误');
  assert.equal(page.state, null, label + '不得保留旧账簿');
  assert.equal(page.data.view, 'mine', label + '不得保留老板视图');
  assert.equal(page.data.title, '绩效任务', label + '不得保留老板标题');
  assert.equal(page.data.period, '', label + '不得保留历史月份');
  assert.equal(page.data.periodEnd, '');
  assert.deepEqual(page.data.tabs, [], label + '不得保留老板页签');
  assert.deepEqual(page.data.rows, []);
  assert.deepEqual(page.data.goalRows, []);
  assert.deepEqual(page.data.staff, []);
  assert.equal(page.data.archive, null);
  assert.equal(page.data.myName, '');
  assert.equal(page.data.canArchive, false, label + '不得保留归档权限');
  assert.equal(page.data.canInitialize, false, label + '不得保留初始化权限');
  assert.equal(page.data.initializationHint, '', label + '不得保留提示');
}
