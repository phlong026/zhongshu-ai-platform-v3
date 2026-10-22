const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/management-completion-20261023/native');
const project = process.env.MANAGEMENT_NATIVE_PROJECT || path.join(root, '.tools/management-completion/native-project');
const sourceFixture = path.join(root, 'qa/owner-workbench-port-20261023/preview-storage.json');
const port = Number(process.env.MANAGEMENT_COMPLETION_PORT || 9952);
const period = '2026-10';
const resumePromotion = process.argv.includes('--resume-promotion');
const clone = value => JSON.parse(JSON.stringify(value));

function fixture() {
  const rows = JSON.parse(fs.readFileSync(sourceFixture));
  const byKey = new Map(rows.map(([key, value]) => [key, clone(value)]));
  const account = byKey.get('zhongshu-account-v1');
  const management = byKey.get('zhongshu-management-v1');
  const business = byKey.get('zhongshu-business-v3');

  management.period = period;
  management.managementHandoverFlags = {};
  management.receipts = (management.receipts || []).filter(row => row.id !== 'qa-management-income');
  management.receipts.push({
    id:'qa-management-income', companyId:'quanzhou', projectId:'qa-management-project', customer:'QA验收客户', name:'QA验收工程到账',
    amount:880000, status:'已核算', date:period + '-15', bankDate:period + '-15', registrarId:'wang-finance', version:1, revisions:[]
  });

  business.customers = (business.customers || []).filter(row => row.id !== 'qa-management-customer');
  const template = clone(business.customers[0] || {});
  business.customers.push({
    ...template,
    id:'qa-management-customer', customerUserId:'qa-management-user', projectId:'qa-management-project', companyId:'quanzhou', name:'QA验收客户',
    owner:'张业务', ownerId:'zhang', createdById:'zhang', ownership:'private', createdAt:period + '-02', registeredAt:period + '-02', status:'设计委托已提交',
    follows:[{ id:'qa-follow-phone', date:period + '-05', method:'电话', note:'确认真实建房计划' }, { id:'qa-follow-visit', date:period + '-12', method:'到店', note:'到店确认设计需求' }],
    serviceOrders:[{ version:1, responsibleId:'zhang', total:16800, first:8400, tail:8400, recipient:'众墅之家总部', file:{ name:'QA设计委托确认单.pdf', sample:true } }],
    businessFlow:{ type:'sales-offline-v1', responsibleId:'zhang', submittedAt:period + '-08T10:00:00.000Z', feedbacks:[], dispatchHistory:[], events:[] },
    quotes:[], receipts:[], returns:[], drafts:{}, acceptance:null, handover:null
  });

  management.work = (management.work || []).filter(work=>work.id!=='qa-management-work');
  management.work.push({id:'qa-management-work',kind:'customer-responsibility',customerId:'qa-management-customer',projectId:'qa-management-project',customer:'QA验收客户',name:'客户跟进',stage:'设计中',owner:'zhang',author:'zhang'});

  const ownerGoals = byKey.get('zhongshu-owner-goals-v1') || { version:1, companies:{} };
  ownerGoals.companies = ownerGoals.companies || {};
  byKey.set('zhongshu-owner-goals-v1', ownerGoals);
  return [...byKey.entries()];
}

(async () => {
  assert(fs.existsSync(sourceFixture), '缺少老板工作台预览存储夹具');
  const seed = fixture();
  const prior = resumePromotion ? JSON.parse(fs.readFileSync(path.join(directory,'progress.json'))) : null;
  if (prior) { assert.equal(prior.checks.length,7); if (!fs.existsSync(path.join(directory,'management-completion-prior-interactions.json'))) fs.copyFileSync(path.join(directory,'management-completion-interactions.json'),path.join(directory,'management-completion-prior-interactions.json')); }
  if (!resumePromotion) fs.rmSync(directory, { recursive:true, force:true });
  fs.mkdirSync(directory, { recursive:true });
  fs.writeFileSync(path.join(directory, 'fixture-storage.json'), JSON.stringify(seed, null, 2));

  if (!process.argv.includes('--reuse-project')) {
  fs.rmSync(project, { recursive:true, force:true });
  fs.mkdirSync(project, { recursive:true });
  fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), { recursive:true });
  const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
  config.projectname = 'management-completion-native-qa';
  fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
  execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli',
    ['auto', '--project', project, '--auto-port', String(port)], { timeout:45000, stdio:'pipe' });
  }

  const native = require('./franchise-native-runtime.cjs');
  let runner;
  for (let attempt = 0; !runner; attempt++) {
    try {
      runner = await native.connect('management-completion', { directory, wsEndpoint:'ws://127.0.0.1:' + port, realEvents:true });
    } catch (error) {
      if (attempt === 4) throw error;
      await native.sleep(1500);
    }
  }

  const checks = prior?.checks.slice() || [], screenshots = prior?.screenshots.slice() || [], mutations = prior?.mutations.slice() || [];
  let data;
  let modalMocked = false, completed = false;
  const pass = (name, evidence = {}) => {
    checks.push({ name, result:'passed', ...evidence });
    fs.writeFileSync(path.join(directory, 'progress.json'), JSON.stringify({ checks, screenshots, mutations, errors:runner.errors }, null, 2));
    console.log('PASS ' + name);
  };
  const capture = async name => {
    await runner.mp.evaluate(() => { wx.hideToast(); return true; });
    await runner.sleep(250);
    screenshots.push(await runner.capture(name));
  };
  const open = async (url, selector) => {
    await runner.open(url, selector);
    const data = await runner.data();
    assert(!data.error, data.error);
    return data;
  };
  const route = expected => runner.until(async () => (await runner.current()).route === expected, expected, 16000);

  try {
    await runner.open('/pages/account/index?view=policy');
    await runner.replace(seed);

    if (!resumePromotion) {
    data = await open('/pages/admin/home/index', '.home-six');
    const metrics = data.desk.home.six;
    assert.equal(metrics.length, 6);
    assert.equal((await (await runner.mp.currentPage()).$$('.home-six-item')).length, 6);
    for (const metric of metrics) {
      await runner.event({ selector:'.home-six-item', dataset:{ key:metric.key } }, 'go');
      await runner.until(async () => {
        const current = await runner.current();
        return current.route === 'pages/admin/detail/index' && current.data.view === 'metric' && current.data.rows.length > 0;
      }, '指标原列表 ' + metric.metric, 16000);
      const detail = await runner.data();
      assert(detail.rows.every(row => row.key && row.name && row.status && row.url), metric.metric + ' 明细必须指向原记录');
      await capture('metric-' + metric.metric);
      await open('/pages/admin/home/index', '.home-six');
    }
    pass('六项经营指标通过原生控件逐项进入可追溯原列表', { metrics:metrics.map(row => row.metric) });

    data = await open('/pages/admin/performance/index', '.perf-filters');
    assert(data.desk.performance.filterOptions.some(row => row.id === 'business'));
    await runner.event({ selector:'.perf-filter', dataset:{ department:'business' } }, 'departmentFilter');
    await runner.until(async () => (await runner.data()).desk.performance.selectedDepartment === 'business', '业务部门筛选');
    data = await runner.data();
    const member = data.desk.performance.groups.flatMap(group => group.rows)[0];
    assert(member, '业务部门应有在岗成员');
    await runner.event({ selector:'.perf-person', dataset:{ person:member.id, department:member.department } }, 'togglePerson');
    await runner.until(async () => (await runner.data()).expandedPerson === member.id, '成员近期原记录展开');
    assert((await runner.rects(['.perf-records']))[0], '成员展开区须真实渲染');
    await capture('performance-business-member');
    pass('绩效部门筛选与成员原记录展开均由真实控件触发', { member:member.id });

    data = await open('/pages/admin/detail/index?view=targets', '.owner-goal-input');
    const targetBefore = data.form.departmentGoals.business.commissionAmount;
    await runner.event({ selector:'.owner-goal-input', dataset:{ scope:'department', department:'business', metric:'commissionAmount' } }, 'goalFieldInput', { value:'54321.25' });
    await runner.event('.owner-save', 'savePerformanceGoals');
    await runner.until(async () => !(await runner.data()).saving && String((await runner.data()).form.departmentGoals.business.commissionAmount) === '54321.25', '业务部设计委托目标保存');
    mutations.push({ kind:'department-goal', before:targetBefore, after:'54321.25' });
    await capture('department-goal-saved');
    pass('部门目标通过原生输入与保存按钮持久化', { department:'business', metric:'commissionAmount', value:'54321.25' });

    data = await open('/pages/admin/detail/index?view=company', '.management-company');
    const companyBefore = clone(data.companyForm);
    const companyValues = { displayName:'QA验收门店', contact:'验收负责人', phone:'13800002688', address:'泉州市QA验收路26号' };
    for (const [field, value] of Object.entries(companyValues)) await runner.event({ selector:'.management-input', dataset:{ field } }, 'companyInput', { value });
    await runner.event('.management-company .owner-save', 'saveCompany');
    await runner.until(async () => {
      const current = await runner.data();
      return !current.saving && Object.entries(companyValues).every(([key, value]) => current.managementSettings.company[key] === value);
    }, '门店资料保存');
    mutations.push({ kind:'company', before:companyBefore, after:companyValues });
    await capture('company-saved');
    pass('门店资料通过真实表单保存并回显', { fields:Object.keys(companyValues) });

    data = await open('/pages/admin/detail/index?view=handover', '.owner-row');
    assert(data.rows.some(row => row.workId === 'qa-management-work' && !row.marked));
    await runner.mp.mockWxMethod('showModal', { confirm:true, cancel:false, content:'QA验收明确标记需交接' });
    modalMocked = true;
    await runner.event({ selector:'.owner-link', dataset:{ id:'qa-management-work' } }, 'markHandover');
    await runner.until(async () => (await runner.data()).rows.some(row => row.workId === 'qa-management-work' && row.marked), '明确标记需交接');
    const markedFlag = await runner.mp.evaluate(() => wx.getStorageSync('zhongshu-management-v1').managementHandoverFlags?.['qa-management-work']);
    assert(markedFlag?.active && markedFlag.reason === 'QA验收明确标记需交接');
    mutations.push({ kind:'handover-mark', workId:'qa-management-work', version:markedFlag.version });
    await runner.mp.restoreWxMethod('showModal');
    modalMocked = false;
    await capture('handover-marked');
    pass('项目经理交接列表的标记按钮写入独立交接标记', { workId:'qa-management-work', systemDialog:'showModal返回确认及明确原因' });

    data = await open('/pages/admin/profile/index', '.owner-management');
    const bossCommission = data.managementEntries.find(row => row.text === '项目回款提成');
    assert(bossCommission);
    await runner.event({ selector:'.owner-management .service-row', dataset:{ url:bossCommission.url } }, 'open');
    await route('pages/admin/commission/index');
    data = await runner.data();
    assert.equal(data.canEditPolicy, true);
    assert((await runner.rects(['.commission-save']))[0], '老板提成规则控件须渲染');
    await capture('commission-boss');
    pass('老板从我的管理入口进入项目回款提成页', { role:'boss' });

    await runner.login('finance-dual', 'finance');
    data = await open('/pages/profile/index', '.profile-card');
    const financeCommission = data.entries.find(row => row.text === '项目回款提成');
    assert(financeCommission);
    await runner.event({ selector:'.service-row.profile-menu', dataset:{ url:financeCommission.url } }, 'open');
    await route('pages/admin/commission/index');
    data = await runner.data();
    assert.equal(data.canEditPolicy, false);
    await runner.until(async () => !!(await runner.rects(['.owner-badge']))[0], '财务只读提示已渲染');
    assert.equal((await runner.rects(['.commission-save']))[0], null, '财务不可渲染提成规则保存控件');
    await capture('commission-finance');
    pass('财务从我的入口进入同一提成页且保持规则只读', { role:'finance' });
    } else { await runner.login('finance-dual','finance'); }

    data = await open('/pages/profile/index', '.profile-card');
    const promotion = data.entries.find(row => row.text === '公司费用与推广登记');
    assert(promotion);
    await runner.event({ selector:'.service-row.profile-menu', dataset:{ url:promotion.url } }, 'open');
    await route('pages/admin/operations/index');
    data = await runner.data();
    assert.equal(data.view, 'expenses');
    assert.equal(data.selected.id, 'company:quanzhou');
    assert.equal(data.form.purpose, '推广获客');
    assert.equal(data.canSubmitExpense, true);
    await runner.until(async () => (await runner.rects(['.ops-expense-name','.ops-expense-amount'])).every(Boolean), '公司费用表单输入控件已渲染');
    await capture('promotion-expense-entry');
    pass('财务从我的入口进入公司推广获客费用原表单', { projectId:'company:quanzhou', purpose:'推广获客' });

    assert.equal(runner.errors.length, 0, JSON.stringify(runner.errors));
    const result = {
      result:'passed', checks, screenshots, mutations, errors:runner.errors, resumedAfterChecks:prior?.checks.length || 0,
      fixture:{ source:path.relative(root, sourceFixture), deterministicCustomer:'qa-management-customer', deterministicReceipt:'qa-management-income', period },
      isolation:{ project:path.relative(root, project), autoPort:port, beforeStorage:'management-completion-before-storage.json' },
      restored:false
    };
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify(result, null, 2));
    fs.rmSync(path.join(directory, 'failure.json'), { force:true });
    completed = true;
  } catch (error) {
    await capture('failure').catch(() => {});
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error:error.stack, checks, screenshots, mutations, current:await runner.current().catch(() => null), errors:runner.errors }, null, 2));
    throw error;
  } finally {
    if (modalMocked) await runner.mp.restoreWxMethod('showModal').catch(() => {});
    await runner.restore();
    if (completed) {
      const file = path.join(directory, 'results.json');
      const result = JSON.parse(fs.readFileSync(file));
      const restorationFile = path.join(directory, 'management-completion-restored.json');
      const restoration = JSON.parse(fs.readFileSync(restorationFile));
      assert.equal(restoration.restored, true, '原生验收结束后必须恢复运行前存储');
      result.restored = true;
      result.restorationEvidence = path.basename(restorationFile);
      fs.writeFileSync(file, JSON.stringify(result, null, 2));
    }
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
