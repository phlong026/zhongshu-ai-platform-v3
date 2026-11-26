const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const CRM = require('../miniprogram/utils/platform-crm');
const root = path.join(__dirname, '..');
const flush = async () => { for (let i = 0; i < 8; i++) await new Promise(r => setImmediate(r)); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

let creationHarnessId = 0;
function libraryHarness() {
  let definition, generation = 1;
  const calls = [], replies = [];
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/design-migration/pages/library/index.js'), 'utf8'), {
    wx: { showToast() {} },
    require(id) {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (id.endsWith('/navigation') || id.endsWith('/assets')) return {};
      if (id.endsWith('/design-case-images')) return { watch() {} };
      if (id.endsWith('/context')) return { getApp: () => ({}) };
      if (id.endsWith('/access')) return { protectedPage: value => { definition = value; } };
      if (id.endsWith('/format')) return { styleLabel: () => '现代' };
      if (id.endsWith('/case-classifications')) return {
        styles: () => Promise.resolve([{ label: '现代', code: 'MODERN' }]),
        floors: () => [{ label: '一层', count: 1 }, { label: '二层', count: 2 }]
      };
      if (id.endsWith('/request')) return { captureSession: () => generation, isSameSession: expected => expected === generation };
      if (id.endsWith('/api')) return { listCases: params => { calls.push(params); return replies.shift()(); } };
      throw Error('Unexpected dependency ' + id);
    }
  });
  const page = { ...definition, data: structuredClone(definition.data), setData(values) { Object.assign(this.data, values); } };
  return { page, calls, replies, changeSession() { generation++; } };
}

test('catalog failure persists as an error until an explicit first-page retry succeeds', async () => {
  const h = libraryHarness();
  h.replies.push(() => Promise.reject({ msg: '公开案例服务暂不可用' }));
  await h.page.loadPage(true);
  assert.equal(h.page.data.error, '公开案例服务暂不可用');
  assert.equal(h.page.data.loading, false);
  h.replies.push(() => Promise.resolve({ list: [], nextCursor: null }));
  await h.page.retry();
  assert.equal(h.calls[1].cursor, null);
  assert.equal(h.page.data.error, '');
  assert.equal(h.page.data.loaded, true);
});

test('catalog append retry retains previous rows and retries the failed cursor', async () => {
  const h = libraryHarness();
  h.replies.push(() => Promise.resolve({ list: [{ caseId: 'first', title: '首套' }], nextCursor: 'next-1' }));
  await h.page.loadPage(true);
  h.replies.push(() => Promise.reject(Error('追加失败'))); await h.page.loadPage(false);
  assert.equal(h.page.data.cases[0].id, 'first');
  assert.equal(h.page.data.error, '追加失败');
  h.replies.push(() => Promise.resolve({ list: [{ caseId: 'second', title: '次套' }], nextCursor: null }));
  await h.page.retry();
  assert.equal(h.calls[2].cursor, 'next-1');
  assert.deepEqual(Array.from(h.page.data.cases, x => x.id), ['first', 'second']);
  h.replies.push(() => Promise.reject(Error('新筛选失败')));
  h.page.onFilterChange({ currentTarget: { dataset: { key: 'styleCode', value: 'NEW_CHINESE' } } });
  await flush();
  assert.equal(h.page.data.cases.length, 0);
  assert.equal(h.page.data.visibleCases.length, 0);
  assert.equal(h.page.data.error, '新筛选失败');
});

test('catalog failure from an old session cannot overwrite the current page', async () => {
  const h = libraryHarness(); let reject;
  h.replies.push(() => new Promise((resolve, fail) => { reject = fail; }));
  const request = h.page.loadPage(true); h.changeSession();
  reject(Error('旧身份错误')); await request;
  assert.equal(h.page.data.error, '');
});

function creationHarness() {
  const state = { scope: 'creation-test-' + (++creationHarnessId), generation: 1 }, requests = [];
  let definition, clipboard;
  const storage=new Map(),wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,structuredClone(v)),showToast(){},getClipboardData(options){clipboard=options;}};
  const cache={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,'miniprogram/utils/creation-link-draft.js'),'utf8'),{module:cache,require:p=>require('../miniprogram/utils/'+p.slice(2)),wx,Uint8Array,unescape,encodeURIComponent});
  vm.runInNewContext(require('./page-definition-source.cjs')(root,'miniprogram/pages/creation/index.js'), {
    Page(value) { definition = value; }, Date, Math, Promise,
    wx,
    require(id) {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (id === '../../utils/creation') return { assets: {} };
      if (id === '../../utils/studio') return { guard: () => true, scope: () => state.scope,
        scopedKey: key => key, read: () => ({}), recentItems: () => [] };
      if (id === '../../utils/storage-keys') return { creationDraft: 'test-draft' };
      if (id === '../../utils/creation-link-draft') return cache.exports;
      if (id === '../../api/auth') return {currentApiPost:()=>({postId:state.scope,userId:'77',orgId:'33'})};
      if (id === '../../api/http') return { tenantId:()=>1, token: () => 'test-session', sessionGeneration: () => state.generation,
        requestGuard: () => () => true };
      if (id === '../../utils/platform-content') return {
        shareLinkUrl: require('../miniprogram/utils/platform-content').shareLinkUrl,
        api: { links: { async parse(url, key) { requests.push({ url, key }); throw Error('Expected test interruption'); } } },
        degradeMessage: error => error.message
      };
      throw Error('Unexpected dependency ' + id);
    }
  });
  return { state, requests, clipboard: () => clipboard, page() {
    const page = { ...definition, data: structuredClone(definition.data), setData(values) { Object.assign(this.data, values); } };
    page.onLoad({}); page.onShow(); return page;
  } };
}

test('creation Tab recreation preserves typed link and original extraction idempotency key', async () => {
  const h = creationHarness(), first = h.page();
  first.inputLink({ detail: { value: 'https://example.test/tab-draft' } });
  await first.extractOnline(); first.onUnload();
  const second = h.page();
  assert.equal(second.data.link, 'https://example.test/tab-draft');
  await second.extractOnline();
  assert.equal(h.requests[1].key, h.requests[0].key);
});

test('creation temporary input clears on session or identity change', () => {
  const h = creationHarness(), page = h.page();
  page.inputLink({ detail: { value: 'https://example.test/private-one' } });
  h.state.generation++; page.onShow();
  assert.equal(page.data.link, '');
  page.inputLink({ detail: { value: 'https://example.test/private-two' } });
  h.state.scope += '-other'; page.onShow();
  assert.equal(page.data.link, '');
  assert.equal(h.page().data.link, '');
});

test('late clipboard callback cannot restore a link after session change or unload', () => {
  const h = creationHarness(), page = h.page(); page.paste();
  h.state.generation++; page.onShow();
  h.clipboard().success({ data: 'https://example.test/private-clipboard' });
  assert.equal(page.data.link, '');
  page.paste(); page.onUnload();
  h.clipboard().success({ data: 'https://example.test/after-unload' });
  assert.equal(page.data.link, '');
});

test('quote export uses recorded server configuration for its document options', async () => {
  let definition;
  const sheet = require('../miniprogram/pages/workflow/quote-export/sheet');
  const snapshot = { main: [], decor: [], mainTotal: 0, decorTotal: 0, total: 0,
    form: { foundation: '独立基础', structure: '框架结构' } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages/workflow/quote-export/index.js'), 'utf8'), {
    Page: value => { definition = value; },
    require(id) {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (id === './sheet') return sheet;
      if (id === './render' || id === './pdf') return {};
      if (id === '../../../api/http') return { get: async () => snapshot };
      throw new Error('Unexpected module ' + id);
    }
  });
  const page = Object.assign({}, definition, { data: {}, setData(values) { Object.assign(this.data, values); } });
  page.onLoad({ 'quote-id': 'unit-quote' }); await flush();
  assert.equal(page.data.allowed, true);
  assert.equal(page.doc.form.foundation, '独立基础');
  assert.equal(page.doc.form.structure, '框架结构');
});

function load(file, modules, session) {
  let definition, nextTimer = 0;
  const timers = new Map();
  vm.runInNewContext(require('./page-definition-source.cjs')(root,file), {
    Page: value => { definition = value; },
    require(id) {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize"); if (id in modules) return modules[id]; throw Error('Unexpected dependency: ' + id); },
    wx: {}, console, Date, Promise,
    setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); }
  }, { filename: file });
  const page = { ...definition, data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch); } };
  return { page, session, timers, async tick() {
    for (const [id, fn] of [...timers]) { timers.delete(id); fn(); }
    await flush();
  } };
}

function customers(count = 60) {
  const session = { generation: 1, post: { postId: 'sales-1', orgId: 'audit-org', roleCode: 'sales' } };
  const database = Array.from({ length: count }, (_, index) => ({
    id: String(index + 1), name: index === 57 ? '跨页目标' : '客户' + index,
    phone: '138' + String(index).padStart(8, '0'), status: 'REGISTERED',
    province: '湖北省', city: index === 57 ? '宜昌市' : '武汉市',
    nextVisitDate: index === 57 ? CRM.dateToArray(CRM.today()) : null
  }));
  const calls = [];
  const api = { customers: { page: async (pageNo, pageSize, keyword, followFilter) => {
    calls.push({ pageNo, pageSize, keyword, ...(followFilter ? {followFilter} : {}) });
    const rows = database.filter(row => {
      if (keyword && ![row.name, row.phone, row.province, row.city].some(value => value.includes(keyword))) return false;
      const date = CRM.toDate(row.nextVisitDate);
      if (followFilter === 'DUE') return !row.lastFollowAt || !!date && date <= CRM.today();
      if (followFilter === 'APPOINTMENTS') return date > CRM.today();
      if (followFilter === 'VISITED') return row.contactKind === 'VISIT';
      return true;
    });
    return { list: rows.slice((pageNo - 1) * pageSize, pageNo * pageSize), total: rows.length };
  } } };
  const http = { token: () => 'unit-session', sessionGeneration: () => session.generation };
  const loaded = load('miniprogram/pages/customers/index.js', {
    '../../utils/organization': require('../miniprogram/utils/organization'),
    '../../utils/platform-crm': CRM, '../../api/http': http,
    '../../api/auth': { currentApiPost: () => session.post }, '../../api/franchise/crm': api
  }, session);
  return { ...loaded, calls, api, database };
}

test('search reaches a matching customer beyond the original first 50 rows', async () => {
  const c = customers(); c.page.onShow(); await flush();
  assert.equal(c.page.data.customers.length, 50);
  c.page.search({ detail: { value: '跨页目标' } }); await c.tick();
  assert.deepEqual(c.calls.at(-1), { pageNo: 1, pageSize: 50, keyword: '跨页目标' });
  assert.equal(c.page.data.visibleCustomers[0]?.id, '58');
});

test('more cannot skip page one when a new search is pending', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.search({ detail: { value: '跨页目标' } }); c.page.more(); await c.tick();
  const matched = c.calls.filter(call => call.keyword === '跨页目标');
  assert.equal(matched[0]?.pageNo, 1);
  assert.equal(c.page.data.visibleCustomers[0]?.id, '58');
});

test('province search results returned by the service remain visible in the client', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.search({ detail: { value: '湖北' } }); await c.tick();
  assert.equal(c.page.data.visibleCustomers.length, 10);
  assert.equal(c.page.data.tabs.find(tab => tab.key === 'all').count, 60);
});

test('clearing search through another filter reloads the unfiltered first page', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.search({ detail: { value: '跨页目标' } }); await c.tick();
  c.page.selectTab({ currentTarget: { dataset: { tab: 'due' } } }); await c.tick();
  assert.equal(c.calls.at(-1).pageNo, 1);
  assert.equal(c.calls.at(-1).keyword, undefined);
  assert.equal(c.page.data.customers.length, 50);
});

test('the first filtered page includes the 51st customer for sales and read-only bosses', async () => {
  for (const roleCode of ['boss','sales']) {
    const c = customers(); c.session.post.roleCode=roleCode;
    for (const row of c.database) Object.assign(row,{status:'FOLLOWING',lastFollowAt:Date.now(),nextVisitDate:null});
    c.database[50].nextVisitDate=CRM.toDate(Date.now()-86400000);
    c.database[52].nextVisitDate=CRM.toDate(Date.now()+86400000);
    c.database[55].contactKind='VISIT';
    c.page.onLoad({focus:'due'});c.page.onShow();await flush();
    const due=c.page.data.visibleCustomers;
    assert.equal(c.calls.at(-1).followFilter,'DUE');assert.equal(c.calls.at(-1).pageNo,1);
    assert.deepEqual(Array.from(due,r=>r.id),['51']);
    assert.equal(due[0].canFollow,roleCode==='sales');assert.match(due[0].cue,/逾期 1 天/);
    const summary=CRM.homeSummary(c.database,[],c.session.post,CRM.today());
    assert.deepEqual(summary.due.map(r=>r.id),['51']);assert.equal(summary.overdue[0].id,'51');
    for (const [tab,filter,id] of [['visited','VISITED','56'],['appointments','APPOINTMENTS','53']]) {
      await c.page.selectTab({currentTarget:{dataset:{tab}}});
      assert.equal(c.calls.at(-1).followFilter,filter);assert.equal(c.calls.at(-1).pageNo,1);
      assert.deepEqual(Array.from(c.page.data.visibleCustomers,r=>r.id),[id]);
    }
    assert.equal(c.page.data.tabs.find(t=>t.key==='appointments').label,'预约回访');
  }
  const view=fs.readFileSync(root+'/miniprogram/pages/business/index.wxml','utf8');
  assert.match(view,/回访已逾期/);assert.doesNotMatch(view,/超\s*7\s*天/);
});

test('rapid typing sends only the final keyword, and unloading cancels pending search', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.search({ detail: { value: '跨页' } });
  c.page.search({ detail: { value: '跨页目标' } }); await c.tick();
  assert.equal(c.calls.filter(call => call.keyword === '跨页').length, 0);
  assert.equal(c.calls.filter(call => call.keyword === '跨页目标').length, 1);
  c.page.search({ detail: { value: '武汉' } }); c.page.onUnload(); await c.tick();
  assert.equal(c.calls.filter(call => call.keyword === '武汉').length, 0);
});

test('a late response cannot restore the previous keyword while the next search is debouncing', async () => {
  const c = customers(); c.page.onShow(); await flush();
  const old = deferred();
  c.api.customers.page = (pageNo, pageSize, keyword) => keyword === '旧查询' ? old.promise : Promise.resolve({ list: [], total: 0 });
  c.page.search({ detail: { value: '旧查询' } }); await c.tick();
  c.page.search({ detail: { value: '新查询' } });
  old.resolve({ list: [c.database[0]], total: 1 }); await flush();
  assert.notEqual(c.page.data.platformStatus, 'ready');
  await c.tick(); assert.equal(c.page.data.customers.length, 0);
});

test('customer return preserves visible rows and merges overlapping refreshes', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.more();
  const before = JSON.stringify(c.page.data.visibleCustomers), pending = deferred();
  let calls = 0;
  c.api.customers.page = () => { calls++; return pending.promise; };
  c.page.onShow(); c.page.onShow();
  assert.equal(JSON.stringify(c.page.data.visibleCustomers), before);
  assert.equal(c.page.data.platformStatus, 'ready');
  assert.equal(c.page.data.platformRefreshing, true);
  assert.equal(calls, 1);
  pending.resolve({ list: c.database.slice(0, 50), total: 60 }); await flush();
  assert.equal(c.page.data.visibleCustomers.length, 20);
  assert.equal(c.page.data.platformRefreshing, false);
});

test('customer refresh preserves every loaded server page until the replacement is ready', async () => {
  const c = customers(); c.page.onShow(); await flush();
  for (let i = 0; i < 5; i++) { c.page.more(); await flush(); }
  assert.equal(c.page.data.visibleCustomers.length, 60);
  const before = JSON.stringify(c.page.data.visibleCustomers);
  c.page.onShow();
  assert.equal(JSON.stringify(c.page.data.visibleCustomers), before);
  await flush();
  assert.equal(c.page.data.visibleCustomers.length, 60);
  assert.equal(c.page.__pageNo, 2);
  assert.deepEqual(c.calls.slice(-2).map(call => [call.pageNo,call.pageSize]), [[1,100],[1,100]]);
});

test('same-count changes to the first page of a filtered list are reflected when the visible prefix grows', async () => {
  const c=customers(61), initial=c.database.slice(0,60);
  const updated=[c.database[60],...initial.filter(row=>row.id!=='1')];
  let changed=false;const calls=[];
  c.api.customers.page=async(page,size,keyword,filter)=>{
    calls.push([page,size,keyword,filter]);
    const rows=changed?updated:initial;
    return {list:rows.slice((page-1)*size,page*size),total:60};
  };
  c.page.onLoad({focus:'due'});await c.page.onShow();
  assert(c.page.data.customers.some(row=>row.id==='1'));
  changed=true;
  await c.page.loadPlatformList(true);
  assert.deepEqual(calls,[[1,50,undefined,'DUE'],[1,100,undefined,'DUE']]);
  const ids=c.page.data.customers.map(row=>row.id);
  assert(!ids.includes('1'));assert(ids.includes('61'));assert.equal(new Set(ids).size,60);
  assert.equal(c.page.data.platformError,'');
});

async function deepCustomerList(pages) {
  const c=customers(260);await c.page.onShow();
  for(let i=1;i<pages;i++)await c.page.loadPlatformList(true);
  assert.equal(c.page.__pageNo,pages);
  assert(c.calls.every(call=>call.pageSize<=200));
  return c;
}

test('beyond the bounded prefix a duplicate from concurrent page changes is still rejected', async () => {
  const c=await deepCustomerList(5),before=JSON.stringify(c.page.data.customers),calls=[],initial=c.database.slice();
  const moved=[initial[245],...initial.filter(row=>row.id!=='246')];
  let inconsistent=true;
  c.api.customers.page=async(page,size)=>{
    calls.push([page,size]);const rows=inconsistent&&page<5?initial:moved;
    return {list:rows.slice((page-1)*size,page*size),total:260};
  };
  await c.page.onShow();
  assert.match(c.page.data.platformError,/名单正在变化/);
  assert.equal(JSON.stringify(c.page.data.customers),before);
  const {render}=require('./role-page-runtime.cjs');
  const flatten=nodes=>nodes.flatMap(node=>[node,...flatten(node.children||[])]);
  assert(flatten(render(root+'/miniprogram/templates/sales-customers.wxml',c.page.data))
    .some(node=>node.attrs?.bindtap==='retryPlatform'));
  inconsistent=false;await c.page.retryPlatform();
  assert.equal(c.page.data.platformError,'');
  assert.equal(new Set(c.page.data.customers.map(row=>row.id)).size,250);
  assert.equal(c.page.data.customers[0].id,'246');
  assert(calls.every(([,size])=>size===50));
});

test('equal-count swap beyond 200 clears an outdated prefix and retries the same filter safely', async () => {
  const c=customers(251), calls=[]; let changed=false;
  c.api.customers.page=async(page,size,keyword,filter,guard,previous)=>{
    const rows=changed?[c.database[250],...c.database.slice(1,250)]:c.database.slice(0,250);
    const end=page*size,start=end-size;
    const stamp=count=>rows.slice(0,count).map(row=>row.id).join(':');
    calls.push({page,size,keyword,filter,guard,previous});
    if(start&&previous!==stamp(start)) return {changed:true,list:[],total:0};
    return {list:rows.slice(start,end),total:250,prefix:stamp(end),changed:false};
  };
  c.page.onLoad({focus:'due'});
  await c.page.onShow();
  for(let i=0;i<3;i++)await c.page.loadPlatformList(true);
  assert.equal(c.page.data.customers.length,200);
  assert(c.page.data.customers.some(row=>row.id==='1'));
  changed=true;
  await c.page.loadPlatformList(true);
  assert.deepEqual(calls.map(x=>[x.page,x.size,x.guard,x.filter]),[[1,50,true,'DUE'],[1,100,true,'DUE'],[1,150,true,'DUE'],[1,200,true,'DUE'],[5,50,true,'DUE']]);
  assert.equal(c.page.data.visibleCustomers.length,0);
  assert.match(c.page.data.platformError,/名单已变化/);
  await c.page.retryPlatform();
  assert.equal(calls.at(-1).page,1);assert.equal(calls.at(-1).size,50);
  assert.equal(calls.at(-1).filter,'DUE');assert(!calls.at(-1).previous);
  assert(c.page.data.customers.some(row=>row.id==='251'));
  assert(!c.page.data.customers.some(row=>row.id==='1'));
});

test('customer refresh rejects a short result even without changing totals or duplicate IDs', async () => {
  const c=customers();await c.page.onShow();await c.page.loadPlatformList(true);
  c.api.customers.page=async()=>({list:c.database.slice(1),total:60});
  await c.page.onShow();
  assert.match(c.page.data.platformError,/名单正在变化/);
  assert.equal(c.page.data.customers.length,60);
  assert(c.page.data.customers.some(row=>row.id==='1'));
});

test('append beyond the bounded prefix retains the page on total drift and retries at the requested depth', async () => {
  const c=await deepCustomerList(4);
  const before=JSON.stringify(c.page.data.customers),calls=[];
  const next=[{...c.database[0],id:'261',name:'刚登记客户'},...c.database];
  c.api.customers.page=async(pageNo,pageSize)=>{
    calls.push([pageNo,pageSize]);
    return {list:next.slice((pageNo-1)*pageSize,pageNo*pageSize),total:next.length};
  };
  await c.page.loadPlatformList(true);
  assert.deepEqual(calls,[[5,50]]);assert.equal(c.page.__pageNo,4);
  assert.equal(JSON.stringify(c.page.data.customers),before);
  assert.match(c.page.data.platformError,/名单正在变化/);
  await c.page.retryPlatform();
  assert.deepEqual(calls,[[5,50],[1,50],[2,50],[3,50],[4,50],[5,50]]);
  assert.equal(c.page.__pageNo,5);
  assert.equal(c.page.data.customers.length,250);
  assert.equal(new Set(c.page.data.customers.map(row=>row.id)).size,250);
});

test('customer return starts a fresh read after an edit while the pre-hide read is pending', async () => {
  const c=customers();c.page.onShow();await flush();
  const old=deferred(),fresh=deferred();let calls=0;
  c.api.customers.page=()=>++calls===1?old.promise:fresh.promise;
  c.page.onShow();const before=JSON.stringify(c.page.data.visibleCustomers);
  c.page.onHide?.();c.page.onShow();
  assert.equal(calls,2);assert.equal(JSON.stringify(c.page.data.visibleCustomers),before);
  fresh.resolve({list:[{...c.database[0],name:'详情修改后的名字'}],total:1});await flush();
  old.resolve({list:[{...c.database[0],name:'旧名字'}],total:1});await flush();
  assert.equal(c.page.data.visibleCustomers[0].name,'详情修改后的名字');
});

test('customer background failure retains this query but authorization failure clears it', async () => {
  const c = customers(); c.page.onShow(); await flush();
  const before = JSON.stringify(c.page.data.visibleCustomers);
  c.api.customers.page = () => Promise.reject({ message: 'network unavailable' });
  c.page.onShow(); await flush();
  assert.equal(JSON.stringify(c.page.data.visibleCustomers), before);
  assert.equal(c.page.data.platformStatus, 'ready');
  assert.match(c.page.data.platformError, /network unavailable/);
  assert.equal(c.page.data.platformRefreshing, false);
  c.api.customers.page = () => Promise.reject({ code: 403, message: 'permission removed' });
  c.page.onShow(); await flush();
  assert.equal(c.page.data.visibleCustomers.length, 0);
  assert.equal(c.page.data.customers.length, 0);
  assert.equal(c.page.data.platformStatus, 'degraded');
});

test('customer refresh clears same-post data on session change and discards the old response', async () => {
  const c = customers(); c.page.onShow(); await flush();
  const old = deferred(), next = deferred(); let calls = 0;
  c.api.customers.page = () => (++calls === 1 ? old.promise : next.promise);
  c.page.onShow();
  c.session.generation++; c.page.onShow();
  assert.equal(c.page.data.visibleCustomers.length, 0);
  assert.equal(c.page.data.platformStatus, 'loading');
  old.resolve({ list: [c.database[0]], total: 1 }); await flush();
  assert.equal(c.page.data.visibleCustomers.length, 0);
  next.resolve({ list: [c.database[1]], total: 1 }); await flush();
  assert.equal(c.page.data.visibleCustomers[0].id, '2');
});

test('returning from an edited customer refreshes the first page even when an append is pending', async () => {
  const c = customers(); c.page.onShow(); await flush();
  for (let i = 0; i < 4; i++) c.page.more();
  assert.equal(c.page.data.visibleCustomers.length, 50);
  const pending = deferred(), requested = [];
  c.api.customers.page = (pageNo,size) => { requested.push([pageNo,size]); return size === 100 ? pending.promise : Promise.resolve({ list: c.database.slice(0, 50), total: 60 }); };
  c.page.more();
  c.database[0].name = '详情已修改的客户';
  c.page.onShow(); await flush();
  assert.deepEqual(requested, [[1,100],[1,50]]);
  assert.equal(c.page.data.customers.find(row => row.id === '1').name, '详情已修改的客户');
  pending.resolve({ list: c.database.slice(50), total: 60 }); await flush();
  assert.equal(c.page.__pageNo, 1);
  assert.equal(c.page.data.customers.length, 50);
  assert.equal(c.page.data.customers.find(row => row.id === '1').name, '详情已修改的客户');
});

test('customer role changes under the same post do not share requests or retain old action permissions', async () => {
  const c = customers(); c.page.onShow(); await flush();
  assert.equal(c.page.data.visibleCustomers[0].canFollow, true);
  const old = deferred(), next = deferred(); let calls = 0;
  c.api.customers.page = () => (++calls === 1 ? old.promise : next.promise);
  c.page.onShow();
  c.session.post = { ...c.session.post, roleCode: 'boss' }; c.page.onShow();
  assert.equal(calls, 2);
  assert.equal(c.page.data.visibleCustomers.length, 0);
  assert.equal(c.page.data.isBossView, true);
  old.resolve({ list: [c.database[0]], total: 1 }); await flush();
  assert.equal(c.page.data.visibleCustomers.length, 0);
  next.resolve({ list: [c.database[1]], total: 1 }); await flush();
  assert.equal(c.page.data.visibleCustomers[0].canFollow, false);
  assert.equal(c.page.data.canRegister, false);
});

function home() {
  const session = { generation: 1, post: { postId: 'sales-1', orgId: 'audit-org', roleCode: 'sales' } };
  const calls = { customers: 0, commissions: 0, profile: 0 };
  let customerResult = Promise.resolve({ list: [{ id: '1', name: '客户', status: 'REGISTERED' }], total: 1 });
  const http = { token: () => 'unit-session', sessionGeneration: () => session.generation,
    get: async () => { calls.profile++; return { nickname: '业务同事' }; } };
  const loaded = load('miniprogram/pages/business/index.js', {
    '../../utils/dashboard': { createDashboard: (_index, config) => config },
    '../../utils/platform-todos-page': { init() {}, refresh() {} }, '../../api/http': http,
    '../../utils/platform-crm': CRM,
    '../../api/franchise/crm': { customers: { page: () => { calls.customers++; return customerResult; } } },
    '../../api/franchise/commission': { commissions: { page: async () => { calls.commissions++; return { list: [], total: 0 }; } } },
    '../../api/franchise/content': { growth: { summary: async () => ({}) }, drafts: { works: async () => [] } },
    '../../api/auth': { currentApiPost: () => session.post },
    '../../utils/platform-content': { rowsOf: value => Array.isArray(value) ? value : [] }
  }, session);
  return { ...loaded, calls, setCustomers(value) { customerResult = value; } };
}

test('overlapping home refreshes share one request group', async () => {
  const h = home(), pending = deferred(); h.setCustomers(pending.promise);
  h.page.loadPlatformHome(); h.page.loadPlatformHome();
  try { assert.equal(h.calls.customers, 1); assert.equal(h.calls.commissions, 1); assert.equal(h.calls.profile, 1); }
  finally { pending.resolve({ list: [], total: 0 }); await flush(); }
});

test('returning to the same home preserves its visible metrics while refreshing real data', async () => {
  const h = home(); h.page.loadPlatformHome(); await flush();
  assert.equal(h.page.data.customerCount, 1);
  const pending = deferred(); h.setCustomers(pending.promise); h.page.loadPlatformHome();
  try { assert.equal(h.page.data.customerCount, 1); }
  finally { pending.resolve({ list: [], total: 0 }); await flush(); }
  assert.equal(h.page.data.customerCount, 0);
});

test('a new organization clears the previous home data before its request completes', async () => {
  const h = home(); h.page.loadPlatformHome(); await flush();
  const pending = deferred(); h.setCustomers(pending.promise); h.session.generation++;
  h.session.post = { ...h.session.post, postId: 'sales-2', orgId: 'other-org' };
  h.page.loadPlatformHome();
  try { assert.equal(h.page.data.platCustomers.length, 0); assert.equal(h.page.data.recentCustomer, null); }
  finally { pending.resolve({ list: [], total: 0 }); await flush(); }
});
