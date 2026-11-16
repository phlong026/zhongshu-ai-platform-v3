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
      if (id.endsWith('/navigation') || id.endsWith('/assets')) return {};
      if (id.endsWith('/context')) return { getApp: () => ({}) };
      if (id.endsWith('/access')) return { protectedPage: value => { definition = value; } };
      if (id.endsWith('/format')) return { styleLabel: () => '现代' };
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
  vm.runInNewContext(fs.readFileSync(path.join(root, 'miniprogram/pages/creation/index.js'), 'utf8'), {
    Page(value) { definition = value; }, Date, Math, Promise,
    wx: { getStorageSync() {}, showToast() {}, getClipboardData(options) { clipboard = options; } },
    require(id) {
      if (id === '../../utils/creation') return { assets: {} };
      if (id === '../../utils/studio') return { guard: () => true, scope: () => state.scope,
        scopedKey: key => key, read: () => ({}), recentItems: () => [] };
      if (id === '../../utils/storage-keys') return { creationDraft: 'test-draft' };
      if (id === '../../utils/creation-link-draft') return require('../miniprogram/utils/creation-link-draft');
      if (id === '../../api/http') return { token: () => 'test-session', sessionGeneration: () => state.generation,
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

test('creation temporary link and request key expire on session or identity change', () => {
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
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), {
    Page: value => { definition = value; },
    require(id) { if (id in modules) return modules[id]; throw Error('Unexpected dependency: ' + id); },
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

function customers() {
  const session = { generation: 1, post: { postId: 'sales-1', orgId: 'audit-org', roleCode: 'sales' } };
  const database = Array.from({ length: 60 }, (_, index) => ({
    id: String(index + 1), name: index === 57 ? '跨页目标' : '客户' + index,
    phone: '138' + String(index).padStart(8, '0'), status: 'REGISTERED',
    province: '湖北省', city: index === 57 ? '宜昌市' : '武汉市',
    nextVisitDate: index === 57 ? CRM.dateToArray(CRM.today()) : null
  }));
  const calls = [];
  const api = { customers: { page: async (pageNo, pageSize, keyword) => {
    calls.push({ pageNo, pageSize, keyword });
    const rows = database.filter(row => !keyword || [row.name, row.phone, row.province, row.city].some(value => value.includes(keyword)));
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

test('a partial customer page cannot claim that all follow-ups are completed', async () => {
  const c = customers(); c.page.onShow(); await flush();
  c.page.selectTab({ currentTarget: { dataset: { tab: 'due' } } });
  assert.match(c.page.data.emptyText, /加载|更多/);
  assert.equal(c.page.data.tabs.find(tab => tab.key === 'all').count, 60);
  assert.notEqual(c.page.data.tabs.find(tab => tab.key === 'due').count, 0);
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
