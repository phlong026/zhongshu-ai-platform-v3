const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');

function flush() {
  return new Promise(resolve => setImmediate(resolve));
}

function loadPage({ create, list, verify, link, works = async () => [], pageData, alive = () => true, scope = () => 'post-42', toasts = [], routes = [], writes = [] }) {
  let definition;
  const studio = {
    title: '',
    publicationPlatforms: [
      { platform: 'douyin', name: '抖音' },
      { platform: 'channels', name: '视频号' }
    ],
    guard() { return true; },
    allowed() { return true; },
    scope,
    read() {
      return { title: '', original: '', script: '', rewriteHistory: [], publishDrafts: {} };
    },
    write(value) { writes.push(value); },
    imageFor() { return '/digital.jpg'; },
    kindLabel() { return '数字人口播'; },
    formatTime(value) { return value || '时间未记录'; }
  };
  const source = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.js'), 'utf8');
  const modules = {
    './material-library':{data:{},handlers:{}},
    './task-display':require('../miniprogram/pages/studio/task-display'),
    './content-task-result':require('../miniprogram/pages/studio/content-task-result'),
    '../../utils/studio': studio,
    '../../utils/creation': { unavailable() {}, assets: [] },
    '../../utils/platform-content': {
      api: {
        publications: { create, page: list, verify, link },
        drafts: { works },
        tasks: { page: async () => [] }
      },
      validatePublicationForm(form) {
        if (!String(form.title || '').trim()) return '请填写标题';
        if (!String(form.publishText || '').trim()) return '请填写发布文案';
        return '';
      },
      validatePublishedUrl(value) { return String(value || '').trim() ? '' : '请填写发布后的作品链接'; },
      rowsOf(rows) { return Array.isArray(rows) ? rows : []; },
      degradeMessage(error) { return error.message || '请求失败'; }
    },
    '../../api/http': {
      token() { return 'fixture-token'; },
      sessionGuard() { return alive; },
      requestGuard() { return alive; }
    },
    '../../utils/page-navigation': { open(url) { routes.push(url); } }
  };
  const context = {
    Page(value) { definition = value; },
    wx: { showToast(options) { toasts.push(options.title); }, redirectTo() {}, reLaunch() {} },
    require(id) {
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (Object.prototype.hasOwnProperty.call(modules, id)) return modules[id];
      throw new Error('Unexpected require: ' + id);
    },
    console, Date, Math, Promise, setTimeout, clearTimeout
  };
  vm.runInNewContext(source, context, { filename: 'miniprogram/pages/studio/index.js' });
  const instance = Object.assign({}, definition);
  instance.data = Object.assign({}, structuredClone(definition.data), pageData);
  instance.actorScope = scope();
  instance.setData = function setData(patch) { Object.assign(this.data, patch); };
  return instance;
}

test('manual publication uses operator-entered account and never sends demo ids or scheduling', async () => {
  let sent;
  const page = loadPage({
    create: async payload => { sent = payload; return { id: 91 }; },
    list: async () => [],
    pageData: {
      view: 'publish', workId: '8', title: '院落漫游', publishText: '手动发布文案', tags: '#乡墅',
      selectedAccount: 1, publicationAccount: ' 泉州门店视频号 ', scheduled: '2099-12-01 10:00'
    }
  });

  page.submitPublishOnline();
  await flush();

  assert.deepEqual(JSON.parse(JSON.stringify(sent)), {
    workId: '8', title: '院落漫游', publishText: '手动发布文案', tags: '#乡墅',
    accountId: '泉州门店视频号', platform: 'channels'
  });
  assert.notEqual(sent.accountId, 'douyin-adviser');
  assert.notEqual(sent.accountId, 'channels-design');
  assert.equal(Object.hasOwn(sent, 'scheduledAt'), false);
});

test('manual publication requires the actual account name before creating a record', async () => {
  let calls = 0;
  const toasts = [];
  const page = loadPage({
    create: async () => { calls += 1; return { id: 91 }; },
    list: async () => [],
    toasts,
    pageData: { view: 'publish', workId: '8', title: '院落漫游', publishText: '手动发布文案', publicationAccount: '   ' }
  });

  page.submitPublishOnline();
  await flush();

  assert.equal(calls, 0);
  assert.deepEqual(toasts, ['请填写实际发布账号名称']);
});

test('manual publication requires a selected saved work and refuses an invalid page state', async () => {
  let calls = 0;
  const toasts = [];
  const page = loadPage({
    create: async () => { calls += 1; return { id: 91 }; }, list: async () => [], toasts,
    pageData: { view: 'publish', workId: '', title: '院落漫游', publishText: '手动发布文案', publicationAccount: '门店视频号' }
  });
  await page.submitPublishOnline();
  page.data.workId = '8';
  page.data.invalidMessage = '作品已失效';
  await page.submitPublishOnline();

  assert.equal(calls, 0);
  assert.deepEqual(toasts, ['请先选择已保存的作品', '请先选择已保存的作品']);
});

test('manual publication disables duplicate submit while one request is pending', async () => {
  let resolveCreate;
  let calls = 0;
  const page = loadPage({
    create: () => { calls += 1; return new Promise(resolve => { resolveCreate = resolve; }); }, list: async () => [],
    pageData: { view: 'publish', workId: '8', title: '院落漫游', publishText: '手动发布文案', publicationAccount: '门店视频号' }
  });

  const first = page.submitPublishOnline();
  const second = page.submitPublishOnline();
  assert.equal(calls, 1);
  assert.equal(page.data.publishSubmitting, true);
  resolveCreate({ id: 91 });
  await Promise.all([first, second]);
  assert.equal(page.data.publishSubmitting, false);

  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  assert.match(wxml, /loading="\{\{publishSubmitting\}\}"/);
  assert.match(wxml, /disabled="\{\{publishSubmitting\}\}"/);
});

test('manual publication ignores a successful response after session or post scope changes', async () => {
  let resolveCreate;
  let requestAlive = true;
  let currentScope = 'post-42';
  const routes = [];
  const writes = [];
  const page = loadPage({
    create: () => new Promise(resolve => { resolveCreate = resolve; }), list: async () => [], routes, writes,
    alive: () => requestAlive, scope: () => currentScope,
    pageData: { view: 'publish', workId: '8', title: '院落漫游', publishText: '手动发布文案', publicationAccount: '门店视频号' }
  });

  const pending = page.submitPublishOnline();
  requestAlive = false;
  currentScope = 'post-99';
  page.actorScope = 'post-99';
  resolveCreate({ id: 91 });
  await pending;

  assert.deepEqual(routes, []);
  assert.deepEqual(writes, []);
});

test('network retry reuses the exact publication payload and idempotency key', async () => {
  const calls = [];
  const routes = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: -1, message: '响应丢失' };
      return { id: 91 };
    },
    list: async () => [], routes,
    pageData: { view: 'publish', workId: '8', title: '原标题', publishText: '原文案', tags: '#原话题', publicationAccount: '门店视频号' }
  });

  await page.submitPublishOnline();
  page.data.workId = '';
  page.data.title = '';
  page.data.publishText = '';
  page.data.invalidMessage = '后来加载作品失败';
  await page.submitPublishOnline();

  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1], calls[0]);
  assert.deepEqual(routes, ['/pages/studio/index?view=receipt&record=91']);
});

test('server 500 keeps the original publication snapshot for an idempotent retry', async () => {
  const calls = [];
  const toasts = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: 500, message: '服务器异常' };
      return { id: 93 };
    },
    list: async () => [], toasts,
    pageData: { view: 'publish', workId: '8', title: '原标题', publishText: '原文案', publicationAccount: '门店视频号' }
  });

  await page.submitPublishOnline();
  page.data.title = '服务器异常后的编辑值';
  page.data.publishText = '不得以同一幂等键发送';
  await page.submitPublishOnline();

  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1], calls[0]);
  assert.equal(toasts[0], '登记结果待确认，请重试核对本次提交');
});

test('an explicit business failure releases the publication snapshot for corrected input', async () => {
  const calls = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: 1114000001, message: '发布参数无效' };
      return { id: 92 };
    },
    list: async () => [],
    pageData: { view: 'publish', workId: '8', title: '旧标题', publishText: '旧文案', publicationAccount: '门店视频号' }
  });

  await page.submitPublishOnline();
  page.data.title = '更正后的标题';
  page.data.publishText = '更正后的文案';
  await page.submitPublishOnline();

  assert.equal(calls.length, 2);
  assert.notEqual(calls[1].key, calls[0].key);
  assert.equal(calls[1].payload.title, '更正后的标题');
  assert.equal(calls[1].payload.publishText, '更正后的文案');
});

test('manual publication template exposes platform and account input but no scheduling controls', () => {
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  assert.match(wxml, /发布平台/);
  assert.match(wxml, /publicationAccount/);
  assert.match(wxml, /手动发布后登记链接|请先在所选平台发布内容，再登记链接/);
  assert.doesNotMatch(wxml, /chooseSchedule|sheet === 'schedule'|确认定时|定时需至少/);

  const pageSource = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.js'), 'utf8');
  assert.doesNotMatch(pageSource, /douyin-adviser|channels-design/);
});

test('manual link registration verifies external publication before linking a publishing record', async () => {
  const calls = [];
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: async () => [],
    verify: async id => { calls.push(['verify', id]); },
    link: async (id, url) => { calls.push(['link', id, url]); },
    pageData: {
      view: 'receipt', recordId: '91', publishedUrlInput: 'https://example.com/manual/91',
      detail: { id: '91', status: 'publishing' }
    }
  });

  page.savePublicationLinkOnline();
  await flush();
  await flush();

  assert.deepEqual(calls, [
    ['verify', '91'],
    ['link', '91', 'https://example.com/manual/91']
  ]);
});

test('receipt loading race cannot write before a valid publication status is loaded', async () => {
  let resolveRows;
  const calls = [];
  const toasts = [];
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: () => new Promise(resolve => { resolveRows = resolve; }),
    verify: async () => { calls.push('verify'); },
    link: async () => { calls.push('link'); },
    toasts,
    pageData: { view: 'receipt', recordId: '91', detail: null, publishedUrlInput: 'https://example.com/manual/91' }
  });

  page.loadApiReceipt('91');
  page.savePublicationLink();
  assert.deepEqual(calls, []);
  assert.deepEqual(toasts, ['发布记录加载中，请稍候']);

  resolveRows([{ id: 91, status: 'publishing', accountId: '门店视频号' }]);
  await flush();
  assert.equal(page.data.receiptLoading, false);
  assert.equal(page.data.detail.status, 'publishing');
});

test('historical scheduled and published receipts are non-writable', async () => {
  for (const status of ['scheduled', 'published']) {
    const calls = [];
    const toasts = [];
    const page = loadPage({
      create: async () => ({ id: 1 }), list: async () => [],
      verify: async () => { calls.push('verify'); }, link: async () => { calls.push('link'); }, toasts,
      pageData: { view: 'receipt', recordId: '91', detail: { id: '91', status }, publishedUrlInput: 'https://example.com/manual/91' }
    });

    page.savePublicationLink();
    await flush();

    assert.deepEqual(calls, [], status + ' must not issue verify or link');
    assert.equal(toasts.length, 1);
    assert.match(toasts[0], status === 'scheduled' ? /历史定时记录/ : /仅供查看/);
  }
});

test('verify status links directly, while publishing verify failure never links', async () => {
  const direct = [];
  const verifyPage = loadPage({
    create: async () => ({ id: 1 }), list: async () => [],
    verify: async () => { direct.push('verify'); }, link: async () => { direct.push('link'); },
    pageData: { view: 'receipt', recordId: '91', detail: { id: '91', status: 'verify' }, publishedUrlInput: 'https://example.com/manual/91' }
  });
  verifyPage.savePublicationLinkOnline();
  await flush();
  assert.deepEqual(direct, ['link']);

  const failed = [];
  const publishingPage = loadPage({
    create: async () => ({ id: 1 }), list: async () => [],
    verify: async () => { failed.push('verify'); throw new Error('确认失败'); },
    link: async () => { failed.push('link'); },
    pageData: { view: 'receipt', recordId: '92', detail: { id: '92', status: 'publishing' }, publishedUrlInput: 'https://example.com/manual/92' }
  });
  publishingPage.savePublicationLinkOnline();
  await flush();
  assert.deepEqual(failed, ['verify']);
  assert.equal(publishingPage.data.receiptError, '确认失败');
});

test('receipt and works ignore responses after request or post scope becomes stale', async () => {
  let resolveRows;
  let resolveWorks;
  let currentScope = 'post-42';
  let requestAlive = true;
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: () => new Promise(resolve => { resolveRows = resolve; }),
    works: () => new Promise(resolve => { resolveWorks = resolve; }),
    alive: () => requestAlive,
    scope: () => currentScope,
    pageData: { view: 'receipt', recordId: '91', detail: { id: 'kept', status: 'verify' }, works: [{ id: 'kept-work' }] }
  });

  page.loadApiReceipt('91');
  const receiptSnapshot = JSON.parse(JSON.stringify(page.data.detail));
  requestAlive = false;
  currentScope = 'post-99';
  resolveRows([{ id: 91, status: 'publishing' }]);
  await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(page.data.detail)), receiptSnapshot);

  requestAlive = true;
  currentScope = 'post-42';
  page.actorScope = currentScope;
  page.data.view = 'works';
  page.syncApiWorks();
  requestAlive = false;
  currentScope = 'post-99';
  resolveWorks([{ id: 7, title: '旧岗位作品' }]);
  await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(page.data.works)), [{ id: 'kept-work' }]);
});

test('an unfinished manual publication can resume the receipt page without claiming external authorization', () => {
  const routes = [];
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: async () => [],
    routes,
    pageData: { view: 'detail', detail: { id: '91', status: 'publishing' } }
  });

  page.advancePublication();

  assert.deepEqual(routes, ['/pages/studio/index?view=receipt&record=91']);
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, />修改链接</);
});

test('publication list keeps request and post-switch response guards', async () => {
  let resolveRows;
  let currentScope = 'post-42';
  let requestAlive = true;
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: () => new Promise(resolve => { resolveRows = resolve; }),
    alive: () => requestAlive,
    scope: () => currentScope,
    pageData: { view: 'records', records: [{ id: 'kept' }] }
  });

  page.syncApiRecords();
  requestAlive = false;
  currentScope = 'post-99';
  resolveRows([{ id: 91, title: '旧任职记录', status: 'publishing' }]);
  await flush();

  assert.deepEqual(JSON.parse(JSON.stringify(page.data.records)), [{ id: 'kept' }]);
});
