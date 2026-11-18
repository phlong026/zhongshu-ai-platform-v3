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
  const runtime = await connect();
  const auth = require('../miniprogram/api/auth');
  const post = { postId: 'workbench-ui-' + role, orgId: 'ui-test', orgType: 'store', roleCode: role };
  auth.saveLogin({ accessToken: 'isolated-ui-test' }); auth.savePosts([post]); auth.switchPost(post.postId);
  let projects = rows;
  const writes = [];
  const filingRequests = [];
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
    else if (url === '/franchise/engineering/projects') data = projects;
    else if (/\/franchise\/engineering\/\d+$/.test(url)) data = rows.find(row => url.endsWith('/' + row.id));
    else if (url.endsWith('/materials/summary')) data = { plans: [] };
    else if (url.includes('/collections')) data = { stages: [], records: [] };
    else if (url.includes('/settle')) data = {};
    if (options.method !== 'GET') writes.push(options.url);
    setImmediate(() => options.success({ statusCode: 200, data: { code: 0, data } }));
  };
  return { runtime, writes, filingRequests, projects(value) { projects = value; }, async open(url) { await runtime.callWxMethod('reLaunch', { url }); await flush(); return runtime.currentPage(); } };
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
      const heading = rendered('components/todo-preview/index.wxml', {appearance:'home', title});
      assert.ok(heading.some(node => node.text === (file.includes('admin/home') ? '待我决策' : '今日待办')), file);
    }
    assert.ok(!source.includes('platformTodos.count'), file);
  }
  for (const state of ['ready', 'suspended', 'empty', 'error']) {
    const nodes = rendered('components/point-asset-card/index.wxml', { visible: true, state });
    assert.ok(!nodes.map(content).join('').includes('按当前工作身份使用'));
    assert.equal(nodes.some(node => node.tag === 'button'), state !== 'ready');
  }
});
