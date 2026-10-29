const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const outputDirectory = path.resolve(process.env.DESIGN_CHECKOUT_QA_DIR || 'qa/design-journey-20261024');
fs.mkdirSync(outputDirectory, { recursive: true });

const clone = value => JSON.parse(JSON.stringify(value));
const save = (name, value) => fs.writeFileSync(path.join(outputDirectory, name), JSON.stringify(value, null, 2));

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const SalesFlow = require('../miniprogram/utils/sales-flow');
  const Checkout = require('../miniprogram/utils/design-checkout');
  const Engineering = require('../miniprogram/pages/build/model');

  const report = {
    result: 'running',
    date: '2026-10-24',
    scope: '真实 Page 处理器与 WXML 控件事件；仅注入测试账号和已保存的400㎡预算起点，不直接写入委托、支付、设计或移交状态。',
    evidence: 'Node Page/WXML integration runtime; not native WeChat rendering and not a real payment',
    actors: {
      design: { loginId: 'hq-design-admin', name: '林设计' },
      sales: { loginId: 'sales-dual', role: 'sales-lead', name: '李业务' },
      storeFinance: { loginId: 'finance-dual', role: 'finance', name: '王财务' },
      headquartersFinance: { loginId: 'hq-finance-dual', role: 'finance', name: '罗财务' },
      engineering: { loginId: 'engineering-dual', role: 'project-manager', name: '张工程' }
    },
    checks: [],
    actions: [],
    scenes: []
  };

  const page = () => getCurrentPages().at(-1);
  const open = url => wx.reLaunch({ url });
  const customer = id => wx.getStorageSync(Business.KEY).customers.find(row => row.id === id);
  const snapshot = () => wx.getStorageInfoSync().keys
    .filter(key => key.startsWith('zhongshu-'))
    .sort()
    .map(key => [key, wx.getStorageSync(key)]);
  const check = (name, detail = {}) => {
    report.checks.push({ name, result: 'passed', ...detail });
    console.log('PASS ' + name);
  };
  const currentElement = selector => runtime.currentPage().then(current => current.$(selector));
  const requireElement = async selector => {
    const element = await currentElement(selector);
    assert(element, '页面上应显示可操作控件：' + selector);
    return element;
  };
  const tap = async selector => (await requireElement(selector)).tap();
  const input = async (selector, value) => (await requireElement(selector)).input(String(value));
  const change = async (selector, value) => (await requireElement(selector)).trigger('change', { value });
  const login = (loginId, role) => {
    Account.mutate(state => Account.loginDemo(state, loginId));
    if (role) Account.mutate(state => Account.switchIdentity(state, state.session.companyId, role));
  };
  const record = (id, action, before, after, extra = {}) => {
    report.actions.push({ id, action, customerId: id, projectId: after.projectId, beforeRevision: before.revision || 0, afterRevision: after.revision || 0, ...extra });
    report.scenes.push({ action, id, input: extra.input || {}, before: before.__fixture, after: snapshot() });
  };
  const fixtureCustomer = (name, phone, city) => {
    const row = Business.add({ name, phone, city, source: '门店到访', summary: '两层乡墅，建筑面积400㎡，委托总部完成全套设计及施工图。' });
    Business.write(row.id, next => {
      next.budgets = [{
        version: 1,
        area: 400,
        total: 600000,
        date: Account.localDate(),
        parameters: { width: 12.5, depth: 16, floors: 2, area: 400 }
      }];
    }, '保存400㎡参考预算', 'budget');
    return Business.customer(row.id);
  };

  async function savePricingThroughProfile() {
    login('hq-design-admin');
    open('/pages/profile/index');
    const entry = '.profile-menu[data-url="/pages/workflow/commission/index?view=pricing"]';
    await tap(entry);
    assert.equal(page().route, 'pages/workflow/commission/index');
    assert.equal(page().data.view, 'pricing');
    await input('input[data-key="rate"]', '20');
    await input('textarea[data-key="scope"]', '全套方案设计、立面及施工图');
    await input('input[data-key="recipient"]', '众墅之家总部');
    if (!page().data.form.active) await change('checkbox-group[data-key="active"]', ['yes']);
    await tap('.commission-primary');
    const pricing = Checkout.pricing();
    assert.equal(pricing.rateCents, 2000);
    assert.equal(pricing.active, true);
    check('总部设计从“我的”进入计价设置并保存20元/㎡规则', { pricingVersion: pricing.version });
  }

  async function submitCommission(id) {
    login('sales-dual', 'sales-lead');
    open(SalesFlow.url(id, 'submit'));
    assert.equal(page().data.pricingQuote.amountCents, 800000);
    assert.equal(page().data.pricingQuote.area, 400);
    const before = clone(customer(id));
    before.__fixture = snapshot();
    await input('textarea[data-key="text"]', '已有宅基地，两层乡墅，老人房与露台按本版预算尺寸设计。');
    assert.equal(page().data.form.scope, '全套方案设计、立面及施工图', '交付范围应由总部计价规则自动带入');
    await tap('.journey-attachment');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-primary');
    const after = customer(id), order = Checkout.order(after);
    assert.equal(order.amountCents, 800000);
    assert.equal(order.area, 400);
    assert.equal(order.rateCents, 2000);
    assert.equal(order.status, 'pending');
    assert.equal(SalesFlow.next(after).view, 'pay');
    record(id, 'commission-submit', before, after, { input: { paymentMode: 'area-full-demo-v1', area: 400, rateCents: 2000 } });
    check(after.name + '从400㎡预算自动生成一次性8000元设计费订单', { customerId: id, orderId: order.id });
    return order;
  }

  async function choosePaymentChannel(channel) {
    const channelIndex = channel === 'alipay' ? 1 : 0;
    const channelButton = await currentElement(`button[data-channel="${channel}"]`);
    if (channelButton) await channelButton.tap();
    else await change('picker[data-key="channel"]', channelIndex);
  }

  async function attemptPayment(id, channel, outcome) {
    login('sales-dual', 'sales-lead');
    open(SalesFlow.url(id, 'pay'));
    const before = clone(customer(id));
    before.__fixture = snapshot();
    await choosePaymentChannel(channel);
    await tap('.journey-pay');
    assert(page().data.cashier.open, '点击付款先打开收银台');
    assert.equal(customer(id).businessFlow.paymentOrder.status, 'pending');
    if (outcome === 'success') await tap('.cashier-confirm');
    else await tap('.cashier-close');
    const after = customer(id);
    if (outcome !== 'success') {
      assert.equal(Checkout.order(after).status, 'pending');
      assert.equal(SalesFlow.next(after).view, 'pay');
      login('hq-design-admin');
      assert(!SalesFlow.tasks().some(task => task.customerId === id && task.action === '登记设计完成'));
      check(after.name + (outcome === 'failed' ? '模拟失败' : '取消') + '支付不创建总部设计待办', { customerId: id, channel, outcome });
      return;
    }
    assert(Checkout.paid(after));
    assert.equal(Checkout.order(after).channel, channel);
    assert.equal(Checkout.order(after).amountCents, 800000);
    assert.equal(SalesFlow.next(after).view, 'complete');
    record(id, 'commission-pay', before, after, { input: { channel, outcome } });
    check(after.name + '通过' + (channel === 'alipay' ? '支付宝' : '微信') + '完成体验版模拟一次性支付', { customerId: id, channel });
  }

  async function verifyFinanceCopy(loginId, role, id, label) {
    login(loginId, role);
    open('/pages/profile/index');
    await tap('button[data-action="messages"]');
    assert.equal(page().data.view, 'messages');
    const message = page().data.messages.find(row => row.objectId === id && /8000|8,000/.test(row.description) && /模拟一次性支付/.test(row.description));
    assert(message, label + '的消息中应有当前项目的支付抄送');
    await tap('button[data-customer="' + id + '"]');
    assert.equal(page().data.paymentReceipt.customerName, customer(id).name);
    await tap('.payment-receipt-close');
    await tap('button[data-handler="readAll"]');
    const account = Account.load();
    assert(Account.scoped(account, account.messages).find(row => row.id === message.id)?.read);
    check(label + '收到当前客户、项目和8000元支付渠道一致的抄送消息', { customerId: id, messageId: message.id });
  }

  async function completeAndDispatch(id) {
    login('hq-design-admin');
    open('/pages/workflow/commission/index?view=admin');
    let task = page().data.tasks.find(row => row.customerId === id && row.actionUrl.includes('view=complete'));
    assert(task, '支付后总部设计首页应出现设计完成待办');
    await tap(`button[data-url="${task.actionUrl}"]`);
    assert.equal(page().data.view, 'complete');
    let before = clone(customer(id));
    before.__fixture = snapshot();
    await tap('.journey-generate');
    assert(page().data.form.drawingFile.previewKind === 'drawing');
    await tap('button[data-preview-key="drawingFile"]');
    assert(page().data.documentPreview.image);
    await tap('.document-close');
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-primary');
    let after = customer(id);
    record(id, 'design-complete', before, after, { input: { checked: true } });

    open('/pages/workflow/commission/index?view=admin');
    task = page().data.tasks.find(row => row.customerId === id && row.actionUrl.includes('view=dispatch'));
    assert(task, '设计完成后总部设计首页应出现上传下发待办');
    await tap(`button[data-url="${task.actionUrl}"]`);
    assert.equal(page().data.view, 'dispatch');
    before = clone(customer(id));
    before.__fixture = snapshot();
    assert.equal(page().data.form.drawingVersion, 'V1');
    assert(page().data.form.resultFile && page().data.form.drawingFile && page().data.form.reviewFile);
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-primary');
    after = customer(id);
    assert(after.businessFlow.dispatch.resultFile?.sample);
    assert(after.businessFlow.dispatch.drawingFile?.sample);
    assert(after.businessFlow.dispatch.reviewFile?.sample);
    for (const file of [after.businessFlow.dispatch.resultFile, after.businessFlow.dispatch.drawingFile, after.businessFlow.dispatch.reviewFile]) {
      assert.equal(file.meta.customerId, id);
      assert.equal(file.meta.projectId, after.projectId);
    }
    record(id, 'design-dispatch', before, after, { input: { drawingVersion: 'V1', files: 3 } });
    check('总部设计待办连续完成并上传同项目的3份可预览成果', { customerId: id, files: 3 });
  }

  async function handoverThroughSalesTodo(id) {
    login('sales-dual', 'sales-lead');
    open('/pages/business/index');
    const task = page().data.flowPending.find(row => row.customerId === id && row.actionable);
    assert(task, '设计下发后业务工作台应出现移交给工程待办');
    if (!page().data.todoRows.some(row => row.id === task.id && row.show)) {
      const more = await currentElement('.sales-todo-more');
      if (more) await more.tap();
    }
    await tap(`button[bindtap="openFlowTask"][data-id="${task.id}"]`);
    assert.equal(page().data.view, 'handover');
    const before = clone(customer(id));
    before.__fixture = snapshot();
    for (const key of ['resultFile', 'drawingFile', 'reviewFile']) {
      assert(page().data.form[key], '总部下发的' + key + '应自动带入业务移交表单');
      assert.equal(page().data.form[key].meta.customerId, id);
    }
    await tap('.journey-client-confirm');
    await tap('.client-confirm-primary');
    await input('textarea[data-key="note"]', '客户确认V1成果；现场放线以本版施工图为准，入场前再核对宅基地边界。');
    await change('checkbox-group[data-key="received"]', ['yes']);
    assert(page().data.form.confirmed, '客户确认动作应生成凭据并标记本版确认');
    await tap('.commission-primary');
    const after = customer(id);
    assert(after.handover && after.handover.status === 'pending');
    assert.equal(SalesFlow.packageRows(after).filter(row => row.file).length, 5);
    record(id, 'packet-submit', before, after, { input: { files: 5, drawingVersion: 'V1' } });
    check('业务从本人待办进入，自动带入3份总部文件并补齐客户确认和电子交底', { customerId: id, files: 5 });
  }

  async function receiveThroughEngineeringTodo(id) {
    login('engineering-dual', 'project-manager');
    open('/pages/build/home/index');
    const task = page().data.eng.tasks.find(row => row.customerId === id || row.id === 'handover:' + id);
    assert(task, '业务移交后工程工作台应出现接收待办');
    await tap('button[data-action="todos"]');
    await tap(`button[data-url="${task.url}"]`);
    assert.equal(page().data.view, 'receive');
    const before = clone(customer(id));
    before.__fixture = snapshot();
    await change('checkbox-group[data-key="checked"]', ['yes']);
    await tap('.commission-primary');
    const after = customer(id);
    const project = Engineering.load().projects.find(row => row.sourceCustomerId === id && row.recordFlow);
    assert.equal(page().data.view, 'created');
    assert(after.acceptance);
    assert(project, '接收应自动建立工程项目');
    assert.equal(project.projectId, after.projectId);
    assert.equal(project.documents.length, 5, '工程项目资料应为5份：' + JSON.stringify(project.documents.map(row => row.name)));
    assert.equal(new Set(project.documents.map(row => row.name)).size, 5);
    record(id, 'packet-receive', before, after, { input: { checked: true }, engineeringId: project.id });
    check('工程从本人待办接收后自动建项并带入同项目5份资料', { customerId: id, engineeringId: project.id, files: project.documents.length });
  }

  try {
    await savePricingThroughProfile();
    login('sales-dual', 'sales-lead');
    const wechatCustomer = fixtureCustomer('陈先生（微信支付闭环）', '13900008931', '武汉市');
    const alipayCustomer = fixtureCustomer('林女士（支付宝隔离项目）', '13900008932', '黄冈市');
    report.customers = [wechatCustomer, alipayCustomer].map(row => ({ id: row.id, projectId: row.projectId, name: row.name, area: 400 }));
    save('initial-state.json', snapshot());

    const wechatOrder = await submitCommission(wechatCustomer.id);
    await attemptPayment(wechatCustomer.id, 'wechat', 'cancelled');
    await attemptPayment(wechatCustomer.id, 'wechat', 'success');
    await verifyFinanceCopy('finance-dual', 'finance', wechatCustomer.id, '门店财务');
    await verifyFinanceCopy('hq-finance-dual', 'finance', wechatCustomer.id, '总部财务');

    const alipayOrder = await submitCommission(alipayCustomer.id);
    await attemptPayment(alipayCustomer.id, 'alipay', 'cancelled');
    await attemptPayment(alipayCustomer.id, 'alipay', 'success');
    assert.notEqual(wechatOrder.id, alipayOrder.id);
    assert.notEqual(wechatCustomer.projectId, alipayCustomer.projectId);
    assert.equal(Checkout.order(customer(wechatCustomer.id)).channel, 'wechat');
    assert.equal(Checkout.order(customer(alipayCustomer.id)).channel, 'alipay');
    check('第二客户以支付宝跑通独立订单，与微信项目的客户、项目和订单完全隔离', { first: wechatCustomer.id, second: alipayCustomer.id });

    await completeAndDispatch(wechatCustomer.id);
    assert(!customer(alipayCustomer.id).businessFlow.completed, '第二客户不得被第一客户的设计操作串单');
    await handoverThroughSalesTodo(wechatCustomer.id);
    await receiveThroughEngineeringTodo(wechatCustomer.id);
    assert(!customer(alipayCustomer.id).handover && !customer(alipayCustomer.id).acceptance, '第二客户不得被第一客户的移交与接收操作污染');

    report.result = 'passed';
    report.summary = { checks: report.checks.length, pageActions: report.actions.length, customers: 2, paymentChannels: ['wechat', 'alipay'], engineeringDocuments: 5 };
    save('page-scenes.json', report.scenes);
    save('final-state.json', snapshot());
    const result = { ...report };
    delete result.scenes;
    save('results.json', result);
    const failedState = path.join(outputDirectory, 'failed-state.json');
    if (fs.existsSync(failedState)) fs.unlinkSync(failedState);
    console.log(`COMPLETE ${report.checks.length} checks, ${report.actions.length} state-changing Page actions`);
  } catch (error) {
    report.result = 'failed';
    report.error = error.stack;
    report.failedPage = { route: page()?.route || '', view: page()?.data?.view || '', error: page()?.data?.error || '' };
    save('failed-state.json', snapshot());
    save('results.json', report);
    throw error;
  } finally {
    await runtime.disconnect();
  }
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
