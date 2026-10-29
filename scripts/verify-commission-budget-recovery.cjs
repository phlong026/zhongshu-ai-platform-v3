const assert = require('node:assert/strict');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const Checkout = require('../miniprogram/utils/design-checkout');
  const SalesFlow = require('../miniprogram/utils/sales-flow');

  const page = () => getCurrentPages().at(-1);
  const current = () => runtime.currentPage();
  const open = url => wx.reLaunch({ url });
  const element = async (selector, message = selector) => {
    const control = await (await current()).$(selector);
    assert(control, 'Visible control missing: ' + message);
    return control;
  };
  const input = async (key, value, tag = 'input') => {
    await (await element(`${tag}[data-key="${key}"]`, key)).input(value);
  };
  const rawCustomer = id => (wx.getStorageSync(Business.KEY).customers || []).find(row => row.id === id);
  const login = (id, role) => {
    Account.mutate(state => Account.loginDemo(state, id));
    if (role) Account.mutate(state => Account.switchIdentity(state, state.session.companyId, role));
  };

  for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
  login('hq-design-admin');
  Checkout.savePricing({
    rate: '20',
    scope: '平面、立面及施工图',
    recipient: '总部实际收款方',
    active: true
  });
  login('sales-dual', 'sales-lead');

  const customer = Business.add({
    name: '陈先生·预算恢复回归',
    phone: '13900009971',
    source: '到店咨询',
    city: '武汉市',
    summary: '两层乡墅，一层老人房，需要完整施工图。'
  });
  const submitted = { text: '两层乡墅，一层老人房，客餐厅通透' };

  assert.throws(
    () => SalesFlow.run(customer.id, 'commission-submit', {
      ...submitted,
      scope: '平面、立面及施工图',
      paymentMode: Checkout.MODE,
      checked: true,
      __budgetVersion: 0,
      __pricingVersion: 1
    }),
    /预算/,
    '无预算不得绕过页面直接制造设计委托'
  );
  assert.equal(rawCustomer(customer.id).request, null);
  assert.equal((rawCustomer(customer.id).serviceOrders || []).length, 0);

  open(SalesFlow.url(customer.id, 'submit'));
  assert.equal(page().route, 'pages/workflow/commission/index');
  await input('text', submitted.text, 'textarea');
  await (await element('button[data-key="attachment"]', '本项目模拟需求附件')).tap();
  await (await element('checkbox-group[data-key="checked"]', '需求核对勾选')).trigger('change', { value: ['yes'] });
  assert.equal(page().data.budgetReady, false);
  const evidence = path.join(os.tmpdir(), 'commission-budget-recovery-before.json');
  await runtime.screenshot({ path: evidence });
  const recovery = await (await current()).$('button[bindtap="completeBudget"]');
  assert(recovery, '预算缺失时必须显示可点击的“保留资料并补齐预算”入口；回归截图：' + evidence);
  assert.match(await recovery.text(), /补齐预算/, '恢复入口必须说明下一步');

  await recovery.tap();
  assert.equal(page().route, 'pages/workflow/index');
  assert.equal(page().data.view, 'budget');
  const storedDraft = rawCustomer(customer.id).drafts[Business.draftKey('offline:submit')];
  for (const [key, value] of Object.entries(submitted)) assert.equal(storedDraft[key], value, '补预算前保留 ' + key);
  assert(storedDraft.attachment?.sample, '模拟需求附件必须随委托草稿保留');
  assert.equal(storedDraft.paymentMode, Checkout.MODE, '草稿保留面积计价链路');

  for (const legacy of ['request', 'delivery', 'hub']) {
    open(`/pages/workflow/index?view=${legacy}&id=${customer.id}`);
    assert.equal(page().route, 'pages/workflow/commission/index');
    assert.equal(page().data.view, 'submit');
    assert.equal(page().data.form.text, submitted.text);
    assert(await (await current()).$('button[bindtap="completeBudget"]'), `${legacy} 旧入口应汇入同一预算修复页`);
  }

  await (await element('button[bindtap="completeBudget"]', '先完成参考预算')).tap();
  assert.equal(page().route, 'pages/workflow/index');
  assert.equal(page().data.view, 'budget');

  await input('footprint', '200');
  await input('area', '400');
  await input('roofArea', '220');
  if (!page().data.form.budgetChecked) await (await element('button[data-key="budgetChecked"]', '人工核定预算')).tap();
  await (await element('button[bindtap="submit"]', '开始测算')).tap();
  assert.equal(page().data.view, 'budget-result');
  assert.equal(rawCustomer(customer.id).budgets.length, 1);
  await (await element('.budget-next', '预算结果继续发起委托')).tap();

  assert.equal(page().route, 'pages/workflow/commission/index');
  assert.equal(page().data.view, 'submit');
  for (const [key, value] of Object.entries(submitted)) assert.equal(page().data.form[key], value, '补预算返回后保留 ' + key);
  assert(page().data.form.attachment?.sample, '补预算返回后保留需求附件');
  assert.equal(page().data.form.scope, Checkout.pricing().scope, '交付范围由总部面积计价规则生成');
  assert.equal(page().data.form.paymentMode, Checkout.MODE);
  assert(page().data.pricingQuote, '补齐预算后必须生成面积计价报价');
  assert.equal(page().data.form.checked, false, '新预算生成后必须重新人工勾选');
  assert.equal(page().data.form.__budgetVersion, 1);
  assert.deepEqual(page().data.budgetBasis, { kind: 'budget', version: 1, width: 12.5, depth: 16, floors: 2, area: 400 });
  const afterBudget = path.join(os.tmpdir(), 'commission-budget-recovery-after-v1.json');
  await runtime.screenshot({ path: afterBudget });
  const rendered = require(afterBudget);
  for (const expected of ['2层', '400㎡', '20.00', '8,000.00']) {
    assert(rendered.renderedText.includes(expected), '面积计价卡未展示 ' + expected);
  }

  const v1Form = { ...page().data.form, checked: true };
  Business.write(customer.id, row => {
    row.budgets.push({
      version: 2,
      area: 400,
      total: row.budgets.at(-1).total,
      parameters: { ...row.budgets.at(-1).parameters, width: '12.5', depth: '16', floors: '2', area: '400' },
      date: Business.date()
    });
  }, '预算参数变更', 'budget');
  assert.throws(
    () => SalesFlow.run(customer.id, 'commission-submit', v1Form),
    /预算.*更新|更新.*预算/,
    '旧 __budgetVersion 不得提交'
  );
  page().onShow();
  for (const [key, value] of Object.entries(submitted)) assert.equal(page().data.form[key], value, '预算变更刷新后保留 ' + key);
  assert.equal(page().data.form.scope, Checkout.pricing().scope);
  assert.equal(page().data.form.checked, false, '同页发现预算变更后撤销旧勾选');
  assert.equal(page().data.form.__budgetVersion, 2, '同页刷新当前预算版本');

  const currentForm = { ...page().data.form, checked: true };
  assert.throws(() => SalesFlow.run(customer.id, 'commission-submit', { ...currentForm, text: '' }), /设计需求/);
  assert.throws(() => SalesFlow.run(customer.id, 'commission-submit', { ...currentForm, scope: '' }), /本次交付范围/);
  assert.throws(() => SalesFlow.run(customer.id, 'commission-submit', { ...currentForm, checked: false }), /勾选|核对/);

  await (await element('checkbox-group[data-key="checked"]', '重新核对当前预算')).trigger('change', { value: ['yes'] });
  await (await element('button[bindtap="submit"]', '提交设计委托')).tap();
  assert.equal(page().data.error, '');
  const commissioned = rawCustomer(customer.id);
  assert.equal(commissioned.request.budgetVersion, 2);
  assert.equal(commissioned.request.attachment.name, storedDraft.attachment.name);
  assert(Checkout.is(commissioned), '委托应锁定面积一次性计价链路');
  assert(Checkout.authorized(commissioned), '委托应锁定当前预算、计价版本和服务版本');
  const order = Checkout.order(commissioned);
  assert.deepEqual(
    { area: order.area, rateCents: order.rateCents, amountCents: order.amountCents, status: order.status, simulated: order.simulated },
    { area: 400, rateCents: 2000, amountCents: 800000, status: 'pending', simulated: true }
  );
  assert.equal(commissioned.businessFlow.authorization.kind, 'area-pricing');
  assert.deepEqual(
    { total: commissioned.serviceOrders.at(-1).total, first: commissioned.serviceOrders.at(-1).first, tail: commissioned.serviceOrders.at(-1).tail },
    { total: 8000, first: 0, tail: 0 }
  );
  assert.equal(page().data.view, 'pay', '委托提交后进入一次性模拟支付');

  login('boss');
  open('/pages/admin/home/index');
  assert(!page().ownerSnapshot.approvals.some(task => task.url.includes(encodeURIComponent(customer.id))), '面积计价不应重复产生老板费用待办');
  const payments = wx.getStorageSync('zhongshu-management-v1').payments || [];
  assert(!payments.some(payment => payment.projectId === customer.projectId || payment.source?.id === customer.id), '回归不得伪造本项目实际付款');

  console.log('PASS 设计委托缺预算可补齐：草稿保留、版本失效、面积计价及后续一次性模拟支付');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
