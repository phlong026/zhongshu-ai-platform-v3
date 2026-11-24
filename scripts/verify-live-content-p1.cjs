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
  let tasksResult = Promise.resolve([]);
  let dismissResult = Promise.resolve();
  let retrySaveResult = Promise.resolve();
  let alive = true;
  let scope = 'post-42';
  let generation = 1, token = 'fixture-token';
  let post = { postId: '42', orgId: '8', orgType: 'store', roleCode: 'sales' };
  const calls = { tasks: 0, works: 0, records: 0, dismiss: 0, retrySave: 0 }, sequences = {};
  const result = value => typeof value === 'function' ? value() : value;
  const api = {
    drafts: { works() { calls.works++; return result(worksResult); } },
    publications: { page() { calls.records++; return result(recordsResult); } },
    tasks: { page() { calls.tasks++; return result(tasksResult); }, dismiss() { calls.dismiss++; return result(dismissResult); }, retrySave() { calls.retrySave++; return result(retrySaveResult); } }
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
      ...require('../miniprogram/utils/platform-content'),
      api,
      rowsOf(rows) { return Array.isArray(rows) ? rows : (rows && (rows.items || rows.list)) || []; },
      degradeMessage(error) { return error.message || '加载失败'; }
    },
    '../../api/auth': { currentApiPost() { return post; } },
    '../../api/http': {
      token() { return token; }, tenantId() { return 1; }, sessionGeneration() { return generation; },
      requestGuard(domain) { const g = generation, n = sequences[domain] = (sequences[domain] || 0) + 1; return () => alive && generation === g && sequences[domain] === n; }
    }
  }, wxOverrides);
  const page = instantiate(definition, Object.assign({ view: 'works' }, overrides));
  page.actorScope = scope;
  page.actorSessionGeneration = generation;
  return {
    page, calls,
    setWorks(value) { worksResult = value; },
    setRecords(value) { recordsResult = value; },
    setTasks(value) { tasksResult = value; },
    setDismiss(value) { dismissResult = value; },
    setRetrySave(value) { retrySaveResult = value; },
    expire() { alive = false; },
    switchScope(value) { scope = value; },
    changePost(patch) { post = { ...post, ...patch }; },
    logout() { token = ''; generation++; }
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
  assert.deepEqual(plain(fixture.page.data.works.map(item => item.id)), ['8']);
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
  assert.match(wxml, /worksLoaded\s*&&\s*!works\.length/);
  assert.match(wxml, /recordsLoaded\s*&&\s*!records\.length/);
});

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const lists = {
  tasks: { kind: 'tasks', setter: 'setTasks', row: id => ({ id, kind: 'digital', status: 'SUCCEEDED', title: '任务' + id }) },
  works: { kind: 'works', setter: 'setWorks', row: id => ({ id, kind: 'digital', title: '作品' + id }) },
  records: { kind: 'records', setter: 'setRecords', row: id => ({ id, status: 'published', title: '发布' + id }) },
  detail: { kind: 'records', setter: 'setRecords', row: id => ({ id, status: 'published', title: '发布' + id }) }
};
const shownIds = (page, view) => view === 'detail'
  ? (page.data.detail ? [page.data.detail.id] : [])
  : plain(page.data[view].map(row => row.id));

for (const [view, list] of Object.entries(lists)) {
  test('F05 ' + view + ': onLoad and first onShow issue one request, including a fast response', async () => {
    for (const fast of [false, true]) {
      const fixture = studioFixture({ view, recordId: '11' });
      const pending = deferred();
      fixture[list.setter](pending.promise);
      fixture.page.onLoad({ view, record: '11' });
      if (fast) { pending.resolve([list.row('11')]); await flush(); }
      fixture.page.onShow();
      assert.equal(fixture.calls[list.kind], 1, 'initial lifecycle must not request twice');
      if (!fast) { pending.resolve([list.row('11')]); await flush(); }
      fixture.page.onHide();
      fixture.page.onShow();
      assert.equal(fixture.calls[list.kind], 2, 'returning to the same page still refreshes');
      await flush();
    }
  });

  test('F05 ' + view + ': concurrent refreshes share a request and retain the loaded frame on failure', async () => {
    const fixture = studioFixture({ view, recordId: '11' }), page = fixture.page;
    fixture[list.setter](Promise.resolve([list.row('11')]));
    page.refreshData(); await flush();
    if (view === 'tasks') Object.assign(page.data.tasks[0], { swiped: true, dismissing: true });
    const before = plain(view === 'detail' ? page.data.detail : page.data[view]);
    const pending = deferred(); fixture[list.setter](pending.promise);
    const first = page.refreshData(), second = page.refreshData();
    assert.equal(fixture.calls[list.kind], 2, 'only one background request is issued');
    assert.equal(first, second, 'refresh callers await the same request');
    assert.equal(page.data[list.kind + 'Loaded'], true);
    assert.equal(page.data[list.kind + 'Loading'], true);
    assert.deepEqual(plain(view === 'detail' ? page.data.detail : page.data[view]), before);
    pending.reject(new Error('刷新超时')); await flush();
    assert.deepEqual(plain(view === 'detail' ? page.data.detail : page.data[view]), before);
    assert.equal(page.data[list.kind + 'Loading'], false);
    assert.equal(page.data[list.kind + 'Loaded'], true);
    assert.ok(page.data[list.kind + 'Error']);
    assert.equal(page.data.invalidMessage, '', 'a transient refresh failure must not replace the loaded page');
    fixture[list.setter](Promise.resolve([list.row('11')]));
    page.refreshData(); await flush();
    assert.equal(page.data[list.kind + 'Error'], '');
    if (view === 'tasks') {
      assert.equal(page.data.tasks[0].swiped, true, 'refresh preserves the open swipe action');
      assert.equal(page.data.tasks[0].dismissing, true, 'refresh preserves a pending deletion');
    }
  });

  test('F05 ' + view + ': first-load errors are distinguishable and permission failures discard displayed data', async () => {
    const fixture = studioFixture({ view, recordId: '11' }), page = fixture.page;
    fixture[list.setter](() => Promise.reject(new Error('首载失败')));
    page.refreshData(); await flush();
    assert.deepEqual(shownIds(page, view), []);
    assert.equal(page.data[list.kind + 'Loaded'], false);
    assert.equal(page.data[list.kind + 'Loading'], false);
    for (const failure of [{ code: 403 }, { code: 1103000002, postInvalid: true }]) {
      fixture[list.setter](Promise.resolve([list.row('11')]));
      page.refreshData(); await flush();
      fixture[list.setter](() => Promise.reject({ ...failure, message: '权限失效' }));
      page.refreshData(); await flush();
      assert.deepEqual(shownIds(page, view), [], 'revoked data must be removed');
      assert.equal(page.data[list.kind + 'Loaded'], false);
    }
  });

  test('F05 ' + view + ': same-post role and organization type changes discard old snapshots and late responses', async () => {
    for (const changed of [{ roleCode: 'boss' }, { orgType: 'company' }]) {
      const fixture = studioFixture({ view, recordId: '11' }), page = fixture.page;
      fixture[list.setter](Promise.resolve([list.row('11')])); page.refreshData(); await flush();
      const old = deferred(); fixture[list.setter](old.promise); page.refreshData();
      fixture.changePost(changed);
      const next = deferred(); fixture[list.setter](next.promise); page.refreshData();
      assert.deepEqual(shownIds(page, view), [], 'old permission scope must not remain visible');
      assert.equal(page.data[list.kind + 'Loaded'], false);
      assert.equal(fixture.calls[list.kind], 3);
      next.resolve([{ ...list.row('11'), title: '新身份数据' }]); await flush();
      const snapshot = plain(page.data);
      old.resolve([{ ...list.row('11'), title: '旧身份数据' }]); await flush();
      assert.deepEqual(plain(page.data), snapshot);
    }
  });
}

test('F05 returning after a permission change clears the prior frame before redirecting', async () => {
  const routes = [], fixture = studioFixture({}, { redirectTo: value => routes.push(value.url) });
  fixture.setWorks(Promise.resolve([lists.works.row('11')]));
  fixture.page.onLoad({ view: 'works' }); fixture.page.onShow(); await flush();
  fixture.changePost({ roleCode: 'boss' }); fixture.page.onShow();
  assert.deepEqual(shownIds(fixture.page, 'works'), []);
  assert.deepEqual(routes, ['/pages/studio/index?view=works']);
});

for (const view of ['records', 'detail']) {
  test('F05 ' + view + ': hydrate covers once after a successful paint and retain previews on refresh failure', async () => {
    const fixture = studioFixture({ view, recordId: '11' }), page = fixture.page;
    let previewReads = 0;
    page.hydratePublicationCovers = () => {
      previewReads++;
      const row = view === 'detail' ? page.data.detail : page.data.records[0];
      assert.equal(row.id, '11', 'cover hydration runs after the matching list frame is painted');
      assert.equal(row.coverAssetId, '301');
      // A redundant read on refresh failure would lose the already displayed preview.
      row.cover = previewReads === 1 ? 'https://cdn.example.com/cover-301.jpg' : '';
    };
    const initial = deferred(); fixture.setRecords(initial.promise);
    const first = page.refreshData(), second = page.refreshData();
    assert.equal(first, second, 'cover hydration retains the original shared list promise');
    initial.resolve([{ id:'11', status:'published', title:'已有封面', coverAssetId:'301' }]);
    await first;
    assert.equal(previewReads, 1, 'coalesced callers do not duplicate preview reads');
    const frame = plain(view === 'detail' ? page.data.detail : page.data.records);
    fixture.setRecords(() => Promise.reject(new Error('刷新超时')));
    await page.refreshData();
    assert.equal(previewReads, 1, 'a failed list refresh never re-reads or clears a valid cover');
    assert.deepEqual(plain(view === 'detail' ? page.data.detail : page.data.records), frame);
    assert.equal(page.data.recordsLoaded, true);assert.equal(page.data.recordsLoading, false);
    assert.equal(page.data.recordsError, '刷新超时');
  });
}

test('F05 a session-expiring 401 clears the visible snapshot even after the HTTP guard expires', async () => {
  const fixture = studioFixture();
  fixture.setWorks(Promise.resolve([lists.works.row('11')])); fixture.page.refreshData(); await flush();
  const pending = deferred(); fixture.setWorks(pending.promise); fixture.page.refreshData();
  fixture.logout(); pending.reject({ code: 401, message: '请重新登录' }); await flush();
  assert.deepEqual(shownIds(fixture.page, 'works'), []);
  assert.equal(fixture.page.data.worksLoaded, false);
  assert.equal(fixture.page.data.worksLoading, false);
});

test('F05 an old permission error cannot erase the newer identity response', async () => {
  const fixture = studioFixture(), old = deferred();
  fixture.setWorks(old.promise); fixture.page.refreshData();
  fixture.changePost({ roleCode: 'boss' });
  fixture.setWorks(Promise.resolve([lists.works.row('22')])); fixture.page.refreshData(); await flush();
  const snapshot = plain(fixture.page.data);
  old.reject({ code: 403, message: '旧权限失效' }); await flush();
  assert.deepEqual(plain(fixture.page.data), snapshot);
});

test('F05 task and publication filters reject late responses and never show a different filter snapshot', async () => {
  for (const view of ['tasks', 'records']) {
    const list = lists[view], filter = view === 'tasks' ? 'taskFilter' : 'recordFilter';
    const fixture = studioFixture({ view }), page = fixture.page;
    fixture[list.setter](Promise.resolve([list.row('11')])); page.refreshData(); await flush();
    const old = deferred(); fixture[list.setter](old.promise); page.refreshData();
    page.setData({ [filter]: 3 });
    const next = deferred(); fixture[list.setter](next.promise); page.refreshData();
    assert.deepEqual(shownIds(page, view), [], 'different filters cannot display the previous snapshot');
    next.resolve([{ ...list.row('22'), status: view === 'tasks' ? 'FAILED' : 'failed' }]); await flush();
    old.resolve([list.row('11')]); await flush();
    assert.deepEqual(shownIds(page, view), ['22']);
  }
});

test('F05 dismissal invalidates and replaces an older pending task refresh', async () => {
  const fixture = studioFixture({ view: 'tasks' }), page = fixture.page;
  fixture.setTasks(Promise.resolve([lists.tasks.row('11'), lists.tasks.row('22')]));
  page.refreshData(); await flush();
  const old = deferred(); fixture.setTasks(old.promise); page.refreshData();
  const next = deferred(); fixture.setTasks(next.promise);
  await page.dismissTask('11');
  assert.equal(fixture.calls.tasks, 3, 'deletion must not reuse its invalidated request');
  assert.deepEqual(shownIds(page, 'tasks'), ['22']);
  old.resolve([lists.tasks.row('11'), lists.tasks.row('22')]); await flush();
  assert.deepEqual(shownIds(page, 'tasks'), ['22']);
  next.resolve([lists.tasks.row('22')]); await flush();
  assert.equal(page.data.tasksLoading, false);
});

test('F05 retry-save completion replaces any list request started before the write', async () => {
  const fixture = studioFixture({ view: 'tasks' }), page = fixture.page;
  fixture.setTasks(Promise.resolve([lists.tasks.row('11')])); page.refreshData(); await flush();
  const old = deferred(); fixture.setTasks(old.promise); page.refreshData();
  const next = deferred(); fixture.setTasks(next.promise);
  const retry = page.retryTaskSave('11'); await flush();
  assert.equal(fixture.calls.tasks, 3, 'the read following a write must not reuse older data');
  next.resolve([{ ...lists.tasks.row('11'), title: '保存已恢复' }]); await retry;
  old.resolve([{ ...lists.tasks.row('11'), title: '保存前' }]); await flush();
  assert.equal(page.data.tasks[0].title, '保存已恢复');
});

test('F05 late task mutations cannot alter rows after a role change', async () => {
  for (const command of ['dismissTask', 'retryTaskSave']) {
    const toasts = [], fixture = studioFixture({ view: 'tasks' }, { showToast: value => toasts.push(value.title) }), page = fixture.page;
    fixture.setTasks(Promise.resolve([lists.tasks.row('11')])); page.refreshData(); await flush();
    const old = deferred(); fixture[command === 'dismissTask' ? 'setDismiss' : 'setRetrySave'](old.promise);
    const pending = page[command]('11');
    fixture.changePost({ roleCode: 'boss' });
    fixture.setTasks(Promise.resolve([{ ...lists.tasks.row('11'), title: '新权限内容' }])); page.refreshData(); await flush();
    const snapshot = plain(page.data), requests = fixture.calls.tasks;
    old.resolve(); await pending; await flush();
    assert.deepEqual(plain(page.data), snapshot, 'old mutation completion must not update the new identity');
    assert.equal(fixture.calls.tasks, requests);
    assert.deepEqual(toasts, []);
  }
});

test('F05 old task mutation completion cannot release the newer identity action lock', async () => {
  for (const [command, setter, counter] of [['dismissTask', 'setDismiss', 'dismiss'], ['retryTaskSave', 'setRetrySave', 'retrySave']]) {
    const fixture = studioFixture({ view: 'tasks' }), page = fixture.page;
    fixture.setTasks(Promise.resolve([lists.tasks.row('11')])); page.refreshData(); await flush();
    const old = deferred(); fixture[setter](old.promise); const first = page[command]('11');
    fixture.changePost({ roleCode: 'boss' }); page.refreshData(); await flush();
    const next = deferred(); fixture[setter](next.promise); const second = page[command]('11');
    assert.equal(fixture.calls[counter], 2, 'the previous identity must not block a new authorized command');
    old.resolve(); await first;
    page[command]('11');
    assert.equal(fixture.calls[counter], 2, 'old finally must not unlock the still pending newer command');
    next.resolve(); await second; await flush();
  }
});

test('F05 detail identity and authoritative removal prevent stale publication actions', async () => {
  const fixture = studioFixture({ view: 'detail', recordId: '11' }), page = fixture.page;
  const old = deferred(); fixture.setRecords(old.promise); page.refreshData();
  page.setData({ recordId: '22' });
  fixture.setRecords(Promise.resolve([lists.records.row('22')])); page.refreshData(); await flush();
  old.resolve([lists.records.row('11')]); await flush();
  assert.deepEqual(shownIds(page, 'detail'), ['22']);
  fixture.setRecords(Promise.resolve([])); page.refreshData(); await flush();
  assert.equal(page.data.detail, null);
  assert.deepEqual(plain(page.data.timeline), []);
  assert.match(page.data.invalidMessage, /不存在|清理/);
});

test('F05 unloaded works and records pages reject delayed responses', async () => {
  for (const view of ['works', 'records']) {
    const list = lists[view], fixture = studioFixture({ view }), pending = deferred();
    fixture[list.setter](pending.promise); fixture.page.refreshData(); fixture.page.onUnload();
    const snapshot = plain(fixture.page.data);
    pending.resolve([list.row('11')]); await flush();
    assert.deepEqual(plain(fixture.page.data), snapshot);
  }
});

test('F05 returning from an editing page starts a fresh read and rejects the pre-hide response', async () => {
  for (const [view, list] of Object.entries(lists)) {
    const fixture = studioFixture({ view, recordId: '11' }), page = fixture.page;
    fixture[list.setter](Promise.resolve([list.row('11')]));
    page.onLoad({ view, record: '11' }); page.onShow(); await flush();
    assert.equal(fixture.calls[list.kind], 1);
    const before = shownIds(page, view), old = deferred();
    fixture[list.setter](old.promise); page.onShow();
    assert.equal(fixture.calls[list.kind], 2);
    page.onHide();
    assert.deepEqual(shownIds(page, view), before, 'hiding must preserve the existing frame');
    // The user updates data on another page while the earlier read is pending.
    const updated = deferred(); fixture[list.setter](updated.promise); page.onShow();
    assert.equal(fixture.calls[list.kind], 3, 'return must read after the edit instead of sharing the pre-hide read');
    updated.resolve([{ ...list.row('11'), title: '修改后的内容' }]); await flush();
    const snapshot = plain(page.data);
    old.resolve([{ ...list.row('11'), title: '修改前的旧内容' }]); await flush();
    assert.deepEqual(plain(page.data), snapshot, 'the released request cannot overwrite the newer read');
  }
});

test('F05 warm list loading and errors do not replace cards or flash a loading block for a known empty result', async () => {
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  for (const [view, label] of [['tasks', '正在加载任务…'], ['works', '作品加载中…'], ['records', '发布记录加载中…']]) {
    const match = wxml.match(new RegExp('<view wx:if="\\{\\{([^}]+)\\}\\}" class="empty">' + label));
    assert.ok(match, view + ' must have an initial loading state');
    const fixture = studioFixture({ view }); fixture.page.refreshData(); await flush();
    const pending = deferred(); fixture[lists[view].setter](pending.promise); fixture.page.refreshData();
    assert.equal(!!vm.runInNewContext(match[1], fixture.page.data), false, 'known empty results must not flash the initial loading block');
    pending.resolve([]); await flush();
  }
  const taskError = wxml.match(/<view wx:elif="\{\{([^}]*tasksError[^}]*)\}\}" class="empty">/);
  assert.ok(taskError);
  assert.equal(!!vm.runInNewContext(taskError[1], { tasksError: '刷新失败', tasksLoaded: true }), false, 'warm errors must not replace task rows');
});
