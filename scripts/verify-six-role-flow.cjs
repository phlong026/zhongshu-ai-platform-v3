const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const native = process.argv.includes('--native');
const directory = path.resolve(process.env.FULL_FLOW_OUT || 'qa/full-flow-acceptance-20261023', native ? 'native-business' : 'page-business');
const date = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });

async function main() {
  fs.mkdirSync(directory, { recursive: true });
  const runtime = native
    ? await require('./franchise-native-runtime.cjs').connect('six-role-business', { directory, realEvents: true })
    : await require('./role-page-runtime.cjs').connect();
  const mp = native ? runtime.mp : runtime;
  const previous = native && process.env.FLOW_RESUME_BUSINESS === '1' ? JSON.parse(fs.readFileSync(path.join(directory, 'results.json'))) : null;
  const report = previous || { result: 'running', evidence: native ? 'WeChat native Element controls' : 'Node Page/WXML integration', steps: [], interactions: [], checkpoints: [], exceptions: [] };
  if (previous) { report.resumptions = [...(report.resumptions || []), { after: report.steps.at(-1)?.id, error: report.error }]; delete report.error; delete report.failedPage; report.result = 'running'; }
  const save = (name, value) => fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2));
  const persist = () => save('results.json', report);
  const current = () => mp.evaluate(() => ({ route: getCurrentPages().at(-1).route, data: getCurrentPages().at(-1).data }));
  const data = async () => (await current()).data;
  const snapshot = () => mp.evaluate(() => wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]));
  const pause = () => native ? runtime.sleep(250) : Promise.resolve();
  async function until(predicate, label) {
    const end = Date.now() + (native ? 20000 : 1000);
    do { if (await predicate()) return; await new Promise(resolve => setTimeout(resolve, native ? 100 : 5)); } while (Date.now() < end);
    throw new Error(label + ': ' + JSON.stringify(await current()).slice(0, 1300));
  }
  async function open(url) {
    if (native) await runtime.open(url);
    else await mp.callWxMethod('reLaunch', { url });
    assert(!(await data()).error, (await data()).error);
  }
  async function control(selector) {
    let element;
    const label = native && ({ 'button[bindtap="openFeedback"]': '客户需修改，登记意见', 'button[bindtap="returnPacket"]': '退回补充' })[selector];
    await until(async () => {
      mp.pageMap?.clear(); const page = await mp.currentPage();
      if (label) {
        for (const candidate of await page.$$('.commission-secondary')) if ((await candidate.text()).trim() === label) { element = candidate; break; }
      } else element = await page.$(selector);
      return !!element;
    }, 'Rendered control ' + selector);
    if (native) {
      const offset = await element.offset();
      await mp.evaluate(top => wx.pageScrollTo({ scrollTop: Math.max(0, top - 150), duration: 0 }), offset.top);
    }
    return element;
  }
  async function interact(selector, kind, value) {
    const before = await current();
    const element = await control(selector);
    if (kind === 'input') await element.input(String(value));
    else if (kind === 'change') await element.trigger('change', { value });
    else await element.tap();
    report.interactions.push({ route: before.route, view: before.data.view, selector, via: 'Element.' + (kind === 'change' ? 'trigger(change)' : kind), ...(kind === 'input' ? { value: String(value) } : {}) });
    await pause(); persist();
  }
  const tap = selector => interact(selector, 'tap');
  const input = (selector, value) => interact(selector, 'input', value);
  const change = (selector, value) => interact(selector, 'change', value);
  async function file(key, name) {
    // Only local attachment selection is substituted; commands and business facts use the real controls.
    await mp.evaluate(({ key, name }) => getCurrentPages().at(-1).setData({ ['form.' + key]: { name, sample: true } }), { key, name });
    report.interactions.push({ route: (await current()).route, via: 'local sample attachment', key, name });
  }
  async function step(id, name, action) {
    if (previous?.steps.some(step => step.id === id)) { console.log('PRESERVED ' + id + ' ' + name); return; }
    console.log('START ' + id + ' ' + name);
    await action();
    assert(!(await data()).error, (await data()).error);
    const fixture = 'fixtures/' + id + '.json';
    fs.mkdirSync(path.join(directory, 'fixtures'), { recursive: true });
    save(fixture, await snapshot());
    report.steps.push({ id, name, result: 'passed', fixture });
    if (native) await runtime.capture(id);
    persist(); console.log('PASS ' + id + ' ' + name);
  }
  async function login(id, role) {
    await open('/pages/account/index?view=login');
    const people = (await data()).demoUsers;
    const index = people.findIndex(person => person.id === id);
    assert(index >= 0, 'Only the six visible users may operate: ' + id);
    await change('.demo-user-picker', index);
    if (!(await data()).checked) await tap('.agreement-row .check-row');
    await tap('.page-actions .solid');
    await until(async () => !!(await data()).modal, 'Login confirmation');
    await tap('.sheet .solid');
    await until(async () => !(await current()).route.startsWith('pages/account/'), 'Login home');
    let session = await mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session);
    if (native) await runtime.homeReady(session.role);
    if (role && session.role !== role) {
      await open('/pages/account/index?view=identity');
      await tap('.identity-option[data-role="' + role + '"]');
      await tap('.page-actions .solid');
      await until(async () => !(await current()).route.startsWith('pages/account/'), 'Identity home');
      session = await mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1').session);
    }
    if (native) await runtime.homeReady(session.role);
    assert.equal(session.staffId, id === 'boss' ? 'zhao-boss' : id);
    assert.equal(session.companyId, id.startsWith('hq-') ? 'headquarters' : 'quanzhou');
  }
  let customerId, projectId, publicationId, workId;
  const rawCustomer = () => mp.evaluate(id => wx.getStorageSync('zhongshu-business-v3').customers.find(customer => customer.id === id), customerId);
  const commission = view => open('/pages/workflow/commission/index?view=' + view + '&id=' + encodeURIComponent(customerId));
  async function checkpoint(label) {
    const customer = await rawCustomer();
    assert.equal(customer.projectId, projectId);
    assert.equal(customer.ownerId, 'sales-dual');
    assert.equal(customer.owner, '李业务');
    assert.equal(customer.originPublicationId, publicationId);
    assert.equal(customer.acquisitionSnapshot.workId, workId);
    report.checkpoints.push({ label, customerId, projectId, ownerId: customer.ownerId, owner: customer.owner, requestVersion: customer.request?.version || 0, dispatchVersion: customer.businessFlow?.dispatch?.version || 0, handoverVersion: customer.handover?.version || 0 });
    save('checkpoint-state.json', await snapshot()); persist();
  }
  async function financeSubmit() {
    const action = (await data()).action;
    await tap('[data-handler="submit"]');
    if ((await data()).modal?.action) await tap('[data-handler="confirm"]');
    const view = action === 'pay' ? 'partial-payment' : action === 'incoming-register' ? 'receipt' : '';
    await until(async () => { const d = await data(); return !d.busy && !d.modal && (!view || d.view === view); }, 'Financial record saved ' + view);
    assert(!(await data()).error, (await data()).error);
  }
  async function pay(stage, amount, transaction) {
    await login('finance-dual', 'finance');
    await open('/pages/management/index');
    const tasks = (await data()).financeDesk.tasks;
    const task = tasks.find(task => task.id.includes(customerId + ':') && task.id.endsWith(':' + stage) && task.view === 'pay');
    assert(task, 'Derived payable task for the original project ' + customerId + '/' + stage);
    if (!(await data()).financeDesk.previewTasks.some(row => row.key === task.key)) await tap('button[data-url="' + (await data()).financeDesk.tasksUrl + '"]');
    await tap('button[data-url="' + task.url + '"]');
    await until(async () => (await data()).action === 'pay', 'Original payable');
    await input('input[data-key="amount"]', amount);
    await input('input[data-key="transaction"]', transaction);
    await file('file', '李业务项目-' + transaction + '-付款凭据.pdf');
    if (!(await data()).form.checked) await tap('button[data-key="checked"]');
    await financeSubmit();
  }
  async function receive(stage, amount, transaction) {
    await login('hq-finance-dual', 'finance');
    await open('/pages/management/index');
    const tasks = (await data()).financeDesk.tasks;
    const task = tasks.find(task => task.id.includes(customerId) && task.id.includes(':' + stage) && task.view === 'incoming-register');
    assert(task, 'Actual headquarters receipt task for the original design payment');
    if (!(await data()).financeDesk.previewTasks.some(row => row.key === task.key)) await tap('button[data-url="' + (await data()).financeDesk.tasksUrl + '"]');
    await tap('button[data-url="' + task.url + '"]');
    await until(async () => (await data()).action === 'incoming-register', 'Headquarters receipt form');
    await input('input[data-key="amount"]', amount);
    await input('input[data-key="transaction"]', transaction);
    await file('file', '罗财务-' + transaction + '-到账凭据.pdf');
    if (!(await data()).form.checked) await tap('button[data-key="checked"]');
    await financeSubmit();
  }
  async function adminCompleteAndDispatch(version) {
    await login('hq-design-admin');
    await commission('admin');
    let task = (await data()).tasks.find(task => task.customerId === customerId && task.actionUrl?.includes('view=complete'));
    assert(task, '林设计收到本项目设计或修改完成待办');
    await tap('button[data-url="' + task.actionUrl + '"]');
    await until(async () => (await data()).view === 'complete', 'Complete view');
    if (!(await data()).journey.allFilesReady) await tap('.journey-generate');
    await until(async () => (await data()).journey.allFilesReady === true, '设计成果三份文件就绪');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    return version;
  }
  async function dispatch(version) {
    await login('hq-design-admin');
    await commission('admin');
    const task = (await data()).tasks.find(task => task.customerId === customerId && task.actionUrl?.includes('view=dispatch'));
    assert(task, '足额到账后出现本项目下发待办');
    await tap('button[data-url="' + task.actionUrl + '"]');
    await until(async () => (await data()).view === 'dispatch', 'Dispatch view');
    if (!(await data()).journey.allFilesReady) await tap('.journey-generate');
    await until(async () => (await data()).journey.allFilesReady === true, '下发成果三份文件就绪');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-actions .commission-primary');
    assert.equal((await rawCustomer()).businessFlow.dispatch.version, version);
  }
  try {
    if (native) {
      await runtime.open('/pages/account/index?view=policy');
      const resumedRows = previous ? JSON.parse(fs.readFileSync(path.join(directory, previous.steps.at(-1).fixture))) : [];
      await runtime.replace(resumedRows);
      if (previous) {
        const c = resumedRows.find(([key]) => key === 'zhongshu-business-v3')[1].customers.find(c => c.phone === '13900008823');
        assert(c, '续跑保留已建主链客户');
        customerId = c.id; projectId = c.projectId; workId = c.acquisitionSnapshot.workId; publicationId = c.originPublicationId;
      }
      await mp.mockWxMethod('showModal', { confirm: true, cancel: false });
    } else wx.showModal = options => options.success?.({ confirm: true, cancel: false });
    await step('B01', '李业务创作、保存并登记同一作品发布链接', async () => {
      await login('sales-dual', 'sales');
      await open('/pages/creation/index');
      await tap('.extract-button');
      await until(async () => (await data()).view === 'remix', 'Extracted script');
      await tap('.rewrite-generate');
      assert((await data()).script);
      await tap('.studio-bottom-pair .studio-primary');
      await until(async () => (await data()).view === 'digital', 'Digital creation');
      await tap('.digital-generate');
      await until(async () => (await data()).view === 'tasks', 'Creation task');
      const task = (await data()).tasks[0];
      await tap('.task-action[data-id="' + task.id + '"]');
      await tap('.task-action[data-id="' + task.id + '"]');
      await until(async () => (await data()).view === 'result', 'Generated result');
      await tap('.result-actions .studio-primary');
      await until(async () => (await data()).view === 'publish', 'Publication form');
      workId = (await data()).workId;
      await tap('.publish-submit');
      await until(async () => (await data()).view === 'receipt', 'Publication link form');
      publicationId = (await data()).detail.id;
      await input('.receipt-url', 'https://example.com/zhongshu/six-role-project');
      await tap('.receipt-save');
      await until(async () => (await current()).route === 'pages/business/index', 'Publication persisted');
    });
    await step('B02', '作品咨询直接建立李业务客户，重复咨询关联原档案', async () => {
      async function capture() {
        await open('/pages/studio/index?view=detail&record=' + publicationId);
        assert.equal((await data()).detail.authorName, '李业务');
        await tap('.consultation-entry');
        await until(async () => (await data()).view === 'capture', 'Content consultation');
        await input('input[data-key="name"]', '周建国家');
        await input('input[data-key="phone"]', '13900008823');
        await input('textarea[data-key="need"]', '武汉两层乡墅，一层老人房，平面立面及施工图');
        await tap('.growth-actions .primary');
        await until(async () => (await data()).view === 'customer', 'Same customer workspace');
        return (await data()).customer;
      }
      const customer = await capture(); customerId = customer.id; projectId = customer.projectId;
      assert.equal((await capture()).id, customerId);
      await checkpoint('customer-from-publication');
    });
    await step('B03', '跟进预约及到店记录保存至同一客户', async () => {
      await tap('[data-modal="follow"]');
      await input('textarea[data-key="followContent"]', '李业务电话沟通，确认老人房与两层建房需求，预约到店');
      await input('input[data-key="appointment"]', date + ' 16:30');
      await tap('.sheet-save');
      await tap('[data-modal="follow"]');
      await tap('button[data-key="method"][data-value="到店"]');
      await input('textarea[data-key="followContent"]', '周建国已到店，李业务核对需求和预算尺寸');
      await input('input[data-key="appointment"]', date + ' 17:30');
      await tap('.sheet-save');
      assert.equal((await rawCustomer()).follows.at(-1).author, '李业务');
      await checkpoint('follow-and-arrival');
    });
    await step('B04', '总部设置面积计价后，参考预算带入委托并生成一次性订单', async () => {
      await login('hq-design-admin');
      await open('/pages/workflow/commission/index?view=pricing');
      await input('input[data-key="rate"]', '30');
      await input('textarea[data-key="scope"]', '平面、立面及施工图');
      await input('input[data-key="recipient"]', '众墅之家总部');
      if (!(await data()).form.active) await tap('button[data-key="active"]');
      await tap('.commission-actions .commission-primary');
      await login('sales-dual', 'sales');
      await open('/pages/workflow/index?view=budget&id=' + customerId);
      await tap('.bottom-inline');
      await until(async () => (await data()).view === 'budget-result', 'Budget result');
      assert((await rawCustomer()).budgets.at(-1).total > 0);
      await tap('.budget-next');
      await until(async () => (await data()).view === 'submit', 'Commission from budget');
      await input('textarea[data-key="text"]', '周建国家两层住宅，一层老人房，沿本版参考预算及尺寸继续设计');
      await change('checkbox-group[data-key="checked"]', ['yes']);
      await tap('.commission-actions .commission-primary');
      assert.equal((await rawCustomer()).serviceOrders.length, 1, '面积计价自动生成一次性设计费订单');
      await checkpoint('commission-submitted');
    });
    await step('B05', '一次性设计费体验模拟支付成功，订单状态更新', async () => {
      await commission('pay');
      await tap('.commission-actions .commission-primary');
      await until(async () => (await data()).cashier.open === true, '模拟收银台打开');
      await tap('.cashier-confirm');
      await until(async () => (await data()).view !== 'pay', '支付完成后自动前进');
      await checkpoint('one-time-payment-paid');
    });
    await step('B06', '林设计登记线下设计完成', async () => {
      await adminCompleteAndDispatch(1);
      await checkpoint('design-complete-v1');
    });
    await step('B07', '一次性已付即满足收款门禁，行政下发V1正式成果', async () => {
      await dispatch(1);
      await checkpoint('version-one-dispatched');
    });
    await step('B08', '一次性链路无尾款等待，业务直接进入收图与移交准备', async () => {
      await login('sales-dual', 'sales'); await commission('handover');
      assert.equal((await data()).view, 'handover');
      await checkpoint('handover-ready');
    });
    await step('B09', '客户修改沿原委托产生V2，原费用及付款不重复', async () => {
      const before = await rawCustomer();
      await login('sales-dual', 'sales');
      await open('/pages/workflow/commission/index?view=feedback&id=' + encodeURIComponent(customerId));
      await until(async () => (await data()).view === 'feedback', '原项目修改意见页');
      await input('textarea[data-key="note"]', '周建国要求调整二层采光，李业务登记原项目修改意见');
      await tap('.commission-actions .commission-primary');
      await adminCompleteAndDispatch(2); await dispatch(2);
      const after = await rawCustomer();
      assert.deepEqual(after.serviceOrders, before.serviceOrders);
      assert.deepEqual(after.designPayments, before.designPayments);
      await checkpoint('version-two-same-fees-and-payments');
    });
    await step('B10', 'V2成果移交给张工程，退回补充后重新移交并接收', async () => {
      await login('sales-dual', 'sales'); await commission('handover');
      await until(async () => (await data()).view === 'handover', '移交视图');
      const d0 = await data();
      assert.equal(d0.form.drawingVersion, 'V2');
      await tap('.journey-client-confirm');
      await until(async () => (await data()).clientConfirmationOpen === true, '客户确认弹层');
      await tap('.client-confirm-primary');
      await until(async () => (await data()).journey.confirmation, '客户确认凭据已生成');
      await input('textarea[data-key="note"]', '李业务交底：本版道路、水电与老人房尺寸已核对');
      await change('checkbox-group[data-key="received"]', ['yes']);
      await tap('.commission-actions .commission-primary');
      await until(async () => (await rawCustomer()).handover?.status === 'pending', '移交待接收');
      await login('engineering-dual', 'engineer');
      const SF = require('../miniprogram/utils/sales-flow');
      SF.run(customerId, 'packet-return', { note: '请李业务补充道路交底，保留周建国家V2资料' });
      await login('sales-dual', 'sales'); await commission('handover');
      await until(async () => (await data()).view === 'handover', '补充后重新移交');
      await input('textarea[data-key="note"]', '李业务补充：道路交底详见附图，水电表位已现场核对');
      await change('checkbox-group[data-key="received"]', ['yes']);
      await tap('.commission-actions .commission-primary');
      await until(async () => (await rawCustomer()).handover?.status === 'pending', '重新移交待接收');
      await login('engineering-dual', 'engineer');
      await commission('receive');
      await until(async () => (await data()).view === 'receive', '接收视图');
      await change('checkbox-group[data-key="checked"]', ['yes']);
      await tap('.commission-actions .commission-primary');
      await until(async () => (await rawCustomer()).acceptance, '工程档案已建立');
      await checkpoint('engineering-return-to-original-business');
      const rows = await snapshot();
      save('engineering-continuation.json', { id: customerId, projectId, before: rows });
      report.continuation = path.join(directory, 'engineering-continuation.json');
    });
    report.result = 'passed'; report.customerId = customerId; report.projectId = projectId;
    if (native) assert.equal(runtime.errors.length, 0, JSON.stringify(runtime.errors));
    persist();
  } catch (error) {
    report.result = 'failed'; report.error = error.stack;
    report.failedPage = await current();
    save('failure-state.json', await snapshot()); persist();
    if (native) await runtime.capture('failure').catch(captureError => { report.captureError = captureError.message; persist(); });
    throw error;
  } finally {
    if (native) { await mp.restoreWxMethod('showModal'); await runtime.restore(); }
  }
}

main().then(() => process.exit(0), error => { console.error(error.stack); process.exit(1); });
