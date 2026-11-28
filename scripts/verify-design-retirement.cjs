const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const policy=require('../miniprogram/utils/design-retirement');
function load(file,requireFn,extra={}){const box={module:{exports:{}},require:requireFn,...extra};vm.runInNewContext(fs.readFileSync(file,'utf8'),box);return box.module.exports;}
test('old workflow AI links show a safe customer destination without running a retired form',()=>{
 const file=path.resolve('miniprogram/pages/workflow/index.js'),real=require('node:module').createRequire(file);
 let definition;const routes=[],calls=[];
 const http={token:()=> 'session',sessionGeneration:()=>1,get:(...args)=>calls.push(args),post:(...args)=>calls.push(args)};
 const post={postId:'11',userId:'22',orgId:'33',orgType:'store',roleCode:'sales'};
 load(file,id=>id==='../../api/http'?http:id==='../../api/auth'?{currentApiPost:()=>post}:id==='../../utils/page-navigation'?{open:url=>routes.push(url)}:real(id),{Page:p=>definition=p,wx:{}});
 const {render}=require('./role-page-runtime.cjs');
 const text=nodes=>nodes.map(n=>(n.text||'')+text(n.children||[])).join('');
 for(const view of ['ai-config','ai-results','result','facade-config','facade-candidates','facade-result','inspiration','case-plan']){
  const page={...definition,data:structuredClone(definition.data),setData:patch=>Object.assign(page.data,patch)};
  page.onLoad({view,id:'90071992547409973'});
  assert.equal(page.data.view,'customers');assert.equal(page.data.customer,null);
  const copy=text(render(file.replace(/\.js$/,'.wxml'),page.data));
  assert.match(copy,/旧入口已停用/);assert.match(copy,/返回客户列表/);
  assert.doesNotMatch(copy,/生成方案|生成立面|完成配置/);
  page.go({currentTarget:{dataset:{view:page.data.blocked.target}}});
  assert.equal(routes.pop(),'/pages/workflow/index?view=customers');
 }
 assert.deepEqual(calls,[]);
});

test('historical commission readers cannot commit local retired workflow commands',()=>{
 const history=require('../miniprogram/utils/commission-history');
 const rules=require('../miniprogram/utils/business-rules-v2');
 const before={id:'historical-customer',businessFlow:{type:'sales-offline-v1',events:[]}},snapshot=structuredClone(before);
 for(const action of history.actions)assert.throws(()=>rules.validate(before,{...before,name:'changed'},action),/在线原单/);
 assert.deepEqual(before,snapshot);
 assert.equal(typeof history.packageRows,'function');assert.equal(typeof history.paymentAuthorization,'function');
 assert.equal(history.run,undefined);assert.equal(history.transition,undefined);assert.equal(history.savePolicy,undefined);
});

test('AI drawing writes stop before transport while budget projects, estimates, versions and cancellation stay available',async()=>{
 const calls=[];const http={post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const api=load('miniprogram/api/franchise/design.js',id=>id.includes('design-retirement')?policy:http);
 for(const name of ['selectFlat','selectElevation','createFlatJob','createElevationJob','revision','notePolish'])await assert.rejects(api.projects[name]('7',{},'old-key'),e=>e.code===1112100004);
 assert.equal(calls.length,0);
 await api.projects.create({sourceType:'SELF_UPLOAD'},'project');await api.budget.estimate('7',{buildingArea:240},'budget');
 await api.projects.resultVersions('7');await api.projects.cancelJob('7');
 assert.deepEqual(calls.map(c=>c[0]),['/franchise/design/projects','/franchise/design/projects/7/budget-estimates','/franchise/design/projects/7/result-versions','/franchise/design/ai-jobs/7/cancellation-requests']);
 assert.equal(calls[1][2]['Idempotency-Key'],'budget');
});
test('migrated request bridge rejects generation before sending a generic flow request',async()=>{
 const calls=[];const platform={token:()=>true,sessionGeneration:()=>1,post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const bridge=load('miniprogram/design-migration/utils/request.js',id=>id.includes('design-retirement')?policy:id==='../../api/http'?platform:id==='./context'?{scope:()=> 'company:1',getApp:()=>({catalogAssets:{}})}:{});
 for(const p of ['/design-projects/7/flat-jobs','/design-projects/7/elevation-jobs','/design-projects/7/revision-requests','/ai-design/note-polish'])await assert.rejects(bridge.post('/app-api/design/v1'+p,{}),e=>e.code===1112100004);
 await assert.rejects(bridge.get('/app-api/design/v1/generation-price-quotes?stage=FLAT'),e=>e.code===1112100004);assert.equal(calls.length,0);
 for(const suffix of ['flat-selections','elevation-selections'])await assert.rejects(bridge.post('/app-api/design/v1/design-projects/7/'+suffix,{}),e=>e.code===1112100004);
 assert.equal(calls.length,0);
 for(const [method,path] of [
   ['post','/budget-drafts'],['put','/budget-drafts/7'],['post','/budget-drafts/7/estimates'],
   ['post','/design-projects/7/budget-estimates/itemized'],['post','/budget-estimates/8/save']
 ])await bridge[method]('/app-api/design/v1'+path,{}, {'Idempotency-Key':'budget-key'});
 assert.equal(calls.length,5);assert.equal(calls[2][1].path,'/budget-drafts/7/estimates');assert.equal(calls[2][2]['Idempotency-Key'],'budget-key');
 for(const path of ['/design-projects/7/result-versions','/budget-estimates/8?revisionId=9','/budget-estimates/independent'])
   await bridge.get('/app-api/design/v1'+path);
 assert.equal(calls.length,8);
});
test('direct drawing page deep links redirect before showing or running page handlers',()=>{
 let page;const routes=[];const access=load('miniprogram/design-migration/utils/access.js',id=>id.endsWith('/http')?{token:()=>true}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{pageMode:()=> 'api'},{Page:d=>page=d,wx:{redirectTo:o=>routes.push(o.url)}});
 let calls=0;access.protectedPage({data:{},onLoad(){calls++;},submit(){calls++;}});page.route='design-migration/pages/ai-design/index';page.setData=p=>Object.assign(page.data,p);page.onLoad();page.submit();assert.equal(calls,0);assert.equal(page.data.accessReady,false);assert.deepEqual(routes,['/design-migration/pages/library/index']);
});
test('authorized sales can open every budget editor and save through its guarded handlers',()=>{
 for(const editor of ['quick','input','parameters','prices','body','exterior']){
   let page;let called=0;const routes=[];
   const access=load('miniprogram/design-migration/utils/access.js',id=>id.endsWith('/http')?{token:()=>true}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{pageMode:()=> 'api'},{Page:d=>page=d,wx:{redirectTo:o=>routes.push(o.url)}});
   access.protectedPage({data:{},onLoad(){called++;},save(){called++;}});
   page.route='design-migration/pages/budget/'+editor;page.setData=p=>Object.assign(page.data,p);
   page.onLoad({projectId:'70'});page.save();assert.equal(called,2);assert.equal(page.data.accessReady,true);assert.deepEqual(routes,[]);
 }
});
test('reference library keeps preview, independent quick budget entry and no AI drawing action',()=>{
 const s=fs.readFileSync('miniprogram/design-migration/pages/library/detail.wxml','utf8');assert.match(s,/previewLibraryImage/);assert.match(s,/bindtap="quickBudget"/);assert.doesNotMatch(s,/bindtap="useHouse"/);
});

test('historical generation page cannot offer or dispatch another paid drawing',()=>{
 let page;const toasts=[];
 load('miniprogram/design-migration/pages/ai-design/generating.js',id=>id==='../../utils/access'?{protectedPage:p=>page=p}:id==='../../utils/context'?{getApp:()=>({globalData:{}})}:id==='../../utils/generation-options'?{selection:()=>({})}:{},{wx:{showToast:o=>toasts.push(o.title)}});
 page.regenerate();assert.deepEqual(toasts,['请在客户跟进中上传外部设计成果']);
 assert.doesNotMatch(page.leavePage.toString(),/ai-design\/index/);
 const template=fs.readFileSync('miniprogram/design-migration/pages/ai-design/generating.wxml','utf8');assert.doesNotMatch(template,/bindtap="regenerate"/);assert.match(template,/bindtap="retryCandidates"/);assert.match(template,/bindtap="cancel"/);
});

test('legacy entry restores the fixed result after login and guards other roles',()=>{
 let page;const routes=[];let token='',role='sales';
 load('miniprogram/pages/workflow/explore/index.js',id=>id.endsWith('/http')?{token:()=>token}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:role})}:require('../miniprogram/utils/platform-design'),{Page:p=>page=p,wx:{redirectTo:o=>routes.push(o.url)}});
 page.setData=()=>{};page.onLoad({view:'plan-result',projectId:'90071992547409973',resultVersionId:'901'});
 const expected='/pages/workflow/explore/index?view=plan-result&projectId=90071992547409973&resultVersionId=901';
 assert.equal(routes.pop(),'/pages/account/index?view=login&return='+encodeURIComponent(expected));
 token='active';role='engineer';page.onShow();assert.match(routes.pop(),/account\/index\?view=access/);
 role='sales';page.onLoad({view:'plan-result',projectId:'90071992547409973',resultVersionId:'901'});
 assert.equal(routes.pop(),'/design-migration/pages/ai-design/result?projectId=90071992547409973&resultVersionId=901');
});

test('quick budget refuses a paid server, then calculates free and retries the original request',async()=>{
 const scope='company:11:user:22'; const prices=[],writes=[],routes=[],storage=new Map();
 const price={subject:'budget_estimate',quantity:'1',credits:'0',priceVersion:'budget-free-v1'};
 const result={budgetId:'90071992547409971',draftId:'90071992547409972',projectId:null};
 let free=false,dropResponse=true,page;
 const wx={
   getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key),
   showModal:()=>assert.fail('免费测算不应出现收费确认'),
   navigateTo:options=>{routes.push(options.url);options.success();}
 };
 const platform={
   token:()=>'store-session',sessionGeneration:()=>3,
   get:async(path,query)=>{prices.push([path,query.subject,query.quantity]);return free ? price : {...price,credits:7,priceVersion:"company-v2"};},
   post:async(path,body,headers)=>{
     assert.equal(path,'/franchise/design/flow');writes.push([body,headers]);
     if(dropResponse){dropResponse=false;throw {message:'response interrupted'};}
     return result;
   }
 };
 const context={scope:()=>scope,getApp:()=>({catalogAssets:{}})};
 const request=load('miniprogram/design-migration/utils/request.js',id=>id.includes('design-retirement')?policy:id.endsWith('/http')?platform:context);
 const api=load('miniprogram/design-migration/utils/api.js',()=>request);
 const drafts={sessionScope:()=>scope,id:value=>String(value)};
 const customer={bind:(kind,id,customerId)=>assert.deepEqual([kind,id,customerId],['budget',result.budgetId,'31']),suffix:id=>'&customerId='+id};
 load('miniprogram/design-migration/pages/budget/quick.js',id=>({
   '../../utils/access':{protectedPage:definition=>page=definition},'../../utils/context':context,'../../utils/api':api,
   '../../utils/budget-draft':drafts,'../../utils/budget-free':require('../miniprogram/design-migration/utils/budget-free'),'../../utils/customer-context':customer,'../../utils/budget-view':{errorText:error=>error.msg}
 }[id]||{}),{wx});
 Object.assign(page,{_scope:scope,_seq:1,_catalogRegion:'WUHAN',_sourceScope:scope,_sourceCustomer:'31',
   setData:patch=>Object.assign(page.data,patch)});
 Object.assign(page.data,{draft:{draftId:result.draftId,version:2},values:{regionCode:'WUHAN'},catalogReady:true});
 await page.generate();assert.equal(writes.length,0);assert.equal(routes.length,0);
 free=true;await page.generate();assert.equal(writes.length,1);assert.equal(routes.length,0);
 await page.generate();assert.equal(writes.length,2);assert.deepEqual(prices,Array(3).fill(['/franchise/design/flow-price','budget_estimate',1]));
 assert.equal(writes[0][0].path,'/budget-drafts/'+result.draftId+'/estimates');
 assert.equal(writes[0][0].body.expectedVersion,2);assert.equal(writes[0][0].body.companyPrice,undefined);assert.deepEqual(writes[1][0],writes[0][0]);
 assert.equal(writes[1][1]['Idempotency-Key'],writes[0][1]['Idempotency-Key']);assert.ok(writes[0][1]['Idempotency-Key']);
 assert.deepEqual(routes,['/design-migration/pages/budget/result?budgetId='+result.budgetId+'&customerId=31']);assert.equal(storage.size,0);
});

test('project budget sends only free new requests and preserves an interrupted legacy receipt', async () => {
 const scope='company:11:user:22', body={regionCode:'WUHAN'}, oldPrice={subject:'budget_estimate',quantity:1,credits:7,priceVersion:'old'};
 let saved={projectId:'91',resultVersionId:'92',companyPrice:oldPrice}, page, enabled=false;
 const writes=[], routes=[];
 const requestBody=draft=>draft.companyPrice?{...body,companyPrice:draft.companyPrice}:{...body};
 const drafts={
   read:()=>saved, write:value=>saved=value, requestBody, sessionScope:()=>scope, id:String,
   prepareGeneration(draft){
     const req=requestBody(draft), signature=JSON.stringify(req);
     const key=draft.generationAttempt?.signature===signature?draft.generationAttempt.key:'fresh-free-key';
     return {key,body:req,draft:{...draft,generationAttempt:{signature,key}}};
   }
 };
 const api={
   getBudgetPointQuote:async()=>({companyPrice:enabled?{...oldPrice,credits:0,priceVersion:'budget-free-v1'}:oldPrice}),
   createItemizedBudget:async(...args)=>{writes.push(args);return {budgetId:'93'};}
 };
 load('miniprogram/design-migration/pages/budget/input.js',id=>({
   '../../utils/access':{protectedPage:definition=>page=definition}, '../../utils/api':api,
   '../../utils/budget-draft':drafts, '../../utils/budget-free':require('../miniprogram/design-migration/utils/budget-free'),
   '../../utils/customer-context':{read:()=>'',bind(){},suffix:()=>''}
 }[id]||{}),{wx:{showModal:()=>assert.fail('不应显示收费确认'),
   navigateTo:options=>{routes.push(options.url);options.success();}}});
 Object.assign(page,{_sessionScope:scope,currentSession:()=>true,renderDraft(){},setData:patch=>Object.assign(page.data,patch)});
 Object.assign(page.data,{draft:saved,canGenerate:true});
 const calculate=async()=>{page.generate();await new Promise(resolve=>setImmediate(resolve));};
 await calculate();
 assert.deepEqual(writes,[]);
 enabled=true;
 await calculate();
 assert.deepEqual(writes[0],['91',body,'fresh-free-key']);
 assert.equal(saved.companyPrice,null);
 saved={...saved,companyPrice:oldPrice,generationAttempt:{key:'old-paid-key',signature:JSON.stringify({...body,companyPrice:oldPrice})}};
 await calculate();
 assert.deepEqual(writes[1],['91',{...body,companyPrice:oldPrice},'old-paid-key']);
 assert.equal(routes.length,2);
});
