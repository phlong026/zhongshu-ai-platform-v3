const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { connect, render } = require('./role-page-runtime.cjs');

const flatten = nodes => nodes.flatMap(node => [node, ...flatten(node.children)]);
const hasClass = (node, name) => String(node.attrs?.class || '').split(/\s+/).includes(name);
const text = node => (node.text || '') + node.children.map(text).join('');
const flush = async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); };

test('所有已开放岗位的实际首页固定为问候、四卡、待办，切岗使用本人姓名', async () => {
  const runtime = await connect();
  const auth = require('../miniprogram/api/auth');
  const organization = require('../miniprogram/utils/organization');
  const cases = [
    ['store', 'sales', 'sales-greeting', 'sales-stat', 'platform-todos-section'],
    ['store', 'sales-lead', 'sales-greeting', 'sales-stat', 'platform-todos-section'],
    ['store', 'engineer', 'eng-hello', 'stat-value', 'eng-section'],
    ['store', 'project-manager', 'eng-hello', 'stat-value', 'eng-section'],
    ['store', 'finance', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['store', 'finance-lead', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['store', 'boss', 'owner-hello', 'home-six-item', 'owner-card'],
    ['store', 'owner', 'owner-hello', 'home-six-item', 'owner-card'],
    ['headquarters', 'boss', 'owner-hello', 'home-six-item', 'owner-card'],
    ['headquarters', 'finance', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['headquarters', 'finance-lead', 'fin-home-intro', 'fin-home-metric', 'fin-home-list'],
    ['headquarters', 'hq-design-admin', 'admin-greeting', 'admin-metric', 'admin-section']
  ];
  let nickname;
  global.wx.request = options => {
    const url = new URL(options.url).pathname.replace('/admin-api', '');
    let data = [];
    if (url === '/platform/profile/mine') data = { nickname };
    else if (url === '/platform/todos') data = { items: [], failedDomains: [], actionableCount: 0 };
    else if (url === '/franchise/commissions' || url === '/franchise/crm/customers' || url.includes('design-fees')) data = { list: [], total: 0 };
    else if (url.includes('performance')) data = { departments: {}, totals: {}, trend: [], company: {}, members: [], departmentsGoals: {} };
    else if (url.includes('growth')) data = {};
    setImmediate(() => options.success({ statusCode: 200, data: { code: 0, data } }));
  };
  for (const [orgType, roleCode, greetingClass, metricClass, todoClass] of cases) {
    nickname = orgType + '-' + roleCode;
    const post = { postId: nickname, orgId: nickname, orgName: '公司名称不能充当姓名', orgType, roleCode };
    auth.saveLogin({ accessToken: 'test-' + nickname }); auth.savePosts([post]); auth.switchPost(post.postId);
    const home = organization.navigation(organization.apiContext(post)).tabs[0];
    await runtime.callWxMethod(home.native ? 'switchTab' : 'reLaunch', { url: home.url });
    await flush();
    const page = getCurrentPages().at(-1);
    const file = path.resolve(__dirname, '../miniprogram', page.route + '.wxml');
    const nodes = flatten(render(file, page.data));
    const greeting = nodes.find(n => hasClass(n, greetingClass));
    assert.ok(greeting, nickname + ' greeting exists');
    assert.ok(text(greeting).includes(nickname), nickname + ' shows authenticated profile name');
    const metrics = nodes.filter(n => hasClass(n, metricClass));
    const topMetrics = roleCode === 'boss' || roleCode === 'owner' ? metrics.slice(0, 4) : metrics;
    assert.equal(topMetrics.length, 4, nickname + ' has four cards');
    const lastMetric = nodes.indexOf(topMetrics.at(-1));
    assert.ok(nodes.indexOf(greeting) < nodes.indexOf(topMetrics[0]), nickname + ' greets before cards');
    const todo = nodes.find(n => hasClass(n, todoClass) && /平台待办|待办事项|今日待办/.test(text(n)));
    assert.ok(todo, nickname + ' todo module exists even when empty');
    assert.ok(lastMetric < nodes.indexOf(todo), nickname + ' cards precede todos');
    if (roleCode === 'boss' || roleCode === 'owner') {
      assert.equal(page.data.desk.home.cards.length, 4);
      assert.equal(metrics.length, 4, '经营区只显示四个指标，已删除工程与财务统计');
      assert.equal(page.data.desk.home.business.metrics.length, 4);
      assert.equal(page.data.desk.home.engineering.metrics.length, 4);
      assert.ok(!text({children:nodes}).includes('工程与财务统计'));
    }
    if (roleCode.startsWith('finance')) {
      assert.equal(page.data.deskHome.metrics[3].taskMetric, true);
      assert.equal(page.data.deskHome.metrics[3].value, page.data.deskHome.taskCount);
      assert.ok(!text(topMetrics[3]).includes('¥'), 'task count is not displayed as money');
    }
  }
  // The owner's engineering tab uses a separate overview page rather than the engineer home.
  const boss={postId:'boss-engineering',orgId:'store',orgType:'store',roleCode:'boss'};
  nickname='老板工程总览';auth.saveLogin({accessToken:'boss-token'});auth.savePosts([boss]);auth.switchPost(boss.postId);
  await runtime.callWxMethod('switchTab',{url:'/pages/engineering/index'});await flush();
  const overview=getCurrentPages().at(-1);
  const nodes=flatten(render(path.resolve(__dirname,'../miniprogram/pages/engineering/index.wxml'),overview.data));
  const hello=nodes.find(n=>hasClass(n,'engineering-greeting'));
  const grid=nodes.find(n=>hasClass(n,'eng-shortcuts'));
  assert.ok(text(hello).includes(nickname));assert.equal(grid.children.filter(n=>n.tag==='button').length,4);
  const todo=nodes.find(n=>hasClass(n,'guest-todos'));
  assert.ok(nodes.indexOf(hello)<nodes.indexOf(grid)&&nodes.indexOf(grid)<nodes.indexOf(todo));
});

test('业务、老板及财务待办的加载、失败、空态保持在四卡之后', () => {
  const owner = require('../miniprogram/utils/platform-owner-home').view({ name: '王五', period: '2026-11' });
  const specs = [
    ['pages/business/index.wxml', { isSalesHome: true, salesName: '王五', commission: {}, salesShortcuts: [] }, 'sales-stat'],
    ['pages/admin/home/index.wxml', { ready: true, desk: owner, homeFinanceTab: 'operating' }, 'home-six-item'],
    ['pages/admin/finance-desk/index.wxml', { view: 'desk', ready: true, deskHome: { name: '王五', metrics: Array.from({ length: 4 }, (_, i) => ({ label: '指标' + i, value: 0 })), previewTasks: [] }, platformFinance: { status: 'loading' } }, 'fin-home-metric']
  ];
  for (const [file, data, metricClass] of specs) for (const status of ['loading', 'degraded', 'partial-empty', 'ready']) {
    const nodes = flatten(render(path.resolve(__dirname, '../miniprogram', file), { ...data, guest: false, platformTodos: { status, rows: [], count: 0 } }));
    const cards = nodes.filter(n => hasClass(n, metricClass)).slice(0, 4);
    assert.equal(cards.length, 4, file + ' keeps four cards in ' + status);
    const message = nodes.find(n => n.tag === '#text' && /待办加载中|待办暂时无法|已加载部分暂无待办|暂无今日待办|暂无平台待办|暂无待办|当前暂无需本人/.test(n.text));
    assert.ok(message, file + ' exposes ' + status);
    assert.ok(nodes.indexOf(cards[3]) < nodes.indexOf(message), file + ' state stays after cards');
  }
});
