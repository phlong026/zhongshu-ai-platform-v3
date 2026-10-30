const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { connect } = require('./franchise-native-runtime.cjs');
const directory = path.resolve('qa/owner-workspace-reuse-20261030/native');
fs.mkdirSync(directory, { recursive: true });

(async () => {
  const rows = JSON.parse(fs.readFileSync('qa/owner-workspace-reuse-20261030/native-fixture.json', 'utf8'));
  const r = await connect('owner-reuse', { directory, wsEndpoint: process.env.OWNER_REUSE_WS || 'ws://127.0.0.1:10065' });
  const results = [];
  async function capture(name) {
    const route = (await r.current()).route;
    const root = { 'pages/customers/index': '.customers-page', 'pages/build/projects/index': '.projects-page', 'pages/build/materials/index': '.materials-page' }[route];
    if (root) await r.until(async () => (await r.rects([root]))[0]?.height > 0, '目标页面已完成原生渲染');
    // Navigation updates Page.route before the outgoing native webview finishes its transition.
    await r.sleep(450);
    await r.mp.pageScrollTo(0);
    results.push({ name, route: (await r.current()).route, top: await r.capture(name + '-top') });
    await r.mp.pageScrollTo(100000);
    await r.sleep(120);
    await r.capture(name + '-bottom');
  }
  try {
    await r.replace(rows);
    await r.open('/pages/admin/profile/index', '.profile-page');
    const profile = await r.data();
    assert.deepEqual(profile.managementEntries.map(row => row.text), ['成员与权限', '审批设置', '我的规矩', '门店资料', '操作记录']);
    assert(!profile.entries.some(row => row.text === '管理模拟联调'));
    await capture('01-owner-profile');
    await r.open('/pages/admin/home/index', '.owner-page');
    assert.equal((await r.rects(['.home-ledger-links']))[0], null);
    await capture('02-owner-home');
    await r.open('/pages/business/index', '.boss-biz-content');
    const workbenchDue = (await r.data()).bossBiz.due;
    await capture('08-owner-business');
    await r.event('.boss-biz-entry', 'openBossLink');
    await r.until(async () => (await r.current()).route === 'pages/customers/index', '老板客户入口停留在客户页');
    let data = await r.data();
    assert(data.customers.some(row => row.id === 'owner-visible-customer'));
    assert(!data.customers.some(row => row.id === 'foreign-customer'));
    assert.equal(data.navigationIndex, 11);
    assert.equal(data.canRegister, false);
    const overdue = data.customers.find(row => row.id === 'owner-visible-customer');
    assert.equal(overdue.cue, '逾期 1 天');
    assert.equal(overdue.cueTone, 'tone-danger');
    assert.equal(overdue.canFollow, false);
    assert.equal(workbenchDue, data.tabs.find(tab => tab.key === 'due').count);
    assert.equal(data.tabs.find(tab => tab.key === 'due').dot, true);
    await capture('03-owner-customers');
    await r.mp.evaluate(() => getCurrentPages().at(-1).selectTab({ currentTarget: { dataset: { tab: 'due' } } }));
    await capture('09-owner-overdue');
    await r.event({ selector: '.customer-action', dataset: { id: 'owner-visible-customer' } }, 'act');
    await r.until(async () => (await r.current()).route === 'pages/workflow/index', '查看客户原跟进记录');
    assert.equal((await r.data()).view, 'customer');
    await capture('04-owner-customer-record');
    await r.open('/pages/engineering/index', '.engineering-page');
    await r.event({ selector: '.guest-section-link', dataset: { action: 'projects' } }, 'openAction');
    await r.until(async () => (await r.current()).route === 'pages/build/projects/index', '复用全部工程项目');
    data = await r.data();
    assert(data.cards.some(row => row.id === 'owner-visible-engineering'));
    assert(!data.cards.some(row => row.id === 'foreign-engineering'));
    assert.equal(data.navigationIndex, 12);
    assert.equal(data.canCreate, false);
    await capture('05-owner-projects');
    await r.open('/pages/engineering/index', '.engineering-page');
    assert((await r.rects(['.guest-shortcut-grid']))[0], '工程快捷操作已原生渲染');
    await r.mp.evaluate(() => getCurrentPages().at(-1).openAction({ currentTarget: { dataset: { action: 'materials' } } }));
    await r.until(async () => (await r.current()).route === 'pages/build/materials/index', '复用工程材料页面');
    data = await r.data();
    assert.equal(data.navigationIndex, 12);
    assert.equal(data.canEdit, false);
    assert((data.groups || []).every(row => !row.canPlan));
    await capture('06-owner-materials');
    await r.open('/pages/workflow/commission/index?view=policy', '.commission-page');
    await capture('07-design-policy');
    assert.equal(r.errors.length, 0, '原生页面无运行异常');
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', system: await r.mp.systemInfo(), results, exceptions: r.errors }, null, 2));
    console.log('PASS 老板原生入口清理、客户记录/项目/材料复用与本店只读边界');
  } catch (error) {
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error: error.stack, current: await r.current(), results, exceptions: r.errors }, null, 2));
    await r.capture('failure');
    throw error;
  } finally { await r.restore(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
