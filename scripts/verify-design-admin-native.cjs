const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/design-admin-workbench-20261022/native');
const project = path.join(root, '.tools/page-actions-flow-20261022/native-project');
const endpoint = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9898';

async function fixtures() {
  execFileSync(process.execPath, ['scripts/verify-sales-offline-flow.cjs'], { cwd: root, stdio: 'pipe' });
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const scenes = JSON.parse(fs.readFileSync(path.join(root, 'qa/business-module-simplification-20261021/page-scenes.json')));
  const ready = scenes.find(scene => scene.action === 'design-dispatch');
  const install = rows => {
    wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
    for (const [key, value] of rows) wx.setStorageSync(key, value);
    A.mutate(account => A.loginDemo(account, 'hq-design-admin'));
    return wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]);
  };
  const active = install(ready.before);
  const customer = wx.getStorageSync('zhongshu-business-v3').customers.find(row => row.id === ready.id);
  const history = install(JSON.parse(fs.readFileSync(path.join(root, 'qa/business-module-simplification-20261021/completed-fixture.json'))));
  return { active, history, id: ready.id, name: customer.name };
}

async function main() {
  const source = await fixtures();
  fs.mkdirSync(directory, { recursive: true });
  if (!process.env.FRANCHISE_WS) {
    fs.mkdirSync(project, { recursive: true });
    fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), {
      recursive: true,
      filter: (from, to) => fs.statSync(from).isDirectory() || !fs.existsSync(to) || !fs.readFileSync(from).equals(fs.readFileSync(to))
    });
    const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
    config.projectname = 'design-admin-workbench-qa';
    fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
    execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', ['auto', '--project', project, '--auto-port', '9898'], { timeout: 45000, stdio: 'pipe' });
  }
  const runtime = require('./franchise-native-runtime.cjs');
  let r;
  for (let attempt = 0; !r; attempt++) {
    try { r = await runtime.connect('design-admin', { directory, wsEndpoint: endpoint, realEvents: true }); }
    catch (error) {
      if (attempt === 9) throw error;
      await runtime.sleep(500);
    }
  }
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const capture = async name => {
    // Match the official SDK navigation settle time before capturing the compositor.
    await r.sleep(3000);
    return r.capture(name);
  };
  try {
    await r.sleep(2500);
    await r.open('/pages/account/index?view=policy');
    await r.replace(source.active);
    await r.open('/pages/workflow/commission/index?view=admin', '.admin-greeting');
    let data = await r.data();
    assert.equal(data.error, '');
    assert.equal(data.metrics.length, 3);
    assert(data.tasks.some(row => row.id === source.id));
    const page = await r.mp.currentPage();
    assert.equal((await page.$$('.admin-metric')).length, 3);
    const nav = await page.$('wx-custom-tab-bar');
    assert(nav, '原生导航组件存在');
    const tabs = await nav.$$('.tab');
    assert.equal(tabs.length, 3);
    assert.deepEqual(await Promise.all(tabs.map(tab => tab.text())), ['首页', '项目', '我的']);
    await capture('home');
    pass('原生首页展示问候、三张统计、今日待办、最近办理和三 Tab');

    await (await page.$('.admin-metric[data-filter="待下发"]')).tap();
    await r.until(async () => (await r.data()).view === 'projects', 'metric opens project filter');
    data = await r.data();
    assert.equal(data.projectFilter, '待下发');
    assert(data.projectRows.some(row => row.id === source.id));
    await capture('projects-ready');
    pass('统计卡片点击进入对应项目筛选');

    const projects = await r.mp.currentPage();
    await (await projects.$('.admin-search input')).input(source.name);
    await r.until(async () => (await r.data()).keyword === source.name, 'project search');
    await (await (await projects.$('wx-custom-tab-bar')).$('.tab[data-index="19"]')).tap();
    await r.until(async () => (await r.data()).view === 'admin', 'home tab');
    await (await (await (await r.mp.currentPage()).$('wx-custom-tab-bar')).$('.tab[data-index="20"]')).tap();
    await r.until(async () => (await r.data()).view === 'projects', 'project tab');
    assert.equal((await r.data()).keyword, source.name);
    pass('原生搜索与 Tab 切换保留项目条件');

    await (await (await r.mp.currentPage()).$('.admin-project-row[data-url*="' + source.id + '"]')).tap();
    await r.until(async () => (await r.current()).route === 'pages/workflow/commission/index' && (await r.data()).view === 'progress', 'open project detail');
    assert.equal((await r.data()).customer.id, source.id);
    // Page data arrives before the native view has finished its navigation animation.
    await r.until(async () => (await r.rects(['.commission-action-panel']))[0], 'rendered detail actions');
    await r.sleep(3000);
    r.mp.pageMap?.clear();
    const actions = await (await r.mp.currentPage()).$('.commission-action-panel');
    assert(actions && !['fixed', 'sticky', 'absolute'].includes(await actions.style('position')), '详情操作随页面内容滚动');
    await capture('detail');
    pass('项目卡片打开对应原委托详情');

    await r.open('/pages/account/index?view=policy');
    await r.replace(source.history);
    await r.open('/pages/workflow/commission/index?view=projects', '.admin-filters');
    await (await (await r.mp.currentPage()).$('.admin-filters button[data-filter="已下发"]')).tap();
    data = await r.data();
    assert(data.projectRows.some(row => row.id === source.id), '移交完成的项目仍在行政历史中');
    await capture('projects-history');
    pass('已下发筛选保留后续移交完成的历史项目');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', scope: 'Local native WeChat simulator; no remote notification or real payment', project, checks, exceptions: r.errors }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'failed', checks, error: error.message, exceptions: r.errors }, null, 2));
    throw error;
  } finally {
    await r.restore();
  }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
