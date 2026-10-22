// Finance workbench acceptance on the current protocol (sales-offline-v1 commission + engineering record flow).
// For every finance identity the rule is the same: the item shows on the person's own home → it is in "全部待办"
// → its link opens a page that person can act on → after acting the item is gone from them and (where the
// process continues) appears on the next person's home. Real Page handlers and WXML controls in the Node runtime;
// this is page integration coverage, not a native renderer or tap record.
//   needs: node scripts/verify-engineering-records.cjs (writes the engineering scenes) and the business gate batch 1
const fs = require('fs'), path = require('path'), assert = require('assert/strict');
const out = path.resolve('qa/finance-desk-acceptance-20261022');
(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account'), M = require('../miniprogram/pages/admin/model'), E = require('../miniprogram/pages/build/model'),
    R = require('../miniprogram/utils/engineering-records'), F = require('../miniprogram/utils/finance-source'), Ops = require('../miniprogram/utils/operations'),
    B = require('../miniprogram/utils/business'), T = require('../miniprogram/utils/intercompany');
  const bizScenes = JSON.parse(fs.readFileSync('qa/business-module-simplification-20261021/page-scenes.json', 'utf8'));
  const engScenes = JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json', 'utf8'));
  fs.mkdirSync(out, { recursive: true });
  const checks = [], date = A.localDate(), file = n => ({ name: n + '.pdf', sample: true });
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const copy = x => JSON.parse(JSON.stringify(x));
  const install = rows => { wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k)); for (const [k, v] of copy(rows)) wx.setStorageSync(k, v); };
  const login = (id, role) => { A.mutate(s => A.loginDemo(s, id)); if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role)); };
  const cur = () => getCurrentPages().at(-1);
  const open = url => { wx.reLaunch({ url }); assert(!cur().data.error, cur().data.error); assert(!cur().data.financeError, cur().data.financeError); return cur(); };
  const click = async selector => { const el = await (await runtime.currentPage()).$(selector); assert(el, 'Visible control ' + selector); await el.tap(); assert(!cur().data.error, cur().data.error); return cur(); };
  const submit = async (form, selector = '[data-handler="submit"]') => {
    cur().setData({ form: { ...cur().data.form, ...form } });
    await click(selector);
    if (cur().data.modal?.type === 'confirm' || cur().data.modal?.action) await click(cur().data.modal?.action ? '[data-handler="confirm"]' : '.sheet .solid');
  };
  // finance home of a person: the preview, the full list behind "全部待办" and whether the page actually opens
  const desk = (who, role) => { login(who, role); const p = open('/pages/management/index'); const d = p.data.financeDesk; assert(d, who + ' has a finance workbench'); return d; };
  const find = (d, view, test) => d.tasks.find(t => t.view === view && test(t));
  // every task of the person must be listed on the full-list page, and the home count must equal the list
  const listed = (who, role, task) => {
    const d = desk(who, role);
    assert.equal(d.taskCount, d.tasks.length, who + ' 首页待办数与全部待办不一致');
    const p = open(d.tasksUrl);
    assert(p.data.rows.some(t => t.key === task.key), who + ' 全部待办缺少 ' + task.view + ' ' + task.name);
  };
  // act on a task from the person's home: returns the page that opened
  async function act(who, role, view, test, form, selector) {
    const d = desk(who, role), task = find(d, view, test);
    assert(task, who + ' 首页没有待办 ' + view);
    listed(who, role, task);
    const p = open(task.url);
    assert.equal(p.route, task.url.slice(1).split('?')[0], '待办链接应直接打开办理页');
    assert(!p.data.error && p.data.view !== 'access', who + ' 打开 ' + view + ' 被拒绝');
    await submit(form, selector);
    assert(!cur().data.error, cur().data.error);
    const after = desk(who, role);
    assert(!after.tasks.some(t => t.key === task.key), who + ' 办完后待办 ' + view + ' 应关闭');
    return task;
  }
  const has = (who, role, view, test) => !!find(desk(who, role), view, test);

  // ---- A. design fee on the commission protocol: store pays, headquarters registers receipt, separate people review ----
  const submitScene = bizScenes.find(s => s.action === 'commission-submit');
  install(submitScene.before);
  login('sales-dual', 'sales-lead');
  require('../miniprogram/utils/sales-flow').run(submitScene.id, 'commission-submit', submitScene.input, B.customer(submitScene.id).revision);
  const customer = wx.getStorageSync(B.KEY).customers.find(c => c.id === submitScene.id);
  assert(customer, 'fixture has a submitted commission');
  const cid = customer.id, mine = t => t.id.includes(cid) || t.projectId === customer.projectId;
  const money = () => M.load();
  const raw = () => wx.getStorageSync(B.KEY).customers.find(c => c.id === cid);

  assert(!has('hq-finance', 'finance', 'incoming-register', mine), '门店未付款时总部不应有到账登记待办');
  assert(has('finance-wang', 'finance', 'pay', mine), '门店财务专员应收到首款付款待办');
  assert(has('finance-dual', 'finance-lead', 'pay', mine) || has('finance-lead', 'finance-lead', 'pay', mine), '门店财务负责人也应能办理首款');
  pass('A1 委托提交后：门店财务首页出现首款付款待办，总部尚无到账待办');

  const payTask = await act('finance-wang', 'finance', 'pay', mine, { amount: '5000', date, transaction: 'ACC-STORE-1', file: file('门店付款'), checked: true });
  const payment = money().payments.find(p => p.transaction === 'ACC-STORE-1');
  assert(payment && payment.status === '待复核', '专员登记的付款应等待独立复核：' + (payment && payment.status));
  assert(!T.condition(raw(), 'first').ready, '付款登记未复核前不应开放设计');
  assert(has('finance-lead', 'finance-lead', 'payment-review', t => t.id === payment.id), '财务负责人应收到独立复核待办');
  assert(!has('finance-wang', 'finance', 'payment-review', t => t.id === payment.id), '登记人本人不应收到自己付款的复核待办');
  pass('A2 门店专员登记付款 → 财务负责人出现独立复核待办，登记人看不到自己的复核');

  await act('finance-lead', 'finance-lead', 'payment-review', t => t.id === payment.id, { checked: true, file: file('付款复核'), note: '核对实际付款' });
  assert(has('hq-finance', 'finance', 'incoming-register', mine), '门店付款核实后总部专员应收到到账登记待办');
  assert(!has('finance-wang', 'finance', 'payment-review', t => t.id === payment.id) && !has('finance-lead', 'finance-lead', 'payment-review', t => t.id === payment.id));
  pass('A3 门店负责人复核完成 → 总部专员出现到账登记待办，门店待办全部关闭');

  const receipt = await act('hq-finance', 'finance', 'incoming-register', mine, { amount: '5000', date, transaction: 'ACC-HQ-1', file: file('总部到账'), checked: true, note: '核实总部银行实际到账' });
  assert(has('hq-finance-lead', 'finance-lead', 'reconcile', mine), '总部负责人应收到独立核算待办');
  assert(!has('hq-finance', 'finance', 'reconcile', mine), '登记人本人不应核算自己登记的到账');
  pass('A4 总部专员登记到账 → 总部负责人出现独立核算待办，登记人不能自核');

  await act('hq-finance-lead', 'finance-lead', 'reconcile', mine, { checked: true });
  assert(!has('hq-finance-lead', 'finance-lead', 'reconcile', mine) && !has('hq-finance', 'finance', 'incoming-register', mine));
  assert(T.condition(raw(), 'first').ready, '双方独立复核后首款应足额开放设计');
  login('hq-design-admin');
  const adminPage = open('/pages/business/index');
  assert((adminPage.data.tasks || []).some(t => t.customerId === cid), '首款核实后设计行政应收到设计完成待办');
  pass('A5 总部负责人核算完成 → 首款足额，设计行政出现设计完成待办');

  // ---- B. boss approval of a payable, then payment (engineering material purchase) ----
  const terms = engScenes.filter(s => s.action === 'material-terms')[0];
  install(terms.before);
  const eng = wx.getStorageSync(E.KEY).projects.find(p => p.recordFlow), eid = eng.id, pid = eng.projectId, planId = new URL(terms.url, 'http://l').searchParams.get('planId');
  assert(planId, 'material-terms scene carries its plan');
  const termsTask = (d) => find(d, 'engineering-material', t => t.id === 'material-terms:' + planId);
  assert(termsTask(desk('finance-wang', 'finance')), '到货后财务专员应收到“记录采购金额”待办');
  assert(termsTask(desk('finance-lead', 'finance-lead')), '财务负责人同样可见');
  assert(!termsTask(desk('hq-finance', 'finance')), '总部财务看不到加盟商工程材料待办');
  login('finance-wang', 'finance');
  const planNow = R.plans(R.get(eid)).find(p => p.id === planId);
  const page = open(termsTask(desk('finance-wang', 'finance')).url);
  assert.equal(page.route, 'pages/build/record/index');
  page.setData({ form: { ...page.data.form, termsFile: file('采购依据'), lines: planNow.lines.map((l, i) => ({ id: l.id, supplier: i ? '砂供应方' : '水泥供应方', unitPrice: i ? '100' : '30', freight: '0' })) } });
  await click('[data-action="material-terms"]');
  assert(!termsTask(desk('finance-wang', 'finance')), '记录采购金额后该待办应关闭');
  pass('B1 材料到货 → 财务专员记录采购金额 → 待办关闭（总部看不到加盟商工程待办）');

  const lines = R.plans(R.get(eid)).find(p => p.id === planId).lines;
  assert(lines.length >= 2);
  const payableId = l => 'engineering-payable:' + eid + ':' + l.supplyId;
  login('finance-wang', 'finance');
  const before = desk('finance-wang', 'finance');
  assert(!before.tasks.some(t => t.view === 'pay' && t.id === payableId(lines[0])), '老板批准前财务不应有该笔付款待办');
  login('boss');
  const approvals = open('/pages/admin/home/index').data.desk.approvals;
  const approval = approvals.find(t => t.url.includes(encodeURIComponent(payableId(lines[0]))));
  assert(approval, '老板首页应有材料采购付款审批');
  const approvePage = open(approval.url);
  assert.equal(approvePage.data.view, 'pay-approval');
  await submit({ checked: true, note: '核对实际金额和原凭据' });
  assert(!open('/pages/admin/home/index').data.desk.approvals.some(t => t.url.includes(encodeURIComponent(payableId(lines[0])))), '批准后老板待办关闭');
  pass('B2 老板首页出现材料付款审批 → 批准 → 审批待办关闭');

  const q = money().payables.find(q => q.id === payableId(lines[0])) || M.load().payables.find(q => q.id === payableId(lines[0]));
  assert(q, 'payable exists');
  await act('finance-wang', 'finance', 'pay', t => t.id === q.id, { amount: String(q.total / 100), date, transaction: 'ACC-PAY-1', file: file('供应商付款'), checked: true });
  const supplierPayment = money().payments.find(p => p.transaction === 'ACC-PAY-1');
  assert(supplierPayment && supplierPayment.status === '待复核', '专员登记的供应商付款应待独立复核');
  assert.equal(F.paid(M.load(), q.id), 0, '未复核前不计入已付');
  assert(!has('finance-wang', 'finance', 'pay', t => t.id === q.id) || F.paid(M.load(), q.id) < q.total);
  await act('finance-lead', 'finance-lead', 'payment-review', t => t.id === supplierPayment.id, { checked: true, file: file('付款复核'), note: '核对供应商实际收款' });
  assert.equal(F.paid(M.load(), q.id), q.total, '复核后已付金额等于应付');
  assert(!has('finance-wang', 'finance', 'pay', t => t.id === q.id) && !has('finance-lead', 'finance-lead', 'pay', t => t.id === q.id), '付清后付款待办关闭');
  pass('B3 批准后财务出现付款待办 → 专员登记付款 → 负责人独立复核 → 已付金额等于应付，待办全部关闭');

  // ---- C. delivered project: cost check, then financial closing (store finance lead) ----
  const done = engScenes.at(-1).after;
  install(done);
  const eng2 = wx.getStorageSync(E.KEY).projects.find(p => p.recordFlow), pid2 = eng2.projectId;
  const closing = (view) => has('finance-lead', 'finance-lead', view, t => t.id === pid2);
  assert(closing('cost-confirm'), '归档后财务负责人应有“核对项目成本”待办');
  assert(!closing('project-close'));
  assert(!has('finance-wang', 'finance', 'cost-confirm', t => t.id === pid2), '专员不承担成本核对');
  const costs = Ops.categories.reduce((m, k) => ({ ...m, [k]: '0' }), {});
  login('finance-lead', 'finance-lead');
  const costTask = find(desk('finance-lead', 'finance-lead'), 'cost-confirm', t => t.id === pid2);
  listed('finance-lead', 'finance-lead', costTask);
  const costPage = open(costTask.url);
  assert.equal(costPage.data.view, 'costs');
  costPage.setData({ form: { ...costPage.data.form, checked: true, file: file('成本核对'), remaining: costs } });
  await click('[data-action="cost-confirm"]');
  assert(!closing('cost-confirm') && closing('project-close'), '成本核对后应转为“确认财务结清”待办');
  pass('C1 工程归档 → 财务负责人出现核对成本待办（专员没有）→ 核对后转为确认结清待办');

  const closeTask = find(desk('finance-lead', 'finance-lead'), 'project-close', t => t.id === pid2);
  listed('finance-lead', 'finance-lead', closeTask);
  const closePage = open(closeTask.url);
  assert.equal(closePage.data.view, 'settlement');
  closePage.setData({ form: { ...closePage.data.form, checked: true, file: file('结清依据'), note: '收付款、成本和往来核对完毕' } });
  await click('[data-action="project-close"]');
  assert(Ops.settlement(M.load(), pid2).closed, '项目应已财务结清');
  assert(!closing('project-close') && !closing('cost-confirm'), '结清后收尾待办应关闭');
  pass('C2 确认结清 → 项目财务结清，收尾待办关闭');

  // a new expense after closing re-opens the closing steps instead of leaving a stale "closed"
  login('finance-wang', 'finance');
  const reopen = open('/pages/admin/operations/index?view=expenses&id=' + encodeURIComponent(pid2));
  for (const [k, v] of Object.entries({ name: '结清后新增费用', amount: '100', recipient: '施工班组', reference: 'ACC-LATE-1', note: '结清后补记' })) reopen.setData({ form: { ...reopen.data.form, [k]: v } });
  reopen.setData({ form: { ...reopen.data.form, due: date, file: file('费用凭据'), checked: true } });
  await click('[data-action="expense-submit"]');
  assert(!Ops.settlement(M.load(), pid2).closed, '结清后新费用应使结清失效');
  const blockers = Ops.settlement(M.load(), pid2).blockers.map(b => b.key);
  assert(blockers.includes('cost') && closing('cost-confirm'), '新增费用后成本待重新核对，财务负责人应重新收到核对成本待办');
  assert(!closing('project-close'), '失效后不应仍提示确认结清');
  pass('C3 结清后新增费用 → 结清失效，退回为“核对项目成本”待办');

  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'passed', scope: 'Node Page/WXML real handlers; not native tap evidence', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
  process.exit(0);
})().catch(e => { console.error(e.stack); process.exit(1); });
