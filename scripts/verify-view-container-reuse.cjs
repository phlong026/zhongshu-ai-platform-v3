const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// B6-3 清理计划：
// 1. 先锁定 routeValue、通用 form.input 与页面级 fail 的既有行为；
// 2. 只迁移逐字同构页面，日期选择、数组行输入、草稿保存及刷新仍由各页负责；
// 3. 用源码约束阻止同构实现回流，并保留带业务副作用的本地处理器。
const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const shared = require('../miniprogram/utils/view-container');

assert.equal(shared.routeValue('A%20B'), 'A B');
assert.equal(shared.routeValue('%E0%A4%A'), '%E0%A4%A', '坏编码应原样返回');

const calls = [];
const page = {
  setData(patch) { calls.push(patch); },
  data: { form: { checked: false } }
};
shared.makeFormHandlers().input.call(page, {
  type: 'input', currentTarget: { dataset: { key: 'name' } }, target: { dataset: {} }, detail: { value: '张三' }
});
assert.deepEqual(calls.pop(), { 'form.name': '张三', error: '' });

shared.makeFormHandlers().input.call(page, {
  type: 'change', currentTarget: { dataset: { key: 'date' } }, target: { dataset: {} }, detail: { value: '2026-10-29' }
});
assert.deepEqual(calls.pop(), { 'form.date': '2026-10-29', error: '' }, '日期 picker 保持既有 input 契约');

global.wx = { showToast(value) { calls.push(value); } };
assert.equal(shared.fail.call(page, '保存失败'), false);
assert.deepEqual(calls.shift(), { error: '保存失败', busy: false });
assert.deepEqual(calls.shift(), { title: '保存失败', icon: 'none' });
delete global.wx;

const inputPages = [
  'miniprogram/pages/build/record/index.js',
  'miniprogram/pages/growth/index.js',
  'miniprogram/pages/workflow/commission/index.js'
];
for (const file of inputPages) {
  const source = read(file);
  assert(source.includes('makeFormHandlers'), `${file} 应复用通用表单输入`);
  assert(!/input\(e\)\{this\.setData\(\{\['form\.'\+e\.currentTarget\.dataset\.key\]:e\.detail\.value,error:''\}\);\}/.test(source), `${file} 不应保留同构 input`);
}

for (const file of ['miniprogram/pages/account/index.js', 'miniprogram/pages/workflow/index.js']) {
  const source = read(file);
  assert(source.includes('fail'), `${file} 应复用通用 fail`);
  assert(!/fail\(message\)\{this\.setData\(\{error:message,busy:false\}\);wx\.showToast/.test(source), `${file} 不应保留同构 fail`);
}

const ownerSource = read('miniprogram/pages/admin/owner-page.js');
assert(ownerSource.includes("require('../../utils/view-container')"), '老板容器应复用安全路由解码');
assert(!ownerSource.includes('const routeValue = value =>'), '老板容器不应保留 routeValue 副本');

const recordSource = read('miniprogram/pages/build/record/index.js');
assert(recordSource.includes('lineInput(e)'), '数组行输入特例必须保留');
const recordTemplate = read('miniprogram/pages/build/record/index.wxml');
assert.match(recordTemplate, /<picker\b[^>]*mode="date"[^>]*bindchange="input"/, '工程办理的日期选择仍沿用通用 input');

// These legacy entries now dispatch to the active pages that own the forms.
let definition;
let token = 'test-session';
const redirects = [];
global.Page = config => { definition = config; };
global.wx = {
  redirectTo: ({url}) => redirects.push(url),
  getStorageSync: key => key === 'zs-api-token-v1' ? token : ''
};
for (const [file, cases] of [
  ['admin/index', [
    [{view:'staff'}, '/pages/account/index?view=api-members'],
    [{view:'pay'}, '/pages/admin/finance-desk/index?view=flows&tab=payables'],
    [{view:'operating'}, '/pages/admin/home/index?view=operating']
  ]],
  ['admin/operations/index', [
    [{view:'receivables'}, '/pages/admin/finance-desk/index?view=projects'],
    [{view:'payroll'}, '/pages/admin/tasks/index'],
    [{view:'costs'}, '/pages/admin/home/index?view=operating']
  ]],
  ['build/index', [
    [{view:'materials'}, '/pages/build/projects/index?destination=materials'],
    [{view:'inspection', id:'p1', nodeId:'n2'}, '/pages/build/record/index?view=node&id=p1&nodeId=n2']
  ]]
]) {
  require('../miniprogram/pages/' + file);
  for (const [options, expected] of cases) {
    definition.onLoad(options);
    assert.deepEqual(redirects.splice(0), [expected], file + ' 应转到对应办理页');
  }
  if (file === 'build/index') {
    token = '';
    definition.onLoad.call(page, {id:'p1'});
    assert.deepEqual(calls.pop(), {missing:true,error:'请先登录后端账号'});
    assert.equal(redirects.length, 0);
    token = 'test-session';
  }
}
delete global.Page;
delete global.wx;

console.log('PASS B6-3：安全解码、通用输入、失败处理已收敛，页面特例保持独立');
