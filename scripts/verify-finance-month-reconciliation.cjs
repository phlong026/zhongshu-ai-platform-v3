const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../miniprogram/pages/admin/finance-desk/index.js'),'utf8');
const {render}=require('./role-page-runtime.cjs');
const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children)).join('');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
function facts(overrides={}){return{grossReceiptsCents:100000,grossReceiptsCount:1,clientRefundsCents:0,clientRefundsCount:0,netReceivedCents:100000,companyPaidCents:20000,companyPaidCount:1,companyReturnedCents:0,companyReturnedCount:0,netCompanyPaidCents:20000,netCents:80000,employeeAdvanceCents:30000,employeeAdvanceCount:1,employeeReturnedCents:0,employeeReturnedCount:0,sourceSignature:'v1',...overrides};}
function harness({strictDate=false}={}){
 let page,session=1,post={roleCode:'finance',orgType:'store',orgName:'测试门店',postId:'post-1'},sequence=0;
 const calls={reads:[],writes:[],toasts:[]};
 const state={read:async period=>({period,current:facts(),confirmed:null,needsReview:false}),write:async(period,key,signature)=>({...facts(),period,sourceSignature:signature,byName:'财务甲',updateTime:'2026-11-23T03:00:00Z'})};
 const http={token:()=>true,currentUserId:()=> 'user-1',tenantId:()=> 'tenant-1',currentPostId:()=>post.postId,sessionGeneration:()=>session,requestGuard:()=>{const old=session;return()=>old===session;}};
 const scopeModule={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/utils/api-scope.js'),'utf8'),{module:scopeModule,require:n=>n.endsWith('/http')?http:{currentApiPost:()=>post}});
 const scope=scopeModule.exports;
 vm.runInNewContext(source,{Page:definition=>{page=definition;},require(request){
  if(request.endsWith('/finance-workbench-view'))return require('../miniprogram/utils/finance-workbench-view');
  if(request.endsWith('/finance-desk'))return {url:()=>''};
  if(request.endsWith('/access'))return {context:()=>({})};
  if(request.endsWith('/auth'))return {currentApiPost:()=>post};
  if(request.endsWith('/api/finance'))return {idemKey:()=>`test-${++sequence}`};
  if(request.endsWith('/api/http'))return http;
  if(request.endsWith('/api-scope'))return scope;
  if(request.endsWith('/profile/profile-page'))return()=>({onHide(){},onUnload(){}});
  if(request.endsWith('/finance-keyboard'))return{stop(){}};
  if(request.endsWith('/platform-finance'))return {errorMessage:error=>error.message||'读取失败'};
  if(request.endsWith('/franchise/performance'))return {operating:{reconciliation:period=>{calls.reads.push(period);return state.read(period);},reconcile:(...args)=>{calls.writes.push(args);return state.write(...args);}}};
  if(request.endsWith('/view-container'))return {routeValue:value=>value||''};
  return {};
 },wx:{showToast:options=>calls.toasts.push(options.title)},console,Date:strictDate?class extends Date{static parse(value){return /\.\d{4,}/.test(value)?NaN:Date.parse(value);}}:Date,Promise});
 page.data={...page.data,view:'flows',flowTab:'reconcile',selectedMonth:'2026-11',platformApiMode:true};
 page.setData=patch=>{for(const[key,value]of Object.entries(patch)){const keys=key.split('.');let target=page.data;for(const part of keys.slice(0,-1))target=target[part]||(target[part]={});target[keys.at(-1)]=value;}};
 page.syncFinanceIdentity(scope());
 return{page,calls,state,changePost(patch){post={...post,...patch};},switchIdentity(){session++;post={...post,postId:'post-2'};page.syncFinanceIdentity(scope());}};
}

test('company cash and employee advances are shown with matching counts; confirmation stays readable after reopening',async()=>{
 const h=harness();await h.page.loadMonthClosing();
 const rows=h.page.data.monthClosing.currentRows;
 assert.equal(rows.find(row=>row.label==='公司实付（含报销）').value,'200.00');
 assert.equal(rows.find(row=>row.label==='公司实付（含报销）').count,1);
 assert.equal(rows.find(row=>row.label==='员工垫付').value,'300.00');
 let saved;
 h.state.write=async(period,key,signature)=>(saved={...facts(),period,sourceSignature:signature,byName:'财务甲',updateTime:'2026-11-23T03:00:00Z'});
 h.state.read=async period=>({period,current:facts(),confirmed:saved||null,needsReview:false});
 await h.page.reconcileBook();await tick();
 assert.equal(h.calls.writes.length,1);assert.deepEqual(h.calls.writes[0],['2026-11','test-1','v1']);
 assert.equal(h.page.data.monthClosing.confirmed.byName,'财务甲');
 assert.equal(h.page.data.monthClosing.confirmedAt,'2026-11-23 11:00');
 const reopened=harness();reopened.state.read=h.state.read;await reopened.page.loadMonthClosing();
 assert.equal(reopened.page.data.monthClosing.confirmedRows.find(row=>row.label==='公司实付（含报销）').value,'200.00');
 await reopened.page.reconcileBook();assert.equal(reopened.calls.writes.length,0,'unchanged confirmed cash does not create a second confirmation');
});

test('later postings show new totals and a review marker without replacing the original confirmation',async()=>{
 const h=harness(),saved={...facts(),period:'2026-11',byName:'财务甲',updateTime:'2026-11-23T03:00:00Z'};
 h.state.read=async period=>({period,current:facts({companyPaidCents:40000,companyPaidCount:2,netCents:60000,sourceSignature:'v2'}),confirmed:saved,needsReview:true});
 await h.page.loadMonthClosing();
 assert.equal(h.page.data.monthClosing.needsReview,true);
 assert.equal(h.page.data.monthClosing.currentRows.find(row=>row.label==='公司实付（含报销）').value,'400.00');
 assert.equal(h.page.data.monthClosing.confirmedRows.find(row=>row.label==='公司实付（含报销）').value,'200.00');
 h.state.read=async()=>{throw new Error('读取超时');};await h.page.loadMonthClosing();
 assert.equal(h.page.data.monthClosing.status,'degraded');assert.equal(h.page.data.monthClosing.confirmed.byName,'财务甲');
 await h.page.reconcileBook();assert.equal(h.calls.writes.length,0);
});

test('failed reads block confirmation and retry the selected month without inventing zero amounts',async()=>{
 const h=harness();h.page.data.selectedMonth='2026-10';h.state.read=async()=>{throw new Error('网络断开');};
 await h.page.loadMonthClosing();await h.page.reconcileBook();
 assert.equal(h.calls.writes.length,0);assert.equal(h.page.data.monthClosing.status,'degraded');
 h.state.read=async period=>({period,current:facts(),confirmed:null,needsReview:false});
 await h.page.retryMonthClosing();assert.deepEqual(h.calls.reads,['2026-10','2026-10']);
 assert.equal(h.page.data.monthClosing.status,'ready');
});

test('uncertain confirmation retries the same reviewed signature and idempotency key',async()=>{
 const h=harness();await h.page.loadMonthClosing();
 h.state.write=async()=>{throw new Error('回执超时');};
 await h.page.reconcileBook();await h.page.reconcileBook();
 assert.equal(h.calls.writes.length,2);assert.deepEqual(h.calls.writes[0],h.calls.writes[1]);
 assert.equal(h.page.data.monthClosing.current.sourceSignature,'v1');
 assert.equal(h.page.data.busy,false);
});

test('month changes, account changes and disposed pages reject late read results',async()=>{
 const h=harness(),a=deferred(),b=deferred();h.state.read=period=>period==='2026-10'?a.promise:b.promise;
 h.page.data.selectedMonth='2026-10';const old=h.page.loadMonthClosing();
 h.page.data.selectedMonth='2026-11';const next=h.page.loadMonthClosing();await tick();
 b.resolve({period:'2026-11',current:facts(),confirmed:null});await next;
 a.resolve({period:'2026-10',current:facts({sourceSignature:'older'}),confirmed:null});await old;
 assert.equal(h.page.data.monthClosing.period,'2026-11');
 const account=deferred();h.state.read=()=>account.promise;const pending=h.page.loadMonthClosing();await tick();h.switchIdentity();
 account.resolve({period:'2026-11',current:facts(),confirmed:null});await pending;
 assert.equal(h.page.data.monthClosing,null);
 const closed=deferred();h.state.read=()=>closed.promise;const beforeClose=h.page.loadMonthClosing();await tick();h.page.__disposed=true;
 closed.resolve({period:'2026-11',current:facts(),confirmed:null});await beforeClose;assert.equal(h.page.data.monthClosing.status,'loading');
});

test('confirmation completed after switching identity cannot overwrite the new page or release its busy owner',async()=>{
 const h=harness(),write=deferred();await h.page.loadMonthClosing();h.state.write=()=>write.promise;
 const pending=h.page.reconcileBook();h.switchIdentity();const newer=h.page.beginFinanceBusy();
 write.resolve({...facts(),period:'2026-11',byName:'旧财务'});await pending;
 assert.equal(h.page.data.monthClosing,null);assert.equal(h.page.ownsFinanceBusy(newer),true);assert.equal(h.calls.toasts.length,0);
});

test('preview conflict refreshes current facts and asks for another explicit confirmation',async()=>{
 const h=harness();await h.page.loadMonthClosing();h.state.write=async()=>{throw{code:1116000001,message:'账有变化，请刷新核对后再确认'};};
 h.state.read=async period=>({period,current:facts({sourceSignature:'v2'}),confirmed:null,needsReview:false});
 await h.page.reconcileBook();await tick();
 assert.equal(h.calls.writes.length,1);assert.equal(h.page.data.monthClosing.current.sourceSignature,'v2');
 assert.equal(h.page.__reconcileRequest,null);
});

test('legacy confirmation amounts are not presented as new detailed company cash counts',async()=>{
 const h=harness();h.state.read=async period=>({period,current:facts(),confirmed:{period,legacy:true,incomeCents:100000,paidCents:20000,netCents:80000,receiptCount:1,paymentCount:2,byName:'原财务',updateTime:'2026-11-22T03:00:00Z'},needsReview:true});
 await h.page.loadMonthClosing();assert.equal(h.page.data.monthClosing.confirmedRows[1].label,'原确认净流出');
 assert.equal(h.page.data.monthClosing.confirmedRows[1].count,2);assert.equal(h.page.data.monthClosing.needsReview,true);
});

for(const newerConfirmation of [false,true])test(`late confirmation cannot overwrite a newer read or its review state (${newerConfirmation?'new confirmation':'new cash'})`,async()=>{
 const h=harness(),write=deferred();await h.page.loadMonthClosing();h.state.write=()=>write.promise;
 const pending=h.page.reconcileBook();
 const original={...facts(),period:'2026-11',byName:'财务甲',updateTime:'2026-11-23T03:00:00Z'};
 const newer={...facts({sourceSignature:'v2',netCents:60000}),period:'2026-11',byName:'财务乙',updateTime:'2026-11-23T04:00:00Z'};
 h.state.read=async period=>({period,current:facts({sourceSignature:'v2',netCents:60000}),confirmed:newerConfirmation?newer:original,needsReview:!newerConfirmation});
 await h.page.loadMonthClosing();
 h.state.read=async()=>{throw new Error('刷新超时');};
 write.resolve(original);await pending;await tick();
 assert.equal(h.page.data.monthClosing.status,'degraded');
 assert.equal(h.page.data.monthClosing.needsReview,!newerConfirmation);
 assert.equal(h.page.data.monthClosing.confirmed.byName,newerConfirmation?'财务乙':'财务甲');
 assert.equal(h.page.data.monthClosing.confirmed.sourceSignature,newerConfirmation?'v2':'v1');
});

test('an in-flight refresh cannot hide the newly confirmed receipt behind an older retained confirmation',async()=>{
 const h=harness(),write=deferred(),read=deferred();
 const old={...facts({sourceSignature:'v0'}),period:'2026-11',byName:'旧财务',updateTime:'2026-11-22T03:00:00Z'};
 h.state.read=async period=>({period,current:facts(),confirmed:old,needsReview:true});await h.page.loadMonthClosing();
 h.state.write=()=>write.promise;const pending=h.page.reconcileBook();
 h.state.read=()=>read.promise;const refresh=h.page.loadMonthClosing();await tick();
 h.state.read=async()=>{throw new Error('刷新超时');};
 write.resolve({...facts(),period:'2026-11',byName:'本次财务',updateTime:'2026-11-23T03:00:00Z'});await pending;await tick();
 assert.equal(h.page.data.monthClosing.status,'degraded');assert.equal(h.page.data.monthClosing.confirmed.sourceSignature,'v1');
 assert.equal(h.page.data.monthClosing.confirmed.byName,'本次财务');assert.equal(h.page.data.monthClosing.needsReview,false);
 read.resolve({period:'2026-11',current:facts(),confirmed:old,needsReview:true});await refresh;
 assert.equal(h.page.data.monthClosing.confirmed.byName,'本次财务');
});

test('server confirmation order keeps microseconds even on engines accepting only three ISO fraction digits',async()=>{
 const h=harness({strictDate:true}),write=deferred();await h.page.loadMonthClosing();h.state.write=()=>write.promise;const pending=h.page.reconcileBook();
 h.state.read=async period=>({period,current:facts({sourceSignature:'v2'}),confirmed:{...facts({sourceSignature:'v2'}),period,byName:'较新财务',updateTime:'2026-11-23T03:00:00.123900Z'},needsReview:false});
 await h.page.loadMonthClosing();h.state.read=async()=>{throw new Error('刷新超时');};
 write.resolve({...facts(),period:'2026-11',byName:'较旧财务',updateTime:'2026-11-23T03:00:00.123100Z'});await pending;await tick();
 assert.equal(h.page.data.monthClosing.confirmed.byName,'较新财务');assert.equal(h.page.data.monthClosing.needsReview,false);
});


test('month closing keeps confirmed and current amounts mounted through a coalesced refresh and ordinary read failure',async()=>{
 const h=harness(),saved={...facts(),period:'2026-11',byName:'财务甲',updateTime:'2026-11-23T03:00:00Z'};
 h.state.read=async period=>({period,current:facts(),confirmed:saved,needsReview:true});await h.page.loadMonthClosing();
 const before=JSON.stringify([h.page.data.monthClosing.currentRows,h.page.data.monthClosing.confirmedRows]),pending=deferred();h.state.read=()=>pending.promise;
 const first=h.page.loadMonthClosing(),second=h.page.loadMonthClosing();await tick();assert.equal(h.calls.reads.length,2,'same query shares its pending month read');
 const html=text(render(path.join(__dirname,'../miniprogram/pages/admin/finance-desk/month-reconciliation.wxml'),h.page.data));
 assert(html.includes('员工垫付')&&html.includes('¥300.00'));assert(html.includes('更新中'));assert.equal(h.page.data.monthClosing.status,'loading');await h.page.reconcileBook();assert.equal(h.calls.writes.length,0);
 pending.reject({status:500,message:'offline'});await Promise.all([first,second]);assert.equal(JSON.stringify([h.page.data.monthClosing.currentRows,h.page.data.monthClosing.confirmedRows]),before);
 assert(text(render(path.join(__dirname,'../miniprogram/pages/admin/finance-desk/month-reconciliation.wxml'),h.page.data)).includes('更新失败'));await h.page.reconcileBook();assert.equal(h.calls.writes.length,0);
});
test('month closing clears both snapshots for each explicit denial representation',async()=>{
 for(const error of [{code:401},{code:403},{status:401},{status:403},{statusCode:401},{statusCode:403},{code:1103000002},{postInvalid:true}]){
  const h=harness();h.state.read=async period=>({period,current:facts(),confirmed:{...facts(),byName:'财务甲'},needsReview:true});await h.page.loadMonthClosing();
  h.state.read=async()=>{throw error;};await h.page.loadMonthClosing();const result=h.page.data.monthClosing;
  assert(!result.current&&!result.confirmed,error);assert.equal((result.currentRows||[]).length,0);assert.equal((result.confirmedRows||[]).length,0);assert.equal(h.page.data.platformRegister,null);
 }
});
test('a same-post permission change invalidates existing and late month amounts without a new onShow',async()=>{
 for(const change of [{roleCode:'finance-lead'},{orgType:'headquarters'},{permissions:['changed:scope']}]){
  const h=harness();await h.page.loadMonthClosing();const pending=deferred();h.state.read=()=>pending.promise;const read=h.page.loadMonthClosing();await tick();h.changePost(change);
  pending.resolve({period:'2026-11',current:facts({sourceSignature:'old-scope'}),confirmed:null});await read;assert.equal(h.page.data.monthClosing,null);
 }
});
test('returning after hide starts a new month read and rejects an older denial',async()=>{
 const h=harness();await h.page.loadMonthClosing();const old=deferred(),next=deferred();h.state.read=()=>old.promise;const first=h.page.loadMonthClosing();await tick();h.page.onHide();h.page.__financeHidden=false;
 h.state.read=()=>next.promise;const second=h.page.loadMonthClosing();await tick();assert.equal(h.calls.reads.length,3);
 next.resolve({period:'2026-11',current:facts({sourceSignature:'new'}),confirmed:null});await second;old.reject({statusCode:403});await first;assert.equal(h.page.data.monthClosing.current.sourceSignature,'new');
});

test('month closing never treats an unread or mismatched pending-funds count as confirmed',async()=>{
 const h=harness();await h.page.loadMonthClosing();
 const template=path.join(__dirname,'../miniprogram/pages/admin/finance-desk/month-reconciliation.wxml');
 const display=(reconciliation,financeRefreshing=false)=>text(render(template,{...h.page.data,reconciliation,financeRefreshing}));
 const stale={status:'ready',month:'2026-10',abnormalCount:5};
 const loading=display(stale,true);
 assert(!loading.includes('5笔资金待关联'));
 assert(loading.includes('待关联资金正在核对'));
 assert(loading.includes('仅确认已入账收支'));
 const failed=display({status:'ready',month:'2026-11',abnormalCount:5,message:'读取失败'});
 assert(!failed.includes('5笔资金待关联'));
 assert(failed.includes('待关联资金还没核对'));
 const ready=display({status:'ready',month:'2026-11',abnormalCount:2});
 assert(ready.includes('全公司还有 2 笔资金待关联'));
 assert(!ready.includes('还没核对'));
 const zero=display({status:'ready',month:'2026-11',abnormalCount:0});
 assert(!zero.includes('还没核对'));
});
