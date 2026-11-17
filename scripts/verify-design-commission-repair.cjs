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
