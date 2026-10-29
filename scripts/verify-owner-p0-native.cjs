const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const directory = path.resolve(process.env.OWNER_P0_NATIVE_OUT || 'qa/owner-p0-completion-20261023/native');
const source = path.resolve(process.env.ENGINEERING_CONTINUATION_SOURCE || 'qa/full-flow-acceptance-20261023/native-engineering/continuous-final-state.json');
const clone = value => JSON.parse(JSON.stringify(value));
const sourceRows = JSON.parse(fs.readFileSync(source, 'utf8'));
const value = (rows, key) => rows.find(([name]) => name === key)?.[1];

function completeFixture(rows) {
  const next = clone(rows);
  const management = value(next, 'zhongshu-management-v1');
  const business = value(next, 'zhongshu-business-v3');
  const account = value(next, 'zhongshu-account-v1');
  const companyId = 'quanzhou';
  const book = management.companyId === companyId ? management : management.books[companyId];
  const owner = book.staff.find(person => person.id === 'sales-dual' && person.active !== false && (person.roles || []).includes('sales'));
  const base = business.customers.find(customer => customer.id === 'customer-chen');
  assert(book && owner && base, '主链最终数据须包含门店账簿、业务人员和客户原单');
  const today = String(book.period || '2026-10') + '-23';
  const names = ['周女士','吴先生','郑女士','孙先生','何女士','高先生','林女士','陈先生','黄女士','罗先生','宋女士','邓先生','梁女士','谢先生','唐女士','韩先生','曹女士','潘先生','彭女士','许先生','冯女士'];
  for (let index = 0; index < names.length; index++) {
    const id = 'owner-p0-page-' + String(index + 1).padStart(2, '0');
    if (business.customers.some(customer => customer.id === id)) continue;
    assert(!base.request && !base.handover && !base.plans.length && !base.budgets.length, '分页使用明确的无后续流程模板');
    const customer = clone(base);
    Object.assign(customer, {
      id,
      projectId: 'project-' + id,
      customerUserId: '',
      name: names[index],
      phone: '13988' + String(100000 + index).slice(-6),
      companyId,
      ownerId: owner.id,
      owner: owner.name,
      createdById: owner.id,
      createdAt: today + 'T08:00:00.000Z',
      status: index === 0 ? '已到店' : '跟进中',
      businessFlow: null,
      serviceOrders: [],
      quotes: [],
      contract: null,
      acceptance: null,
      follows: [{ method: index === 0 ? '到店' : '电话', result: index === 0 ? '已到店' : '已联系', next: index === 1 ? '到店沟通' : '电话联系', appointment: today + (index === 1 ? ' 10:00' : ' 16:30'), date: today, author: owner.name, authorId: owner.id, content: '已核实建房需求与下一步沟通安排' }]
    });
    business.customers.push(customer);
  }
  const leadCustomer = business.customers.find(customer => customer.id === 'owner-p0-page-01');
  const existingGrowth = value(next, 'zhongshu-growth-v1') || { version:1, contents:[], leads:[], events:[] };
  const growth = {
    ...existingGrowth,
    version: 1,
    contents: [...(existingGrowth.contents || []), { id:'owner-p0-content', companyId, authorId:owner.id, authorName:owner.name, status:'演示已发布', publishedAt:today + 'T09:00:00.000Z', pack:{title:'武汉乡墅采光设计', platform:'视频号'} }],
    leads: [...(existingGrowth.leads || []), { id:'owner-p0-lead', companyId, ownerId:owner.id, registrarId:owner.id, name:leadCustomer.name, phone:leadCustomer.phone, source:'内容咨询', contentId:'owner-p0-content', customerId:leadCustomer.id, status:'已转客户', at:today + 'T10:00:00.000Z' }],
    events: [...(existingGrowth.events || []), { id:'owner-p0-lead', type:'lead-convert', at:today + 'T10:05:00.000Z', actorId:owner.id, by:owner.name }]
  };
  const growthIndex = next.findIndex(([key]) => key === 'zhongshu-growth-v1');
  if (growthIndex >= 0) next[growthIndex][1] = growth;
  else next.push(['zhongshu-growth-v1', growth]);
  account.session.companyId = companyId;
  return next;
}

(async () => {
  assert(process.env.FRANCHISE_WS, '请通过 FRANCHISE_WS 连接已就绪的隔离微信开发者工具');
  fs.mkdirSync(directory, { recursive: true });
  const runtime = require('./franchise-native-runtime.cjs');
  const r = await runtime.connect('owner-p0', { directory, wsEndpoint: process.env.FRANCHISE_WS, realEvents: true });
  const priorFile = path.join(directory,'failure.json');
  const prior = process.env.OWNER_P0_RESUME === '1' && fs.existsSync(priorFile) ? JSON.parse(fs.readFileSync(priorFile)) : null;
  const checks = prior?.checks || [], screenshots = prior?.screenshots || [], scenarios = prior?.scenarios || checks.map((name,index)=>({id:'M'+String(index+1).padStart(2,'0'),name,fixture:'fixtures/M'+String(index+1).padStart(2,'0')+'.json'}));
  const pass = async name => { const id='M'+String(scenarios.length+1).padStart(2,'0'),fixture='fixtures/'+id+'.json';fs.mkdirSync(path.join(directory,'fixtures'),{recursive:true});fs.writeFileSync(path.join(directory,fixture),JSON.stringify(await r.snapshot(),null,2));scenarios.push({id,name,fixture});checks.push(name);console.log('PASS '+name); };
  const shot = async name => { screenshots.push(await r.capture(name)); };
  const data = () => r.data();
  const openHome = () => r.open('/pages/admin/home/index', '.home-six', { relaunch: true });
  try {
    await r.replace(prior ? JSON.parse(fs.readFileSync(path.join(directory,scenarios.at(-1).fixture))) : completeFixture(sourceRows));
    await r.login('boss');
    await openHome();
    if (checks.length < 1) {
    const expectedMetrics = ['commission-amount','income','commission-count','new-leads','effective-customers','visited-customers'];
    assert.deepEqual((await data()).desk.home.six.map(row => row.metric), expectedMetrics);
    for (const metric of expectedMetrics) {
      await openHome();
      await r.event({ selector:'.home-six-item', dataset:{ key:'stat:' + metric } }, 'go');
      await r.until(async () => (await data()).view === 'stat', '经营指标明细 ' + metric);
      await r.sleep(650);
      const detail = await data();
      assert(detail.total > 0, metric + ' 须有主链或明确模拟原单');
      const mainCustomer = value(sourceRows, 'zhongshu-business-v3').customers.find(row => row.phone === '13900008823');
      const original = mainCustomer ? detail.rows.find(row => row.name.includes(mainCustomer.name)) : detail.rows[0];
      assert(original, metric + ' 明细必须包含同一主链客户');
      await r.event('.owner-row[data-key="' + original.key + '"]', 'go');
      await r.until(async () => (await r.current()).route !== 'pages/admin/detail/index', metric + ' 原单跳转');
    }
    await pass('六项经营统计通过 Element.tap 逐项进入明细并回到原单');
    }


    if (checks.length < 2) {
    for (const key of ['attention:appointments','attention:content']) {
      await openHome();
      await r.event({ selector:'.home-note', dataset:{ key } }, 'go');
      await r.until(async () => (await data()).view === 'stat', key + ' 明细');
      assert((await data()).total > 0);
    }
    await pass('预约到店与内容获客汇总可点击追溯对应原记录');
    }


    if (checks.length < 3) {
    await openHome();
    await r.event({ selector:'.home-chip', dataset:{ tab:'handover' } }, 'todoTab');
    const handovers = (await data()).homeTodos;
    if (handovers.length) {
      assert(handovers.every(row => row.group === 'handover' && row.url.includes('view=transfer')));
      for (const handover of handovers) {
        await openHome();
        await r.event({ selector:'.home-chip', dataset:{ tab:'handover' } }, 'todoTab');
        await r.sleep(650);
        await r.event('button[data-key="' + handover.key + '"]', 'go');
        await r.until(async () => (await data()).view === 'transfer', '责任交接原记录');
        const page = await data();
        const options = page.sections.flatMap(section => section.rows).find(row => row.type === 'picker' && row.key === 'assignee').options;
        const index = options.findIndex(person => person.id === 'sales-dual');
        assert(index >= 0, '责任交接给李业务本人');
        await r.event('picker[data-key="assignee"]', 'pick', { value:index });
        await r.event('textarea[data-key="note"]', 'input', { value:'原经办人离岗，张老板将原事项转交李业务继续办理' });
        await r.event('button[data-key="checked"]', 'toggle');
        await r.event('[data-handler="submit"]', 'submit');
        await r.until(async () => !!(await data()).modal, '交接确认');
        await r.event('.sheet [data-handler="confirm"]', 'confirm');
        await r.until(async () => !(await data()).modal && !(await data()).busy, '原事项交接保存');
        assert.equal((await data()).error, '');
        const workId = handover.key.slice('handover:'.length);
        const work = await r.mp.evaluate(id => {
          const raw = wx.getStorageSync('zhongshu-management-v1');
          return (raw.companyId === 'quanzhou' ? raw : raw.books.quanzhou).work.find(row => row.id === id);
        }, workId);
        assert.equal(work.owner, 'sales-dual');
        assert.notEqual(work.author, 'sales-dual', '原登记人历史保留，当前责任转交给李业务');
      }
    } else {
      assert.equal((await data()).homeTodoTotal, 0);
      assert(await r.rects(['.owner-empty']).then(rows => rows[0]));
    }
    await openHome();
    await r.event({ selector:'.home-chip', dataset:{ tab:'handover' } }, 'todoTab');
    assert.equal((await data()).homeTodoTotal, 0, '交接完成关闭原待办');
    await pass('交接分组从内部责任原记录办理，并覆盖无待办空状态');
    }


    if (checks.length < 4) {
    await openHome();
    await r.event({ selector:'.home-six-item', dataset:{ key:'stat:effective-customers' } }, 'go');
    await r.until(async () => (await data()).view === 'stat', '有效客户明细');
    assert((await data()).total > 20);
    assert.equal((await data()).rows.length, 20);
    await r.until(async()=>!!(await r.rects(['.owner-save']))[0], '分页控件渲染完成');
    await r.event('.owner-save', 'loadMore');
    assert.equal((await data()).rows.length, (await data()).total);
    await r.event('.owner-search input', 'search', { value:'周女士' });
    assert((await data()).rows.length > 0);
    assert((await data()).rows.every(row => (row.name + ' ' + row.status).includes('周女士')));
    await pass('原单明细复用每次20条加载与关键词搜索');
    }


    if (checks.length < 5) {
    await openHome();
    for (const page of ['finance','performance','profile','home']) {
      await r.sleep(650);
      r.mp.pageMap.clear();
      const navigation = await (await r.mp.currentPage()).$('#owner-navigation');
      assert(navigation, '老板四Tab导航组件存在');
      const tab = await navigation.$('.boss-nav-item[data-page="' + page + '"]');
      assert(tab, '老板四Tab包含 ' + page);
      await tab.tap();
      await r.until(async () => (await r.current()).route === 'pages/admin/' + page + '/index', '四Tab ' + page);
    }
    await pass('首页、经营、绩效、我的四Tab实际点击互通');
    }

    if (checks.length < 6) {
    await r.open('/pages/admin/performance/index?history=1', '.perf-targets', { relaunch:true });
    await r.open('/pages/admin/detail/index?view=targets', '.owner-form-row');
    await r.until(async()=>!!(await r.rects(['.owner-form-row']))[0], '目标表单渲染完成');
    assert(!(await r.rects(['.owner-goal-input']))[0]);
    await r.event('.owner-page', 'save');
    assert.match((await data()).error, /只读|绩效任务/);
    await pass('旧月度目标只读保留历史数值且不能保存');
    }


    await openHome();
    await shot('owner-p0-home');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result:'passed', source, checks, scenarios, screenshots, interactions:r.interactions, errors:r.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error:error.stack, checks, scenarios, screenshots, current:await r.current(), errors:r.errors }, null, 2));
    throw error;
  } finally {
    await r.restore();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
