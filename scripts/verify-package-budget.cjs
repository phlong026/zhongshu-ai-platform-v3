const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const verify = require('./verify-package-resources.cjs');
const ROOT = path.resolve(__dirname, '..');
const MINI = path.join(ROOT, 'miniprogram');
const LIMIT = 2 * 1024 * 1024, RESERVE = 128 * 1024;

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zs-package-budget-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  function write(file, value) {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, value);
  }
  const config = { miniprogramRoot: 'miniprogram/', packOptions: { ignore: [] } };
  const saveConfig = () => write('project.config.json', JSON.stringify(config));
  saveConfig();
  write('miniprogram/app.json', JSON.stringify({ subPackages: [{ root: 'pages/a' }, { root: 'pages/b' }] }));
  write('miniprogram/pages/a/index.wxml', '');
  write('miniprogram/pages/b/index.wxml', '');
  return { root, write, config, saveConfig };
}

test('relative image paths in either quote style resolve to their package or the main package', t => {
  const f = fixture(t);
  f.write('miniprogram/pages/a/assets/image.png', 'fixture');
  f.write('miniprogram/assets/shared.png', 'fixture');
  f.write('miniprogram/pages/a/index.wxml', `<image src='assets/image.png'/><image src="../../assets/shared.png"/>`);
  assert.equal(verify(f.root).resourceReferences, 2);
});

for (const kind of ['missing', 'excluded file', 'excluded folder', 'another package']) {
  test('relative image validation rejects ' + kind + ' even when other source files remain', t => {
    const f = fixture(t), target = kind === 'another package' ? 'pages/b/assets/image.png' : 'pages/a/assets/image.png';
    if (kind !== 'missing') f.write('miniprogram/' + target, 'fixture');
    if (kind.startsWith('excluded')) {
      f.config.packOptions.ignore.push({ type: kind === 'excluded file' ? 'file' : 'folder', value: kind === 'excluded file' ? target : 'pages/a/assets' });
      f.saveConfig();
      assert.ok(fs.existsSync(path.join(f.root, 'miniprogram', target)), 'excluded source is still archived on disk');
    }
    f.write('miniprogram/pages/a/index.wxml', `<image src="${kind === 'another package' ? '../b/assets/image.png' : 'assets/image.png'}"/>`);
    assert.throws(() => verify(f.root), kind === 'another package' ? /belongs to another package/ : /missing or excluded resource/);
  });
}

test('remote images stay external while both literal branches of a local image expression are checked', t => {
  const f = fixture(t);
  f.write('miniprogram/pages/a/index.wxml', `<image src="https://example.test/cover.png"/><image src='wxfile://temporary.png'/><image src="{{remoteUrl}}"/>`);
  assert.equal(verify(f.root).resourceReferences, 0);
  f.write('miniprogram/pages/a/assets/one.png', 'fixture');
  f.write('miniprogram/pages/a/index.wxml', `<image src="{{paid?'/pages/a/assets/one.png':'/pages/a/assets/two.png'}}"/>`);
  assert.throws(() => verify(f.root), /two\.png/);
});

for (const owner of ['main', 'pages/a']) {
  test(owner + ' keeps the agreed 128 KiB reserve after applying pack ignores', t => {
    const f = fixture(t), initial = verify(f.root), prefix = owner === 'main' ? '' : owner + '/';
    const size = LIMIT - RESERVE - initial.packages[owner];
    f.write('miniprogram/' + prefix + 'payload.bin', Buffer.alloc(size));
    f.write('miniprogram/' + prefix + 'archived.bin', Buffer.alloc(RESERVE));
    f.config.packOptions.ignore.push({ type: 'file', value: prefix + 'archived.bin' });
    f.saveConfig();
    assert.equal(verify(f.root).headroomBytes[owner], RESERVE, 'exactly the reserve is allowed; ignored archive is not counted');
    f.write('miniprogram/' + prefix + 'payload.bin', Buffer.alloc(size + 1));
    assert.throws(() => verify(f.root), /131071 bytes of headroom.*131072 bytes/);
    f.write('miniprogram/' + prefix + 'payload.bin', Buffer.alloc(LIMIT + 1));
    assert.throws(() => verify(f.root), /exceeds the 2 MiB project budget/);
  });
}

const app = require('../miniprogram/app.json');
const config = require('../project.config.json');
const ownerOf = file => app.subPackages.find(pkg => file.startsWith(pkg.root + '/'))?.root || 'main';
const included = file => !config.packOptions.ignore.some(rule => rule.type === 'file' ? rule.value === file : rule.type === 'folder' && file.startsWith(rule.value.replace(/\/$/, '') + '/'));
function images(nodes) { return nodes.flatMap(node => (node.tag === 'image' ? [node.attrs.src] : []).concat(images(node.children || []))); }
function checkImages(file, sources, expectedOwner) {
  assert.ok(sources.length, file + ': the tested branch must render images');
  return sources.map(src => {
    assert.equal(typeof src, 'string', file + ': image source is present');
    const target = src.startsWith('/') ? src.slice(1) : path.posix.normalize(path.posix.join(path.posix.dirname(file), src));
    assert.ok(fs.existsSync(path.join(MINI, target)), file + ': missing ' + target);
    assert.ok(included(target), file + ': excluded from upload ' + target);
    assert.ok(ownerOf(target) === 'main' || ownerOf(target) === expectedOwner, file + ': cross-package image ' + target);
    return target;
  });
}

test('real guest, sales creation and engineering menus render included images without preloading another package', async () => {
  const { connect, render } = require('./role-page-runtime.cjs');
  const runtime = await connect();
  wx.loadSubpackage = () => { throw Error('Images cannot require preloading another subpackage'); };
  const guest = getCurrentPages().at(-1);
  const guestImages = checkImages('pages/home/index.wxml', images(render(guest.file, guest.data)), 'main');
  assert.ok(guestImages.includes('assets/home-engineer-icon.png'), 'dynamic engineering icon stays packed');
  assert.ok(guestImages.includes('assets/home-chart-icon.png'), 'dynamic management icon stays packed');
  const auth = require('../miniprogram/api/auth');
  auth.saveLogin({ accessToken: 'package-fixture', userId: 7 });
  auth.savePosts([{ postId: 11, userId: 7, orgId: 31, orgType: 'store', roleCode: 'engineer' }]);
  auth.switchPost(11);
  wx.request = () => {}; // Keep initial API loading state; the banner does not depend on API content.
  await runtime.callWxMethod('reLaunch', { url: '/pages/build/home/index' });
  const engineering = getCurrentPages().at(-1);
  assert.equal(engineering.route, 'pages/build/home/index');
  const rendered = checkImages('pages/build/home/index.wxml', images(render(engineering.file, engineering.data)), 'pages/build');
  assert.ok(rendered.includes('pages/build/assets/materials-banner.jpg'));
  assert.ok(!rendered.includes('assets/materials-banner.jpg'));
  auth.savePosts([{ postId: 22, userId: 7, orgId: 31, orgType: 'store', roleCode: 'sales' }]);
  auth.switchPost(22);
  await runtime.callWxMethod('reLaunch', { url: '/pages/home/index' });
  const sales = getCurrentPages().at(-1);
  assert.equal(sales.data.salesNative, true);
  // The deterministic renderer does not expand <include>; use the real included template with the page's real data.
  const salesTemplate = 'templates/sales-creation.wxml';
  const shortcuts = checkImages(salesTemplate, images(render(path.join(MINI, salesTemplate), sales.data)), 'main');
  for (const name of ['folder', 'works', 'tasks']) assert.ok(shortcuts.includes('assets/creation-' + name + '.png'));
  assert.ok(!shortcuts.includes('assets/creation-publish.png'), 'retired shortcut is not generated by the real page');
});

test('both simulated payment channels and legacy journey templates use the included shared payment images', t => {
  const { render } = require('./role-page-runtime.cjs');
  const f = fixture(t), wrapper = path.join(f.root, 'journey.wxml');
  f.write('journey.wxml', `<import src="${path.join(MINI, 'pages/workflow/commission/journey.wxml')}"/><template is="journey" data="{{view,journey,form,canPay,busy,generating}}"/>`);
  const paymentFile = 'pages/workflow/commission/simulated-payment.wxml';
  const cashierFile = 'pages/workflow/commission/simulated-cashier.wxml';
  const shared = ['pages/workflow/assets/commission/alipay.png', 'pages/workflow/assets/commission/wechat-pay.png'];
  for (const channel of ['ALIPAY', 'WECHAT']) {
    const data = { view: 'pay', primary: '登记付款', canPay: true, busy: false, generating: false, paymentDetails: false,
      paymentChannel: channel, simulationPhase: '', simulationUncertain: false, paymentNotice: '',
      paymentOrder: { status: 'CREATED', customerName: '本次客户', amountText: '¥1,000.00' },
      journey: { name: '本次客户', city: '本市', amount: '1,000.00', steps: [], files: [] }, form: { channel: channel.toLowerCase() } };
    assert.deepEqual(checkImages(paymentFile, images(render(path.join(MINI, paymentFile), data)), 'pages/workflow').sort(), shared);
    const cashier = checkImages(cashierFile, images(render(path.join(MINI, cashierFile), { ...data, simulationPhase: 'confirm' })), 'pages/workflow');
    assert.deepEqual(cashier, [shared[channel === 'ALIPAY' ? 0 : 1]]);
    assert.deepEqual(checkImages('pages/workflow/commission/journey.wxml', images(render(wrapper, data)), 'pages/workflow').sort(), shared);
  }
});

test('retired local reference-image cache remains stored while the old case entry follows the authorized API route', async () => {
  const { connect, render } = require('./role-page-runtime.cjs');
  const runtime = await connect(), auth = require('../miniprogram/api/auth');
  const businessKey = require('../miniprogram/utils/storage-keys').business;
  const reference = '/pages/workflow/assets/biz-reference-plan.jpg';
  const old = { version: 1, selected: 'archived-customer', customers: [{ id: 'archived-customer', plans: [{ sample: true, images: [reference] }] }] };
  wx.setStorageSync(businessKey, old);
  auth.saveLogin({ accessToken: 'package-legacy-cache', userId: 7 });
  auth.savePosts([{ postId: 11, userId: 7, orgId: 31, orgType: 'store', roleCode: 'sales' }]);
  auth.switchPost(11);
  const requests = [];
  wx.request = options => requests.push(options);
  await runtime.callWxMethod('reLaunch', { url: '/pages/workflow/explore/index?view=case&id=55' });
  const page = getCurrentPages().at(-1);
  assert.equal(page.route, 'design-migration/pages/library/detail');
  assert.ok(requests.some(request => request.url.endsWith('/franchise/design/flow')
    && request.data.method === 'GET' && request.data.path === '/cases/55'), 'the migrated page actually requests the API case through the existing design adapter');
  assert.ok(!images(render(page.file, page.data)).includes(reference));
  assert.ok(!JSON.stringify(page.data).includes('archived-customer'));
  assert.deepEqual(wx.getStorageSync(businessKey), old, 'package cleanup does not rewrite customer history');
  assert.equal(included(reference.slice(1)), false);
  assert.ok(fs.existsSync(path.join(MINI, reference)), 'original reference image stays archived in the repository');
});
