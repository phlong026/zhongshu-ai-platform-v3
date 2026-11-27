const automator=require('../node_modules/miniprogram-automator');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const out=process.env.DESIGN_UI_ARTIFACTS||path.resolve('docs/design-ui-20261116/result');fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 await delay(2500);
 const mp=await automator.connect({wsEndpoint:process.env.WECHAT_AUTOMATOR_WS||'ws://127.0.0.1:9948'}),results=[];
 const invoke=async(name,arg)=>{console.log('invoke',name);await mp.evaluate((n,a)=>{const p=getCurrentPages().slice(-1)[0];setTimeout(()=>{const result=p[n](a);if(result&&result.catch)result.catch(e=>console.error(e));},300);return true;},name,arg);console.log("evaluated",name);await delay(1200);};
 const waitRoute=async(route)=>{await delay(1500);const deadline=Date.now()+15000;while(Date.now()<deadline){const p=await mp.currentPage();if(p.path===route)return p;await delay(150);}throw Error('Navigation did not reach '+route);};
 await mp.evaluate(()=>{globalThis.__designUiStorage=Object.fromEntries(wx.getStorageInfoSync().keys.map(key=>[key,wx.getStorageSync(key)]));});
 let mockedRequest=false, mockedModal=false;
 try{
 results.push=(...items)=>{console.log(...items);return Array.prototype.push.apply(results,items);};
 const capture=mp.screenshot.bind(mp);mp.screenshot=async o=>{console.log('capture',path.basename(o.path));await capture(o);console.log('captured');};
 await mp.mockWxMethod('showModal',function(){return {confirm:true,cancel:false};});mockedModal=true;
 await mp.mockWxMethod('request',function(options){
  const state=globalThis.__designParity||(globalThis.__designParity={drafts:[],requests:[],favorites:[],next:500});
  const route=String(options.url).split('/admin-api')[1]?.split('?')[0]||'';
  const q={};(String(options.url).split('?')[1]||'').split('&').filter(Boolean).forEach(pair=>{const a=pair.split('=');q[decodeURIComponent(a[0])]=decodeURIComponent(a[1]||'');});
  const input=options.data||{};state.requests.push({route,method:options.method,body:input,headers:Object.assign({},options.header,options.header&&options.header.Authorization?{Authorization:"Bearer native-test-session"}:{})});
  if(options.method==='GET')Object.assign(q,input);
  const summary=id=>({caseId:String(id),caseVersionId:'551',title:'完整图纸案例'+id,sourceType:'COMPANY',styleCode:'MODERN',coverAssetId:'601',floorCount:2,buildingArea:240,faceWidth:12,depth:10,allowedActions:['VIEW','FAVORITE','DESIGN_WITH']});
  const detail={...summary(501),dimensionBasis:'SITE',description:'原生测试用例',parameters:{styleCode:'MODERN',floorCount:2,buildingArea:240,faceWidth:12,depth:10},floorPlanAssetIds:['602','603'],floorPlans:[{assetId:'602',floorNo:1},{assetId:'603',floorNo:2}],elevationAssetId:'604'};
  const budget={budgetId:'901',revisionId:'902',model:'ITEMIZED_V1',projectId:null,draftId:state.drafts[0]?.draftId||'501',projectName:'独立预算',schemeName:'参考预算',regionName:'武汉',inputSummary:{regionCode:'WUHAN',footprintArea:120,floorCount:2,buildingArea:240,roofArea:120},completeness:state.completeBudget?'COMPLETE':'INCOMPLETE',pricedSubtotalCents:41020000,totalCents:state.completeBudget?41020000:null,categoryTotals:{BODY:32400000,EXTERIOR:8620000},items:[],missingItems:['基础形式待选择'],warnings:[],disclaimer:'前期参考，最终以正式报价为准',createdAt:'2026-11-16T00:00:00Z',saved:false};
  const version={versionId:'81',version:1,superseded:false,selectedFlatAssetId:'602',selectedElevationAssetId:null,flatImageInfo:{resolution:'2K',orientation:'PORTRAIT',width:1536,height:2048},configSnapshot:{},createdAt:'2026-11-16T00:00:00Z'};
  let data={};
  if(route==='/__design_ui_complete'){state.completeBudget=true;data=true;}
  else if(route==='/__design_parity_reset'){state.drafts=[];state.favorites=[];state.requests=[];state.next=Math.floor(Date.now()/10);data=true;}
  else if(route==='/__design_parity_trace')data=state.requests;
  else if(route==='/platform/posts/mine')data=[{postId:'99',userId:'22',orgId:'119',roleCode:'sales-lead',orgName:'测试公司'}];
  else if(route==='/franchise/design/cases/styles')data=[{code:'NEW_CHINESE',label:'新中式'},{code:'MODERN',label:'现代'},{code:'CHINESE',label:'中式'},{code:'EUROPEAN',label:'欧式'},{code:'RURAL',label:'田园'},{code:'CUSTOM_STYLE',label:'后台新增风格'}];
  else if(route==='/franchise/design/cases')data={list:[summary(501),summary(502)],nextCursor:null};
  else if(route==='/franchise/company-points/summary')data={balance:1000,inFlight:0,status:'ACTIVE'};
  else if(route==='/franchise/design/flow-price')data={subject:q.subject,quantity:Number(q.quantity),credits:q.subject==='budget_estimate'?0:Number(q.quantity)*6,priceVersion:q.subject==='budget_estimate'?'budget-free-v1':'native-price-v1'};
  else if(route==='/franchise/design/flow-upload-ticket')data={assetId:input.assetType==='USER_SKETCH'?'710':'711',uploadUrl:'https://upload.example.test/parity.jpg',method:'PUT'};
  else if(/^\/franchise\/design\/assets\/\d+\/complete$/.test(route))data={uploadStatus:'ACCEPTED'};
  else if(route.includes('/download-tickets'))data={downloadUrl:'https://images.example.test/fixture.jpg'};
  else if(route.startsWith('/franchise/design/flow-ai-jobs/'))data={jobId:'71',projectId:'61',status:'PARTIALLY_SUCCEEDED',phase:'FLAT',requestedCount:2,acceptedCount:1,progress:100,imageOptions:{resolution:'2K',orientation:'PORTRAIT'},allowedActions:[],totalPointCost:12,refundedPointCost:6,netPointCost:6,billingFinalized:true};
  else if(route==='/franchise/customers/701/commission-context')data={customerId:'701',commissionId:null,latestBudget:{version:1,width:12,depth:10,floors:2,area:240,payload:'{}'},feeOffer:{mode:'AREA_FULL',ruleVersion:1,area:240,rateCents:10000,totalCents:2400000,firstCents:0,tailCents:0,scope:'建筑方案设计',recipient:'总部设计服务中心'},commission:null,allowedActions:['commission-submit']};
  else if(route==='/franchise/customers/page'||route==='/franchise/customers')data={list:[{id:'701',name:'原生测试客户',phone:'13800000000'}],total:1};
  else if(route.includes('/save-to-customer')||route==='/franchise/design/flow-budget-archive')data={schemeId:'801',version:1};
  else if(route==='/franchise/design/flow'){
   const p=input.path,b=input.body||{},query=input.query||{};
   if(p==='/cases')data={list:query.cursor?[summary(503)]:[summary(501),summary(502)],nextCursor:query.cursor?null:'page-2'};
   else if(p==='/cases/styles')data=[{code:'MODERN',label:'现代'}];
   else if(p==='/cases/501')data=detail;
   else if(p.endsWith('/favorite')){state.favorites=input.method==='PUT'?[summary(501)]:[];data=true;}
   else if(p==='/favorites')data={list:state.favorites,nextCursor:null};
   else if(p.includes('generation-candidate'))data=p.endsWith('/generation-candidates')?{list:[detail],candidates:[detail]}:detail;
   else if(p==='/design-projects')data=input.method==='GET'?{list:[],nextCursor:null}:{projectId:'61',allowedActions:['GENERATE_FLAT']};
   else if(p.endsWith('/result-versions'))data={list:[version]};
   else if(p.endsWith('/flat-selections'))data={projectId:'61',resultVersionId:'81',selectionSaved:true,flatDeliveryCreated:true};
   else if(p==='/design-projects/61')data={projectId:'61',stage:'FLAT',line:'FLAT',resolution:'2K',orientation:'PORTRAIT',candidates:[{candidateId:'72',jobId:'71',assetId:'602',slotNo:1}],allowedActions:['SELECT_FLAT','CREATE_FLAT_JOB']};
   else if(p==='/budget/regions')data=[{code:'WUHAN',name:'武汉'}];
   else if(p==='/budget/my-regions'||p==='/budget/my-prices')data=[];
   else if(p==='/budget/options')data={items:[
    ['FOUNDATION','地基基础','BODY',[['DEFAULT','条形基础']]],['STRUCTURE','主体结构','BODY',[['DEFAULT','砖混结构']]],['ROOF','屋面结构','BODY',[['DEFAULT','木屋面']]],
    ['DECORATION','装饰构件','EXTERIOR',[['DEFAULT','现代']]],['DOORS_WINDOWS','门窗','EXTERIOR',[['DOOR','全铜门'],['WINDOW','1.4壁厚']]],
    ['WALL_PAINT','外墙漆','EXTERIOR',[['DEFAULT','真石漆']]],['CULTURE_STONE','文化石','EXTERIOR',[['DEFAULT','页岩陶粒']]],
    ['LIGHTING','灯具','EXTERIOR',[['STRIP','灯带'],['WALL_LAMP','壁灯']]],['WATERPROOF_LIGHTNING','防水防雷','EXTERIOR',[['WATERPROOF','雨虹防水'],['LIGHTNING','防雷']]],['INSURANCE','保险','EXTERIOR',[['DEFAULT','保险']]]
   ].map((row,index)=>({itemId:String(100+index),code:row[0],name:row[1],category:row[2],source:'STANDARD',options:row[3].map((o,j)=>({optionId:String(200+index*10+j),code:o[0],label:o[1],selectionGroup:o[0],availability:'AVAILABLE'}))}))};
   else if(p==='/budget-drafts'){
    if(input.method==='GET')data=state.drafts;
    else {data={draftId:String(++state.next),version:1,name:'独立预算'+state.next,state:{inputOverrides:{},optionIds:[]}};state.drafts.unshift(data);}
   }else if(/^\/budget-drafts\/\d+$/.test(p)){
    const draft=state.drafts.find(d=>d.draftId===p.split('/')[2]);
    if(input.method==='PUT'){draft.state=b.state;draft.version++;}data=draft;
   }else if(p.endsWith('/estimates'))data={...budget,draftId:p.split('/')[2]};
   else if(p==='/budget-estimates/independent')data={list:[budget],nextCursor:null};
   else if(p==='/budget-estimates/901')data=budget;
   else if(p.endsWith('/quotes'))data=[];
   else if(p.endsWith('/save'))data={budgetId:'901',saved:true};
   else throw Error('Unmocked migrated resource '+input.method+' '+p);
  }
  return {statusCode:200,data:{code:0,data},header:{}};
 });
 mockedRequest=true;
 await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_parity_reset',method:'GET'});
 await mp.evaluate(()=>{wx.setStorageSync('zs-api-token-v1','native-mocked-'+Date.now());wx.setStorageSync('zs-api-post-v1','99');wx.setStorageSync('zs-api-posts-v1',[{postId:'99',userId:'22',orgId:'119',roleCode:'sales-lead',orgName:'测试公司'}]);});

 let page=await mp.reLaunch('/design-migration/pages/budget/quick');await delay(800);let data=await page.data();assert(data.draft);const draftId=data.draft.draftId;
 page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(1000);data=await page.data();assert.equal(data.estimate.totalCents,null);assert.equal(data.amountText,'41.02');
 assert.equal((await page.$('.amount-label')).text ? await (await page.$('.amount-label')).text() : '', '预算小计');
 assert.equal((await page.$$('.result-tab')).length,4);assert.equal((await page.$$('.category-icon')).length,2);assert.equal((await page.$$('.precision-action')).length,0);assert.equal((await page.$$('.result-note-card')).length,0);
 await mp.screenshot({path:path.join(out,'budget-result.png')});await mp.pageScrollTo(1000);await delay(700);await mp.screenshot({path:path.join(out,'budget-result-actions.png')});
 await (await page.$('.result-tab')).tap();await delay(700);assert.equal((await page.data()).estimate.saved,true);results.push('预算小计、删冗余文字、分类图标、四个底部操作及保存预算');
 const send=await page.$$('.result-tab');await send[1].tap();page=await waitRoute('design-migration/pages/customer/archive');await delay(500);assert.equal((await page.$$('.archive-hint')).length,0);await (await page.$('.customer')).tap();await delay(500);await mp.screenshot({path:path.join(out,'send-customer.png')});await (await page.$('.archive-footer button')).tap();await delay(700);assert.equal((await page.data()).saved,true);results.push('发送至客户：标题与说明清理、客户选择、固定预算版本归档');
 page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(700);await invoke('commission');page=await waitRoute('design-migration/pages/customer/archive');assert.equal((await page.data()).forCommission,true);await invoke('choose',{currentTarget:{dataset:{id:'701'}}});await invoke('save');page=await waitRoute('pages/workflow/commission/index');data=await page.data();assert.equal(data.title,'设计委托');assert.equal(data.budgetReady,true);assert.equal(data.customer.name,'原生测试客户');assert.equal(data.rows[0].value,'待发起委托');await mp.screenshot({path:path.join(out,'commission-entry.png')});results.push('发起设计委托：先归档所选客户预算，随后进入现有委托表单，不自动提交');
 page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(700);await invoke('history');page=await waitRoute('design-migration/pages/budget/history');results.push('四个标签中的预算历史进入原历史列表');
 page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(700);await invoke('adjust');page=await waitRoute('design-migration/pages/budget/quick');assert.equal((await page.data()).draft.draftId,draftId);results.push('重新测算返回原草稿，不创建另一套预算');
 await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_ui_complete',method:'GET'});page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(800);assert.equal((await page.data()).estimate.completeness,'COMPLETE');assert.equal((await page.$$('.export-link')).length,1);await mp.screenshot({path:path.join(out,'complete-result.png')});results.push('完整预算保留预算图导出入口');
 const trace=await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_parity_trace',method:'GET'});const requests=trace.data.data;
 assert(!requests.some(r=>r.method==='POST'&&r.route.startsWith('/franchise/customers/')&&r.route.endsWith('/commission')),'Opening the form must never submit a commission');
 const archives=requests.filter(r=>r.route==='/franchise/design/flow-budget-archive');assert.equal(archives.length,2);assert(archives.every(r=>r.body.customerId==='701'&&r.body.budgetId==='901'&&r.body.revisionId==='902'));
 fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify({status:'passed',http:'mocked B APIs; no actual commission or paid operation',results,requests},null,2));console.log(JSON.stringify({status:'passed',results}));
 }finally{await mp.evaluate(()=>{const saved=globalThis.__designUiStorage;if(saved){wx.getStorageInfoSync().keys.forEach(k=>{if(!(k in saved))wx.removeStorageSync(k)});Object.entries(saved).forEach(([k,v])=>wx.setStorageSync(k,v));delete globalThis.__designUiStorage;}});try{if(mockedRequest)await mp.restoreWxMethod('request');if(mockedModal)await mp.restoreWxMethod('showModal');await mp.reLaunch('/pages/home/index');}finally{await mp.disconnect();}}
})().catch(error=>{console.error(error);process.exitCode=1;});
