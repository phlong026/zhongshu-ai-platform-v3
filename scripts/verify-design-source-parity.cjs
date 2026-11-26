const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ROOT=path.resolve(__dirname,'..'),current=path.join(ROOT,'miniprogram/design-migration/utils'),oracle=path.join(__dirname,'fixtures/design-source');
const clone=value=>value===undefined?undefined:JSON.parse(JSON.stringify(value));
test('原规则测试快照摘要与迁移清单一致',()=>{
 const manifest=JSON.parse(fs.readFileSync(path.join(ROOT,'docs/design-parity-20261116/source-manifest.json'),'utf8'));
 for(const name of fs.readdirSync(oracle).filter(name=>name.endsWith('.js'))){
  const original=manifest.files.find(f=>f.path.replace(/\\/g,'/').endsWith('/utils/'+name));assert(original,name);
  assert.equal(require('node:crypto').createHash('sha256').update(fs.readFileSync(path.join(oracle,name))).digest('hex'),original.sha256,name);
 }
});
const validId=value=>typeof value==='string'&&/^[1-9]\d{0,18}$/.test(value)?value:null;
function helper(root,name,overrides={}){
 const module={exports:{}};
 vm.runInNewContext(fs.readFileSync(path.join(root,name+'.js'),'utf8'),{module,exports:module.exports,Uint8Array,ArrayBuffer,unescape,encodeURIComponent,require(request){
  if(Object.hasOwn(overrides,request))return overrides[request];
  if(request==='../../utils/design-retirement')return require('../miniprogram/utils/design-retirement');
  if(request==='./budget-draft')return {id:validId};
  if(request==='./api')return {};
  if(request==='./format')return helper(root,'format');
  throw Error('Unexpected dependency '+request);
 }},{filename:name+'.js'});return module.exports;
}
function same(name,fn,...args){const a=helper(oracle,name),b=helper(current,name);const result=module=>{try{return {value:clone(module[fn](...args))};}catch(e){return {error:e.message};}};assert.deepEqual(result(b),result(a),name+'.'+fn+': '+JSON.stringify(args));}
test('原小程序输入边界：平面尺寸、入口、家庭、院落、图纸类型和立面自由输入保持一致',()=>{
 for(const mode of [0,1])for(const width of ['',null,'2.9','3','12.5','40','40.1','12.55','1e1','-3'])for(const floorIndex of [-1,0,1,2,3,99]){
  same('design-inputs','buildRequirementInputs',mode,{faceWidth:width,depth:'12.5',floorIndex,familyIndex:2,entranceIndex:1,layoutIndex:1,drawingIndex:1,note:'保留原需求',referenceAssetIds:mode?['901','902']:[],styleCode:'',roofType:'',material:'',color:''});
 }
 for(const depth of ['2.9','3','40','40.1','12.55',''])same('design-inputs','buildRequirementInputs',0,{faceWidth:'12',depth});
});
test('原小程序出图默认值与2K/4K横竖屏组合保持一致',()=>{
 for(const line of ['FLAT','ELEVATION',null]){
  same('generation-options','defaults',line);
  for(const resolution of [undefined,'2K','4K','8K'])for(const orientation of [undefined,'LANDSCAPE','PORTRAIT','SQUARE'])same('generation-options','selection',line,{resolution,orientation});
 }
 for(const resolution of ['2K','4K','bad'])same('generation-options','orientations',resolution);
});
const catalog={items:[{itemId:'101',code:'FOUNDATION',category:'BODY',name:'基础',source:'STANDARD',options:[{optionId:'201',code:'A',label:'筏板',selectionGroup:'FORM',availability:'AVAILABLE'},{optionId:'202',code:'B',label:'条基',selectionGroup:'FORM',availability:'MISSING_PRICE'}]},{itemId:'102',code:'CUSTOM',category:'EXTERIOR',name:'附加项',source:'CUSTOM_TEMPLATE',options:[{optionId:'203',code:'X',label:'选项',selectionGroup:'DEFAULT',availability:'AVAILABLE'}]}]};
test('原预算目录：缺价选项可选，目录校验与同组互斥、分组摘要保持一致',()=>{
 const items=helper(oracle,'budget-selection').catalog(catalog);
 same('budget-selection','catalog',catalog);
 for(const selections of [[],['201'],['202'],['201','202'],['203'],['999'],['201','203']]){
  const draft={selections};same('budget-selection','reconcile',draft,items);
  for(const category of ['BODY','EXTERIOR'])same('budget-selection','summary',items,draft,category);
  same('budget-selection','groupChoices',items[0],draft);
  for(const option of ['201','202',''])same('budget-selection','choose',draft,items,'101','FORM',option);
 }
 same('budget-selection','catalog',{items:[{...catalog.items[0],options:[{...catalog.items[0].options[0],availability:'DISABLED'}]}]});
});
const budget={budgetId:'301',revisionId:'401',model:'ITEMIZED_V1',projectId:null,draftId:'501',projectName:'',schemeName:'参考预算',regionName:'测试地区',inputSummary:{regionCode:'WUHAN',footprintArea:120,floorCount:2,buildingArea:240,roofArea:120},completeness:'COMPLETE',pricedSubtotalCents:12345678,totalCents:12345678,categoryTotals:{BODY:10000000,EXTERIOR:2345678},items:[],missingItems:[],warnings:[],saved:false};
test('原预算读取：缺价不按0计、金额不取区间中值、公开快照不暴露内部单价规则',()=>{
 for(const value of [budget,{...budget,completeness:'INCOMPLETE',totalCents:null,missingItems:['主体待补']},{...budget,completeness:'INCOMPLETE',totalCents:0},{...budget,totalCents:123},{...budget,projectId:'9',draftId:null},null])same('budget-view','publicBudget',value);
 for(const amount of [0,1,9999,10000,12345678,10000000000,null])for(const exact of [true,false])same('budget-view','amount',amount,exact);
 const projected=helper(current,'budget-view').publicBudget({...budget,secretUnitPrice:888,formula:'internal'});assert.equal(projected.secretUnitPrice,undefined);assert.equal(projected.formula,undefined);
});
function requestHarness(){let generation=1,token='b-token';const calls=[];const http={token:()=>token,sessionGeneration:()=>generation,get:(p,q)=>{calls.push({method:'GET',p,q});return Promise.resolve(p.includes('flow-price')?{subject:q.subject,quantity:q.quantity,credits:12,priceVersion:'v1'}:{});},post:(p,b,h)=>{calls.push({method:'POST',p,b,h});return Promise.resolve({ok:true});}};
 const design={cases:{downloadUrl:(c,a)=>Promise.resolve({downloadUrl:'https://images.example.test/'+c+'/'+a})},assets:{downloadUrl:a=>Promise.resolve({downloadUrl:'https://provider.example.test/'+a})},projects:{}};
 const m=helper(current,'request',{'../../api/http':http,'../../api/franchise/design':design,'./context':{scope:()=> '1:10:20:30',getApp:()=>({catalogAssets:{602:'501'}})},'../../api/company-points':{summary:()=>Promise.resolve({balance:950})}});
 return {m,calls,http,design,change:()=>generation++,logout:()=>token=''};
}
test('公开案例和自有结果即使图片编号相同也使用各自来源的下载接口',async()=>{
 const h=requestHarness();const api=helper(current,'api',{'./request':h.m});const a=helper(current,'assets',{'./api':api,'./request':h.m,'../../api/franchise/design':h.design,'../../utils/design-case-images':{load:(c,a)=>Promise.resolve('wxfile://case-cache/'+c+'/'+a)}});
 assert.equal(await a.fetchCaseAssetDataUrl('501','602'),'wxfile://case-cache/501/602');
 assert.equal(await a.fetchAssetDataUrl('602'),'https://provider.example.test/602');
});
test('实际资源API保持原路径，上传票据、审核完成与结果下载均进入B资源适配',async()=>{
 const normalized=p=>fs.readFileSync(p,'utf8').split(/\r?\n/).filter(line=>line.trim()).join('\n');
 assert.equal(normalized(path.join(current,'api.js')),normalized(path.join(oracle,'api.js')));
 const h=requestHarness(),api=helper(current,'api',{'./request':h.m}),completed=[];
 h.design.assets.complete=id=>{completed.push(id);return Promise.resolve({uploadStatus:'ACCEPTED'});};
 await api.getUploadTicket('USER_SKETCH','image/jpeg',123,'a'.repeat(64));
 assert.equal(h.calls[0].p,'/franchise/design/flow-upload-ticket');assert.equal(h.calls[0].b.assetType,'USER_SKETCH');assert.equal(h.calls[0].b.sizeBytes,'123');
 assert.equal(await api.completeUpload('602'),true);assert.deepEqual(completed,['602']);
 assert.equal((await api.createDownloadTicket('602')).downloadUrl,'https://provider.example.test/602');
 h.design.assets.complete=()=>Promise.resolve({uploadStatus:'PENDING'});await assert.rejects(api.completeUpload('602'),error=>error.msg.includes('校验中'));
});
test('B适配只携带当前B身份，原资源路径通过封闭能力路由，幂等键保持不变',async()=>{
 const h=requestHarness();await h.m.post('/app-api/design/v1/budget-drafts/9/estimates',{expectedVersion:2,companyPrice:{priceVersion:'v1'}},{'Idempotency-Key':'stable-key'});
 assert.equal(h.calls[0].p,'/franchise/design/flow');assert.equal(h.calls[0].b.path,'/budget-drafts/9/estimates');assert.equal(h.calls[0].h['Idempotency-Key'],'stable-key');
});
test('切岗或换号后的迟到结果被拒绝；未登录不发起请求',async()=>{
 const h=requestHarness();const pending=h.m.get('/app-api/design/v1/budget-drafts');h.change();await assert.rejects(pending,e=>e.code==='SESSION_CHANGED');h.logout();await assert.rejects(h.m.get('/app-api/design/v1/budget-drafts'),e=>e.code===401);
});
test('B价格变化走重新确认语义，报价与余额来自真实公司积分接口',async()=>{
 const h=requestHarness();assert.equal((await h.m.get('/app-api/design/v1/point-account')).availablePoints,950);
 const q=await h.m.get('/app-api/design/v1/budget-point-quotes');assert.equal(q.companyPrice.priceVersion,'v1');assert.equal(q.totalPointCost,12);
 h.http.post=()=>Promise.reject({code:1072000016,message:'价格已改变'});await assert.rejects(h.m.post('/app-api/design/v1/budget-drafts/9/estimates',{}),e=>e.code==='DESIGN_PRICE_CHANGED');
 h.http.post=()=>Promise.reject({code:1072000001,message:'公司没有开通设计能力'});await assert.rejects(h.m.post('/app-api/design/v1/budget-drafts/9/estimates',{}),e=>e.code===1072000001);
});
test('旧出图入口回到灵感资料库，快速预算链接保留创建与历史的上下文',()=>{
 let page;const routes=[];vm.runInNewContext(fs.readFileSync(path.join(ROOT,'miniprogram/pages/workflow/explore/index.js'),'utf8'),{Page:p=>page=p,wx:{redirectTo:o=>routes.push(o.url)},require:p=>p.endsWith('/api/http')?{token:()=> 'b'}:p.endsWith('/api/auth')?{currentApiPost:()=>({postId:'1'})}:p.endsWith('/utils/platform-design')?{pageMode:()=> 'api'}:p==='./platform-bridge'?p=>{p.platTeardown=()=>{};p.platOnLoadInit=()=>{throw Error('old flow invoked');};}: {}});
 page.setData=()=>{};for(const view of ['inspiration','ai-config','ai-facade','budget'])page.onLoad({view});
 page.onLoad({view:'budget-result',budgetId:'90071992547409971',revisionId:'90071992547409972'});
 page.onLoad({view:'plan-result',projectId:'90071992547409973',resultVersionId:'90071992547409975'});
 page.onLoad({view:'project',id:'not-an-id'});
 page.onLoad({view:'budget',draftId:'90071992547409974'});
 page.onLoad({view:'budget',projectId:'90071992547409970',resultVersionId:'701'});
 assert.deepEqual(routes,['/design-migration/pages/library/index','/design-migration/pages/library/index','/design-migration/pages/library/index','/design-migration/pages/budget/quick',
 '/design-migration/pages/budget/result?budgetId=90071992547409971&revisionId=90071992547409972',
 '/design-migration/pages/ai-design/result?projectId=90071992547409973&resultVersionId=90071992547409975',
 '/design-migration/pages/profile/records?type=projects','/design-migration/pages/budget/quick?draftId=90071992547409974','/design-migration/pages/budget/input?projectId=90071992547409970&resultVersionId=701']);
});
test('全图展示和公司归档入口已接入；历史只读与完成后才导出预算图保持原限制',()=>{
 const root=path.join(ROOT,'miniprogram/design-migration');assert.match(fs.readFileSync(path.join(root,'pages/library/detail.wxml'),'utf8'),/aspectFit/);assert.match(fs.readFileSync(path.join(root,'pages/ai-design/result.js'),'utf8'),/pages\/customer\/archive/);assert.doesNotMatch(fs.readFileSync(path.join(root,'pages/ai-design/result.js'),'utf8'),/ai-design\/publish/);
 assert.match(fs.readFileSync(path.join(root,'utils/budget-share.js'),'utf8'),/completeness !== 'COMPLETE'/);
 const app=JSON.parse(fs.readFileSync(path.join(ROOT,'miniprogram/app.json'),'utf8'));assert(app.subPackages.find(s=>s.root==='design-migration').pages.includes('pages/customer/archive'));
});
