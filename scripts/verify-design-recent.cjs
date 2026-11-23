const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
const pending=()=>{let resolve,reject;const promise=new Promise((ok,fail)=>{resolve=ok;reject=fail});return {promise,resolve,reject};};
function harness({projects=[],details={},jobs={},deferred,read,download,imageInfo}={}){
 let valid=true;const calls=[],routes=[],timers=[],downloads=[],images=[],patches=[];const module={exports:{}};
 const session={token:'token-1',generation:1,userId:'user-1',tenantId:'tenant-1',post:{userId:'user-1',tenantId:'tenant-1',orgId:'org-1',postId:'post-1',roleCode:'sales',orgType:'FRANCHISEE'}};
 const http={token:()=>session.token,currentUserId:()=>session.userId,tenantId:()=>session.tenantId,sessionGeneration:()=>session.generation,
  requestGuard:()=>{const generation=session.generation;return ()=>valid&&generation===session.generation;},
  post:async(url,body)=>{calls.push([url,body]);return read?read(body):body.path==='/design-projects'?(deferred||{list:projects}):details[body.path.split('/').pop()]||{};},get:async url=>jobs[url.split('/').pop()]};
 const auth={currentApiPost:()=>session.post};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/design-recent'),'utf8'),{module,exports:module.exports,require:p=>p.endsWith('/http')?http:p.endsWith('/auth')?auth:{assets:{downloadUrl:async id=>{downloads.push(id);return download?download(id):{downloadUrl:'https://assets.test/real.png'};}}},wx:{navigateTo:o=>routes.push(o.url),getImageInfo:o=>{images.push(o.src);if(imageInfo)imageInfo(o);else o.success({path:'wxfile://'+o.src.split('/').pop()});}},setTimeout:(f,ms)=>{assert.equal(ms,15000);timers.push(f);return timers.length;},clearTimeout(){}});
 const page={data:{recentTasks:[],recentCount:0},setData(p){patches.push(JSON.parse(JSON.stringify(p)));Object.assign(this.data,p);}};
 return {api:module.exports,page,calls,routes,timers,downloads,images,patches,session,invalidate:()=>valid=false};
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

const coveredProject=()=>({projectId:'11',coverAssetId:'asset-1',resultVersionId:'version-1',jobStatus:'RUNNING'});
test('the actual 15-second poll retains a cover through signing and image loading, then replaces it in place',async()=>{
 const ticket=pending();let requests=0,nextImage;
 const h=harness({projects:[coveredProject()],download:()=>++requests===1?{downloadUrl:'https://assets.test/first.png'}:ticket.promise,
  imageInfo:o=>{if(o.src.endsWith('first.png'))o.success({path:'wxfile://first.png'});else nextImage=o;}});
 await h.api.load(h.page);await flush();assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://first.png');
 h.patches.length=0;await h.timers[0]();
 assert.equal(h.downloads.length,2);assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://first.png');
 ticket.resolve({downloadUrl:'https://assets.test/next.png'});await flush();
 assert(nextImage,'a new signed URL must be read before replacing the rendered image');
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://first.png');
 nextImage.success({path:'wxfile://next.png'});await flush();
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://next.png');
 assert(h.patches.filter(p=>p.recentTasks).every(p=>p.recentTasks.length===1&&p.recentTasks[0].coverUrl));
});
test('ordinary signing or image download failure preserves the previously usable cover',async()=>{
 for(const failure of ['signing','image']){
  let requests=0;const h=harness({projects:[coveredProject()],download:()=>++requests===1?{downloadUrl:'https://assets.test/first.png'}:failure==='signing'?Promise.reject(Error('offline')):{downloadUrl:'https://assets.test/next.png'},
   imageInfo:o=>o.src.endsWith('next.png')?o.fail({errMsg:'network timeout'}):o.success({path:'wxfile://first.png'})});
  await h.api.load(h.page);await flush();await h.api.load(h.page);await flush();
  assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://first.png',failure);
 }
});
test('a changed asset, result version or removed cover never inherits the previous preview',async()=>{
 for(const change of [{coverAssetId:'asset-2'},{resultVersionId:'version-2'},{coverAssetId:''}]){
  const project=coveredProject(),ticket=pending();let requests=0;
  const h=harness({projects:[project],download:()=>++requests===1?{downloadUrl:'https://assets.test/first.png'}:ticket.promise});
  await h.api.load(h.page);await flush();Object.assign(project,change);await h.api.load(h.page);
  assert.equal(h.page.data.recentTasks[0].coverUrl,'',JSON.stringify(change));
  ticket.resolve({downloadUrl:'https://assets.test/next.png'});await flush();
 }
});
test('session, account, tenant, post and permission changes clear old covers before the new list arrives',async()=>{
 for(const field of ['generation','userId','tenantId','post.userId','post.tenantId','post.orgId','post.postId','post.roleCode','post.orgType']){
  const project=coveredProject(),ticket=pending(),list=pending();let requests=0,hold=false;
  const h=harness({read:body=>body.path==='/design-projects'?(hold?list.promise:{list:[project]}):{},download:()=>++requests===2?ticket.promise:{downloadUrl:'https://assets.test/first.png'}});
  await h.api.load(h.page);await flush();await h.api.load(h.page);
  if(field.startsWith('post.'))h.session.post[field.slice(5)]+='-changed';else h.session[field]+='-changed';
  hold=true;const next=h.api.load(h.page);assert.equal(h.page.data.recentCount,0,field);
  ticket.resolve({downloadUrl:'https://assets.test/old.png'});await flush();assert.equal(h.page.data.recentCount,0,field);
  list.resolve({list:[project]});await next;await flush();assert.equal(h.page.data.recentCount,1,field);
  assert(!h.page.data.recentTasks[0].coverUrl.includes('old.png'),field);
 }
});
test('a late cover response also clears content after logout or a same-post role change without another load',async()=>{
 for(const logout of [false,true]){
  const ticket=pending();let requests=0;const h=harness({projects:[coveredProject()],download:()=>++requests===1?{downloadUrl:'https://assets.test/first.png'}:ticket.promise});
  await h.api.load(h.page);await flush();await h.api.load(h.page);
  if(logout){h.session.token='';h.session.generation++;}else h.session.post.roleCode='boss';
  ticket.resolve({downloadUrl:'https://assets.test/old.png'});await flush();assert.equal(h.page.data.recentCount,0);
 }
});
test('permission, missing asset and expired asset errors discard the affected cached cover',async()=>{
 for(const error of [{code:401},{code:403},{code:404},{code:410},{postInvalid:true}]){
  let requests=0;const h=harness({projects:[coveredProject()],download:()=>++requests===1?{downloadUrl:'https://assets.test/first.png'}:Promise.reject(error)});
  await h.api.load(h.page);await flush();await h.api.load(h.page);await flush();
  assert(h.page.data.recentTasks.every(row=>!row.coverUrl),JSON.stringify(error));
 }
});
test('a project-detail denial cannot silently preserve a cover from the list snapshot',async()=>{
 let deny=false;const project=coveredProject(),h=harness({read:body=>body.path==='/design-projects'?{list:[project]}:deny?Promise.reject({code:403,message:'denied'}):{}});
 await h.api.load(h.page);await flush();deny=true;await h.api.load(h.page);await flush();
 assert(h.page.data.recentTasks.every(row=>!row.coverUrl));assert.equal(h.downloads.length,1);
});
test('superseded signing failures and hidden-page image callbacks cannot erase or refill a newer cover',async()=>{
 const ticket=pending();let requests=0;const h=harness({projects:[coveredProject()],download:()=>++requests===2?ticket.promise:{downloadUrl:'https://assets.test/'+requests+'.png'}});
 await h.api.load(h.page);await flush();await h.api.load(h.page);await h.api.load(h.page);await flush();
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://3.png');ticket.reject({code:403});await flush();
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://3.png');
 let image;const hidden=harness({projects:[coveredProject()],imageInfo:o=>image=o});await hidden.api.load(hidden.page);await flush();hidden.api.stop(hidden.page);
 image.success({path:'wxfile://late.png'});await flush();assert.equal(hidden.page.data.recentTasks[0].coverUrl,'');
});
test('both rendered entry points handle actual image failure without clearing a newer replacement',async()=>{
 const h=harness({projects:[coveredProject()]});await h.api.load(h.page);await flush();
 const module={exports:{}};vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/utils/sales-design-page'),'utf8'),{module,require:p=>p==='./design-recent'?h.api:{}});
 const definition=module.exports();
 for(const file of ['templates/sales-design.wxml','pages/design/index.wxml']){
  const source=fs.readFileSync(require.resolve('../miniprogram/'+file),'utf8');
  assert.match(source,/<image[^>]*src="\{\{item.coverUrl\}\}"[^>]*data-id="\{\{item.id\}\}"[^>]*data-cover="\{\{item.coverUrl\}\}"[^>]*binderror="recentCoverError"/);
 }
 definition.recentCoverError.call(h.page,{currentTarget:{dataset:{id:'11',cover:'wxfile://old.png'}}});
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://real.png');
 definition.recentCoverError.call(h.page,{currentTarget:{dataset:{id:'11',cover:'wxfile://real.png'}}});
 assert.equal(h.page.data.recentTasks[0].coverUrl,'');
});
test('a real scheduled list failure keeps the visible card, offers inline retry and continues polling to recovery',async()=>{
 const {render}=require('./role-page-runtime.cjs');
 const project=coveredProject();let offline=false;
 const h=harness({read:body=>body.path==='/design-projects'?(offline?Promise.reject({code:500,message:'offline'}):{list:[project]}):{}});
 await h.api.load(h.page);await flush();offline=true;await h.timers[0]();await flush();
 assert.equal(h.page.data.recentCount,1);assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://real.png');
 assert.equal(h.timers.length,2);assert(h.page.data.recentError);
 const flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children)]);
 for(const file of ['templates/sales-design.wxml','pages/design/index.wxml']){
  const nodes=flatten(render(require.resolve('../miniprogram/'+file),{...h.page.data,entranceEnabled:true}));
  assert(nodes.some(n=>n.tag==='image'&&n.attrs.src==='wxfile://real.png'));
  assert(nodes.some(n=>n.tag==='button'&&n.attrs.bindtap==='retryRecent'&&n.parent.attrs.class.includes('recent-heading')));
 }
 offline=false;project.name='已更新的任务';await h.timers[1]();await flush();
 assert.equal(h.page.data.recentError,'');assert(h.page.data.recentTasks[0].title.startsWith('已更新的任务'));
});
test('a transient detail failure keeps the last verified row but never hides a known new asset or version',async()=>{
 const project={projectId:'11',jobStatus:'RUNNING'};let detailOffline=false;
 const h=harness({read:body=>body.path==='/design-projects'?{list:[project]}:detailOffline?Promise.reject({code:500,message:'detail offline'}):{...coveredProject(),name:'已核验设计'}});
 await h.api.load(h.page);await flush();detailOffline=true;await h.timers[0]();await flush();
 assert.equal(h.page.data.recentTasks[0].coverUrl,'wxfile://real.png');assert(h.page.data.recentTasks[0].title.startsWith('已核验设计'));
 assert(h.page.data.recentError);assert.equal(h.timers.length,2);
 project.coverAssetId='';await h.api.load(h.page);await flush();assert.equal(h.page.data.recentTasks[0].coverUrl,'');
});
test('a current list denial removes private rows and stops their polling',async()=>{
 for(const error of [{code:401},{code:403},{code:404},{code:410},{postInvalid:true}]){
  let deny=false;const h=harness({read:body=>body.path==='/design-projects'?(deny?Promise.reject(error):{list:[coveredProject()]}):{}});
  await h.api.load(h.page);await flush();deny=true;await h.timers[0]();await flush();
  assert.equal(h.page.data.recentCount,0);assert.equal(h.timers.length,1);
 }
});
