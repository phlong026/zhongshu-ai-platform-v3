const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'qa/owner-workbench-port-20261023/native');
const project = path.join(root, '.tools/owner-workbench-port/native-project');
const port = 9946;

(async () => {
  const seed = JSON.parse(fs.readFileSync(path.join(directory, '../preview-storage.json')));
  fs.mkdirSync(directory, { recursive: true });
  fs.rmSync(path.join(directory, 'results.json'), { force: true });
  fs.mkdirSync(project, { recursive: true });
  fs.cpSync(path.join(root, 'miniprogram'), path.join(project, 'miniprogram'), { recursive: true });
  const config = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json')));
  config.projectname = 'owner-workbench-port-qa';
  fs.writeFileSync(path.join(project, 'project.config.json'), JSON.stringify(config, null, 2));
  execFileSync(process.env.WECHAT_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli',
    ['auto', '--project', project, '--auto-port', String(port)], { timeout: 45000, stdio: 'pipe' });

  const native = require('./franchise-native-runtime.cjs');
  let r;
  for (let attempt = 0; !r; attempt++) {
    try {
      r = await native.connect('owner-port', { directory, wsEndpoint: 'ws://127.0.0.1:' + port, realEvents: true });
    } catch (error) {
      if (attempt === 4) throw error;
      await native.sleep(1500);
    }
  }
  const checks = [], screenshots = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const control = async selector => {
    let element;
    await r.until(async () => {
      r.mp.pageMap.clear();
      element = await (await r.mp.currentPage()).$(selector);
      return !!element;
    }, '原生页面控件 ' + selector, 12000);
    return element;
  };
  const capture = async name => { await r.sleep(700); screenshots.push(await r.capture(name)); };
  const open = async (page, selector) => {
    await r.open('/pages/admin/' + page + '/index', selector);
    assert(!(await r.data()).error, (await r.data()).error);
  };
  const saved = [];
  try {
    await r.open('/pages/account/index?view=policy');
    await r.replace(seed);
    await open('home', '.home-six');
    assert.equal((await r.data()).desk.home.six.length, 6);
    assert.equal((await (await r.mp.currentPage()).$$('.home-six-item')).length, 6);
    await capture('home');
    pass('首页原生显示六项经营数据、待办与业务工程概况');

    const todo = (await r.data()).homeTodos[0];
    assert(todo, '连续流程提供待办原事项');
    if ((await r.data()).homeTodoTotal > (await r.data()).homeTodos.length) {
      await (await control('[data-action="todos"]')).tap();
    } else {
      await r.open('/pages/admin/detail/index?view=todos');
    }
    await r.until(async () => (await r.data()).view === 'todos', '全部待办');
    await (await control('.owner-search input')).input(todo.name);
    await r.until(async () => (await r.data()).keyword === todo.name, '待办关键词');
    assert((await r.data()).rows.some(row => row.key === todo.key));
    assert((await r.data()).rows.every(row => (row.name + ' ' + row.status).includes(todo.name)));
    pass('全部待办通过原生搜索框筛选并保留原事项');
    await open('home', '.home-six');

    for (const [page, selector] of [['finance', '.biz-summary'], ['performance', '.perf-targets'], ['profile', '.profile-card'], ['home', '.home-six']]) {
      const navigation = await control('#owner-navigation');
      const buttons = await navigation.$$('.boss-nav-item');
      assert.deepEqual(await Promise.all(buttons.map(button => button.text())), ['首页', '经营', '绩效', '我的']);
      await (await navigation.$('[data-page="' + page + '"]')).tap();
      await r.until(async () => (await r.current()).route === 'pages/admin/' + page + '/index', '点击导航 ' + page, 16000);
      await control(selector);
      await capture(page);
    }
    pass('四个底部导航通过原生控件实际点击互通');

    await open('finance', '.biz-summary');
    const business = (await r.data()).desk.business;
    const row = [...business.receivable.rows, ...business.payable.rows][0];
    assert(row, '连续流程提供真实未收或未付的项目原单');
    {
      await (await control('.biz-row[data-key="' + row.key + '"]')).tap();
      const expected = new URL(row.url, 'http://local');
      await r.until(async () => {
        const page = await r.current();
        return page.route === expected.pathname.slice(1) && page.data.record?.id === expected.searchParams.get('id');
      }, '应收应付原单跳转', 16000);
      assert.equal((await r.data()).record.amount, row.amountCents);
      assert((await (await control('.owner-readonly-row')).text()).includes(row.sourceId));
      await capture('original-record');
      pass('经营明细按原单编号显示相同的未结金额');
    }

    await r.open('/pages/admin/performance/index?history=1', '.perf-targets');
    assert.equal((await r.data()).desk.performance.targets.length, 4);
    await r.open('/pages/admin/detail/index?view=targets');
    assert.equal(await (await r.mp.currentPage()).$('.owner-save'), null);
    pass('历史绩效指标与旧目标页保留查看且不再保存');

    await open('profile', '.profile-card');
    const performance = await control('.quick-card [data-action="performance"]');
    assert((await performance.text()).includes('绩效任务'));
    await performance.tap();
    await r.until(async () => (await r.current()).route === 'pages/admin/tasks/index' && (await r.data()).view === 'team', '我的绩效任务');
    await open('profile', '.owner-management');
    assert((await (await r.mp.currentPage()).$$('.owner-management .service-row')).length >= 4);
    await capture('profile-management');
    pass('我的复用统一模板，绩效任务与门店管理可达');
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify({ result: 'passed', checks, screenshots, saved, errors: r.errors }, null, 2));
    fs.rmSync(path.join(directory, 'failure.json'), { force: true });
  } catch (error) {
    await capture('failure').catch(() => {});
    fs.writeFileSync(path.join(directory, 'failure.json'), JSON.stringify({ error: error.stack, checks, screenshots, current: await r.current(), errors: r.errors }, null, 2));
    throw error;
  } finally {
    await r.open('/pages/account/index?view=policy');
    await r.replace(r.before);
    const norm = rows => JSON.stringify(rows.sort((a, b) => a[0].localeCompare(b[0])));
    assert.equal(norm(await r.snapshot()), norm(r.before));
    fs.writeFileSync(path.join(directory, 'storage-restored.json'), JSON.stringify({ restored: true, keys: r.before.length }));
    await r.mp.disconnect();
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
