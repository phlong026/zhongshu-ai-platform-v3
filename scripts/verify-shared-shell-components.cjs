const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const json = file => JSON.parse(read(file));
const filesUnder = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const file = path.join(directory, entry.name);
  return entry.isDirectory() ? filesUnder(file) : [file];
});

for (const file of filesUnder(path.join(root, 'miniprogram')).filter(file => file.endsWith('.wxml'))) {
  assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /\{\{\{/, `${path.relative(root, file)} 不得使用原生 WXML 无法编译的三花括号属性`);
}

for (const ext of ['js', 'json', 'wxml', 'wxss']) {
  assert(fs.existsSync(path.join(root, `miniprogram/components/list-row/index.${ext}`)), `list-row 缺少 ${ext}`);
}
assert.equal(json('miniprogram/components/list-row/index.json').component, true);

const row = read('miniprogram/components/list-row/index.wxml');
assert.match(row, /<slot name="title"\s*\/>/);
assert.match(row, /<slot name="detail"\s*\/>/);
assert.match(row, /<slot name="extra"\s*\/>/);
assert.match(row, /bindtap="activate"/);
assert.match(read('miniprogram/components/list-row/index.js'), /externalClasses:\s*\['custom-class'\]/);

const operations = read('miniprogram/pages/admin/operations/index.wxml');
// 切生产收尾批：operations 页已改跳转壳——list-row 页面消费断言摘除（组件契约见上）

const sharedNav = read('miniprogram/styles/subpage-navigation.wxss');
for (const className of ['subpage-shell', 'subpage-nav', 'subpage-back', 'subpage-title']) {
  assert.match(sharedNav, new RegExp(`\\.${className}\\b`), `缺少共享头部样式 ${className}`);
}
for (const file of [
  'miniprogram/components/brand-header/index.wxml',
  'miniprogram/components/business-header/index.wxml',
  'miniprogram/components/creation-header/index.wxml',
  'miniprogram/pages/admin/owner-header/index.wxml',
]) {
  const source = read(file);
  assert.match(source, /subpage-shell/);
  assert.match(source, /subpage-nav/);
  assert.match(source, /subpage-back/);
  assert.match(source, /subpage-title/);
  assert.match(source, /工作台/);
}
assert.match(read('miniprogram/components/brand-header/index.wxml'), /class="brand"/);
assert.match(read('miniprogram/components/business-header/index.wxml'), /design-flow/);
assert.match(read('miniprogram/components/creation-header/index.wxml'), /statusHeight \+ navHeight/);
assert.match(read('miniprogram/pages/admin/owner-header/index.wxml'), /wx:if="\{\{back\}\}"/);

const adminModal = read('miniprogram/pages/admin/index.wxml');
assert.match(adminModal, /<action-panel wx:if="\{\{modal&&modal.type==='preview'\}\}"/);
assert.match(adminModal, /panel="\{\{modal\}\}"/);
assert.match(adminModal, /<view wx:elif="\{\{modal\}\}" class="overlay"/);
const accountModal = read('miniprogram/pages/account/index.wxml');
assert.match(accountModal, /<action-panel wx:if="\{\{modal&&\(modal.type==='notice'/);
assert.match(accountModal, /panel="\{\{modal\}\}"/);
assert.match(accountModal, /<view wx:elif="\{\{modal\}\}" class="overlay"/);
for (const file of ['miniprogram/pages/admin/index.json', 'miniprogram/pages/account/index.json']) {
  assert(json(file).usingComponents['action-panel'], `${file} 未复用 action-panel`);
}
assert.match(read('miniprogram/components/action-panel/index.wxml'), /panel\.error/);

(async () => {
  const roleRuntime = require('./role-page-runtime.cjs');
  const flat = nodes => nodes.flatMap(node => [node, ...flat(node.children || [])]);
  const classes = node => String(node.attrs?.class || '').split(/\s+/);
  const adminFile = path.join(root, 'miniprogram/pages/admin/index.wxml');
  const adminPreview = flat(roleRuntime.render(adminFile, { modal: { type: 'preview', title: '凭据预览', text: '核对内容', src: '/assets/management-receipt.jpg' }, error: '', form: {} }));
  assert(adminPreview.some(node => node.tag === 'button' && classes(node).includes('confirm')), 'admin 预览未展开 action-panel');
  assert(adminPreview.some(node => node.tag === 'image' && classes(node).includes('hero') && node.attrs.src === '/assets/management-receipt.jpg'), 'admin modal.src 未传入 action-panel');
  assert(adminPreview.some(node => node.tag === 'text' && classes(node).includes('description') && node.children.some(child => child.text === '核对内容')), 'admin modal.text 未传入 action-panel');
  const adminConfirm = flat(roleRuntime.render(adminFile, { modal: { type: 'confirm', title: '确认登记', text: '核对原单' }, error: '', form: {} }));
  assert(adminConfirm.some(node => node.tag === 'button' && classes(node).includes('solid')), '财务确认原页面入口被改变');
  const accountFile = path.join(root, 'miniprogram/pages/account/index.wxml');
  const accountNotice = flat(roleRuntime.render(accountFile, { modal: { type: 'notice', title: '提示', text: '内容' }, error: '', form: {} }));
  assert(accountNotice.some(node => node.tag === 'button' && classes(node).includes('confirm')), 'account 通知未展开 action-panel');
  const accountLogin = flat(roleRuntime.render(accountFile, { modal: { type: 'login-demo', title: '登录', text: '选择账号' }, error: '', form: {} }));
  assert(accountLogin.some(node => node.attrs?.['data-handler'] === 'confirmModal'), '登录弹层原生验收入口被改变');

  const runtime = await roleRuntime.connect();
  // 切生产收尾批：operations 本地待办链已下线（页改跳转壳）——运行时断言段摘除，组件行为由下方 fixture 段覆盖

  const page = await runtime.mountFixture({
    file: path.join(__dirname, 'fixtures/shared-components.wxml'),
    data: {
      rowTitle: '待核对原单',
      rowMeta: '约定 1000 · 未收 300',
      selectedUrl: '',
      submitted: false,
      closed: false,
      panel: { title: '确认原单', description: '请核对对象与凭据', error: '凭据待补充', confirm: '确认' },
    },
    methods: {
      go(e) { this.setData({ selectedUrl: e.currentTarget.dataset.url }); },
      closePanel() { this.setData({ closed: true }); },
      submitPanel() { this.setData({ submitted: true }); },
    },
  });

  const rowButton = await page.$('list-row button.ops-link');
  assert(rowButton, 'list-row 没有展开外部样式类与默认插槽');
  assert.match(await rowButton.text(), /待核对原单.*约定 1000/);
  await rowButton.tap();
  assert.equal(getCurrentPages().at(-1).data.selectedUrl, '/pages/admin/index?view=todos');
  assert(await page.$('list-row[interactive="false"] view.ops-link'), '只读 list-row 被错误渲染为按钮');

  const panelText = await page.$('action-panel .panel-error');
  assert.equal(await panelText.text(), '凭据待补充');
  await (await page.$('action-panel .confirm')).tap();
  assert.equal(getCurrentPages().at(-1).data.submitted, true, 'action-panel submit 未代理到父页面');
  await (await page.$('action-panel .close')).tap();
  assert.equal(getCurrentPages().at(-1).data.closed, true, 'action-panel close 未代理到父页面');

  await runtime.disconnect();
  console.log('PASS list-row 插槽、四头部导航基线和纯确认弹层收敛');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
