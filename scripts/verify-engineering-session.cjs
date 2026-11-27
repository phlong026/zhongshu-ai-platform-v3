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

function expenseSaveFixture(perform){
 const f=fixture('pages/build/record/index.js',{perform,errorText:e=>e.message||String(e)});
 const p=f.page;let reloads=0;
 p.id='original-project';p.data.view='expenses';p.refresh=()=>{};
 p.onShow();p.refreshOnline=async reset=>{assert.equal(reset,true);reloads++;};
 p.data.form={expenseName:'卸货费',expenseAmount:'200',purposeCode:'OTHER',note:'今天。'};
 p.setData=function(patch){for(const [key,value] of Object.entries(patch)){
  if(key.startsWith('form.'))this.data.form[key.slice(5)]=value;else this.data[key]=value;
 }};
 const submit=()=>p.perform({currentTarget:{dataset:{action:'expense-register'}}});
 return{...f,p,submit,reloads:()=>reloads};
}
test('a WeChat switch while expense is saving cannot create a second payment entry',async()=>{
 const a=deferred(),calls=[],f=expenseSaveFixture((...args)=>{calls.push(args);return a.promise;});
 const first=f.submit();f.p.onHide();f.p.onShow();f.p.data.busy=false;
 await f.submit();assert.equal(f.p.data.expenseSaveState,'sending');assert.equal(calls.length,1);
 a.resolve({expenseId:'receipt-1'});await first;
 assert.equal(f.p.data.expenseSaveState,'');assert.equal(f.p.data.form.expenseAmount,'');
 assert.equal(f.reloads(),1);assert.equal(f.notices.at(-1).title,'费用已保存');
});
test('a hidden successful expense is acknowledged on return, then a real second expense gets a new key',async()=>{
 const a=deferred(),calls=[],f=expenseSaveFixture((...args)=>{calls.push(args);return calls.length===1?a.promise:Promise.resolve({expenseId:'receipt-2'});});
 const first=f.submit();f.p.onHide();a.resolve({expenseId:'receipt-1'});await first;
 assert.equal(f.notices.length,0);f.p.onShow();
 assert.equal(f.p.data.form.expenseName,'');assert.equal(f.p.data.form.note,'');assert.equal(f.reloads(),1);
 f.p.data.form={expenseName:'卸货费',expenseAmount:'200',purposeCode:'OTHER',note:'另一次'};
 await f.submit();assert.equal(calls.length,2);assert.notEqual(calls[0][4],calls[1][4]);
});
test('lost expense result only permits checking frozen input under the original key',async()=>{
 const calls=[],rows=new Map(),f=expenseSaveFixture((...args)=>{
  calls.push(args);if(!rows.has(args[4]))rows.set(args[4],Number(args[2].expenseAmount));
  return calls.length===1?Promise.reject({code:-1,message:'offline after save'}):Promise.resolve({expenseId:'same-receipt'});
 });
 await f.submit();assert.equal(f.p.data.expenseSaveState,'unknown');assert.equal(f.p.data.expenseSaveLabel,'卸货费 · 200元');
 f.p.onHide();f.p.onShow();f.p.data.form.note='改过的标点';await f.submit();assert.equal(calls.length,1);
 await f.p.retryExpense();assert.deepEqual(calls[0],calls[1]);assert.equal(calls[1][2].note,'今天。');
 assert.deepEqual([...rows.values()],[200]);assert.equal(f.p.data.form.expenseAmount,'');assert.equal(f.reloads(),1);
});
test('an explicit expense rejection restores editing; a pending record is cleared on post change',async()=>{
 const a=deferred(),calls=[],f=expenseSaveFixture((...args)=>{calls.push(args);return calls.length===1?Promise.reject({code:400,message:'请填金额'}):a.promise;});
 await f.submit();assert.equal(f.p.data.expenseSaveState,'');assert.equal(f.p.data.form.expenseName,'卸货费');assert.equal(f.p.data.error,'请填金额');
 const save=f.submit();f.p.onHide();f.switchPost();f.p.onShow();
 a.resolve({expenseId:'old-receipt'});await save;
 assert.equal(f.p.data.expenseSaveState,'');assert.equal(f.p.data.expenseSaveLabel,'');assert.equal(f.reloads(),0);
 assert.deepEqual(f.notices.map(x=>x.title),['请填金额']);
});
test('expense form and write actions stay hidden until the original save can be checked',()=>{
 const {render}=require('./role-page-runtime.cjs');
 const file=path.join(__dirname,'../miniprogram/pages/build/record/index.wxml');
 const flatten=n=>[n,...(n.children||[]).flatMap(flatten)];
 const read=expenseSaveState=>{
  const tree=render(file,{view:'expenses',p:{id:'project'},own:true,expanded:{},expenseSaveState,
   expenseSaveLabel:'卸货费 · 200元',expenseMode:'register',expenseRows:[{id:'item',name:'劳务',canTerms:true}],
   fields:[{key:'expenseName',label:'费用名称'}],form:{},actions:[{action:'expense-register',label:'登记费用'}]});
  return (Array.isArray(tree)?tree.flatMap(flatten):flatten(tree)).filter(n=>n.tag==='button');
 };
 assert.equal(read('sending').some(x=>['retryExpense','expenseMode','perform'].includes(x.attrs.bindtap)),false);
 assert.deepEqual(read('unknown').filter(x=>['retryExpense','expenseMode','perform'].includes(x.attrs.bindtap)).map(x=>x.attrs.bindtap),['retryExpense']);
 assert.equal(read('').some(x=>x.attrs.bindtap==='perform'),true);
});

test('settlement names the revised expense and displays the difference without changing totals or adding actions',()=>{
 const {render}=require('./role-page-runtime.cjs');
 const {settleView}=require('../miniprogram/utils/platform-engineering');
 const file=path.join(__dirname,'../miniprogram/pages/build/record/index.wxml');
 const row={expenseId:'labor',name:'劳务核减',revisedCents:80000,bookedCents:100000,netPaidCents:100000,netAboveRevisedCents:20000};
 const show=(remaining,...rows)=>render(file,{view:'settlement',p:{id:'project'},expanded:{},actions:[],
   settle:settleView({expensesTotalCents:100000,expensesPaidCents:100000-remaining,expensesRemainingCents:remaining,...(rows.length?{expenseReviewRows:rows}:{})})});
 const snapshot=tree=>JSON.stringify(tree,(key,value)=>key==='parent'?undefined:value);
 const before=snapshot(show(0,row));
 for(const label of ['请财务核对原单','劳务核减','工程修订 ¥ 800','财务原应付 ¥ 1,000','净已付 ¥ 1,000','净已付比修订金额多 ¥ 200']) {
  assert.ok(before.includes(label),label);
 }
 assert.ok(!before.includes('data-action'), 'notice must not add a refund or payment action');
 assert.ok(!snapshot(show(20000)).includes('请财务核对原单'), 'old backend or redacted response');
 const after=snapshot(show(20000,{...row,netPaidCents:80000,netAboveRevisedCents:0}));
 assert.ok(after.includes('净已付 ¥ 800'));
 assert.ok(after.includes('¥ 200（原账余额，待核对）'));
 assert.ok(!snapshot(show(20000)).includes('原账余额，待核对'));
 assert.ok(!after.includes('净已付比修订金额多'));
 assert.equal(settleView({expensesTotalCents:100000}).expensesTotalText,'¥ 1,000');
});


test('cancelled-material settlement keeps original debt and only adds the server review note',()=>{
 const {render}=require('./role-page-runtime.cjs'), {settleView}=require('../miniprogram/utils/platform-engineering');
 const file=path.join(__dirname,'../miniprogram/pages/build/record/index.wxml');
 const show=facts=>JSON.stringify(render(file,{view:'settlement',p:{id:'original-project'},expanded:{},actions:[],settle:settleView(facts)}),(key,value)=>key==='parent'?undefined:value);
 const facts={materialsTotalCents:10000,materialsPaidCents:4000,materialsRemainingCents:6000,materialsNote:'已取消材料仍有原应付或实际付款，请财务核对原付款事项。'};
 const view=settleView(facts);
 assert.equal(view.materialsTotalText,'¥ 100');
 assert.equal(view.materialsPaidText,'¥ 40');
 assert.equal(view.materialsRemainingText,'¥ 60');
 const rendered=show(facts);
 for(const text of ['已取消材料','请财务核对原付款事项','¥ 100','¥ 40','¥ 60'])assert.ok(rendered.includes(text),text);
 assert.ok(!rendered.includes('data-action'),'review note cannot add a payable or payment action');
 assert.ok(!show({materialsTotalCents:0,materialsPaidCents:0,materialsRemainingCents:0,materialsNote:''}).includes('已取消材料'));
 assert.ok(!show({}).includes('已取消材料'),'older or redacted response does not invent a review state');
});
