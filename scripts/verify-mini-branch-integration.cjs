const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('老板入口保留当前在线客户与工程路由', async () => {
  const home = read('miniprogram/pages/admin/home/index.wxml');
  const customers = read('miniprogram/utils/sales-customers-page.js');
  assert.match(read('miniprogram/pages/customers/index.js'),/compatibility\('customers',make\)/);
  const projects = read('miniprogram/pages/build/projects/index.js');

  const business = read('miniprogram/pages/business/index.wxml');
  assert.match(business, /wx:elif="\{\{isBossBiz\}\}"[\s\S]*data-url="\/pages\/customers\/index" bindtap="openBossLink"/);
  assert.match(home, /data-url="\/pages\/build\/projects\/index"/);
  assert.match(customers, /\['boss','owner'\]\.includes\(post\.roleCode\)/);
  assert.match(customers, /customers\.page\(pageNo,50,keyword\|\|undefined\)/);
  assert.match(projects, /\['boss','owner'\]\.includes\(navCtx\.role\)/);
  assert.match(projects, /P\.api\.projects\.page\(\)/);
  const runtime = await require('./role-page-runtime.cjs').connect();
  const auth = require('../miniprogram/api/auth');
  const post = { postId: 'boss-project-route', orgId: 'test-store', orgType: 'store', roleCode: 'boss' };
  auth.saveLogin({ accessToken: 'isolated-route-test' }); auth.savePosts([post]); auth.switchPost(post.postId);
  wx.request = options => setImmediate(() => options.success({ statusCode: 200, data: { code: 0,
    data: [{ id: '9007199254740993', name: '当前在线工程', status: 'IN_PROGRESS' }] } }));
  await runtime.callWxMethod('reLaunch', { url: '/pages/build/projects/index' });
  for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve));
  const page = getCurrentPages().at(-1);
  assert.equal(page.data.readOnly, true);
  assert.equal(page.data.navigationIndex, 12);
  assert.equal(page.data.cards[0].id, '9007199254740993');
});

test('上传链使用当前B资产协议并隔离切岗迟到响应', () => {
  const finance = read('miniprogram/api/finance.js');
  const engineering = read('miniprogram/api/franchise/engineering.js');
  const business = read('miniprogram/pages/business/index.js');
  const explore = read('miniprogram/pages/workflow/explore/platform-bridge.js');

  for (const source of [finance, engineering]) {
    assert.match(source, /b-asset-upload/);
    assert.match(source, /sessionGuard\(\)/);
    assert.match(source, /BAsset\.putTicketBytes/);
  }
  assert.match(business, /sessionGeneration\(\)/);
  assert.match(explore, /platSessionStale/);
});

test('长文本布局由当前样式系统覆盖', () => {
  const tasks = read('miniprogram/pages/admin/tasks/index.wxss');
  const commission = read('miniprogram/pages/workflow/commission/index.wxss');
  const journey = read('miniprogram/pages/workflow/commission/journey.wxss');
  const app = read('miniprogram/app.wxss');

  assert.match(tasks, /\.task-copy\{flex:1;min-width:0\}/);
  assert.match(tasks, /\.source-copy\{[^}]*overflow-wrap:anywhere/);
  assert.match(tasks, /\.archive-copy\{[^}]*overflow-wrap:anywhere/);
  assert.match(tasks, /grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/);
  assert.match(commission, /\.commission-value\{[^}]*word-break:break-all/);
  assert.match(commission, /\.commission-field input,\.commission-field textarea,\.commission-input\{box-sizing:border-box;width:100%/);
  assert.match(journey, /\.journey-project-name\{overflow-wrap:anywhere\}/);
  assert.match(app, /\.page-actions\.page-actions>button\{[^}]*white-space:normal;overflow-wrap:anywhere/);
});
