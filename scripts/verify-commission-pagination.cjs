const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const {createRequire}=require('node:module');
function fixture(){
 const file=path.resolve('miniprogram/pages/workflow/commission/index.js'),real=createRequire(file),calls=[],guards={};
 let p,post={postId:'hq-a',orgId:'1',orgType:'headquarters',roleCode:'hq-design-admin'},gen=1,loggedIn=true;
 const data=Array.from({length:237},(_,i)=>({commission:{id:String(9007199254740993n+BigInt(i)),customerId:String(i+1),customerName:'客户'+String(i+1).padStart(3,'0'),status:i%2?'DESIGN_COMPLETED':'DESIGNING'},commissionId:String(9007199254740993n+BigInt(i)),allowedActions:[i%2?'design-dispatch':'design-complete']}));
 const requests={counts:0,recent:0,todos:0};
 const http={token:()=>loggedIn?'token':'',sessionGeneration:()=>gen,get:async()=>({}),requestGuard:key=>{const stamp=guards[key]={},actor=post.postId,session=gen;return()=>loggedIn&&guards[key]===stamp&&post.postId===actor&&gen===session;}};
 const api={commissions:{workbenchCounts:async()=>{requests.counts++;return {DESIGNING:119,DESIGN_COMPLETED:118};},workbenchRecent:async()=>{requests.recent++;return [];},page:async(number,size,keyword,states)=>{
   calls.push({number,size,keyword,states});const list=data.filter(r=>(!keyword||r.commission.customerName.includes(keyword))&&(!states||states.split(',').includes(r.commission.status)));
   return {list:list.slice((number-1)*size,number*size),total:list.length};
 }}};
 const todos={items:data.map(r=>({key:'franchise:design-complete:'+r.commissionId,title:r.commission.customerName,actionable:true,appPath:'/pages/workflow/commission/index?commissionId='+r.commissionId})),failedDomains:[]};
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:d=>p=d,require:id=>id.endsWith('/api/http')?http:id.endsWith('/api/auth')?{currentApiPost:()=>post}:id.endsWith('/api/franchise/commission')?api:id.endsWith('/api/todos')?{list:async()=>{requests.todos++;return todos;}}:id.endsWith('/organization')?{apiContext:p=>({role:p.roleCode,orgType:p.orgType})}:id==='./payment-flow'?()=>({}):real(id),wx:{pageScrollTo(){}}});
 p.data=structuredClone(p.data);p.setData=patch=>Object.assign(p.data,patch);p.onLoad({view:'projects'});
 return {p,api,http,calls,todos,data,requests,switchPost:()=>{post={...post,postId:'hq-b',orgId:'2'};gen++;},logout:()=>{loggedIn=false;gen++;}};
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
 const {render}=require('./role-page-runtime.cjs');
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
