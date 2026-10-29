const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const phase = process.env.FOOTER_PHASE || 'after';
const directory = path.join(root, 'qa/page-actions-flow-20261022', phase);
const project = path.join(root, '.tools/page-actions-flow-20261022/native-project');
const endpoint = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9898';
const clone = value => JSON.parse(JSON.stringify(value));

async function scenes() {
  // Build fixtures through the existing business tests, including permissions and payment gates.
  for (const script of ['verify-sales-offline-flow.cjs', 'verify-engineering-records.cjs']) {
    execFileSync(process.execPath, [path.join(__dirname, script)], { cwd: root, stdio: 'pipe' });
  }
  const commission = require('../qa/business-module-simplification-20261021/page-scenes.json');
  const engineering = require('../qa/engineering-records-20261021/page-scenes.json');
  const fixtures = await require('./audit-fixtures.cjs')();
  const views = { 'commission-submit': 'submit', 'design-complete': 'complete', 'design-dispatch': 'dispatch', 'packet-submit': 'handover', 'packet-return': 'receive', 'packet-receive': 'receive' };
  const rows = commission.map((scene, index) => ({
    name: 'commission-' + index + '-' + views[scene.action], rows: scene.before,
    url: '/pages/workflow/commission/index?view=' + views[scene.action] + '&id=' + scene.id,
    bar: '.commission-action-panel', content: '.commission-field'
  }));
  for (const [index, scene] of engineering.filter(scene => ['prepare', 'start', 'materials', 'delivery'].includes(scene.view)).entries()) {
    rows.push({ name: 'record-' + index + '-' + scene.view, rows: scene.before, url: scene.url, bar: '.page-actions' });
  }
  const sales = fixtures.rowsByRole['sales-lead'];
  rows.push(
    { name: 'customer', rows: sales, url: '/pages/workflow/index?view=customer&id=' + fixtures.customerId, bar: '.page-actions' },
    { name: 'account-login', rows: [], url: '/pages/account/index?view=login', bar: '.page-actions' },
    { name: 'account-help', rows: sales, url: '/pages/account/index?view=help', bar: '.page-actions' },
    { name: 'build-new', rows: fixtures.rowsByRole['project-manager'], url: '/pages/build/index?view=new', bar: '.page-actions', content: '.body' },
    { name: 'studio-remix', rows: sales, url: '/pages/studio/index?view=remix', bar: '.page-actions' },
    { name: 'ai-video', rows: sales, url: '/pages/ai-video/index', bar: '.page-actions' },
    { name: 'growth-capture', rows: sales, url: '/pages/growth/index?view=capture', bar: '.page-actions', content: '.content' }
  );
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  for (const [key, value] of clone(sales)) wx.setStorageSync(key, value);
  require('../miniprogram/pages/workflow/design-session').estimate();
  rows.push({ name: 'budget-result', rows: wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]), url: '/pages/workflow/explore/index?view=budget-result', bar: '.budget-action-bar', content: '.budget-result-surface .card' });
  wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
  for (const [key, value] of clone(commission[0].before)) wx.setStorageSync(key, value);
  require('../miniprogram/utils/sales-flow').run(commission[0].id, commission[0].action, commission[0].input);
  const account = require('../miniprogram/utils/account');
  const state = account.load();
  require('../miniprogram/utils/demo-users').login(state, 'finance-dual');
  wx.setStorageSync(account.KEY, state);
  const payable = require('../miniprogram/pages/admin/model').load().payables.find(item => item.source?.id === commission[0].id);
  assert(payable, 'Fixture has a payable for the submitted commission');
  rows.push({ name: 'finance-pay', rows: wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]), url: '/pages/admin/index?view=pay&id=' + payable.id, bar: '.page-actions' });
  return rows;
}

// Fixed or sticky bars either overlap preceding content or fail to move by the scroll distance.
function measure({ bar, content }) {
  return new Promise(resolve => {
    const q = wx.createSelectorQuery();
    q.selectAll(bar).boundingClientRect();
    const buttons = ['btn', 'solid', 'outline', 'primary', 'studio-primary', 'studio-outline', 'creation-primary', 'eng-primary', 'eng-outline', 'commission-primary', 'commission-secondary'];
    q.selectAll(buttons.map(name => bar + ' .' + name).join(',')).boundingClientRect();
    q.selectAll(content || '.no-footer-content-anchor').boundingClientRect();
    q.selectViewport().scrollOffset();
    q.exec(([bars, buttons, contents, viewport]) => resolve(JSON.stringify({ bars, buttons, contents, scrollTop: viewport.scrollTop, window: wx.getWindowInfo() })));
  });
}

async function main() {
  const targets = await scenes();
  fs.mkdirSync(directory, { recursive: true });
  if (!process.env.FRANCHISE_WS) {
    fs.mkdirSync(project, { recursive: true });
    fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), {
      recursive: true,
      // Avoid triggering recompilation for every unchanged asset in an already-open project.
      filter: (source, target) => fs.statSync(source).isDirectory() || !fs.existsSync(target) || !fs.readFileSync(source).equals(fs.readFileSync(target))
    });
    const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
    config.projectname = 'page-actions-flow-qa';
    fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
    execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', ['auto', '--project', project, '--auto-port', '9898'], { timeout: 45000, stdio: 'pipe' });
  }
  const runtime = require('./franchise-native-runtime.cjs');
  let r;
  for (let attempt = 0; !r; attempt++) {
    try { r = await runtime.connect('footer-flow', { directory, wsEndpoint: endpoint }); }
    catch (error) {
      if (attempt === 9) throw error;
      await runtime.sleep(500);
    }
  }
  const checks = [];
  try {
    await r.sleep(2500);
    for (const target of targets) {
      console.log('OPEN ' + target.name);
      await r.open('/pages/account/index?view=policy');
      await r.replace(target.rows);
      await r.open(target.url, target.bar);
      const page = await r.mp.currentPage();
      const position = await (await page.$(target.bar)).style('position');
      const before = JSON.parse(await r.mp.evaluate(measure, { bar: target.bar, content: target.content }));
      await r.capture(target.name + '-top');
      assert.equal(before.bars.length, 1, target.name + ': one action area');
      const bar = before.bars[0];
      assert(!['fixed', 'sticky', 'absolute'].includes(position), target.name + ': actions must follow page content, actual position=' + position);
      if (before.contents.length) assert(bar.top >= before.contents.at(-1).bottom - 1, target.name + ': actions must not overlap the last form field');
      await r.mp.evaluate(() => new Promise(resolve => wx.pageScrollTo({ scrollTop: 99999, duration: 0, complete: () => setTimeout(resolve, 200) })));
      const after = JSON.parse(await r.mp.evaluate(measure, { bar: target.bar, content: target.content }));
      const moved = after.scrollTop - before.scrollTop;
      assert(Math.abs(bar.top - after.bars[0].top - moved) < 2, target.name + ': actions scroll with content');
      assert(after.bars[0].bottom <= after.window.safeArea.bottom + 1, target.name + ': actions clear the home indicator');
      assert(after.bars[0].top >= 0, target.name + ': actions can be scrolled into view');
      assert(after.buttons.length > 0, target.name + ': visible actions');
      for (const button of after.buttons) {
        assert(button.width >= 44 && button.height >= after.window.windowWidth * 88 / 750 - 2, target.name + ': usable button size');
        assert(button.left >= 12 && button.right <= after.window.windowWidth - 12, target.name + ': horizontal inset');
      }
      await r.capture(target.name + '-bottom');
      checks.push({ name: target.name, url: target.url, position, before, after });
      console.log('PASS ' + target.name + ': scroll=' + moved + 'px');
    }
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', project, checks, exceptions: r.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'failed', project, checks, error: error.message, exceptions: r.errors }, null, 2));
    throw error;
  } finally {
    await r.restore();
  }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
