const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const templates = require('../miniprogram/pages/studio/publication-cover-templates');
const ready = { requestId: '501', status: 'READY', terminal: true, coverAssetId: '301' };

function fixture(options = {}) {
  const storage = options.storage || new Map(), calls = [], applied = [];
  let live = true;
  const owner = { tenant:'1',user:'10',org:'20',post:'30',...options.owner };
  const scope = () => owner.org + ':' + owner.post;
  const page = { actorScope:scope(), data:{ view:'publish',workId:options.workId||'80',title:'发布标题',
    coverAssetId:'299',cover:'https://cdn.example.com/current.jpg',coverReady:true,
    coverTemplates:[{id:'template-a',title:'模板 A',url:'https://cdn.example.com/template-a.jpg'}],
    coverTemplatesLoaded:true,coverTemplatesLoading:false,coverTemplatesPage:1,coverTemplateId:'template-a',
    coverGenerationTitle:'封面标题',coverCanvasPreset:'9:16',coverGenerationPending:false,coverGenerationLocked:false,
    coverGenerationTerminal:false,coverGenerationStatus:'',coverGeneratedAssetId:'',coverGeneratedPreview:'',
    coverGenerating:false,coverUsing:false,coverLoading:false,coverUploading:false },
    setData(patch){Object.assign(this.data,patch);},persistEditor(){} };
  const env = { post:{orgId:owner.org,postId:owner.post},
    http:{ token:()=>live?'token':'',currentUserId:()=>owner.user,tenantId:()=>owner.tenant,sessionGuard:()=>()=>live },
    studio:{scope},wx:{getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,structuredClone(value)),
      removeStorageSync:key=>storage.delete(key),showModal:options=>options.success({confirm:true})},
    message:error=>error.message||error.errMsg||'服务暂不可用',
    api:{covers:{list:async query=>{calls.push(['list',structuredClone(query)]);return {list:[],total:0,page:query.page,size:query.size};},
      generate:async(body,key)=>{calls.push(['generate',structuredClone(body),key]);return ready;}}},
    previewCover:async(_env,assetId)=>{calls.push(['preview',assetId]);return 'https://cdn.example.com/'+assetId+'.jpg';},
    useCover:async(target,_env,assetId)=>{applied.push(assetId);target.setData({coverAssetId:assetId,cover:'https://cdn.example.com/'+assetId+'.jpg'});return true;}
  };
  return {page,env,storage,calls,applied,revoke:()=>{live=false;}};
}

test('template list uses real paged responses, distinguishes empty from configuration errors, and has no fallback rows',async()=>{
  const f=fixture();f.page.data.coverTemplates=[];f.page.data.coverTemplatesLoaded=false;
  await templates.load(f.page,f.env);
  assert.deepEqual(f.calls[0],['list',{page:1,size:12}]);assert.deepEqual(f.page.data.coverTemplates,[]);
  assert.equal(f.page.data.coverTemplatesLoaded,true);assert.equal(f.page.data.coverTemplatesError,'');
  f.env.api.covers.list=async()=>{throw {code:1114000006};};await templates.load(f.page,f.env);
  assert.match(f.page.data.coverTemplatesError,/尚未配置/);assert.equal(f.page.data.coverTemplates.length,0);
  f.env.api.covers.list=async query=>({list:[{id:'template-b',title:'模板 B',url:'https://cdn.example.com/b.jpg'}],total:13,page:query.page,size:query.size});
  await templates.load(f.page,f.env);assert.equal(f.page.data.coverTemplatesHasMore,true);
  await templates.load(f.page,f.env,true);assert.equal(f.page.data.coverTemplatesHasMore,false);
  assert.equal(f.page.data.coverTemplates.length,1,'pagination deduplicates provider IDs');
});

test('generation persists its exact request before sending and never applies an unconfirmed candidate',async()=>{
  const f=fixture();
  f.env.api.covers.generate=async(body,key)=>{
    assert.equal(f.storage.size,1);const stored=[...f.storage.values()][0];
    assert.deepEqual(stored.payload,body);assert.equal(stored.key,key);assert.equal(stored.workId,'80');return ready;
  };
  await templates.generate(f.page,f.env);
  assert.equal(f.page.data.coverGenerationStatus,'READY');assert.equal(f.page.data.coverGeneratedAssetId,'301');
  assert.equal(f.page.data.coverGeneratedPreview,'https://cdn.example.com/301.jpg');
  assert.equal(f.page.data.coverAssetId,'299');assert.equal(f.applied.length,0);assert.equal(f.storage.size,1);
  await templates.use(f.page,f.env);assert.deepEqual(f.applied,['301']);assert.equal(f.page.data.coverAssetId,'301');
  assert.equal(f.storage.size,0);assert.equal(f.page.data.coverGenerationPending,false);
});

test('storage failure prevents the first generation request',async()=>{
  const f=fixture();f.env.wx.setStorageSync=()=>{throw Error('storage unavailable');};
  await templates.generate(f.page,f.env);
  assert.equal(f.calls.length,0);assert.match(f.page.data.coverGenerationError,/storage unavailable/);
});

test('response loss survives page exit and retries only the original payload and key',async()=>{
  const f=fixture();let original;
  f.env.api.covers.generate=async(body,key)=>{original={body:structuredClone(body),key};throw Error('网络中断');};
  await templates.generate(f.page,f.env);assert.equal(f.page.data.coverGenerationPending,true);
  const reopened=fixture({storage:f.storage});await templates.restore(reopened.page,reopened.env);
  templates.edit(reopened.page,'coverGenerationTitle','不应替换原请求');assert.equal(reopened.page.data.coverGenerationTitle,'封面标题');
  reopened.page.data.coverGenerationTitle='代码外部修改的新标题';
  reopened.env.api.covers.generate=async(body,key)=>{assert.deepEqual(body,original.body);assert.equal(key,original.key);return ready;};
  await templates.resume(reopened.page,reopened.env);assert.equal(reopened.page.data.coverGenerationStatus,'READY');
  assert.equal(reopened.page.data.coverGenerationTitle,'代码外部修改的新标题','recovery does not overwrite later edits');
});

test('PROCESSING, UNCERTAIN and ARCHIVE_PENDING keep one key and forbid a fresh generation',async()=>{
  const f=fixture(), statuses=['PROCESSING','UNCERTAIN','ARCHIVE_PENDING'];let index=0;
  f.env.api.covers.generate=async(body,key)=>{f.calls.push(['generate',structuredClone(body),key]);return {requestId:'501',status:statuses[index++],terminal:false};};
  await templates.generate(f.page,f.env);
  await templates.generate(f.page,f.env);assert.equal(index,1,'a second click cannot create another request');
  await templates.resume(f.page,f.env);assert.match(f.page.data.coverGenerationMessage,/联系管理员/);
  await templates.resume(f.page,f.env);assert.match(f.page.data.coverGenerationMessage,/保存暂未完成/);
  await templates.reset(f.page,f.env);assert.equal(f.storage.size,1,'nonterminal attempts cannot be discarded');
  const requests=f.calls.filter(call=>call[0]==='generate');assert.equal(new Set(requests.map(call=>call[2])).size,1);
  assert.equal(f.page.data.coverGenerationTerminal,false);assert.equal(f.page.data.coverGenerationLocked,true);
});

test('a definite FAILED result permits a corrected request with a fresh key',async()=>{
  const f=fixture();let count=0;
  f.env.api.covers.generate=async(body,key)=>{f.calls.push(['generate',structuredClone(body),key]);return ++count===1
    ?{requestId:'501',status:'FAILED',terminal:true,message:'标题不适合此模板'}:ready;};
  await templates.generate(f.page,f.env);assert.equal(f.page.data.coverGenerationLocked,false);
  templates.edit(f.page,'coverGenerationTitle','修改后的标题');await templates.generate(f.page,f.env);
  const requests=f.calls.filter(call=>call[0]==='generate');assert.notEqual(requests[0][2],requests[1][2]);
  assert.equal(requests[1][1].title,'修改后的标题');
});

test('a stale FAILED page cannot overwrite a newer in-progress attempt from another page',async()=>{
  const a=fixture(),requests=[];
  const generate=async(body,key)=>{requests.push({body:structuredClone(body),key});return requests.length===1
    ?{requestId:'501',status:'FAILED',terminal:true,message:'失败'}:{requestId:'502',status:'PROCESSING',terminal:false};};
  a.env.api.covers.generate=generate;await templates.generate(a.page,a.env);
  const b=fixture({storage:a.storage});b.env.api.covers.generate=generate;await templates.restore(b.page,b.env);
  templates.edit(b.page,'coverGenerationTitle','第二次标题');await templates.generate(b.page,b.env);
  const current=[...a.storage.values()][0].key;
  assert.equal(a.page.data.coverGenerationStatus,'FAILED');await templates.generate(a.page,a.env);
  assert.equal(requests.length,2);assert.equal(new Set(requests.map(row=>row.key)).size,2);
  assert.equal([...a.storage.values()][0].key,current);assert.equal(a.page.data.coverGenerationStatus,'PROCESSING');
  await templates.resume(a.page,a.env);assert.equal(requests[2].key,current);assert.equal(requests[2].body.title,'第二次标题');
});

test('stale READY actions cannot use or discard the newer request on another page',async()=>{
  for(const action of ['use','reset']){
    const a=fixture();await templates.generate(a.page,a.env);
    const b=fixture({storage:a.storage});await templates.restore(b.page,b.env);await templates.reset(b.page,b.env);
    b.env.api.covers.generate=async()=>({requestId:'502',status:'PROCESSING',terminal:false});await templates.generate(b.page,b.env);
    const current=[...a.storage.values()][0].key;await templates[action](a.page,a.env);
    assert.equal(a.applied.length,0);assert.equal([...a.storage.values()][0].key,current);
    assert.equal(a.page.data.coverGenerationStatus,'PROCESSING');
  }
});

test('a late old READY response cannot replace a newer durable attempt',async()=>{
  const a=fixture();let finish;a.env.api.covers.generate=()=>new Promise(resolve=>{finish=resolve;});
  const flight=templates.generate(a.page,a.env);
  const b=fixture({storage:a.storage});await templates.restore(b.page,b.env);await templates.resume(b.page,b.env);
  await templates.use(b.page,b.env);b.env.api.covers.generate=async()=>({requestId:'502',status:'PROCESSING',terminal:false});
  await templates.generate(b.page,b.env);const current=[...a.storage.values()][0].key;
  finish(ready);await flight;
  assert.equal([...a.storage.values()][0].key,current);assert.equal(a.page.data.coverGenerationStatus,'PROCESSING');
  assert.equal(a.page.data.coverGenerating,false);assert.equal(a.page.data.coverGeneratedAssetId,'');
});

test('confirmation checks the durable key again after its modal returns',async()=>{
  const a=fixture();await templates.generate(a.page,a.env);let confirm;
  a.env.wx.showModal=options=>{confirm=options;};const resetting=templates.reset(a.page,a.env);
  const b=fixture({storage:a.storage});await templates.restore(b.page,b.env);await templates.reset(b.page,b.env);
  b.env.api.covers.generate=async()=>({requestId:'502',status:'PROCESSING',terminal:false});await templates.generate(b.page,b.env);
  const current=[...a.storage.values()][0].key;confirm.success({confirm:true});await resetting;
  assert.equal([...a.storage.values()][0].key,current);assert.equal(a.page.data.coverGenerationStatus,'PROCESSING');
});

test('ordinary HTTP/business exceptions and invalid result contracts remain recoverable',async()=>{
  for(const response of [()=>Promise.reject({code:1114000006,message:'暂不可用'}),()=>Promise.resolve({...ready,coverAssetId:''})]){
    const f=fixture();f.env.api.covers.generate=response;await templates.generate(f.page,f.env);
    assert.equal(f.storage.size,1);assert.equal(f.page.data.coverGenerationPending,true);assert.equal(f.page.data.coverGenerationTerminal,false);
    assert.equal(f.page.data.coverGeneratedAssetId,'');assert.match(f.page.data.coverGenerationError,/继续核对/);
  }
});

test('a preview failure retains the accepted candidate until preview and explicit confirmation succeed',async()=>{
  const f=fixture();f.env.previewCover=async()=>{throw Error('图片下载失败');};
  await templates.generate(f.page,f.env);await templates.use(f.page,f.env);
  assert.equal(f.applied.length,0);assert.equal(f.storage.size,1);assert.equal(f.page.data.coverGeneratedPreview,'');
  f.env.previewCover=async()=> 'https://cdn.example.com/refreshed.jpg';await templates.previewCandidate(f.page,f.env);
  await templates.use(f.page,f.env);assert.equal(f.page.data.coverAssetId,'301');assert.equal(f.storage.size,0);
});

test('late success is stored for the original work and restored without a second provider request',async()=>{
  const f=fixture();let resolve;f.env.api.covers.generate=()=>new Promise(done=>{resolve=done;});
  const flight=templates.generate(f.page,f.env);f.page.__closed=true;resolve(ready);await flight;
  assert.equal(f.page.data.coverGenerationStatus,'PENDING');assert.equal([...f.storage.values()][0].result.status,'READY');
  const reopened=fixture({storage:f.storage});await templates.restore(reopened.page,reopened.env);
  assert.equal(reopened.page.data.coverGenerationStatus,'READY');assert.equal(reopened.calls.some(call=>call[0]==='generate'),false);
  assert.equal(reopened.page.data.coverGeneratedPreview,'https://cdn.example.com/301.jpg');
});

test('changing work or revoking the session cannot apply a late result',async()=>{
  for(const change of [f=>{f.page.data.workId='81';},f=>f.revoke()]){
    const f=fixture();let resolve;f.env.api.covers.generate=()=>new Promise(done=>{resolve=done;});
    const flight=templates.generate(f.page,f.env);change(f);resolve(ready);await flight;
    assert.equal(f.page.data.coverGeneratedAssetId,'');assert.equal(f.applied.length,0);
    assert.equal([...f.storage.values()][0].workId,'80');
  }
});

test('pending cover generation is isolated by tenant, user, organization, post and work',async()=>{
  const f=fixture();f.env.api.covers.generate=async()=>{throw Error('timeout');};await templates.generate(f.page,f.env);
  for(const options of [{owner:{tenant:'2'}},{owner:{user:'11'}},{owner:{org:'21'}},{owner:{post:'31'}},{workId:'81'}]){
    const other=fixture({...options,storage:f.storage});await templates.restore(other.page,other.env);
    assert.equal(other.page.data.coverGenerationPending,false);assert.equal(other.calls.length,0);
  }
  assert.equal(f.storage.size,1);
});

test('discarding an unused READY candidate requires confirmation before another key can be created',async()=>{
  const f=fixture();await templates.generate(f.page,f.env);const first=[...f.storage.values()][0].key;
  f.env.wx.showModal=options=>options.success({confirm:false});await templates.reset(f.page,f.env);assert.equal(f.storage.size,1);
  f.env.wx.showModal=options=>options.success({confirm:true});await templates.reset(f.page,f.env);assert.equal(f.storage.size,0);
  await templates.generate(f.page,f.env);assert.notEqual([...f.storage.values()][0].key,first);
});

test('failure to persist a returned result still displays it and preserves the original durable key',async()=>{
  const f=fixture();let writes=0;const save=f.env.wx.setStorageSync;
  f.env.wx.setStorageSync=(key,value)=>{if(++writes>1)throw Error('quota');save(key,value);};
  await templates.generate(f.page,f.env);
  assert.equal(f.page.data.coverGenerationStatus,'READY');assert.equal(f.page.data.coverGeneratedAssetId,'301');
  assert.equal(f.storage.size,1);assert.equal([...f.storage.values()][0].result,undefined);
  const reopened=fixture({storage:f.storage});await templates.restore(reopened.page,reopened.env);
  assert.equal(reopened.page.data.coverGenerationStatus,'PENDING');await templates.resume(reopened.page,reopened.env);
  assert.equal(reopened.page.data.coverGenerationStatus,'READY');
});

test('cover API wrapper uses the agreed routes, pagination, caller key and generation timeout',async()=>{
  const filename=path.resolve(__dirname,'../miniprogram/api/franchise/content.js'),calls=[];
  const context={module:{exports:{}},require:name=>{
    assert.equal(name,'../http');return {get:(...args)=>{calls.push(['get',...args]);return Promise.resolve({list:[]});},
      post:(...args)=>{calls.push(['post',...args]);return Promise.resolve(ready);}};
  }};vm.runInNewContext(fs.readFileSync(filename,'utf8'),context,{filename});
  const api=context.module.exports.covers, body={templateId:'template-a',title:'标题',canvasPreset:'3:4'};
  await api.list({page:2,size:12});await api.generate(body,'original-key');
  assert.equal(calls[0][1],'/franchise/content/covers/templates');assert.deepEqual(calls[0][2],{page:2,size:12});
  assert.equal(calls[1][1],'/franchise/content/covers');assert.equal(calls[1][2],body);
  assert.equal(calls[1][3]['Idempotency-Key'],'original-key');assert.equal(calls[1][4].timeout,120000);
});
