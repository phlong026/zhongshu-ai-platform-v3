const test=require('node:test'),assert=require('node:assert/strict');
const C=require('../miniprogram/pages/account/message-center');
const H=require('../miniprogram/api/http');const N=require('../miniprogram/utils/page-navigation');
const P=require('../miniprogram/pages/account/platform-messages-page');
const message=(id,extra={})=>({id,type:'BUSINESS',title:'设计费模拟支付已完成',read:false,module:'finance',createTime:'2026-11-17T09:00:00+08:00',context:{customerName:'陈先生 · 泉州住宅',amountCents:'168000',simulated:true,notice:'模拟支付不计入实际收支',fields:[{label:'委托版本',value:'V2'}]},...extra});
const page=()=>({data:{messageTab:'业务',messageMode:'ALL',messageModule:'finance'},setData(p){Object.assign(this.data,p)},filter(){this.data.messages=this.apiMessages;this.data.unread=this.apiUnread;}});
test('business cards preserve large IDs, exact cents, factual simulation labels and detail context',()=>{const r=C.row(message('9007199254740999123'));assert.equal(r.id,'9007199254740999123');assert.equal(r.amount,'¥1,680.00');assert.equal(r.subject,'陈先生 · 泉州住宅');assert.equal(r.simulated,true);assert.match(r.notice,/不计入/);assert.equal(C.money('9007199254740999123'),'¥90,071,992,547,409,991.23');assert.equal(C.row({id:'invalid'}),null);});
test('API inbox excludes locally derived duplicate notifications',()=>{const token=H.token;H.token=()=> 'test';try{const p=page();p.apiMessages=[{id:'99'}];assert.deepEqual(C.rows(p,[{id:'demo-1'}]),[{id:'99'}]);H.token=()=>'';assert.deepEqual(C.rows(p,[{id:'demo-1'}]),[]);}finally{H.token=token}});
test('server filtering, opaque task IDs, pagination and current-action revalidation',async()=>{const originals={get:H.get,post:H.post,token:H.token,guard:H.requestGuard,open:N.open};let navigated='',calls=[];H.token=()=> 'test';H.requestGuard=()=>()=>true;H.get=async(url,q)=>{calls.push({url,q});return {items:[message('99')],unread:1,pending:2,snapshotAt:'2026-11-17T12:00:00+08:00',hasMore:true,nextCursor:'99'}};N.open=url=>{navigated=url};try{const p=page();p.data.messageKeyword='陈先生';await P.sync.call(p);assert.equal(calls[0].q.keyword,'陈先生');assert.equal(calls[0].q.type,'BUSINESS');assert.equal(p.data.messagePending,2);await P.sync.call(p,true);assert.equal(calls[1].q.beforeId,'99');assert.equal(p.apiMessages.length,1);H.post=async()=>message('99',{read:true,actionPath:'',stateLabel:'权限已变更',context:{}});await P.read.call(p,p.apiMessages[0],true);assert.equal(navigated,'');assert.equal(p.data.messageDetail.stateLabel,'权限已变更');assert.equal(p.apiUnread,0);assert.throws(()=>P.target({actionPath:'https://outside.test'}));assert.equal(P.target({actionPath:'/pages/admin/finance-desk/index?view=flows&messageObject=123'}),'/pages/admin/finance-desk/index?view=flows&messageObject=123');}finally{Object.assign(H,{get:originals.get,post:originals.post,token:originals.token,requestGuard:originals.guard});N.open=originals.open;}});
test('all-read uses server snapshot, refreshes counts and preserves pending work',async()=>{const originals={get:H.get,post:H.post,token:H.token,guard:H.requestGuard};let request;H.token=()=> 'test';H.requestGuard=()=>()=>true;H.post=async(url,body)=>{request={url,body};return {updated:3}};H.get=async()=>({items:[],unread:1,pending:4,snapshotAt:'2026-11-17T12:01:00+08:00'});try{const p=page();p.__messagesSnapshot='2026-11-17T12:00:00+08:00';await P.readAll.call(p);assert.equal(request.body.through,'2026-11-17T12:00:00+08:00');assert.equal(p.data.messagePending,4);assert.equal(p.apiUnread,1);assert.equal(p.data.messageBusy,false);}finally{Object.assign(H,{get:originals.get,post:originals.post,token:originals.token,requestGuard:originals.guard})}});

test('late message-open response cannot navigate after leaving the page',async()=>{const oldPost=H.post,oldGuard=H.requestGuard,oldOpen=N.open;let release,opened=false;H.post=()=>new Promise(r=>{release=r});H.requestGuard=()=>()=>true;N.open=()=>{opened=true};try{const p=page();p.__messagesGen=1;p.apiMessages=[C.row(message('99'))];const run=P.read.call(p,p.apiMessages[0]);p.__messagesGen++;release(message('99',{read:true,actionPath:'/pages/workflow/index?view=customer&id=1'}));await run;assert.equal(opened,false);assert.equal(p.apiMessages[0].read,false);}finally{H.post=oldPost;H.requestGuard=oldGuard;N.open=oldOpen;}});
function accountLifecycle(){
  const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
  let account;
  const code=fs.readFileSync(path.join(__dirname,'../miniprogram/pages/account/index.js'),'utf8');
  vm.runInNewContext(code,{Page:definition=>{account=definition;},require:id=>id==='./platform-messages-page'?P:{}});
  return account;
}
for(const event of ['onUnload','onHide'])test('message action after '+event+' does not update data or navigate',async()=> {
  const originals={post:H.post,guard:H.requestGuard,open:N.open};
  const lifecycle=accountLifecycle(),p=page();p.data.view='messages';p.cancelLoginFlow=()=>{};
  p.resetHandoverSearch=lifecycle.resetHandoverSearch; // 使用真实页面清理方法，保留离开后迟到回写的观测。
  let resolve,navigations=0,writes=0;
  const update=p.setData.bind(p);p.setData=patch=>{writes++;update(patch);};
  H.requestGuard=()=>()=>true;H.post=()=>new Promise(r=>{resolve=r;});N.open=()=>{navigations++;};
  try{
    const pending=P.read.call(p,message('99'),true);
    lifecycle[event].call(p);const before=writes;
    resolve(message('99',{read:true,actionPath:'/pages/studio/index?view=tasks'}));
    await pending;
    assert.equal(writes,before);assert.equal(navigations,0);
  }finally{H.post=originals.post;H.requestGuard=originals.guard;N.open=originals.open;}
});
test('filter change drops stale detail and does not clear a newer action busy state',async()=> {
  const originals={get:H.get,post:H.post,token:H.token,guard:H.requestGuard};
  const p=page(),requests=[];
  H.token=()=> 'test';H.requestGuard=()=>()=>true;
  H.get=async()=>({items:[message('100')],unread:1});
  H.post=()=>new Promise(r=>requests.push(r));
  try{
    const older=P.read.call(p,message('99'),false);
    await P.change.call(p,{messageMode:'UNREAD'});
    assert.equal(p.data.messageBusy,false);
    const newer=P.read.call(p,message('100'),false);
    requests[0](message('99',{read:true}));await older;
    assert.equal(p.data.messageDetail,null);assert.equal(p.data.messageBusy,true);
    requests[1](message('100',{read:true}));await newer;
    assert.equal(p.data.messageDetail.id,'100');assert.equal(p.data.messageBusy,false);
  }finally{H.get=originals.get;H.post=originals.post;H.token=originals.token;H.requestGuard=originals.guard;}
});
test('an invalidated request guard releases busy without opening a message',async()=> {
  const originals={post:H.post,guard:H.requestGuard};
  const p=page();let resolve,alive=true;
  H.requestGuard=()=>()=>alive;H.post=()=>new Promise(r=>{resolve=r;});
  try{
    const pending=P.read.call(p,message('99'),false);
    alive=false;resolve(message('99',{read:true}));await pending;
    assert.equal(p.data.messageDetail,undefined);assert.equal(p.data.messageBusy,false);
  }finally{H.post=originals.post;H.requestGuard=originals.guard;}
});

test('load-more and opening the original order can complete in either request order',async()=>{
  const originals={get:H.get,post:H.post,token:H.token,guard:H.requestGuard,open:N.open};
  const route='/pages/workflow/index?view=customer&id=1',navigations=[];
  H.token=()=> 'test';
  H.requestGuard=()=>()=>true;
  N.open=url=>navigations.push(url);
  try {
    for(const pageFirst of [true,false]){
      const p=page();
      H.get=async()=>({items:[message('99')],unread:2,hasMore:true,nextCursor:'99'});
      await P.sync.call(p);
      let releaseOpen,releasePage;
      H.post=()=>new Promise(resolve=>{releaseOpen=resolve;});
      H.get=()=>new Promise(resolve=>{releasePage=resolve;});
      const opening=P.read.call(p,p.apiMessages[0],true);
      const paging=P.sync.call(p,true);
      const opened=()=>releaseOpen(message('99',{read:true,unread:1,actionPath:route}));
      const appended=()=>releasePage({items:[message('98')],unread:pageFirst?1:2,hasMore:false});
      if(pageFirst){appended();await paging;opened();}
      else{opened();await opening;appended();}
      await Promise.all([opening,paging]);
      assert.deepEqual(p.apiMessages.map(row=>[row.id,row.read]),[['99',true],['98',false]]);
      assert.equal(p.apiUnread,1);
      assert.equal(p.data.messageBusy,false);
    }
    assert.deepEqual(navigations,[route,route]);
  }finally{
    H.get=originals.get;H.post=originals.post;H.token=originals.token;H.requestGuard=originals.guard;N.open=originals.open;
  }
});

test('expired bulk-read asks for an explicit refresh and leaves newer messages alone',async()=>{
  const originals={get:H.get,post:H.post,token:H.token,requestGuard:H.requestGuard};
  let refreshes=0,posts=0;
  H.token=()=> 'test';H.requestGuard=()=>()=>true;
  H.get=async()=>{refreshes++;return {items:[],unread:1};};
  H.post=async()=>{posts++;throw {code:1105000001,message:'消息列表已过期，请刷新后重试'};};
  try {
    const p=page();
    p.__messagesSnapshot='2026-11-17T12:00:00+08:00';
    await P.readAll.call(p);
    await P.readAll.call(p);
    assert.equal(posts,1);
    assert.equal(refreshes,0);
    assert.match(p.data.messageError,/刷新后重试/);
    assert.equal(p.data.messageBusy,false);
  } finally {Object.assign(H,originals);}
});
