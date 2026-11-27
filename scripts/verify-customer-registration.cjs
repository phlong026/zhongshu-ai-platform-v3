const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const CRM = require('../miniprogram/utils/platform-crm');
const root = path.resolve(__dirname, '..');
const copy = value => JSON.parse(JSON.stringify(value));
const address = {province:'湖北省',city:'武汉市',district:'江夏区',town:'纸坊街道',village:'某社区',regionCode:'420115001001',addressDetail:'一组12号'};
function instance(definition) {
  return {...definition,data:copy(definition.data),setData(values,done){
    for(const [key,value] of Object.entries(values)){
      const parts=key.split('.');let target=this.data;
      while(parts.length>1){const part=parts.shift();target=target[part]||(target[part]={});}
      target[parts[0]]=copy(value);
    }
    if(done)done();
  }};
}
function pageHarness(http = {}, navigations = [], orgStaff = async()=>[]) {
  let definition,windowHeight=800,listener,unsubscribed=false;
  vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/pages/workflow/index.js'),'utf8'),{
    Page(value){definition=value;},Date,Math,Promise,
    wx:{getWindowInfo:()=>({windowHeight}),onKeyboardHeightChange(fn){listener=fn;},offKeyboardHeightChange(fn){unsubscribed=fn===listener;},hideKeyboard(){},showToast(){}},
    require(id){
      if(id.endsWith('/platform-crm'))return CRM;
      if(id.endsWith('/view-container'))return {routeValue:value=>value,fail(){}};
      if(id==='./views')return {titles:{}};
      if(id.endsWith('/http'))return http;
      if(id.endsWith('/api/org'))return {orgStaff};
      if(id.endsWith('/organization'))return {roles:{sales:'销售'}};
      if(id.endsWith('/page-navigation'))return {open(url){navigations.push(url);}};
      if(id.endsWith('/api/franchise/content'))return {growth:{customerSources:()=>Promise.resolve([])}};
      if(id==='./customer-follow')return require('../miniprogram/pages/workflow/customer-follow');
      throw Error('Unexpected dependency '+id);
    }
  });
  const page=instance(definition),requests=[];
  page.__http={sessionGeneration:()=>1};page.__postKey='sales';page.__pageSession=1;
  page.platformSession=()=>({postId:'sales',roleCode:'sales'});
  page.__crmApi={leads:{capture(payload){requests.push(payload);return Promise.resolve({result:'CREATED',customerId:'77'});}}};
  page.openView=(view,replace,id)=>{page.destination={view,id};};
  return {page,requests,setWindowHeight(value){windowHeight=value;},emitKeyboard(height){listener({height});},unsubscribed:()=>unsubscribed};
}

test('registration requires address before making a request and submits a full trimmed snapshot',async()=>{
  const {page,requests}=pageHarness();
  page.data.form={name:' 张客户 ',phone:'13800000000',source:'朋友介绍',summary:'三层自建房'};
  page.platformSubmitRegister();assert.match(page.data.error,/乡镇/);assert.equal(requests.length,0);
  Object.assign(page.data.form,address);page.platformSubmitRegister();
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(requests.length,1);assert.equal(requests[0].name,'张客户');
  for(const [key,value] of Object.entries(address))assert.equal(requests[0][key],value);
  assert.equal(page.destination.id,'77');
});

test('details and edit preserve all address levels, and village keywords remain searchable',()=>{
  const model=CRM.detailView({customer:{id:'42',name:'客户',...address},follows:[],schemes:[]},{canWrite:true});
  assert.match(model.customer.address,/纸坊街道 某社区 一组12号/);
  const payload=CRM.updatePayload(model.customer,4);
  for(const key of Object.keys(address))assert.equal(payload[key],address[key]);
  assert.match(CRM.customerRow({id:'42',name:'客户',...address},{}).searchText,/某社区/);
});

test('keyboard keeps the sheet above both overlay and resized keyboards and reveals the focused field',()=>{
  const h=pageHarness(),page=h.page;page.initFormKeyboard();page.openModal('register');
  page.formFieldFocus({currentTarget:{dataset:{field:'customer-summary-field'}}});
  h.emitKeyboard(320);assert.equal(page.data.keyboardInset,320);assert.equal(page.data.sheetHeight,468);
  assert.equal(page.data.sheetFocusTarget,'customer-summary-field');
  h.setWindowHeight(480);h.emitKeyboard(320);assert.equal(page.data.keyboardInset,0);assert.equal(page.data.sheetHeight,468);
  h.setWindowHeight(800);h.emitKeyboard(0);assert.equal(page.data.sheetHeight,720);
  page.onUnload();assert.equal(h.unsubscribed(),true);
});

test('busy registration cannot be dismissed or replaced',()=>{
  const {page}=pageHarness();page.openModal('register');page.setData({busy:true});
  page.closeModal();page.openModal('edit');assert.equal(page.data.modal,'register');
});

function componentHarness(resolver) {
  let definition,generation=1;
  vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/components/customer-address/index.js'),'utf8'),{
    Component(value){definition=value;},
    require(id){if(id.endsWith('/crm'))return {regions:{children:resolver}};if(id.endsWith('/http'))return {sessionGeneration:()=>generation};throw Error(id);}
  });
  const component=instance({...definition.methods,data:{...definition.data,value:{},disabled:false}}),events=[];
  component.triggerEvent=(name,detail)=>{events.push({name,detail});if(name==='change')component.data.value=detail.value;};
  component._ready=true;component._cache={};
  return {component,events,changeSession(){generation++;}};
}
const regions={'':[{code:'42',name:'湖北省'},{code:'44',name:'广东省'}],42:[{code:'4201',name:'武汉市'}],4201:[{code:'420115',name:'江夏区'}],420115:[{code:'420115001',name:'纸坊街道'}],420115001:[{code:'420115001001',name:'某社区'}],44:[{code:'4401',name:'广州市'}]};

test('cascading selectors restore saved addresses and clear downstream selections on province change',async()=>{
  const {component,events}=componentHarness(async parent=>regions[parent]||[]);
  component.data.value=copy(address);await component.load(address);
  assert.equal(component.data.rows[4].selected,'某社区');
  component.choose({currentTarget:{dataset:{level:0}},detail:{value:'1'}});
  await new Promise(resolve=>setImmediate(resolve));
  const value=events.find(event=>event.name==='change').detail.value;
  assert.equal(value.province,'广东省');assert.equal(value.regionCode,'44');assert.equal(value.town,'');assert.equal(value.village,'');
  assert.equal(component.data.rows[1].options[0].name,'广州市');
});

test('late address options cannot overwrite a newer selection or a new session',async()=>{
  let resolve;const pending=new Promise(done=>{resolve=done;});
  const h=componentHarness(parent=>parent==='42'?pending:Promise.resolve(regions[parent]||[]));
  const old=h.component.load({province:'湖北省',regionCode:'42'});
  await new Promise(done=>setImmediate(done));
  await h.component.load({province:'广东省',regionCode:'44'});
  resolve(regions[42]);await old;
  assert.equal(h.component.data.rows[1].options[0].name,'广州市');
  let resolveRoot;const next=componentHarness(()=>new Promise(done=>{resolveRoot=done;}));
  const request=next.component.load({});next.changeSession();resolveRoot(regions['']);await request;
  assert.equal(next.component.data.rows[0].options.length,0);
});

test('missing village data allows explicit manual entry, while fetch errors remain retryable',async()=>{
  const h=componentHarness(async parent=>parent==='420115001'?[]:regions[parent]||[]);
  h.component.data.value={...address,village:'',regionCode:'420115001'};
  await h.component.load(h.component.data.value);assert.equal(h.component.data.manualVillage,true);
  h.component.manual({detail:{value:'新建村'}});assert.equal(h.events[0].detail.value.village,'新建村');
  const failure=componentHarness(async()=>{throw Error('网络异常');});
  await failure.component.load({});assert.equal(failure.component.data.error,'网络异常');
  assert.equal(failure.component.data.rows[0].loading,false);
});

function followupHarness(customerView = {}) {
  const http={sessionGeneration:()=>1}, navigations=[], requests=[];
  // 约定两天后跟进：未逾期。
  const planned=CRM.toDate(Date.now()+2*86400000);
  const {page}=pageHarness(http, navigations);
  const customer={id:'42',name:'钱女士',revision:4,ownerUserId:'11',status:'FOLLOWING',
    workflow:{status:'ACTIVE',completedCount:2,totalSteps:8,progressPercent:25,currentStepCode:'HOUSE_SELECTION',currentStepName:'户型选择',nextStepCode:'SITE_VISIT',nextFollowOn:planned}};
  const stepNames={INVITATION:'邀约进店',INTRODUCTION:'店面讲解',HOUSE_SELECTION:'户型选择',SITE_VISIT:'带看工地',MEASUREMENT:'现场测量',QUOTATION:'别墅报价',CONTRACT:'合同签约',START:'开工大吉'};
  // 步骤名称来自服务端八步视图（Step.label），工作区不自己翻译步骤编码。
  const steps=Object.keys(stepNames).map((code,index)=>({code,name:stepNames[code],order:index+1,
    status:index<2?'COMPLETED':'TODO',revision:index<2?1:0,occurredOn:null,note:'',fields:{},attachments:[],completedAt:null,canWrite:false,canComplete:false,canReopen:false,completionErrors:[],source:'MANUAL'}));
  const entry={id:'9',stepCode:'INTRODUCTION',type:'CONSULT',occurredOn:null,note:'想了解两层的报价',createdAt:Date.now(),actorUserId:'11',actorName:'李业务',revision:5,fields:{},attachments:[]};
  const timeline=(pageNo,total)=>({list:pageNo===1?[entry,{...entry,id:'8',type:'COMPLETE',stepCode:'INTRODUCTION',note:'已到店'}]:[{...entry,id:'7',type:'LEGACY',stepCode:null}],total});
  page.id='42';page.__http=http;page.__pf=CRM;page.__post={postId:'sales',roleCode:'sales'};page.__postKey='sales';page.__pageSession=1;
  page.platformSession=()=>({postId:'sales',roleCode:'sales'});
  page.__crmApi={customers:{
    get:id=>{requests.push(['get',id]);return Promise.resolve({customer,follows:[],schemes:[]});},
    workflow:id=>{requests.push(['workflow',id]);return Promise.resolve({...customerView,workflowId:'5',customerId:'42',status:'ACTIVE',revision:3,completedCount:2,totalSteps:8,progressPercent:25,
      currentStepCode:'HOUSE_SELECTION',nextStepCode:'SITE_VISIT',canWrite:true,steps,events:[],hasMoreEvents:false,nextFollowOn:planned,canChangeStatus:customerView.canChangeStatus!==false});},
    timeline:(id,pageNo)=>{requests.push(['timeline',id,pageNo]);return Promise.resolve(timeline(pageNo,pageNo===1?3:0));}
  }};
  page.setData({platformMode:true,view:'customer',customer:{id:'42'},canFollow:true});
  return {page,navigations,requests,customer};
}

test('the workspace reads the eight-step view and the merged follow-up timeline, and never posts a follow-up',async()=>{
  const {page,requests}=followupHarness();
  assert.equal(CRM.followPayload,undefined,'the old follow payload builder is gone');
  page.loadPlatformDetail('customer');
  for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(requests.map(row=>row[0]),['get','workflow','timeline']);
  assert.equal(page.data.flow.status,'ACTIVE');
  assert.deepEqual(page.data.rail.filter(row=>row.active).map(row=>row.code),['HOUSE_SELECTION']);
  assert.equal(page.data.currentStepName,'户型选择');
  assert.equal(page.data.canChange,true);assert.equal(page.data.nextFollowLate,false);
  assert.equal(page.data.timelineTotal,3);assert.equal(page.data.timelineMore,true);
  assert.deepEqual(page.data.timeline.map(row=>row.title),['店面讲解 · 再次咨询','店面讲解 · 完成步骤']);
  page.moreTimeline();
  for(let i=0;i<4;i++)await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(requests.at(-1),['timeline','42',2]);
  assert.equal(page.data.timeline.length,3);assert.equal(page.data.timelineMore,false);assert.equal(page.data.timeline[2].title,'跟进记录');
});

test('an overdue appointment and a read-only boss are shown from the eight-step view',async()=>{
  const overdue=followupHarness();overdue.page.__crmApi.customers.workflow=()=>Promise.resolve({...overdue.page.data.flow,nextFollowOn:CRM.dateToArray(CRM.toDate(Date.now()-86400000))});
  overdue.page.loadPlatformDetail('customer');
  for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));
  assert.equal(overdue.page.data.nextFollowLate,true);assert.match(overdue.page.data.nextFollowText,/逾期 1 天/);
  const boss=followupHarness({canChangeStatus:false});
  boss.page.loadPlatformDetail('customer');
  for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));
  assert.equal(boss.page.data.canChange,false);
  boss.page.openStatus({currentTarget:{dataset:{kind:'lost'}}});
  assert.deepEqual(boss.navigations,[],'a read-only viewer cannot open the status sheet');
});

test('status actions and the retired follow entry open the eight-step page instead of a follow form',()=>{
  const {page,navigations}=followupHarness();
  page.setData({canChange:true});
  for(const kind of ['paused','lost','resume']) {
    page.openStatus({currentTarget:{dataset:{kind}}});
    assert.equal(navigations.at(-1),'/pages/customer-flow/index?customerId=42&status='+kind);
  }
  page.openCustomerFlow();assert.equal(navigations.at(-1),'/pages/customer-flow/index?customerId=42');
  // 旧版待办深链仍可能带 modal=follow：改为进入八步页，不再弹出“记录跟进”
  page.openModal('follow');
  assert.equal(navigations.at(-1),'/pages/customer-flow/index?customerId=42');
  assert.equal(page.data.modal,'');page.saveModal();
  assert.equal(page.data.error,'');
});

test('the workspace template exposes status actions, the timeline and no follow-up sheet',()=>{
  const template=fs.readFileSync(path.join(root,'miniprogram/pages/workflow/index.wxml'),'utf8');
  assert.doesNotMatch(template,/modal==='follow'|customer\.follows/);
  for(const kind of ['paused','lost','resume'])assert.match(template,new RegExp('data-kind="'+kind+'"[^>]*bindtap="openStatus"|bindtap="openStatus"[^>]*data-kind="'+kind+'"'));
  assert.match(template,/bindtap="moreTimeline"/);assert.match(template,/bindtap="openCustomerFlow"/);
});

function customerWriteHarness(action) {
  let generation=1, post={postId:'sales',roleCode:'sales'}, loads=0;
  const http={sessionGeneration:()=>generation,token:()=>post?'token':''};
  const {page}=pageHarness(http);
  const calls=[], effects=new Map(), reads=[];
  const customer={id:'77',name:'陈客户',revision:4,ownerUserId:'11'};
  const detail={customer:{...customer,name:'已修改',revision:5},follows:[],schemes:[]};
  function delayed(calls,extra) {
    let resolve,reject;
    const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});
    calls.push({...extra,resolve,reject});
    return promise;
  }
  function send(id,payload,key) {
    return delayed(calls,{id,payload,key,commit() {
      if(effects.has(key)) assert.deepEqual(effects.get(key),{id,payload});
      effects.set(key,{id,payload});
    },ack(receipt) {
      this.commit();
      this.resolve(receipt|| (action==='capture'?{result:'CREATED',customerId:'99'}:detail));
    }});
  }
  page.id=action==='capture'?'':'77';
  page.__pageSession=generation;
  page.__http=http;
  page.platformSession=()=>post;
  page.loadPlatformDetail=page.loadPlatformList=()=>{loads++;};
  page.__crmApi={leads:{capture:(payload,key)=>send('',payload,key)},customers:{update:send,transfer:send,get:id=>delayed(reads,{id})}};
  page.setData({customer,platformMode:true,view:{capture:'customers',update:'customer',transfer:'transfer'}[action]});
  if(action==='capture'){
    page.openModal('register');
    page.setData({form:{name:'陈客户',phone:'13800000000',summary:'两层自建房',source:'到店咨询',...address}});
  }else if(action==='update'){
    page.openModal('edit');
    page.setData({'form.name':'已修改'});
  }else{
    page.__transferCandidates=[{label:'同名 · 一组',userId:'101'},{label:'同名 · 二组',userId:'102'}];
    page.setData({form:{transferReason:'调整分工'}});
    page.pickTransferOwner({detail:{value:'1'}});
  }
  return {page,calls,reads,effects,loads:()=>loads,send:()=>action==='transfer'?page.submit():page.saveModal(),
    switchTo(next){generation++;post=next;}};
}

test('transfer submits the selected user ID when names and departments are identical',async()=>{
  const h=customerWriteHarness('transfer'),{page,calls}=h;
  page.__transferCandidates=[{label:'张三 · 一组',userId:'101'},{label:'张三 · 一组',userId:'202'}];
  page.pickTransferOwner({detail:{value:'1'}});
  const saving=h.send();
  assert.equal(calls[0].payload.targetUserId,'202');
  page.pickTransferOwner({detail:{value:'0'}});
  assert.equal(page.data.form.newOwnerUserId,'202','an in-flight write keeps its original recipient');
  calls[0].ack();await saving;
});

test('refreshed transfer candidates keep the selected identity through reorder and clear revoked targets',async()=>{
  const employee=id=>({userId:id,name:'张三',teamName:'一组',roleCode:'sales',active:true,mobile:'13800000'+id});
  let rows=[employee('101'),employee('202')];
  const {page}=pageHarness({},[],async()=>rows);
  page.__post={orgId:'1'};page.__pf=CRM;
  await page.loadTransferTargets(1);
  assert.deepEqual(copy(page.data.ownerOptions),['张三 · 一组 · 手机尾号 0101','张三 · 一组 · 手机尾号 0202']);
  assert(!JSON.stringify(page.data).includes(employee('202').mobile));
  page.pickTransferOwner({detail:{value:'1'}});
  assert.match(page.data.form.newOwner,/0202$/);
  rows=[employee('202'),employee('101')];
  await page.loadTransferTargets(1);
  assert.equal(page.data.form.newOwnerUserId,'202');
  assert.equal(page.data.form.newOwnerIndex,0);
  rows=[employee('101'),{...employee('202'),active:false}];
  await page.loadTransferTargets(1);
  assert.equal(page.data.form.newOwnerUserId,'');
  assert.equal(page.data.form.newOwner,'');
  page.platformSubmitTransfer();assert.match(page.data.error,/请选择接手人/);
});

test('indistinguishable names with missing or matching phone hints cannot be submitted',async()=>{
  const employee=(id,mobile,name='张三')=>({userId:id,name,teamName:'一组',roleCode:'sales',active:true,mobile});
  const rows=[employee('101','13812341234'),employee('202','13945671234'),employee('303',''),employee('404','13800000404','李四')];
  const {page}=pageHarness({},[],async()=>rows);
  page.__post={orgId:'1'};page.__pf=CRM;
  await page.loadTransferTargets(1);
  assert.deepEqual(copy(page.data.ownerOptions), [
    '张三 · 一组 · 手机尾号 1234 · 需核对身份',
    '张三 · 一组 · 手机尾号 1234 · 需核对身份',
    '张三 · 一组 · 需核对身份','李四 · 一组'
  ]);
  for(const value of ['0','1','2']){
    page.pickTransferOwner({detail:{value}});
    assert.equal(page.data.form.newOwnerUserId,'');
    assert.match(page.data.error,/请核对员工资料/);
  }
  page.platformSubmitTransfer();
  assert.match(page.data.error,/请选择接手人/);
  page.pickTransferOwner({detail:{value:'3'}});
  assert.equal(page.data.form.newOwnerUserId,'404');
});

test('transfer cannot use a display label or an invalid picker index as an identity',()=>{
  const {page}=customerWriteHarness('transfer');
  page.setData({form:{newOwner:'同名 · 二组',transferReason:'调整分工'}});
  page.platformSubmitTransfer();assert.match(page.data.error,/请选择接手人/);
  for(const value of ['-1','999','1.5','NaN']){
    page.pickTransferOwner({detail:{value}});
    assert.equal(page.data.form.newOwnerUserId,'');
  }
  page.data.sourceOptions=['朋友介绍','到店咨询'];
  page.pick({currentTarget:{dataset:{key:'source',options:'sourceOptions'}},detail:{value:'1'}});
  assert.equal(page.data.form.source,'到店咨询','unrelated pickers retain their original behavior');
});

for(const action of ['capture','update','transfer']){
  for(const commitBeforeTimeout of [false,true]){
    test(action+' retries once after resume when the first request '+(commitBeforeTimeout?'committed':'did not commit'),async()=>{
      const h=customerWriteHarness(action),{page,calls,effects}=h;
      const original=copy(page.data.form);
      const saving=h.send();
      page.onHide();
      if(commitBeforeTimeout)calls[0].commit();
      if(!commitBeforeTimeout)page.onShow();
      h.send();
      assert.equal(calls.length,1);
      calls[0].reject({code:commitBeforeTimeout?504:-1});
      await saving;
      if(commitBeforeTimeout)page.onShow();
      assert.equal(h.loads(),0);
      assert.equal(page.data.busy,false);
      assert.equal(page.data.customerRetry,'confirm');
      page.input({currentTarget:{dataset:{key:'name'}},detail:{value:'迟到的输入事件'}});
      assert.deepEqual(page.data.form,original);
      assert.equal(effects.size,commitBeforeTimeout?1:0);
      const retry=h.send();
      assert.equal(calls.length,2);
      assert.equal(calls[1].key,calls[0].key);
      assert.deepEqual(calls[1].payload,calls[0].payload);
      if(action!=='capture')assert.equal(calls[1].payload.revision,4);
      if(action==='transfer')assert.equal(calls[1].payload.targetUserId,'102');
      calls[1].ack();
      await retry;
      assert.equal(effects.size,1);
      assert.equal(page.data.busy,false);
      assert.equal(page.data.modal,'');
      if(action==='update')assert.equal(page.data.customer.name,'已修改');
      else assert.equal(page.destination.view,'customer');
    });
  }
  for(const replyWhileHidden of [true,false]){
    test(action+' presents success once when the reply arrives '+(replyWhileHidden?'while hidden':'after resume'),async()=>{
      const h=customerWriteHarness(action),{page,calls}=h;
      const results=[];
      page.openView=(...args)=>results.push(args);
      const apply=page.applyDetail;
      page.applyDetail=function(d){results.push(d);return apply.call(this,d);};
      const saving=h.send();page.onHide();
      if(!replyWhileHidden)page.onShow();
      assert.equal(h.loads(),0);
      calls[0].ack();await saving;
      if(replyWhileHidden){ assert.equal(results.length,0);page.onShow(); }
      page.onShow();assert.equal(results.length,1);assert.equal(page.data.busy,false);assert.equal(calls.length,1);
    });
  }
  for(const accountOnly of [false,true]){
    test(action+' drops the draft and late response on '+(accountOnly?'account':'post')+' switch',async()=>{
      const h=customerWriteHarness(action),{page,calls}=h;
      const saving=h.send();page.onHide();
      h.switchTo({postId:accountOnly?'sales':'new-post',roleCode:'sales'});
      page.onShow();
      assert.equal(page.data.busy,false);assert.equal(page.data.modal,'');assert.deepEqual(page.data.form,{});
      page.setData({form:{name:'新会话草稿'}});
      if(accountOnly)calls[0].ack();else calls[0].reject({code:401});
      await saving;
      assert.equal(page.destination,undefined);assert.equal(page.data.error,'');
      assert.equal(page.data.form.name,'新会话草稿');assert.equal(page.data.customer,null);
    });
  }
}

test('a saved linked registration retries only the detail read and navigates after returning',async()=>{
  const h=customerWriteHarness('capture'),{page,calls,reads}=h;
  const first=h.send();
  calls[0].ack({result:'LINKED',customerId:'99'});
  await new Promise(done=>setImmediate(done));
  page.onHide();page.onShow();
  reads[0].reject({code:-1});await first;
  assert.equal(page.data.customerRetry,'view');
  const retry=h.send();page.onHide();
  reads[1].resolve({});await retry;
  assert.equal(page.destination,undefined);
  page.onShow();assert.equal(page.destination.id,'99');
  assert.equal(calls.length,1);assert.equal(reads.length,2);
});

test('confirmed registration with pending association or restricted detail closes the write form',async()=>{
  for(const result of ['LINK_REQUIRED','LINKED']){
    const h=customerWriteHarness('capture'),{page,calls,reads}=h;
    const first=h.send();
    calls[0].ack({result,customerId:'99'});await new Promise(done=>setImmediate(done));
    if(result==='LINKED')reads[0].reject({code:403});
    await first;
    assert.equal(page.data.modal,'');assert.match(page.data.error,/已登记/);assert.equal(page.data.busy,false);
    h.send();assert.equal(calls.length,1);
  }
});

for(const action of ['update','transfer']){
  test(action+' reloads an actual version conflict and waits for an explicit new submission',async()=>{
    const h=customerWriteHarness(action),{page,calls}=h;
    const original=copy(page.data.form);
    const first=h.send();calls[0].reject({code:1099000001});await first;
    assert.match(page.data.error,/刷新/);assert.equal(h.loads(),1);assert.equal(calls.length,1);
    assert.equal(page.data.busy,false);assert.deepEqual(page.data.form,original);
    page.setData({'customer.revision':7});
    const next=h.send();
    assert.equal(calls[1].payload.revision,7);assert.notEqual(calls[1].key,calls[0].key);
    calls[1].ack();await next;
  });
}

test('customer reminders open the matching customer follow-up tab on a cold entry',()=>{
  const id='90071992547409971', post={postId:'sales',roleCode:'sales'};
  const http={sessionGeneration:()=>1};
  for(const [options,expected] of [
    [{view:'customer',id,tab:'1'},1],[{view:'customer',id},0],
    [{view:'customer',id,tab:'2'},0],[{view:'customers',tab:'1'},0]
  ]){
    const {page}=pageHarness(http);
    page.platformLoad(options,post);
    assert.equal(page.data.activeCustomerTab,expected);
    if(options.id)assert.equal(page.id,id);
  }
});


test('最后一个待办和八步完成来自服务端，不把 nextStepCode 为空当作完成',()=>{
  const {page}=followupHarness();
  page.applyFlow({status:'ACTIVE',completedCount:7,currentStepCode:'START',nextStepCode:null,steps:[]});
  assert.equal(page.data.currentStepName,'开工大吉');
  page.applyFlow({status:'COMPLETED',completedCount:8,currentStepCode:null,nextStepCode:null,steps:[]});
  assert.equal(page.data.currentStepName,'业务八步完成');
});
