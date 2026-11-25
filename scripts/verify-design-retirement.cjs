const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const policy=require('../miniprogram/utils/design-retirement');
function load(file,requireFn,extra={}){const box={module:{exports:{}},require:requireFn,...extra};vm.runInNewContext(fs.readFileSync(file,'utf8'),box);return box.module.exports;}
test('all paid drawing aliases fail before transport while history and budgets remain callable',async()=>{
 const calls=[];const http={post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const api=load('miniprogram/api/franchise/design.js',id=>id.includes('design-retirement')?policy:http);
 for(const name of ['createFlatJob','createElevationJob','revision','notePolish'])await assert.rejects(api.projects[name]('7',{},'old-key'),e=>e.code===1112100004);
 assert.equal(calls.length,0);await api.projects.resultVersions('7');await api.projects.cancelJob('7');await api.budget.estimate('7',{},'budget');assert.equal(calls.length,3);
});
test('migrated request bridge rejects generation before sending a generic flow request',async()=>{
 const calls=[];const platform={token:()=>true,sessionGeneration:()=>1,post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const bridge=load('miniprogram/design-migration/utils/request.js',id=>id.includes('design-retirement')?policy:id==='../../api/http'?platform:id==='./context'?{scope:()=> 'company:1',getApp:()=>({catalogAssets:{}})}:{});
 for(const p of ['/design-projects/7/flat-jobs','/design-projects/7/elevation-jobs','/design-projects/7/revision-requests','/ai-design/note-polish'])await assert.rejects(bridge.post('/app-api/design/v1'+p,{}),e=>e.code===1112100004);
 await assert.rejects(bridge.get('/app-api/design/v1/generation-price-quotes?stage=FLAT'),e=>e.code===1112100004);assert.equal(calls.length,0);
 await bridge.post('/app-api/design/v1/budget-drafts/7/estimates',{area:240});await bridge.get('/app-api/design/v1/design-projects/7/result-versions');assert.equal(calls.length,2);
});
test('direct drawing page deep links redirect before showing or running page handlers',()=>{
 let page;const routes=[];const access=load('miniprogram/design-migration/utils/access.js',id=>id.endsWith('/http')?{token:()=>true}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{pageMode:()=> 'api'},{Page:d=>page=d,wx:{redirectTo:o=>routes.push(o.url)}});
 let calls=0;access.protectedPage({data:{},onLoad(){calls++;},submit(){calls++;}});page.route='design-migration/pages/ai-design/index';page.setData=p=>Object.assign(page.data,p);page.onLoad();page.submit();assert.equal(calls,0);assert.equal(page.data.accessReady,false);assert.deepEqual(routes,['/design-migration/pages/library/index']);
});
test('reference library keeps quick budget without a new AI-design action',()=>{
 const s=fs.readFileSync('miniprogram/design-migration/pages/library/detail.wxml','utf8');assert.match(s,/bindtap="quickBudget"/);assert.doesNotMatch(s,/bindtap="useHouse"/);
});

test('historical generation page cannot offer or dispatch another paid drawing',()=>{
 let page;const toasts=[];
 load('miniprogram/design-migration/pages/ai-design/generating.js',id=>id==='../../utils/access'?{protectedPage:p=>page=p}:id==='../../utils/context'?{getApp:()=>({globalData:{}})}:id==='../../utils/generation-options'?{selection:()=>({})}:{},{wx:{showToast:o=>toasts.push(o.title)}});
 page.regenerate();assert.deepEqual(toasts,['请在客户跟进中上传外部设计成果']);
 const template=fs.readFileSync('miniprogram/design-migration/pages/ai-design/generating.wxml','utf8');assert.doesNotMatch(template,/bindtap="regenerate"/);assert.match(template,/bindtap="retryCandidates"/);assert.match(template,/bindtap="cancel"/);
});
