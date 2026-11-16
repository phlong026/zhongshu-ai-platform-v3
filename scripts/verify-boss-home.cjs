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

async function setup() {
  const runtime = await connect();
  const auth = require('../miniprogram/api/auth');
  const post = { postId:'boss-home', orgId:'store-home', orgName:'不应显示的门店名称', orgType:'store', roleCode:'boss' };
  auth.saveLogin({ accessToken:'boss-home-test' }); auth.savePosts([post]); auth.switchPost(post.postId);
  const day = CRM.today();
  const later = CRM.toDate(Date.now() + 86400000), earlier = CRM.toDate(Date.now() - 86400000);
  const customers = Array.from({ length:135 }, (_, i) => ({
    id:String(i + 1), name:'客户' + i, status:i < 2 ? 'REGISTERED' : 'FOLLOWING',
    lastFollowAt:'2026-01-01', nextVisitDate:i === 3 ? later : i === 4 ? earlier : null
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

test('经营账本互斥切换，每排四卡，旧模块和顶部说明已移除', async () => {
  const h = await setup(), page = await h.runtime.currentPage();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-six-item')).length, 4);
  assert.equal(h.nodes().filter(node => hasClass(node,'home-ledger-stat')).length, 0);
  await (await page.$('.home-finance-tab[data-tab="ledger"]')).tap();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-six-item')).length, 0);
  assert.equal(h.nodes().filter(node => hasClass(node,'home-ledger-stat')).length, 4);
  await (await page.$('.home-finance-tab[data-tab="operating"]')).tap();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-six-item')).length, 4);
  assert.equal(h.nodes().filter(node => hasClass(node,'home-metric')).length, 8);
  const visible = render(file,h.page.data).map(text).join('');
  for (const removed of ['本月','当前业务','当前工程','工程与财务统计','经营简报','在浏览器打开','财务实收','不应显示的门店名称']) assert.ok(!visible.includes(removed), removed);
});

test('点击真实指标按钮向下展开、切换、收起，两个模块可独立展开', async () => {
  const h = await setup(), page = await h.runtime.currentPage();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail')).length, 0);
  await (await page.$('.home-metric[data-module="business"][data-key="due"]')).tap();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail')).length, 1);
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail-row')).length, 3);
  await (await page.$('.home-metric[data-module="business"][data-key="appointments"]')).tap();
  assert.equal(h.page.data.expandedBusiness, 'appointments');
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail-row')).length, 1);
  await (await page.$('.home-metric[data-module="engineering"][data-key="在建"]')).tap();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail')).length, 2);
  await (await page.$('.home-collapse[data-module="business"]')).tap();
  assert.equal(h.page.data.expandedBusiness, '');
  assert.equal(h.page.data.expandedEngineering, '在建');
  await (await page.$('.home-metric[data-module="engineering"][data-key="在建"]')).tap();
  assert.equal(h.nodes().filter(node => hasClass(node,'home-detail')).length, 0);
});

test('失败不冒充零，业务与工程及绩效接口独立，空数据可展开', async () => {
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
  h.page.toggleHomeMetric({currentTarget:{dataset:{module:'business',key:'due'}}});
  assert.ok(h.nodes().filter(node => hasClass(node,'home-detail-empty')).some(node => text(node).includes('暂无待跟进客户')));
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
