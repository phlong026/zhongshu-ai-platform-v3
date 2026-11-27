// Historical new-LEGACY submission lifecycle (retired by T13/T14); run only against its pre-T14 Git worktree.
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
if (!process.env.ZS_LEGACY_WORKTREE_ROOT) throw Error('Historical check: set ZS_LEGACY_WORKTREE_ROOT to the pre-T14 e9cd1313 worktree');
const filename = path.resolve(process.env.ZS_LEGACY_WORKTREE_ROOT, 'miniprogram/pages/workflow/commission/index.js');
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

const Brief=require(path.resolve(process.env.ZS_LEGACY_WORKTREE_ROOT,'miniprogram/pages/workflow/commission/design-brief.js'));
test('requirements inherit recorded scheme fields without confusing entrance, house, image orientation or population',()=>{
 const inherited=Brief.inherit({customerName:'王先生',latestBudget:{payload:JSON.stringify({configSnapshot:{orientation:'PORTRAIT',entranceOrientation:'SOUTH',rooms:{bedroom:5,living:3,bath:2},style:'现代'}})}}).brief;
 assert.equal(inherited.entranceDirection,'南');assert.equal(inherited.orientation,'');assert.equal(inherited.entranceAligned,'');assert.equal(inherited.householdMembers,'');assert.equal(inherited.bedrooms,'5');assert.deepEqual(inherited.rooms,['卧室','客厅']);assert.deepEqual(inherited.styleRefs,['现代']);
 assert.equal(Brief.inherit({}, {...inherited,bedrooms:'4'}).brief.bedrooms,'4');
 assert.equal(Brief.inherit({latestBudget:{payload:'bad json'}}).brief.orientation,'');
});

const legacyRequire=createRequire(path.resolve(process.env.ZS_LEGACY_WORKTREE_ROOT,'scripts/verify-design-commission-repair.cjs'));
test('retired simulation never enters a command even when an older server advertises it',()=>{
 let page;
 vm.runInNewContext(fs.readFileSync(legacyRequire.resolve('../miniprogram/pages/workflow/commission/index.js'),'utf8'),{
  Page:value=>{page=value;},
  require:id=>id==='./payment-flow'?()=>({}):id.endsWith('/view-container')?{makeFormHandlers:()=>({})}:{}
 });
 const form={__mode:'AREA_FULL',__budgetVersion:1,__pricingVersion:2,text:'建房需求',scope:'设计方案',checked:true};
 const ctx={data:{form},__context:{feeOffer:{simulationAllowed:false}}};
 const payload=()=>page.commandPayload.call(ctx,'commission-submit');
 assert.equal(payload().paymentMode,undefined);
 form.testPayment=true;
 assert.throws(payload,/模拟付款已停用/);
 ctx.__context.feeOffer.simulationAllowed=true;
 assert.throws(payload,/模拟付款已停用/,'an older backend cannot authorize the versioned test mode');
 ctx.__context.feeOffer.simulationPaymentMode='TEST_SIMULATION';
 assert.throws(payload,/模拟付款已停用/);
 form.testPayment=false;
 assert.equal(payload().paymentMode,undefined);
});

function commissionPage(postSubmit, postCommand) {
 let definition;
 const offer={mode:'AREA_FULL',ruleVersion:2,scope:'设计方案',totalCents:1000,simulationPaymentMode:'TEST_SIMULATION'};
 const api={commissions:{
   context:async()=>({customerId:'7',customerName:'张工',allowedActions:['commission-submit'],
     latestBudget:{version:1},feeOffer:{...offer}}),
   submit:postSubmit,
   command:postCommand,
 }};
 const presentation=legacyRequire('../miniprogram/pages/workflow/commission/presentation');
 vm.runInNewContext(fs.readFileSync(legacyRequire.resolve('../miniprogram/pages/workflow/commission/index.js'),'utf8'),{
  Page:value=>{definition=value;},
  wx:{showToast(){},pageScrollTo(){},removeStorageSync(){}},
  require:id=>({
   './payment-flow':()=>({}),'../../../utils/view-container':{makeFormHandlers:()=>({})},
   '../../../api/franchise/commission':api,'../../../api/http':{sessionGuard:()=>()=>true},
   './presentation':presentation,'./design-brief':legacyRequire('../miniprogram/pages/workflow/commission/design-brief')
  }[id]||{})
 });
 const page={...definition,view:'submit',action:'commission-submit',customerId:'7',__byCustomer:true,options:{},
  initialized:true,__post:{},__context:{feeOffer:{...offer}},actor:()=>({}),
  setData(patch){for(const [key,value] of Object.entries(patch)){
   if(key.startsWith('form.'))this.data.form[key.slice(5)]=value;
   else this.data[key]=value;
  }},
  refresh(){if(this.view==='submit')return this.refreshCommission(()=>true,false);},
  refreshOrderSheet(){}};
 page.data={...definition.data,platformStatus:'ready',simulationAllowed:true,
   form:{__mode:'AREA_FULL',__budgetVersion:1,__pricingVersion:2,text:'建房需求',scope:'设计方案',checked:true,testPayment:true}};
 return {page,withdraw:()=>{delete offer.simulationPaymentMode;}};
}
test('原负责人转交后，指定接收人可以在原页面确认移交',async()=>{
 const calls=[]; let approved=false;
 const {page}=commissionPage(null,async(id,action,payload,key)=>{
  calls.push({id,action,payload,key});
  if(!approved)throw {code:1115000011,message:'请联系现负责人在工程档案中变更负责人'};
  return {};
 });
 page.view='receive';page.id='8001';page.action='packet-receive';page.data.form={checked:true};
 await page.submit();
 assert.equal(page.view,'receive');
 assert.match(page.data.error,/联系现负责人/);
 approved=true;
 await page.submit();
 assert.equal(calls.length,2);
 for(const call of calls) assert.deepEqual({...call.payload},{confirmed:true});
 assert.equal(calls[1].key,calls[0].key);
 assert.equal(page.view,'progress');
});
test('withdrawal invalidates consent and formal submission requires a new explicit choice',async()=>{
 const calls=[];
 const {page,withdraw}=commissionPage(async (...call)=>{calls.push(call);return {commissionId:'8'};});
 withdraw();
 await page.refresh();
 assert.equal(page.data.paymentChoiceRequired,true);
 assert.equal(page.data.form.checked,false);
 await page.submit();
 assert.equal(calls.length,0);
 page.chooseFormalCommission();
 await page.submit();
 assert.equal(calls.length,0);
 page.check({currentTarget:{dataset:{key:'checked'}},detail:{value:['yes']}});
 await Promise.all([page.submit(),page.submit()]);
 assert.equal(calls.length,1);
 assert.equal(calls[0][1].paymentMode,undefined);
});
test('stale simulation drafts require a fresh physical-fee confirmation before any request',async()=>{
 const calls=[];
 const {page,withdraw}=commissionPage(async (_,payload)=>{
   calls.push(payload.paymentMode);withdraw();throw {code:1111000022,message:'演练权限已撤回'};
 });
 await page.submit();
 await page.submit();
 assert.deepEqual(calls,[]);
 assert.equal(page.data.paymentChoiceRequired,true);
 assert.equal(page.data.form.checked,false);
});
