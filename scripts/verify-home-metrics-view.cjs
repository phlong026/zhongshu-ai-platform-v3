const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function model(){const module={exports:{}};vm.runInNewContext(fs.readFileSync('miniprogram/utils/home-metrics.js','utf8'),{module,require:p=>p==='../api/http'?{}:{localDate:()=> '2026-11-18'}});return module.exports;}
test('金额以分计算，万元显示不丢原始明细金额，负净额保留负号',()=>{const h=model();assert.equal(h.money(18420000).value,'18.42');assert.equal(h.money(-9380000).value,'-9.38');assert.equal(h.money(12345,false).value,'123.45');assert.equal(h.money(null).value,'0');assert.equal(h.metric('net',null,'finance').value,'—');});
test('退款和待报销按金额展示，工程到期未收按项目数展示',()=>{const h=model(),r={total:2,cents:240000};assert.equal(h.metric('reimbursements',r,'finance').value,'2,400');assert.equal(h.metric('projectDue',r,'engineering').value,'2');});
test('日期支持后端毫秒与LocalDate数组，不出现NaN逾期天数',()=>{const h=model(),r={total:1,cents:0};for(const first_date of [Date.parse('2026-11-15T00:00:00+08:00'),[2026,11,15],'2026-11-15'])assert.equal(h.metric('overdueNodes',{...r,first_date},'engineering').caption,'最长3天');});
test('岗位、月份、指标保留到详情URL；源单路由保留不透明大ID',()=>{const h=model(),id='9223372036854775807';assert.equal(h.detailUrl('business','signed','2026-10'),'/pages/home-metrics/index?role=business&metric=signed&period=2026-10');assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'node',source_id:id,project_id:'12'},'engineering').includes('nodeId='+id));assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'payment',source_id:id},'finance').includes('messageObject='+id));assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'declaration',source_id:id},'finance').includes('messageType=sale_collection_declaration'));});
test('首页换月与换岗的迟到响应不能覆盖当前指标',async()=>{const h=model(),pending=[],http={sessionGeneration:()=>1,token:()=>true},auth={currentApiPost:()=>({postId:'p1'})};let definition;vm.runInNewContext(fs.readFileSync('miniprogram/components/home-overview/index.js','utf8'),{Component:d=>definition=d,require:p=>p.includes('home-metrics')?{...h,load:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))}:p.endsWith('/http')?http:auth});const page={properties:{role:'business'},data:{month:'2026-11'},__attached:true,setData(p){Object.assign(this.data,p);}};Object.assign(page,definition.methods);const first=page.load();page.data.month='2026-10';const second=page.load();pending[1].resolve({metrics:{signed:{total:1,cents:12000000}}});await second;pending[0].resolve({metrics:{signed:{total:99,cents:99000000}}});await first;assert.equal(page.data.board.metrics.signed.value,'12');const third=page.load();auth.currentApiPost=()=>({postId:'p2'});pending[2].resolve({metrics:{signed:{total:1,cents:88000000}}});await third;assert.equal(page.data.board.metrics.signed.value,'—');});

test('材料和待接收入口定位原供货事项与接收办理',()=>{const h=model();assert.equal(require('../miniprogram/pages/home-metrics/source-url')({source_type:'material',source_id:'23',project_id:'12'},'engineering'),'/pages/build/record/index?id=12&view=material&homeSupply=23');assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'handover',source_id:'45',customer_id:'6'},'engineering').endsWith('view=receive'));});
test('外部委托入口先选择真实客户，普通客户入口不受影响',()=>{let mod={exports:{}},urls=[];vm.runInNewContext(fs.readFileSync('miniprogram/utils/sales-customers-page.js','utf8'),{module:mod,require:()=>({}),wx:{navigateTo:o=>urls.push(o.url)}});const p=mod.exports();p.setData=function(v){Object.assign(this.data,v);};p.data.customers=[{id:'7'}];p.data.canRegister=true;const e={currentTarget:{dataset:{id:'7'}}};p.onLoad({intent:'entrust'});p.open(e);assert.equal(urls.pop(),'/pages/external-entrust/index?customerId=7');p.onLoad({});p.open(e);assert.equal(urls.pop(),'/pages/workflow/index?view=customer&id=7');});

test('home opens start registration and the original tail installment',()=>{
 const route=require('../miniprogram/pages/home-metrics/source-url');
 const start=route({project_id:'7',source_type:'node',milestone:'START',source_id:'200'},'engineering','nextNodes','2026-11','engineer');
 assert.match(start,/id=7&view=start$/);
 const row={project_id:'7',source_type:'project-receivable',stage_code:'tail',stage_id:'81'};
 assert.match(route(row,'owner','projectDue','2026-11','boss'),/view=collection&stage=tail$/);
 assert.match(route(row,'finance','projectDue','2026-11','finance'),/homeStage=81$/);
});

// Run the real component handlers and view mapper; only identity and API I/O are simulated.
function overviewFixture() {
 const pending = [], state = { session: 1, token: 'token', userId: 'u1', tenantId: 't1', post: { postId: 'p1', orgId: 'o1' } };
 const http = { sessionGeneration: () => state.session, token: () => state.token, currentUserId: () => state.userId, tenantId: () => state.tenantId };
 const home = { ...model(), load(role, month) { return new Promise((resolve, reject) => pending.push({ role, month, resolve, reject })); } };
 let definition;
 vm.runInNewContext(fs.readFileSync('miniprogram/components/home-overview/index.js','utf8'), {
  Component: value => { definition = value; },
  require: name => name.includes('home-metrics') ? home : name.endsWith('/http') ? http : { currentApiPost: () => state.post }
 });
 const page = { ...definition.methods, properties: { role: 'business', period: '2026-11' }, data: JSON.parse(JSON.stringify(definition.data)), __attached: true,
  setData(patch) { Object.assign(this.data, patch); }, triggerEvent() {} };
 page.data.month = '2026-11';
 return { page, definition, pending, state };
}
const overviewResult = cents => ({ metrics: { signed: { total: 1, cents } } });

test('overview attached and page show share one initial request',async()=>{
 const f=overviewFixture();
 const first=f.definition.lifetimes.attached.call(f.page);
 const second=f.definition.pageLifetimes.show.call(f.page);
 assert.equal(f.pending.length,1);
 f.pending[0].resolve(overviewResult(123400));await Promise.all([first,second]);
 assert.equal(f.page.data.board.metrics.signed.value,'1,234');
 assert.equal(f.page.data.loading,false);
});

test('same-scope overview refresh and network failure preserve visible metrics',async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const visible=f.page.data.board,refresh=f.page.load();
 assert.equal(f.page.data.board,visible);
 assert.equal(f.page.data.loading,true);
 f.pending[1].reject({code:-1});await refresh;
 assert.equal(f.page.data.board,visible);
 assert.equal(f.page.data.loading,false);
 assert.match(f.page.data.error,/无法加载/);
 const retry=f.page.load();f.pending[2].resolve(overviewResult(222200));await retry;
 assert.equal(f.page.data.board.metrics.signed.value,'2,222');
 assert.equal(f.page.data.error,'');
});

for(const change of ['session','user','tenant','organization','post','postRole','orgType','role','month'])test('overview clears data when '+change+' changes',async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const old=f.page.load();
 if(change==='session')f.state.session++;
 if(change==='user')f.state.userId='u2';
 if(change==='tenant')f.state.tenantId='t2';
 if(change==='organization')f.state.post={...f.state.post,orgId:'o2'};
 if(change==='post')f.state.post={...f.state.post,postId:'p2'};
 if(change==='postRole')f.state.post={...f.state.post,roleCode:'owner'};
 if(change==='orgType')f.state.post={...f.state.post,orgType:'company'};
 if(change==='role')f.page.properties.role='owner';
 if(change==='month')f.page.data.month='2026-10';
 const fresh=f.page.load();assert.equal(f.page.data.board.metrics.signed.known,false);
 f.pending[2].resolve(overviewResult(222200));await fresh;
 f.pending[1].resolve(overviewResult(999900));await old;
 assert.equal(f.page.data.board.metrics.signed.value,'2,222');
});

test('hide releases an unfinished request; show can refresh and ignores old permission failures',async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const old=f.page.load();f.definition.pageLifetimes.hide.call(f.page);
 const next=f.definition.pageLifetimes.show.call(f.page);
 assert.equal(f.pending.length,3);
 assert.equal(f.page.data.board.metrics.signed.value,'1,234');
 f.pending[1].reject({code:403});await old;
 assert.equal(f.page.data.board.metrics.signed.value,'1,234');
 f.pending[2].resolve(overviewResult(222200));await next;
 assert.equal(f.page.data.board.metrics.signed.value,'2,222');assert.equal(f.page.data.loading,false);
});

for(const error of [{code:401},{code:403},{code:1103000002,postInvalid:true}])test('current overview authorization failure clears retained metrics: '+error.code,async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const refresh=f.page.load();f.pending[1].reject(error);await refresh;
 assert.equal(f.page.data.board.metrics.signed.known,false);assert.equal(f.page.data.loading,false);
});

test('logout during an overview request clears the old board without accepting the response',async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const old=f.page.load();f.state.session++;f.state.token='';f.state.post=null;
 f.pending[1].resolve(overviewResult(999900));await old;
 assert.equal(f.page.data.board.metrics.signed.known,false);assert.equal(f.page.data.loading,false);
});

test('overview status collapses when idle and reserves height only while loading or errored',()=>{
 const wxml=fs.readFileSync('miniprogram/components/home-overview/index.wxml','utf8');
 const css=fs.readFileSync('miniprogram/components/home-overview/index.wxss','utf8');
 // 元素常驻（保住 aria-live 与固定文档位置），但空闲时高度收敛为 0，不再留常驻空白。
 assert.match(wxml,/<view class="overview-status \{\{\(error\|\|loading\)\?'active':''\}\}"/);
 assert.match(css,/\.overview-status\s*\{[^}]*\bheight:\s*0/);
 assert.match(css,/\.overview-status\.active\s*\{[^}]*\bheight:\s*56rpx/);
});

// Real detail/record handlers, CRM adapter, identity scope and WXML; only I/O is simulated.
function budgetFixture(name='record',options={type:'budget',customerId:'101',id:'9223372036854775806',version:'1'}){
 const state={session:1,userId:'u1',tenantId:'t1',post:{postId:'p1',orgId:'o1',roleCode:'sales',orgType:'store'}},calls=[],pending=[],urls=[],toasts=[],guards=new Map();
 const http={token:()=>true,sessionGeneration:()=>state.session,currentUserId:()=>state.userId,tenantId:()=>state.tenantId,currentPostId:()=>state.post?.postId,
  requestGuard(domain){const sequence=(guards.get(domain)||0)+1,session=state.session;guards.set(domain,sequence);return()=>guards.get(domain)===sequence&&session===state.session;},
  get(url,params){calls.push({method:'GET',url,params});if(url==='/franchise/home/metrics/budgets')return Promise.resolve({rows:[],total:0,cents:0,pageSize:20});return new Promise((resolve,reject)=>pending.push({url,resolve,reject}));},
  post(url,body,headers){calls.push({method:'POST',url,body,headers});return Promise.resolve({});}
 };
 const auth={currentApiPost:()=>state.post};
 const load=(file,require)=>{const module={exports:{}};vm.runInNewContext(fs.readFileSync(file,'utf8'),{module,require},{filename:file});return module.exports;};
 const home=load('miniprogram/utils/home-metrics.js',p=>p==='../api/http'?http:{localDate:()=> '2026-11-27'});
 const scope=load('miniprogram/utils/api-scope.js',p=>p==='../api/http'?http:auth);
 const crm=load('miniprogram/api/franchise/crm.js',()=>http);
 let definition;
 vm.runInNewContext(fs.readFileSync('miniprogram/pages/home-metrics/'+name+'.js','utf8'),{Page:d=>definition=d,
  wx:{showToast:o=>toasts.push(o.title)},require:p=>{
   if(p==='./source-url')return require('../miniprogram/pages/home-metrics/source-url');
   if(p.endsWith('/home-metrics'))return home;
   if(p.endsWith('/http'))return http;
   if(p.endsWith('/auth'))return auth;
   if(p.endsWith('/api-scope'))return scope;
   if(p.endsWith('/finance-date'))return require('../miniprogram/utils/finance-date');
   if(p.endsWith('/franchise/crm'))return crm;
   if(p.endsWith('/page-navigation'))return {open:url=>urls.push(url)};
   throw Error('Unexpected module '+p);
  }
 });
 const page={...definition,data:JSON.parse(JSON.stringify(definition.data)),setData(p){Object.assign(this.data,p);}};
 page.onLoad(name==='record'?options:{role:'business',metric:'budgets',period:'2026-11'});
 return {page,state,calls,pending,urls,toasts};
}
function budgetDetail(){
 const itemizedBudget={model:'ITEMIZED_V1',completeness:'COMPLETE',totalCents:1234567,pricedSubtotalCents:1234567,regionName:'武汉',inputSummary:{footprintArea:'120',roofArea:'130'},
  items:[{publicName:'主体工程',category:'BODY',amountCents:1234567,lines:[{optionLabel:'原版配置',status:'PRICED',quantity:'120',unit:'㎡',amountCents:1234567,unitPriceCents:987654}]}]};
 const original={id:'9223372036854775806',customerId:'101',kind:'BUDGET',version:1,title:'原版参考预算',width:'10',depth:'12',floors:2,area:'240',totalCents:1234567,payload:JSON.stringify({itemizedBudget}),createTime:[2026,10,1]};
 return {customer:{id:'101',name:'张先生',salesStage:'QUOTED'},schemes:[original,{...original,id:'9223372036854775807',version:2,title:'新版预算',totalCents:9999999}]};
}
const budgetRow=(id='9223372036854775806')=>({key:'budget:'+id,source_type:'budget',source_id:id,customer_id:'101',title:'张先生'});
const viewNodes=(name,data)=>require('./role-page-runtime.cjs').render(require('path').resolve('miniprogram/pages/home-metrics/'+name+'.wxml'),data);
const viewText=nodes=>nodes.map(n=>(n.text||'')+viewText(n.children)).join('');
const viewControls=nodes=>nodes.flatMap(n=>[...(n.attrs?.bindtap?[n.attrs.bindtap]:[]),...viewControls(n.children)]);

test('budget source carries its exact opaque ID and version through modal and read-only original page',async()=>{
 const index=budgetFixture('index');index.page.data.rows=[budgetRow()];
 const opened=index.page.open({currentTarget:{dataset:{key:budgetRow().key}}});
 assert.equal(index.pending[0].url,'/franchise/customers/101');index.pending[0].resolve(budgetDetail());await opened;
 assert.equal(index.page.data.selected.budget.version,1);assert.equal(index.page.data.selected.writable,false);
 index.page.openBudget();
 assert.equal(index.urls[0],'/pages/home-metrics/record?type=budget&customerId=101&id=9223372036854775806&version=1');
 const options=Object.fromEntries(new URL(index.urls[0],'https://fixture.invalid').searchParams),record=budgetFixture('record',options);
 const reading=record.page.onShow();record.pending[0].resolve(budgetDetail());await reading;
 assert.equal(record.page.data.budget.id,'9223372036854775806');assert.equal(record.page.data.budget.version,1);
 assert.equal(record.page.data.budget.total,'¥12,345.67');assert.equal(record.page.data.budget.groups[0].lines[0].amount,'¥12,345.67');
 assert(!JSON.stringify(record.page.data).includes('unitPriceCents'));
 assert.deepEqual(record.calls.map(c=>[c.method,c.url]),[['GET','/franchise/customers/101']]);
 const nodes=viewNodes('record',record.page.data),text=viewText(nodes);
 assert.match(text,/原版参考预算/);assert.match(text,/归档版本 V1/);assert.match(text,/不代表第六步「别墅报价」已完成/);
 assert(!text.includes('新版预算'));assert.equal(viewControls(nodes).length,0);
});

for(const payload of ['{}','not-json',JSON.stringify({otherBudget:{totalCents:9999999}})])test('legacy budget shows only stored original fields without another budget fallback: '+payload,async()=>{
 const f=budgetFixture(),detail=budgetDetail();detail.schemes[0].payload=payload;
 const reading=f.page.onShow();f.pending[0].resolve(detail);await reading;
 assert.equal(f.page.data.budget.total,'¥12,345.67');assert.equal(f.page.data.budget.groups.length,0);
 const text=viewText(viewNodes('record',f.page.data));assert.match(text,/10m/);assert.match(text,/240㎡/);assert.match(text,/未保存可读取的分项明细/);assert.match(text,/未记录/);
 assert.equal(f.calls.length,1);
});

test('incomplete archived budget retains frozen priced subtotal and missing quantities instead of zero or recomputation',async()=>{
 const f=budgetFixture(),detail=budgetDetail(),original=detail.schemes[0];original.totalCents=null;
 original.payload=JSON.stringify({itemizedBudget:{model:'ITEMIZED_V1',completeness:'INCOMPLETE',totalCents:null,pricedSubtotalCents:456700,missingItems:['外墙石材'],items:[
  {publicName:'外装工程',category:'EXTERIOR',amountCents:null,pricedSubtotalCents:456700,lines:[{optionLabel:'原墙面',status:'PRICED',amountCents:456700},{optionLabel:'石材',status:'MISSING_QUANTITY',amountCents:null}]}]}});
 const reading=f.page.onShow();f.pending[0].resolve(detail);await reading;
 assert.equal(f.page.data.budget.total,'未记录');assert.equal(f.page.data.budget.subtotal,'¥4,567');assert.equal(f.page.data.budget.groups[0].subtotal,'¥4,567');
 assert.equal(f.page.data.budget.groups[0].lines[1].amount,'未记录');
 const text=viewText(viewNodes('record',f.page.data));assert.match(text,/归档已计价部分/);assert.match(text,/待补量/);assert.match(text,/外墙石材/);assert(!text.includes('¥0'));
});

for(const wrong of ['source','version','customer','scheme-customer','kind'])test('budget original rejects mismatched '+wrong+' without opening latest or creating a quote',async()=>{
 const f=budgetFixture(),detail=budgetDetail();
 if(wrong==='source')f.page.options.id='999';
 if(wrong==='version')f.page.options.version='2';
 if(wrong==='customer')detail.customer.id='202';
 if(wrong==='scheme-customer')detail.schemes[0].customerId='202';
 if(wrong==='kind')detail.schemes[0].kind='PLAN';
 const reading=f.page.onShow();f.pending[0].resolve(detail);await reading;
 assert.equal(f.page.data.budget,null);assert.match(f.page.data.error,/不匹配/);assert.equal(f.calls.length,1);assert.equal(f.urls.length,0);
});

for(const version of [undefined,'','0','1.5'])test('budget original requires an explicit valid version: '+version,async()=>{
 const f=budgetFixture();f.page.options.version=version;await f.page.onShow();
 assert.equal(f.calls.length,0);assert.equal(f.page.data.budget,null);assert.match(f.page.data.error,/无效/);
});

test('customer read rejection leaves no budget content or alternate design-provider request',async()=>{
 const f=budgetFixture(),reading=f.page.onShow();f.pending[0].reject({code:1110000000,msg:'客户不存在或无权访问'});await reading;
 assert.equal(f.page.data.budget,null);assert.match(viewText(viewNodes('record',f.page.data)),/无权访问/);assert.equal(f.calls.length,1);
});

for(const condition of ['reference-only','formal-only','both','signed'])test('budget origin stays exact for '+condition,async()=>{
 const f=budgetFixture(),detail=budgetDetail();
 if(condition==='reference-only')detail.customer.salesStage='REGISTERED';
 if(condition==='formal-only')detail.schemes=[];
 if(condition==='signed')detail.customer.salesStage='SIGNED';
 const reading=f.page.onShow();f.pending[0].resolve(detail);await reading;
 if(condition==='formal-only'){assert.equal(f.page.data.budget,null);assert.match(f.page.data.error,/不匹配/);}
 else{assert.equal(f.page.data.budget.id,'9223372036854775806');assert.equal(f.page.data.budget.total,'¥12,345.67');}
 assert.deepEqual(f.calls.map(c=>c.method),['GET']);assert.equal(f.urls.length,0);
});

test('only latest unconfirmed budget offers reference confirmation; original read remains available to boss and history',async()=>{
 for(const condition of ['latest','history','confirmed','boss']){
  const f=budgetFixture('index'),detail=budgetDetail(),row=budgetRow(condition==='history'?'9223372036854775806':'9223372036854775807');f.page.data.rows=[row];
  if(condition==='confirmed')detail.schemes[1].confirmedAt=[2026,11,1];
  if(condition==='boss')f.state.post.roleCode='boss';
  const reading=f.page.open({currentTarget:{dataset:{key:row.key}}});f.pending[0].resolve(detail);await reading;
  const nodes=viewNodes('index',f.page.data),controls=viewControls(nodes);
  assert(controls.includes('openBudget'));assert.equal(controls.includes('confirmBudget'),condition==='latest');
  if(condition!=='latest'){f.page.data.note='伪造确认';await f.page.confirmBudget();assert(!f.calls.some(c=>c.method==='POST'));}
  else{
   f.page.note({detail:{value:'  11月27日电话确认本版参考范围  '}});await f.page.confirmBudget();
   const writes=f.calls.filter(c=>c.method==='POST');assert.equal(writes.length,1);assert.equal(writes[0].url,'/franchise/customers/101/budget-confirmation');
   assert.deepEqual(JSON.parse(JSON.stringify(writes[0].body)),{schemeId:row.source_id,note:'11月27日电话确认本版参考范围'});
   assert(writes[0].headers['Idempotency-Key']);assert(!f.calls.some(c=>/quotes|workflow/.test(c.url)));
  }
 }
});

for(const surface of ['index','record'])for(const change of ['hide','post','company','session'])test(surface+' ignores late budget reads after '+change,async()=>{
 const f=budgetFixture(surface);f.page.data.rows=[budgetRow()];
 const reading=surface==='record'?f.page.onShow():f.page.open({currentTarget:{dataset:{key:budgetRow().key}}});
 if(change==='hide')f.page.onHide();
 if(change==='post')f.state.post.postId='p2';
 if(change==='company')f.state.post.orgId='o2';
 if(change==='session')f.state.session++;
 f.pending[0].resolve(budgetDetail());await reading;
 assert.equal(surface==='record'?f.page.data.budget:f.page.data.selected,null);assert.equal(f.urls.length,0);
});

test('financial original still uses the existing scoped original-record endpoint',async()=>{
 const f=budgetFixture('record',{role:'business',period:'2026-11',metric:'customerNet',type:'receipt',id:'999'}),reading=f.page.onShow();
 assert.equal(f.pending[0].url,'/franchise/home/metrics/customerNet/record');
 f.pending[0].resolve({id:'999',title:'合同收款',status:'VERIFIED',original_cents:123400,amount:120000,date:'2026-11-01'});await reading;
 assert.equal(f.page.data.budget,null);assert.equal(f.page.data.row.original,'1,234');assert.match(viewText(viewNodes('record',f.page.data)),/合同收款/);
});
