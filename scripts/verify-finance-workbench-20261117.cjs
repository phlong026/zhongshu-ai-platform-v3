const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const PF = require('../miniprogram/utils/platform-finance');
const path = require('node:path');
const {render} = require('./role-page-runtime.cjs');
const tick = () => new Promise(resolve=>setImmediate(resolve));
function refreshHarness(count=2){
 let spec;const requests=[],patches=[],sequences={};
 const state={hold:false,session:1,token:'token-1',userId:'user-1',tenant:'tenant-1',post:{userId:'user-1',tenantId:'tenant-1',postId:'post-1',orgId:'org-1',roleCode:'finance',orgType:'headquarters'},receipts:Array.from({length:count},(_,i)=>({id:String(i+1),name:'收款'+(i+1),status:'已登记',receivedDate:'2026-11-10',amountCents:10000}))};
 const http={token:()=>state.token,currentUserId:()=>state.userId,tenantId:()=>state.tenant,currentPostId:()=>state.post.postId,sessionGeneration:()=>state.session,
  get:async()=>({nickname:'当前财务'}),requestGuard:domain=>{const id=sequences[domain]=(sequences[domain]||0)+1,session=state.session;return()=>session===state.session&&sequences[domain]===id;}};
 const auth={currentApiPost:()=>state.post};
 const scopeModule={exports:{}};vm.runInNewContext(fs.readFileSync('miniprogram/utils/api-scope.js','utf8'),{module:scopeModule,require:p=>p.endsWith('/http')?http:auth});
 const call=(kind,args)=>{const value=kind==='reconciliation'?{month:args[0],grossReceiptsCents:state.receipts.length*10000,netReceivedCents:state.receipts.length*10000}:kind==='receipts'&&!args[0]?structuredClone(state.receipts):[];
  const request={kind,args,value};requests.push(request);return state.hold?new Promise((resolve,reject)=>Object.assign(request,{resolve,reject})):Promise.resolve(value);};
 const api=Object.fromEntries(['receipts','payments','refunds','bankFlows','payables','reconciliation'].map(kind=>[kind,(...args)=>call(kind,args)]));
 const req=n=>n.endsWith('/api/http')?http:n.endsWith('/api/auth')?auth:n.endsWith('/api/finance')?api:n.endsWith('/api-scope')?scopeModule.exports:n.endsWith('/finance-workbench-view')?require('../miniprogram/utils/finance-workbench-view'):n.endsWith('/accounting/model')?require('../miniprogram/pages/admin/accounting/model'):n.endsWith('/finance-date')?require('../miniprogram/utils/finance-date'):n.endsWith('/account')?{localDate:()=> '2026-11-27'}:n.endsWith('/platform-finance')?PF:n.endsWith('/finance-desk')?{url:()=>'/finance'}:n.endsWith('/view-container')?{routeValue:v=>v||''}:n.endsWith('/platform-todos-page')?{init(){},refresh(){}}:n.endsWith('/profile/profile-page')?()=>({onHide(){},onUnload(){}}):n.endsWith('/finance-keyboard')?{start(){},stop(){}}:{};
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/index.js','utf8'),{Page:p=>spec=p,require:req,Date,Promise,console,wx:{pageScrollTo(){},showToast(){}}});
 const page={...spec,__designFinance:true,__platformActions:true,__workbench:true,__journey:true,data:structuredClone(spec.data),
  setData(patch,done){patches.push(structuredClone(patch));for(const[k,v]of Object.entries(patch)){const keys=k.replace(/\[(\d+)\]/g,'.$1').split('.');let d=this.data;for(const key of keys.slice(0,-1))d=d[key]||(d[key]={});d[keys.at(-1)]=v;}if(done)done();},
  resumePayment(){},installWorkbenchActions(){},loadWorkbench(){},loadDesignFees(){},loadFinanceSourceRows(){},loadFinanceProjects(){},loadBookLedger(){}};
 page.onLoad({view:'flows',tab:'ledger',month:'2026-11'});
 return {page,state,requests,patches,http,api,req,scope:scopeModule.exports,release(start=0){for(const r of requests.slice(start))if(r.resolve)r.resolve(r.value);}};
}
test('same-query finance refresh keeps the full visible ledger and reconciliation while coalescing duplicate shows',async()=>{
 const h=refreshHarness(205);h.page.onShow();await tick();h.page.loadMoreLedger();assert.equal(h.page.data.platformLedger.length,200);
 const before=JSON.stringify([h.page.data.platformFinance,h.page.data.reconciliation,h.page.data.platformLedger,h.page.data.deskHome.metrics]);
 h.state.hold=true;const start=h.requests.length;h.page.onShow();h.page.onShow();
 assert.equal(h.requests.length-start,7);assert.equal(h.page.data.platformFinance.status,'ready');assert.equal(h.page.data.platformLedger.length,200);
 assert.equal(JSON.stringify([h.page.data.platformFinance,h.page.data.reconciliation,h.page.data.platformLedger,h.page.data.deskHome.metrics]),before);
 const rendered=render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data);
 const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]);assert(flat(rendered).filter(n=>n.attrs&&n.attrs.class==='fin-flow-row').length>=200);
 h.release(start);await tick();assert.equal(h.page.data.platformLedger.length,200);assert.equal(h.page.data.ledgerPage,2);
});
test('ordinary finance and reconciliation failures retain the last figures and retry without a loading replacement',async()=>{
 const h=refreshHarness();h.page.onShow();await tick();const amount=h.page.data.platformFinance.metrics.received,net=h.page.data.reconciliation.netText;
 h.state.hold=true;const start=h.requests.length;h.page.loadPlatformFinance();
 for(const r of h.requests.slice(start))if(r.kind==='receipts'&&!r.args[0]||r.kind==='reconciliation')r.reject({code:500,message:'offline'});else r.resolve(r.value);
 await tick();assert.equal(h.page.data.platformFinance.status,'ready');assert.equal(h.page.data.platformFinance.metrics.received,amount);assert.equal(h.page.data.reconciliation.netText,net);assert.equal(h.page.data.platformLedger.length,2);
 assert(h.page.data.platformFinance.message);assert(h.page.data.reconciliation.message);
 const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children)).join('');assert(text(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data)).includes('更新失败'));
 h.state.hold=false;await h.page.retryPlatformFinance();await tick();assert(!h.page.data.platformFinance.message);assert(!h.page.data.reconciliation.message);
});
test('month, post permission and tenant changes clear finance values and reject previous responses',async()=>{
 for(const field of ['month','roleCode','orgType','tenant','userId','permissions']){
  const h=refreshHarness();h.page.onShow();await tick();h.state.hold=true;let start=h.requests.length;h.page.loadPlatformFinance();
  if(field==='month')h.page.data.selectedMonth='2026-10';else if(['tenant','userId'].includes(field))h.state[field]+='-new';else h.state.post[field]=field==='roleCode'?'finance-lead':field==='orgType'?'store':['scope:new'];
  const nextStart=h.requests.length;h.page.loadPlatformFinance();assert.equal(h.page.data.platformLedger.length,0,field);assert.equal(h.page.data.platformRegister,null,field);
  h.release(nextStart);await tick();const latest=JSON.stringify(h.page.data.platformFinance);h.release(start);await tick();assert.equal(JSON.stringify(h.page.data.platformFinance),latest,field);
 }
});
test('hide and unload invalidate finance flights and a return reads again before the old batch settles',async()=>{
 for(const action of ['onHide','onUnload']){
  const h=refreshHarness();h.page.onShow();await tick();h.state.hold=true;const start=h.requests.length;h.page.loadPlatformFinance();h.page[action]();h.page.onShow();
  assert.equal(h.requests.length-start,14,action);h.release(start+7);await tick();const latest=JSON.stringify(h.page.data.platformFinance);
  h.requests[start].reject({code:403});for(const r of h.requests.slice(start+1,start+7))r.resolve(r.value);await tick();assert.equal(JSON.stringify(h.page.data.platformFinance),latest);
 }
});
test('an explicit post-transaction refresh cannot merge into an older pending financial snapshot',async()=>{
 const h=refreshHarness();h.page.onShow();await tick();h.state.hold=true;const start=h.requests.length;h.page.loadPlatformFinance();
 h.state.receipts=[{...h.state.receipts[0],amountCents:90000}];h.page.loadPlatformFinance(true);assert.equal(h.requests.length-start,14);
 h.release(start+7);await tick();assert.equal(h.page.data.platformFinance.metrics.received,'900.00');h.release(start);await tick();assert.equal(h.page.data.platformFinance.metrics.received,'900.00');
});
test('a current finance denial clears cached facts, visible cash and the other in-flight read result',async()=>{
 for(const error of [{code:401},{code:403},{status:401},{status:403},{statusCode:401},{statusCode:403},{code:1103000002},{postInvalid:true}]){
  const h=refreshHarness();h.page.onShow();await tick();h.page.data.journeyCashRows=[{key:'old'}];h.state.hold=true;const start=h.requests.length;h.page.loadPlatformFinance();h.requests[start].reject(error);await tick();
  assert.equal(h.page.data.platformLedger.length,0);assert.equal(h.page.data.journeyCashRows.length,0);assert.equal(h.page.__financeFacts,null);assert.equal(h.page.data.platformRegister,null);
  h.release(start+1);await tick();assert.notEqual(h.page.data.reconciliation&&h.page.data.reconciliation.status,'ready');
 }
});
test('auxiliary reads cannot restore finance content after a batch denial or hide and return',async()=>{
 for(const transition of ['denial','hide']){
  const h=refreshHarness();h.page.onShow();await tick();h.state.hold=true;const start=h.requests.length;h.page.loadPlatformFinance();
  const pending=[];h.http.get=url=>new Promise(resolve=>pending.push(()=>resolve(url.endsWith('/books')?[{id:'private-old-book'}]:[])));h.api.designFees=()=>new Promise(resolve=>pending.push(()=>resolve({list:[],total:0})));
  for(const name of ['workbench','design-finance']){const module={exports:{}};vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/'+name+'.js','utf8'),{module,require:h.req,Promise,console,wx:{showToast(){}}});module.exports(h.page,h.api);}
  const work=h.page.loadWorkbench(),design=h.page.loadDesignFees();
  if(transition==='denial'){h.requests[start].reject({statusCode:403});await tick();}
  else{h.page.onHide();h.page.__financeHidden=false;}
  h.release(start+1);for(const resolve of pending)resolve([]);await Promise.all([work,design]);assert.equal(h.page.data.workbenchBooks.length,0);
  if(transition==='denial'){assert.equal(h.page.data.deskHome,null);assert.equal(h.page.data.designFeeRows.length,0);}
  else{assert.notEqual(h.page.__messageWorkbenchReady,true,'hidden callbacks remain invalid after return');assert.notEqual(h.page.data.designFeeStatus,'ready');}
 }
});
test('cash net includes both refund directions and excludes personal funding', () => {
 const result=PF.metrics([{status:'已登记',receivedDate:'2026-11-01',amountCents:100000}],
  [{status:'已核实',payDate:'2026-11-01',amountCents:60000},{status:'已核实',payDate:'2026-11-01',amountCents:240000,fundingType:'EMPLOYEE'}],
  [{status:'已核实',date:'2026-11-02',amountCents:5000,refundDirection:'client'},{status:'已核实',date:'2026-11-02',amountCents:2000,refundDirection:'expense'},{status:'已核实',date:'2026-11-02',amountCents:1000,refundDirection:'expense',fundingType:'EMPLOYEE'}],'2026-11');
 assert.equal(result.net,'370.00');assert.equal(result.paid,'600.00');
 assert.equal(PF.metrics([],[{status:'已核实',payDate:'2026-11-01',amountCents:300}],[],'2026-11').net,'-3.00');
});
test('refund to employee reduces reimbursement eligibility without duplicating vendor payable',()=>{
 const rows=PF.payableProgress([{id:'2',sourceType:'reimbursement',sourceRef:'10',totalCents:240000,status:'进行中'}],
 [{id:'10',payableId:'1',status:'已核实',amountCents:240000,fundingType:'EMPLOYEE'},{id:'11',payableId:'2',status:'已核实',amountCents:100000}],
 [{id:'12',paymentId:'10',amountCents:50000,refundDirection:'expense',status:'已核实',fundingType:'EMPLOYEE'}]);
 assert.equal(rows[0].totalCents,190000);assert.equal(rows[0].remainingCents,90000);
});
test('returning an unallocated overpayment does not reopen the settled supplier balance',()=>{
 const row=PF.payableProgress([{id:'q',totalCents:800000,status:'进行中'}],[{id:'p',payableId:'q',amountCents:1000000,pendingLinkCents:200000,status:'已核实'}],[{id:'f',paymentId:'p',amountCents:200000,status:'已核实',refundDirection:'expense'}])[0];
 assert.equal(row.paidCents,800000);assert.equal(row.remainingCents,0);
});
test('the selected month survives loading and the picker reloads the actual selected value', async()=>{
 let spec;const api={receipts:async()=>[],payments:async()=>[],refunds:async()=>[],payables:async()=>[],bankFlows:async()=>[],reconciliation:async month=>({month})};
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/index.js','utf8'),{Page:p=>spec=p,require:n=>n.endsWith('/finance-workbench-view')?require('../miniprogram/utils/finance-workbench-view'):n.endsWith('/api/finance')?api:n.endsWith('/api-scope')?()=> 'finance-test-scope':n.endsWith('/api/auth')?{currentApiPost:()=>({roleCode:'finance'})}:n.endsWith('/api/http')?{requestGuard:()=>()=>true,get:async()=>({})}:n.endsWith('/platform-finance')?PF:n.endsWith('/finance-desk')?{url:()=>''}:{},Date,Promise,wx:{}});
 const p={...spec,data:{...spec.data,view:'desk',selectedMonth:'2026-10'},setData(patch){for(const [k,v]of Object.entries(patch)){const parts=k.split('.');let d=this.data;while(parts.length>1){const f=parts.shift();d=d[f]||(d[f]={});}d[parts[0]]=v;}}};
 p.loadPlatformFinance();await new Promise(r=>setImmediate(r));assert.equal(p.data.platformFinance.month,'2026-10');
 p.month({detail:{value:'2026-09'}});await new Promise(r=>setImmediate(r));assert.equal(p.data.platformFinance.month,'2026-09');
});
test('home keeps confirmed module order and a single month selector',()=>{
 const s=fs.readFileSync('miniprogram/pages/admin/finance-desk/finance-home.wxml','utf8');
 const markers=['fin-home-intro','<home-overview','id="today-todos"','常用办理','正在办理'].map(x=>s.indexOf(x));
 assert.match(s,/<todo-preview\b(?=[^>]*id="today-todos")(?=[^>]*appearance="home")[^>]*>/);
 assert.ok(markers.every((n,i)=>n>=0&&(!i||n>markers[i-1])));assert.ok(!s.includes('平台账簿'));assert.ok(!s.includes('本月'));assert.equal((s.match(/<month-select /g)||[]).length,1);assert.ok(s.includes('bind:change="financeMonth"'));
 assert.equal(PF.shortcuts(false)[2].label,'报销办理');
 assert.ok(s.includes('wx:if="{{!desk.hq}}" role="finance"'));assert.ok(s.includes('wx:if="{{desk.hq}}"'));assert.ok(s.includes('bind:monthchange="financeMonth"'));
});
test('manual entries and contract receipts recover the frozen request after process reload',async()=>{
 const source=fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js','utf8');
 const recovery=fs.readFileSync('miniprogram/pages/admin/finance-desk/payment-recovery.js','utf8');
 for(const type of ['receipt','expense','stage'])for(const committed of [false,true]){
  let uploads=0,lookups=0,postId='original-post',roleCode='finance';const calls=[],refreshes=[],storage=new Map();
  const result=type==='stage'?{receiptId:'9007199254742999'}:{id:'9007199254742999'};
  const endpoint=type==='stage'?'/franchise/sales/stages/9007199254741999/collect':'/franchise/finance/workbench/entries';
  const key='frozen-'+type+'-'+committed;
  const wx={showToast(){},pageScrollTo(){},setStorageSync:(k,v)=>storage.set(k,structuredClone(v)),getStorageSync:k=>storage.get(k),removeStorageSync:k=>storage.delete(k)};
  const auth={currentApiPost:()=>({orgId:'store',userId:'finance',postId,orgType:'store',roleCode})};
  const http={sessionGuard:()=>()=>true,currentPostId:()=>postId,tenantId:()=> 'tenant',requestGuard:()=>()=>true,
   post:async(url,body,headers)=>{calls.push(JSON.parse(JSON.stringify({url,body,headers})));if(calls.length===1)throw Error('network response lost');return result;},
   get:async(url,args)=>{lookups++;assert.equal(url,endpoint+'/registration-result');assert.equal(args.key,key);return committed?result:{state:'NOT_REGISTERED'};}};
  const api={idemKey:()=>key,uploadVoucher:async()=>{uploads++;return '9007199254740999';}};
  const form={type:type==='expense'?'expense':'receipt',scope:type==='stage'?'contract:91':'company',name:'原收付事项',category:'其他',amount:'860',date:'2026-11-17',accountName:'公司银行',counterparty:'原往来方',voucherPath:'/test/voucher.png',...(type==='stage'?{stageId:'9007199254741999'}:{})};
  function create(form){
   const module={exports:{}};vm.runInNewContext(source,{module,require:n=>n.endsWith('/api/http')?http:n.endsWith('/api/auth')?auth:n.endsWith('/platform-finance')?PF:n.endsWith('/account')?{localDate:()=> '2026-11-17'}:{},wx,Promise,Number,String});
   const page={data:{view:'book',wbForm:form},setData(patch){for(const[k,v]of Object.entries(patch)){const parts=k.split('.');let d=this.data;while(parts.length>1){const k=parts.shift();d=d[k]||(d[k]={});}d[parts[0]]=v;}},options:{},loadPlatformFinance(force){refreshes.push(force);}};
   module.exports(page,api);page.wbKey=key;page.wbGeneration=1;page.loadWorkbench=()=>{};
   const moduleRecovery={exports:{}};vm.runInNewContext(recovery,{module:moduleRecovery,require});moduleRecovery.exports(page,api,http,auth,wx,PF);
   return page;
  }
  const first=create({...form});
  if(type==='stage'){roleCode='engineer';await first.submitWorkbench();assert.equal(calls.length,0);assert.equal(uploads,0);roleCode='finance';}
  await first.submitWorkbench();
  assert.equal(calls.length,1);assert.equal(first.data.paymentPending,true);assert.equal(storage.size,1);
  first.wbInput({currentTarget:{dataset:{field:'amount'}},detail:{value:'999'}});
  assert.equal(first.data.wbForm.amount,'860');await first.submitWorkbench();assert.equal(calls.length,1);
  const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]);
  const controls=flat(render(path.resolve('miniprogram/pages/admin/finance-desk/journey-forms.wxml'),first.data)).filter(n=>['input','picker'].includes(n.tag));
  assert(controls.length>0);assert(controls.every(n=>n.attrs.disabled));
  first.__disposed=true;
  const reopened=create({...form,amount:'999',voucherPath:'/test/another.png'});
  postId='another-post';await reopened.resumePayment();assert.equal(lookups,0);assert.equal(calls.length,1);
  postId='original-post';await reopened.resumePayment();
  if(!committed){assert.equal(reopened.data.paymentPending,true);await reopened.retryPendingPayment();assert.equal(calls.length,2);assert.deepEqual(calls[1],calls[0]);}
  else assert.equal(calls.length,1);
  assert.equal(calls[0].url,endpoint);assert.equal(calls[0].body.amountCents,86000);assert.equal(calls[0].body.voucherFileId,'9007199254740999');
  assert(!Object.hasOwn(calls[0].body,'stageId'));assert.equal(uploads,1);assert.equal(storage.size,0);assert.equal(reopened.data.wbForm,null);assert.equal(reopened.data.paymentPending,false);assert.deepEqual(refreshes,[true]);
 }
});
// Append to scripts/verify-finance-workbench-20261117.cjs for review reproduction.
const reviewBooks=[{scope:'book:11',name:'旧岗位私有账本'}];
const reviewBookResult={summary:{received_cents:12345,paid_cents:0,refunded_cents:0,expense_refunded_cents:0,committed_cents:0},entries:[{id:'private-entry',type:'RECEIPT',name:'旧岗位私有收款',amount_cents:12345,date:'2026-11-10',status:'已登记'}]};
function attachReviewWorkbench(h){
 const module={exports:{}};
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js','utf8'),{module,require:h.req,Promise,console,wx:{showToast(){},pageScrollTo(){}}});
 module.exports(h.page,h.api);
}
for(const method of ['loadFinanceProjects','loadBookLedger'])for(const transition of ['denial','hide']){
 test('F09C review '+method+' cannot restore books after '+transition,async()=>{
  const h=refreshHarness();h.page.data.view=method==='loadBookLedger'?'book':'projects';h.page.options.bookScope='book:11';h.page.onShow();await tick();
  h.state.hold=true;const start=h.requests.length;const finance=h.page.loadPlatformFinance();
  const pending=[];h.http.get=url=>new Promise(resolve=>pending.push(()=>resolve(url.endsWith('/ledger')?structuredClone(reviewBookResult):structuredClone(reviewBooks))));
  attachReviewWorkbench(h);const read=h.page[method]();
  if(transition==='denial'){h.requests[start].reject({code:403});await tick();}else h.page.onHide();
  assert.equal(h.page.data.workbenchBooks.length,0);
  h.release(start+1);for(const resolve of pending)resolve();await read;
  if(transition==='denial')await finance;
  assert.equal(h.page.data.workbenchBooks.length,0,'a late auxiliary read repopulated books after its page was invalidated');
 });
}
for(const check of ['visible detail','cached rows'])test('F09C review denial removes old book '+check,async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.options.bookScope='book:11';h.page.onShow();await tick();
 attachReviewWorkbench(h);h.http.get=async url=>url.endsWith('/ledger')?structuredClone(reviewBookResult):structuredClone(reviewBooks);
 await h.page.loadBookLedger();h.page.openBookEntry({currentTarget:{dataset:{key:'RECEIPT:private-entry'}}});
 h.state.hold=true;const start=h.requests.length,read=h.page.loadPlatformFinance();h.requests[start].reject({code:403});h.release(start+1);await read;
 assert.equal(h.page.data.bookEntries.length,0);
 if(check==='visible detail'){
  const content=nodes=>nodes.map(n=>(n.text||'')+content(n.children)).join('');
  assert.equal(content(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data)).includes('旧岗位私有收款'),false,'the actual revoked page still renders the old entry detail');
  assert.equal(h.page.data.bookEntryDetail,null);
 }else{
  h.page.changeBookFlowTab({currentTarget:{dataset:{tab:'cash'}}});
  assert.equal(h.page.data.bookEntries.length,0,'switching the visible book tab resurrected rows from the old identity');
 }
});
test('the current book remains mounted through refresh and network error but clears when selecting another book',async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.options.bookScope='book:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 h.http.get=async url=>url.endsWith('/ledger')?structuredClone(reviewBookResult):structuredClone(reviewBooks);await h.page.loadBookLedger();
 const content=()=>{const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children)).join('');return text(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data));};
 let reject;h.http.get=url=>url.endsWith('/ledger')?new Promise((yes,no)=>reject=no):Promise.resolve(structuredClone(reviewBooks));const refresh=h.page.loadBookLedger();
 assert(content().includes('旧岗位私有收款'),'refresh must not remove the existing book subtree');reject({status:500,message:'暂时离线'});await refresh;assert(content().includes('旧岗位私有收款'));assert(content().includes('更新失败'));
 h.page.options.bookScope='book:22';const next=h.page.loadBookLedger();assert(!content().includes('旧岗位私有收款'));assert.equal(h.page.data.bookEntryRows.length,0);reject({status:500,message:'暂时离线'});await next;assert(!content().includes('旧岗位私有收款'));
});

test('F09C review retry after denial cannot revive an older workbench authorization',async()=>{
 const h=refreshHarness();h.page.onShow();await tick();attachReviewWorkbench(h);
 h.state.hold=true;const first=h.requests.length;h.page.loadPlatformFinance();
 const auxiliary=[];
 h.http.get=url=>url==='/platform/profile/mine'?Promise.resolve({nickname:'当前财务'}):new Promise(resolve=>auxiliary.push(()=>resolve(url.endsWith('/books')?structuredClone(reviewBooks):[])));
 const old=h.page.loadWorkbench(),oldReceipt=h.requests.at(-1),identity=h.page.__financeIdentity;
 h.requests[first].reject({code:403});await tick();assert.equal(h.page.__financeIdentity,'');
 h.page.retryPlatformFinance();assert.equal(h.page.__financeIdentity,identity);
 assert.equal(h.page.data.workbenchBooks.length,0);
 for(const resolve of auxiliary)resolve();oldReceipt.resolve(oldReceipt.value);await old;
 assert.equal(h.page.data.workbenchBooks.length,0,'restoring the identity string revived a request from before the denial');
 assert.notEqual(h.page.__messageWorkbenchReady,true);
});
test('the book retry after an explicit denial performs a fresh authorized read on its first tap',async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.options.bookScope='book:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 h.http.get=async url=>url.endsWith('/ledger')?structuredClone(reviewBookResult):structuredClone(reviewBooks);await h.page.loadBookLedger();
 h.state.hold=true;const start=h.requests.length,read=h.page.loadPlatformFinance();h.requests[start].reject({status:403});h.release(start+1);await read;assert.equal(h.page.data.bookResult,null);
 await h.page.loadBookLedger();assert.equal(h.page.data.bookName,'旧岗位私有账本');assert.equal(h.page.data.bookEntries.length,1);assert.equal(h.page.data.bookError,'');
});

const employeeFacts=[
 {payable_id:'9007199254740999',origin_payment_id:'101',payer_name:'未报销员工',eligible_cents:10000,reimbursed_cents:0,recovered_cents:0},
 {payable_id:'9007199254741000',origin_payment_id:'102',payer_name:'部分报销员工',eligible_cents:10000,reimbursed_cents:4000,recovered_cents:0},
 {payable_id:'9007199254741001',origin_payment_id:'103',payer_name:'待归还员工',eligible_cents:2000,reimbursed_cents:4000,recovered_cents:500},
 {payable_id:'9007199254741002',origin_payment_id:'104',payer_name:'已结清员工',eligible_cents:2000,reimbursed_cents:4000,recovered_cents:2000},
 {payable_id:'9007199254741003',origin_payment_id:'105',payer_name:'另一项目员工',eligible_cents:8000,reimbursed_cents:0,recovered_cents:0}
].map(r=>({...r,stage:'原交通垫付',pay_date:'2026-11-01'}));
const employeeLedger=(scope,ids,entries=[])=>({scope,reimbursementIds:ids,entries,summary:{received_cents:0,paid_cents:0,refunded_cents:0,expense_refunded_cents:0,committed_cents:0,outstanding_cents:0}});
for(const count of [501,1001])test(`${count} cash entries do not hide original employee balances or their actions`,async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.data.bookFlowTab='employee';h.page.options.bookScope='project:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 const ids=employeeFacts.slice(0,4).map(r=>r.payable_id),books=[{scope:'project:11',name:'原项目'}];
 const entries=Array.from({length:count},(_,i)=>({id:String(2000+i),type:'RECEIPT',date:'2026-11-27',amount_cents:1,name:'较新流水',status:'已登记'})).slice(0,500);
 h.http.get=async url=>url.endsWith('/ledger')?employeeLedger('project:11',ids,entries):url.endsWith('/books')?books:url.endsWith('/reimbursements')?structuredClone(employeeFacts):[];
 if(count===501){await h.page.loadBookLedger();await h.page.loadWorkbench();}else{await h.page.loadWorkbench();await h.page.loadBookLedger();}
 assert.equal(h.page.data.bookEntryRows.length,500);
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.id),ids);
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.remaining),[10000,6000,-1500,0]);
 assert.equal(h.page.data.bookEmployeeRows[2].remainingText,'15.00');
 const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]),nodes=flat(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data));
 assert.equal(nodes.filter(n=>n.attrs?.class==='book-employee-row').length,4);
 assert.equal(nodes.filter(n=>n.attrs?.bindtap==='reimburseEmployee').length,2);
 assert.equal(nodes.filter(n=>n.attrs?.bindtap==='recoverEmployee').length,1);
 h.page.openRegister=()=>h.page.setData({registerForm:{}});
 h.page.reimburseEmployee({currentTarget:{dataset:{id:ids[1]}}});
 assert.equal(h.page.data.registerForm.payableId,ids[1]);assert.equal(h.page.data.registerForm.amount,'60.00');
 h.api.idemKey=()=> 'original-recovery';
 h.page.recoverEmployee({currentTarget:{dataset:{id:ids[2]}}});
 assert.equal(h.page.data.wbForm.payableId,ids[2]);assert.equal(h.page.data.wbForm.amount,'15.00');
});

for(const transition of ['book','company','post'])test(`employee balances cannot return from an older ${transition} request`,async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.options.bookScope='project:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 const first=employeeFacts[0],second=employeeFacts[4],books=[{scope:'project:11',name:'项目A'},{scope:'project:22',name:'项目B'}];
 let facts=[first,second];
 h.http.get=async url=>url.endsWith('/ledger')?employeeLedger('project:11',[first.payable_id]):url.endsWith('/books')?books:url.endsWith('/reimbursements')?structuredClone(facts):[];
 await h.page.loadWorkbench();await h.page.loadBookLedger();
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.id),[first.payable_id]);
 const pending=[];
 h.http.get=(url,args)=>url.endsWith('/ledger')?new Promise(resolve=>pending.push({scope:args.scope,resolve})):Promise.resolve(url.endsWith('/books')?books:url.endsWith('/reimbursements')?structuredClone(facts):[]);
 const old=h.page.loadBookLedger();
 h.page.options.bookScope='project:22';
 if(transition==='company'){h.state.post.orgId='org-2';h.state.tenant='tenant-2';h.state.session++;}
 if(transition==='post'){h.state.post.postId='post-2';h.state.post.roleCode='finance-lead';}
 const next=h.page.loadBookLedger();assert.equal(h.page.data.bookEmployeeRows.length,0);
 facts=[second];await h.page.loadWorkbench();pending[1].resolve(employeeLedger('project:22',[second.payable_id]));await next;
 pending[0].resolve(employeeLedger('project:11',[first.payable_id]));await old;
 assert.equal(h.page.data.bookScope,'project:22');
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.id),[second.payable_id]);
 assert.equal(h.page.data.bookEmployeeRows[0].remaining,8000);
});

test('employee balances fail closed when the backend omits complete scoped IDs',async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.data.bookFlowTab='employee';h.page.options.bookScope='project:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 const first=employeeFacts[0],books=[{scope:'project:11',name:'兼容账本'}];
 const entry={id:first.origin_payment_id,type:'ADVANCE',date:'2026-11-27',amount_cents:10000,name:'原垫付',status:'已核实'};
 let ledger=employeeLedger('project:11',undefined,[entry]);delete ledger.reimbursementIds;
 h.http.get=async url=>url.endsWith('/ledger')?ledger:url.endsWith('/books')?books:url.endsWith('/reimbursements')?structuredClone(employeeFacts):[];
 const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]);
 const renderBook=()=>flat(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data));
 const hasText=value=>renderBook().map(n=>n.text||'').join('').includes(value);
 const findRetry=()=>renderBook().find(n=>n.tag==='button'&&n.attrs.bindtap==='loadBookLedger'&&n.children.some(child=>child.text==='重试'));
 await h.page.loadWorkbench();await h.page.loadBookLedger();
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.id),[]);
 assert.equal(h.page.data.bookResult.employeeScopeIncomplete,true);
 assert(hasText('员工往来完整范围暂不可用，已隐藏不完整数据'),'old backend must never present a partial advance list as complete');
 assert(!hasText('暂无可显示的近期员工往来')&&!hasText('暂无员工往来'),'an incomplete response must not look like a confirmed empty balance');
 const retry=findRetry();assert(retry,'show a real retry button when the complete scope is unavailable');
 ledger=employeeLedger('project:11',[],[entry]);
 await h.page[retry.attrs.bindtap]();assert.equal(h.page.data.bookEmployeeRows.length,0);
 assert.equal(h.page.data.bookResult.employeeScopeIncomplete,false);
 assert(!hasText('员工往来完整范围暂不可用'));assert(!findRetry());
 assert(hasText('暂无员工往来'));
});

for(const scenario of ['slow','failure','partial'])test(`project employee balances never report empty before scoped details are ready (${scenario})`, async()=>{
 const h=refreshHarness();h.page.data.view='book';h.page.data.bookFlowTab='employee';h.page.options.bookScope='project:11';h.page.onShow();await tick();attachReviewWorkbench(h);
 const facts=employeeFacts.slice(0,2), ids=facts.map(r=>r.payable_id),books=[{scope:'project:11',name:'项目A'}];
 let resolveDetails, detailCalls=0, ledgerCalls=0;
 h.http.get=async (url)=>{
  if(url.endsWith('/ledger')){ledgerCalls++;return employeeLedger('project:11',ids);}
  if(url.endsWith('/books'))return books;
  if(!url.endsWith('/reimbursements'))return [];
  if(++detailCalls>1)return structuredClone(facts);
  if(scenario==='failure')throw Error('weak network');
  if(scenario==='partial')return structuredClone(facts.slice(0,1));
  return new Promise(resolve=>{resolveDetails=resolve;});
 };
 const loading=h.page.loadWorkbench();await h.page.loadBookLedger();
 const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children)]), redraw=()=>flat(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),h.page.data));
 const hasText=value=>redraw().map(n=>n.text||'').join('').includes(value);
 assert(!hasText('暂无员工往来'),'a nonempty scope cannot be treated as no balances');
 assert.equal(h.page.data.bookEmployeeRows.length,0,'do not present a partial list as complete');
 if(scenario==='slow'){
  assert(hasText('正在读取员工往来'));
  assert(!redraw().some(n=>n.attrs?.bindtap==='retryBookEmployees'));
  resolveDetails(structuredClone(facts));await loading;
 }else{
  await loading;
  assert(hasText('员工往来明细暂未读全'));
  const retry=redraw().find(n=>n.tag==='button'&&n.attrs.bindtap==='retryBookEmployees');assert(retry);
  await h.page[retry.attrs.bindtap]();
  assert.equal(detailCalls,2);assert.equal(ledgerCalls,2);
 }
 assert.deepEqual(Array.from(h.page.data.bookEmployeeRows,r=>r.id),ids);
 assert(!hasText('暂无员工往来')&&!hasText('员工往来明细暂未读全'));
});
