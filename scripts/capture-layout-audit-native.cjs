const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { connect } = require('./franchise-native-runtime.cjs');

const phase = process.argv[2] || 'after';
assert(['before', 'after'].includes(phase), '用法: node scripts/capture-layout-audit-native.cjs before|after');

const root = path.resolve(__dirname, '..');
const out = path.join(root, 'qa/layout-unification-20261030/native', phase);
fs.mkdirSync(out, { recursive: true });
const engineeringScenes = path.join(root, 'qa/engineering-records-20261021/page-scenes.json');

const titleSelectors = [
  '.section-title', '.heading', '.eng-heading', '.owner-heading',
  '.navigation-title', '.title', '.home-title', '.creation-heading-title'
];
const controlSelectors = [
  '.primary', '.solid', '.outline', '.btn', '.text-link',
  '.customer-action', '.sales-todo-primary',
  '.eng-primary', '.eng-outline', '.owner-primary', '.owner-link',
  '.creation-primary', '.studio-primary', '.account-action', '.fin-primary',
  '.demo-user-picker', '.input', '.textarea'
];

function safeName(value) {
  return value.replace(/[^a-z0-9-]+/gi, '-').replace(/^-|-$/g, '');
}

(async () => {
  const audit = await connect('layout-' + phase, {
    directory: out,
    wsEndpoint: process.env.LAYOUT_AUDIT_WS || 'ws://127.0.0.1:9757'
  });
  const results = [];

  async function inspect(name, expected, options = {}) {
    const record = { name, expected, url: options.url || '', status: 'running' };
    try {
      if (options.url) await audit.open(options.url, options.selector || '', { relaunch: true });
      const current = await audit.current();
      const geometry = await audit.mp.evaluate(({ titleSelectors, controlSelectors }) => new Promise(resolve => {
        const selectors = [...titleSelectors, ...controlSelectors];
        const query = wx.createSelectorQuery();
        selectors.forEach(selector => query.selectAll(selector).boundingClientRect());
        query.selectViewport().scrollOffset();
        query.exec(rows => resolve({
          entries: selectors.map((selector, index) => ({ selector, rects: rows[index] || [] })),
          scroll: rows.at(-1) || {}
        }));
      }), { titleSelectors, controlSelectors });
      const info = await audit.mp.systemInfo();
      const width = info.windowWidth || info.screenWidth;
      const height = info.windowHeight || info.screenHeight;
      const controls = geometry.entries.filter(entry => controlSelectors.includes(entry.selector)).flatMap(entry => entry.rects.map(rect => ({ selector: entry.selector, ...rect })));
      const titles = geometry.entries.filter(entry => titleSelectors.includes(entry.selector)).flatMap(entry => entry.rects.map(rect => ({ selector: entry.selector, ...rect })));
      record.actual = { route: current.route, view: current.data?.view || '', role: await audit.mp.evaluate(() => wx.getStorageSync('zhongshu-account-v1')?.session?.role || 'guest') };
      record.viewport = { width, height };
      record.geometry = {
        titles,
        controls,
        offscreen: controls.filter(rect => rect.left < -1 || rect.right > width + 1),
        undersized: controls.filter(rect => rect.width > 0 && rect.height > 0 && (rect.width < 44 || rect.height < 44))
      };
      record.status = record.actual.route === expected ? 'captured' : 'redirected';
      await audit.mp.pageScrollTo(0);
      await audit.sleep(180);
      record.top = await audit.capture(safeName(name) + '-top');
      await audit.mp.pageScrollTo(100000);
      await audit.sleep(180);
      const scrolled = await audit.mp.evaluate(() => new Promise(resolve => wx.createSelectorQuery().selectViewport().scrollOffset(row => resolve(row?.scrollTop || 0)).exec()));
      if (scrolled > 30) record.bottom = await audit.capture(safeName(name) + '-bottom');
      record.scrollTop = scrolled;
    } catch (error) {
      record.status = 'failed';
      record.error = error.stack;
      try { record.actual = (await audit.current()).route; } catch (_) {}
    }
    results.push(record);
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ phase, results, exceptions: audit.errors }, null, 2));
  }

  async function inspectRedirect(name, url, expected, selector) {
    await audit.mp.evaluate(url => new Promise(resolve => wx.reLaunch({ url, complete: resolve })), url);
    await audit.until(async () => (await audit.current()).route === expected, name + ' target');
    await inspect(name, expected, { selector });
    const record = results.at(-1);
    if (record.status !== 'failed') record.status = 'redirected';
    record.redirectFrom = url;
  }

  try {
    await audit.replace([]);
    await inspect('00-public-home', 'pages/home/index', { url: '/pages/home/index', selector: '.home-page' });
    await inspect('01-login', 'pages/account/index', { url: '/pages/account/index?view=login', selector: '.account' });

    await audit.replace([]);
    await audit.login('sales-dual', 'sales');
    await inspect('02-sales-workbench', 'pages/business/index', { url: '/pages/business/index', selector: '.business-page' });
    await inspect('03-sales-customers', 'pages/customers/index', { url: '/pages/customers/index', selector: '.sales-page' });
    await inspect('04-sales-design', 'pages/design/index', { url: '/pages/design/index', selector: '.design-page' });
    await inspect('05-sales-creation', 'pages/creation/index', { url: '/pages/creation/index', selector: '.creation-page' });
    await inspect('06-sales-profile', 'pages/profile/index', { url: '/pages/profile/index', selector: '.profile-page' });
    await inspect('07-account-profile', 'pages/account/index', { url: '/pages/account/index?view=profile', selector: '.account' });
    await inspect('08-account-privacy', 'pages/account/index', { url: '/pages/account/index?view=privacy', selector: '.account' });

    await audit.replace([]);
    await audit.login('engineering-dual', 'engineer');
    await inspect('09-engineering-home', 'pages/build/home/index', { url: '/pages/build/home/index', selector: '.engineering-desk' });
    await inspect('10-engineering-projects', 'pages/build/projects/index', { url: '/pages/build/projects/index', selector: '.engineering-desk' });
    const engineering = await audit.data();
    const projectId = engineering.cards?.[0]?.id;
    if (projectId) {
      await inspect('11-engineering-project', 'pages/build/record/index', { url: '/pages/build/record/index?view=overview&id=' + encodeURIComponent(projectId), selector: '.record-page' });
      await inspect('12-engineering-form', 'pages/build/record/index', { url: '/pages/build/record/index?view=prepare&id=' + encodeURIComponent(projectId), selector: '.record-page' });
    } else if (fs.existsSync(engineeringScenes)) {
      const scene = JSON.parse(fs.readFileSync(engineeringScenes, 'utf8')).find(row => row.view === 'prepare');
      assert(scene, '工程夹具缺少开工准备状态，请先运行 npm run check:flow:all');
      await audit.replace(scene.before);
      await inspect('11-engineering-project', 'pages/build/record/index', { url: scene.url.replace('view=prepare', 'view=overview'), selector: '.record-page' });
      await inspect('12-engineering-form', 'pages/build/record/index', { url: scene.url, selector: '.record-page' });
    } else results.push({ name: '11-12-engineering-project-and-form', status: 'missing-fixture', error: '工程种子未产生可见项目' });

    await audit.replace([]);
    await audit.login('boss', 'boss');
    await inspect('13-owner-home', 'pages/admin/home/index', { url: '/pages/admin/home/index', selector: '.owner-page' });
    await inspect('14-owner-performance', 'pages/admin/performance/index', { url: '/pages/admin/performance/index?history=1', selector: '.owner-page' });
    await inspect('15-owner-settings', 'pages/admin/index', { url: '/pages/admin/index?view=company', selector: '.admin' });

    await audit.replace([]);
    await audit.login('finance-dual');
    await inspectRedirect('16-finance-workbench', '/pages/management/index', 'pages/admin/finance-desk/index', '.fin-page');
    await inspect('17-finance-desk', 'pages/admin/finance-desk/index', { url: '/pages/admin/finance-desk/index', selector: '.fin-page' });
    const partial = results.some(row => ['failed', 'missing-fixture'].includes(row.status));
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ phase, result: partial ? 'partial' : 'passed', system: await audit.mp.systemInfo(), results, exceptions: audit.errors }, null, 2));
    if (partial) process.exitCode = 1;
  } finally {
    await audit.restore();
  }
})().catch(error => {
  fs.writeFileSync(path.join(out, 'failed.json'), JSON.stringify({ phase, error: error.stack }, null, 2));
  console.error(error.stack);
  process.exitCode = 1;
});
