const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/account/index.js'), 'utf8');
const copy = value => JSON.parse(JSON.stringify(value));
const deferred = () => { let resolve, reject; const promise = new Promise((ok,no) => { resolve=ok;reject=no; }); return {promise,resolve,reject}; };
const employee = {postId:'11', userId:'9007199254741001', name:'原员工', roleCode:'sales', active:true};
const sales = {postId:'12', userId:'9007199254741002', name:'接任销售', roleCode:'sales', active:true};
const engineer = {postId:'13', userId:'9007199254741003', name:'接任工长', roleCode:'engineer', active:true};
const items = [
  {domain:'franchise-crm',label:'客户',inFlight:2,transferred:0,status:'PENDING_UNASSIGNED'},
  {domain:'franchise-engineering',label:'工程任务',inFlight:1,transferred:0,status:'PENDING_UNASSIGNED'},
  {domain:'franchise-commission',label:'设计委托',inFlight:0,transferred:0,status:'NONE'},
  {domain:'franchise-finance',label:'财务账款',inFlight:0,transferred:0,status:'ORGANIZATION_SHARED'}
];
function harness(overrides={}) {
  let definition, generation=0;
  const writes=[], previews=[];
  const post={postId:'1',userId:'boss',orgId:'store-1',orgType:'store',roleCode:'boss'};
  const auth={currentApiPost:()=>post};
  const http={token:()=> 'token',sessionGuard:()=>{const captured=generation;return ()=>captured===generation}};
  const org={orgTeams:async()=>[],orgStaff:async()=>[employee,sales,engineer],handoverPending:async()=>[],
    handoverPreview:async(orgId,fromUserId)=>{previews.push({orgId,fromUserId});return copy(items)},
    staffHandover:async payload=>{writes.push(copy(payload));return {mode:'NORMAL',receivers:payload.receivers,items:items.map(row=>({...row,transferred:row.inFlight,status:row.inFlight?'HANDED_OVER':row.status}))}},...overrides};
  const modules={
    '../../utils/view-container':{routeValue:value=>value||'',fail(message){this.setData({error:message})}},
    '../../api/auth':auth,'../../api/http':http,'../../api/org':org,'../../utils/account-login':{},
    '../../utils/organization':{roles:{sales:'业务员',engineer:'工长'},templates:{store:['sales','engineer']},enabled:()=>true},
    '../../utils/sample-files':{},'./procurement':{},'../../utils/account':{services:[],chapters:[]},
    '../../utils/platform-messages':{},'../../utils/learning-catalog':{courses:[],resources:[]}
  };
  vm.runInNewContext(source,{Page:value=>{definition=value},require:id=>{if(!(id in modules))throw Error(id);return modules[id]},
    wx:{showToast(){},pageScrollTo(){}},console,setTimeout,clearTimeout,Promise,Date});
  const page={...definition,data:copy(definition.data),options:{},setData(patch){for(const [key,value]of Object.entries(patch)){
    const bits=key.split('.');let target=this.data;for(const bit of bits.slice(0,-1))target=target[bit]||(target[bit]={});target[bits.at(-1)]=value;
  }}};
  page.data.view='api-members';
  return {page,org,writes,previews,post,invalidate:()=>{generation++}};
}
const select=(page,domain,userId)=>page.pickHandoverTarget({currentTarget:{dataset:{domain}},detail:{value:page.data.apiReceivers.findIndex(person=>person.userId===userId)}});

test('小程序仅为实际在办类别选人，一次提交销售和工长两个接收人',async()=>{
  const env=harness();await env.page.loadApiMembers();
  await env.page.setHandoverSource(employee);
  assert.deepEqual(Array.from(env.page.data.handoverRequirements,row=>row.domain),['franchise-crm','franchise-engineering']);
  select(env.page,'franchise-crm',sales.userId);
  await env.page.confirmHandover();
  assert.equal(env.writes.length,0,'有在办工程却没有接收人时不提交');
  select(env.page,'franchise-engineering',engineer.userId);
  await env.page.confirmHandover();
  assert.deepEqual(env.writes,[{orgId:'store-1',fromUserId:employee.userId,receivers:{'franchise-crm':sales.userId,'franchise-engineering':engineer.userId}}]);
  assert.equal(env.page.data.handoverResult.items[0].receiverName,sales.name);
  assert.equal(env.page.data.handoverResult.items[1].receiverName,engineer.name);
});

test('小程序预览失败不降级为旧单人交接，重试仍沿用原员工',async()=>{
  let calls=0;
  const env=harness({handoverPreview:async()=>{if(++calls===1)throw Error('offline');return []}});
  await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  await env.page.confirmHandover();assert.equal(env.writes.length,0);
  assert.match(env.page.data.handoverPreviewError,/重试/);
  await env.page.loadHandoverPreview();await env.page.confirmHandover();
  assert.equal(env.writes.length,1);
  assert.deepEqual(env.writes[0],{orgId:'store-1',fromUserId:employee.userId,receivers:{}});
});

test('小程序切换来源、离开或切账号后丢弃旧预览',async()=>{
  const first=deferred();
  const env=harness({handoverPreview:async(_org,user)=>user===employee.userId?first.promise:[items[1]]});
  await env.page.loadApiMembers();
  const pending=env.page.setHandoverSource(employee);
  await env.page.setHandoverSource(sales);
  first.resolve([items[0]]);await pending;
  assert.equal(env.page.data.handoverRequirements[0].domain,'franchise-engineering');
  const second=deferred();env.org.handoverPreview=()=>second.promise;
  const leaving=env.page.setHandoverSource(employee);env.page.onHide();second.resolve(items);await leaving;
  assert.equal(env.page.data.handoverPreviewReady,false);
  assert.equal(env.page.data.handoverRequirements.length,0);
  env.page.memberPageHidden=false;
  const third=deferred();env.org.handoverPreview=()=>third.promise;
  const switching=env.page.setHandoverSource(employee);env.invalidate();third.resolve(items);await switching;
  assert.equal(env.page.data.handoverPreviewReady,false);
  await env.page.confirmHandover();
  assert.equal(env.page.data.handoverSource,null,'切账号后不能沿用原账号来源');
  assert.equal(env.writes.length,0);
});

test('小程序刷新按员工ID保留各类接收人，人员消失时要求重选',async()=>{
  const env=harness();await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  select(env.page,'franchise-crm',sales.userId);select(env.page,'franchise-engineering',engineer.userId);
  env.org.orgStaff=async()=>[employee,engineer,sales];await env.page.loadApiMembers();
  assert.equal(env.page.data.handoverReceivers['franchise-crm'],sales.userId);
  assert.equal(env.page.data.handoverRequirements.find(row=>row.domain==='franchise-crm').targetIndex,1);
  env.org.orgStaff=async()=>[employee,engineer];await env.page.loadApiMembers();
  assert.equal(env.page.data.handoverReceivers['franchise-crm'],undefined);
  await env.page.confirmHandover();assert.equal(env.writes.length,0);
});

test('小程序交接预览API保留组织与来源编号',async()=>{
  let api,call;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/api/org.js'),'utf8'),{
    require:()=>({get:async(url,data)=>{call={url,data};return []}}),module:{set exports(value){api=value}}
  });
  await api.handoverPreview('9007199254741099',employee.userId);
  assert.equal(call.url,'/platform/org/staff-handover-preview');
  assert.deepEqual(copy(call.data),{orgId:'9007199254741099',fromUserId:employee.userId});
});


test('小程序提交失败保留各类接收人，重新核实后才允许再次提交',async()=>{
  let attempts=0;
  const env=harness({staffHandover:async()=>{attempts++;throw Error('在办事项有变化')}});
  await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  select(env.page,'franchise-crm',sales.userId);select(env.page,'franchise-engineering',engineer.userId);
  await env.page.confirmHandover();await env.page.confirmHandover();
  assert.equal(attempts,1);
  assert.equal(env.page.data.handoverPreviewReady,false);
  assert.equal(env.page.data.handoverReceivers['franchise-crm'],sales.userId);
  await env.page.loadHandoverPreview();
  assert.equal(env.page.data.handoverPreviewReady,true);
  assert.equal(env.page.data.handoverReceivers['franchise-engineering'],engineer.userId);
});

test('小程序已提交后卸载，迟到结果不再回写页面',async()=>{
  const pending=deferred();const env=harness({staffHandover:()=>pending.promise});
  await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  select(env.page,'franchise-crm',sales.userId);select(env.page,'franchise-engineering',engineer.userId);
  const submission=env.page.confirmHandover();env.page.onUnload();
  let lateWrites=0;env.page.setData=()=>{lateWrites++};
  pending.resolve({mode:'NORMAL',items:[],receivers:{}});await submission;
  assert.equal(lateWrites,0);
});

test('小程序新会话不会被上一会话未完成的交接锁住',async()=>{
  const pending=deferred();const env=harness({staffHandover:()=>pending.promise});
  await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  select(env.page,'franchise-crm',sales.userId);select(env.page,'franchise-engineering',engineer.userId);
  const submission=env.page.confirmHandover();assert.equal(env.page.data.handoverBusy,true);
  env.invalidate();await env.page.loadApiMembers();
  assert.equal(env.page.data.handoverBusy,false);
  const snapshot=copy(env.page.data);pending.resolve({mode:'NORMAL',items:[],receivers:{}});await submission;
  assert.deepEqual(copy(env.page.data),snapshot);
});

test('小程序隐藏期间缓存已完成交接，返回同会话才显示结果',async()=>{
  const pending=deferred();const env=harness({staffHandover:()=>pending.promise});
  await env.page.loadApiMembers();await env.page.setHandoverSource(employee);
  select(env.page,'franchise-crm',sales.userId);select(env.page,'franchise-engineering',engineer.userId);
  const submission=env.page.confirmHandover();env.page.onHide();
  const originalSetData=env.page.setData;let hiddenWrites=0;
  env.page.setData=function(patch){hiddenWrites++;originalSetData.call(this,patch)};
  pending.resolve({mode:'NORMAL',items:[],receivers:{}});await submission;
  assert.equal(hiddenWrites,0);
  env.page.setData=originalSetData;env.page.options=null;env.page.onShow();
  assert.equal(env.page.data.handoverBusy,false);
  assert.equal(env.page.data.handoverResult.title,'原员工 交接清单');
});
