const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const directory = path.resolve(process.env.COMMISSION_RECOVERY_NATIVE_OUT || 'qa/commission-budget-recovery-native-20261023');
const wsEndpoint = process.env.FRANCHISE_WS || 'ws://127.0.0.1:10030';
const save = (name, value) => fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2));

async function fixture() {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
  Account.mutate(state => Account.loginDemo(state, 'sales-dual'));
  Account.mutate(state => Account.switchIdentity(state, state.session.companyId, 'sales-lead'));
  const customer = Business.add({
    name: '陈先生·原生补预算验收',
    phone: '13900009972',
    source: '到店咨询',
    province: '湖北省',
    city: '武汉市',
    summary: '两层乡墅，一层老人房，先核定预算再委托设计。'
  });
  assert.equal(Business.customer(customer.id).budgets.length, 0);
  const rows = wx.getStorageInfoSync().keys
    .filter(key => key.startsWith('zhongshu-'))
    .map(key => [key, wx.getStorageSync(key)]);
  await runtime.disconnect();
  return { customerId: customer.id, projectId: customer.projectId, rows };
}

async function main() {
  fs.mkdirSync(directory, { recursive: true });
  const initial = await fixture();
  const report = {
    result: 'running',
    evidence: '微信开发者工具原生渲染，通过 Element.tap/input/trigger(change) 执行',
    setup: '仅注入一个已分配业务、零预算、零委托的新客户起点',
    endpoint: wsEndpoint.replace(/:\d+$/, ':<port>'),
    customerId: initial.customerId,
    projectId: initial.projectId,
    checks: [],
    screenshots: [],
    handoffs: []
  };
  const r = await require('./franchise-native-runtime.cjs').connect('commission-budget-recovery', {
    directory,
    wsEndpoint,
    realEvents: true
  });
  const customerId = initial.customerId;
  const projectId = initial.projectId;
  const current = () => r.current();
  const data = () => r.data();
  const customer = () => r.mp.evaluate(id => {
    const row = (wx.getStorageSync('zhongshu-business-v3')?.customers || []).find(customer => customer.id === id);
    if (!row) return null;
    return {
      id: row.id,
      projectId: row.projectId,
      ownerId: row.ownerId,
      budgets: (row.budgets || []).map(budget => ({ version: budget.version, area: budget.area, parameters: budget.parameters })),
      request: row.request ? { version: row.request.version, budgetVersion: row.request.budgetVersion, attachment: row.request.attachment } : null,
      serviceOrders: (row.serviceOrders || []).map(order => ({ id: order.id, version: order.version, total: order.total, first: order.first, tail: order.tail, recipient: order.recipient })),
      approval: (row.moneyApprovals || []).filter(row => row.kind === 'service').map(row => ({ id: row.id, version: row.version, total: row.total, status: row.status })),
      businessFlow: row.businessFlow || null,
      handover: row.handover || null,
      acceptance: row.acceptance || null
    };
  }, customerId);
  const project = () => r.mp.evaluate(id => (wx.getStorageSync('zhongshu-engineering-v1')?.projects || []).find(project => project.sourceCustomerId === id), customerId);
  const urlButton = url => 'button[data-url="' + url + '"]';
  const persist = () => {
    report.interactions = r.interactions.slice();
    report.navigation = r.navigations.slice();
    save('results.json', report);
  };
  const mark = (name, detail = '') => {
    report.checks.push({ name, detail, result: 'passed' });
    console.log('PASS ' + name);
    persist();
  };
  const capture = async name => {
    const file = await r.capture(name);
    report.screenshots.push(file);
    persist();
    return file;
  };
  const route = async (expectedRoute, expectedView, expectedId = customerId) => {
    const state = await r.until(async () => {
      const page = await current();
      return page.route === expectedRoute
        && (!expectedView || page.data.view === expectedView)
        && (!expectedId || page.data.customer?.id === expectedId || page.data.id === expectedId || await r.mp.evaluate(id => getCurrentPages().at(-1)?.id === id, expectedId))
        ? page : null;
    }, expectedRoute + (expectedView ? ' ' + expectedView : ''), 15000);
    assert(!state.data.error, state.data.error);
    return state;
  };
  const action = async (selector, kind = 'tap', value) => {
    await r.until(async () => {
      r.mp.pageMap?.clear();
      return !!(await (await r.mp.currentPage()).$(selector));
    }, '可见控件 ' + selector, 15000);
    await r.event(selector, kind === 'input' ? 'input' : kind === 'change' ? 'change' : 'tap', value === undefined ? {} : { value });
    persist();
  };
  const tap = selector => action(selector);
  const input = (selector, value) => action(selector, 'input', String(value));
  const change = (selector, value) => action(selector, 'change', value);
  const visibleText = async selector => {
    let element;
    await r.until(async () => {
      r.mp.pageMap?.clear();
      element = await (await r.mp.currentPage()).$(selector);
      if (!element) return false;
      const size = await element.size();
      return size.width > 0 && size.height > 0;
    }, '可见控件 ' + selector, 15000);
    const offset = await element.offset();
    await r.mp.pageScrollTo(Math.max(0, offset.top - 150));
    await r.sleep(150);
    return element.text();
  };
  const login = async (id, role) => {
    await r.login(id, role);
    report.role = { id, role: await r.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session.role) };
    persist();
  };
  const handoff = async (role, task, selector, screenshot) => {
    const text = await visibleText(selector);
    report.handoffs.push({ role, taskId: task.key || task.id, taskName: task.name, taskAction: task.action || task.label, url: task.url, selector, text, screenshot });
    await capture(screenshot.replace(/\.png$/, ''));
    persist();
  };
  const financeTask = async predicate => {
    let desk = (await data()).financeDesk;
    assert(desk, '财务首页未就绪');
    const task = desk.tasks.find(predicate);
    assert(task, '找不到本项目财务待办：' + JSON.stringify(desk.tasks.map(row => ({ id: row.id, view: row.view, name: row.name }))));
    if (!desk.previewTasks.some(row => row.key === task.key)) {
      await tap('button[data-url="' + desk.tasksUrl + '"]');
      await r.until(async () => (await data()).rows?.some(row => row.key === task.key), '全部待办含本项目');
    }
    const selector = 'button[data-url="' + task.url + '"]';
    return { task, selector };
  };
  const designTask = async (view, screenshot) => {
    const task = ((await data()).tasks || []).find(row => row.customerId === customerId && row.actionUrl?.includes('view=' + view));
    assert(task, '总部设计首页找不到本客户 ' + view + ' 待办');
    const selector = urlButton(task.actionUrl);
    await handoff('总部设计', task, selector, screenshot);
    await tap(selector);
    await route('pages/workflow/commission/index', view);
    return task;
  };
  const salesTask = async screenshot => {
    let desk = await data();
    let task = (desk.todoRows || []).find(row => row.customerId === customerId && row.url?.includes('view=handover'));
    if (!task && desk.todoTab !== 'all') {
      await tap('.sales-todo-tabs button[data-tab="all"]');
      desk = await data();
      task = (desk.todoRows || []).find(row => row.customerId === customerId && row.url?.includes('view=handover'));
    }
    assert(task, '业务首页找不到本客户成果移交待办');
    if (!task.show) {
      await tap('.sales-todo-more');
      desk = await data();
      task = desk.todoRows.find(row => row.id === task.id);
    }
    const selector = '.sales-todo-row[data-id="' + task.id + '"]';
    await handoff('业务', task, selector, screenshot);
    await tap(selector);
    await route('pages/workflow/commission/index', 'handover');
    return task;
  };
  const engineerTask = async screenshot => {
    let desk = await data();
    const task = (desk.eng?.tasks || []).find(row => (row.customerId === customerId || row.url?.includes(customerId)) && row.url?.includes('view=receive'));
    assert(task, '工程首页找不到本项目资料接收待办');
    if (!(desk.eng.previewTasks || []).some(row => row.id === task.id)) {
      await tap('button[data-action="todos"]');
      desk = await data();
      assert((desk.tasks || desk.rows || desk.eng?.tasks || []).some(row => row.id === task.id), '工程全部待办找不到本项目资料接收事项');
    }
    const selector = urlButton(task.url);
    await handoff('工程负责人', task, selector, screenshot);
    await tap(selector);
    await route('pages/workflow/commission/index', 'receive');
    return task;
  };
  const financialSubmit = async () => {
    const before = (await data()).action;
    const expectedView = { pay: 'partial-payment', 'incoming-register': 'receipt' }[before];
    await tap('[data-handler="submit"]');
    if ((await data()).modal?.action) await tap('[data-handler="confirm"]');
    await r.until(async () => {
      const state = await data();
      return !state.busy && !state.modal && (!expectedView || state.view === expectedView);
    }, '财务登记回执', 15000);
    assert(!(await data()).error, (await data()).error);
  };

  try {
    await r.replace(initial.rows);
    await login('sales-dual', 'sales-lead');
    await r.open('/pages/workflow/commission/index?view=submit&id=' + encodeURIComponent(customerId), '.commission-budget-action', { relaunch: true });
    await route('pages/workflow/commission/index', 'submit');

    await input('textarea[data-key="text"]', '两层乡墅，一层老人房，沿本项目预算完成设计。');
    await input('textarea[data-key="scope"]', '平面、立面及施工图');
    await input('input[data-key="total"]', '10000');
    await input('input[data-key="first"]', '4000');
    await input('input[data-key="recipient"]', '总部');
    await tap('button[data-sample-key="attachment"]');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    assert.equal((await data()).budgetReady, false);
    assert.match(await visibleText('.commission-budget'), /尚无有效参考预算|先完成预算/);
    await capture('01-missing-budget-recovery');
    mark('零预算委托页明确展示补预算入口');

    await tap('.commission-budget-action');
    await route('pages/workflow/index', 'budget');
    await tap('.bottom-inline');
    await route('pages/workflow/index', 'budget-result');
    await capture('02-budget-v1-result');
    await tap('.budget-next');
    await route('pages/workflow/commission/index', 'submit');
    const commission = await data();
    assert.equal(commission.form.text, '两层乡墅，一层老人房，沿本项目预算完成设计。');
    assert.equal(commission.form.scope, '平面、立面及施工图');
    assert.equal(commission.form.total, '10000');
    assert.equal(commission.form.first, '4000');
    assert.equal(commission.form.recipient, '总部');
    assert(commission.form.attachment?.sample);
    assert.equal(commission.form.checked, false);
    assert.equal(commission.form.__budgetVersion, 1);
    assert.match(await visibleText('.commission-budget'), /参考预算\s*V1/);
    await capture('03-commission-restored-budget-v1');
    mark('预算测算返回后保留全部委托输入与附件，并撤销旧勾选');

    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    await route('pages/workflow/commission/index', 'progress');
    const submitted = await customer();
    assert.equal(submitted.request.budgetVersion, 1);
    assert.equal(submitted.serviceOrders[0].total, 10000);
    assert.equal(submitted.serviceOrders[0].first, 4000);
    assert.equal(submitted.serviceOrders[0].tail, 6000);
    mark('委托按预算 V1 提交，设计费总额、首款和尾款一次形成');

    await login('boss');
    let bossData = await data();
    let bossTask = bossData.homeTodos?.find(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review'));
    if (!bossTask) {
      assert(bossData.homeTodoTotal > bossData.homeTodos.length, '老板首页和全部待办均找不到本项目');
      await tap('.home-all');
      bossData = await data();
      bossTask = bossData.rows.find(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review'));
    }
    assert(bossTask, '老板找不到本项目设计费审批待办');
    const bossSelector = 'button[data-key="' + bossTask.key + '"]';
    await handoff('加盟商老板', bossTask, bossSelector, '04-boss-single-approval-todo.png');
    await tap(bossSelector);
    await route('pages/workflow/index', 'money-review');
    const approvalRows = (await data()).sections.flatMap(section => section.items || []);
    const amount = label => approvalRows.find(row => row.label === label)?.value;
    assert.match(String(amount('设计总金额')), /10,?000/);
    assert.match(String(amount('首付款金额')), /4,?000/);
    assert.match(String(amount('设计尾款金额')), /6,?000/);
    await capture('05-boss-approval-three-amounts');
    await tap('.option[data-key="moneyDecision"][data-value="批准"]');
    await input('textarea[data-key="moneyNote"]', '已一次核对设计总额10000元、首付4000元、尾款6000元及交付范围。');
    await tap('.check-row[data-key="moneyChecked"]');
    await tap('.primary-action');
    await r.until(async () => !(await data()).primary && !(await data()).busy, '老板审批保存', 15000);
    assert(!(await data()).error, (await data()).error);
    await login('boss');
    bossData = await data();
    assert(!bossData.homeTodos?.some(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review')));
    const remainingApprovals = await r.mp.evaluate(() => getCurrentPages().at(-1).ownerSnapshot?.approvals || []);
    assert(!remainingApprovals.some(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review')));
    mark('老板一次审批总额及首尾款，完成后不重复生成审批');

    await login('hq-design-admin');
    assert(!(await data()).tasks.some(row => row.customerId === customerId && row.actionUrl?.includes('view=complete')), '门店首款未登记前，总部设计不得收到可办理的设计完成待办');
    mark('老板审批后但门店首款未登记时，总部设计尚不可开始办理');

    await login('finance-dual', 'finance');
    const store = await financeTask(row => row.view === 'pay' && row.id.includes(customerId) && row.id.endsWith(':first'));
    await handoff('门店财务', store.task, store.selector, '06-store-first-payment-todo.png');
    await tap(store.selector);
    await route('pages/admin/index', 'pay', null);
    assert.match(String((await data()).hero?.amount || ''), /4,?000/);
    await input('input[data-key="amount"]', '4000');
    await input('input[data-key="transaction"]', 'RECOVERY-STORE-FIRST-4000');
    await tap('button[data-sample-key="file"]');
    await tap('[data-key="checked"]');
    await financialSubmit();
    mark('门店财务从待办登记同项目首款4000元');

    await login('hq-design-admin');
    await designTask('complete', '07-hq-design-complete-todo.png');
    await input('textarea[data-key="note"]', '本项目V1平面、立面、施工图及线下专业审核已经完成。');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    await route('pages/workflow/commission/index', 'progress');
    assert((await customer()).businessFlow.completed, '门店首款登记后应可完成设计，不依赖总部首款确认');
    mark('总部设计在门店首款登记后完成设计，此时总部尚未确认首款');

    await login('hq-finance-dual', 'finance');
    const headquartersFirst = await financeTask(row => row.view === 'incoming-register' && row.id.includes(customerId) && row.id.includes(':first'));
    await handoff('总部财务', headquartersFirst.task, headquartersFirst.selector, '08-hq-first-incoming-todo.png');
    await tap(headquartersFirst.selector);
    await route('pages/admin/index', 'incoming-register', null);
    assert.match(String((await data()).hero?.amount || ''), /4,?000/);
    await input('input[data-key="amount"]', '4000');
    await input('input[data-key="transaction"]', 'RECOVERY-HQ-FIRST-4000');
    await tap('button[data-sample-key="file"]');
    await tap('[data-key="checked"]');
    await financialSubmit();
    mark('总部财务从同项目待办确认首款实际到账4000元');

    await login('boss');
    bossData = await data();
    assert(!bossData.homeTodos?.some(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review')));
    const approvalsAfterDesign = await r.mp.evaluate(() => getCurrentPages().at(-1).ownerSnapshot?.approvals || []);
    assert(!approvalsAfterDesign.some(row => row.url?.includes(encodeURIComponent(customerId)) && row.url.includes('view=money-review')));
    mark('设计完成及首款到账后仍无第二条老板费用审批');

    await login('finance-dual', 'finance');
    const storeTail = await financeTask(row => row.view === 'pay' && row.id.includes(customerId) && row.id.endsWith(':tail'));
    await handoff('门店财务', storeTail.task, storeTail.selector, '09-store-tail-payment-todo.png');
    await tap(storeTail.selector);
    await route('pages/admin/index', 'pay', null);
    assert.match(String((await data()).hero?.amount || ''), /6,?000/);
    await input('input[data-key="amount"]', '6000');
    await input('input[data-key="transaction"]', 'RECOVERY-STORE-TAIL-6000');
    await tap('button[data-sample-key="file"]');
    await tap('[data-key="checked"]');
    await financialSubmit();
    mark('设计完成后门店财务直接登记尾款6000元，无第二次老板审批');

    await login('hq-finance-dual', 'finance');
    const headquartersTail = await financeTask(row => row.view === 'incoming-register' && row.id.includes(customerId) && row.id.includes(':tail'));
    await handoff('总部财务', headquartersTail.task, headquartersTail.selector, '10-hq-tail-incoming-todo.png');
    await tap(headquartersTail.selector);
    await route('pages/admin/index', 'incoming-register', null);
    assert.match(String((await data()).hero?.amount || ''), /6,?000/);
    await input('input[data-key="amount"]', '6000');
    await input('input[data-key="transaction"]', 'RECOVERY-HQ-TAIL-6000');
    await tap('button[data-sample-key="file"]');
    await tap('[data-key="checked"]');
    await financialSubmit();
    mark('总部财务确认尾款实际到账6000元');

    await login('hq-design-admin');
    await designTask('dispatch', '11-hq-design-dispatch-todo.png');
    await input('input[data-key="recipient"]', '李业务');
    await input('textarea[data-key="note"]', '本项目V1正式设计成果、施工图与线下审核结论已实际下发。');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    await route('pages/workflow/commission/index', 'progress');
    assert.equal((await customer()).businessFlow.dispatch.version, 1);
    mark('总部设计在首尾款全部到账后下发V1正式成果');

    await login('sales-dual', 'sales-lead');
    await salesTask('12-sales-handover-todo.png');
    for (const key of ['resultFile', 'drawingFile', 'reviewFile', 'confirmationFile']) {
      await tap('button[data-sample-key="' + key + '"]');
      const file = (await data()).form[key];
      assert(file?.sample, key + ' 应使用本项目模拟文件');
      assert.equal(file.meta?.customerId, customerId, key + ' 客户ID应一致');
      assert.equal(file.meta?.projectId, projectId, key + ' 项目ID应一致');
    }
    await input('input[data-key="drawingVersion"]', 'V1');
    await input('textarea[data-key="note"]', '本项目V1道路、水电、老人房和施工重点已完成电子交底。');
    assert.equal((await data()).form.managerId, 'engineering-dual', '默认工程接收人应为张工程');
    await change('checkbox-group[data-key="received"]', ['yes']);
    await change('checkbox-group[data-key="confirmed"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    await route('pages/workflow/commission/index', 'progress');
    assert.equal((await customer()).handover.managerId, 'engineering-dual');
    mark('业务用四份同项目V1模拟文件直接移交默认工程负责人');

    await login('engineering-dual', 'project-manager');
    await engineerTask('13-engineering-receive-todo.png');
    const deliveryLabels = ['正式设计成果', '正式施工图', '线下专业审核依据', '客户确认凭据'];
    const receivedFiles = (await data()).rows.filter(row => deliveryLabels.includes(row.label) && row.file?.sample);
    assert.equal(receivedFiles.length, 4, '工程必须收到四份同项目模拟文件');
    for (const row of receivedFiles) {
      assert.equal(row.file.meta?.customerId, customerId, row.label + ' 客户ID应一致');
      assert.equal(row.file.meta?.projectId, projectId, row.label + ' 项目ID应一致');
      assert.equal(row.file.meta?.version, 'V1', row.label + ' 应属于V1成果');
    }
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    await route('pages/workflow/commission/index', 'progress');
    const acceptedCustomer = await customer();
    await login('engineering-dual', 'project-manager');
    const acceptedProject = await project();
    assert.equal(acceptedCustomer.acceptance.managerId, 'engineering-dual');
    assert(acceptedProject, '工程接收后应建立同客户工程项目');
    assert.equal(acceptedProject.projectId, projectId);
    assert.equal(acceptedProject.sourceCustomerId, customerId);
    mark('工程负责人从首页接收四份V1资料，并形成同项目工程验收记录');

    const finalCustomer = await customer();
    report.final = {
      customerId: finalCustomer.id,
      projectId: finalCustomer.projectId,
      budgetVersions: finalCustomer.budgets.map(row => row.version),
      requestBudgetVersion: finalCustomer.request.budgetVersion,
      serviceOrder: finalCustomer.serviceOrders[0],
      approval: finalCustomer.approval.at(-1),
      dispatchVersion: finalCustomer.businessFlow.dispatch.version,
      drawingVersion: finalCustomer.handover.drawingVersion,
      acceptance: finalCustomer.acceptance,
      engineeringProject: { id: acceptedProject.id, projectId: acceptedProject.projectId, sourceCustomerId: acceptedProject.sourceCustomerId }
    };
    report.result = 'passed';
    delete report.role;
    persist();
    console.log('PASS 原生缺预算恢复、设计收付款、成果移交与工程接收闭环');
  } catch (error) {
    report.result = 'failed';
    report.error = error.message;
    const failed = await current().catch(() => null);
    report.failedPage = failed ? { route: failed.route, view: failed.data?.view, id: failed.data?.customer?.id || failed.data?.id || '' } : null;
    persist();
    throw error;
  } finally {
    await r.restore();
  }
}

main().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
