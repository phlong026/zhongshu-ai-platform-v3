const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r;});return{promise,resolve};};
function fixture(file,P){
  let post='A',gen=0,spec;const notices=[],sequences={};
  const http={currentPostId:()=>post,sessionGeneration:()=>gen,sessionGuard:()=>{const g=gen;return()=>g===gen;},requestGuard:key=>{const g=gen,s=sequences[key]=(sequences[key]||0)+1;return()=>g===gen&&s===sequences[key];},token:()=> 'token'};
  const auth={currentApiPost:()=>({postId:post,orgId:post,roleCode:'engineer'})};
  const org={roles:{engineer:{}},has:()=>false,apiContext:p=>({role:p.roleCode}),navigation:()=>({tabs:[]})};
  const wx={showToast:x=>notices.push(x),switchTab:()=>{},navigateTo:()=>{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram',file),'utf8'),{
    Page:x=>{spec=x;},wx,
    require:name=>name.endsWith('/platform-engineering')?P:name.endsWith('/api/http')?http:name.endsWith('/api/auth')?auth:name.endsWith('/organization')?org:name.endsWith('/access')?{context:()=>({}),isBoss:()=>false}:name.endsWith('/view-container')?{makeFormHandlers:()=>({})}:name.endsWith('/account')?{localDate:()=> '2026-11-15'}:name.endsWith('/sample-files')?{release:()=>true}:{},
    getApp:()=>({globalData:{}}),Date,Math,Promise
  });
  const page=Object.assign({},spec,{data:structuredClone(spec.data),setData(o){Object.assign(this.data,o);}});
  return{page,notices,http,wx,switchPost(){post='B';gen++;}};
}
test('slow engineering list from old organization cannot replace the new organization list',async()=>{
  const a=deferred(),b=deferred();let n=0;
  const P={api:{projects:{page:()=>++n===1?a.promise:b.promise}},listCards:r=>r,statusMatches:()=>true,online:()=>true,errorText:e=>e.message};
  const f=fixture('pages/build/projects/index.js',P);
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

test('a file-picker callback from the previous post cannot populate the new draft',()=>{
  const f=fixture('pages/build/record/index.js',{});f.page.id='1';let selected;
  f.wx.showActionSheet=o=>o.success({tapIndex:1});f.wx.chooseMessageFile=o=>{selected=o;};
  f.wx.saveFile=()=>{throw Error('old file must never be saved into a new identity draft');};
  f.page.upload({currentTarget:{dataset:{key:'file'}}});f.switchPost();
  selected.success({tempFiles:[{name:'A.pdf',path:'/private/A.pdf'}]});
  assert.equal(f.page.data.form.file,undefined);
});
