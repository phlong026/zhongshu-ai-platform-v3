const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');

function flush() {
  return new Promise(resolve => setImmediate(resolve));
}

function loadPage({ create, list, verify = async () => {}, link = async () => {}, capture, works = async () => [], accounts = async () => [], growthContents = [], pageData, alive = () => true, scope = () => 'post-42', toasts = [], routes = [], writes = [] }) {
  let definition;
  const storage=new Map();
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
    '../../utils/growth': {load:()=>({contents:growthContents})},
    '../../utils/creation': { unavailable() {}, assets: [] },
    '../../utils/platform-content': {
      api: {
        publications: { create, page: list, verify, link },
        publisherAccounts: { list: accounts },
        drafts: { works },
        tasks: { page: async () => [] }
      },
      validatePublicationForm(form) {
        if (!String(form.title || '').trim()) return '请填写标题';
        if (!String(form.publishText || '').trim()) return '请填写发布文案';
        return '';
      },
      validatePublishedUrl(value) {
        const url = String(value || '').trim();
        if (!url) return '请填写发布后的作品链接';
        if (!/^https?:\/\//i.test(url)) return '请填写发布后的作品链接';
        return '';
      },
      rowsOf(rows) { return Array.isArray(rows) ? rows : []; },
      degradeMessage(error) { return error.message || '请求失败'; }
    },
    '../../api/franchise/crm': { leads: { capture: capture || (async () => ({ result: 'CREATED', customerId: 'customer-1' })) } },
    '../../api/http': {
      token() { return 'fixture-token'; }, currentUserId() { return '42'; },
      sessionGeneration() { return 1; }, tenantId() { return 1; },
      sessionGuard() { return alive; },
      requestGuard() { return alive; }
    },
    '../../api/auth': { currentApiPost() { return { postId: scope(), orgId: '8', orgType: 'store', roleCode: 'sales' }; } },
    '../../utils/page-navigation': { open(url) { routes.push(url); } }
  };
  const context = {
    Page(value) { definition = value; },
    wx: { getStorageSync:key=>storage.get(key), setStorageSync:(key,value)=>storage.set(key,structuredClone(value)), removeStorageSync:key=>storage.delete(key), showToast(options) { toasts.push(options.title); }, redirectTo() {}, reLaunch(options) { if(options&&options.url)routes.push(options.url); }, navigateTo(options) { if(options&&options.url)routes.push(options.url); } },
    require(id) {
      if(id==='./publication-pending')return require('../miniprogram/pages/studio/publication-pending');
      if(id==='./publication-accounts')return require('../miniprogram/pages/studio/publication-accounts');
      if(id==='./publication-cover')return require('../miniprogram/pages/studio/publication-cover');
      if(id==='./publication-cover-templates')return require('../miniprogram/pages/studio/publication-cover-templates');
      if(id.endsWith("creation-keyboard"))return require("../miniprogram/utils/creation-keyboard");
      if(id.endsWith("creation-text-resize"))return require("../miniprogram/utils/creation-text-resize");
      if (Object.prototype.hasOwnProperty.call(modules, id)) return modules[id];
      throw new Error('Unexpected require: ' + id);
    },
    console, Date, Math, Promise, setTimeout, clearTimeout
  };
  vm.runInNewContext(source, context, { filename: 'miniprogram/pages/studio/index.js' });
  const instance = Object.assign({}, definition);
  instance.data = Object.assign({}, structuredClone(definition.data), {
    publisherAccountsLoaded:true, publisherAccountId:pageData&&pageData.selectedAccount===1?'201':'200',
    publisherAccounts:[{id:'200',platform:'douyin',displayName:'门店抖音',enabled:true},{id:'201',platform:'channels',displayName:'门店视频号',enabled:true}]
  }, pageData);
  instance.actorScope = scope();
  instance.actorSessionGeneration = 1;
  instance.setData = function setData(patch) { Object.assign(this.data, patch); };
  return instance;
}

const basePublishData = {
  view: 'publish', workId: '8', title: '院落漫游', publishText: '口播文案', tags: '#乡墅',
  selectedAccount: 1, publisherAccountId: '201',
  publishedUrlInput: 'https://example.com/published/91'
};

test('link backfill uses a registered account ID and captures the lead with the publication source', async () => {
  const calls = [];
  const routes = [];
  const page = loadPage({
    create: async (payload, key) => { calls.push(['create', payload, key]); return { id: 91, status: 'publishing' }; },
    list: async () => [],
    verify: async (id, key) => { calls.push(['verify', id, key]); },
    link: async (id, url, key) => { calls.push(['link', id, url, key]); },
    capture: async (payload, key) => { calls.push(['capture', payload, key]); return { result: 'CREATED', customerId: 'customer-9' }; },
    routes,
    pageData: Object.assign({}, basePublishData, { leadOpen: true, leadName: ' 王先生 ', leadPhone: '13800138000', leadNeed: ' 想了解二层户型 ' })
  });

  page.submitLinkAndLead();
  for (let i = 0; i < 8; i += 1) await flush();

  assert.equal(calls[0][0], 'create');
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0][1])), {
    workId: '8', title: '院落漫游', publishText: '口播文案', tags: '#乡墅',
    publisherAccountId: '201', platform: 'channels'
  });
  assert.equal(Object.hasOwn(calls[0][1], 'accountId'), false);
  assert.equal(Object.hasOwn(calls[0][1], 'accountName'), false);
  assert.equal(Object.hasOwn(calls[0][1], 'scheduledAt'), false);
  assert.deepEqual(JSON.parse(JSON.stringify(calls[1])).slice(0, 2), ['verify', '91']);
  assert.deepEqual(JSON.parse(JSON.stringify(calls[2])).slice(0, 3), ['link', '91', 'https://example.com/published/91']);
  assert.deepEqual(JSON.parse(JSON.stringify(calls[3])).slice(0, 2), ['capture', { name: '王先生', phone: '13800138000', need: '想了解二层户型', source: '内容发布:91' }]);
  assert.equal(page.data.linkSaved, true);
  assert.equal(page.data.linkSubmitting, false);
  assert.match(routes[routes.length - 1], /\/pages\/workflow\/index\?view=customer&id=customer-9/);
});

test('link-only submission skips lead capture and returns to the business home', async () => {
  let captures = 0;
  const routes = [];
  const page = loadPage({
    create: async () => ({ id: 92, status: 'publishing' }),
    capture: async () => { captures += 1; return { result: 'CREATED', customerId: 'customer-1' }; },
    routes,
    pageData: Object.assign({}, basePublishData, { leadOpen: false })
  });

  page.submitLinkAndLead();
  for (let i = 0; i < 8; i += 1) await flush();

  assert.equal(captures, 0);
  assert.equal(page.data.linkSaved, true);
  assert.equal(routes.includes('/pages/business/index'), true);
});

test('link backfill requires an available registered account before creating a record', async () => {
  let calls = 0;
  const toasts = [];
  const page = loadPage({
    create: async () => { calls += 1; return { id: 91 }; }, list: async () => [], toasts,
    pageData: Object.assign({}, basePublishData, { publisherAccountId: '' })
  });

  page.submitLinkAndLead();
  await flush();

  assert.equal(calls, 0);
  assert.deepEqual(toasts, ['请选择发布账号']);
});

test('link backfill requires a selected saved work and a valid published URL', async () => {
  let calls = 0;
  const toasts = [];
  const page = loadPage({
    create: async () => { calls += 1; return { id: 91 }; }, list: async () => [], toasts,
    pageData: Object.assign({}, basePublishData, { workId: '' })
  });
  page.submitLinkAndLead();
  assert.deepEqual(toasts, ['请先选择已保存的作品']);

  page.data.workId = '8';
  page.data.publishedUrlInput = 'not-a-link';
  page.submitLinkAndLead();
  assert.equal(calls, 0);
  assert.equal(page.data.operationError, '请填写发布后的作品链接');
});

test('link backfill disables duplicate submit while one request is pending', async () => {
  let resolveCreate;
  let calls = 0;
  const page = loadPage({
    create: () => { calls += 1; return new Promise(resolve => { resolveCreate = resolve; }); }, list: async () => [],
    pageData: Object.assign({}, basePublishData)
  });

  const first = page.submitLinkAndLead();
  const second = page.submitLinkAndLead();
  assert.equal(calls, 1);
  assert.equal(page.data.linkSubmitting, true);
  resolveCreate({ id: 91, status: 'publishing' });
  await Promise.all([first, second]);
  assert.equal(page.data.linkSubmitting, false);

  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  assert.match(wxml, /loading="\{\{linkSubmitting\}\}"/);
  assert.match(wxml, /disabled="\{\{linkSubmitting\|\|linkSaved/);
});

test('link backfill ignores a successful response after session or post scope changes', async () => {
  let resolveCreate;
  let requestAlive = true;
  let currentScope = 'post-42';
  const routes = [];
  const page = loadPage({
    create: () => new Promise(resolve => { resolveCreate = resolve; }), list: async () => [], routes,
    alive: () => requestAlive, scope: () => currentScope,
    pageData: Object.assign({}, basePublishData)
  });

  const pending = page.submitLinkAndLead();
  requestAlive = false;
  currentScope = 'post-99';
  page.actorScope = 'post-99';
  resolveCreate({ id: 91, status: 'publishing' });
  await pending;

  assert.deepEqual(routes, []);
  assert.equal(page.data.linkSaved, false);
});

test('network retry reuses the exact creation payload and idempotency key', async () => {
  const calls = [];
  const routes = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: -1, message: '响应丢失' };
      return { id: 91, status: 'publishing' };
    },
    list: async () => [], routes,
    pageData: Object.assign({}, basePublishData, { title: '原标题', publishText: '原文案', tags: '#原话题' })
  });

  await page.submitLinkAndLead();
  page.data.title = '';
  page.data.publishText = '';
  await page.submitLinkAndLead();
  for (let i = 0; i < 8; i += 1) await flush();

  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1], calls[0]);
  assert.equal(page.data.linkSaved, true);
});

test('server 500 keeps the original creation snapshot for an idempotent retry', async () => {
  const calls = [];
  const toasts = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: 500, message: '服务器异常' };
      return { id: 93, status: 'publishing' };
    },
    list: async () => [], toasts,
    pageData: Object.assign({}, basePublishData)
  });

  await page.submitLinkAndLead();
  page.data.title = '服务器异常后的编辑值';
  page.data.publishText = '不得以同一幂等键发送';
  await page.submitLinkAndLead();
  for (let i = 0; i < 8; i += 1) await flush();

  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1], calls[0]);
  assert.equal(toasts[0], '提交结果待确认，请重试核对本次提交');
});

test('an explicit business failure releases the snapshot for corrected input', async () => {
  const calls = [];
  const page = loadPage({
    create: async (payload, key) => {
      calls.push({ payload: JSON.parse(JSON.stringify(payload)), key });
      if (calls.length === 1) throw { code: 1114000001, message: '发布参数无效' };
      return { id: 92, status: 'publishing' };
    },
    list: async () => [],
    pageData: Object.assign({}, basePublishData, { title: '旧标题', publishText: '旧文案' })
  });

  await page.submitLinkAndLead();
  page.data.title = '更正后的标题';
  page.data.publishText = '更正后的文案';
  await page.submitLinkAndLead();
  for (let i = 0; i < 8; i += 1) await flush();

  assert.equal(calls.length, 2);
  assert.notEqual(calls[1].key, calls[0].key);
  assert.equal(calls[1].payload.title, '更正后的标题');
  assert.equal(calls[1].payload.publishText, '更正后的文案');
});

test('link backfill template exposes registered accounts, link and optional lead but no old form', () => {
  const wxml = fs.readFileSync(path.join(root, 'miniprogram/pages/studio/index.wxml'), 'utf8');
  assert.match(wxml, /回填发布链接/);
  assert.match(wxml, /发布链接/);
  assert.match(wxml, /登记本次咨询客资/);
  assert.match(wxml, /submitLinkAndLead/);
  assert.match(wxml, /publisherAccounts/);
  assert.match(wxml, /managePublisherAccounts/);
  assert.doesNotMatch(wxml, /创建手动发布登记/);
  assert.doesNotMatch(wxml, /publish-title-input/);
  assert.doesNotMatch(wxml, /publish-copy-input/);
  assert.doesNotMatch(wxml, /bindinput="inputPublicationAccount"/);
  assert.doesNotMatch(wxml, /定时需至少|确认定时|sheet === 'schedule'/);

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
  let worksReads = 0;
  let currentScope = 'post-42';
  let requestAlive = true;
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: () => new Promise(resolve => { resolveRows = resolve; }),
    works: () => ++worksReads === 1 ? Promise.resolve([{ id: 'kept-work', title: '已显示作品' }]) : new Promise(resolve => { resolveWorks = resolve; }),
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
  await flush();
  const worksSnapshot = JSON.parse(JSON.stringify(page.data.works));
  page.syncApiWorks();
  requestAlive = false;
  currentScope = 'post-99';
  resolveWorks([{ id: 7, title: '旧岗位作品' }]);
  await flush();
  assert.deepEqual(JSON.parse(JSON.stringify(page.data.works)), worksSnapshot);
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
  let reads = 0;
  let currentScope = 'post-42';
  let requestAlive = true;
  const page = loadPage({
    create: async () => ({ id: 1 }),
    list: () => ++reads === 1 ? Promise.resolve([{ id: 'kept', title: '已显示记录', status: 'published' }]) : new Promise(resolve => { resolveRows = resolve; }),
    alive: () => requestAlive,
    scope: () => currentScope,
    pageData: { view: 'records', records: [{ id: 'kept' }] }
  });

  page.syncApiRecords();
  await flush();
  const snapshot = JSON.parse(JSON.stringify(page.data.records));
  page.syncApiRecords();
  requestAlive = false;
  currentScope = 'post-99';
  resolveRows([{ id: 91, title: '旧任职记录', status: 'publishing' }]);
  await flush();

  assert.deepEqual(JSON.parse(JSON.stringify(page.data.records)), snapshot);
});


test('a restored growth platform is applied before the first account request', async () => {
  const requests=[];
  const page=loadPage({create:async()=>({id:1}),list:async()=>[],
    accounts:async platform=>{requests.push(platform);return [{id:'202',platform:'channels',displayName:'门店视频号',identifier:'shop01',enabled:true}];},
    growthContents:[{id:'content-1',workId:'8',pack:{title:'来源标题',publishText:'来源文案',tags:'',cover:'',platform:'channels'}}]
  });
  page.onLoad({view:'publish',work:'8',content:'content-1'});
  await flush();
  assert.deepEqual(requests,['channels']);
  assert.equal(page.data.publisherAccountsLoading,false);
  assert.equal(page.data.publisherAccountId,'202');
});

test('a saved link must match the visible link before recording a consultation', async () => {
  let linked = '';
  let captured = 0;
  const page = loadPage({
    create: async () => ({ id: '91', status: 'publishing' }),
    list: async () => linked ? [{ id: '91', status: 'published', publishedUrl: linked }] : [],
    link: async (_, url) => { linked = url; throw { code: -1, message: '回执未返回' }; },
    capture: async () => { captured++; return { result: 'CREATED', customerId: '100' }; },
    pageData: { ...basePublishData, publishedUrlInput: 'https://v.douyin.com/exampleA/' }
  });
  await page.submitLinkAndLead();
  assert.equal(linked, 'https://v.douyin.com/exampleA/');
  Object.assign(page.data, {publishedUrlInput:'https://v.douyin.com/exampleB/',leadOpen:true,leadName:'示例客户',leadPhone:'13900000000',leadNeed:'咨询乡墅'});
  await page.submitLinkAndLead();
  assert.equal(captured, 0, 'consultation from B must not be linked to publication A');
});

test('unknown lead response only retries the original contact against the confirmed link', async () => {
  const calls=[];
  let linkWrites=0;
  const url='https://v.douyin.com/original/';
  const original={leadOpen:true,leadName:'示例甲',leadPhone:'13900000001',leadNeed:'咨询庭院'};
  const page=loadPage({
    create:async()=>({id:'91',status:'publishing'}),
    list:async()=>[{id:'91',status:'published',publishedUrl:url}],
    link:async()=>{linkWrites++;},
    capture:async(payload,key)=>{
      calls.push({payload:JSON.parse(JSON.stringify(payload)),key});
      if(calls.length===1)throw {code:-1,message:'未收到回执'};
      return {result:'CREATED',customerId:'100'};
    },
    pageData:{...basePublishData,...original,publishedUrlInput:url}
  });
  await page.submitLinkAndLead();
  page.data.leadPhone='13900000002';
  await page.submitLinkAndLead();
  assert.equal(calls.length,1);
  Object.assign(page.data,original);
  await page.submitLinkAndLead();
  assert.equal(calls.length,2);
  assert.deepEqual(calls[0],calls[1]);
  assert.equal(linkWrites,1);
  assert.equal(page.data.linkSaved,true);
});

test('a published record without a matching server URL cannot attach a contact even on first submit',async()=>{
  for(const publishedUrl of ['', 'https://v.douyin.com/another/']){
    let captured=0;
    const page=loadPage({
      create:async()=>({id:'91',status:'published',publishedUrl}),
      list:async()=>[],
      capture:async()=>{captured++;return {result:'CREATED',customerId:'100'};},
      pageData:{...basePublishData,leadOpen:true,leadName:'示例甲',leadPhone:'13900000001',leadNeed:'咨询庭院'}
    });
    await page.submitLinkAndLead();
    assert.equal(captured,0);
    assert.match(page.data.operationError,/链接与当前输入不一致/);
  }
});
