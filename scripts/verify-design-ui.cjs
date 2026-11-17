const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../miniprogram/design-migration');
const clone = x => JSON.parse(JSON.stringify(x));
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b});return {resolve,reject,promise}; };
function moduleAt(file, dependencies) {
 const module = {exports:{}};
 vm.runInNewContext(fs.readFileSync(path.join(root,file),'utf8'), {module,exports:module.exports,require:p=>{
  if(Object.hasOwn(dependencies,p))return dependencies[p];throw Error('Unmocked '+p);
 }});return module.exports;
}
function library({styles, listCases, download}={}) {
 let page, session='a';const downloads=[];
 const http={captureSession:()=>session,isSameSession:s=>s===session};
 const classifications={floors:()=>[{label:'一层',count:1},{label:'两层',count:2},{label:'三层',count:3},{label:'四层',count:4}],styles:styles||(()=>Promise.resolve([]))};
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/library/index.js'),'utf8'),{require:p=>({
 '../../utils/navigation':{},'../../utils/context':{getApp:()=>({})},'../../utils/access':{protectedPage:p=>page=p},
 '../../utils/api':{listCases:listCases||(()=>Promise.resolve({list:[]}))},'../../utils/request':http,
 '../../utils/assets':{fetchCaseAssetDataUrl:(id,asset)=>{downloads.push([id,asset]);return download?download(id,asset):Promise.resolve('https://image.test/'+asset)}},
 '../../utils/format':{styleLabel:code=>code},'../../utils/case-classifications':classifications,
 '../../../utils/design-case-images':{FRESH_MS:300000,watch:(_page,_selector,rows,visit)=>rows.slice(0,3).forEach(visit)}
 }[p])});
 page.data=clone(page.data);page.setData=patch=>Object.assign(page.data,patch);
 return {page,downloads,changeSession:()=>session='b'};
}
test('分类来自原接口，保留顺序、自定义风格，去除重复代码；层数复用原1到4层',async()=>{
 let requests=0;
 const inputs=require('../miniprogram/design-migration/utils/design-inputs');
 const c=moduleAt('utils/case-classifications.js',{'../../api/franchise/design':{cases:{styles:async()=>{requests++;return [{code:'EUROPEAN',label:'欧式'},{code:'CUSTOM',label:'后台风格'},{code:'CUSTOM',label:'重复'}]}}},'./design-inputs':inputs});
 assert.deepEqual(clone(await c.styles()),[{code:'EUROPEAN',label:'欧式'},{code:'CUSTOM',label:'后台风格'}]);assert.equal(requests,1);assert.equal(c.floors().at(-1).count,4);
});
test('换岗或退出页面后迟到的分类不回填',async()=>{
 for(const unload of [false,true]){const wait=deferred(),h=library({styles:()=>wait.promise});const run=h.page.loadClassifications();if(unload)h.page.onUnload();else h.changeSession();wait.resolve([{code:'OLD',label:'旧身份分类'}]);await run;assert.equal(h.page.data.styleOptions.length,1);}
});
test('分类失败不妨碍案例读取，重试可以读取后台新增风格',async()=>{
 let failed=true;const h=library({styles:()=>failed?Promise.reject(Error('offline')):Promise.resolve([{code:'CUSTOM',label:'新增风格'}]),listCases:()=>Promise.resolve({list:[{caseId:'1',title:'方案',styleCode:'CUSTOM'}]})});
 await Promise.all([h.page.loadClassifications(),h.page.loadPage(true)]);assert.equal(h.page.data.cases.length,1);assert(h.page.data.classificationError);failed=false;await h.page.loadClassifications();assert.equal(h.page.data.classificationError,'');assert.equal(h.page.data.visibleCases[0].tag,'新增风格');
});
test('可见封面独立加载；筛选变更后迟到响应不回填，失败可独立重试',async()=>{
 const pending=[];let active=0,max=0,fail=false;
 const h=library({listCases:()=>Promise.resolve({list:Array.from({length:6},(_,i)=>({caseId:String(i+1),coverAssetId:String(i+100)}))}),download:()=>{active++;max=Math.max(max,active);if(fail){active--;return Promise.reject(Error('bad'))}const w=deferred();pending.push(w);return w.promise.finally(()=>active--);}});
 const run=h.page.loadPage(true);await flush();assert.equal(h.downloads.length,3);h.page._listSeq++;pending.forEach(w=>w.resolve('https://image.test/a'));await run;await flush();assert.equal(h.downloads.length,3);assert.equal(max,3);
 fail=true;await h.page.retryCover({currentTarget:{dataset:{id:'1'}}});
 // Invalidate an already-started image before simulating the component's load failure.
 h.page.imageError({currentTarget:{dataset:{id:'1'}}});await h.page.retryCover({currentTarget:{dataset:{id:'1'}}});await flush();assert.equal(h.page.data.cases[0].imageError,true);
});
test('分类接口格式错误显式失败，不改回硬编码分类',async()=>{
 const c=moduleAt('utils/case-classifications.js',{'../../api/franchise/design':{cases:{styles:async()=>({unexpected:true})}},'./design-inputs':{FLOOR_OPTIONS:[]}});await assert.rejects(c.styles(),/风格分类/);
});

function archive({post, customer, navigationFails=false}={}) {
 let page, scope='session-a'; const calls=[], navigations=[], storage=new Map();
 const valid=value=>typeof value==='string'&&/^[1-9]\d{0,18}$/.test(value)?value:null;
 const deps={
  '../../utils/access':{protectedPage:p=>page=p},'../../utils/request':{captureSession:()=>scope,isSameSession:s=>!!s&&s===scope},
  '../../../api/franchise/crm':{customers:{get:async id=>{if(customer===false)throw Error('unavailable');return {customer:customer||{id,name:'原客户'}};},page:async()=>({list:[{id:'701',name:'测试客户'}],total:1})}},
  '../../../api/franchise/design':{projects:{saveToCustomer:async()=>({schemeId:'801',version:1})}},
  '../../../api/http':{post:(route,body,headers)=>{calls.push({route,body:clone(body),headers});return post?post():Promise.resolve({schemeId:'801',version:1});}},
  '../../utils/record-view':{id:valid},'../../utils/context':{scope:()=>scope},
  '../../utils/sha256':require('../miniprogram/design-migration/utils/sha256')
 };
 vm.runInNewContext(fs.readFileSync(path.join(root,'pages/customer/archive.js'),'utf8'),{Uint8Array,unescape,encodeURIComponent,
  require:p=>{if(Object.hasOwn(deps,p))return deps[p];throw Error('Unexpected '+p)},
  wx:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),showToast(){},
   navigateTo:o=>{navigations.push(o.url);if(navigationFails)o.fail();else if(o.success)o.success();}}
 });page.data=clone(page.data);page.setData=p=>Object.assign(page.data,p);
 return {page,calls,navigations,storage,changeSession:()=>scope='session-b'};
}
async function selectCommission(h) {h.page.onLoad({budgetId:'901',revisionId:'902',next:'commission'});await flush();h.page.choose({currentTarget:{dataset:{id:'701'}}});}
test('发起委托先归档选定预算版本，完成后进入同一客户的现有委托表单',async()=>{
 const w=deferred(),h=archive({post:()=>w.promise});await selectCommission(h);
 const run=h.page.save();await h.page.save();assert.equal(h.calls.length,1);assert.equal(h.navigations.length,0);
 assert.deepEqual(h.calls[0].body,{customerId:'701',budgetId:'901',revisionId:'902'});
 w.resolve({schemeId:'801',version:1});await run;
 assert.deepEqual(h.navigations,['/pages/workflow/commission/index?view=submit&customerId=701&customerName='+encodeURIComponent('测试客户')]);assert.equal(h.page.data.saved,true);
});
test('发送至客户保留归档功能，不自动创建委托',async()=>{
 const h=archive();h.page.onLoad({budgetId:'901',revisionId:'902'});await flush();h.page.choose({currentTarget:{dataset:{id:'701'}}});await h.page.save();
 assert.equal(h.page.data.saved,true);assert.equal(h.navigations.length,0);assert.equal(h.calls[0].route,'/franchise/design/flow-budget-archive');
});
test('归档失败保留幂等键重试，未确认成功时不打开委托',async()=>{
 let failed=true;const h=archive({post:()=>failed?Promise.reject(Error('offline')):Promise.resolve({schemeId:'801',version:1})});await selectCommission(h);
 await h.page.save();assert.equal(h.navigations.length,0);assert.equal(h.page.data.saved,false);failed=false;await h.page.save();
 assert.equal(h.calls[0].headers['Idempotency-Key'],h.calls[1].headers['Idempotency-Key']);assert.equal(h.navigations.length,1);
});
test('打开委托失败后可直接重试导航，不重复归档',async()=>{
 const h=archive({navigationFails:true});await selectCommission(h);await h.page.save();assert(h.page.data.error);assert.equal(h.page.data.saved,true);
 await h.page.save();assert.equal(h.calls.length,1);assert.equal(h.navigations.length,2);
});
test('账号变更后的归档响应不打开旧客户的委托',async()=>{
 const w=deferred(),h=archive({post:()=>w.promise});await selectCommission(h);const run=h.page.save();h.changeSession();w.resolve({schemeId:'801',version:1});await run;assert.equal(h.navigations.length,0);assert.equal(h.page.data.saved,false);
});

test('从客户预算进入直接校验、归档并带入原客户，无需再次选择',async()=>{
 const h=archive();h.page.onLoad({budgetId:'901',revisionId:'902',next:'commission',customerId:'701'});await flush();await flush();
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].body.customerId,'701');assert.equal(h.navigations.length,1);assert.match(h.navigations[0],/customerId=701/);
});
test('原客户不可访问时回到选择列表，不误归档给列表第一人',async()=>{
 const h=archive({customer:false});h.page.onLoad({budgetId:'901',next:'commission',customerId:'702'});await flush();
 assert.equal(h.calls.length,0);assert.equal(h.navigations.length,0);assert.equal(h.page.data.selected,'');assert.equal(h.page.data.rowCount,1);
});
