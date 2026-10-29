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
    const remaining=after.tasks.find(t=>t.key===task.key);if(remaining){assert.equal(view,'pay');const q=M.load().payables.find(q=>q.id===task.id);assert(q&&F.paid(M.load(),q.id)<q.total,'仅部分付款可保留原待办');}
    return task;
  }
  const has = (who, role, view, test) => !!find(desk(who, role), view, test);

  // ---- A. design fee on the commission protocol: store records payment, headquarters confirms actual receipt ----
  const submitScene = bizScenes.find(s => s.action === 'commission-submit');
  install(submitScene.before);
  login('sales-dual', 'sales-lead');
  require('../miniprogram/utils/sales-flow').run(submitScene.id, 'commission-submit', submitScene.input, B.customer(submitScene.id).revision);
  const customer = wx.getStorageSync(B.KEY).customers.find(c => c.id === submitScene.id);
  assert(customer, 'fixture has a submitted commission');
  const cid = customer.id, mine = t => t.id.includes(cid) || t.projectId === customer.projectId;
  const money = () => M.load();
  const raw = () => wx.getStorageSync(B.KEY).customers.find(c => c.id === cid);

  assert(has('finance-wang', 'finance', 'pay', mine));
  login('hq-finance', 'finance');
  const D = require('../miniprogram/utils/finance-desk'), copies = D.services(M.load()).filter(r => r.customerId === cid);
  assert.equal(copies.length, 2, '费用授权后首尾款都抄送总部，不等待加盟商付款');
  for (const stage of ['first','tail']) { const row = D.statement(M.load(),cid,stage); assert.equal(row.primary,null); assert(row.approved); }
  assert(!desk('hq-finance','finance').tasks.some(t => t.view === 'incoming-register' && mine(t)));
  pass('A1 老板费用授权后首尾款自动抄送总部；加盟商尚未付款时不伪造到账待办');

  login('hq-design-admin');
  assert(!open('/pages/business/index').data.tasks.some(t => t.customerId === cid));
  assert.equal(require('../miniprogram/utils/sales-flow').next(raw()).waitingFirst,true);
  pass('A2 老板费用授权后仍需门店足额登记首款，行政不提前开始设计');

  async function receive(stage, amount, transaction) {
    login('hq-finance','finance');
    const state=M.load(),receipt=state.receipts.find(r=>r.status==='待登记'&&r.source?.module==='intercompany'&&r.source.protocol==='sales-offline-v1'&&r.source.customerId===cid&&r.source.stage===stage);
    assert(receipt,'总部应收到'+(stage==='first'?'首款':'尾款')+'实际到账登记待办');
    await act('hq-finance','finance','incoming-register',t=>t.id===receipt.id,{amount:String(amount),date,transaction,file:file(transaction),checked:true,note:'核对总部银行实际到账',expectedSource:JSON.stringify(receipt.source),expectedAmount:String(receipt.amount)});
    const saved=M.load().receipts.find(r=>r.id===receipt.id);assert(saved&&saved.status==='已核算'&&saved.registrationMode==='record-only-v1'&&!saved.reviewerId);
    return saved;
  }

  await act('finance-wang','finance','pay',mine,{amount:'2000',date,transaction:'ACC-STORE-1',file:file('付款1'),checked:true});
  let payment=money().payments.find(p=>p.transaction==='ACC-STORE-1');
  assert.equal(payment.status,'已核实');assert.equal(payment.registrationMode,'record-only-v1');assert(!payment.reviewerId);
  assert.equal(T.condition(raw(),'first').paid,200000);assert(!T.condition(raw(),'first').ready);
  assert(has('finance-wang','finance','pay',mine),'部分登记后剩余款项仍可登记');
  assert(!has('finance-lead','finance-lead','payment-review',mine));
  assert(has('hq-finance','finance','incoming-register',mine),'加盟商付款后总部应出现到账确认待办');
  await receive('first',2000,'ACC-HQ-FIRST-1');
  assert.equal(T.condition(raw(),'first').received,200000);assert(!T.condition(raw(),'first').ready);
  login('hq-design-admin');assert(!open('/pages/business/index').data.tasks.some(t=>t.customerId===cid));
  pass('A3 普通财务部分登记直接计入已付，总部确认部分实收后仍等待剩余首款且不开放设计');

  login('finance-wang','finance');
  let firstPayable=money().payables.find(q=>q.source?.id===cid&&q.source.stage==='first');
  const beforeRejected=JSON.stringify(wx.getStorageSync(M.KEY));
  assert.throws(()=>M.mutate(s=>M.apply(s,'pay',firstPayable.id,{amount:'3001',date,transaction:'ACC-OVER',file:file('超额'),checked:true})),/超过/);
  assert.throws(()=>M.mutate(s=>M.apply(s,'pay',firstPayable.id,{amount:'100',date,transaction:'ACC-STORE-1',file:file('重复'),checked:true})),/重复/);
  assert.equal(JSON.stringify(wx.getStorageSync(M.KEY)),beforeRejected);
  pass('A4 超额付款及重复流水被拒绝，不改写账簿或原业务');

  await act('finance-wang','finance','pay',mine,{amount:'3000',date,transaction:'ACC-STORE-2',file:file('付款2'),checked:true});
  assert(!T.condition(raw(),'first').ready);assert(!has('finance-lead','finance-lead','payment-review',mine));
  login('hq-design-admin');assert(open('/pages/business/index').data.tasks.some(t=>t.customerId===cid));
  login('hq-finance','finance');assert.equal(D.statement(M.load(),cid,'first').paid,500000);
  assert(has('hq-finance','finance','incoming-register',mine));
  await receive('first',3000,'ACC-HQ-FIRST-2');
  assert.equal(T.condition(raw(),'first').received,500000);assert(T.condition(raw(),'first').ready);
  pass('A5 门店足额登记首款即开放设计；总部足额确认后首款到账条件成立');

  login('hq-design-admin');require('../miniprogram/utils/sales-flow').run(cid,'design-complete',{checked:true});
  assert(has('finance-wang','finance','pay',t=>mine(t)&&t.id.endsWith(':tail')));
  login('hq-design-admin');
  const tailWaiting=require('../miniprogram/utils/sales-flow').next(raw());
  assert.equal(tailWaiting.state,'设计尾款待门店付款');assert.equal(tailWaiting.owner,'门店财务');assert.match(tailWaiting.detail,/差额 ¥5,000\.00/);
  assert.throws(()=>require('../miniprogram/utils/sales-flow').run(cid,'design-dispatch',{checked:true,recipient:'业务',note:'尾款未付'}),/到账|财务/);
  await act('finance-wang','finance','pay',t=>mine(t)&&t.id.endsWith(':tail'),{amount:'5000',date,transaction:'ACC-TAIL',file:file('尾款'),checked:true});
  assert(!T.condition(raw(),'tail').ready);assert.throws(()=>{login('hq-design-admin');require('../miniprogram/utils/sales-flow').run(cid,'design-dispatch',{checked:true,recipient:'业务',note:'仅门店登记尾款'});},/到账|财务/);
  await receive('tail',5000,'ACC-HQ-TAIL');assert(T.condition(raw(),'tail').ready);
  login('hq-design-admin');require('../miniprogram/utils/sales-flow').run(cid,'design-dispatch',{checked:true,recipient:'业务',note:'总部确认尾款足额到账后已下发'});
  login('sales-dual','sales-lead');assert(require('../miniprogram/utils/sales-flow').tasks().some(t=>t.customerId===cid&&t.action==='收图并移交'));
  pass('A6 未付款或仅门店登记时禁止下发；总部足额到账确认后行政下发接续业务移交');

  // ---- B. boss approval of a payable, then payment (engineering material purchase) ----
  const terms = engScenes.filter(s => s.action === 'material-terms')[0];
  install(terms.before);
  const eng = wx.getStorageSync(E.KEY).projects.find(p => p.recordFlow), eid = eng.id, pid = eng.projectId, planId = new URL(terms.url, 'http://l').searchParams.get('planId');
  assert(planId, 'material-terms scene carries its plan');
  const termsTask = (d) => find(d, 'pay', t => t.id === 'material-terms:' + planId);
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
  assert(before.tasks.some(t => t.view === 'pay' && t.id === payableId(lines[0])), '免审后财务直接出现付款待办');
  login('boss');
  const approvals = open('/pages/admin/home/index').ownerSnapshot.approvals;
  assert(!approvals.some(t => t.url.includes(encodeURIComponent(payableId(lines[0])))), '免审后老板首页无材料付款逐笔审批');
  pass('B2 付款免审：应付生成即进财务待办，老板无逐笔审批');

  const q = money().payables.find(q => q.id === payableId(lines[0])) || M.load().payables.find(q => q.id === payableId(lines[0]));
  assert(q, 'payable exists');
  await act('finance-wang', 'finance', 'pay', t => t.id === q.id, { amount: String(q.total / 100), date, transaction: 'ACC-PAY-1', file: file('供应商付款'), checked: true });
  const supplierPayment = money().payments.find(p => p.transaction === 'ACC-PAY-1');
  assert(supplierPayment&&supplierPayment.status==='已核实'&&supplierPayment.registrationMode==='record-only-v1');
  assert.equal(F.paid(M.load(),q.id),q.total,'登记完成直接计入已付');
  assert(!has('finance-wang', 'finance', 'pay', t => t.id === q.id) || F.paid(M.load(), q.id) < q.total);
  assert(!has('finance-lead','finance-lead','payment-review',t=>t.id===supplierPayment.id));
  assert.equal(F.paid(M.load(), q.id), q.total, '复核后已付金额等于应付');
  assert(!has('finance-wang', 'finance', 'pay', t => t.id === q.id) && !has('finance-lead', 'finance-lead', 'pay', t => t.id === q.id), '付清后付款待办关闭');
  pass('B3 材料付款登记直接回写原计划，已付足额后待办关闭');

  // ---- C. construction income is registered by franchise finance, with no manual closing ----
  const collect=engScenes.find(s=>s.action==='collect');install(collect.before);
  const rp=wx.getStorageSync(E.KEY).projects.find(p=>p.recordFlow),stage=collect.extra.stage;
  login('manager-li','engineer');assert.throws(()=>R.run(rp.id,'collect',collect.form),/财务/);
  const receiptTask=find(desk('finance-wang','finance'),'engineering-collection',t=>t.id===rp.id+':collection:'+stage);assert(receiptTask);listed('finance-wang','finance',receiptTask);
  let p=open(receiptTask.url);p.setData({form:{...p.data.form,...collect.form}});await click('[data-action="collect"]');
  const entry=M.load().receipts.find(r=>r.transaction===collect.form.transaction);assert(entry&&entry.status==='已核算'&&!entry.reviewerId);
  assert.equal(Ops.receivable(M.load(),rp.projectId).received,Number(collect.form.amount)*100);
  assert(desk('finance-lead','finance-lead').tasks.every(t=>['pay','incoming-register','engineering-collection'].includes(t.view)));
  pass('C1 工程只读收款进度，加盟商财务从首页直接登记，立即更新合同实收');

  install(engScenes.at(-1).after);login('finance-lead','finance-lead');
  assert(desk('finance-lead','finance-lead').tasks.every(t=>['pay','incoming-register','engineering-collection'].includes(t.view)));
  const recordProject=wx.getStorageSync(E.KEY).projects.find(p=>p.recordFlow);
  const snapshot=JSON.stringify(wx.getStorageSync(M.KEY));
  for(const action of ['reconcile','payment-review','refund','refund-request','correct','correction-request','appeal','cost-confirm','project-close'])assert.throws(()=>M.mutate(s=>M.apply(s,action,recordProject.projectId,{checked:true,file:file('不能操作')})),/线下办理/);
  assert.equal(JSON.stringify(wx.getStorageSync(M.KEY)),snapshot);
  assert(!D.project(M.load(),recordProject.projectId).settlement);
  pass('C2 归档不生成成本核对和结清待办，退款更正及复核操作被阻止，原记录保留');

  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ result: 'passed', scope: 'Node Page/WXML real handlers; not native tap evidence', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
  process.exit(0);
})().catch(e => { console.error(e.stack); process.exit(1); });
