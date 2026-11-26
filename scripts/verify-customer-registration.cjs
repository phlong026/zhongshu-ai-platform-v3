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
function pageHarness(http = {}) {
  let definition,windowHeight=800,listener,unsubscribed=false;
  vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/pages/workflow/index.js'),'utf8'),{
    Page(value){definition=value;},Date,Math,Promise,
    wx:{getWindowInfo:()=>({windowHeight}),onKeyboardHeightChange(fn){listener=fn;},offKeyboardHeightChange(fn){unsubscribed=fn===listener;},hideKeyboard(){},showToast(){}},
    require(id){
      if(id.endsWith('/platform-crm'))return CRM;
      if(id.endsWith('/view-container'))return {routeValue:value=>value,fail(){}};
      if(id==='./views')return {titles:{}};
      if(id.endsWith('/http'))return http;
      if(id.endsWith('/organization'))return {roles:{sales:'销售'}};
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

function followHarness() {
  let generation = 1, post = {postId:'sales',roleCode:'sales'}, reads = 0;
  const http = {token:()=>post ? 'token' : '',sessionGeneration:()=>generation};
  const {page} = pageHarness(http);
  const calls = [], records = new Map();
  page.id = 'customer-1';
  page.__http = http;
  page.platformSession = () => post;
  page.loadPlatformDetail = () => { reads++; };
  page.setData({view:'customer',platformMode:true,canFollow:true});
  page.__crmApi.customers = {follow(id,payload,key) {
    let resolve, reject;
    const promise = new Promise((ok,fail)=>{resolve=ok;reject=fail;});
    function commit() {
      if(records.has(key)) assert.deepEqual(records.get(key),payload);
      records.set(key,payload);
    }
    calls.push({id,key,payload,commit,reject,acknowledge() {
      commit();
      resolve({customer:{id,name:'客户'},follows:[...records.values()].map(row=>({note:row.note,occurredOn:row.occurredOn}))});
    }});
    return promise;
  }};
  page.openModal('follow');
  page.input({currentTarget:{dataset:{key:'followContent'}},detail:{value:'约电话沟通方案'}});
  page.date({currentTarget:{dataset:{key:'appointment'}},detail:{value:CRM.today()}});
  return {page,calls,records,reads:()=>reads,changePost(next) { generation++; post=next; }};
}

for(const replyWhileHidden of [false,true]) {
  test('pending follow displays the saved record when the reply arrives '+(replyWhileHidden?'in background':'after return'),async()=>{
    const {page,calls,records,reads}=followHarness();
    const save=page.saveModal();
    page.onHide();
    if(!replyWhileHidden) { page.onShow(); page.onShow(); }
    assert.equal(reads(),0);
    page.saveModal();
    assert.equal(calls.length,1);
    calls[0].acknowledge();
    await save;
    if(replyWhileHidden)page.onShow();
    assert.equal(page.data.busy,false);
    assert.equal(page.data.modal,'');
    assert.equal(page.data.activeCustomerTab,1);
    assert.equal(page.data.customer.follows[0].content,'约电话沟通方案');
    assert.equal(records.size,1);
    assert.equal(reads(),0);
    page.onShow();
    assert.equal(reads(),1);
  });
}

for(const [saved,code] of [[true,-1],[true,504],[false,-1]]) {
  test('follow retries the same command after '+(saved?'a lost receipt':'a failed write')+' ('+code+')',async()=>{
    const {page,calls,records,reads}=followHarness();
    const first=page.saveModal();
    page.onHide();
    if(saved)calls[0].commit();
    if(!saved)page.onShow();
    calls[0].reject({code});
    await first;
    if(saved)page.onShow();
    assert.equal(reads(),0);
    assert.equal(page.data.busy,false);
    assert.equal(page.data.modal,'follow');
    assert.equal(page.data.form.followContent,'约电话沟通方案');
    assert.match(page.data.error,/重试/);
    assert.equal(records.size,saved?1:0);
    const retry=page.saveModal();
    assert.equal(calls[0].key,calls[1].key);
    assert.deepEqual(calls[0].payload,calls[1].payload);
    calls[1].acknowledge();
    await retry;
    assert.equal(page.data.customer.follows.length,1);
    assert.equal(page.data.modal,'');
    assert.equal(records.size,1);
  });
}

for(const [label,post] of [['account with the same post ID',{postId:'sales',roleCode:'sales'}],['post',{postId:'other',roleCode:'sales'}],['logout',null]]) {
  test('switching '+label+' discards the follow form and its late reply',async()=>{
    const {page,calls,changePost}=followHarness();
    const oldSave=page.saveModal();
    page.onHide();
    changePost(post);
    page.onShow();
    assert.equal(page.data.modal,'');
    assert.equal(page.data.busy,false);
    assert.deepEqual(page.data.form,{});
    if(post) {
      page.setData({canFollow:true});
      page.openModal('follow');
      page.input({currentTarget:{dataset:{key:'followContent'}},detail:{value:'新身份草稿'}});
    }
    if(label==='post')calls[0].reject({code:401});
    else calls[0].acknowledge();
    await oldSave;
    assert.equal(page.data.customer,null);
    assert.equal(page.data.error,'');
    assert.equal(page.data.form.followContent,post?'新身份草稿':undefined);
  });
}

test('a late follow receipt cannot update a different customer or an unloaded page',async()=>{
  for(const unload of [false,true]) {
    const {page,calls}=followHarness();
    const first=page.saveModal();
    page.onHide();
    if(unload)page.onUnload();
    else { page.id='customer-2'; page.onShow(); }
    calls[0].acknowledge();
    await first;
    assert.equal(page.data.customer,null);
    if(!unload)assert.equal(page.data.busy,false);
  }
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
    page.setData({form:{newOwner:'同名 · 二组',transferReason:'调整分工'}});
  }
  return {page,calls,reads,effects,loads:()=>loads,send:()=>action==='transfer'?page.submit():page.saveModal(),
    switchTo(next){generation++;post=next;}};
}

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
