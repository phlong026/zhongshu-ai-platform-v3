const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const directory = path.resolve(process.env.HUMAN_FLOW_OUT || 'qa/human-flow-20261023');
const resumed = process.argv.includes('--resume');
const stopAfter = process.argv.find(value => value.startsWith('--stop-after='))?.split('=')[1];
const save = (name, value) => fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2));
const read = name => JSON.parse(fs.readFileSync(path.join(directory, name)));

// The starting fixture ends at a saved budget. All later domain writes use native controls.
async function budgetFixture() {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  A.mutate(account => A.loginDemo(account, 'sales-dual'));
  const customer = B.add({ name: '陈建国家（连续验收）', phone: '13900008826', province: '湖北省', city: '黄冈市', source: '朋友介绍', summary: '两层乡墅，老人房与二层露台，预算到工程交付连续验收' });
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/index?view=budget&id=' + customer.id });
  await (await (await runtime.currentPage()).$('.bottom-inline')).tap();
  assert.equal(getCurrentPages().at(-1).data.view, 'budget-result');
  assert(B.customer(customer.id).budgets.length && !B.customer(customer.id).request);
  return { customerId: customer.id, projectId: customer.projectId, rows: wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]) };
}

async function main() {
  fs.mkdirSync(directory, { recursive: true });
  const initial = resumed ? read('initial.json') : await budgetFixture();
  if (!resumed) save('initial.json', initial);
  const report = resumed ? read('results.json') : { result: 'running', evidence: 'Official WeChat native Element.tap/input/trigger(change)', setup: '仅注入预算已保存、委托尚未提交的起点数据', customerId: initial.customerId, projectId: initial.projectId, steps: [], navigation: [], findings: [] };
  if (resumed) report.retries = [...(report.retries || []), { after: report.steps.at(-1)?.id, priorError: report.error, time: new Date().toISOString() }];
  delete report.error;
  delete report.failedPage;
  report.result = 'running';
  const r = await require('./franchise-native-runtime.cjs').connect('human-flow', { directory, wsEndpoint: process.env.FRANCHISE_WS || 'ws://127.0.0.1:10023', realEvents: true });
  const priorInteractions = report.interactions || [];
  let stopped = false;
  const customerId = initial.customerId;
  const initialCustomer = Object.fromEntries(initial.rows)['zhongshu-business-v3'].customers.find(row => row.id === customerId);
  const contractTotal = initialCustomer.budgets.at(-1).total;
  const startAmount = Math.round(contractTotal * 0.6);
  const toppingAmount = contractTotal - startAmount;
  report.amounts = { referenceBudget: contractTotal, constructionContract: contractTotal, constructionStart: startAmount, constructionTopping: toppingAmount, designService: 12000 };
  const current = () => r.current();
  const data = () => r.data();
  const snapshot = () => r.snapshot();
  const customer = () => r.mp.evaluate(id => wx.getStorageSync('zhongshu-business-v3').customers.find(customer => customer.id === id), customerId);
  const project = () => r.mp.evaluate(id => wx.getStorageSync('zhongshu-engineering-v1')?.projects?.find(project => project.sourceCustomerId === id && project.recordFlow), customerId);
  const persist = () => { report.interactions = [...priorInteractions, ...r.interactions]; save('results.json', report); };
  const urlButton = url => 'button[data-url="' + url + '"]';

  async function action(selector, kind, value) {
    const before = await current();
    await r.until(async () => {
      r.mp.pageMap?.clear();
      return !!(await (await r.mp.currentPage()).$(selector));
    }, '可见控件 ' + selector, 15000);
    await r.event(selector, kind === 'input' ? 'input' : kind === 'change' ? 'change' : 'tap', value === undefined ? {} : { value });
    report.navigation.push({ from: before.route, view: before.data.view, selector, via: 'Element.' + kind, to: (await current()).route });
    persist();
  }
  const tap = selector => action(selector, 'tap');
  const input = (selector, value) => action(selector, 'input', String(value));
  const change = (selector, value) => action(selector, 'change', value);
  async function tapText(text, selector = 'button') {
    r.mp.pageMap?.clear();
    const candidates = await (await r.mp.currentPage()).$$(selector);
    for (const candidate of candidates) {
      if (!(await candidate.text()).replace(/\s/g, '').includes(text.replace(/\s/g, ''))) continue;
      const offset = await candidate.offset();
      await r.mp.pageScrollTo(Math.max(0, offset.top - 150));
      await candidate.tap();
      r.interactions.push({ route: (await current()).route, text, via: 'Element.tap' });
      await r.sleep(350); persist(); return;
    }
    throw Error('找不到可见按钮：' + text);
  }
  async function handoff(task, selector, area) {
    let element;
    await r.until(async () => {
      r.mp.pageMap?.clear();
      element = await (await r.mp.currentPage()).$(selector);
      return !!element && (await element.size()).height > 0;
    }, '下一岗待办可见 ' + area, 15000);
    const proof = { step: report.running?.id, responsibility: report.role, area, taskId: task.key || task.id, taskName: task.name || task.title, taskAction: task.action || task.label || task.button, customerId, projectId: initial.projectId, selector, visible: true, screenshot: 'handoff-' + ((report.handoffs || []).length + 1) + '.png' };
    assert(element, '下一岗任务有实际控件');
    const offset = await element.offset();
    await r.mp.evaluate(top => wx.pageScrollTo({ scrollTop: Math.max(0, top - 150), duration: 0 }), offset.top);
    await r.sleep(150); await r.capture(proof.screenshot.replace(/\.png$/, ''));
    report.handoffs = [...(report.handoffs || []), proof]; persist();
  }
  async function taskDestination(url) {
    const expected = new URL(url, 'https://local.test').searchParams;
    const destination = await r.until(async () => {
      const page = await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); return { route:p.route, id:p.id, view:p.data.view, customerId:p.data.customer?.id, projectId:p.data.p?.projectId, error:p.data.error }; });
      if (expected.get('id') && page.id !== expected.get('id')) return null;
      if (expected.get('view') && page.view !== expected.get('view')) return null;
      return page;
    }, '待办打开对应原单', 15000);
    assert(!destination.error, destination.error);
    report.handoffs.at(-1).destination = destination;
    persist();
  }
  async function workspaceTab(index) {
    r.mp.pageMap?.clear();
    const bar = await (await r.mp.currentPage()).$('wx-custom-tab-bar');
    const button = await bar?.$('button[data-index="' + index + '"]');
    assert(button, '可见工作台栏目 ' + index);
    await button.tap();
    r.interactions.push({ route: (await current()).route, selector: 'wx-custom-tab-bar button[data-index="' + index + '"]', via: 'Element.tap' });
    await r.sleep(350); persist();
  }
  async function login(user, role) {
    await r.login(user, role);
    report.role = { user, role: await r.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session.role) };
    await r.sleep(350); persist();
  }
  async function state(view) { await r.until(async () => (await data()).view === view && !(await data()).busy, '页面 ' + view); assert(!(await data()).error, (await data()).error); }
  async function sample(key = 'file') {
    const selector = 'button[data-sample-key="' + key + '"]';
    await tap(selector);
    const file = (await data()).form[key];
    assert(file?.sample && file.text?.length > 40, '模拟文件应有可预览正文：' + key);
    assert(file.text.includes((await customer()).name), '模拟资料应属于当前客户：' + key);
    assert.equal(file.meta?.customerId, customerId, '模拟资料客户ID一致：' + key);
    assert.equal(file.meta?.projectId, initial.projectId, '模拟资料项目ID一致：' + key);
    assert(file.meta.version, '模拟资料必须有具体业务版本：' + key);
    report.files = [...(report.files || []), { key, name: file.name, customerId, meta: file.meta, text: file.text, route: (await current()).route }];
  }
  async function check(key, selector) {
    if ((await data()).form[key]) return;
    if (selector) await tap(selector);
    else if ((await current()).route === 'pages/workflow/commission/index') await change('checkbox-group[data-key="' + key + '"]', ['yes']);
    else await tap('button[data-key="' + key + '"]');
  }
  async function step(id, title, responsibility, execute) {
    if (stopped) return;
    if (report.steps.some(step => step.id === id)) return;
    console.log('START ' + id + ' ' + title);
    report.running = { id, title, responsibility }; persist();
    await execute();
    assert(!(await data()).error, (await data()).error);
    const c = await customer();
    assert.equal(c.projectId, initial.projectId);
    assert.equal(c.ownerId, 'sales-dual');
    const p = await project();
    if (p) { assert.equal(p.projectId, c.projectId); assert.equal(p.sourceCustomerId, c.id); report.engineeringId = p.id; }
    await r.mp.pageScrollTo(0);
    await r.sleep(350);
    await r.capture(id);
    const fixture = 'checkpoint-' + id + '.json';
    save(fixture, await snapshot());
    const pageState = await r.mp.evaluate(() => { const p = getCurrentPages().at(-1); return { route: p.route, id: p.id, view: p.data.view, planId: p.planId, nodeId: p.nodeId, stage: p.stage }; });
    const resumeUrl = pageState.route === 'pages/build/record/index' ? '/' + pageState.route + '?id=' + encodeURIComponent(pageState.id) + '&view=' + pageState.view + (pageState.planId ? '&planId=' + encodeURIComponent(pageState.planId) : '') + (pageState.nodeId ? '&nodeId=' + encodeURIComponent(pageState.nodeId) : '') + (pageState.stage ? '&stage=' + pageState.stage : '') : '';
    report.steps.push({ id, title, responsibility, resumeUrl, result: 'passed', customerId, projectId: c.projectId, engineeringId: p?.id || '', fixture, screenshot: id + '.png' });
    delete report.running; persist(); console.log('PASS ' + id + ' ' + title);
    if (id === stopAfter) stopped = true;
  }
  async function salesTask() {
    const d = await data();
    const task = d.todoRows?.find(row => (row.customerId === customerId || String(row.id).includes(customerId)) && (row.url?.includes('/commission/') || row.kind === 'commission'));
    assert(task, '李业务首页找不到本客户待办');
    if (d.todoTab !== 'all') await tap('.sales-todo-tabs button[data-tab="all"]');
    if (!(await data()).todoRows.find(row => row.id === task.id)?.show) await tapText('查看全部', '.sales-todo-more');
    const selector = '.sales-todo-row[data-id="' + task.id + '"]';
    await handoff(task, selector, '业务首页待我办理'); await tap(selector); await taskDestination(task.url);
  }
  async function financeTask(match) {
    const d = await data(), desk = d.financeDesk;
    assert(desk, '当前财务首页未就绪');
    const task = desk.tasks.find(match);
    assert(task, '本项目财务待办不存在：' + JSON.stringify(desk.tasks.map(row => ({ id: row.id, name: row.name }))));
    if (!desk.previewTasks.some(row => row.key === task.key)) await tap(urlButton(desk.tasksUrl));
    await handoff(task, urlButton(task.url), '财务首页或全部待办'); await tap(urlButton(task.url)); await taskDestination(task.url);
    await r.sleep(250); assert(!(await data()).error, (await data()).error);
  }
  async function bossTask(match) {
    let d = await data(), task = d.homeTodos?.find(match);
    if (!task) {
      await tapText('查看全部', '.home-all');
      d = await data(); task = d.rows.find(match);
      while (!task && d.rows.length < d.total) { await tapText('加载更多'); d = await data(); task = d.rows.find(match); }
    }
    assert(task, '张老板待办列表找不到本项目审批事项');
    const selector = 'button[data-key="' + task.key + '"]';
    await handoff(task, selector, '老板首页或全部待办'); await tap(selector); await taskDestination(task.url);
  }
  async function designTask(view) {
    const tasks = (await data()).tasks || [];
    const task = tasks.find(row => row.customerId === customerId && row.actionUrl?.includes('view=' + view));
    assert(task, '总部设计首页没有本客户的 ' + view + ' 待办');
    await handoff(task, urlButton(task.actionUrl), '总部设计首页待我办理');
    await tap(urlButton(task.actionUrl)); await state(view); await taskDestination(task.actionUrl);
  }
  async function engineerTask(match) {
    const d = await data(), p = await project();
    const task = d.eng.tasks.find(row => (row.customerId === customerId || row.projectId === p?.id || row.projectId === initial.projectId || row.url?.includes(customerId)) && match(row));
    assert(task, '张工程首页找不到本项目下一步：' + JSON.stringify(d.eng.tasks.map(row => ({ title: row.title, projectId: row.projectId }))));
    if (!d.eng.previewTasks.some(row => row.id === task.id)) await tap('button[data-action="todos"]');
    await handoff(task, urlButton(task.url), '工程首页或全部待办'); await tap(urlButton(task.url)); await taskDestination(task.url);
  }
  async function engineeringProject() {
    await login('engineering-dual', 'engineer');
    await tap('button[data-action="projects"]');
    await tap('button[data-id="' + (await project()).id + '"]'); await state('overview');
  }
  async function financialSubmit() {
    const before = (await data()).action;
    const expectedView = { pay:'partial-payment', 'incoming-register':'receipt', 'approve-pay':'todos' }[before];
    await tap('[data-handler="submit"]');
    if ((await data()).modal?.action) await tap('[data-handler="confirm"]');
    await r.until(async () => { const d = await data(); return !d.busy && !d.modal && (!expectedView || d.view === expectedView); }, '财务登记回执');
    assert(!(await data()).error, (await data()).error);
  }
  async function designMoney(kind, stage, amount, transaction) {
    await login(kind === 'pay' ? 'finance-dual' : 'hq-finance-dual', 'finance');
    await financeTask(row => row.id.includes(customerId) && (kind === 'pay' ? row.id.endsWith(':' + stage) && row.view === 'pay' : row.id.includes(':' + stage) && row.view === 'incoming-register'));
    await input('input[data-key="amount"]', amount);
    await input('input[data-key="transaction"]', transaction);
    await sample();
    assert.equal((await data()).form.file.meta.version, '设计服务V' + (await customer()).serviceOrders.at(-1).version);
    await check('checked'); await financialSubmit();
  }
  async function engineeringReceipt(stage, amount, transaction) {
    await login('finance-dual', 'finance');
    await financeTask(row => row.id === (report.engineeringId || '') + ':collection:' + stage);
    await input('input[data-key="amount"]', amount);
    await input('input[data-key="transaction"]', transaction);
    await sample();
    await tap('[data-action="collect"]'); await state('payments');
    assert((await data()).collections.some(row => row.transaction === transaction));
  }

  try {
    await r.replace(resumed && report.steps.length ? read(report.steps.at(-1).fixture) : initial.rows);
    if (!resumed || !report.steps.length) await r.open('/pages/workflow/index?view=budget-result&id=' + customerId, '.budget-next', { relaunch: true });
    // Checkpoint recovery reopens the last rendered page only; it never invents a business result.
    if (resumed && report.steps.length) {
      const previous = report.steps.at(-1);
      if (previous.resumeUrl) await r.open(previous.resumeUrl, undefined, { relaunch: true });
      else await login('sales-dual', 'sales');
    }

    await step('B01', '预算结果进入设计委托并提交', '李业务', async () => {
      await tap('.budget-next'); await state('submit');
      await input('textarea[data-key="text"]', '陈建国家两层住宅，沿本版预算与尺寸，首层老人房、二层露台。');
      await input('textarea[data-key="scope"]', '平面、立面及施工图');
      await input('input[data-key="total"]', '12000');
      await input('input[data-key="first"]', '6000');
      await input('input[data-key="recipient"]', '众墅之家总部');
      await check('checked'); await tap('.commission-actions .commission-primary'); await state('progress');
      assert.equal((await customer()).request.budgetVersion, (await customer()).budgets.at(-1).version);
    });
    await step('B02', '从老板首页批准本项目设计费用', '张老板', async () => {
      await login('boss'); await bossTask(row => String(row.key || row.url).includes(customerId));
      await tap('[data-key="moneyDecision"][data-value="批准"]');
      await input('textarea[data-key="moneyNote"]', '核对陈建国家本版范围，总费12000元，首尾各6000元。');
      await check('moneyChecked', '[data-key="moneyChecked"]');
      await tap('.primary-action');
    });
    await step('B03', '门店登记设计首款实际付款', '王财务', () => designMoney('pay', 'first', '6000', 'HUMAN-STORE-FIRST'));
    await step('B04', '总部核实首款实际到账', '罗财务', () => designMoney('receive', 'first', '6000', 'HUMAN-HQ-FIRST'));
    await step('B05', '总部设计登记线下设计与审核完成', '林设计', async () => {
      await login('hq-design-admin'); await designTask('complete');
      await input('textarea[data-key="note"]', '陈建国家V1平面、立面、施工图及线下专业审核已完成。');
      await check('checked'); await tap('.commission-actions .commission-primary');
    });
    await step('B06', '门店尾款先付2000元，总部核收后仍待补足', '王财务 → 罗财务 → 林设计', async () => {
      await designMoney('pay', 'tail', '2000', 'HUMAN-STORE-TAIL-1');
      await designMoney('receive', 'tail', '2000', 'HUMAN-HQ-TAIL-1');
      await login('hq-design-admin');
      assert(!(await data()).tasks.some(row => row.customerId === customerId && row.actionUrl?.includes('view=dispatch')));
      await workspaceTab(20); await state('projects');
      await tap('.admin-filters button[data-filter="待到账确认"]');
      const waiting = (await data()).projectRows.find(row => row.id === customerId);
      assert(waiting, '设计项目栏目应能找到正在等尾款的原项目');
      assert(/2,?000/.test(waiting.detail) && /4,?000/.test(waiting.detail) && /门店财务/.test(waiting.detail), '等待信息应说明已收2000、差额4000及门店财务接手：' + waiting.detail);
      await tap(urlButton(waiting.url)); await state('progress');
      assert((await data()).rows.some(row => row.label === '当前办理' && /门店财务/.test(row.value)));
    });
    await step('B07', '尾款补足4000元并核收，出现成果下发待办', '王财务 → 罗财务', async () => {
      await designMoney('pay', 'tail', '4000', 'HUMAN-STORE-TAIL-2');
      await designMoney('receive', 'tail', '4000', 'HUMAN-HQ-TAIL-2');
    });
    await step('B08', '总部按原项目下发V1正式成果', '林设计', async () => {
      await login('hq-design-admin'); await designTask('dispatch');
      await input('input[data-key="recipient"]', '李业务');
      await input('textarea[data-key="note"]', '陈建国家V1正式成果已下发，关联同版施工图和线下审核依据。');
      await check('checked'); await tap('.commission-actions .commission-primary');
      assert.equal((await customer()).businessFlow.dispatch.version, 1);
    });
    await step('B09', '业务首页找到移交，选择四类本项目模拟资料并预览', '李业务', async () => {
      await login('sales-dual', 'sales'); await salesTask(); await state('handover');
      for (const key of ['resultFile', 'drawingFile', 'reviewFile', 'confirmationFile']) {
        await sample(key);
        await tap('.commission-file[data-key="' + key + '"]');
        assert((await data()).preview?.text.includes((await customer()).name));
        await tapText('关闭预览', '.commission-mask button');
      }
      await input('input[data-key="drawingVersion"]', 'V1');
      await input('textarea[data-key="note"]', '陈建国家V1道路、水电、首层老人房与二层露台交底已核对。');
      await check('received'); await check('confirmed'); await tap('.commission-actions .commission-primary'); await state('progress');
      assert.equal((await customer()).handover.managerId, 'engineering-dual');
    });
    await step('E00', '工程从首页退回一项交底说明，回到原业务人员', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('移交')); await state('receive');
      await input('textarea[data-key="note"]', '请补充入场道路宽度和本版材料堆放点，保留原V1成果。');
      await tapText('退回补充', '.commission-secondary');
      assert.equal((await customer()).handover.status, 'returned');
    });
    await step('E00b', '业务从退回待办补说明并重交，原四文件与版本保留', '李业务', async () => {
      await login('sales-dual', 'sales'); await salesTask(); await state('handover');
      for (const key of ['resultFile', 'drawingFile', 'reviewFile', 'confirmationFile']) {
        assert.equal((await data()).form[key].meta.projectId, initial.projectId);
        assert.equal((await data()).form[key].meta.customerId, customerId);
      }
      assert.equal((await data()).form.drawingVersion, 'V1');
      await input('textarea[data-key="note"]', '陈建国家V1补充：入场道路宽4.5米，西侧材料堆放点已交底，水电及露台排水按本版图纸。');
      await check('received'); await check('confirmed'); await tap('.commission-actions .commission-primary');
    });
    await step('E01', '工程首页接收业务四类成果和本版交底', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('移交')); await state('receive');
      const received = (await data()).rows.filter(row => row.file?.sample);
      assert.equal(received.length, 4, '工程必须收到同版四类模拟文件');
      for (const row of received) {
        assert.equal(row.file.meta.customerId, customerId);
        assert.equal(row.file.meta.projectId, initial.projectId);
        assert.equal(row.file.meta.version, 'V1');
      }
      const handoverNote = (await customer()).handover.note;
      assert((await data()).rows.some(row => row.label === '电子交底' && row.value === handoverNote));
      await check('checked'); await tap('.commission-actions .commission-primary');
      assert.equal((await customer()).acceptance.managerId, 'engineering-dual');
    });
    await step('E02', '工程首页进入开工准备，登记已签合同及两期收款', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('开工准备'));
      await input('input[data-key="total"]', contractTotal); await input('input[data-key="first"]', startAmount);
      await input('textarea[data-key="note"]', '陈建国家已签施工合同，班组、道路与现场开工条件已核对。');
      await sample(); await tap('[data-action="prepare"]');
    });
    await step('E03', '老板从首页确认与预算对应的合同和两期收款', '张老板', async () => {
      const customerName = (await customer()).name;
      await login('boss'); await bossTask(row => row.name.includes('合同') && (row.detail?.includes(customerName) || row.url?.includes(initial.projectId)));
      await input('textarea[data-key="note"]', '本合同与预算金额一致，开工款' + startAmount + '元、封顶款' + toppingAmount + '元。');
      await check('checked', '.ops-check'); await tap('[data-action="plan-approve"]');
    });
    await step('E04', '门店财务分两笔登记开工款', '王财务', async () => {
      await engineeringReceipt('start', String(startAmount / 2), 'HUMAN-START-1');
      await engineeringReceipt('start', String(startAmount / 2), 'HUMAN-START-2');
    });
    await step('E05', '工程首页记录实际开工', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('记录开工'));
      await input('textarea[data-key="note"]', '陈建国家首款已收齐，班组实际进场开工。'); await sample(); await tap('[data-action="start"]');
      assert((await project()).actualStart);
    });
    await step('E06', '从当前节点进入资金计划并保存', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('待记录'));
      await tap(urlButton((await data()).nodeFundingUrl)); await state('funding');
      await input('input[data-key="amount"]', '10000'); await input('textarea[data-key="note"]', '本节点材料、人工预计用款。');
      await tap('[data-action="funding-plan"]');
    });
    await step('E07', '从节点提交水泥与砂两项材料需求', '张工程', async () => {
      await tap(urlButton((await data()).nodeRecordUrl)); await state('node');
      await tap(urlButton((await data()).nodeMaterialUrl)); await state('materials');
      for (const [index, line] of [{ name: '水泥', quantity: '10', unit: '包' }, { name: '砂', quantity: '2', unit: '立方' }].entries()) {
        if (index) await tap('.record-add-line');
        for (const [key, value] of Object.entries(line)) await input('input[data-index="' + index + '"][data-key="' + key + '"]', value);
      }
      await tap('[data-action="material-plan"]'); await state('material');
    });
    await step('E08', '原材料计划分两批到货并留存凭据', '张工程', async () => {
      for (const quantities of [['6', '1'], ['4', '1']]) {
        for (const [index, quantity] of quantities.entries()) await input('input[data-index="' + index + '"][data-key="actual"]', quantity);
        await sample(); await tap('[data-action="arrival"]'); await state('material');
      }
      assert.equal((await data()).plan.arrival, '已验收齐全');
    });
    await step('E09', '财务待办补齐供应方和采购金额', '王财务', async () => {
      const plan = (await project()).recordFlow.materialPlans.at(-1);
      await login('finance-dual', 'finance'); await financeTask(row => row.id === 'material-terms:' + plan.id);
      for (const [index, line] of [{ supplier: '陈建国家水泥供应方', unitPrice: '30', freight: '0' }, { supplier: '陈建国家砂石供应方', unitPrice: '100', freight: '0' }].entries()) {
        for (const [key, value] of Object.entries(line)) await input('input[data-index="' + index + '"][data-key="' + key + '"]', value);
      }
      await sample('termsFile'); await tap('[data-action="material-terms"]');
    });
    for (let index = 0; index < 2; index++) {
      await step('E10-' + index, '老板审批材料款，财务按原单付款', '张老板 → 王财务', async () => {
        const p = await project(), supply = p.supplies[index], payableId = 'engineering-payable:' + p.id + ':' + supply.id;
        await login('boss'); await bossTask(row => String(row.url || row.key).includes(encodeURIComponent(payableId)) || String(row.url || row.key).includes(payableId));
        await input('textarea[data-key="note"]', '核对陈建国家本项目材料采购与到货依据。'); await check('checked'); await financialSubmit();
        await login('finance-dual', 'finance'); await financeTask(row => row.id === payableId);
        await input('input[data-key="amount"]', String((await data()).state.payables.find(row => row.id === payableId).total / 100));
        await input('input[data-key="transaction"]', 'HUMAN-MATERIAL-' + index); await sample(); await check('checked'); await financialSubmit();
      });
    }
    await step('E11', '按每个施工节点登记现场与验收，封顶后收尾款', '张工程 → 王财务', async () => {
      while ((await project()).plan.nodes.some(node => node.status !== '已完成')) {
        await login('engineering-dual', 'engineer');
        const p = await project(), node = p.plan.nodes.find(node => node.status !== '已完成');
        await engineerTask(row => row.url?.includes('nodeId=' + node.id)); await state('node');
        await input('textarea[data-key="note"]', '陈建国家' + node.name + '现场施工与本节点验收通过。');
        await sample(); if (!(await data()).p.materials.some(row => row.nodeId === node.id && row.quantity > 0)) await check('noMaterials');
        await tap('[data-action="site"]');
        // Site saving stays on the same node; the completion must use the visible action.
        await input('textarea[data-key="note"]', '陈建国家' + node.name + '已核对现场与验收凭据，确认本节点完成。');
        await sample(); if (!(await data()).p.materials.some(row => row.nodeId === node.id && row.quantity > 0)) await check('noMaterials');
        await tap('[data-action="node"]');
        if (node.milestone === 'topping') await engineeringReceipt('topping', String(toppingAmount), 'HUMAN-TOPPING');
      }
      assert((await project()).plan.nodes.every(node => node.status === '已完成'));
    });
    await step('E11b', '从原施工节点提交人工费用，老板审批后财务付款', '张工程 → 张老板 → 王财务', async () => {
      await engineeringProject();
      await tap(urlButton((await data()).nodeRows.at(-1).url)); await state('node');
      await tap(urlButton((await data()).nodeFundingUrl)); await state('funding');
      await tap('button[data-view="expenses"]'); await state('expenses');
      await change('picker[data-key="category"]', (await data()).categories.indexOf('人工'));
      await change('picker[data-key="purpose"]', (await data()).purposes.indexOf('其他'));
      await change('picker[data-key="due"]', (await data()).form.date);
      for (const [key, value] of Object.entries({ name: '陈建国家收尾人工费用', amount: '1000', recipient: '陈建国家施工班组', reference: 'HUMAN-LABOR' })) await input('input[data-key="' + key + '"]', value);
      await input('textarea[data-key="note"]', '本项目末节点实际发生的施工人工费用。');
      await sample(); await tap('[data-action="expense-submit"]');
      assert(!(await data()).error, (await data()).error);
      const expense = await r.mp.evaluate(projectId => {
        const raw = wx.getStorageSync('zhongshu-management-v1');
        const book = raw.companyId === 'quanzhou' ? raw : raw.books.quanzhou;
        return book.operations.expenses.filter(row => row.projectId === projectId).at(-1);
      }, initial.projectId);
      assert(expense?.nodeId === (await project()).plan.nodes.at(-1).id);
      await login('boss'); await bossTask(row => String(row.key || row.url).includes(expense.id));
      await input('textarea[data-key="note"]', '核对原节点人工事项及1000元依据，批准费用。');
      await check('checked', '.ops-check');
      await tap('[data-action="expense-approve"][data-id="' + expense.id + '"]');
      await login('finance-dual', 'finance'); await financeTask(row => row.id === 'expense-payable:' + expense.id);
      await input('input[data-key="amount"]', '1000'); await input('input[data-key="transaction"]', 'HUMAN-LABOR-PAID');
      await sample(); await check('checked'); await financialSubmit();
    });
    await step('E12', '工程首页完成业主签收交付与施工资料归档', '张工程', async () => {
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('交付'));
      await input('input[data-key="recipient"]', '陈建国（业主本人）');
      await input('textarea[data-key="note"]', '同一项目全部施工节点完成，业主核对本版成果、实体工程和移交资料后签收。');
      await sample(); await tap('[data-action="deliver"]'); await state('archive');
      await login('engineering-dual', 'engineer'); await engineerTask(row => row.title.includes('归档')); await state('archive');
      await tap('[data-action="archive"]'); assert((await project()).archived && (await project()).delivery);
    });
    await step('E13', '从交付档案登记原项目保修回访', '张工程', async () => {
      await tapText('保修记录'); await state('warranty');
      await input('textarea[data-key="note"]', '业主入住回访：露台排水检查已完成并由陈建国确认。');
      await sample(); await check('completed'); await tap('[data-action="warranty"]');
    });
    await step('F01', '财务从项目账核对原项目累计收付款与交付', '王财务', async () => {
      await login('finance-dual', 'finance');
      const desk = (await data()).financeDesk, shortcut = desk.shortcuts.find(row => row.label === '项目收支');
      await tap(urlButton(shortcut.url)); await state('projects');
      const row = (await data()).rows.find(row => row.id === initial.projectId); assert(row);
      await tap(urlButton(row.url)); await state('project');
      assert.equal((await data()).detail.received, contractTotal * 100);
      const p = await project(); assert(p.archived && p.delivery && p.plan.nodes.every(node => node.status === '已完成'));
      assert.equal(p.collections.reduce((sum, row) => sum + row.cents, 0), contractTotal * 100);
      assert(!(await data()).allTasks.some(row => row.projectId === initial.projectId && row.view === 'pay'));
    });
    report.result = stopped ? 'partial-passed' : 'passed';
    report.final = { customer: await customer(), project: await project(), exceptions: r.errors };
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    persist();
  } catch (error) {
    report.result = 'failed'; report.error = error.stack; report.failedPage = await current();
    save('failure-state.json', await snapshot()); await r.capture('failure').catch(() => {}); persist(); throw error;
  } finally { await r.restore(); }
  console.log(JSON.stringify({ result: report.result, steps: report.steps.length, customerId, projectId: initial.projectId }));
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
