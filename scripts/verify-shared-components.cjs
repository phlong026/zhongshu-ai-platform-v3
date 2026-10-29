const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const json = file => JSON.parse(read(file));

for (const name of ['search-box', 'todo-row', 'empty-state']) {
  for (const ext of ['js', 'json', 'wxml', 'wxss']) {
    assert(fs.existsSync(path.join(root, `miniprogram/components/${name}/index.${ext}`)), `${name} 缺少 ${ext}`);
  }
  assert.equal(json(`miniprogram/components/${name}/index.json`).component, true);
  new vm.Script(read(`miniprogram/components/${name}/index.js`), { filename: name });
}

const search = read('miniprogram/components/search-box/index.wxml');
assert.match(search, /bindinput="input"/);
assert.match(search, /bindtap="clear"/);
assert.match(search, /aria-label="\{\{ariaLabel/);

const todo = read('miniprogram/components/todo-row/index.wxml');
assert.match(todo, /bindtap="activate"/);
assert.match(todo, /\{\{title\}\}/);
assert.match(todo, /\{\{note\}\}/);

const empty = read('miniprogram/components/empty-state/index.wxml');
assert.match(empty, /\{\{message\}\}/);
assert.match(empty, /wx:if="\{\{detail\}\}"/);

const searchStyle = read('miniprogram/components/search-box/index.wxss');
assert.match(searchStyle, /height:74rpx/);
assert.match(searchStyle, /\.business\{[^}]*border:4rpx solid var\(--zs-card\)/);
assert.match(searchStyle, /\.engineering\{[^}]*gap:18rpx[^}]*border:1rpx solid #e7e5e1/);
assert.match(searchStyle, /\.management\{[^}]*margin-bottom:20rpx/);

const todoStyle = read('miniprogram/components/todo-row/index.wxss');
assert.match(todoStyle, /min-height:112rpx/);
assert.match(todoStyle, /\.todo-icon\{[^}]*width:74rpx[^}]*height:74rpx/);
assert.match(todoStyle, /\.todo-note\{[^}]*font-size:var\(--zs-font-caption\)/);

const statStyle = read('miniprogram/components/stat-grid/index.wxss');
assert.match(statStyle, /\.summary\{[^}]*padding:18rpx 0[^}]*background:#fcf9f4/);
assert.match(statStyle, /\.summary \.stat-value\{font-size:var\(--zs-font-heading\);line-height:56rpx\}/);

const migrations = [
  ['miniprogram/pages/business/index.wxml', /<search-box[^>]+bind:input="search"/],
  ['miniprogram/pages/engineering/index.wxml', /<search-box[^>]+bind:input="searchDesk"[^>]+bind:clear="clearDeskSearch"/],
  ['miniprogram/pages/engineering/index.wxml', /<todo-row[^>]+data-action="\{\{item.action\}\}"[^>]+bind:activate="openAction"/],
  ['miniprogram/pages/admin/home/index.wxml', /<empty-state[^>]+message="今日暂无审批、复核或交接待办"/],
  ['miniprogram/pages/admin/performance/index.wxml', /<empty-state[^>]+message="当前筛选暂无在岗成员"/],
];
for (const [file, pattern] of migrations) assert.match(read(file), pattern, file);

const componentRefs = [
  ['miniprogram/pages/business/index.json', ['search-box', 'empty-state']],
  ['miniprogram/pages/engineering/index.json', ['search-box', 'todo-row', 'empty-state']],
  ['miniprogram/pages/admin/home/index.json', ['empty-state']],
  ['miniprogram/pages/admin/performance/index.json', ['empty-state']],
];
for (const [file, names] of componentRefs) {
  const using = json(file).usingComponents || {};
  for (const name of names) assert(using[name], `${file} 未注册 ${name}`);
}

assert.doesNotMatch(read('miniprogram/pages/business/index.wxss'), /\.search-box\s*\{/);
assert.doesNotMatch(read('miniprogram/pages/engineering/index.wxss'), /\.guest-task\s*\{/);
assert.doesNotMatch(read('miniprogram/pages/management/index.wxss'), /\.guest-stats\s*\{/);

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const page = await runtime.mountFixture({
    file: path.join(__dirname, 'fixtures/shared-components.wxml'),
    data: { keyword: '', activated: '', filter: '', stats: [{ label: '待复核', value: 2, filter: 'review' }] },
    methods: {
      search(e) { this.setData({ keyword: e.detail.value }); },
      activate(e) { this.setData({ activated: e.currentTarget.dataset.action }); },
      openStat(e) { this.setData({ filter: e.detail.filter }); },
    },
  });

  const input = await page.$('search-box input');
  assert(input, '运行时未展开 search-box 内部 input');
  await input.input('款项复核');
  assert.equal(getCurrentPages().at(-1).data.keyword, '款项复核', '自定义组件 input 事件未代理到父页面');

  const todoButton = await page.$('todo-row button');
  assert(todoButton, '运行时未展开 todo-row 内部 button');
  assert.match(await todoButton.text(), /去办理/);
  await todoButton.tap();
  assert.equal(getCurrentPages().at(-1).data.activated, 'review', '组件宿主 data-action 未传递给父页面 handler');

  const statButton = await page.$('stat-grid button');
  assert(statButton, '运行时未按 items 属性展开 stat-grid');
  await statButton.tap();
  assert.equal(getCurrentPages().at(-1).data.filter, 'review', 'stat-grid 的 filter 未通过自定义事件返回父页面');
  assert(await page.$('stat-grid[interactive="false"] .stat-item'), 'stat-grid 只读属性没有保留非按钮语义');

  await runtime.disconnect();
  console.log('PASS 公共搜索、工程待办、纯展示空态与只读统计摘要完成收敛');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
