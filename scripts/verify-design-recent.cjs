const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function harness({projects=[],details={},jobs={},deferred}={}){
 let valid=true;const calls=[],routes=[],timers=[];const module={exports:{}};
 const http={token:()=>true,requestGuard:()=>()=>valid,post:async(url,body)=>{calls.push([url,body]);return body.path==='/design-projects'?(deferred||{list:projects}):details[body.path.split('/').pop()];},get:async url=>jobs[url.split('/').pop()]};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/design-recent'),'utf8'),{module,exports:module.exports,require:p=>p.endsWith('/http')?http:{assets:{downloadUrl:async()=>({downloadUrl:'https://assets.test/real.png'})}},wx:{navigateTo:o=>routes.push(o.url)},setTimeout:f=>{timers.push(f);return timers.length;},clearTimeout(){}});
 const page={data:{recentTasks:[],recentCount:0},setData(p){Object.assign(this.data,p);}};
 return {api:module.exports,page,calls,routes,timers,invalidate:()=>valid=false};
}
test('recent design tasks show actual counts and separate in-progress, success and failure routes',async()=>{
 const h=harness({projects:[{projectId:'11',name:'客户甲'},{projectId:'12',name:'客户乙'},{projectId:'13'}],details:{11:{projectId:'11',jobId:'101',stage:'FLAT'},12:{projectId:'12',jobId:'102',stage:'ELEVATION',resultVersionId:'202',resumeAction:'VIEW_RESULT'},13:{projectId:'13',jobId:'103',stage:'FLAT'}},jobs:{101:{jobId:'101',status:'RUNNING',requestedCount:2,acceptedCount:1,imageOptions:{resolution:'2K'}},102:{jobId:'102',status:'SUCCEEDED',requestedCount:1,acceptedCount:1},103:{jobId:'103',status:'FAILED',requestedCount:2,acceptedCount:0}}});
 await h.api.load(h.page);assert.equal(h.page.data.recentCount,3);assert.equal(h.page.data.recentRunning,1);
 assert.equal(h.page.data.recentTasks[0].progress,'已生成 1/2 张');assert.equal(h.page.data.recentTasks[0].statusText,'生成中');assert.equal(h.page.data.recentTasks[2].action,'查看原因');
 for(const id of ['11','12','13'])h.api.open(h.page,{currentTarget:{dataset:{id}}});
 assert.match(h.routes[0],/generating\?projectId=11&jobId=101/);assert.match(h.routes[1],/result\?projectId=12&resultVersionId=202/);assert.match(h.routes[2],/jobId=103/);
 assert.equal(h.timers.length,1);assert.equal(h.calls[0][1].query.limit,3);
});
test('late task responses cannot refill a hidden or switched-account design page',async()=>{
 let resolve;const pending=new Promise(r=>resolve=r),h=harness({deferred:pending});
 const work=h.api.load(h.page);h.api.stop(h.page);resolve({list:[{projectId:'99'}]});await work;assert.equal(h.page.data.recentCount,0);assert.equal(h.calls.length,1);
});
test('missing backend fields never become invented counts, image previews or finished status',()=>{
 const h=harness(),row=h.api.row({projectId:'42',stage:'FLAT'});
 assert.equal(row.progress,'');assert.equal(row.metadata,'');assert.equal(row.coverUrl,'');assert.equal(row.statusText,'待确认');assert.equal(row.running,false);
});

test('a new successful generation with V1 still present opens candidate selection until confirmation',()=>{
 for(const stage of ['FLAT','ELEVATION']){
  const h=harness(),project={projectId:'11',jobId:'102',stage,resultVersionId:'201',resumeAction:'SELECT_'+stage};
  h.page.data.recentTasks=[h.api.row(project,{jobId:'102',status:'SUCCEEDED'})];
  assert.equal(h.page.data.recentTasks[0].action,'选择新方案');
  h.api.open(h.page,{currentTarget:{dataset:{id:'11'}}});assert.match(h.routes[0],/generating\?projectId=11&jobId=102/);assert(!h.routes[0].includes('201'));
  h.page.data.recentTasks=[h.api.row({...project,resumeAction:'VIEW_RESULT',resultVersionId:'202'},{jobId:'102',status:'SUCCEEDED'})];
  h.api.open(h.page,{currentTarget:{dataset:{id:'11'}}});assert.match(h.routes[1],/result\?projectId=11&resultVersionId=202/);
 }
});
test('missing recovery action never infers that the historical version belongs to the current job',()=>{
 const h=harness();h.page.data.recentTasks=[h.api.row({projectId:'11',resultVersionId:'201',jobId:'102'},{status:'SUCCEEDED'})];
 h.api.open(h.page,{currentTarget:{dataset:{id:'11'}}});assert.match(h.routes[0],/jobId=102/);assert(!h.routes[0].includes('201'));
});
