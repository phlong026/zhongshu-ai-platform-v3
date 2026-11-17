const test=require('node:test'),assert=require('node:assert/strict');
const Draft=require('../miniprogram/pages/studio/characters/profile-draft');
const clone=x=>structuredClone(x), tick=()=>new Promise(r=>setImmediate(r));
const later=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject};};
test('draft transport uses the authenticated platform HTTP client and encoded keys',()=>{
  const calls=[],http=Object.fromEntries(['get','put','post'].map(method=>[method,(...args)=>calls.push([method,...args])]));
  const api=require('../miniprogram/pages/studio/characters/draft-api')(http);
  api.draft('new');api.saveDraft('21',{revision:2,form:{name:'张工'}});api.publishDraft('21',3);
  assert.deepEqual(calls,[['get','/franchise/content/personas/drafts/new'],['put','/franchise/content/personas/drafts/21',{revision:2,form:{name:'张工'}}],['post','/franchise/content/personas/drafts/21/publish',{revision:3}]]);
});
function setup(options={}){
  const disk=options.disk||new Map(),writes=[],statuses=[];let shown,locked=false,valid=true;
  let remote={exists:false,revision:0,form:{}};
  const api={draft:async()=>clone(remote),saveDraft:async(key,data)=>{writes.push(clone(data));if(data.revision!==remote.revision)throw{code:1114000009};remote={exists:true,revision:remote.revision+1,form:clone(data.form)};return clone(remote);},publishDraft:async()=>({id:'101'}),...options.api};
  const storage={getStorageSync:k=>disk.get(k),setStorageSync:(k,v)=>disk.set(k,clone(v)),removeStorageSync:k=>disk.delete(k)};
  const draft=Draft.create({api,storage,storageKey:options.key||'user-1:new',key:'new',form:{name:'',who:'',enabled:true},valid:()=>valid,delay:100000,onForm:x=>shown=x,onStatus:x=>statuses.push(x),onLock:x=>locked=x});
  return{draft,disk,writes,statuses,api,get shown(){return shown},get locked(){return locked},remote:x=>{remote=x},invalidate:()=>valid=false};
}
test('input persists synchronously, cloud sync keeps partial fields, reopening restores both pages',async()=>{
  const h=setup();await h.draft.start();h.draft.change({name:'',who:'乡墅顾问',enabled:true});
  assert.equal(h.disk.get('user-1:new').form.who,'乡墅顾问');assert.equal(h.writes.length,0);
  await h.draft.flush();assert.equal(h.writes.length,1);assert.equal(h.statuses.at(-1),'草稿已自动保存');
  h.draft.stop();const reopened=setup({disk:h.disk,api:h.api});await reopened.draft.start();
  assert.equal(reopened.shown.who,'乡墅顾问');reopened.draft.stop();
});
test('a late read never replaces typing and multiple edits coalesce',async()=>{
  const read=later(),h=setup({api:{draft:()=>read.promise}}),load=h.draft.start();
  h.draft.change({name:'张工',who:'顾问',enabled:true});h.draft.change({name:'张工',who:'本地乡墅顾问',enabled:true});
  read.resolve({exists:false,revision:0,form:{}});await load;await h.draft.flush();
  assert.equal(h.writes.length,1);assert.equal(h.writes[0].form.who,'本地乡墅顾问');h.draft.stop();
});
test('typing during a request is sent after it, never overwritten by its response',async()=>{
  const ack=later();let count=0;
  const h=setup({api:{saveDraft:async(k,d)=>{h.writes.push(clone(d));if(++count===1)return ack.promise;return{revision:d.revision+1};}}});
  await h.draft.start();h.draft.change({name:'张工',who:'A',enabled:true});const run=h.draft.flush();await tick();
  h.draft.change({name:'张工',who:'B',enabled:true});ack.resolve({revision:1});await run;
  assert.deepEqual(h.writes.map(w=>[w.revision,w.form.who]),[[0,'A'],[1,'B']]);
  assert.equal(h.disk.get('user-1:new').form.who,'B');assert.equal(h.disk.get('user-1:new').dirty,false);h.draft.stop();
});
test('offline input survives closing, retry loads cloud before uploading',async()=>{
  const disk=new Map(),h=setup({disk,api:{draft:async()=>{throw Error('offline')}}});
  await assert.rejects(h.draft.start());h.draft.change({name:'张工',who:'断网填写',enabled:true});h.draft.stop();
  const next=setup({disk});await next.draft.start();await next.draft.flush();
  assert.equal(next.writes[0].form.who,'断网填写');next.draft.stop();
});
test('cloud conflict keeps local values until an explicit keep-local choice',async()=>{
  const disk=new Map([['user-1:new',{form:{name:'张工',who:'本机',enabled:true},revision:1,dirty:true}]]);
  const h=setup({disk});h.remote({exists:true,revision:2,form:{name:'张工',who:'另一台设备',enabled:true}});
  await h.draft.start();await assert.rejects(h.draft.flush());assert.equal(h.shown.who,'本机');assert.equal(h.writes.length,0);
  await h.draft.resolve(true);assert.equal(h.writes[0].revision,2);assert.equal(h.writes[0].form.who,'本机');h.draft.stop();
});
test('choosing cloud restores its content and a different account has its own cache',async()=>{
  const h=setup();await h.draft.start();h.draft.change({name:'本机',who:'顾问',enabled:true});
  h.remote({exists:true,revision:1,form:{name:'云端',who:'设计师',enabled:true}});await h.draft.resolve(false);
  assert.equal(h.shown.name,'云端');assert.equal(h.writes.length,0);
  const other=setup({disk:h.disk,key:'user-2:new'});await other.draft.start();assert.equal(other.shown.name,'');
  h.invalidate();h.draft.change({name:'越界'});await assert.rejects(h.draft.flush());h.draft.stop();other.draft.stop();
});
test('publish waits for latest autosave and removes cache only on acknowledged success',async()=>{
  let revision;const h=setup({api:{publishDraft:async(k,r)=>{revision=r;return{id:'101'};}}});
  await h.draft.start();h.draft.change({name:'张工',who:'乡墅顾问',enabled:true});
  assert.equal((await h.draft.publish()).id,'101');assert.equal(revision,1);assert.equal(h.disk.size,0);
});
test('unknown publish result freezes the form and retries the same revision',async()=>{
  const revisions=[];let count=0;const h=setup({api:{publishDraft:async(k,r)=>{revisions.push(r);if(++count===1)throw Error('timeout');return{id:'101'};}}});
  await h.draft.start();h.draft.change({name:'张工',who:'顾问',enabled:true});await assert.rejects(h.draft.publish());
  assert.equal(h.locked,true);assert.equal(h.draft.change({name:'另一个人'}),false);
  await h.draft.publish();assert.deepEqual(revisions,[1,1]);assert.equal(h.writes.length,1);
});
test('clean cache is superseded by a consumed cloud draft; unknown commit can recover after restart',async()=>{
  const disk=new Map([['user-1:new',{form:{name:'旧草稿',who:'',enabled:true},revision:1,dirty:false}]]);
  const h=setup({disk});h.remote({exists:false,revision:2,form:{}});await h.draft.start();assert.equal(h.shown.name,'');h.draft.stop();
  disk.set('user-1:new',{form:{name:'已提交',who:'',enabled:true},revision:1,dirty:false,pending:1});
  let sent;const again=setup({disk,api:{draft:async()=>({exists:false,revision:2,form:{},publishedRevision:1,publishedPersonaId:'101'}),publishDraft:async(k,r)=>{sent=r;return{id:'101'}}}});
  await again.draft.start();assert.equal(again.locked,true);assert.equal((await again.draft.publish()).id,'101');assert.equal(sent,1);
});
test('an older editor response cannot overwrite a reopened editor local cache',async()=>{
  const response=later(),h=setup({api:{saveDraft:()=>response.promise}});await h.draft.start();
  h.draft.change({name:'张工',who:'旧页面',enabled:true});const run=h.draft.flush();await tick();h.draft.stop();
  const next=setup({disk:h.disk});await next.draft.start();next.draft.change({name:'张工',who:'重新打开后的新填写',enabled:true});
  response.resolve({revision:1});await run;
  assert.equal(h.disk.get('user-1:new').form.who,'重新打开后的新填写');next.draft.stop();
});
