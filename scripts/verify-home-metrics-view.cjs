const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function model(){const module={exports:{}};vm.runInNewContext(fs.readFileSync('miniprogram/utils/home-metrics.js','utf8'),{module,require:p=>p==='../api/http'?{}:{localDate:()=> '2026-11-18'}});return module.exports;}
test('金额以分计算，万元显示不丢原始明细金额，负净额保留负号',()=>{const h=model();assert.equal(h.money(18420000).value,'18.42');assert.equal(h.money(-9380000).value,'-9.38');assert.equal(h.money(12345,false).value,'123.45');assert.equal(h.money(null).value,'0');assert.equal(h.metric('net',null,'finance').value,'—');});
test('退款和待报销按金额展示，工程到期未收按项目数展示',()=>{const h=model(),r={total:2,cents:240000};assert.equal(h.metric('reimbursements',r,'finance').value,'2,400');assert.equal(h.metric('projectDue',r,'engineering').value,'2');});
test('日期支持后端毫秒与LocalDate数组，不出现NaN逾期天数',()=>{const h=model(),r={total:1,cents:0};for(const first_date of [Date.parse('2026-11-15T00:00:00+08:00'),[2026,11,15],'2026-11-15'])assert.equal(h.metric('overdueNodes',{...r,first_date},'engineering').caption,'最长3天');});
test('岗位、月份、指标保留到详情URL；源单路由保留不透明大ID',()=>{const h=model(),id='9223372036854775807';assert.equal(h.detailUrl('business','signed','2026-10'),'/pages/home-metrics/index?role=business&metric=signed&period=2026-10');assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'node',source_id:id,project_id:'12'},'engineering').includes('nodeId='+id));assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'payment',source_id:id},'finance').includes('messageObject='+id));assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'declaration',source_id:id},'finance').includes('messageType=sale_collection_declaration'));});
test('首页换月与换岗的迟到响应不能覆盖当前指标',async()=>{const h=model(),pending=[],http={sessionGeneration:()=>1,token:()=>true},auth={currentApiPost:()=>({postId:'p1'})};let definition;vm.runInNewContext(fs.readFileSync('miniprogram/components/home-overview/index.js','utf8'),{Component:d=>definition=d,require:p=>p.includes('home-metrics')?{...h,load:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))}:p.endsWith('/http')?http:auth});const page={properties:{role:'business'},data:{month:'2026-11'},__attached:true,setData(p){Object.assign(this.data,p);}};Object.assign(page,definition.methods);const first=page.load();page.data.month='2026-10';const second=page.load();pending[1].resolve({metrics:{signed:{total:1,cents:12000000}}});await second;pending[0].resolve({metrics:{signed:{total:99,cents:99000000}}});await first;assert.equal(page.data.board.metrics.signed.value,'12');const third=page.load();auth.currentApiPost=()=>({postId:'p2'});pending[2].resolve({metrics:{signed:{total:1,cents:88000000}}});await third;assert.equal(page.data.board.metrics.signed.value,'—');});

test('材料和待接收入口定位原供货事项与接收办理',()=>{const h=model();assert.equal(require('../miniprogram/pages/home-metrics/source-url')({source_type:'material',source_id:'23',project_id:'12'},'engineering'),'/pages/build/record/index?id=12&view=material&homeSupply=23');assert.ok(require('../miniprogram/pages/home-metrics/source-url')({source_type:'handover',source_id:'45',customer_id:'6'},'engineering').endsWith('view=receive'));});
test('提交委托先选择真实客户，普通客户入口不受影响',()=>{let mod={exports:{}},urls=[];vm.runInNewContext(fs.readFileSync('miniprogram/utils/sales-customers-page.js','utf8'),{module:mod,require:()=>({}),wx:{navigateTo:o=>urls.push(o.url)}});const p=mod.exports();p.setData=function(v){Object.assign(this.data,v);};p.data.customers=[{id:'7'}];p.data.canRegister=true;const e={currentTarget:{dataset:{id:'7'}}};p.onLoad({intent:'commission'});p.open(e);assert.equal(urls.pop(),'/pages/workflow/commission/index?view=submit&customerId=7');p.onLoad({});p.open(e);assert.equal(urls.pop(),'/pages/workflow/index?view=customer&id=7');});

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

for(const change of ['session','user','tenant','organization','post','role','month'])test('overview clears data when '+change+' changes',async()=>{
 const f=overviewFixture(),first=f.page.load();f.pending[0].resolve(overviewResult(123400));await first;
 const old=f.page.load();
 if(change==='session')f.state.session++;
 if(change==='user')f.state.userId='u2';
 if(change==='tenant')f.state.tenantId='t2';
 if(change==='organization')f.state.post={...f.state.post,orgId:'o2'};
 if(change==='post')f.state.post={...f.state.post,postId:'p2'};
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

test('overview status uses one permanent reserved slot instead of inserting a loading row',()=>{
 const wxml=fs.readFileSync('miniprogram/components/home-overview/index.wxml','utf8');
 const css=fs.readFileSync('miniprogram/components/home-overview/index.wxss','utf8');
 assert.match(wxml,/<view class="overview-status"[^>]*>/);
 assert.doesNotMatch(wxml,/<(?:view|text)[^>]*wx:(?:if|elif)="{{(?:error|loading)}}"[^>]*class="(?:state|loading)"/);
 assert.match(css,/\.overview-status\s*\{[^}]*\bheight:\s*56rpx/);
});
