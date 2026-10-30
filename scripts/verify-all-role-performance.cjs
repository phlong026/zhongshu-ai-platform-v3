const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const keys = require('../miniprogram/utils/storage-keys');
  const Account = require('../miniprogram/utils/account');
  const Model = require('../miniprogram/pages/admin/model');
  const Tasks = require('../miniprogram/utils/performance-tasks').withFacts(require('../miniprogram/pages/admin/owner-performance').facts);
  const Access = require('../miniprogram/utils/access');
  const period = Account.localDate().slice(0, 7);
  const fixtures = [];
  const due = new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
  const login = (id, role) => Account.mutate(state => {
    Account.loginDemo(state, id);
    if (role) Account.switchIdentity(state, state.session.companyId, role);
  });
  const openMine = () => { wx.reLaunch({ url:'/pages/admin/tasks/index?view=mine' }); return getCurrentPages().at(-1); };
  const create = (assigneeId, type, target, key) => Tasks.create(Model.load(), {
    period, title:key + '目标', assigneeId, due, type, target:String(target ?? ''), idempotencyKey:'all-role:' + key
  });
  const goal = (page, id) => {
    const row = page.data.goalRows.find(item => item.id === id);
    assert(row, '未找到本人目标 ' + id);
    return row;
  };

  try {
    wx.setStorageSync(keys.management, require('../miniprogram/utils/management-seed')());
    wx.removeStorageSync(keys.performanceTasks);
    login('boss', 'boss');

    const root = wx.getStorageSync(keys.management), headquarters = root.books.headquarters;
    root.period = period;
    headquarters.period = period;
    root.receipts = [{
      id:'all-role-store-receipt', projectId:'all-role-store-project', companyId:'quanzhou', customer:'门店客户', status:'已核算', amount:100000,
      bankDate:period + '-12', date:period + '-12', financialRegistrarId:'finance-dual', financialRegistrarPersonId:'finance-dual', reviewerId:'chen-finance-lead', reviewerPersonId:'chen-finance-lead', reviewedAt:period + '-12T10:00:00+08:00'
    }];
    root.payments = [{
      id:'all-role-store-payment', projectId:'all-role-store-project', companyId:'quanzhou', customer:'门店客户', status:'已核实', amount:20000,
      date:period + '-13', registrarId:'finance-dual', registrarPersonId:'finance-dual'
    }];
    headquarters.receipts = [{
      id:'all-role-hq-receipt', projectId:'all-role-design-project', companyId:'headquarters', customer:'总部客户', status:'已核算', amount:200000,
      bankDate:period + '-16', date:period + '-16', financialRegistrarId:'hq-finance-dual', financialRegistrarPersonId:'hq-finance-dual'
    }];
    headquarters.payments = [];
    wx.setStorageSync(keys.management, root);
    wx.setStorageSync(keys.business, { version:1, customers:[
      {
        id:'all-role-sales-customer', companyId:'quanzhou', projectId:'all-role-store-project', name:'李业务客户', ownerId:'sales-dual',
        contract:{ file:{ name:'真实合同.pdf' }, actorId:'sales-dual', personId:'sales-dual', date:period + '-10', total:4000 }
      },
      {
        id:'all-role-design-customer', companyId:'quanzhou', designOrganizationId:'headquarters', projectId:'all-role-design-project', name:'总部设计客户',
        request:{ version:1 }, businessFlow:{ type:'sales-offline-v1', completed:{ requestVersion:1, actorId:'hq-design-admin', personId:'hq-design-admin', at:period + '-15T09:00:00+08:00' } }
      }
    ] });
    wx.setStorageSync(keys.engineering, { version:1, projects:[{
      id:'all-role-engineering', companyId:'quanzhou', projectId:'all-role-store-project', customer:'工程客户', managerId:'engineering-dual',
      plan:{ nodes:[{ id:'all-role-node', name:'基础验收', originalEnd:period + '-20', end:period + '-20', status:'已完成', completedById:'engineering-dual', completedByPersonId:'engineering-dual', acceptedAt:period + '-18' }] },
      inspections:[{ id:'all-role-inspection', nodeId:'all-role-node', result:'通过', date:period + '-18', submittedById:'engineering-dual', submittedByPersonId:'engineering-dual' }],
      supplies:[{ id:'all-role-supply', quantity:10, date:period + '-22', applicantId:'engineering-dual', applicantPersonId:'engineering-dual' }],
      receipts:[{ id:'all-role-material-receipt', supplyId:'all-role-supply', actual:10, quality:'合格', date:period + '-19' }], reships:[]
    }] });
    wx.setStorageSync(keys.growth, { version:1, contents:[], leads:[], events:[] });

    login('boss', 'boss');
    const sales = create('sales-dual', 'auto:contract', 4000, '业务签约');
    const node = create('engineering-dual', 'auto:completedNodes', 1, '工程节点');
    const material = create('engineering-dual', 'auto:qualifiedDeliveries', 1, '工程到货');
    const finance = create('finance-dual', 'auto:financeRegistrations', 2, '财务登记');
    const review = create('chen-finance-lead', 'auto:financeReviews', 1, '财务复核');
    const boss = create('zhao-boss', 'manual', '', '老板复盘');
    const owner = create('store-owner', 'manual', '', '门店负责人复盘');

    assertRole('sales-dual', 'sales', sales, 4000, ['perf-contract:all-role-sales-customer']);
    assertRole('sales-dual', 'sales-lead', sales, 4000, ['perf-contract:all-role-sales-customer']);
    assertRole('engineering-dual', 'engineer', node, 1, ['perf-node:all-role-engineering:all-role-node']);
    assertRole('engineering-dual', 'project-manager', material, 1, ['perf-material:all-role-engineering:all-role-supply']);
    assertRole('finance-dual', 'finance', finance, 2, ['perf-finance-in:all-role-store-receipt', 'perf-finance-pay:all-role-store-payment']);
    assertRole('finance-dual', 'finance-lead', finance, 2, ['perf-finance-in:all-role-store-receipt', 'perf-finance-pay:all-role-store-payment']);
    assertRole('finance-lead', 'finance-lead', review, 1, ['perf-finance-in-review:all-role-store-receipt']);
    assertRole('boss', 'boss', boss, 0, []);
    assertRole('boss', 'owner', boss, 0, []);
    assertRole('store-owner', 'owner', owner, 0, []);
    login('finance-wang', 'finance');
    assert.equal(openMine().data.goalRows.length, 0, '其他真实人员不得看到王财务兼岗目标');

    login('hq-boss', 'boss');
    const design = create('hq-design-admin', 'auto:approvedDeliverables', 1, '总部设计完成');
    const hqFinance = create('hq-finance-dual', 'auto:financeRegistrations', 1, '总部财务登记');
    const hqBoss = create('hq-boss', 'manual', '', '总部老板复盘');
    assertRole('hq-design-admin', 'hq-design-admin', design, 1, ['perf-design:all-role-design-customer:1']);
    assertRole('hq-finance-dual', 'finance', hqFinance, 1, ['perf-finance-in:all-role-hq-receipt']);
    assertRole('hq-finance-dual', 'finance-lead', hqFinance, 1, ['perf-finance-in:all-role-hq-receipt']);
    assertRole('hq-boss', 'boss', hqBoss, 0, []);
    assert(!openMine().data.goalRows.some(row => row.id === finance.id), '总部公司不得读到加盟商财务目标');

    const outDir = path.resolve('qa/all-role-performance-20261030');
    fs.mkdirSync(outDir, { recursive:true });
    fs.writeFileSync(path.join(outDir, 'fixtures.json'), JSON.stringify({ period, fixtures }, null, 2) + '\n');

    console.log('PASS 全岗位绩效目标、真实事实、兼岗与跨公司隔离');

    function assertRole(loginId, role, task, collected, sourceIds) {
      login(loginId, role);
      wx.reLaunch({ url:'/pages/profile/index' });
      getCurrentPages().at(-1).open({ currentTarget:{ dataset:{ action:'performance' } } });
      const entry = getCurrentPages().at(-1);
      assert.equal(entry.route, 'pages/admin/tasks/index', role + ' 的个人中心必须进入统一绩效页面');
      assert.equal(entry.data.view, Access.isBoss() ? 'team' : 'mine');
      const page = openMine(), row = goal(page, task.id);
      assert.equal(row.collected, collected, loginId + '/' + role + ' 完成值必须来自真实事实');
      assert.equal(row.percent, task.target ? collected / task.target * 100 : 0);
      assert.deepEqual(row.sourceIds.slice().sort(), sourceIds.slice().sort(), loginId + '/' + role + ' 须保留原单明细');
      assert(row.sources.every(source => source.url.startsWith('/pages/')), '自动事实须可下钻');
      assert.equal(page.data.period, period);
      const actor = Access.context();
      fixtures.push({
        id:[actor.companyId, role, loginId].join(':'), loginId, expectedPerson:actor.personId, name:actor.name, role, company:actor.companyId,
        taskId:task.id, target:row.target, collected:row.collected, percent:row.percent,
        route:'/pages/admin/tasks/index?view=mine', profileRoute:'/pages/profile/index',
        rows:wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)])
      });
    }
  } finally {
    await runtime.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
