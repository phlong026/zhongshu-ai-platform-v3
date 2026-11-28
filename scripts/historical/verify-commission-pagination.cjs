// Historical design-admin workbench only (retired by BIZ-T14). Not a current acceptance gate.
// To reproduce: point ZS_LEGACY_WORKTREE_ROOT at a clean e9cd1313 checkout with its locked dependencies.
if (!process.env.ZS_LEGACY_WORKTREE_ROOT) throw Error('Historical check: set ZS_LEGACY_WORKTREE_ROOT to the pre-T14 e9cd1313 worktree');
process.chdir(process.env.ZS_LEGACY_WORKTREE_ROOT);
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const {createRequire}=require('node:module');
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function fixture(){
 const file=path.resolve('miniprogram/pages/workflow/commission/index.js'),real=createRequire(file),calls=[],guards={},routes=[];
 let p,post={postId:'hq-a',orgId:'1',orgType:'headquarters',roleCode:'hq-design-admin'},gen=1,loggedIn=true;
 const data=Array.from({length:237},(_,i)=>({commission:{id:String(9007199254740993n+BigInt(i)),customerId:String(i+1),customerName:'客户'+String(i+1).padStart(3,'0'),status:i%2?'DESIGN_COMPLETED':'DESIGNING'},commissionId:String(9007199254740993n+BigInt(i)),allowedActions:[i%2?'design-dispatch':'design-complete']}));
 const requests={counts:0,recent:0,todos:0};
 const http={token:()=>loggedIn?'token':'',sessionGeneration:()=>gen,get:async()=>({}),requestGuard:key=>{const stamp=guards[key]={},actor=post.postId,session=gen;return()=>loggedIn&&guards[key]===stamp&&post.postId===actor&&gen===session;}};
 const api={commissions:{workbenchCounts:async()=>{requests.counts++;return {DESIGNING:119,DESIGN_COMPLETED:118};},workbenchRecent:async()=>{requests.recent++;return [];},page:async(number,size,keyword,states)=>{
   calls.push({number,size,keyword,states});const list=data.filter(r=>(!keyword||r.commission.customerName.includes(keyword))&&(!states||states.split(',').includes(r.commission.status)));
   return {list:list.slice((number-1)*size,number*size),total:list.length};
 }}};
 const todos={items:data.map(r=>({key:'franchise:design-complete:'+r.commissionId,title:r.commission.customerName,actionable:true,appPath:'/pages/workflow/commission/index?commissionId='+r.commissionId})),failedDomains:[]};
 const todosApi={list:async()=>{requests.todos++;return todos;}};
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:d=>p=d,require:id=>id.endsWith('/api/http')?http:id.endsWith('/api/auth')?{currentApiPost:()=>post}:id.endsWith('/api/franchise/commission')?api:id.endsWith('/api/todos')?todosApi:id.endsWith('/page-navigation')?{open:url=>routes.push(url)}:id.endsWith('/organization')?{apiContext:p=>({role:p.roleCode,orgType:p.orgType})}:id==='./payment-flow'?()=>({}):real(id),wx:{pageScrollTo(){}}});
 p.data=structuredClone(p.data);p.setData=patch=>Object.assign(p.data,patch);p.onLoad({view:'projects'});
 return {p,api,http,calls,todos,todosApi,data,requests,routes,switchPost:()=>{post={...post,postId:'hq-b',orgId:'2'};gen++;},logout:()=>{loggedIn=false;gen++;}};
}
test('237 records load through twelve server pages without loss or duplicate and large IDs remain exact',async()=>{
 const {p,calls}=fixture();await p.refresh();
 assert.equal(p.data.projectRows.length,20);assert.equal(p.data.projectTotal,237);
 while(p.data.projectHasMore)await p.loadMoreProjects();
 assert.equal(calls.length,12);assert.equal(p.data.projectRows.length,237);assert.equal(new Set(p.data.projectRows.map(x=>x.id)).size,237);
 assert.match(p.data.projectRows[0].url,/9007199254740993/);assert.equal(p.data.projectHasMore,false);
});
test('server searches find records 101 and 237, reset page number, and combine the status filter',async()=>{
 const {p,calls}=fixture();await p.refresh();await p.loadMoreProjects();
 for(const keyword of ['客户101','客户237']){
  await p.searchProjects({detail:{value:keyword}});assert.equal(p.data.projectRows.length,1);assert.equal(p.data.projectRows[0].name,keyword);assert.equal(calls.at(-1).number,1);
 }
 await p.searchProjects({detail:{value:''}});await p.selectProjectFilter({currentTarget:{dataset:{filter:'待下发'}}});
 assert.equal(calls.at(-1).states,'DESIGN_COMPLETED');assert.equal(p.data.projectTotal,118);assert.ok(p.data.projectRows.every(r=>r.status==='待下发'));
});
test('home counts and actionable tasks cover the whole organization without relying on the first page',async()=>{
 const {p,calls}=fixture();p.view='admin';await p.refresh();
 assert.equal(p.data.metrics.at(-1).value,237);assert.equal(p.data.metrics[0].value,119);assert.equal(p.data.taskCount,237);assert.equal(p.data.tasks.length,237);assert.equal(calls.length,0);
});
test('old search response cannot mix into a newer search and duplicate load-more taps coalesce',async()=>{
 const {p,api}=fixture();await p.refresh();let resolve;
 api.commissions.page=()=>new Promise(r=>resolve=r);const older=p.searchProjects({detail:{value:'旧'}});const finishOld=resolve;
 const newer=p.searchProjects({detail:{value:'新'}});resolve({list:[],total:0});await newer;
 finishOld({list:[{commission:{customerId:'old',customerName:'旧结果',status:'DESIGNING'},commissionId:'old'}],total:1});await older;assert.equal(p.data.projectRows.length,0);
 p.data.projectHasMore=true;let calls=0;api.commissions.page=()=>{calls++;return new Promise(r=>resolve=r);};
 const first=p.loadMoreProjects();p.loadMoreProjects();assert.equal(calls,1);resolve({list:[],total:0});await first;
});
test('switching account clears old rows immediately and discards a late page',async()=>{
 const {p,api,switchPost}=fixture();await p.refresh();let resolve;
 api.commissions.page=()=>new Promise(r=>resolve=r);const old=p.loadMoreProjects();const finishOld=resolve;
 switchPost();const next=p.refresh();assert.equal(p.data.projectRows.length,0);await new Promise(setImmediate);
 resolve({list:[],total:0});await next;finishOld({list:[{commission:{customerId:'old',customerName:'旧',status:'DESIGNING'},commissionId:'old'}],total:237});await old;
 assert.equal(p.data.projectRows.length,0);assert.equal(p.data.projectTotal,0);
});
test('failed next page keeps the same page available to retry',async()=>{
 const {p,api}=fixture();await p.refresh();const original=api.commissions.page;api.commissions.page=async()=>{throw Error('离线');};
 await p.loadMoreProjects();assert.equal(p.data.projectRows.length,20);assert.equal(p.data.projectHasMore,true);assert.equal(p.data.projectLoading,false);
 api.commissions.page=original;await p.loadMoreProjects();assert.equal(p.data.projectRows.length,40);
});

const recentRecord=(id='9007199254741229',kind='DISPATCH',name='客户237')=>({commissionId:id,customerName:name,kind,handledAt:'2026-11-22T10:00:00Z'});
function rendered(page){
 const {render}=require('../role-page-runtime.cjs');
 const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]);
 return flat(render(path.resolve('miniprogram/pages/workflow/commission/index.wxml'),page.data));
}
test('home renders server-selected recent handling beyond the first 100 commissions with exact project links',async()=>{
 const {p,api,calls}=fixture();p.view='admin';
 const rows=[recentRecord(),recentRecord('9007199254741093','COMPLETE','客户101')];
 api.commissions.workbenchRecent=async()=>rows;
 await p.refresh();
 assert.deepEqual(Array.from(p.data.recent,r=>r.id),rows.map(r=>r.commissionId));
 assert.equal(p.data.recent[0].recentText,'成果已下发');assert.equal(p.data.recent[1].recentText,'已登记设计完成');
 assert.match(p.data.recent[0].url,/commissionId=9007199254741229/);
 const buttons=rendered(p).filter(n=>n.tag==='button'&&n.attrs?.class==='admin-list-row');
 assert.equal(buttons.length,2);assert.equal(buttons[0].attrs['data-url'],p.data.recent[0].url);
 assert.equal(p.data.metrics.at(-1).value,237);assert.equal(p.data.taskCount,237);assert.equal(calls.length,0);
});
test('recent loading and failure keep counts and todos usable; retry fetches only recent handling',async()=>{
 const {p,api,requests,calls}=fixture();p.view='admin';let rejectRecent;
 api.commissions.workbenchRecent=()=>{requests.recent++;return new Promise((_,reject)=>rejectRecent=reject);};
 const pending=p.refresh();await new Promise(setImmediate);
 assert.equal(p.data.metrics.at(-1).value,237);assert.equal(p.data.taskCount,237);assert.equal(p.data.recentLoading,true);
 assert.ok(!rendered(p).some(n=>n.text==='暂无办理记录'));
 rejectRecent(Error('offline'));await pending;
 assert.equal(p.data.platformStatus,'ready');assert.equal(p.data.recentLoading,false);assert.ok(p.data.recentError);
 assert.ok(!rendered(p).some(n=>n.text==='暂无办理记录'));
 assert.ok(rendered(p).some(n=>n.attrs?.bindtap==='retryRecent'));
 api.commissions.workbenchRecent=async()=>{requests.recent++;return [recentRecord()];};
 await p.retryRecent();assert.equal(p.data.recent.length,1);assert.equal(p.data.recentError,'');
 assert.deepEqual(requests,{counts:1,todos:1,recent:2});assert.equal(calls.length,0);
});
test('only a successful empty response displays no handling records; project paging never fetches recent',async()=>{
 const {p,api,requests}=fixture();await p.refresh();assert.equal(requests.recent,0);
 p.view='admin';await p.refresh();assert.equal(p.data.recent.length,0);assert.equal(p.data.recentError,'');
 assert.ok(rendered(p).some(n=>n.text==='暂无办理记录'));
 api.commissions.workbenchRecent=async()=>({});await p.retryRecent();assert.ok(p.data.recentError);
 assert.ok(!rendered(p).some(n=>n.text==='暂无办理记录'));
});
test('an older home refresh cannot overwrite newer recent handling within the same login',async()=>{
 const {p,api}=fixture();p.view='admin';let finishOld;
 api.commissions.workbenchRecent=()=>new Promise(resolve=>finishOld=resolve);const older=p.refresh();
 api.commissions.workbenchRecent=async()=>[recentRecord('9007199254741230','COMPLETE','新记录')];
 await p.refresh();finishOld([recentRecord()]);await older;
 assert.equal(p.data.recent[0].name,'新记录');assert.equal(p.data.recentError,'');
});
test('switching posts clears old handling and discards the original post response',async()=>{
 const {p,api,switchPost}=fixture();p.view='admin';api.commissions.workbenchRecent=async()=>[recentRecord()];await p.refresh();
 let finishOld;api.commissions.workbenchRecent=()=>new Promise(resolve=>finishOld=resolve);const older=p.refresh();
 switchPost();api.commissions.workbenchRecent=async()=>[];const newer=p.refresh();assert.equal(p.data.recent.length,0);
 await newer;finishOld([recentRecord()]);await older;assert.equal(p.data.recent.length,0);
});
for(const leave of ['logout','unload','projects'])test(leave+' prevents a late recent response from repopulating the page',async()=>{
 const {p,api,logout}=fixture();p.view='admin';let finish;
 api.commissions.workbenchRecent=()=>new Promise(resolve=>finish=resolve);const pending=p.refresh();
 if(leave==='logout'){logout();p.refresh();}else if(leave==='unload')p.onUnload();else p.view='projects';
 finish([recentRecord()]);await pending;assert.equal(p.data.recent.length,0);
});
test('recent API uses the bounded headquarters read endpoint',async()=>{
 const module={exports:{}},calls=[];
 vm.runInNewContext(fs.readFileSync('miniprogram/api/franchise/commission.js','utf8'),{module,require:()=>({get:async(...args)=>{calls.push(args);return [];}})});
 await module.exports.commissions.workbenchRecent();assert.deepEqual(calls,[['/franchise/commissions/workbench-recent']]);
});

test('first project failure shows an inline retry instead of an empty result and keeps the original conditions',async()=>{
 const {p,api,calls}=fixture(),readPage=api.commissions.page;
 p.setData({keyword:'客户101',projectFilter:'设计中'});
 api.commissions.page=async()=>{throw Error('请求超时');};await p.refresh();
 assert.ok(!rendered(p).some(n=>n.text==='没有符合当前条件的项目。'));
 assert.ok(rendered(p).some(n=>n.text==='项目暂时没加载，请重试'));
 assert.ok(rendered(p).some(n=>n.attrs?.bindtap==='retryProjects'));
 assert.equal(p.data.keyword,'客户101');assert.equal(p.data.projectFilter,'设计中');
 api.commissions.page=readPage;await p.retryProjects();
 assert.equal(p.data.projectRows.length,1);assert.equal(p.data.projectRows[0].name,'客户101');
 assert.deepEqual(calls.at(-1),{number:1,size:20,keyword:'客户101',states:'DESIGNING'});
 assert.equal(p.data.projectError,'');
});
test('project pages start before counts finish and remain searchable and pageable when counts fail',async()=>{
 const {p,api,calls,requests}=fixture();let rejectCounts;
 api.commissions.workbenchCounts=()=>{requests.counts++;return new Promise((_,reject)=>rejectCounts=reject);};
 const pending=p.refresh();await new Promise(setImmediate);const started=calls.length;
 rejectCounts(Error('统计超时'));await pending;
 assert.equal(started,1);assert.equal(p.data.projectRows.length,20);assert.ok(p.data.countsError);
 assert.ok(rendered(p).some(n=>n.attrs?.bindtap==='retryWorkbenchCounts'));
 await p.loadMoreProjects();assert.equal(p.data.projectRows.length,40);
 await p.searchProjects({detail:{value:'客户237'}});assert.equal(p.data.projectRows[0].name,'客户237');
 const pageRequests=calls.length;
 api.commissions.workbenchCounts=async()=>{requests.counts++;return {DESIGNING:119,DESIGN_COMPLETED:118};};
 await p.retryWorkbenchCounts();assert.equal(calls.length,pageRequests);assert.equal(p.data.countsError,'');
 assert.equal(p.data.projectFilters[0].count,237);assert.equal(p.data.keyword,'客户237');
});
test('failed search and malformed results never claim there are no matching projects',async()=>{
 const {p,api}=fixture(),readPage=api.commissions.page;await p.refresh();
 await p.selectProjectFilter({currentTarget:{dataset:{filter:'待下发'}}});
 api.commissions.page=async()=>{throw Error('搜索超时');};
 await p.searchProjects({detail:{value:'客户102'}});
 assert.equal(p.data.projectRows.length,0);assert.equal(p.data.keyword,'客户102');assert.equal(p.data.projectFilter,'待下发');
 assert.ok(!rendered(p).some(n=>n.text==='没有符合当前条件的项目。'));
 api.commissions.page=async()=>({});await p.retryProjects();
 assert.ok(!rendered(p).some(n=>n.text==='没有符合当前条件的项目。'));
 api.commissions.page=readPage;await p.retryProjects();assert.equal(p.data.projectRows[0].name,'客户102');
 await p.searchProjects({detail:{value:'没有匹配的客户'}});
 assert.ok(rendered(p).some(n=>n.text==='没有符合当前条件的项目。'));
 assert.ok(!rendered(p).some(n=>n.attrs?.bindtap==='retryProjects'));
});
test('inline project retry continues a failed next page without discarding the pages already loaded',async()=>{
 const {p,api,calls}=fixture(),readPage=api.commissions.page;await p.refresh();
 api.commissions.page=async()=>{throw Error('下一页超时');};await p.loadMoreProjects();
 assert.equal(p.data.projectRows.length,20);assert.ok(p.data.projectError);
 assert.ok(rendered(p).some(n=>n.attrs?.bindtap==='retryProjects'));
 api.commissions.page=readPage;await p.retryProjects();
 assert.equal(calls.at(-1).number,2);assert.equal(p.data.projectRows.length,40);assert.equal(p.data.projectError,'');
});

for(const failed of ['counts','todos'])for(const recentFirst of [true,false])test(failed+' failure preserves recent handling returned '+(recentFirst?'before':'after')+' the failure; retry reads only that block',async()=>{
 const {p,api,todosApi,requests,routes}=fixture();p.view='admin';
 const failure=deferred(),recent=deferred(),readCounts=api.commissions.workbenchCounts,readTodos=todosApi.list;
 api.commissions.workbenchRecent=()=>{requests.recent++;return recent.promise;};
 if(failed==='counts')api.commissions.workbenchCounts=()=>{requests.counts++;return failure.promise;};
 else todosApi.list=()=>{requests.todos++;return failure.promise;};
 const pending=p.refresh();
 if(recentFirst){recent.resolve([recentRecord()]);await new Promise(setImmediate);assert.equal(p.data.recent.length,1);failure.reject(Error('offline'));}
 else{failure.reject(Error('offline'));await new Promise(setImmediate);recent.resolve([recentRecord()]);}
 await pending;
 assert.equal(p.data.recent.length,1);assert.equal(p.data.recentError,'');assert.equal(p.data.error,'');
 assert.equal(failed==='counts'?p.data.taskCount:p.data.metrics.at(-1).value,237);
 assert.ok(p.data[failed+'Error']);assert.ok(rendered(p).some(n=>n.attrs?.bindtap===(failed==='counts'?'retryWorkbenchCounts':'retryTodos')));
 assert.ok(!rendered(p).some(n=>n.text==='暂无办理记录'||failed==='todos'&&n.text==='今日暂无设计待办'));
 const button=rendered(p).find(n=>n.attrs?.class==='admin-list-row');p.openTask({currentTarget:{dataset:{url:button.attrs['data-url']}}});
 assert.match(routes[0],/commissionId=9007199254741229/);
 const retained=JSON.stringify({recent:p.data.recent,other:failed==='counts'?p.data.tasks:p.data.metrics});
 api.commissions.workbenchCounts=readCounts;todosApi.list=readTodos;
 await p[failed==='counts'?'retryWorkbenchCounts':'retryTodos']();
 assert.equal(p.data[failed+'Error'],'');assert.equal(JSON.stringify({recent:p.data.recent,other:failed==='counts'?p.data.tasks:p.data.metrics}),retained);
 assert.deepEqual(requests,{counts:failed==='counts'?2:1,todos:failed==='todos'?2:1,recent:1});
});
test('pending or partially failed todos never display a false empty result while other home blocks are usable',async()=>{
 const {p,api,todosApi,todos}=fixture();p.view='admin';const pendingTodos=deferred();
 api.commissions.workbenchRecent=async()=>[recentRecord()];todosApi.list=()=>pendingTodos.promise;
 const loading=p.refresh();await new Promise(setImmediate);
 assert.equal(p.data.metrics.at(-1).value,237);assert.equal(p.data.recent.length,1);assert.equal(p.data.todosLoading,true);
 assert.ok(!rendered(p).some(n=>n.text==='今日暂无设计待办'));
 pendingTodos.resolve({items:todos.items,failedDomains:['franchise']});await loading;
 assert.ok(p.data.todosError);assert.equal(p.data.recent.length,1);assert.equal(p.data.metrics.at(-1).value,237);
 assert.ok(!rendered(p).some(n=>n.text==='今日暂无设计待办'));
 todosApi.list=async()=>({items:[],failedDomains:[]});await p.retryTodos();
 assert.ok(rendered(p).some(n=>n.text==='今日暂无设计待办'));assert.equal(p.data.recent.length,1);
});
test('late success and failure from an older home refresh cannot overwrite any newer block',async()=>{
 const {p,api,todosApi,todos}=fixture();p.view='admin';const counts=deferred(),tasks=deferred(),recent=deferred();
 api.commissions.workbenchCounts=()=>counts.promise;todosApi.list=()=>tasks.promise;api.commissions.workbenchRecent=()=>recent.promise;
 const older=p.refresh();
 api.commissions.workbenchCounts=async()=>({DESIGNING:3});todosApi.list=async()=>({items:todos.items.slice(0,1),failedDomains:[]});
 api.commissions.workbenchRecent=async()=>[recentRecord('9007199254741230','COMPLETE','新记录')];await p.refresh();
 const newest=JSON.stringify(p.data);
 counts.reject(Error('旧统计超时'));tasks.resolve(todos);recent.resolve([recentRecord()]);await older;
 assert.equal(JSON.stringify(p.data),newest);assert.equal(p.data.taskCount,1);assert.equal(p.data.metrics.at(-1).value,3);
});
for(const leave of ['switchPost','logout','unload','projects'])test(leave+' prevents late home block responses from clearing or refilling the next screen',async()=>{
 const {p,api,todosApi,todos,switchPost,logout}=fixture();p.view='admin';api.commissions.workbenchRecent=async()=>[recentRecord()];await p.refresh();
 const counts=deferred(),tasks=deferred(),recent=deferred();
 api.commissions.workbenchCounts=()=>counts.promise;todosApi.list=()=>tasks.promise;api.commissions.workbenchRecent=()=>recent.promise;
 const older=p.refresh();
 api.commissions.workbenchCounts=async()=>({});todosApi.list=async()=>({items:[],failedDomains:[]});api.commissions.workbenchRecent=async()=>[];
 if(leave==='switchPost'){
  switchPost();const next=p.refresh();assert.equal(p.data.recent.length,0);assert.equal(p.data.tasks.length,0);assert.ok(p.data.metrics.every(row=>row.value==='—'));await next;
 }else if(leave==='logout'){
  logout();p.refresh();assert.equal(p.data.recent.length,0);assert.equal(p.data.tasks.length,0);assert.equal(p.data.metrics.length,0);
 }else if(leave==='projects'){
  await p.openMetric({currentTarget:{dataset:{filter:'设计与修改'}}});await new Promise(setImmediate);assert.equal(p.data.projectRows.length,20);
 }else p.onUnload();
 const current=JSON.stringify(p.data),lateWrites=[];const setData=p.setData;p.setData=patch=>{lateWrites.push(patch);setData(patch);};
 counts.reject(Error('旧首页统计超时'));tasks.resolve(todos);recent.resolve([recentRecord()]);await older;
 assert.deepEqual(lateWrites,[]);assert.equal(JSON.stringify(p.data),current);
 if(leave==='projects'){assert.equal(p.data.view,'projects');assert.equal(p.data.projectRows.length,20);}
});
