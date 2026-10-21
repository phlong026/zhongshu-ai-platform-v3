const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const dir=path.resolve('qa/tail-payment-repair-20261021');fs.mkdirSync(dir,{recursive:true});
const copy=x=>JSON.parse(JSON.stringify(x)),trace=require('./hq-design-fixtures.cjs')(),view=s=>new URL(s.url,'http://local').searchParams.get('view');
(async()=>{
 const rt=await require('./role-page-runtime.cjs').connect(),A=require('../miniprogram/utils/account'),B=require('../miniprogram/utils/business'),F=require('../miniprogram/utils/finance-source'),M=require('../miniprogram/pages/admin/model'),T=require('../miniprogram/utils/intercompany'),D=require('../miniprogram/utils/design-coordination');
 const ready=trace.find(s=>view(s)==='deliverable-review'),tail=trace.find(s=>view(s)==='tail-apply'),id=new URL(tail.url,'http://local').searchParams.get('id'),checks=[],accounts={};
 const p=()=>getCurrentPages().at(-1),raw=()=>wx.getStorageSync(B.KEY).customers.find(c=>c.id===id),file=name=>({name,sample:true}),pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 function install(rows){for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,copy(v));}
 function login(who,role){A.mutate(s=>A.loginDemo(s,who));if(role)A.mutate(s=>A.switchIdentity(s,s.session.companyId,role));}
 function open(v,form={},area='workflow',recordId=id){wx.reLaunch({url:'/pages/'+area+'/index?view='+v+'&id='+encodeURIComponent(recordId)});p().setData({form:{...p().data.form,...form}});return p();}
 function submit(v,form={},area='workflow',recordId=id){const page=open(v,form,area,recordId);assert(!page.data.blocked,page.data.blocked?.message);page.submit();if(page.data.modal?.type==='confirm')page.confirm();assert(!page.data.error,page.data.error);return p();}
 const admin=(v,recordId,form)=>submit(v,form,'admin',recordId),moneyTask=()=>M.load().payables.find(q=>q.source?.id===id&&q.source.stage==='tail');
 async function tap(selector){const e=await(await rt.currentPage()).$(selector);assert(e,'Rendered '+selector);await e.tap();}
 try{
  install(ready.before);
  // Existing signed contract is a fixture fact, never created by the payment action.
  const signedScene=trace.find(s=>view(s)==='contract-prepare'),signed=signedScene.after.find(r=>r[0]===B.KEY)[1].customers.find(c=>c.id===id),store=wx.getStorageSync(B.KEY),c=store.customers.find(c=>c.id===id);
  Object.assign(c,{contract:copy(signed.contract),signedFile:copy(signed.signedFile),quotes:copy(signed.quotes)});wx.setStorageSync(B.KEY,store);const contractBefore=JSON.stringify(raw().contract);
  login('hq-design-lead');submit('intake',{intakeChecked:true});
  const initial=wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]);
  for(const who of ['sales-dual','boss','finance-wang','finance-lead','hq-finance','hq-finance-lead','hq-design-lead','hq-design-dual']){login(who,who==='hq-design-dual'?'hq-designer':undefined);accounts[who]=wx.getStorageSync(A.KEY);}
  fs.writeFileSync(path.join(dir,'fixture.json'),JSON.stringify({rows:initial,accounts,id,today:A.localDate()},null,2));
  login('sales-dual');assert(open('tail-apply').data.blocked?.message.includes('审核'));assert.throws(()=>B.write(id,n=>{n.tailApplication={version:1,serviceVersion:1,note:'跳过审核',date:A.localDate(),status:'pending'};},'跳过成果审核','tail-apply'),/审核/);pass('未审核成果：页面与模型均拒绝尾款申请');
  login('hq-design-lead');submit('deliverable-review',{reviewResult:'审核通过',reviewOpinion:'总部按原委托核对成果完整',reviewFile:file('本版成果审核.pdf')});
  login('sales-dual');wx.reLaunch({url:'/pages/business/index'});assert(p().data.flowPending.some(r=>r.customerId===id&&r.url.includes('tail-apply')&&r.state.includes('审核通过')));pass('总部审核通过后，加盟商业务首页出现尾款申请待办和原项目入口');
  for(const v of ['request','delivery','formal-progress']){open(v);assert(!JSON.stringify(p().data.sections).includes('建造合同'));assert(!JSON.stringify(p().data.sections).includes('建造签约'));}pass('委托、项目流程、设计进度隐藏建造合同，原签约记录保留');
  submit('tail-apply',{tailApplyNote:'本版成果审核通过，按原设计服务单申请尾款'});assert.equal(p().data.view,'payment-tail');assert(p().data.coordination.state.includes('待老板审批'));assert.equal(raw().tailApplication.serviceVersion,raw().serviceOrders.at(-1).version);const application=JSON.stringify(raw().tailApplication);submit('tail-apply');assert.equal(JSON.stringify(raw().tailApplication),application);pass('业务提交尾款直达付款进度，关联原服务单且重复进入不重复申请');
  open('delivery');const row=p().data.sections.flatMap(s=>s.items.flatMap(i=>i.values||[])).find(r=>r.title==='老板审批与尾款核对');assert.equal(row.state,'current');assert.equal(row.view,'payment-tail');await tap('.workflow-nav[data-view="payment-tail"]');assert.equal(p().data.view,'payment-tail');open('formal-progress');assert.equal(p().data.primaryView,'payment-tail');await tap('.primary-action');assert.equal(p().data.view,'payment-tail');pass('原断点：项目当前卡片及正式设计底部按钮均可继续尾款办理');
  const pid=moneyTask().id;login('finance-wang');assert(!open('pay',{},'admin',pid).data.action);login('hq-design-dual','hq-designer');open('release');p().submit();assert(!raw().release);pass('老板审批前禁止付款，款项未齐禁止正式下发');
  login('boss');assert(F.tasks(M.load()).some(t=>t.id===pid&&t.view==='pay-approval'));admin('pay-approval',pid,{checked:true,note:'核对原服务单、成果及本次尾款'});assert(moneyTask().approvalValid);pass('尾款申请自动进入加盟商老板待办，审批只产生财务待办');
  login('finance-wang');assert(F.tasks(M.load()).some(t=>t.id===pid&&t.view==='pay'));const due=moneyTask().total/100;admin('pay',pid,{amount:String(due),date:A.localDate(),transaction:'QA-TAIL-STORE-PAY',file:file('模拟尾款实付.pdf'),checked:true});let payment=M.load().payments.find(r=>r.transaction==='QA-TAIL-STORE-PAY');assert.equal(payment.status,'待复核');assert.equal(T.condition(raw(),'tail').paid,0);pass('门店分人财务：实付登记后待复核，未自动算成有效付款');
  login('finance-lead');admin('payment-review',payment.id,{checked:true,file:file('模拟付款独立复核.pdf'),note:'独立核对本笔实际付款'});assert.equal(T.condition(raw(),'tail').paid,Math.round(due*100));assert(!T.condition(raw(),'tail').ready);pass('门店另一财务负责人复核后有效付款，但总部未到账仍不能下发');
  login('hq-finance');let incoming=M.load().receipts.find(r=>r.source?.customerId===id&&r.source.stage==='tail'&&r.status==='待登记');assert(incoming);admin('incoming-register',incoming.id,{amount:String(due),date:A.localDate(),transaction:'QA-TAIL-HQ-RECEIVED',file:file('模拟总部实际到账.pdf'),checked:true,note:'按原付款流水匹配本服务单尾款'});assert(!T.condition(raw(),'tail').ready);pass('总部实际到账登记独立于门店付款，登记后等待本方独立核对');
  login('hq-finance-lead');admin('reconcile',incoming.id,{checked:true});assert(T.condition(raw(),'tail').ready);pass('总部另一负责人独立核对后，尾款满足正式下发条件');
  login('hq-design-dual','hq-designer');open('formal-progress');assert.equal(p().data.primaryView,'release');submit('release');assert(raw().release);const release=JSON.stringify(raw().release);open('release');p().submit();assert.equal(JSON.stringify(raw().release),release);pass('原授权设计人员收到下发入口，正式下发绑定审核版本且不重复下发');
  login('sales-dual');wx.reLaunch({url:'/pages/business/index'});assert(p().data.flowPending.some(r=>r.customerId===id&&r.url.includes('design-receipt')));submit('design-receipt',{designReceiveChecked:true});assert(D.receipt(raw()));assert.equal(JSON.stringify(raw().contract),contractBefore);pass('正式下发回到加盟商收图待办，收图成功且已签建造合同未改写');
  fs.writeFileSync(path.join(dir,'model-results.json'),JSON.stringify({result:'passed',scope:'真实 Page/WXML 与模型连续操作，非微信原生渲染或银行转账',checks},null,2));
 }catch(e){fs.writeFileSync(path.join(dir,'model-failure.json'),JSON.stringify({error:e.stack,checks,view:p()?.data.view,pageError:p()?.data.error,primary:p()?.data.primary,task:require('../miniprogram/utils/design-tasks').current(B.customer(id)),context:require('../miniprogram/utils/access').context(),projected:B.customer(id)},null,2));throw e;}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
