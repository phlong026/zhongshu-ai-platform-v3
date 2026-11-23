const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');
const CRM = require('../miniprogram/utils/platform-crm');
const Engineering = require('../miniprogram/utils/platform-engineering');

const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children)]);
const text = node => (node.text || '') + node.children.map(text).join('');
const hasClass = (node, name) => String(node.attrs?.class || '').split(/\s+/).includes(name);
const flush = async () => { for (let i = 0; i < 20; i++) await new Promise(resolve => setImmediate(resolve)); };
const file = path.resolve(__dirname, '../miniprogram/pages/admin/home/index.wxml');
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

function departmentFixture() {
  const fs=require('node:fs'),vm=require('node:vm'),Home=require('../miniprogram/utils/home-metrics');
  const state={session:1,post:{postId:'boss',orgId:'store',tenantId:'tenant',orgType:'store',roleCode:'boss'}},pending=[],navigation=[],app={globalData:{}};
  let definition,barDefinition,page;
  const request=(kind,area)=>new Promise((resolve,reject)=>pending.push({kind,area,resolve,reject,done:false}));
  const http={token:()=>true,sessionGeneration:()=>state.session,currentUserId:()=>1,tenantId:()=>state.post.tenantId,sessionGuard(){const session=state.session;return()=>session===state.session;},get(url,params){return url.includes('profile')?Promise.resolve({nickname:'老板'}):request(url.includes('/focus')?'focus':/\/team\//.test(url)?'member':'members',params.area);}};
  const tabs=()=>['home','business','engineering','profile'].map((name,i)=>({key:'owner-'+name,index:name==='profile'?4:10+i,url:name==='profile'?'/pages/profile/index':name==='home'?'/pages/admin/home/index':'/pages/admin/department/index?area='+name,native:name==='profile'}));
  const wx={redirectTo:o=>navigation.push(o),switchTab:o=>navigation.push(o),navigateTo:o=>navigation.push(o),showToast(){}};
  const dependencies=id=>{
    if(id.endsWith('/http'))return http;
    if(id.endsWith('/auth'))return {currentApiPost:()=>state.post};
    if(id.endsWith('/home-metrics'))return {...Home,load:()=>request('home',page.data.area)};
    if(id.endsWith('/page-navigation'))return {open:url=>navigation.push({url})};
    if(id.endsWith('/workbench-nav'))return ()=>({tabs:tabs()});
    if(id.endsWith('/workspace-navigation')){const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../miniprogram/utils/workspace-navigation.js'),'utf8'),{module,require:dependencies,wx,getApp:()=>app,getCurrentPages:()=>[page]});return module.exports;}
    throw Error('Unexpected department dependency '+id);
  };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../miniprogram/pages/admin/department/index.js'),'utf8'),{Page:value=>definition=value,require:dependencies,wx});
  page={...definition,data:structuredClone(definition.data),route:'pages/admin/department/index',setData(patch){Object.assign(this.data,patch);}};
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../miniprogram/pages/admin/owner-nav/index.js'),'utf8'),{Component:value=>barDefinition=value,require:dependencies,wx,getApp:()=>app,getCurrentPages:()=>[page]});
  const bar={...barDefinition.methods,data:structuredClone(barDefinition.data),properties:{active:'business'},setData(patch){Object.assign(this.data,patch);}};
  const tap=area=>{bar.properties.active=page.data.area;bar.go({currentTarget:{dataset:{page:area}}});};
  const settle=async()=>{for(const item of pending.filter(x=>!x.done)){item.done=true;item.resolve(item.kind==='home'?{metrics:Object.fromEntries(Object.keys(Home.labels).map(key=>[key,{total:1,cents:123400}]))}:item.kind==='members'?[{id:'m1',name:item.area+'同事',assigned:1,pending:0}]:{rows:[{id:item.area+'-1',title:item.area+'记录'}],total:1,more:false});}await flush();};
  page.onLoad({area:'business'});
  return {page,bar,tap,state,pending,navigation,app,settle};
}

test('owner department return preserves visible content and shares an in-flight refresh',async()=>{
  const h=departmentFixture();h.page.onShow();await h.settle();
  const before=JSON.stringify([h.page.data.large,h.page.data.members,h.page.data.focus]),count=h.pending.length;
  h.page.onShow();h.page.onShow();
  assert.equal(JSON.stringify([h.page.data.large,h.page.data.members,h.page.data.focus]),before);
  assert.equal(h.pending.length-count,3);
  assert.equal(h.page.data.refreshing,true);
  await h.settle();assert.equal(h.page.data.refreshing,false);
});

test('owner business and engineering tabs reuse one page and restore their own loaded view',async()=>{
  const h=departmentFixture();h.page.onShow();await h.settle();
  const before=JSON.stringify(h.page.data.members);
  h.tap('engineering');assert.equal(h.page.data.area,'engineering');assert.equal(h.navigation.length,0);
  await h.settle();assert.equal(h.page.data.members[0].name,'engineering同事');
  h.tap('business');assert.equal(h.page.data.area,'business');assert.equal(h.navigation.length,0);
  assert.equal(JSON.stringify(h.page.data.members),before);
  await h.settle();
});

test('owner department network failure preserves its view but permission changes clear it',async()=>{
  const h=departmentFixture();h.page.onShow();await h.settle();
  h.page.onShow();const load=h.pending.find(x=>!x.done&&x.kind==='home');load.done=true;load.reject({code:500,message:'网络失败'});await flush();
  assert.equal(h.page.data.members.length,1);assert.match(h.page.data.error,/网络失败/);
  await h.settle();h.page.onShow();
  h.state.post={...h.state.post,roleCode:'owner'};h.page.onShow();
  assert.equal(h.page.data.members.length,0);
  await h.settle();assert.equal(h.page.data.members.length,1);
  h.page.onShow();const denied=h.pending.find(x=>!x.done&&x.kind==='home');denied.done=true;denied.reject({code:403,message:'没有权限'});await flush();
  assert.equal(h.page.data.members.length,0);assert.equal(h.page.data.large.length,0);
});

test('owner navigation uses one transition and keeps only the last queued destination',()=>{
  const h=departmentFixture();h.page.switchWorkspaceTab=()=>false;
  h.tap('home');h.tap('engineering');h.tap('profile');
  assert.equal(h.navigation.length,1);
  h.navigation[0].complete();
  assert.equal(h.navigation.length,2);assert.equal(h.navigation[1].url,'/pages/profile/index');
  h.navigation[1].complete();assert.equal(h.app.globalData.workspaceNavigating,false);
});

async function setup(initialFailure={}) {
  const runtime = await connect();
  const auth = require('../miniprogram/api/auth');
  const post = { postId:'boss-home', orgId:'store-home', orgName:'不应显示的门店名称', orgType:'store', roleCode:'boss' };
  auth.saveLogin({ accessToken:'boss-home-test' }); auth.savePosts([post]); auth.switchPost(post.postId);
  const day = CRM.today();
  const later = CRM.toDate(Date.now() + 86400000), earlier = CRM.toDate(Date.now() - 86400000);
  const customers = Array.from({ length:135 }, (_, i) => ({
    id:String(i + 1), name:'客户' + i, status:i < 2 ? 'REGISTERED' : 'FOLLOWING',
    lastFollowAt:i < 2 ? null : '2026-01-01', nextVisitDate:i === 3 ? later : i === 4 ? earlier : null
  }));
  const commissions = Array.from({ length:125 }, (_, i) => ({ id:'m' + i, customerName:'委托客户' + i, createTime:day, totalCents:10000 }));
  commissions.push({ id:'old', createTime:'2025-01-01', totalCents:9999900 });
  const projects = ['IN_PROGRESS','PENDING_START','READY_DELIVER','DELIVERED','ARCHIVED'].map((status, i) => ({ id:'p' + i, name:'工程' + i, status }));
  const calls = [];
  let businessFailure = !!initialFailure.business, projectsFailure = !!initialFailure.projects, summaryFailure = !!initialFailure.summary;
  let intercept = () => false;
  global.wx.request = options => {
    const url = new URL(options.url).pathname.replace('/admin-api', '');
    calls.push({ url, data:options.data, method:options.method });
    if (intercept(url, options)) return;
    let data = [];
    let failed = false;
    if (url === '/platform/profile/mine') data = { nickname:'裴火荣' };
    else if (url === '/platform/todos') data = { items:[], failedDomains:[], actionableCount:0 };
    else if (url === '/franchise/customers' || url === '/franchise/commissions') {
      failed = businessFailure;
      const rows = url === '/franchise/customers' ? customers : commissions;
      const { pageNo=1, pageSize=100 } = options.data || {};
      data = { list:rows.slice((pageNo - 1) * pageSize, pageNo * pageSize), total:rows.length };
    } else if (url === '/franchise/engineering/projects') { data = projects; failed = projectsFailure; }
    else if (url.includes('performance')) {
      failed = summaryFailure;
      data = { departments:{ business:{ 'auto:collectionsAmount':1234500 }, engineering:{ 'auto:completedNodes':700 } }, totals:{}, trend:[], company:{}, members:[] };
    }
    if (url.endsWith('/operating/analytics')) data = {
      trend:[{ period:day.slice(0,7), incomeCents:1234500, paidCents:20000 }],
      totals:{ receivableCents:350000, payableOutstandingCents:60000 }
    };
    setImmediate(() => options.success({ statusCode:200, data:{ code:failed ? 500 : 0, msg:failed ? '测试接口失败' : '', data } }));
  };
  await runtime.callWxMethod('reLaunch', { url:'/pages/admin/home/index' }); await flush();
  const page = getCurrentPages().at(-1);
  return {
    runtime, page, auth, post, customers, commissions, projects, calls,
    nodes:() => flatten(render(file, page.data)),
    failure:(type, value) => { if (type === 'business') businessFailure=value; if (type === 'projects') projectsFailure=value; if (type === 'summary') summaryFailure=value; },
    intercept:fn => { intercept=fn; }
  };
}

test('老板指标使用业务、工程首页的同一统计函数和全量接口数据', async () => {
  const h = await setup();
  const business = h.page.data.desk.home.business;
  const engineering = h.page.data.desk.home.engineering;
  assert.deepEqual(business.metrics, CRM.homeSummary(h.customers, h.commissions, h.post).metrics);
  assert.deepEqual(business.metrics.map(row => row.value), [3, 1, '1.25', 135]);
  assert.deepEqual(engineering.counts, Engineering.homeStats(Engineering.listCards(h.projects)));
  assert.deepEqual(engineering.metrics.map(row => row.value), [1,1,1,5]);
  for (const url of ['/franchise/customers','/franchise/commissions']) {
    assert.deepEqual(h.calls.filter(call => call.url === url).map(call => call.data.pageNo), [1,2]);
  }
  assert.equal(business.panels.find(panel => panel.key === 'all').total, 135);
  assert.equal(business.panels.find(panel => panel.key === 'all').rows.length, 8);
  assert.ok(business.panels.find(panel => panel.key === 'all').hasMore);

  // Render the existing business home with the same backend rows, rather than only comparing helper output.
  const sales = { ...h.post, postId:'sales-home', roleCode:'sales' };
  h.auth.savePosts([sales]); h.auth.switchPost(sales.postId);
  await h.runtime.callWxMethod('switchTab', { url:'/pages/business/index' }); await flush();
  const actualBusiness = getCurrentPages().at(-1).data;
  assert.deepEqual([actualBusiness.todayCount,actualBusiness.appointmentCount,actualBusiness.commission.value,actualBusiness.customerCount], business.metrics.map(row => row.value));
  assert.equal(actualBusiness.commission.unit, business.metrics[2].unit);
  const engineer = { ...h.post, postId:'engineering-home', roleCode:'engineer' };
  h.auth.savePosts([engineer]); h.auth.switchPost(engineer.postId);
  await h.runtime.callWxMethod('reLaunch', { url:'/pages/build/home/index' }); await flush();
  assert.deepEqual(getCurrentPages().at(-1).data.eng.stats, engineering.counts);
});

async function ownerOverviewFixture(h) {
  const mod=path.resolve(__dirname,'../miniprogram/components/home-overview/index');
  let def;const previous=global.Component;
  try {global.Component=d=>{def=d;};delete require.cache[require.resolve(mod)];require(mod);}finally{global.Component=previous;delete require.cache[require.resolve(mod)];}
  const Home=require('../miniprogram/utils/home-metrics');const facts=Object.fromEntries(Object.keys(Home.labels).map(k=>[k,{total:2,cents:350000}]));
  assert.ok(h.nodes().some(n=>n.tag==='home-overview'&&n.attrs.role==='owner'));
  return h.runtime.mountFixture({file:mod+'.wxml',data:{role:'owner',tab:'operating',month:'2026-11',board:Home.view('owner',{metrics:facts})},methods:{switchTab:def.methods.switchTab,metric(e){return def.methods.metric.call({...this,properties:{role:'owner'}},e);}}});
}

test('经营与账本切换对应指标，工程交付和资金关注持续可见',async()=>{
  const h=await setup(), p=await ownerOverviewFixture(h);
  assert.ok(await p.$('[data-key="signed"]'));assert.equal(await p.$('[data-key="net"]'),null);
  await(await p.$('[data-tab="ledger"]')).tap();assert.ok(await p.$('[data-key="net"]'));assert.equal(await p.$('[data-key="signed"]'),null);
  for(const k of ['building','delivered','attention','dueReceipt','soonPayable'])assert.ok(await p.$('[data-key="'+k+'"]'),k);
  await(await p.$('[data-tab="operating"]')).tap();assert.ok(await p.$('[data-key="signed"]'));
});

test('经营指标打开所选月份及岗位的明细',async()=>{
  const h=await setup(),p=await ownerOverviewFixture(h);await(await p.$('[data-key="signed"]')).tap();await flush();
  const detail=getCurrentPages().at(-1);assert.equal(detail.route,'pages/home-metrics/index');assert.equal(detail.role,'owner');assert.equal(detail.data.key,'signed');assert.equal(detail.data.period,'2026-11');
});

test('普通失败保留已核验业务和工程，成功空结果才显示真实空态', async () => {
  const h = await setup();
  h.failure('business',true); await h.page.refreshHomeModules(); await flush();
  assert.deepEqual(h.page.data.desk.home.business.metrics,CRM.homeSummary(h.customers,h.commissions,h.post).metrics);
  assert.equal(h.page.data.desk.home.business.available,true);assert.ok(h.page.ownerBusinessError);
  assert.equal(h.page.data.desk.home.engineering.available,true);
  h.failure('business',false); h.failure('projects',true);
  await h.page.refreshHomeModules(); await flush();
  assert.equal(h.page.data.desk.home.business.available,true);
  assert.deepEqual(h.page.data.desk.home.engineering.counts,Engineering.homeStats(Engineering.listCards(h.projects)));assert.ok(h.page.ownerProjectsError);
  h.failure('projects',false); h.failure('summary',true);
  h.page.refresh(); await flush();
  assert.equal(h.page.data.desk.home.business.available,true);
  assert.equal(h.page.data.desk.home.engineering.available,true);
  h.customers.length=0; h.commissions.length=0; h.projects.length=0;
  await h.page.refreshHomeModules(); await flush();
  assert.ok(h.page.data.desk.home.business.metrics.every(row => Number(row.value) === 0));
  assert.ok(h.page.data.desk.home.engineering.metrics.every(row => row.value === 0));
  const empty=h.nodes().find(n=>hasClass(n,'home-empty'));assert.ok(empty);assert.equal(text(empty), '当前没有工程项目');
});

test('换月只影响经营账本；换岗与迟到响应不会恢复旧模块内容', async () => {
  const h = await setup();
  const originalMetrics = h.page.data.desk.home.business.metrics;
  h.page.month({detail:{value:'2026-10'}}); await flush();
  assert.deepEqual(h.page.data.desk.home.business.metrics, originalMetrics);
  const delayed = deferred();
  h.intercept((url, options) => {
    if (url !== '/franchise/engineering/projects') return false;
    delayed.promise.then(() => options.success({statusCode:200,data:{code:0,data:[{id:'late',name:'旧岗位工程',status:'IN_PROGRESS'}]}}));
    return true;
  });
  const oldRequest = h.page.refreshHomeModules();
  const next = {...h.post,postId:'new-boss',orgId:'new-store'};
  h.auth.savePosts([next]); h.auth.switchPost(next.postId);
  h.customers.length=0; h.commissions.length=0; h.projects.length=0; h.intercept(() => false);
  await h.page.refreshHomeModules(); delayed.resolve(); await oldRequest; await flush();
  assert.ok(h.page.data.desk.home.business.metrics.every(row => Number(row.value) === 0));
  assert.ok(h.page.data.desk.home.engineering.metrics.every(row => row.value === 0));
  assert.equal(h.page.data.expandedBusiness,''); assert.equal(h.page.data.expandedEngineering,'');
});

// Append after the existing definitions in scripts/verify-boss-home.cjs.
// Read-only review: these tests were executed via Module._compile without editing the worktree.
test('warm network errors keep the actual team and focus nodes mounted',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();
 const file=path.resolve(__dirname,'../miniprogram/pages/admin/department/index.wxml');
 assert.ok(flatten(render(file,h.page.data)).some(n=>hasClass(n,'team')));
 h.page.onShow();const failure=h.pending.find(x=>!x.done&&x.kind==='home');failure.done=true;failure.reject({code:500,message:'临时失败'});await flush();
 assert.ok(flatten(render(file,h.page.data)).some(n=>hasClass(n,'team')),'loaded team nodes were removed after a background refresh error');
});
test('hide and return release stale focus pagination loading state',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.data.focusMore=true;h.page.showFocus();
 const expansion=h.page.expandFocus();assert.equal(h.page.data.focusBusy,true);
 h.page.onHide();h.page.onShow();await h.settle();await expansion;
 assert.equal(h.page.data.focusBusy,false,'load-more remains disabled after the invalidated request returns');
});
test('hide and return recover a selected member request',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.showMember({currentTarget:{dataset:{id:'m1'}}});
 assert.equal(h.page.data.memberBusy,true);h.page.onHide();h.page.onShow();await h.settle();await h.settle();
 assert.equal(h.page.data.memberBusy,false,'the retained member sheet remains stuck loading');
});
test('repeated owner-home taps do not redirect to the just reached page again',()=>{
 const h=departmentFixture();h.page.switchWorkspaceTab=()=>false;h.tap('home');h.tap('home');
 assert.equal(h.navigation.length,1);h.page.route='pages/admin/home/index';h.navigation[0].complete();
 assert.equal(h.navigation.length,1,'a queued tap to the current non-native owner page adds a second transition');
});
test('focus pagination rejects a same-session role change',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.data.focusMore=true;
 const pending=h.page.expandFocus();h.state.post={...h.state.post,roleCode:'sales'};
 const response=h.pending.find(x=>!x.done&&x.kind==='focus');response.done=true;response.resolve({rows:[{id:'restricted',title:'旧老板范围'}],total:2,more:false});await pending;
 assert.equal(h.page.data.focus.some(row=>row.id==='restricted'),false,'the pagination response crossed a role boundary');
});


test('owner focus pagination waits for a full refresh instead of racing the first page',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.data.focusMore=true;
 const refresh=h.page.reload(),before=h.pending.length;
 await h.page.expandFocus();assert.equal(h.pending.length,before);
 const nodes=flatten(render(path.resolve(__dirname,'../miniprogram/pages/admin/department/index.wxml'),{...h.page.data,focusOpen:true}));
 assert.equal(nodes.find(n=>n.tag==='button'&&n.attrs.bindtap==='expandFocus').attrs.disabled,true);
 await h.settle();await refresh;assert.equal(h.page.data.refreshing,false);
});

// Append after departmentFixture / flush in scripts/verify-boss-home.cjs.
// Independently reproduced in memory without modifying the F08 worktree.
test('loaded member sheet is refreshed after returning from a source record',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.showMember({currentTarget:{dataset:{id:'m1'}}});await h.settle();
 assert.equal(h.page.data.records.length,1);const count=h.pending.filter(x=>x.kind==='member').length;
 h.page.onHide();h.page.onShow();await h.settle();
 assert.ok(h.pending.filter(x=>x.kind==='member').length>count,'retained selected member rows never revalidate on return');
});
test('interrupted member append retains all loaded pages after resuming',async()=>{
 const h=departmentFixture();h.page.onShow();await h.settle();h.page.showMember({currentTarget:{dataset:{id:'m1'}}});
 let item=h.pending.find(x=>!x.done&&x.kind==='member');item.done=true;item.resolve({rows:[{id:'page-1',title:'第一页'}],more:true});await flush();
 const second=h.page.memberRows(true);item=h.pending.find(x=>!x.done&&x.kind==='member');item.done=true;item.resolve({rows:[{id:'page-2',title:'第二页'}],more:true});await second;
 assert.equal(h.page.data.records.length,2);const third=h.page.memberRows(true);
 h.page.onHide();h.page.onShow();await h.settle();await third;
 item=h.pending.find(x=>!x.done&&x.kind==='member');assert.ok(item);item.done=true;item.resolve({rows:[{id:'page-1',title:'第一页刷新'}],more:true});await flush();
 assert.equal(h.page.data.records.length,2,'resumed member reload shrinks the visible retained list to the first page');
 item=h.pending.find(x=>!x.done&&x.kind==='member');assert.ok(item);item.done=true;item.resolve({rows:[{id:'page-2',title:'第二页刷新'}],more:false});await flush();assert.equal(h.page.data.records.length,2);assert.equal(h.page.data.records[1].title,'第二页刷新');
});

test('first owner reads that fail never invent zero metrics or cached projects',async()=>{
 const h=await setup({business:true,projects:true,summary:true});
 assert.equal(h.page.data.desk.home.business.available,false);assert(h.page.data.desk.home.business.metrics.every(r=>r.value==='—'));
 assert.equal(h.page.data.desk.home.engineering.available,false);assert.equal(h.page.data.desk.home.engineering.sites.length,0);assert.equal(h.page.data.desk.ledger.inTotal,'—');
});
test('same-scope duplicate shows retain all owner cards and share pending domain reads',async()=>{
 const h=await setup(),before=JSON.stringify([h.page.data.desk.home,h.page.platSummary]);const held=[];
 h.intercept((url,o)=>{if(url==='/franchise/engineering/projects'){held.push(o);return true;}return false;});
 const start=h.calls.length;h.page.onShow();h.page.onShow();
 assert.equal(JSON.stringify([h.page.data.desk.home,h.page.platSummary]),before);
 await flush();for(const url of ['/franchise/engineering/projects','/franchise/performance/tasks/summary','/franchise/performance/goals','/franchise/performance/operating/analytics','/platform/profile/mine'])assert.equal(h.calls.slice(start).filter(c=>c.url===url).length,1,url);
 assert.equal(h.nodes().filter(n=>hasClass(n,'home-project-row')).length,3);
 assert(h.nodes().some(n=>n.tag==='#text'&&n.text.includes('更新中')));
 held[0].success({statusCode:200,data:{code:0,data:h.projects}});await flush();assert.equal(h.page.data.homeRefreshing,false);
});
test('an ordinary project refresh failure keeps mounted rows and retries from the existing heading',async()=>{
 const h=await setup();h.failure('projects',true);await h.page.refreshHomeModules();await flush();
 assert.equal(h.nodes().filter(n=>hasClass(n,'home-project-row')).length,3);assert(h.nodes().some(n=>n.tag==='#text'&&n.text.includes('更新失败')));
 h.failure('projects',false);const p=await h.runtime.currentPage();const retry=await p.$('.home-section-heading button');await retry.tap();await flush();
 assert(!h.page.ownerProjectsError);assert.equal(h.page.data.desk.home.engineering.sites.length,3);
});
test('owner summaries retain a failed domain while applying the other domain latest response',async()=>{
 const h=await setup(),oldSummary=structuredClone(h.page.platSummary.summary);
 h.intercept((url,o)=>{if(url.endsWith('/tasks/summary')){setImmediate(()=>o.success({statusCode:200,data:{code:500,msg:'offline'}}));return true;}if(url.endsWith('/operating/analytics')){setImmediate(()=>o.success({statusCode:200,data:{code:0,data:{trend:[{period:h.page.platPeriod,incomeCents:990000,paidCents:0,netCents:990000}],totals:{}}}}));return true;}return false;});
 await h.page.refreshPlatSummary();await flush();assert.deepEqual(h.page.platSummary.summary,oldSummary);assert.equal(h.page.platSummary.operating.trend[0].incomeCents,990000);assert.equal(h.page.platDegraded,true);
 const oldOperating=structuredClone(h.page.platSummary.operating);
 h.intercept((url,o)=>{if(url.endsWith('/operating/analytics')){setImmediate(()=>o.success({statusCode:200,data:{code:500,msg:'offline'}}));return true;}if(url.endsWith('/tasks/summary')){setImmediate(()=>o.success({statusCode:200,data:{code:0,data:{departments:{business:{'auto:collectionsAmount':2220000}}}}}));return true;}return false;});
 await h.page.refreshPlatSummary();await flush();assert.deepEqual(h.page.platSummary.operating,oldOperating);assert.equal(h.page.platSummary.summary.departments.business['auto:collectionsAmount'],2220000);assert.equal(h.page.platOperatingDegraded,true);
});
test('same-post owner permission and organization changes clear every cached module before pending reads return',async()=>{
 for(const change of [{roleCode:'owner'},{orgType:'headquarters'},{permissions:['changed:scope']},{tenantId:'another-tenant'},{userId:'another-user'}]){
  const h=await setup(),old=[];h.intercept((url,o)=>{if(url==='/franchise/engineering/projects'){old.push(o);return true;}return false;});h.page.refreshHomeModules();await flush();
  h.auth.savePosts([{...h.post,...change}]);const held=[];h.intercept((url,o)=>{if(url.startsWith('/franchise/')){held.push(o);return true;}return false;});h.page.onShow();
  assert.equal(h.page.ownerProjects,null,JSON.stringify(change));assert.equal(h.page.ownerCustomers,null);assert.equal(h.page.platSummary,null);assert.equal(h.page.data.desk.home.engineering.sites.length,0);
  old[0].success({statusCode:200,data:{code:0,data:[{id:'old-scope',status:'IN_PROGRESS'}]}});await flush();assert.equal(h.page.ownerProjects,null);
  h.page.onUnload();for(const o of held)o.success({statusCode:200,data:{code:0,data:[]}});await flush();
 }
});
test('hidden owner reads cannot merge with a return or overwrite its new projects, performance or profile',async()=>{
 for(const leave of ['onHide','onUnload']){
  const h=await setup(),held=[];
  h.intercept((url,o)=>{if(['/franchise/engineering/projects','/franchise/performance/tasks/summary','/platform/profile/mine'].includes(url)){held.push({url,o});return true;}return false;});h.page.onShow();await flush();
  const start=h.calls.length;h.page[leave]();h.intercept(()=>false);h.page.onShow();await flush();
  for(const url of ['/franchise/engineering/projects','/franchise/performance/tasks/summary','/platform/profile/mine'])assert.equal(h.calls.slice(start).filter(c=>c.url===url).length,1,url);
  const latest=JSON.stringify(h.page.data.desk);for(const {url,o}of held)o.success({statusCode:200,data:{code:url==='/platform/profile/mine'?0:403,data:{nickname:'旧姓名'}}});await flush();assert.equal(JSON.stringify(h.page.data.desk),latest);
 }
});
test('current owner denial representations clear all retained summaries and project data',async()=>{
 const original=Engineering.api.projects.page;
 try{for(const error of [{code:401},{code:403},{status:401},{status:403},{statusCode:401},{statusCode:403},{code:1103000002},{postInvalid:true}]){
  Engineering.api.projects.page=original;const h=await setup();Engineering.api.projects.page=async()=>{throw error;};await h.page.refreshHomeModules();await flush();
  assert.equal(h.page.ownerProjects,null,JSON.stringify(error));assert.equal(h.page.ownerCustomers,null);assert.equal(h.page.platSummary,null);assert.equal(h.page.data.desk.home.engineering.sites.length,0);
 }}finally{Engineering.api.projects.page=original;}
});
test('month changes clear only the financial snapshot and reject late previous-period responses',async()=>{
 const h=await setup(),projects=h.page.ownerProjects,customers=h.page.ownerCustomers,held=[];
 h.intercept((url,o)=>{if(url.includes('/performance/')){held.push(o);return true;}return false;});h.page.refreshPlatSummary();await flush();
 h.intercept(()=>false);h.page.month({detail:{value:'2026-10'}});assert.equal(h.page.platSummary,null);assert.equal(h.page.ownerProjects,projects);assert.equal(h.page.ownerCustomers,customers);await flush();
 const latest=JSON.stringify(h.page.platSummary);for(const o of held)o.success({statusCode:200,data:{code:0,data:{departments:{private:'previous-month'}}}});await flush();assert.equal(JSON.stringify(h.page.platSummary),latest);
});


test('owner project retry starts a new read even when another home domain is still pending',async()=>{
 const h=await setup(),old=[];
 h.intercept((url,o)=>{if(url==='/franchise/customers'){old.push(o);return true;}if(url==='/franchise/engineering/projects'){setImmediate(()=>o.success({statusCode:200,data:{code:500,msg:'项目网络失败'}}));return true;}return false;});
 h.page.refreshHomeModules();await flush();assert(h.page.ownerProjectsError);const start=h.calls.length;
 h.intercept(()=>false);const p=await h.runtime.currentPage();await(await p.$('.home-section-heading button')).tap();await flush();
 assert.equal(h.calls.slice(start).filter(c=>c.url==='/franchise/engineering/projects').length,1,'explicit retry was swallowed by the other domain pending flight');
 assert.equal(h.page.ownerProjectsError,'');assert.equal(h.page.data.desk.home.engineering.sites.length,3);
 for(const o of old)o.success({statusCode:200,data:{code:0,data:{list:[],total:0}}});await flush();
});
test('owner summary retry starts a fresh failed domain while the parallel summary domain is pending',async()=>{
 const h=await setup(),old=[];
 h.intercept((url,o)=>{if(url.endsWith('/tasks/summary')){old.push(o);return true;}if(url.endsWith('/operating/analytics')){setImmediate(()=>o.success({statusCode:200,data:{code:500,msg:'经营网络失败'}}));return true;}return false;});
 h.page.refreshPlatSummary();await flush();assert.equal(h.page.platOperatingDegraded,true);const start=h.calls.length;
 h.intercept(()=>false);h.page.retryHome();await flush();
 assert.equal(h.calls.slice(start).filter(c=>c.url.endsWith('/operating/analytics')).length,1,'explicit retry must not merge into the failed read batch');
 assert.equal(h.page.platOperatingDegraded,false);
 for(const o of old)o.success({statusCode:200,data:{code:0,data:{departments:{}}}});await flush();
});
test('current owner denial followed by same-scope retry cannot revive any earlier domain owner',async()=>{
 const h=await setup(),old=[];
 h.intercept((url,o)=>{if(['/franchise/engineering/projects','/franchise/performance/tasks/summary','/platform/profile/mine'].includes(url)){old.push({url,o});return true;}if(url.endsWith('/operating/analytics')){setImmediate(()=>o.success({statusCode:200,data:{code:403,msg:'当前权限拒绝'}}));return true;}return false;});
 h.page.onShow();await flush();assert.equal(h.page.ownerProjects,null);assert.equal(h.page.ownerCustomers,null);assert.equal(h.page.platSummary,null);assert.equal(h.page.platProfileName,'');
 const fresh=[];h.intercept((url,o)=>{if(url==='/platform/todos')return false;fresh.push(o);return true;});h.page.retryHome();await flush();
 for(const{url,o}of old)o.success({statusCode:200,data:{code:0,data:url==='/franchise/engineering/projects'?[{id:'old-private',name:'旧权限工程',status:'IN_PROGRESS'}]:url==='/platform/profile/mine'?{nickname:'旧姓名'}:{departments:{business:{private:'old'}}}}});await flush();
 assert.equal(h.page.ownerProjects,null);assert.equal(h.page.platSummary,null);assert.equal(h.page.platProfileName,'');assert.equal(h.page.data.desk.home.engineering.sites.length,0);
 h.page.onUnload();for(const o of fresh)o.success({statusCode:200,data:{code:0,data:[]}});await flush();
});
test('owner archive confirmation forces a new summary batch instead of joining its pre-archive read',async()=>{
 const h=await setup(),old=[],writes=[];
 h.intercept((url,o)=>{if(url.endsWith('/operating/snapshot')){writes.push(o);return true;}if(url.includes('/performance/')){old.push(o);return true;}return false;});
 h.page.refreshPlatSummary();await flush();h.page.archivePlatSnapshot();await flush();assert.equal(writes.length,1);const start=h.calls.length;
 h.intercept(()=>false);writes[0].success({statusCode:200,data:{code:0,data:{}}});await flush();
 for(const url of ['/franchise/performance/tasks/summary','/franchise/performance/goals','/franchise/performance/operating/analytics'])assert.equal(h.calls.slice(start).filter(c=>c.url===url).length,1,url);
 const latest=JSON.stringify(h.page.platSummary);for(const o of old)o.success({statusCode:200,data:{code:0,data:{departments:{private:'pre-archive'}}}});await flush();assert.equal(JSON.stringify(h.page.platSummary),latest);
});
