const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');

function loadPage(relativePath, modules) {
  let definition;
  const source = fs.readFileSync(path.join(root, relativePath), 'utf8');
  const context = {
    Page(page) { definition = page; },
    wx: {
      showToast() {}, redirectTo() {}, navigateTo() {}, switchTab() {},
      showModal() {}, setClipboardData() {}, reLaunch() {}
    },
    require(id) {
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
    '../../api/http': { token() { return 'fixture-token'; } },
    '../../utils/view-container': { makeFormHandlers() { return {}; } }
  });
  const page = instantiate(growthDefinition, { view: 'home', published: [], publishedNames: [] });

  page.syncGrowth();
  await flush();

  assert.deepEqual(plain(page.data.growthStats), { publications: 6, leads: 6, converted: 2 });
  assert.equal(page.data.published[0].publicationId, '91');
  assert.equal(page.data.publishedNames[1], '庭院实拍');
});

function studioFixture(overrides = {}) {
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
  });
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
  assert.match(wxml, /!worksLoading\s*&&\s*!worksError\s*&&\s*!works\.length/);
  assert.match(wxml, /!recordsLoading\s*&&\s*!recordsError\s*&&\s*!records\.length/);
});
