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

async function setup() {
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
  let businessFailure = false, projectsFailure = false, summaryFailure = false;
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

test('失败不冒充零，业务与工程及绩效接口独立，重点项目有空态', async () => {
  const h = await setup();
  h.failure('business',true); await h.page.refreshHomeModules(); await flush();
  assert.ok(h.page.data.desk.home.business.metrics.every(row => row.value === '—'));
  assert.equal(h.page.data.desk.home.business.available,false);
  assert.equal(h.page.data.desk.home.engineering.available,true);
  h.failure('business',false); h.failure('projects',true);
  await h.page.refreshHomeModules(); await flush();
  assert.equal(h.page.data.desk.home.business.available,true);
  assert.ok(h.page.data.desk.home.engineering.metrics.every(row => row.value === '—'));
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
