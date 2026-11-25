const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return{promise,resolve};};
function fixture(file,P){
  let post='A',gen=0,spec;const mod={exports:{}};const notices=[],sequences={};
  const http={currentPostId:()=>post,sessionGeneration:()=>gen,sessionGuard:()=>{const g=gen;return()=>g===gen;},requestGuard:key=>{const g=gen,s=sequences[key]=(sequences[key]||0)+1;return()=>g===gen&&s===sequences[key];},token:()=> 'token'};
  const auth={currentApiPost:()=>({postId:post,orgId:post,roleCode:'engineer'})};
  const org={roles:{engineer:{}},has:()=>false,apiContext:p=>({role:p.roleCode}),navigation:()=>({tabs:[]})};
  const wx={showToast:x=>notices.push(x),switchTab:()=>{},navigateTo:()=>{}};
  const transfer={exports:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/build/record/manager-transfer.js'),'utf8'),{
    module:transfer,wx,require:n=>n.endsWith('/http')?http:n.endsWith('/engineering')?{projects:{}}:{}
  });
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram',file),'utf8'),{
    Page:x=>{spec=x;},module:mod,wx,
    require:name=>name==='./manager-transfer'?transfer.exports:name==='../payment-plan'?{read:async()=>undefined,apply:()=>{}}:name.endsWith('/workspace-scope')?()=>String(post)+':'+gen:name.endsWith('/platform-engineering')?P:name.endsWith('/api/franchise/engineering')?{cameras:{list:async()=>[],playback:(...args)=>P.playback(...args)},progress:{handlers:async()=>[]}}:name.endsWith('/api/http')?http:name.endsWith('/api/auth')?auth:name.endsWith('/organization')?org:name.endsWith('/access')?{context:()=>({}),isBoss:()=>false}:name.endsWith('/view-container')?{makeFormHandlers:()=>({})}:name.endsWith('/account')?{localDate:()=> '2026-11-15'}:name.endsWith('/sample-files')?{release:()=>true}:{},
    getApp:()=>({globalData:{}}),Date,Math,Promise
  });
  spec=spec||mod.exports;
  const page=Object.assign({},spec,{data:structuredClone(spec.data),setData(o){Object.assign(this.data,o);}});
  return{page,notices,http,wx,switchPost(){post='B';gen++;}};
}
test('slow engineering list from old organization cannot replace the new organization list',async()=>{
  const a=deferred(),b=deferred();let n=0;
  const P={api:{projects:{pageChunk:()=>++n===1?a.promise:b.promise}},listCards:r=>r,statusMatches:()=>true,online:()=>true,errorText:e=>e.message};
  const f=fixture('pages/build/projects/page.js',P);
  const old=f.page.refreshOnline();f.switchPost();const next=f.page.refreshOnline();
  b.resolve([{id:'B'}]);await next;a.resolve([{id:'A'}]);await old;
  assert.equal(f.page.data.cards[0].id,'B');assert.equal(f.page.__platRows[0].id,'B');
});
test('old detail reply is ignored after a post switch even without a second request',async()=>{
  const a=deferred();
  const P={loadProject:()=>a.promise,recordView:b=>({p:b}),errorText:e=>e.message};
  const f=fixture('pages/build/record/index.js',P);f.page.id='1';
  const old=f.page.refreshOnline();f.switchPost();a.resolve({id:'A',documents:[{assetId:'secret-A'}]});await old;
  assert.equal(f.page.data.p,null);assert.deepEqual(f.notices,[]);
});
test('old engineering failure cannot erase the new view or show an old toast',async()=>{
  let reject;const a=new Promise((_r,j)=>{reject=j;});
  const P={loadProject:()=>a,recordView:b=>({p:b}),errorText:e=>e.message};
  const f=fixture('pages/build/record/index.js',P);f.page.id='1';
  const old=f.page.refreshOnline();f.switchPost();f.page.data.p={id:'B'};reject(Error('old organization error'));await old;
  assert.equal(f.page.data.p.id,'B');assert.deepEqual(f.notices,[]);
});
test('old engineering write cannot clear the new form, toast or reload after switching posts',async()=>{
  const a=deferred();let reloads=0;
  const P={idemFingerprint:()=> 'same-command',perform:()=>a.promise,errorText:e=>e.message};
  const f=fixture('pages/build/record/index.js',P);f.page.id='1';f.page.refreshOnline=async()=>{reloads++;};
  const old=f.page.performOnline('expense-register',{});f.switchPost();f.page.data.form={note:'B draft'};f.page.data.busy='B busy';
  a.resolve({});await old;
  assert.equal(f.page.data.form.note,'B draft');assert.equal(f.page.data.busy,'B busy');assert.equal(reloads,0);assert.deepEqual(f.notices,[]);
});
test('unloaded engineering page ignores its late response',async()=>{
  const a=deferred();const P={loadProject:()=>a.promise,recordView:b=>({p:b}),errorText:e=>e.message};
  const f=fixture('pages/build/record/index.js',P);f.page.id='1';const old=f.page.refreshOnline();f.page.onUnload();
  a.resolve({id:'A'});await old;assert.equal(f.page.data.p,null);
});
test('new engineering identity clears the previous draft and action state before loading',()=>{
  const f=fixture('pages/build/record/index.js',{});f.page.id='1';f.page.refresh=()=>{};f.page.onShow();
  f.page.data.p={id:'A'};f.page.data.form={note:'A private'};f.page.data.actions=['save'];f.page.__idemKeys={old:'A'};
  f.switchPost();f.page.onShow();
  assert.equal(f.page.data.p,null);assert.deepEqual(Object.keys(f.page.data.form),[]);assert.equal(f.page.data.actions.length,0);assert.equal(Object.keys(f.page.__idemKeys).length,0);
});

test('schedule row switches preserve unsaved dates and opaque START identifiers',()=>{
  const f=fixture('pages/build/record/index.js',{}),p=f.page;
  p.data.form={nodes:[{id:'-9007199254740993',start:'2026-11-20',end:'2026-11-20'}]};
  const click=id=>p.togglePlanNode({currentTarget:{dataset:{id}}});
  click('-9007199254740993');assert.equal(p.data.planEditorId,'-9007199254740993');
  click('9007199254740999');assert.equal(p.data.form.nodes[0].start,'2026-11-20');
  click('9007199254740999');assert.equal(p.data.planEditorId,'');
});

test('role changes collapse expanded history and close the previous schedule editor',()=>{
  const f=fixture('pages/build/record/index.js',{}),p=f.page;p.id='1';p.refresh=()=>{};p.onShow();
  p.data.expanded={events:true,progress:true};p.data.planEditorId='old-node';f.switchPost();p.onShow();
  assert.equal(Object.keys(p.data.expanded).length,0);assert.equal(p.data.planEditorId,'');
});

test('progress preview follows current work, keeps every row and shows latest events first',async()=>{
  const rows=Array.from({length:13},(_,i)=>({id:String(i),status:i<8?'已完成':'待开工'}));
  const events=[{id:'handover'},{id:'start'},{id:'delivery'},{id:'archive'}];
  const P={loadProject:async()=>({}),recordView:()=>({p:{id:'1'},nodeRows:rows,events,paceDone:8}),errorText:e=>e.message};
  const f=fixture('pages/build/record/index.js',P);f.page.id='1';await f.page.refreshOnline();
  assert.equal(f.page.data.nodeRows.length,13);
  assert.equal(f.page.data.nodeRows.filter(n=>n.preview).map(n=>n.id).join(','),'8,9,10');
  assert.equal(f.page.data.events[0].id,'archive');assert.equal(events[0].id,'handover');
  assert.equal(f.page.data.progressPercent,62);
  rows.forEach(n=>n.status='已完成');await f.page.refreshOnline();
  assert.equal(f.page.data.nodeRows.filter(n=>n.preview).map(n=>n.id).join(','),'10,11,12');
});

test('camera authorization arriving after role switch cannot expose the previous stream',async()=>{
  const a=deferred(),f=fixture('pages/build/record/index.js',{playback:()=>a.promise,errorText:e=>e.message});f.page.id='1';
  const request=f.page.playCamera({currentTarget:{dataset:{id:'A'}}});f.switchPost();f.page.data.playbackUrl='new-role-state';
  a.resolve({url:'https://private.example/live.m3u8'});await request;assert.equal(f.page.data.playbackUrl,'new-role-state');
});
test('last selected camera wins and leaving the page clears its playback URL',async()=>{
  const a=deferred(),b=deferred();let n=0;const f=fixture('pages/build/record/index.js',{playback:()=>++n===1?a.promise:b.promise,errorText:e=>e.message});f.page.id='1';
  const first=f.page.playCamera({currentTarget:{dataset:{id:'A'}}}),second=f.page.playCamera({currentTarget:{dataset:{id:'B'}}});
  b.resolve({url:'https://camera.example/b.m3u8'});await second;a.resolve({url:'https://camera.example/a.m3u8'});await first;
  assert.equal(f.page.data.playbackUrl,'https://camera.example/b.m3u8');f.page.onHide();assert.equal(f.page.data.playbackUrl,'');
});

test('a file-picker callback from the previous post cannot populate the new draft',()=>{
  const f=fixture('pages/build/record/index.js',{});f.page.id='1';let selected;
  f.wx.showActionSheet=o=>o.success({tapIndex:1});f.wx.chooseMessageFile=o=>{selected=o;};
  f.wx.saveFile=()=>{throw Error('old file must never be saved into a new identity draft');};
  f.page.upload({currentTarget:{dataset:{key:'file'}}});f.switchPost();
  selected.success({tempFiles:[{name:'A.pdf',path:'/private/A.pdf'}]});
  assert.equal(f.page.data.form.file,undefined);
});
