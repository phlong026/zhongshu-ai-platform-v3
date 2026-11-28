const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const Sheet=require('../miniprogram/pages/workflow/commission/order-sheet');
const CRM=require('../miniprogram/utils/platform-crm');
test('customer list and detail reflect the actual commission without losing follow state',()=>{
 for(const [designStatus,text] of Object.entries({FEE_PENDING:'委托待审核',FEE_RETURNED:'委托待修改',DESIGNING:'设计中',DESIGN_COMPLETED:'设计完成',DISPATCHED:'成果已下发',ACCEPTED:'已移交工程'})){
  const customer={id:'90071992547409930',status:'FOLLOWING',designStatus};
  assert.equal(CRM.customerRow(customer,{}).stageName,text);assert.equal(CRM.detailView({customer},{}).customer.status,text);assert.equal(customer.status,'FOLLOWING');
 }
 assert.equal(CRM.customerRow({id:'1',status:'REGISTERED'},{}).status,'已登记');
});
test('commission export uses the visible snapshot, wraps long text and omits unavailable facts',()=>{
 const data={view:'progress',designBrief:{projectName:'测试项目',specialNeeds:'长文本需求'.repeat(100)},orderCustomer:{name:'测试客户',phone:'13800138000',address:'测试建房地址'},showCustomerPhone:false,budgetBasis:{version:2,area:240,floors:2,width:12,depth:10},form:{}};
 const sheet=Sheet.model(data,{commissionId:'90071992547409930',request:{version:3}},'设计中');
 assert.equal(sheet.number,'90071992547409930');assert.equal(sheet.version,3);assert.equal(sheet.fees.length,0);assert(!JSON.stringify(sheet).includes('13800138000'));
 const calls=[],ctx={measureText:s=>({width:Array.from(s).length*25}),fillRect(){},fillText:(...args)=>calls.push(args)};
 const canvas={getContext:()=>ctx},size=Sheet.draw(canvas,sheet);
 assert.equal(size.width,750);assert(size.height>1200);assert.equal(calls.filter(c=>c[0].includes('长文本')).map(c=>c[0]).join(''),data.designBrief.specialNeeds);assert(calls.every(c=>c[2]<size.height));
 assert.equal(Sheet.model({...data,view:'submit'},{}).status,'待提交 · 核对稿');
});
function generation(){
 let page,failedNavigation=true,stored=null,creates=0,jobs=0,quotes=0;const routes=[],globals={};
 const attempts={begin:()=>stored||(stored={scope:'s',key:'stable',payload:{},state:'DRAFT'}),save:(_,a)=>stored=a,assertCurrent(){},submitted:(_,a)=>a.state='SUBMITTED',complete:()=>stored=null,read:()=>stored,failed:()=>false};
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/design-migration/pages/ai-design/index.js'),'utf8'),{
  require:p=>p.endsWith('/access')?{protectedPage:p=>page=p}:p.endsWith('/context')?{getApp:()=>({globalData:globals})}:p.endsWith('/api')?{createProject:async()=>{creates++;return{projectId:'42'};}}:p.endsWith('/generation-attempt')?attempts:p.endsWith('/generation-options')?require('../miniprogram/design-migration/utils/generation-options'):p.endsWith('/design-inputs')?require('../miniprogram/design-migration/utils/design-inputs'):p.endsWith('/generation-price')?{confirm:async()=>{quotes++;return{companyPrice:{}};}}:p.endsWith('/budget-draft')?{sessionScope:()=> 's'}:p.endsWith('/customer-context')?{bind(){}}:{},
  wx:{showToast(){},redirectTo:o=>{routes.push(o.url);failedNavigation?o.fail():o.success();}}
 });
 page.setData=p=>Object.assign(page.data,p);
 const spec={slot:'flat',stage:'FLAT',count:1,body:{},imageOptions:{resolution:'2K'},redirectUrl:'/design-migration/pages/ai-design/generating?stage=plane',createJob:async()=>{jobs++;return{jobId:'71'};}};
 return {page,spec,routes,ok:()=>failedNavigation=false,result:()=>({creates,jobs,quotes,stored})};
}
test('first submission opens its exact job; retrying a failed navigation never creates or charges again',async()=>{
 const h=generation();await h.page.submitAttempt(h.spec);assert.equal(h.page.data.creating,false);assert.equal(h.page.data.pendingGeneration,true);
 assert.match(h.routes[0],/projectId=42&jobId=71/);assert.equal(h.result().stored.jobId,'71');
 h.ok();await h.page.submitAttempt(h.spec);assert.deepEqual(h.result(),{creates:1,jobs:1,quotes:1,stored:null});assert.equal(h.routes.length,2);
});

test('accepted work remains resumable when the user has already left the design form',async()=>{
 const h=generation();h.page._candidateClosed=true;await h.page.submitAttempt(h.spec);assert.equal(h.routes.length,0);assert.equal(h.result().stored.jobId,'71');assert.equal(h.page.data.creating,false);
 h.page._candidateClosed=false;h.ok();await h.page.submitAttempt(h.spec);assert.equal(h.result().jobs,1);assert.equal(h.routes.length,1);
});

test('retired legacy submission rejects even when older state advertises simulation or formal fees',()=>{
 let page;
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/workflow/commission/index.js'),'utf8'),{
  Page:value=>{page=value;},require:id=>id.endsWith('/view-container')?{makeFormHandlers:()=>({})}:{}
 });
 for(const testPayment of [true,false]){
  const ctx={data:{form:{__mode:'AREA_FULL',__budgetVersion:1,__pricingVersion:2,text:'原需求',scope:'原范围',checked:true,testPayment}},__context:{feeOffer:{simulationAllowed:true}}};
  assert.throws(()=>page.commandPayload.call(ctx,'commission-submit'),/旧委托新增已停用/);
 }
});

function commissionPage(postSubmit, postCommand) {
 let definition;
 const offer={mode:'AREA_FULL',ruleVersion:2,scope:'设计方案',totalCents:1000,simulationPaymentMode:'TEST_SIMULATION'};
 const api={commissions:{
   context:async()=>({customerId:'7',customerName:'张工',allowedActions:['commission-submit'],
     latestBudget:{version:1},feeOffer:{...offer}}),
   submit:postSubmit,
   command:postCommand,
 }};
 const presentation=require('../miniprogram/pages/workflow/commission/presentation');
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/workflow/commission/index.js'),'utf8'),{
  Page:value=>{definition=value;},
  wx:{showToast(){},pageScrollTo(){},removeStorageSync(){}},
  require:id=>({
   '../../../utils/view-container':{makeFormHandlers:()=>({})},
   '../../../api/franchise/commission':api,'../../../api/http':{sessionGuard:()=>()=>true},
   './presentation':presentation,'./design-brief':require('../miniprogram/pages/workflow/commission/design-brief')
  }[id]||{})
 });
 const page={...definition,view:'submit',action:'commission-submit',customerId:'7',__byCustomer:true,options:{},
  initialized:true,__post:{},__context:{feeOffer:{...offer}},actor:()=>({}),
  setData(patch){for(const [key,value] of Object.entries(patch)){
   if(key.startsWith('form.'))this.data.form[key.slice(5)]=value;
   else this.data[key]=value;
  }},
  refresh(){if(this.view==='submit')return this.refreshCommission(()=>true,false);},
  refreshOrderSheet(){}};
 page.data={...definition.data,platformStatus:'ready',simulationAllowed:true,
   form:{__mode:'AREA_FULL',__budgetVersion:1,__pricingVersion:2,text:'建房需求',scope:'设计方案',checked:true,testPayment:true}};
 return {page,withdraw:()=>{delete offer.simulationPaymentMode;}};
}
test('原负责人转交后，指定接收人可以在原页面确认移交',async()=>{
 const calls=[]; let approved=false;
 const {page}=commissionPage(null,async(id,action,payload,key)=>{
  calls.push({id,action,payload,key});
  if(!approved)throw {code:1115000011,message:'请联系现负责人在工程档案中变更负责人'};
  return {};
 });
 page.view='receive';page.id='8001';page.action='packet-receive';page.data.form={checked:true};
 await page.submit();
 assert.equal(page.view,'receive');
 assert.match(page.data.error,/联系现负责人/);
 approved=true;
 await page.submit();
 assert.equal(calls.length,2);
 for(const call of calls) assert.deepEqual({...call.payload},{confirmed:true});
 assert.equal(calls[1].key,calls[0].key);
 assert.equal(page.view,'progress');
});
test('stale formal and simulated drafts cannot send a new LEGACY request',async()=>{
 const calls=[];const {page}=commissionPage(async(...call)=>{calls.push(call);return {commissionId:'8'};});
 for(const testPayment of [true,false]){
  page.data.form.testPayment=testPayment;page.data.form.checked=true;
  await page.submit();assert.match(page.data.error,/旧委托新增已停用/);assert.equal(page.data.busy,false);
 }
 assert.deepEqual(calls,[]);
});

test('archive pagination uses the committed keyword and discards stale search responses',async()=>{
 let definition;
 const requests=[];
 vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/design-migration/pages/customer/archive.js'),'utf8'),{
  require:path=>({
   '../../utils/access':{protectedPage:p=>definition=p},
   '../../../api/franchise/crm':{customers:{page:(number,size,keyword)=>new Promise(resolve=>{
    requests.push({number,size,keyword,resolve});
   })}},
   '../../utils/record-view':{id:String},
  }[path]||{})
 });
 const page={...definition,data:{...definition.data},current:()=>true,setData(patch){Object.assign(this.data,patch);}};
 page.input({detail:{value:'陈'}});
 const first=page.search();
 requests[0].resolve({list:[{id:'1'}],total:22});await first;
 page.choose({currentTarget:{dataset:{id:'1'}}});
 page.input({detail:{value:'王'}});
 const oldPage=page.more();
 assert.equal(requests[1].keyword,'陈');
 const newPage=page.search();
 assert.equal(page.data.selected,'');
 assert.equal(requests[2].keyword,'王');
 requests[2].resolve({list:[{id:'2'}],total:1});await newPage;
 requests[1].resolve({list:[{id:'3'}],total:22});await oldPage;
 page.choose({currentTarget:{dataset:{id:'3'}}});
 assert.equal(page.data.rows.length,1);
 assert.equal(page.data.rows[0].id,'2');
 assert.equal(page.data.selected,'');
});
