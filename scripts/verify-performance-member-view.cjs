const assert = require('node:assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const keys = require('../miniprogram/utils/storage-keys');
  const Account = require('../miniprogram/utils/account');
  const Model = require('../miniprogram/pages/admin/model');
  const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(require('../miniprogram/pages/admin/owner-performance').facts);
  const OrgViews = require('../miniprogram/pages/admin/organization-views-v2');
  const period = Account.localDate().slice(0, 7);
  const historicalPeriod = new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)) - 2, 1)).toISOString().slice(0, 7);
  const due = new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
  const login = (id, role) => Account.mutate(state => { Account.loginDemo(state, id); if (role) Account.switchIdentity(state, state.session.companyId, role); });
  const create = (assigneeId, type, target, key) => Tasks.create(Model.load(), { period, due, assigneeId, type, target:String(target ?? ''), title:key, idempotencyKey:'member:' + key });
  const open = url => { wx.reLaunch({ url }); return getCurrentPages().at(-1); };

  try {
    wx.setStorageSync(keys.management, require('../miniprogram/utils/management-seed')());
    wx.removeStorageSync(keys.performanceTasks);
    login('boss', 'boss');
    const management = wx.getStorageSync(keys.management);
    management.period = period;
    management.staff.push(
      { id:'zhang-alias', personId:'zhang', name:'张业务兼岗', active:true, roles:['sales'], roleId:'sales', teamIds:{ sales:'sales' } },
      { id:'sales-west', personId:'sales-west', name:'西区业务', active:true, roles:['sales'], roleId:'sales', teamIds:{ sales:'sales-west' } },
      { id:'engineer-west', personId:'engineer-west', name:'西区工程', active:true, roles:['engineer'], roleId:'engineer', teamIds:{ engineering:'engineering-west' } },
      { id:'sales-disabled', personId:'sales-disabled', name:'停用业务', active:false, roles:['sales'], roleId:'sales', teamIds:{ sales:'sales' } }
    );
    wx.setStorageSync(keys.management, management);
    wx.setStorageSync(keys.business, { version:1, customers:[{
      id:'member-customer', companyId:'quanzhou', projectId:'member-project', name:'张业务客户', ownerId:'zhang',
      contract:{ file:{ name:'真实合同.pdf' }, actorId:'zhang', personId:'zhang', date:period + '-10', total:1000 }
    }] });
    wx.setStorageSync(keys.engineering, { version:1, projects:[] });
    wx.setStorageSync(keys.growth, { version:1, contents:[], leads:[], events:[] });

    login('boss', 'boss');
    const zhangContract = create('zhang', 'auto:contract', 1000, '张业务签约目标');
    const zhangAlias = create('zhang-alias', 'manual', '', '张业务兼岗复盘');
    const historicalTask = Tasks.create(Model.load(), { period:historicalPeriod, due:historicalPeriod + '-28', assigneeId:'zhang', type:'manual', title:'张业务历史目标', idempotencyKey:'member:历史目标' });
    create('sales-west', 'manual', '', '西区业务目标');
    create('li', 'manual', '', '李工程目标');
    create('engineer-west', 'manual', '', '西区工程目标');
    create('li-finance', 'manual', '', '李财务目标');

    login('sales-dual', 'sales-lead');
    const salesRows = Tasks.list(Model.load(), { scope:'member', staff:'zhang', period });
    assert.deepEqual(new Set(salesRows.map(row => row.assigneePersonId)), new Set(['zhang']), '负责人只读取目标真实 person');
    assert.deepEqual(new Set(salesRows.map(row => row.id)), new Set([zhangContract.id, zhangAlias.id]), '同 person 多 staff 任务不得拆开');
    assert.equal(salesRows.find(row => row.id === zhangContract.id).collected, 1000, '成员页必须展示当前真实完成值');
    assert.throws(() => Tasks.list(Model.load(), { scope:'member', staff:'sales-west', period }), /管理团队|无权/, '业务负责人不得查其他团队');
    assert.throws(() => Tasks.list(Model.load(), { scope:'member', staff:'sales-disabled', period }), /在岗|无权/);
    assert.throws(() => Tasks.list(Model.load(), { scope:'member', staff:'hq-design-admin', period }), /在岗|无权/, '不得跨公司查人');

    let page = open('/pages/admin/tasks/index?view=member&person=zhang');
    assert.equal(page.data.view, 'member');
    assert.equal(page.data.myName, '张业务');
    assert.equal(page.data.goalRows.length, 2);
    assert.equal(page.data.goalRows.find(row => row.id === zhangContract.id).percent, 100);
    assert.deepEqual(page.data.tabs, [], '成员页不展示任务管理页签');
    assert.equal(page.data.canArchive, false);
    const before = Tasks.list(Model.load(), { scope:'member', staff:'zhang', period });
    page.beginManual({ currentTarget:{ dataset:{ id:zhangAlias.id } } });
    assert.equal(page.data.evidenceTask, null, '只读页不得打开完成凭证');
    page.archive();
    assert.match(page.data.error, /仅供查看/);
    assert.deepEqual(Tasks.list(Model.load(), { scope:'member', staff:'zhang', period }), before, '只读页不得修改任务');

    page = open('/pages/admin/tasks/index?view=member&person=zhang&period=' + historicalPeriod);
    assert.equal(page.data.period, historicalPeriod, '成员页允许只读切换合法历史月份');
    assert.deepEqual(page.data.rows.map(row => row.id), [historicalTask.id]);
    page.month({ detail:{ value:period } });
    assert.equal(page.data.period, period, '成员页月份选择不是写操作');

    const salesView = OrgViews.config('business-team', Model.visible(Model.load()), {}, '');
    const salesLink = salesView.sections.flatMap(section => section.rows).find(row => row.label === '张业务');
    assert.equal(salesLink.url, '/pages/admin/tasks/index?view=member&person=zhang');
    assert.notEqual(salesLink.view, 'targets', '负责人团队成员不再进入旧目标档案');
    assert.match(salesLink.subtitle, /查看月度绩效目标/);
    assert.doesNotMatch(salesLink.subtitle, /考核/, '团队卡片不得继续展示旧绩效档案状态');

    page = open('/pages/admin/tasks/index?view=member&person=zhang');
    login('sales-dual', 'sales');
    page.onShow();
    assert.equal(page.data.view, 'mine', '同一人切换岗位身份也必须丢弃负责人 member 参数');
    assert.equal(page.requestedMemberStaffId, '');
    assert.equal(page.data.error, '', '身份切换应预清负责人参数，不应以越权错误兜底');

    login('engineering-dual', 'project-manager');
    const visibleEngineering = Model.visible(Model.load()).staff.map(person => person.id);
    assert(visibleEngineering.includes('li') && visibleEngineering.includes('engineering-dual'), '工程负责人保留本人和已授权团队');
    assert(!visibleEngineering.includes('engineer-west'), '工程负责人不得看其他团队成员');
    const engineerRows = Tasks.list(Model.load(), { scope:'member', staff:'li', period });
    assert.equal(engineerRows.length, 1);
    assert.throws(() => Tasks.list(Model.load(), { scope:'member', staff:'engineer-west', period }), /管理团队|无权/);
    const engineeringView = OrgViews.config('engineering-team', Model.visible(Model.load()), {}, '');
    const engineerLink = engineeringView.sections.flatMap(section => section.rows).find(row => row.label === '李工程师');
    assert.equal(engineerLink.url, '/pages/admin/tasks/index?view=member&person=li', '工程专员必须出现并进入新目标页');

    page = open('/pages/admin/tasks/index?view=member&person=li');
    login('sales-dual', 'sales-lead');
    page.onShow();
    assert.equal(page.data.view, 'mine', '身份变化后必须丢弃原 member 参数');
    assert.equal(page.requestedMemberStaffId, '');

    page = open('/pages/admin/tasks/index?view=member&person=sales-west');
    assert(page.data.error);
    assert.equal(page.data.view, 'mine');
    assert.equal(page.state, null);
    assert.deepEqual(page.data.rows, []);
    assert.deepEqual(page.data.goalRows, []);
    assert.equal(page.requestedMemberStaffId, '', '成员授权失败后必须清除参数');

    login('finance-dual', 'finance-lead');
    assert.throws(() => Tasks.list(Model.load(), { scope:'member', staff:'li-finance', period }), /无权/, '财务负责人不新增他人绩效查看权');
    assert(Tasks.list(Model.load(), { scope:'member', staff:'finance-dual', period }).every(row => row.assigneePersonId === 'finance-dual'), '财务负责人仅可按 member 查本人');

    console.log('PASS 负责人成员绩效只读、团队权限、兼岗合并与参数清理');
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
