const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:9946';
process.env.FRANCHISE_REAL_EVENTS = '1';
process.env.FRANCHISE_NATIVE_OUT = path.resolve('qa/management-finance-layout-20261023/native');

const ROOT = '/pages/admin/finance-desk/index';
const HOME = '/pages/management/index';
const read = file => JSON.parse(fs.readFileSync(path.resolve(file), 'utf8'));
const businessScenes = read('qa/business-module-simplification-20261021/page-scenes.json');
const engineeringScenes = read('qa/engineering-records-20261021/page-scenes.json');
const businessScene = businessScenes.filter(scene => scene.action === 'packet-receive').at(-1);
const engineeringScene = engineeringScenes.at(-1);
const seed = engineeringScene.after || engineeringScene.before;

(async () => {
  assert(businessScene?.before?.length, '缺少当前业务 page scene');
  assert(seed?.length, '缺少当前工程 page scene');
  const businessCustomer = businessScene.before.find(([key]) => key === 'zhongshu-business-v3')?.[1]?.customers?.find(row => row.id === businessScene.id);
  const seededCustomer = seed.find(([key]) => key === 'zhongshu-business-v3')?.[1]?.customers?.find(row => row.id === businessScene.id);
  assert(businessCustomer && seededCustomer, '工程 fixture 必须接续同一真实业务客户');

  const r = await require('./franchise-native-runtime.cjs').connect('finance-layout');
  const checks = [], screenshots = [], interactions = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const capture = async name => { await r.sleep(500); screenshots.push(await r.capture(name)); };
  const element = async (selector, attribute, value) => {
    let found;
    await r.until(async () => {
      r.mp.pageMap.clear();
      const page = await r.mp.currentPage(), rows = await page.$$(selector);
      for (const row of rows) {
        if (!attribute || String(await row.attribute(attribute)) === String(value)) { found = row; return true; }
      }
      return false;
    }, '原生控件 ' + selector + (attribute ? ' ' + attribute + '=' + value : ''), 12000);
    return found;
  };
  const scroll = async top => { await r.mp.evaluate(value => wx.pageScrollTo({ scrollTop: value, duration: 0 }), top); await r.sleep(180); };
  const tap = async (selector, attribute, value) => {
    let target = await element(selector, attribute, value);
    const offset = await target.offset();
    await scroll(Math.max(0, Number(offset.top) - 220));
    target = await element(selector, attribute, value);
    const text = (await target.text()).slice(0, 100);
    await target.tap();
    await r.sleep(500);
    interactions.push({ kind: 'Element.tap', selector, attribute: attribute || '', value: value || '', text });
  };
  const input = async (selector, value) => {
    const target = await element(selector);
    await target.input(value);
    await r.until(async () => String((await r.data()).keyword) === value, '搜索值绑定');
    interactions.push({ kind: 'Element.input', selector, value });
  };
  const expect = async (route, view) => r.until(async () => {
    const current = await r.current();
    return current.route === route && (!view || current.data.view === view);
  }, route + (view ? ' ' + view : ''), 15000);
  const open = async (url, selector) => {
    await r.open(url, selector);
    const data = await r.data();
    assert(!data.error, data.error);
    return data;
  };
  const noHomeNavOverlap = async name => {
    await scroll(10000);
    const [lastSection] = await r.rects(['.fin-home-progress']);
    const info = await r.mp.systemInfo();
    assert(lastSection && lastSection.bottom <= info.windowHeight - 48, '财务首页末项不能被底部导航遮挡');
    await capture(name);
    await scroll(0);
  };
  const amountVisibleAboveNav = async () => {
    let amount = await element('.fin-amount');
    await scroll(Math.max(0, Number((await amount.offset()).top) - 260));
    const [amountRect] = await r.rects(['.fin-amount']);
    const nav = await element('#finance-desk-nav');
    const bar = await nav.$('.tabbar'), navRect = await bar.offset();
    assert(amountRect && amountRect.height > 0 && amountRect.top >= 0 && amountRect.bottom < navRect.top, '金额必须完整显示在底部导航上方');
  };
  const navTo = async (index, expectedRoute, expectedView) => {
    const nav = await element('#finance-desk-nav');
    const button = await nav.$('.tab[data-index="' + index + '"]');
    assert(button, '缺少财务底部导航 ' + index);
    await button.tap();
    interactions.push({ kind: 'Element.tap', selector: '#finance-desk-nav .tab[data-index="' + index + '"]' });
    await expect(expectedRoute, expectedView);
  };
  const verifyFourTabs = async booksView => {
    for (const [index, route, view] of [[17, 'pages/admin/finance-desk/index', 'flows'], [18, 'pages/admin/finance-desk/index', booksView], [3, 'pages/management/index'], [4, 'pages/profile/index']]) {
      await open(ROOT + '?view=flows', '.fin-desk-tools');
      await navTo(index, route, view);
    }
  };

  try {
    await r.replace(seed);
    await r.login('finance-dual', 'finance');
    let home = await open(HOME, '.fin-home-intro');
    assert(home.financeDesk && !home.financeError);
    assert((await (await r.mp.currentPage()).$$('.fin-home-metric')).length === home.financeDesk.metrics.length);
    await capture('01-store-finance-home');
    await noHomeNavOverlap('01b-store-finance-home-bottom');
    pass('门店财务首页暖色五区、财务指标和底部留白原生可见');

    const metric = home.financeDesk.metrics[0];
    await tap('.fin-home-metric', 'data-index', '0');
    await expect('pages/admin/finance-desk/index', 'flows');
    assert((await r.data()).ready);
    await capture('02-store-finance-flows');
    await amountVisibleAboveNav();
    for (const tab of ['receivables', 'payables', 'ledger']) {
      await tap('.fin-segment', 'data-tab', tab);
      assert.equal((await r.data()).flowTab, tab);
    }
    await tap('.fin-segment', 'data-tab', 'payables');
    await input('.fin-search-input', '不存在的财务布局记录');
    assert.equal((await r.data()).rows.length, 0);
    await tap('.fin-clear');
    assert.equal((await r.data()).keyword, '');
    const sort = await element('.fin-filter-row picker[data-type="sort"]');
    await sort.trigger('change', { value: 1 });
    await r.until(async () => (await r.data()).sort === '金额优先', '金额优先筛选');
    interactions.push({ kind: 'Element.trigger(change)', selector: '.fin-filter-row picker[data-type="sort"]', value: 1 });
    pass('门店收支搜索、清空、三分段和排序筛选均由真实控件生效');

    await open(ROOT + '?view=flows&tab=receivables', '.fin-flow-row');
    const original = (await r.data()).rows.find(row => row.url);
    assert(original, '当前 fixture 应提供可达原单');
    await tap('.fin-outline', 'data-url', original.url);
    const target = new URL(original.url, 'http://local');
    await expect(target.pathname.slice(1), target.searchParams.get('view'));
    assert(!(await r.data()).error, (await r.data()).error);
    pass('门店财务从列表真实点击进入原单，只验证可达不重复提交支付链');

    await open(ROOT + '?view=flows', '.fin-desk-tools');
    await navTo(18, 'pages/admin/finance-desk/index', 'projects');
    await capture('03-store-finance-projects');
    assert((await r.data()).rows.some(row => row.id === businessCustomer.projectId));
    await verifyFourTabs('projects');
    pass('门店首页、收支、项目、我的四个底部导航均由原生控件点击可达');

    await r.replace(seed);
    await r.login('hq-finance-dual', 'finance');
    home = await open(HOME, '.fin-home-intro');
    assert(home.financeDesk?.hq && !home.financeError);
    await capture('04-headquarters-finance-home');
    await noHomeNavOverlap('04b-headquarters-finance-home-bottom');
    await open(ROOT + '?view=flows', '.fin-desk-tools');
    await navTo(18, 'pages/admin/finance-desk/index', 'partners');
    await capture('05-headquarters-finance-partners');
    const partner = (await r.data()).rows.find(row => row.url);
    assert(partner, '总部 fixture 应提供加盟商往来');
    await tap('.fin-outline', 'data-url', partner.url);
    await expect('pages/admin/finance-desk/index', 'partner');
    assert(!(await r.data()).error, (await r.data()).error);
    await verifyFourTabs('partners');
    pass('总部首页、收支、加盟商、我的四个底部导航及加盟商原单均真实可达');

    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(r.directory, 'results.json'), JSON.stringify({
      result: 'passed',
      scope: '当前业务与工程 page-scenes 模拟数据；微信开发者工具真实渲染与 Element.tap/input/change。仅检查布局、导航、筛选和原单可达，不重复提交付款领域链。',
      fixtures: ['qa/business-module-simplification-20261021/page-scenes.json', 'qa/engineering-records-20261021/page-scenes.json'],
      checks, screenshots, interactions, exceptions: r.errors
    }, null, 2));
  } catch (error) {
    await capture('failure').catch(() => {});
    fs.writeFileSync(path.join(r.directory, 'failure.json'), JSON.stringify({ error: error.stack, checks, screenshots, interactions, current: await r.current(), exceptions: r.errors }, null, 2));
    throw error;
  } finally {
    await r.restore();
  }
})().then(() => process.exit(0)).catch(error => { console.error(error.stack); process.exit(1); });
