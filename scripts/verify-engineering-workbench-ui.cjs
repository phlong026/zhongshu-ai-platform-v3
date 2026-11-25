const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');

const rows = [
  { id: '9007199254740993', name: '林家乡墅', customer: '林先生', city: '泉州', status: 'IN_PROGRESS', nodes: [] },
  { id: '9007199254740994', name: '王家乡墅', customer: '王女士', city: '晋江', status: 'PENDING_START', nodes: [] },
  { id: '9007199254740995', name: '陈家乡墅', customer: '陈先生', city: '厦门', status: 'READY_DELIVER', nodes: [] }
];
const flush = async () => { for (let i = 0; i < 14; i++) await new Promise(resolve => setImmediate(resolve)); };
const current = () => getCurrentPages().at(-1);
const flat = nodes => nodes.flatMap(node => [node, ...flat(node.children)]);
const content = node => (node.text || '') + node.children.map(content).join('');
const rendered = (file, data) => flat(render(path.resolve(__dirname, '../miniprogram', file), data));

async function setup(role = 'engineer', supportsFiling = true) {
  const runtime = await connect({lifecycles:true});
  const auth = require('../miniprogram/api/auth');
  const post = { postId: 'workbench-ui-' + role, orgId: 'ui-test', orgType: 'store', roleCode: role };
  auth.saveLogin({ accessToken: 'isolated-ui-test' }); auth.savePosts([post]); auth.switchPost(post.postId);
  let projects = rows;
  const writes = [];
  const filingRequests = [];
  const projectRequests = [];
  wx.request = options => {
    const url = new URL(options.url).pathname.replace('/admin-api', '');
    let data = [];
    if (url === '/franchise/engineering/projects/contract-filing') {
      if (!supportsFiling) return setImmediate(() => options.success({ statusCode: 404, data: {} }));
      if (options.method === 'GET') data = true;
      else {
        filingRequests.push(options.data);
        if (!options.data.independent && options.data.phone !== '13900008158') {
          return setImmediate(() => options.success({statusCode:200,data:{code:1115000001,msg:'未找到对应销售合同，请选择独立建档'}}));
        }
        data = rows[0];
      }
    }
    if (url === '/platform/profile/mine') data = { nickname: '工程同事' };
    else if (url === '/platform/todos') data = { items: [], failedDomains: [], actionableCount: 0 };
    else if (url === '/franchise/engineering/projects') {
      const {page = 1, pageSize = 50} = options.data || {};
      data = projects.slice((page - 1) * pageSize, page * pageSize); projectRequests.push(options.data);
    }
    else if (/\/franchise\/engineering\/\d+$/.test(url)) data = rows.find(row => url.endsWith('/' + row.id));
    else if (url.endsWith('/materials/summary')) data = { plans: [] };
    else if (url.includes('/collections')) data = { stages: [], records: [] };
    else if (url.includes('/settle')) data = {};
    if (options.method !== 'GET') writes.push(options.url);
    setImmediate(() => options.success({ statusCode: 200, data: { code: 0, data } }));
  };
  return { runtime, writes, filingRequests, projectRequests, projects(value) { projects = value; }, async open(url) { await runtime.callWxMethod('reLaunch', { url }); await flush(); return runtime.currentPage(); } };
}

function workspaceBar() {
  const fs = require('node:fs'), vm = require('node:vm');
  const file = require.resolve('../miniprogram/custom-tab-bar/index.js');
  let definition;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {Component: value => { definition = value; },
    require: require('node:module').createRequire(file), getCurrentPages, getApp, wx});
  const bar = { ...definition.methods, data: {}, properties: {activeIndex: current().data.navigationIndex, nested: true},
    setData(patch) { Object.assign(this.data, patch); } };
  bar.refresh();
  return index => bar.switchTab({currentTarget: {dataset: {index}}});
}

for (const role of ['engineer', 'project-manager']) {
  test(role + ' 的首页、项目、材料连续点击保留同一页面和已加载面板', async () => {
    const h = await setup(role);
    await h.open('/pages/build/home/index');
    const home = current(), initialRequests = h.projectRequests.length, navigations = [], lifecycles = [];
    for (const method of ['onLoad', 'onShow', 'onHide', 'onUnload']) {
      const original = home[method]; home[method] = function (...args) { lifecycles.push(method); return original.apply(this, args); };
    }
    for (const method of ['navigateTo', 'redirectTo', 'switchTab', 'reLaunch']) {
      const original = wx[method]; wx[method] = options => { navigations.push([method, options.url]); return original(options); };
    }
    const tap = workspaceBar();
    tap(8); tap(9); tap(2); tap(9);
    await flush();
    assert.equal(current(), home, '主导航不得重建 Page 或走中间路由');
    assert.deepEqual(navigations, []); assert.deepEqual(lifecycles, [], '局部切换不能手动重放 Page 生命周期');
    assert.equal(home.data.homeMounted, true); assert.equal(home.data.projectsMounted, true);
    assert.equal(home.data.destination, 'materials');
    assert.equal(home.data.navigationIndex, 9);
    assert.equal(home.data.cards.length, 3);
    assert.equal(h.projectRequests.length - initialRequests, 1, '首次项目加载期间切材料只复用同一列表请求');
    tap(8); home.setData({keyword: '王女士', expandedProjects: true}); home.applyProjectFilters();
    tap(2); tap(9); tap(8);
    assert.equal(home.data.keyword, '王女士');
    assert.equal(home.data.expandedProjects, true);
    assert.equal(home.data.cards[0].id, rows[1].id);
    assert.equal(h.projectRequests.length - initialRequests, 1, '就地返回已加载面板不再重拉列表');
  });
}

test('旧项目直链保留筛选、destination 和 mode，随后仍在原 Page 切换工作台', async () => {
  const h = await setup();
  await h.open('/pages/build/projects/index?filter=' + encodeURIComponent('在建') + '&destination=schedule&mode=tasks');
  const page = current(), tap = workspaceBar();
  assert.equal(page.data.filter, '在建'); assert.equal(page.data.destination, 'schedule'); assert.equal(page.data.mode, 'tasks');
  tap(9); tap(2); tap(8); await flush();
  assert.equal(current(), page); assert.equal(page.data.destination, ''); assert.equal(page.data.mode, '');
  assert.equal(page.data.filter, '在建');
});

test('项目建档草稿跨首页和材料保存，材料面板不显示项目建档表单', async () => {
  const h = await setup();
  await h.open('/pages/build/projects/index');
  const page = current(), tap = workspaceBar();
  await page.openCreate(); page.setData({'createForm.name': '未提交项目'});
  tap(9); await flush();
  assert.equal(current(), page);
  const material = await h.runtime.currentPage();
  assert.equal(await material.$('.eng-create-form'), null);
  assert.ok(await material.$('.eng-project-open'));
  tap(2); tap(8);
  assert.equal(page.data.creating, true); assert.equal(page.data.createForm.name, '未提交项目');
  assert.ok(await (await h.runtime.currentPage()).$('.eng-create-form'));
});

test('从材料详情返回原工作台时保留分页、筛选、展开和滚动，后台刷新完整快照后才替换', async () => {
  const h = await setup();
  const many = Array.from({length: 51}, (_, index) => ({...rows[0], id: String(BigInt(rows[0].id) + BigInt(index)), name: '工程项目' + index}));
  h.projects(many);
  const facade = await h.open('/pages/build/projects/index?destination=materials&filter=' + encodeURIComponent('在建'));
  const page = current(); page.toggleProjects(); await page.loadNextProjects();
  assert.equal(page.data.cards.length, 51);
  page.onPageScroll({scrollTop: 760});
  await (await facade.$('.eng-project-open')).tap(); await flush();
  assert.equal(getCurrentPages().length, 2); assert.equal(current().data.view, 'materials');
  const request = wx.request, pending = [], scrolls = [];
  wx.request = options => options.url.endsWith('/franchise/engineering/projects') ? pending.push(options) : request(options);
  wx.pageScrollTo = options => scrolls.push(options);
  await h.runtime.callWxMethod('navigateBack', {delta: 1});
  assert.equal(current(), page); assert.equal(page.data.destination, 'materials');
  assert.equal(page.data.filter, '在建'); assert.equal(page.data.expandedProjects, true);
  assert.equal(page.data.cards.length, 51); assert.equal(scrolls.at(-1).scrollTop, 760);
  pending[0].success({statusCode: 200, data: {code: 0, data: many.slice(0, 50)}}); await flush();
  assert.equal(page.data.cards.length, 51, '不能先缩成第一页导致可见列表和滚动跳动');
  assert.equal(pending[1].data.page, 2);
  pending[1].success({statusCode: 200, data: {code: 0, data: many.slice(50)}}); await flush();
  assert.equal(page.data.cards.length, 51); assert.equal(page.__projectPage, 2);
});

test('同岗位项目刷新失败保留可见列表，403 失权清空缓存和建档草稿', async () => {
  const h = await setup(); await h.open('/pages/build/projects/index');
  const page = current(); await page.openCreate(); page.setData({'createForm.name': '当前草稿'});
  wx.request = options => setImmediate(() => options.success({statusCode: 503, data: {code: 503, msg: '暂不可用'}}));
  await page.refreshOnline();
  assert.equal(page.data.cards.length, 3); assert.equal(page.data.createForm.name, '当前草稿');
  wx.request = options => setImmediate(() => options.success({statusCode: 403, data: {code: 403, msg: '权限已失效'}}));
  await page.refreshOnline();
  assert.deepEqual(page.data.cards, []); assert.deepEqual(page.__platRows, []);
  assert.equal(page.data.canCreate, false); assert.equal(page.data.creating, false); assert.equal(page.data.createForm.name, '');
});

test('详情返回的全量刷新期间下一页不能抢占请求或把旧第一页拼回列表', async () => {
  const h = await setup();
  const many = Array.from({length: 51}, (_, index) => ({...rows[0], id: String(BigInt(rows[0].id) + BigInt(index)), name: '原项目' + index}));
  h.projects(many); await h.open('/pages/build/projects/index');
  const page = current(); page.toggleProjects();
  const updated = many.map((row, index) => ({...row, name: '详情更新后' + index}));
  h.projects(updated);
  const request = wx.request, pending = [];
  wx.request = options => options.url.endsWith('/franchise/engineering/projects') ? pending.push(options) : request(options);
  const refresh = page.refreshOnline(), append = page.loadNextProjects(), started = pending.length;
  const nextButton = rendered('pages/build/projects/index.wxml', page.data).find(node => node.attrs?.bindtap === 'loadNextProjects');
  const nextPage = pending.find(options => options.data.page === 2);
  if (nextPage) nextPage.success({statusCode: 200, data: {code: 0, data: updated.slice(50)}});
  await append;
  pending[0].success({statusCode: 200, data: {code: 0, data: updated.slice(0, 50)}}); await refresh;
  assert.equal(page.data.cards[0].name, '详情更新后0');
  assert.equal(started, 1); assert.equal(nextButton.attrs.disabled, true);
  wx.request = request; await page.loadNextProjects();
  assert.equal(page.data.cards.length, 51); assert.equal(page.data.cards[0].name, '详情更新后0');
});

for (const source of ['home', 'projects']) for (const error of [{postInvalid: true, message: '工作身份已撤销'}, {code: 1103000002, message: '工作身份已撤销'}]) {
  test(source + ' 的 ' + (error.postInvalid ? 'postInvalid' : '1103000002') + ' 清空所有工程缓存和草稿', async () => {
    const h = await setup(); await h.open('/pages/build/home/index');
    const page = current(), tap = workspaceBar(); tap(8); await flush();
    await page.openCreate(); page.setData({'createForm.name': '已撤销岗位草稿'});
    const api = require('../miniprogram/utils/platform-engineering').api.projects;
    const method = source === 'home' ? 'page' : 'pageChunk', original = api[method];
    api[method] = () => Promise.reject(error);
    try { await (source === 'home' ? page.loadOnline() : page.refreshOnline()); }
    finally { api[method] = original; }
    assert.equal(page.data.cards.length, 0); assert.equal(page.__platRows.length, 0);
    assert.equal(page.data.createForm.name, ''); assert.equal(page.data.creating, false); assert.equal(page.data.canCreate, false);
    assert.equal(page.data.eng?.recent || null, null);
    assert.match(source === 'home' ? page.data.homeError : page.data.error, /工作身份已撤销/);
  });
}

test('工程失权清理后，之前建档请求的迟到成功不能打开旧工程详情', async () => {
  const h = await setup(); await h.open('/pages/build/projects/index');
  const page = current(); await page.openCreate(); page.setData({'createForm.name': '失权前提交'});
  const api = require('../miniprogram/utils/platform-engineering').api.projects;
  const create = api.create, chunk = api.pageChunk; let finish;
  api.create = () => new Promise(resolve => { finish = resolve; });
  api.pageChunk = () => Promise.reject({postInvalid: true, message: '工作身份已撤销'});
  try {
    const submit = page.submitCreateOnline(); await page.refreshOnline();
    finish(rows[0]); await submit;
    assert.equal(current(), page); assert.equal(page.data.canCreate, false); assert.equal(page.data.cards.length, 0);
  } finally { api.create = create; api.pageChunk = chunk; }
});

test('已提交建档在其他页面返回结果时不会抢导航，回到工作台可继续使用', async () => {
  const h = await setup(); await h.open('/pages/build/projects/index');
  const page = current(), tap = workspaceBar(); await page.openCreate();
  page.setData({'createForm.name': '后台建档', 'createForm.phone': '13900008158'});
  const request = wx.request, writes = [];
  wx.request = options => options.method === 'POST' ? writes.push(options) : request(options);
  const submit = page.submitCreateOnline(); tap(2); await flush();
  await h.runtime.callWxMethod('navigateTo', {url: '/pages/build/record/index?id=' + rows[0].id + '&view=overview'}); await flush();
  const detail = current();
  writes[0].success({statusCode: 200, data: {code: 0, data: rows[0]}}); await submit;
  assert.equal(current(), detail); assert.equal(page.data.busy, false); assert.equal(page.data.creating, false);
  await h.runtime.callWxMethod('navigateBack'); tap(8); await flush();
  assert.equal(current(), page); assert.equal(page.data.busy, false); assert.equal(page.data.canCreate, true);
});

for (const [field, value] of [['roleCode', 'project-manager'], ['orgType', 'headquarters'], ['orgId', 'other-org'], ['tenantId', 'other-tenant']]) {
test('同 postId 的 ' + field + ' 变更立即清掉常驻首页、列表和草稿，拒绝迟到旧请求', async () => {
  const h = await setup();
  await h.open('/pages/build/home/index');
  const page = current(), tap = workspaceBar(); tap(8); await flush();
  await page.openCreate(); page.setData({'createForm.name': '旧岗位草稿'});
  const auth = require('../miniprogram/api/auth'), pending = [];
  wx.request = options => pending.push(options);
  const oldRequest = page.refreshOnline();
  const oldPost = auth.currentApiPost(); auth.savePosts([{...oldPost, [field]: value}]);
  page.onShow();
  assert.equal(page.data.eng, null); assert.deepEqual(page.data.cards, []);
  assert.equal(page.data.createForm.name, ''); assert.equal(page.data.creating, false);
  pending[0].success({statusCode: 200, data: {code: 0, data: [{...rows[0], name: '旧岗位项目'}]}});
  await oldRequest; await flush();
  assert.deepEqual(page.data.cards, []);
});
}

for (const role of ['boss', 'finance']) {
  test(role + ' 的工程只读直链不接管工程岗位主导航', async () => {
    const h = await setup(role);
    await h.open('/pages/build/projects/index?destination=materials&filter=' + encodeURIComponent('在建'));
    const page = current();
    assert.equal(page.data.cards[0].id, rows[0].id);
    assert.equal(page.data.destination, 'materials');
    assert.equal(page.switchWorkspaceTab({key: 'engineering', index: 2}), false);
    if (role === 'finance') assert.equal(page.data.canCreate, false);
    else assert.equal(page.data.readOnly, true);
  });
}

test('旧后端可浏览工程列表，缺少合同建档能力时不展示入口也不能提交', async () => {
  const h = await setup('engineer', false);
  const page = await h.open('/pages/build/projects/index');
  assert.equal(current().data.cards.length, 3);
  assert.equal(await page.$('.eng-create-entry'), null);
  await current().openCreate();
  await current().submitCreateOnline();
  assert.deepEqual(h.writes, []);
});

test('未匹配合同保留建档表单，只有用户主动选择时才提交独立建档', async () => {
  const h = await setup();
  const page = await h.open('/pages/build/projects/index');
  await (await page.$('.eng-create-entry')).tap();
  const form = current();
  form.setData({'createForm.name':'陈家乡墅','createForm.phone':'13900008157'});
  await form.submitCreateOnline();
  assert.match(form.data.error,/选择独立建档/);
  assert.equal(form.data.creating,true);
  assert.equal(form.data.createForm.name,'陈家乡墅');
  assert.equal(h.filingRequests[0].independent,undefined);
  form.toggleIndependent({detail:{value:true}});
  await form.submitCreateOnline();
  assert.equal(h.filingRequests[1].independent,true);
  assert.equal(h.filingRequests[1].name,'陈家乡墅');
  assert.ok(h.writes.every(url=>url.endsWith('/projects/contract-filing')));
});

test('工程概览指标进入对应月份的工程明细，最近项目随数量显示入口', async () => {
  const h = await setup();
  const home = await h.open('/pages/build/home/index');
  assert.ok(await home.$('home-overview[role="engineering"]'));
  const file = require.resolve('../miniprogram/components/home-overview/index.js');
  let overview;
  require('node:vm').runInNewContext(require('node:fs').readFileSync(file, 'utf8'), {
    Component: value => { overview = value; }, require: require('node:module').createRequire(file)
  });
  const metrics = ['overdueNodes', 'materials', 'rectifications', 'projectDue'];
  const board = require('../miniprogram/utils/home-metrics').view('engineering', {
    metrics: Object.fromEntries(metrics.map(key => [key, {total: 1, cents: 0}]))
  });
  for (const key of metrics) {
    const page = await h.runtime.mountFixture({
      file: file.replace(/\.js$/, '.wxml'), data: {role: 'engineering', month: '2026-10', board},
      methods: {properties: {role: 'engineering'}, metric: overview.methods.metric}
    });
    await (await page.$('button[data-key="' + key + '"]')).tap(); await flush();
    assert.equal(current().route, 'pages/home-metrics/index');
    assert.equal(current().role, 'engineering');
    assert.equal(current().key, key);
    assert.equal(current().data.period, '2026-10');
  }
  for (const count of [0, 1, 3]) {
    h.projects(rows.slice(0, count));
    const page = await h.open('/pages/build/home/index');
    assert.equal(!!(await page.$('.eng-recent-project')), count > 0);
    assert.equal(!!(await page.$('.eng-recent-footer button')), count > 1);
    const text = rendered('pages/build/home/index.wxml', current().data).map(content).join('');
    assert.ok(!/进度以项目详情为准|可在这里继续办理|平台待办|已加载可办理/.test(text));
  }
  assert.deepEqual(h.writes, []);
});

test('微信原生传入的编码筛选参数会解码后匹配项目', async () => {
  const h = await setup();
  await h.open('/pages/build/projects/index');
  current().onLoad({filter:encodeURIComponent('在建')});
  current().applyProjectFilters();
  assert.equal(current().data.filter,'在建');
  assert.equal(current().data.cards.length,1);
  assert.doesNotThrow(()=>current().onLoad({filter:'%E0%A4%A'}));
});

test('项目空态不显示查看入口，多项目可展开、收起并搜索客户和城市，新增入口位于顶部', async () => {
  const h = await setup();
  let page = await h.open('/pages/build/projects/index');
  assert.equal(current().data.visibleCards.length, 1);
  await (await page.$('.eng-project-more button')).tap();
  assert.equal(current().data.visibleCards.length, 3);
  const text = rendered('pages/build/projects/index.wxml', current().data).map(content).join('');
  assert.ok(!text.includes('-1%'), '未知进度不展示负数百分比');
  await (await page.$('.eng-project-more button')).tap();
  assert.equal(current().data.visibleCards.length, 1);
  await (await page.$('.eng-search-input')).input('王女士');
  assert.equal(current().data.cards[0].id, rows[1].id);
  await (await page.$('.eng-search-input')).input('厦门');
  assert.equal(current().data.cards[0].id, rows[2].id);
  await (await page.$('.eng-create-entry')).tap();
  assert.ok(await page.$('.eng-create-form'));
  assert.equal(current().data.parentOptions.length, 4, '增项可关联完整授权项目列表');
  assert.equal(await page.$('.eng-project-open'), null);
  h.projects([]);
  page = await h.open('/pages/build/projects/index');
  assert.equal(await page.$('.eng-project-open'), null);
  assert.equal(await page.$('.eng-project-more'), null);
  assert.equal(await page.$('button[bindtap="showProjects"]'), null);
  assert.ok(await page.$('.eng-create-entry'));
  assert.deepEqual(h.writes, []);
});

test('材料Tab与常用入口直达材料项目选择，点击项目进入同一项目的材料详情', async () => {
  const h = await setup();
  const organization = require('../miniprogram/utils/organization');
  const material = organization.navigation({ loggedIn: true, active: true, orgType: 'store', role: 'engineer' }).tabs.find(tab => tab.key === 'materials');
  assert.equal(material.url, '/pages/build/projects/index?destination=materials');
  let page = await h.open('/pages/build/home/index');
  await (await page.$('button[data-action="materials"]')).tap(); await flush();
  assert.equal(current().data.destination, 'materials');
  assert.equal(current().data.navigationIndex, 9);
  page = await h.runtime.currentPage();
  assert.equal(await (await page.$('.eng-project-open')).text(), '查看材料');
  assert.equal(await page.$('.eng-create-entry'), null);
  await (await page.$('.eng-project-open')).tap(); await flush();
  assert.equal(current().route, 'pages/build/record/index');
  assert.equal(current().id, rows[0].id);
  assert.equal(current().data.view, 'materials');
  assert.equal(current().data.error, '');
  assert.ok(current().data.p);
  assert.deepEqual(h.writes, []);
});

test('老板工程总览的四卡跳转与统计口径一致', async () => {
  const h = await setup('boss');
  for (const [action, filter] of [['projects', '全部'], ['active', '在建'], ['pending', '待开工'], ['delivery', '待交付']]) {
    const page = await h.open('/pages/engineering/index');
    assert.equal(current().data.activeCount, 1);
    await (await page.$('.eng-shortcuts button[data-action="' + action + '"]')).tap(); await flush();
    assert.equal(current().data.filter, filter);
  }
});

test('工作台显示对应岗位的待办标题，积分卡异常态仍可重试', () => {
  const files = ['pages/build/home/index.wxml', 'pages/engineering/index.wxml', 'pages/business/index.wxml', 'pages/admin/home/index.wxml'];
  for (const file of files) {
    const source = require('node:fs').readFileSync(path.resolve(__dirname, '../miniprogram', file), 'utf8');
    if (file === 'pages/engineering/index.wxml') assert.match(source, />今日待办</);
    else {
      const tag = source.match(/<todo-preview\b[^>]*\/>/)[0];
      assert.match(tag, /appearance="home"/);
      const definition = {};
      const fs = require('node:fs');
      require('node:vm').runInNewContext(fs.readFileSync(require.resolve('../miniprogram/components/todo-preview/index.js'), 'utf8'),
        {Component: value => Object.assign(definition, value)});
      const title = tag.match(/\btitle="([^"]+)"/)?.[1] || definition.properties.title.value;
      const ownerMode = file.includes('admin/home');
      if (ownerMode) assert.match(tag, /owner-mode="{{true}}"/);
      const heading = rendered('components/todo-preview/index.wxml', {appearance:'home', title, ownerMode});
      assert.ok(heading.some(node => node.text === '今日待办'), file);
    }
    assert.ok(!source.includes('platformTodos.count'), file);
  }
  for (const state of ['ready', 'suspended', 'empty', 'error']) {
    const nodes = rendered('components/point-asset-card/index.wxml', { visible: true, state });
    assert.ok(!nodes.map(content).join('').includes('按当前工作身份使用'));
    assert.equal(nodes.some(node => node.tag === 'button'), state !== 'ready');
  }
});

test('项目和材料分别记住真实筛选、搜索、展开与列表，不读取对方的迟到结果', async () => {
  const h=await setup();
  await h.open('/pages/build/projects/index');
  const page=current(),tap=workspaceBar();
  page.filter({currentTarget:{dataset:{value:'待交付'}}});
  await page.search({detail:{value:'陈家'}});
  page.toggleProjects();
  assert.deepEqual(page.data.cards.map(x=>x.id),[rows[2].id]);

  const request=wx.request, pending=[];
  wx.request=options=>options.url.endsWith('/franchise/engineering/projects')&&options.data?.keyword==='陈家'
    ?pending.push(options):request(options);
  const old=page.refreshOnline();
  tap(9);await flush();
  assert.equal(page.data.filter,'全部');assert.equal(page.data.keyword,'');assert.equal(page.data.expandedProjects,false);
  assert.deepEqual(page.data.cards.map(x=>x.id),rows.map(x=>x.id));
  pending[0].success({statusCode:200,data:{code:0,data:[rows[2]]}});await old;
  assert.deepEqual(page.data.cards.map(x=>x.id),rows.map(x=>x.id));
  await page.search({detail:{value:'王家'}});
  assert.deepEqual(page.data.cards.map(x=>x.id),[rows[1].id]);

  wx.request=request;tap(8);await flush();
  assert.equal(page.data.filter,'待交付');assert.equal(page.data.keyword,'陈家');assert.equal(page.data.expandedProjects,true);
  assert.deepEqual(page.data.cards.map(x=>x.id),[rows[2].id]);
  tap(9);await flush();
  assert.equal(page.data.filter,'全部');assert.equal(page.data.keyword,'王家');assert.equal(page.data.expandedProjects,false);
  assert.deepEqual(page.data.cards.map(x=>x.id),[rows[1].id]);
  h.projects([rows[1], {...rows[0],name:'林家新项目'}]);
  await page.search({detail:{value:'林家'}});
  assert.deepEqual(page.data.cards.map(x=>x.name),['林家新项目'],'restored panel still accepts fresh server results');
});
