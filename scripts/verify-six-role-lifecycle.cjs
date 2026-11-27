const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const filename = path.resolve(__dirname, '../miniprogram/pages/workflow/commission/index.js');
const actualRequire = createRequire(filename);
function harness() {
  let definition, generation = 1, context;
  const calls = [], post = { postId: 's1', userId: '145', orgId: '119', roleCode: 'sales', orgType: 'store' };
  const http = { token: () => 'token', sessionGeneration: () => generation,
    sessionGuard: () => { const g = generation; return () => g === generation; }, requestGuard: () => http.sessionGuard() };
  const api = { commissions: {
    context: async id => { calls.push(['context', id]); return context; },
    get: async id => { calls.push(['get', id]); return context.commission; },
    submit: async (id, body) => { calls.push(['submit', id, body]); return { commissionId: '9007199254741999' }; },
    command: async (id, action, body) => { calls.push(['command', id, action, body]); return {}; }
  } };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Page: p => { definition = p; },
    require: n => n.endsWith('/api/http') ? http : n.endsWith('/api/auth') ? { currentApiPost: () => post }
      : n.endsWith('/api/franchise/commission') ? api : n.endsWith('/utils/organization') ? { apiContext: () => ({ role: 'sales', orgType: 'store' }) }
        : n.endsWith('/utils/view-container') ? { makeFormHandlers: () => ({}) } : actualRequire(n),
    wx: { showToast() {}, pageScrollTo() {} }, console });
  const page = { ...definition, data: structuredClone(definition.data), setData(patch) { for (const [key, value] of Object.entries(patch)) { const keys = key.split('.'); let target = this.data; for (const segment of keys.slice(0, -1)) target = target[segment] ||= {}; target[keys.at(-1)] = value; } } };
  return { page, api, calls, http, post, switchPost() { generation++; }, setContext(value) { context = value; } };
}
const context = (budgetVersion = 1, ruleVersion = 2) => ({ customerId: '9007199254741001', latestBudget: { version: budgetVersion, width: 12, depth: 15, floors: 2, area: 360 },
  feeOffer: { mode: 'AREA_FULL', ruleVersion, area: 360, rateCents: 1000, totalCents: 360000, firstCents: 0, tailCents: 0, scope: '施工图', recipient: '总部' }, allowedActions: ['commission-submit'] });
test('new customer uses context, version update keeps draft and forces reconfirmation', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' });
  await h.page.refresh(); assert.equal(h.calls[0][0], 'context'); assert.equal(h.page.id, '');
  h.page.data.form.text = '保留用户已填需求'; h.page.data.form.checked = true;
  h.setContext(context(2, 3)); await h.page.refresh(false);
  assert.equal(h.page.data.form.text, '保留用户已填需求'); assert.equal(h.page.data.form.checked, false);
  assert.equal(h.page.data.form.__budgetVersion, 2); assert.equal(h.page.data.form.__pricingVersion, 3);
});
test('submit uses customerId and subsequent commands use returned opaque commissionId', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' }); await h.page.refresh();
  h.page.data.form.text = 'TEST ONLY'; h.page.data.form.checked = true; h.page.refresh = () => {};
  await h.page.submit(); assert.equal(h.page.id, '9007199254741999'); assert.equal(h.calls.find(c => c[0] === 'submit')[1], '9007199254741001');
  h.page.action = 'design-complete'; h.page.data.form.checked = true; await h.page.submit();
  assert.equal(h.calls.find(c => c[0] === 'command')[1], '9007199254741999');
});
test('old session command response never changes new session state', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' }); await h.page.refresh();
  let finish; h.api.commissions.submit = () => new Promise(resolve => { finish = resolve; });
  h.page.data.form.text = 'TEST ONLY'; h.page.data.form.checked = true;
  const pending = h.page.submit(); h.switchPost(); h.page.data.form.text = '新身份内容'; finish({ commissionId: 'old-id' }); await pending;
  assert.equal(h.page.id, ''); assert.equal(h.page.data.form.text, '新身份内容'); assert.equal(h.page.view, 'submit');
});
test('HQ detail reload stays commission-scoped rather than using CRM customer context', async () => {
  const h = harness(); h.setContext({ commission: { commissionId: 'c1', commission: { id: 'c1', customerId: 'customer1', status: 'DESIGNING' }, submissions: [], handovers: [], progress: [], dispatches: [], allowedActions: [] } });
  h.page.onLoad({ view: 'progress', commissionId: 'c1' }); await h.page.refresh(); await h.page.refresh();
  assert.deepEqual(h.calls.map(c => c[0]), ['get', 'get']);
});

test('unsettled legacy handover exposes only its authorized return with a usable reason field',async()=>{
  const h=harness(),requests=[],module={exports:{}},id='912345678901234567';
  h.post.roleCode='engineer';
  const detail={commission:{id,customerId:'812345678901234567',customerName:'历史客户',status:'HANDOVER_SUBMITTED',workflowMode:'LEGACY'},
    submissions:[],handovers:[],progress:[],dispatches:[],allowedActions:['packet-return']};
  h.http.get=async()=>structuredClone(detail);
  h.http.post=async(url,body,headers)=>{requests.push(structuredClone({url,body,headers}));return {};};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/api/franchise/commission.js'),'utf8'),{module,require:()=>h.http});
  Object.assign(h.api.commissions,module.exports.commissions);
  h.page.onLoad({view:'receive',commissionId:id});await h.page.onShow();
  assert.equal(h.page.data.canReturn,true);assert.equal(h.page.data.primary,'');assert.equal(h.page.action,'');
  assert.deepEqual(Array.from(h.page.data.fields,f=>f.key),['note']);
  await h.page.returnPacket();assert.match(h.page.data.error,/退回原因/);assert.equal(requests.length,0);
  h.page.setData({'form.note':'  补充原交底资料  '});await h.page.returnPacket();
  assert.equal(requests.length,1);
  assert.equal(requests[0].url,'/franchise/commissions/'+id+'/commands/packet-return');
  assert.deepEqual(requests[0].body,{reason:'补充原交底资料',confirmed:true});
  assert.ok(requests[0].headers['Idempotency-Key']);
  h.page.onLoad({view:'receive',commissionId:id,history:'1'});await h.page.onShow();
  assert.equal(h.page.data.canReturn,false);assert.equal(h.page.data.fields.length,0);
  await h.page.returnPacket();assert.equal(requests.length,1);
});

function receiptHarness(options={}) {
  const h=harness(), requests=[], module={exports:{}};
  h.post.roleCode=options.role||'finance-lead';
  h.order={commissionId:'912345678901234567',submissionVersion:2,customerName:'历史客户',status:'PAID',amountCents:20000,
    historicalSettlementPrepared:false,canPrepareHistoricalSettlement:h.post.roleCode==='finance-lead'};
  h.http.get=async url=>{requests.push({method:'GET',url});return structuredClone(h.order);};
  h.http.post=async (url,body,headers)=>{
    requests.push(structuredClone({method:'POST',url,body,headers}));
    if(h.submitReply)return h.submitReply(url,body,headers);
    h.order.historicalSettlementPrepared=true;h.order.canPrepareHistoricalSettlement=false;return {};
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/api/franchise/commission.js'),'utf8'),{module,require:()=>h.http});
  Object.assign(h.api.commissions,{simulatedReceipt:module.exports.commissions.simulatedReceipt,historicalSettlement:module.exports.commissions.historicalSettlement});
  h.page.onLoad({view:'receipt',commissionId:h.order.commissionId});
  return Object.assign(h,{requests,open:()=>h.page.onShow(),fill:note=>h.page.settlementInput({detail:{value:note}})});
}

test('historical settlement uses the original receipt version, actual API and finance review permission',async()=>{
  const h=receiptHarness();await h.open();
  await h.page.prepareHistoricalSettlement();assert.match(h.page.data.error,/核对说明/);
  assert.equal(h.requests.filter(r=>r.method==='POST').length,0);
  h.fill('  已核对原费用及结算依据  ');await h.page.prepareHistoricalSettlement();
  const sent=h.requests.find(r=>r.method==='POST');
  assert.equal(sent.url,'/franchise/commissions/'+h.order.commissionId+'/historical-settlement');
  assert.deepEqual(sent.body,{submissionVersion:2,reviewNote:'已核对原费用及结算依据'});
  assert.ok(sent.headers['Idempotency-Key']);
  assert.equal(h.page.data.paymentOrder.historicalSettlementPrepared,true);
  assert.equal(h.page.data.paymentOrder.canPrepareHistoricalSettlement,false);
  for(const role of ['finance','sales','boss']){
    const restricted=receiptHarness({role});await restricted.open();restricted.fill('说明');
    await restricted.page.prepareHistoricalSettlement();
    assert.equal(restricted.requests.filter(r=>r.method==='POST').length,0);
  }
});

test('unknown historical review result retries the original payload and key after returning to the page',async()=>{
  const h=receiptHarness();await h.open();h.fill('原复核说明');
  h.submitReply=async()=>{h.order.historicalSettlementPrepared=true;throw {code:-1};};
  await h.page.prepareHistoricalSettlement();
  assert.equal(h.page.data.settlementPending,true);
  const readCount=h.requests.filter(r=>r.method==='GET').length;
  h.fill('不能改变原提交');await h.open();
  assert.equal(h.page.data.settlementNote,'原复核说明');
  assert.equal(h.requests.filter(r=>r.method==='GET').length,readCount);
  h.submitReply=async()=>{h.order.canPrepareHistoricalSettlement=false;return {};};
  await h.page.prepareHistoricalSettlement();
  const writes=h.requests.filter(r=>r.method==='POST');
  assert.deepEqual(writes[1],writes[0]);
  assert.equal(h.page.data.settlementPending,false);
});

test('historical review drops stale customer/post responses and clears revoked receipt access',async()=>{
  for(const change of ['commission','post']){
    const h=receiptHarness();await h.open();h.fill('原说明');
    let finish;h.submitReply=()=>new Promise(resolve=>{finish=resolve;});
    const pending=h.page.prepareHistoricalSettlement();
    if(change==='commission'){h.order.commissionId='123';h.page.onLoad({view:'receipt',commissionId:'123'});}
    else h.switchPost();
    await h.open();h.fill('当前说明');assert.equal(h.page.data.settlementNote,'当前说明');
    const expected=structuredClone(h.page.data);
    finish({});await pending;assert.deepEqual(structuredClone(h.page.data),expected);
  }
  for(const rejection of [{code:401},{code:403},{code:1111000000},{code:1111000001},{postInvalid:true}]){
    const revoked=receiptHarness();await revoked.open();revoked.fill('说明');
    revoked.submitReply=async()=>{throw {...rejection,message:'无权办理'};};
    await revoked.page.prepareHistoricalSettlement();
    assert.equal(revoked.page.data.paymentOrder,null);assert.equal(revoked.page.data.busy,false);
    assert.equal(revoked.page.__settlementCommand,null);assert.equal(revoked.page.data.settlementNote,'');
    assert.equal(revoked.page.__cmdKeys['historical-settlement'],undefined);
  }
});

test('historical receipt cannot bind a different commission and exposes only the existing review controls',async()=>{
  const h=receiptHarness();h.order.commissionId='another';await h.open();
  assert.equal(h.page.data.platformStatus,'degraded');assert.equal(h.page.data.paymentOrder,null);
  const template=fs.readFileSync(path.join(__dirname,'../miniprogram/pages/workflow/commission/simulated-payment.wxml'),'utf8');
  assert.match(template,/paymentOrder.canPrepareHistoricalSettlement/);
  assert.match(template,/bindtap="prepareHistoricalSettlement"/);
  assert.match(template,/模拟记录不计入实际收支/);
});

test('receipt refresh clears historical financial details and draft after a domain access rejection',async()=>{
  for(const code of ['1111000000','1111000001',401,403]){
    const h=receiptHarness();await h.open();h.fill('旧核对说明');h.page.cmdKey('historical-settlement');
    h.http.get=async()=>{throw {code,message:'无权读取'};};
    await h.open();
    assert.equal(h.page.data.paymentOrder,null);assert.equal(h.page.data.customer,null);
    assert.equal(h.page.data.settlementNote,'');assert.equal(h.page.__settlementCommand,null);
    assert.equal(h.page.__cmdKeys['historical-settlement'],undefined);
  }
});
