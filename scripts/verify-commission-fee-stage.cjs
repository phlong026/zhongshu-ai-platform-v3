const assert = require('assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const SF = require('../miniprogram/utils/sales-flow');
  const Approval = require('../miniprogram/utils/commercial-approval');
  const M = require('../miniprogram/pages/admin/model');
  const F = require('../miniprogram/utils/finance-source');
  const T = require('../miniprogram/utils/intercompany');

  const checks = [];
  const page = () => getCurrentPages().at(-1);
  const today = A.localDate();
  const file = name => ({ name: name + '.pdf', sample: true });
  const login = (id, role) => {
    A.mutate(state => A.loginDemo(state, id));
    if (role) A.mutate(state => A.switchIdentity(state, state.session.companyId, role));
  };
  const open = url => {
    wx.reLaunch({ url });
    assert(!page().data.error, page().data.error);
    return page();
  };
  const tap = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '页面缺少可点击控件 ' + selector);
    await element.tap();
    assert(!page().data.error, page().data.error);
  };
  const check = (name, verify) => {
    try {
      verify();
      checks.push({ name, passed: true });
      console.log('PASS ' + name);
    } catch (error) {
      checks.push({ name, passed: false, error: error.message });
      console.error('FAIL ' + name + ': ' + error.message);
    }
  };
  const visibleText = value => {
    if (value == null) return '';
    if (Array.isArray(value)) return value.map(visibleText).join(' ');
    if (typeof value !== 'object') return String(value);
    return Object.entries(value)
      .filter(([key]) => key !== 'file')
      .map(([, child]) => visibleText(child))
      .join(' ');
  };

  login('sales-dual', 'sales-lead');
  require('../miniprogram/utils/sales-demo').ensure();
  const management = wx.getStorageSync(M.KEY);
  for (const book of [management, ...Object.values(management.books || {})]) {
    for (const key of ['receipts', 'payables', 'payments', 'refunds', 'events']) book[key] = [];
    book.drafts = {};
    book.revision = 0;
  }
  wx.setStorageSync(M.KEY, management);
  wx.setStorageSync(B.KEY, { version: 1, customers: [] });

  const customer = B.add({
    name: '费用阶段回归客户',
    phone: '13900008873',
    source: '到店咨询',
    city: '武汉市',
    summary: '两层住宅，需要全案深化设计'
  });
  const id = customer.id;
  const raw = () => wx.getStorageSync(B.KEY).customers.find(row => row.id === id);
  const snapshot = () => wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]);
  const install = rows => {
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of B.clone(rows)) wx.setStorageSync(key, value);
  };
  B.write(id, current => {
    current.budgets.push({
      version: 1,
      area: 400,
      total: 480000,
      parameters: { width: 12.5, depth: 16, floors: 2, area: 400 },
      date: today
    });
  }, '保存费用阶段回归预算', 'budget');

  open(SF.url(id, 'submit'));
  page().setData({
    form: {
      ...page().data.form,
      text: '已有宅基地，需要全案深化设计并交付施工图',
      scope: '全案深化设计及施工图',
      total: '12000',
      first: '4000',
      recipient: '众墅之家总部',
      checked: true
    }
  });
  await tap('.commission-actions button[bindtap="submit"]');
  assert(SF.is(raw()), '委托提交后未生成业务原单');
  assert.equal(raw().serviceOrders.at(-1).tail, 8000);

  login('boss');
  const approvalTasks = F.tasks(M.load()).filter(task => task.view === 'money-review' && task.id === id && task.record === 'service');
  assert.equal(approvalTasks.length, 1, '同一设计委托应只生成一张老板费用审批单');
  open('/pages/workflow/index?view=money-review&id=' + encodeURIComponent(id) + '&record=service');
  const approvalText = visibleText(page().data.sections);
  check('老板在同一审批单核对总额、首款、自动尾款、范围和收款方', () => {
    assert.match(approvalText, /设计总金额[^]*12,000/);
    assert.match(approvalText, /首付款金额[^]*4,000/);
    assert.match(approvalText, /设计尾款金额[^]*8,000/);
    assert.match(approvalText, /交付范围[^]*全案深化设计及施工图/);
    assert.match(approvalText, /实际收款方[^]*众墅之家总部/);
  });
  page().setData({ form: { ...page().data.form, moneyDecision: '批准', moneyChecked: true, moneyNote: '总额、首款、尾款、范围和收款方一并批准' } });
  await tap('.primary-action');
  assert(SF.feeApproved(raw()), '老板批准后费用授权未生效');
  assert.equal(raw().moneyApprovals.filter(row => row.kind === 'service').length, 1, '老板一次批准不得拆成首尾款两次审批');
  const approvedSnapshot = snapshot();

  const pay = (stage, amount, transaction) => {
    login('finance-dual', 'finance');
    const ledger = M.load();
    const payable = ledger.payables.find(row => row.source?.id === id && row.source.stage === stage);
    assert(payable, '缺少' + stage + '付款原单');
    M.mutate(current => M.apply(current, 'pay', payable.id, { amount: String(amount), date: today, transaction, file: file(transaction), checked: true }));
  };
  const receive = (stage, amount, transaction) => {
    login('hq-finance', 'finance');
    const ledger = M.load();
    const receipt = ledger.receipts.find(row => row.status === '待登记' && row.source?.protocol === 'sales-offline-v1' && row.source.customerId === id && row.source.stage === stage);
    assert(receipt, '总部缺少' + stage + '实际到账待办');
    M.mutate(current => M.apply(current, 'incoming-register', receipt.id, {
      amount: String(amount),
      date: today,
      transaction,
      file: file(transaction),
      checked: true,
      note: '核对总部银行实际到账',
      expectedSource: JSON.stringify(receipt.source),
      expectedAmount: String(receipt.amount)
    }));
  };

  let state;
  login('finance-dual', 'finance');
  state = M.load();
  const firstPayable = state.payables.find(row => row.source?.id === id && row.source.stage === 'first');
  assert(firstPayable?.approvalValid, '一次老板审批后首款应直接进入财务登记');
  assert(F.tasks(state).some(task => task.view === 'pay' && task.id === firstPayable.id), '门店财务缺少首款登记待办');

  login('hq-design-admin');
  check('首款未登记时设计行政没有设计完成待办', () => {
    assert(!SF.tasks().some(task => task.customerId === id && task.action === '设计完成'));
  });
  check('首款未登记时模型拒绝越级登记设计完成', () => {
    assert.throws(
      () => SF.transition(raw(), 'design-complete', { checked: true, note: '不得越级完成' }),
      /首款|付款/
    );
  });

  pay('first', 2000, 'FEE-STAGE-FIRST-STORE-1');
  login('hq-design-admin');
  check('首款只登记一部分时仍不能开始设计', () => {
    assert.equal(T.condition(raw(), 'first').paid, 200000);
    assert(!SF.tasks().some(task => task.customerId === id && task.action === '设计完成'));
    assert.throws(() => SF.transition(raw(), 'design-complete', { checked: true }), /首款|付款/);
  });
  pay('first', 2000, 'FEE-STAGE-FIRST-STORE-2');
  login('hq-design-admin');
  check('门店足额登记首款后立即开放设计且总部收到首款到账待办', () => {
    assert(SF.tasks().some(task => task.customerId === id && task.action === '设计完成'));
    assert.equal(T.condition(raw(), 'first').paid, 400000);
    assert(!T.condition(raw(), 'first').ready, '总部尚未确认前不应记为双方到账完成');
    login('hq-finance', 'finance');
    assert(M.load().receipts.some(row => row.status === '待登记' && row.source?.customerId === id && row.source.stage === 'first'));
  });
  login('hq-design-admin');
  SF.run(id, 'design-complete', { checked: true, note: '设计成果及内部审核完成' }, B.customer(id).revision);
  open(SF.url(id, 'progress'));
  check('设计完成后的主状态进入设计尾款，首款欠款仍单独提示', () => {
    assert.match(page().data.rows[0].value, /设计尾款/);
    assert.match(SF.next(raw()).detail, /首款.*(?:待|未|不足|差额|核收|确认)/);
  });

  login('finance-dual', 'finance');
  state = M.load();
  const tailPayable = state.payables.find(row => row.source?.id === id && row.source.stage === 'tail');
  check('尾款财务待办继承同一老板审批且不产生第二次金额审批', () => {
    assert(tailPayable?.approvalValid, '尾款未继承原设计委托审批');
    assert.equal(tailPayable.approval.sourceId, firstPayable.approval.sourceId);
    assert(F.tasks(state).some(task => task.view === 'pay' && task.id === tailPayable.id), '门店财务缺少尾款登记待办');
    assert.equal(Approval.tasks(raw()).filter(task => task.record === 'service').length, 0);
    assert.equal(raw().moneyApprovals.filter(row => row.kind === 'service').length, 1);
  });

  pay('tail', 8000, 'FEE-STAGE-TAIL-STORE');
  receive('tail', 8000, 'FEE-STAGE-TAIL-HQ');
  check('尾款已到账但首款未获总部确认时仍不可下发正式成果', () => {
    assert(T.condition(raw(), 'tail').ready);
    assert(!T.condition(raw(), 'first').ready);
    assert.match(SF.next(raw()).state, /设计尾款/);
    login('hq-design-admin');
    assert.throws(
      () => SF.run(id, 'design-dispatch', { checked: true, recipient: '经办业务', note: '不得提前下发' }, B.customer(id).revision),
      /首尾款|到账|财务/
    );
  });

  receive('first', 2000, 'FEE-STAGE-FIRST-HQ-1');
  receive('first', 2000, 'FEE-STAGE-FIRST-HQ-2');
  login('hq-design-admin');
  assert(T.condition(raw(), 'first').ready && T.condition(raw(), 'tail').ready, '首尾款应均已足额到账');
  SF.run(id, 'design-dispatch', { checked: true, recipient: '经办业务', note: '首尾款均到账后下发正式成果' }, B.customer(id).revision);
  check('首尾款均到账后可正式下发', () => assert(raw().businessFlow.dispatch));

  install(approvedSnapshot);
  const historical = wx.getStorageSync(B.KEY);
  const historicalCustomer = historical.customers.find(row => row.id === id);
  historicalCustomer.businessFlow.completed = {
    date: today,
    at: new Date().toISOString(),
    by: '历史设计行政',
    actorId: 'hq-design-admin',
    personId: 'person-hq-design-admin',
    note: '历史版本已完成设计',
    requestVersion: historicalCustomer.request.version,
    feedbackId: null
  };
  wx.setStorageSync(B.KEY, historical);
  login('finance-dual', 'finance');
  check('历史已完成但漏登首款时保留完成事实并阻止尾款登记', () => {
    assert(raw().businessFlow.completed, '历史设计完成事实被删除');
    assert.match(SF.next(raw()).state + ' ' + SF.next(raw()).detail, /首款.*(?:补|待|未|付款)/);
    const ledger = M.load();
    assert(!F.tasks(ledger).some(task => task.view === 'pay' && ledger.payables.find(row => row.id === task.id)?.source?.stage === 'tail'));
  });
  pay('first', 4000, 'FEE-STAGE-HISTORY-FIRST');
  login('finance-dual', 'finance');
  check('历史单补登首款后开放尾款且不重复设计完成', () => {
    const ledger = M.load();
    const tail = ledger.payables.find(row => row.source?.id === id && row.source.stage === 'tail');
    assert(tail?.approvalValid);
    assert(F.tasks(ledger).some(task => task.view === 'pay' && task.id === tail.id));
    assert(raw().businessFlow.completed, '补登首款不应改写历史设计完成事实');
  });

  const failed = checks.filter(row => !row.passed);
  if (failed.length) {
    throw new Error('费用阶段回归失败 ' + failed.length + ' 项：' + failed.map(row => row.name + '（' + row.error + '）').join('；'));
  }
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
