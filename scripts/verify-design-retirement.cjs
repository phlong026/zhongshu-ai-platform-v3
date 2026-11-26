const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const policy=require('../miniprogram/utils/design-retirement');
function load(file,requireFn,extra={}){const box={module:{exports:{}},require:requireFn,...extra};vm.runInNewContext(fs.readFileSync(file,'utf8'),box);return box.module.exports;}
test('design projects and estimates reject writes before transport, frozen versions and cancellation stay available',async()=>{
 const calls=[];const http={post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const api=load('miniprogram/api/franchise/design.js',id=>id.includes('design-retirement')?policy:http);
 for(const name of ['create','selectFlat','selectElevation','createFlatJob','createElevationJob','revision','notePolish'])await assert.rejects(api.projects[name]('7',{},'old-key'),e=>e.code===1112100004);
 await assert.rejects(api.budget.estimate('7',{},'budget'),e=>e.code===1112100004);
 assert.equal(calls.length,0);await api.projects.resultVersions('7');await api.projects.cancelJob('7');assert.equal(calls.length,2);
});
test('migrated request bridge rejects generation before sending a generic flow request',async()=>{
 const calls=[];const platform={token:()=>true,sessionGeneration:()=>1,post:(...a)=>{calls.push(a);return Promise.resolve({});},get:(...a)=>{calls.push(a);return Promise.resolve({});}};
 const bridge=load('miniprogram/design-migration/utils/request.js',id=>id.includes('design-retirement')?policy:id==='../../api/http'?platform:id==='./context'?{scope:()=> 'company:1',getApp:()=>({catalogAssets:{}})}:{});
 for(const p of ['/design-projects/7/flat-jobs','/design-projects/7/elevation-jobs','/design-projects/7/revision-requests','/ai-design/note-polish'])await assert.rejects(bridge.post('/app-api/design/v1'+p,{}),e=>e.code===1112100004);
 await assert.rejects(bridge.get('/app-api/design/v1/generation-price-quotes?stage=FLAT'),e=>e.code===1112100004);assert.equal(calls.length,0);
 for(const [method,path] of [
   ['post','/budget-drafts'],['put','/budget-drafts/7'],['post','/budget-drafts/7/estimates'],
   ['post','/design-projects/7/budget-estimates/itemized'],['post','/budget-estimates/8/save'],
   ['get','/budget-point-quotes'],['post','/design-projects'],['del','/design-projects/7']
 ])await assert.rejects(bridge[method]('/app-api/design/v1'+path,{}),e=>e.code===1112100004);
 assert.equal(calls.length,0);
 for(const path of ['/design-projects/7/result-versions','/budget-estimates/8?revisionId=9','/budget-estimates/independent'])
   await bridge.get('/app-api/design/v1'+path);
 assert.equal(calls.length,3);
});
test('direct drawing page deep links redirect before showing or running page handlers',()=>{
 let page;const routes=[];const access=load('miniprogram/design-migration/utils/access.js',id=>id.endsWith('/http')?{token:()=>true}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{pageMode:()=> 'api'},{Page:d=>page=d,wx:{redirectTo:o=>routes.push(o.url)}});
 let calls=0;access.protectedPage({data:{},onLoad(){calls++;},submit(){calls++;}});page.route='design-migration/pages/ai-design/index';page.setData=p=>Object.assign(page.data,p);page.onLoad();page.submit();assert.equal(calls,0);assert.equal(page.data.accessReady,false);assert.deepEqual(routes,['/design-migration/pages/library/index']);
});
test('old budget editor links reach read-only history before any editor lifecycle or save handler',()=>{
 for(const editor of ['quick','input','parameters','prices','body','exterior']){
   let page;let called=0;const routes=[];
   const access=load('miniprogram/design-migration/utils/access.js',id=>id.endsWith('/http')?{token:()=>true}:id.endsWith('/auth')?{currentApiPost:()=>({roleCode:'sales'})}:{pageMode:()=> 'api'},{Page:d=>page=d,wx:{redirectTo:o=>routes.push(o.url)}});
   access.protectedPage({data:{},onLoad(){called++;},save(){called++;}});
   page.route='design-migration/pages/budget/'+editor;page.setData=p=>Object.assign(page.data,p);
   page.onLoad({projectId:'70'});page.save();assert.equal(called,0);assert.equal(routes.length,1);
   assert.equal(routes[0],'/design-migration/pages/budget/history?projectId=70');
 }
});
test('reference library keeps preview and drawings without a new estimate action',()=>{
 const s=fs.readFileSync('miniprogram/design-migration/pages/library/detail.wxml','utf8');assert.match(s,/previewLibraryImage/);assert.doesNotMatch(s,/bindtap="(quickBudget|useHouse)"/);
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
