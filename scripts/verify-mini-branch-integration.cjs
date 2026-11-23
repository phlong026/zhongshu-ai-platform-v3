const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('老板入口保留当前在线客户与工程路由', async () => {
  const home = read('miniprogram/pages/admin/home/index.wxml');
  const customers = read('miniprogram/utils/sales-customers-page.js');
  assert.match(read('miniprogram/pages/customers/index.js'),/compatibility\('customers',make\)/);
  const projects = read('miniprogram/pages/build/projects/page.js');

  const business = read('miniprogram/pages/business/index.wxml');
  assert.match(business, /wx:elif="\{\{isBossBiz\}\}"[\s\S]*data-url="\/pages\/customers\/index" bindtap="openBossLink"/);
  assert.match(home, /data-url="\/pages\/build\/projects\/index"/);
  assert.match(customers, /\['boss','owner'\]\.includes\(post\.roleCode\)/);
  assert.match(customers, /customers\.page\(pageNo,50,keyword\|\|undefined,tab==='all'\?undefined:tab\.toUpperCase\(\)\)/);
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

const pairedWorkflow = () => read('.github/workflows/paired-release.yml');
function selectedPair(eventName, { frontend = '', backend = '', head = '', run = '1'.repeat(40) } = {}) {
  const source = pairedWorkflow();
  const context = { inputs: { frontend_sha: frontend, backend_sha: backend },
    github: { event_name: eventName, sha: run, event: { pull_request: { head: { sha: head } } } } };
  const selected = key => {
    const match = source.match(new RegExp('^      ' + key + ': \\$\\{\\{(.+)\\}\\}\\s*$', 'm'));
    assert.ok(match, 'the reusable workflow must receive ' + key);
    // The scalar expressions here use only string comparison and &&/||, shared with Actions.
    return vm.runInNewContext(match[1], context);
  };
  return { frontend: selected('frontend_sha'), backend: selected('backend_sha') };
}

test('默认分支手动演练提供可留空的前端完整版本输入', () => {
  const input = pairedWorkflow().match(/      frontend_sha:\n((?:        [^\n]*\n)+)/);
  assert.ok(input, 'workflow_dispatch must expose frontend_sha');
  assert.match(input[1], /required: false/);
  assert.match(input[1], /default: ''/);
  assert.match(input[1], /type: string/);
});

test('手动演练原样传递两个完整版本，前端留空时冻结本次触发版本', () => {
  const frontend = 'a'.repeat(40), backend = 'b'.repeat(40), run = 'c'.repeat(40);
  assert.deepEqual(selectedPair('workflow_dispatch', { frontend, backend, run }), { frontend, backend });
  assert.deepEqual(selectedPair('workflow_dispatch', { backend, run }), { frontend: run, backend });
  assert.deepEqual(selectedPair('workflow_dispatch', { run }), { frontend: run, backend: 'backend-main' });
  // Invalid nonempty refs must reach the pinned backend validator unchanged, never select another revision.
  for (const frontend of ['HEAD', 'deadbeef', '--upload-pack=bad', 'main\nBAD=1']) {
    assert.equal(selectedPair('workflow_dispatch', { frontend, backend, run }).frontend, frontend);
  }
});

test('自动PR和push仍冻结各自提交，不接受手动前端版本覆盖', () => {
  const frontend = 'a'.repeat(40), run = 'c'.repeat(40), head = 'd'.repeat(40);
  assert.equal(selectedPair('pull_request', { frontend, head, run }).frontend, head);
  assert.equal(selectedPair('push', { frontend, run }).frontend, run);
});

test('配对入口继续复用固定后端工作流与只读仓库权限', () => {
  const source = pairedWorkflow();
  assert.match(source, /uses: phlong026\/zhongshu-ai-platform-v3\/\.github\/workflows\/paired-release\.yml@474fba410bbca5c97e2e0c9d2bb732709c609e2a/);
  assert.match(source, /permissions:\n  contents: read/);
  assert.doesNotMatch(source, /^\s+run:/m, 'reference validation must stay in the pinned reusable workflow');
});
