const automator=require('../node_modules/miniprogram-automator');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const out=process.env.DESIGN_UI_ARTIFACTS||path.resolve('docs/design-ui-20261116');fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 await delay(2500);
 const mp=await automator.connect({wsEndpoint:process.env.WECHAT_AUTOMATOR_WS||'ws://127.0.0.1:9948'}),results=[];
 const invoke=async(name,arg)=>{console.log('invoke',name);await mp.evaluate((n,a)=>{const p=getCurrentPages().slice(-1)[0];setTimeout(()=>{const result=p[n](a);if(result&&result.catch)result.catch(e=>console.error(e));},300);return true;},name,arg);console.log("evaluated",name);await delay(1200);};
 const waitRoute=async(route)=>{await delay(1500);const deadline=Date.now()+15000;while(Date.now()<deadline){const p=await mp.currentPage();if(p.path===route)return p;await delay(150);}throw Error('Navigation did not reach '+route);};
 await mp.evaluate(()=>{globalThis.__designUiStorage=Object.fromEntries(wx.getStorageInfoSync().keys.map(key=>[key,wx.getStorageSync(key)]));});
 try{
 results.push=(...items)=>{console.log(...items);return Array.prototype.push.apply(results,items);};
 const capture=mp.screenshot.bind(mp);mp.screenshot=async o=>{console.log('capture',path.basename(o.path));await capture(o);console.log('captured');};
 await mp.mockWxMethod('chooseMedia',function(){return {tempFiles:[{tempFilePath:'/design-migration/assets/v12/budget-sketch.jpg',size:22508}],type:'image'};});
 await mp.mockWxMethod('showModal',function(){return {confirm:true,cancel:false};});
 await mp.mockWxMethod('request',function(options){
  const state=globalThis.__designParity||(globalThis.__designParity={drafts:[],requests:[],favorites:[],next:500});
  const route=String(options.url).split('/admin-api')[1]?.split('?')[0]||'';
  const q={};(String(options.url).split('?')[1]||'').split('&').filter(Boolean).forEach(pair=>{const a=pair.split('=');q[decodeURIComponent(a[0])]=decodeURIComponent(a[1]||'');});
  const input=options.data||{};state.requests.push({route,method:options.method,body:input,headers:options.header});
  if(options.method==='GET')Object.assign(q,input);
  const summary=id=>({caseId:String(id),caseVersionId:'551',title:'完整图纸案例'+id,sourceType:'COMPANY',styleCode:'MODERN',coverAssetId:'601',floorCount:2,buildingArea:240,faceWidth:12,depth:10,allowedActions:['VIEW','FAVORITE','DESIGN_WITH']});
  const detail={...summary(501),dimensionBasis:'SITE',description:'原生测试用例',parameters:{styleCode:'MODERN',floorCount:2,buildingArea:240,faceWidth:12,depth:10},floorPlanAssetIds:['602','603'],floorPlans:[{assetId:'602',floorNo:1},{assetId:'603',floorNo:2}],elevationAssetId:'604'};
  const budget={budgetId:'901',revisionId:'902',model:'ITEMIZED_V1',projectId:null,draftId:state.drafts[0]?.draftId||'501',projectName:'独立预算',schemeName:'参考预算',regionName:'武汉',inputSummary:{regionCode:'WUHAN',footprintArea:120,floorCount:2,buildingArea:240,roofArea:120},completeness:'INCOMPLETE',pricedSubtotalCents:0,totalCents:null,categoryTotals:{BODY:0,EXTERIOR:0},items:[],missingItems:['基础形式待选择'],warnings:[],disclaimer:'前期参考，最终以正式报价为准',createdAt:'2026-11-16T00:00:00Z',saved:false};
  const version={versionId:'81',version:1,superseded:false,selectedFlatAssetId:'602',selectedElevationAssetId:null,flatImageInfo:{resolution:'2K',orientation:'PORTRAIT',width:1536,height:2048},configSnapshot:{},createdAt:'2026-11-16T00:00:00Z'};
  let data={};
  if(route==='/__design_parity_reset'){state.drafts=[];state.favorites=[];state.requests=[];state.next=Math.floor(Date.now()/10);data=true;}
  else if(route==='/__design_parity_trace')data=state.requests;
  else if(route==='/platform/posts/mine')data=[{postId:'99',userId:'22',orgId:'119',roleCode:'sales-lead',orgName:'测试公司'}];
  else if(route==='/franchise/design/cases/styles')data=[{code:'NEW_CHINESE',label:'新中式'},{code:'MODERN',label:'现代'},{code:'CHINESE',label:'中式'},{code:'EUROPEAN',label:'欧式'},{code:'RURAL',label:'田园'},{code:'CUSTOM_STYLE',label:'后台新增风格'}];
  else if(route==='/franchise/design/cases')data={list:[summary(501),summary(502)],nextCursor:null};
  else if(route==='/franchise/company-points/summary')data={balance:1000,inFlight:0,status:'ACTIVE'};
  else if(route==='/franchise/design/flow-price')data={subject:q.subject,quantity:Number(q.quantity),credits:q.subject==='budget_estimate'?7:Number(q.quantity)*6,priceVersion:'native-price-v1'};
  else if(route==='/franchise/design/flow-upload-ticket')data={assetId:input.assetType==='USER_SKETCH'?'710':'711',uploadUrl:'https://upload.example.test/parity.jpg',method:'PUT'};
  else if(/^\/franchise\/design\/assets\/\d+\/complete$/.test(route))data={uploadStatus:'ACCEPTED'};
  else if(route.includes('/download-tickets'))data={downloadUrl:'https://images.example.test/fixture.jpg'};
  else if(route.startsWith('/franchise/design/flow-ai-jobs/'))data={jobId:'71',projectId:'61',status:'PARTIALLY_SUCCEEDED',phase:'FLAT',requestedCount:2,acceptedCount:1,progress:100,imageOptions:{resolution:'2K',orientation:'PORTRAIT'},allowedActions:[],totalPointCost:12,refundedPointCost:6,netPointCost:6,billingFinalized:true};
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
 await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_parity_reset',method:'GET'});
 await mp.evaluate(()=>{wx.setStorageSync('zs-api-token-v1','native-mocked-'+Date.now());wx.setStorageSync('zs-api-post-v1','99');wx.setStorageSync('zs-api-posts-v1',[{postId:'99',userId:'22',orgId:'119',roleCode:'sales-lead',orgName:'测试公司'}]);});
 let page=await mp.reLaunch('/pages/workflow/explore/index?view=inspiration');await delay(700);page=await mp.currentPage();assert.equal(page.path,'design-migration/pages/library/index');let data=await page.data();if(data.cases.length!==2){console.log('unexpected library',JSON.stringify(data.cases));console.log('trace',JSON.stringify(await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_parity_trace',method:'GET'})));}assert.equal(data.cases.length,2);assert.equal(data.styleOptions.length,7);assert.equal(data.floorOptions.at(-1).count,4);await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({cases:p.data.cases.map(row=>({...row,image:'/pages/design/assets/biz-case-1.jpg',imageLoading:false}))});p.refreshVisible();});await mp.screenshot({path:path.join(out,'library.png')});await invoke('onReachBottom');data=await page.data();assert.equal(data.cases.length,3);assert.equal(data.hasMore,false);await invoke('selectSource',{currentTarget:{dataset:{index:1}}});assert.equal((await page.data()).source,1);results.push('灵感库旧入口、来源筛选、游标加载到末页');
 page=await mp.reLaunch('/design-migration/pages/library/detail?id=501');await delay(600);data=await page.data();assert.equal(data.drawings.length,3);assert.deepEqual(data.drawings.map(item=>item.assetId),['604','602','603']);assert.equal(data.caseDetail.canDesign,true);await invoke('toggleFavorite');assert.equal((await page.data()).favorite,true);
 await mp.evaluate(()=>{const p=getCurrentPages().slice(-1)[0];p.setData({hero:{...p.data.hero,url:'/pages/design/assets/biz-case-1.jpg',status:'ready'},tab:1,drawings:p.data.drawings.map((item,index)=>index===1?{...item,url:'/design-migration/assets/v12/budget-sketch.jpg',status:'ready'}:item)});});await mp.evaluate(()=>{setTimeout(()=>wx.pageScrollTo({scrollTop:450,duration:0}),300);return true;});await delay(1700);await mp.screenshot({path:path.join(out,'native-case-full-plan.png')});results.push('详情全部楼层图、全图比例、收藏与授权动作');
 page=await mp.reLaunch('/pages/workflow/explore/index?view=ai-config');await delay(600);page=await mp.currentPage();data=await page.data();assert.equal(data.line,'FLAT');assert.equal(data.resolution,'2K');assert.equal(data.orientation,'LANDSCAPE');assert.equal(data.quoteReady,true);assert.equal((await page.$$('.page-title')).length,0);assert.equal((await page.$$('.case-match')).length,0);assert.equal((await page.$$('.settings-pixels')).length,0);await mp.screenshot({path:path.join(out,'native-ai-flat.png')});await invoke('chooseImage');assert.equal((await page.data()).sketchAssetId,'710');results.push('AI平面独立入口、原默认规格、公司报价及草图上传审核');
 page=await mp.reLaunch('/pages/workflow/explore/index?view=ai-facade');await delay(600);page=await mp.currentPage();data=await page.data();assert.equal(data.line,'ELEVATION');assert.equal(data.elevStyles.length,6);await invoke('onElevStyle',{currentTarget:{dataset:{code:'CUSTOM_STYLE'}}});assert.equal((await page.data()).elevStyleLabel,'后台新增风格');await mp.screenshot({path:path.join(out,'native-ai-elevation.png')});await invoke('chooseElevImage');assert.equal((await page.data()).elevRefs[0].assetId,'711');results.push('AI立面独立入口、可选配置及参考图上传审核');
 page=await mp.reLaunch('/design-migration/pages/ai-design/generating?projectId=61&jobId=71&count=2');await delay(700);data=await page.data();assert.equal(data.candidates.length,1);assert.equal(data.candidates[0].candidateId,'72');assert.equal(data.candidates[0].assetId,'602');assert.equal(data.finished,true);assert.match(data.partialNotice,/6/);await mp.screenshot({path:path.join(out,'native-partial-generation.png')});results.push('部分成功、候选读取及实际公司积分退款');
 page=await mp.reLaunch('/design-migration/pages/ai-design/result?projectId=61&resultVersionId=81');await delay(600);data=await page.data();assert.equal(data.latest.versionId,'81');assert.equal(data.optionsReady,true);assert.equal(data.error,'');await invoke('publish');page=await waitRoute('design-migration/pages/customer/archive');assert.equal(page.path,'design-migration/pages/customer/archive');await invoke('choose',{currentTarget:{dataset:{id:'701'}}});await invoke('save');assert.equal((await page.data()).saved,true);results.push('不可变结果版本、读取出图规格、保存至客户');
 page=await mp.reLaunch('/pages/workflow/explore/index?view=budget');await delay(700);page=await mp.currentPage();data=await page.data();assert(data.draft);assert.equal(data.loading,false);
 await invoke('region',{detail:{value:0}});await invoke('edit',{currentTarget:{dataset:{field:'footprintArea'}},detail:{value:'120'}});await invoke('floor',{detail:{value:1}});await invoke('edit',{currentTarget:{dataset:{field:'roofArea'}},detail:{value:'120'}});await invoke('save');assert.equal((await page.data()).dirty,false);
 const cells=(await page.data()).configCells;assert.equal(cells.length,13);assert.equal(cells[0].label,'地基基础');assert(cells.some(c=>c.label==='入户门'));assert(cells.some(c=>c.label==='灯带'));assert(!cells.some(c=>c.label.includes('配置选项')));
 await invoke('choose',{currentTarget:{dataset:{key:cells[0].key}},detail:{value:1}});assert((await page.data()).draft.state.optionIds.includes(cells[0].optionIds[0]));
 const buttons=await page.$$('.actions button');for(const button of buttons){const size=await button.size();assert(size.height>=44,JSON.stringify(size));}
 await mp.screenshot({path:path.join(out,'budget-top.png')});await mp.pageScrollTo(900);await delay(500);await mp.screenshot({path:path.join(out,'budget-config.png')});await mp.pageScrollTo(2500);await delay(500);await mp.screenshot({path:path.join(out,'budget-actions.png')});
 await mp.screenshot({path:path.join(out,'native-budget-draft.png')});await invoke('generate');page=await waitRoute('design-migration/pages/budget/result');assert.equal(page.path,'design-migration/pages/budget/result');data=await page.data();assert.equal(data.estimate.totalCents,null);assert.equal(data.estimate.completeness,'INCOMPLETE');await delay(900);await mp.screenshot({path:path.join(out,'native-budget-incomplete.png')});results.push('独立预算草稿、地区与基础参数、报价确认、缺价总额为空');
 await invoke('archive');page=await waitRoute('design-migration/pages/customer/archive');await invoke('choose',{currentTarget:{dataset:{id:'701'}}});await invoke('save');assert.equal((await page.data()).saved,true);results.push('缺价预算按冻结快照保存至客户，保留空总额');page=await mp.reLaunch('/design-migration/pages/budget/result?budgetId=901');await delay(700);await invoke('history');page=await waitRoute('design-migration/pages/budget/history');assert.equal(page.path,'design-migration/pages/budget/history');results.push('独立预算历史入口与冻结版本读取');
 page=await mp.reLaunch('/pages/design/index');await delay(900);await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({catalogCases:p.data.catalogCases.map(row=>({...row,coverUrl:'/pages/design/assets/biz-case-1.jpg',coverLoading:false}))});});await mp.screenshot({path:path.join(out,'home.png')});assert.equal((await page.$$('.design-hero-description')).length,0);results.push('首页文案清理、分类接口全量展示、四层筛选、自定义风格及预算配置与按钮尺寸');
 const trace=await mp.callWxMethod('request',{url:'https://native.example.test/admin-api/__design_parity_trace',method:'GET'});const requests=trace.data.data;assert(requests.some(r=>r.route==='/franchise/design/assets/602/download-tickets'));assert(requests.some(r=>r.route==='/franchise/design/cases/501/assets/602/download-tickets'));assert(requests.some(r=>r.route==='/franchise/design/flow'&&r.body.path==='/cases'&&r.body.query.sourceType==='AI'));const uploads=requests.filter(r=>r.route==='/franchise/design/flow-upload-ticket');assert(uploads.some(r=>r.body.assetType==='USER_SKETCH'));assert(uploads.some(r=>r.body.assetType==='DESIGN_REFERENCE'));const puts=requests.filter(r=>r.method==='PUT'&&r.route==='');assert.equal(puts.length,2);puts.forEach(r=>assert.equal(r.headers.Authorization,undefined));const archived=requests.find(r=>r.route==='/franchise/design/flow-budget-archive');assert.equal(String(archived.body.revisionId),'902');const generation=requests.find(r=>r.route==='/franchise/design/flow'&&/\/estimates$/.test(r.body.path||''));assert(generation.body.body.companyPrice.priceVersion);assert.equal(generation.body.body.usageConfirmation,undefined);
 fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify({status:'passed',http:'mocked B APIs; no live AI or points',media:'local test images only for native full-image rendering',results,requests},null,2));console.log(JSON.stringify({status:'passed',results}));
 }finally{await mp.evaluate(()=>{const saved=globalThis.__designUiStorage;if(saved){wx.getStorageInfoSync().keys.forEach(k=>{if(!(k in saved))wx.removeStorageSync(k)});Object.entries(saved).forEach(([k,v])=>wx.setStorageSync(k,v));delete globalThis.__designUiStorage;}});try{await mp.restoreWxMethod('chooseMedia');await mp.restoreWxMethod('request');await mp.restoreWxMethod('showModal');}finally{await mp.disconnect();}}
})().catch(error=>{console.error(error);process.exitCode=1;});
