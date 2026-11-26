const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {render} = require('./role-page-runtime.cjs');
const route = path.resolve('miniprogram/pages/build/issues/index');
const project = '9007199254741001', manager = '9007199254741002', engineer = '9007199254741003', issue = '9007199254741004';
const snapshot = value => JSON.parse(JSON.stringify(value));
const event = data => ({currentTarget:{dataset:data}});
const pick = value => ({detail:{value}});
function setup({role='engineer',legacy=false,quality=false}={}) {
  const calls=[],requests=[];
  const current={orgId:'4',userId:manager,roleCode:role};
  let session=1, definition, active=legacy;
  const row={id:issue,title:'楼梯整改',state:'OPEN',note:'原情况',ownerUserId:null,dueDate:null};
  const history=legacy?[{id:'1',action:'create',note:'首次转录',actorUserId:manager,actorName:'张工',occurredAt:'2026-11-01 09:05:00',ownerUserId:null,dueDate:null}]:[];
  const base='/franchise/engineering/'+project+'/rectifications';
  const api={projects:{get:async()=>({id:project,name:'原项目',managerUserId:manager,managerName:'张工',assignedEngineerUserId:engineer,status:'IN_PROGRESS'})},
    progress:{handlers:async()=>[{id:manager,name:'张工'},{id:engineer,name:'李工'},{id:'51',name:'外项目'}]},
    materials:{summary:async()=>({plans:[{lines:[{name:'砂石',supplyId:'52',receipts:[{id:'53',transactionNo:'S53',qualified:false}]}]}]}),
      arrival:async(id,body,key)=>calls.push({id,body:snapshot(body),key})},uploadVoucher:async()=>'111'};
  const http={sessionGuard:()=>{const n=session;return ()=>n===session;},currentPostId:()=>String(session),
    get:async url=>{requests.push(url);if(url===base)return active?[snapshot(row)]:[];if(url===base+'/'+issue)return {...snapshot(row),history:snapshot(history)};throw Error(url);},
    post:async(url,cmd,headers)=>{calls.push({url,cmd:snapshot(cmd),headers});active=true;
      if(cmd.ownerUserId){row.ownerUserId=cmd.ownerUserId;row.dueDate=cmd.dueDate;} if(cmd.action==='submit')row.state='REVIEW';
      history.push({id:String(history.length+10),action:cmd.action,note:cmd.note,actorName:'张工',actorUserId:manager,ownerUserId:row.ownerUserId,dueDate:row.dueDate,occurredAt:'2026-11-26 14:00:00'});return issue;}};
  vm.runInNewContext(fs.readFileSync(route+'.js','utf8'),{Page:page=>definition=page,wx:{showToast(){}},
    require:name=>name.endsWith('/engineering')?api:name.endsWith('/auth')?{currentApiPost:()=>current}:http});
  const page={...definition,data:snapshot(definition.data),setData(patch){Object.assign(this.data,patch);}};
  page.onLoad({project,...(legacy?{issue}:{}),...(quality?{supply:'52',receipt:'53'}:{})});
  const screen=()=>JSON.stringify(render(route+'.wxml',page.data));
  return {page,calls,requests,screen,base,switchPost:()=>session++};
}

test('新单需明确选择本项目负责人和期限，状态操作使用原单，逐次历史可读',async()=>{
  const {page,calls,screen,base,requests}=setup();await page.load();
  assert.deepEqual(snapshot(page.data.handlers.map(h=>h.id)),[manager,engineer]);
  assert.equal(page.data.ownerUserId,'');assert.equal(page.data.dueDate,'');
  page.setData({title:'楼梯整改',note:'梯段钢筋复核'});await page.save(event({action:'create'}));assert.equal(calls.length,0);
  page.pickOwner(pick('1'));page.pickDate(pick('2026-12-03'));await page.save(event({action:'create'}));
  assert.deepEqual(calls[0].cmd,{action:'create',note:'梯段钢筋复核',title:'楼梯整改',nodeId:null,ownerUserId:engineer,dueDate:'2026-12-03'});
  assert.ok(requests.includes(base+'/'+issue));assert.match(screen(),/2026-12-03/);assert.match(screen(),/张工/);
  page.setData({note:'提交现场复核'});await page.save(event({action:'submit'}));
  assert.deepEqual(calls[1].cmd,{action:'submit',note:'提交现场复核',issueId:issue});assert.match(screen(),/提交复验/);
});

test('历史无负责人无期限允许经办人明确补录，所有者只读，切换岗位阻止过期表单',async()=>{
  const {page,screen,calls,switchPost}=setup({legacy:true});await page.load();
  assert.match(screen(),/负责人未登记/);assert.match(screen(),/期限未登记/);assert.match(screen(),/首次转录/);
  page.pickOwner(pick('0'));page.pickDate(pick('2026-12-02'));page.setData({note:'查原单补录'});await page.save(event({action:'assign'}));
  assert.equal(calls[0].cmd.issueId,issue);assert.equal(calls[0].cmd.ownerUserId,manager);
  assert.match(screen(),/查原单补录/);assert.match(screen(),/首次转录/);
  switchPost();page.setData({note:'过期页面'});await page.save(event({action:'submit'}));assert.equal(calls.length,1);
  const reader=setup({role:'owner',legacy:true});await reader.page.load();assert.equal(reader.page.data.editable,false);assert.match(reader.screen(),/首次转录/);
});

test('材料质检仍回原收货单，分派字段不进入换货请求',async()=>{
  const {page,calls,requests}=setup({quality:true});await page.load();assert.equal(requests.length,0);
  assert.equal(page.data.selected.id,'53');page.setData({note:'验收合格',photo:'receipt.jpg'});await page.save(event({action:'resolve'}));
  assert.deepEqual(calls[0].body,{qualityResolution:{receiptId:'53',note:'验收合格',fileAssetId:'111'}});assert.equal(calls[0].id,'52');
});
