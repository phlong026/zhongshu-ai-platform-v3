const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');

function loadPage(relativePath, modules, wxOverrides = {}) {
  let definition;
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const context = {
    Page(page) { definition = page; },
    wx: {
      showToast() {}, redirectTo() {}, navigateTo() {}, switchTab() {},
      showModal() {}, setClipboardData() {}, reLaunch() {}, ...wxOverrides
    },
    require(id) {
      if(id==='./material-library')return {data:{},handlers:{}};
      if(id==='../../utils/creation-keyboard')return require('../miniprogram/utils/creation-keyboard');
      if(id==='../../utils/creation-text-resize')return require('../miniprogram/utils/creation-text-resize');
      if(id==='./content-task-result')return require('../miniprogram/pages/studio/content-task-result');
      if(id==='./task-display')return require('../miniprogram/pages/studio/task-display');
      if (Object.prototype.hasOwnProperty.call(modules, id)) return modules[id];
      throw new Error('Unexpected require: ' + id);
    },
    console, Date, Math, Promise, setTimeout, clearTimeout
  };
  vm.runInNewContext(source, context, { filename: relativePath });
  assert.ok(definition, relativePath + ' must register a Page');
  return definition;
}

function instantiate(definition, data = {}) {
  const page = Object.assign({}, definition);
  page.data = Object.assign({}, structuredClone(definition.data), data);
  page.setData = function setData(patch) { Object.assign(this.data, patch); };
  return page;
}

function flush() {
  return new Promise(resolve => setImmediate(resolve));
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test('LIVE-03 growth summary aggregates backend status maps and accepts per-publication id', async () => {
  const growthDefinition = loadPage('miniprogram/pages/growth/index.js', {
    '../../utils/organization': { has() { return true; } },
    '../../utils/growth': {},
    '../../utils/platform-content': { degradeMessage(error) { return error.message; } },
    '../../api/franchise/content': { growth: { summary: async () => ({
      publications: { publishing: 2, published: 3, failed: 1 },
      leads: { CAPTURED: 4, LINKED: 2 },
      converted: 2,
      perPublication: [{ id: 91, title: '庭院实拍', leads: 4, converted: 2 }]
    }) } },
    '../../api/franchise/crm': {},
    '../../api/http': { token() { return 'fixture-token'; }, requestGuard: () => () => true },
    '../../utils/view-container': { makeFormHandlers() { return {}; } }
  });
  const page = instantiate(growthDefinition, { view: 'home', published: [], publishedNames: [] });

  page.syncGrowth();
  await flush();

  assert.deepEqual(plain(page.data.growthStats), { publications: 6, leads: 6, converted: 2 });
  assert.equal(page.data.published[0].publicationId, '91');
  assert.equal(page.data.publishedNames[1], '庭院实拍');
});

function studioFixture(overrides = {}, wxOverrides = {}) {
  let worksResult = Promise.resolve([]);
  let recordsResult = Promise.resolve([]);
  let alive = true;
  let scope = 'post-42';
  const api = {
    drafts: { works() { return worksResult; } },
    publications: { page() { return recordsResult; } },
    tasks: { page: async () => [] }
  };
  const studio = {
    title: '', accounts: [], guard() { return true; }, allowed() { return true; },
    scope() { return scope; }, read() { return { title: '', original: '', script: '', rewriteHistory: [], publishDrafts: {} }; },
    imageFor(item) { return item.kind === 'ai' ? '/ai.jpg' : '/digital.jpg'; },
    kindLabel(kind) { return kind === 'ai' ? 'AI视频' : '数字人口播'; },
    formatTime(value) { return value ? '格式化:' + value : '时间未记录'; }
  };
  const definition = loadPage('miniprogram/pages/studio/index.js', {
    '../../utils/studio': studio,
    '../../utils/creation': { unavailable() {}, assets: [] },
    '../../utils/platform-content': {
      api,
      rowsOf(rows) { return Array.isArray(rows) ? rows : (rows && (rows.items || rows.list)) || []; },
      taskView() { return 'completed'; }, progressOf() { return 100; }, degradeMessage(error) { return error.message || '加载失败'; }
    },
    '../../api/http': { token() { return 'fixture-token'; }, requestGuard() { return () => alive; } }
  }, wxOverrides);
  const page = instantiate(definition, Object.assign({ view: 'works' }, overrides));
  page.actorScope = scope;
  return {
    page,
    setWorks(value) { worksResult = value; },
    setRecords(value) { recordsResult = value; },
    expire() { alive = false; },
    switchScope(value) { scope = value; }
  };
}

const publicationId = '90071992547409931';
function consultationVisible(detail) {
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  const button = wxml.match(/<button\b[^>]*class="[^"]*\bconsultation-entry\b[^"]*"[^>]*>/);
  assert.ok(button, 'publication detail has a consultation entry');
  const condition = button[0].match(/wx:if="\{\{([^}]+)\}\}"/)[1];
  return !!vm.runInNewContext(condition, { detail });
}

async function openPublicationConsultation(status = 'published') {
  const routes = [];
  const fixture = studioFixture({ view: 'detail', recordId: publicationId }, {
    navigateTo({ url }) { routes.push(url); }
  });
  fixture.setRecords(Promise.resolve([{
    id: publicationId, workId: '90071992547409930', authorUserId: '42',
    title: '庭院实拍', publishText: '发布正文', accountId: '真实门店账号', platform: 'douyin',
    status, publishedUrl: status === 'published' ? 'https://example.test/published/31' : ''
  }]));
  fixture.page.refreshData();
  await flush();
  fixture.page.registerConsultation();
  return { ...fixture, routes };
}

function growthCaptureFixture(entry, { result = 'CREATED', available = true, readable = true } = {}) {
  const routes = [], captures = [], reads = [];
  const definition = loadPage('miniprogram/pages/growth/index.js', {
    '../../utils/organization': { has(role, capability) { return role === 'sales' && capability === 'sales'; } },
    '../../utils/growth': { load: () => ({ contents: [], leads: [] }), existingCustomer: () => null },
    '../../utils/platform-content': { degradeMessage: error => error.message || '请求失败' },
    '../../api/franchise/content': { growth: { summary: async () => ({ publications: { published: 1 }, leads: {}, converted: 0,
      perPublication: available ? [{ id: publicationId, title: '庭院实拍', leads: 0, converted: 0 }] : [] }) } },
    '../../api/franchise/crm': {
      leads: { capture: async (body, key) => { captures.push({ body: plain(body), key }); return { result, customerId: '90071992547409932' }; } },
      customers: { get: async id => { reads.push(id); if (!readable) throw { code: 403 }; return { id }; } }
    },
    '../../api/auth': { currentApiPost: () => ({ postId: 'post-42', orgType: 'store', roleCode: 'sales' }) },
    '../../api/http': { token: () => 'fixture-token', sessionGeneration: () => 1, requestGuard: () => () => true },
    '../../utils/view-container': { makeFormHandlers: () => ({}) }
  }, { navigateTo({ url }) { routes.push(url); } });
  const page = instantiate(definition);
  page.onLoad(Object.fromEntries(new URL(entry, 'https://mini.test').searchParams));
  page.onShow();
  return { page, routes, captures, reads };
}

test('published detail exposes consultation using its real publication id instead of work or content id', async () => {
  const fixture = await openPublicationConsultation();
  assert.equal(consultationVisible(fixture.page.data.detail), true);
  assert.equal(fixture.page.data.detail.contentId, undefined);
  assert.deepEqual(fixture.routes, ['/pages/growth/index?view=capture&publicationId=' + publicationId]);
});

test('unpublished detail cannot display or invoke the consultation action', async () => {
  for (const status of ['publishing', 'scheduled', 'verify', 'failed']) {
    const fixture = await openPublicationConsultation(status);
    assert.equal(consultationVisible(fixture.page.data.detail), false);
    assert.deepEqual(fixture.routes, []);
  }
});

for (const result of ['CREATED', 'LINKED']) {
  test('publication entry preserves its source when customer capture returns ' + result, async () => {
    const publication = await openPublicationConsultation();
    assert.equal(publication.routes.length, 1);
    const fixture = growthCaptureFixture(publication.routes[0], { result });
    await flush();
    assert.equal(fixture.page.data.form.contentId, publicationId);
    assert.equal(fixture.page.data.publishedIndex, 1);
    Object.assign(fixture.page.data.form, { name: '王先生', phone: '13800138000', need: '两层自住' });
    fixture.page.perform();
    await flush(); await flush();
    assert.equal(fixture.captures.length, 1);
    assert.equal(fixture.captures[0].body.source, '内容发布:' + publicationId);
    assert.ok(fixture.captures[0].key);
    assert.deepEqual(fixture.reads, result === 'LINKED' ? ['90071992547409932'] : []);
    assert.deepEqual(fixture.routes, ['/pages/workflow/index?view=customer&id=90071992547409932']);
  });
}

test('publication capture blocks an unavailable source and never bypasses linked customer read permission', async () => {
  const publication = await openPublicationConsultation();
  assert.equal(publication.routes.length, 1);
  const unavailable = growthCaptureFixture(publication.routes[0], { available: false });
  await flush();
  Object.assign(unavailable.page.data.form, { name: '王先生', phone: '13800138000', need: '两层自住' });
  unavailable.page.perform(); await flush();
  assert.equal(unavailable.captures.length, 0);
  assert.match(unavailable.page.data.error, /核对发布作品/);
  const denied = growthCaptureFixture(publication.routes[0], { result: 'LINKED', readable: false });
  await flush();
  Object.assign(denied.page.data.form, { name: '王先生', phone: '13800138000', need: '两层自住' });
  denied.page.perform(); await flush(); await flush();
  assert.equal(denied.captures[0].body.source, '内容发布:' + publicationId);
  assert.deepEqual(denied.routes, []);
  assert.match(denied.page.data.error, /由同事负责/);
});

test('LIVE-04 works view requests drafts and distinguishes loading, empty and error states', async () => {
  const fixture = studioFixture();
  fixture.setWorks(Promise.resolve([{ id: 8, taskId: 7, kind: 'ai_video', title: '院落漫游', status: 'saved' }]));

  fixture.page.refreshData();
  assert.equal(fixture.page.data.worksLoading, true);
  await flush();
  assert.equal(fixture.page.data.worksLoading, false);
  assert.equal(fixture.page.data.worksError, '');
  assert.deepEqual(plain(fixture.page.data.works.map(item => ({ id: item.id, taskId: item.taskId, title: item.title, kind: item.kind }))), [
    { id: '8', taskId: '7', title: '院落漫游', kind: 'ai' }
  ]);

  fixture.setWorks(Promise.reject(new Error('作品接口超时')));
  fixture.page.refreshData();
  await flush();
  assert.equal(fixture.page.data.worksLoading, false);
  assert.equal(fixture.page.data.worksError, '作品接口超时');
  assert.deepEqual(plain(fixture.page.data.works), []);
});

test('LIVE-04 records view maps publication statuses and ignores stale session responses', async () => {
  let resolveRecords;
  const fixture = studioFixture({ view: 'records', recordFilter: 1 });
  fixture.setRecords(new Promise(resolve => { resolveRecords = resolve; }));

  fixture.page.refreshData();
  assert.equal(fixture.page.data.recordsLoading, true);
  resolveRecords([
    { id: 11, workId: 8, title: '待登记内容', status: 'publishing', platform: 'douyin', accountId: 'douyin-adviser', createTime: '2026-11-14T08:00:00Z' },
    { id: 12, workId: 9, title: '已发布内容', status: 'published', platform: 'channels', publishedAt: '2026-11-14T09:00:00Z' }
  ]);
  await flush();
  assert.equal(fixture.page.data.recordsLoading, false);
  assert.deepEqual(plain(fixture.page.data.records.map(item => item.id)), ['11']);
  assert.equal(fixture.page.data.records[0].label, '待登记链接');

  let resolveStale;
  fixture.setRecords(new Promise(resolve => { resolveStale = resolve; }));
  fixture.page.refreshData();
  fixture.expire();
  fixture.switchScope('post-99');
  const snapshot = plain(fixture.page.data);
  resolveStale([{ id: 99, title: '旧岗位数据', status: 'failed' }]);
  await flush();
  assert.deepEqual(plain(fixture.page.data), snapshot);
});

test('LIVE-04 templates expose separate loading, failure and true empty states', () => {
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  for (const state of ['worksLoading', 'worksError', 'recordsLoading', 'recordsError']) {
    assert.match(wxml, new RegExp(state), 'template must render ' + state);
  }
  assert.match(wxml, /wx:elif="{{works\[0\]}}">[\s\S]*?wx:for="{{works}}"[\s\S]*?<\/block>\s*<view wx:else class="empty">暂无已保存作品/);
  assert.match(wxml, /!recordsLoading\s*&&\s*!recordsError\s*&&\s*!records\.length/);
});
