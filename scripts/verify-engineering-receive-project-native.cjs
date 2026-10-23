const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const out = path.resolve(process.env.FRANCHISE_NATIVE_OUT || 'qa/engineering-receive-project-20261023/native');
const nativeProject = path.resolve(process.env.ENGINEERING_RECEIVE_NATIVE_PROJECT || '.tools/engineering-receive-project-20261023/native-project');
process.env.FRANCHISE_NATIVE_OUT = out;
process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:10035';

const copy = value => JSON.parse(JSON.stringify(value));
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
function fingerprint(directory) {
  const walk = relative => fs.readdirSync(path.join(directory, relative), { withFileTypes: true })
    .flatMap(entry => entry.isDirectory() ? walk(path.posix.join(relative, entry.name)) : [path.posix.join(relative, entry.name)]);
  const files = walk('').sort(), digest = crypto.createHash('sha256');
  for (const file of files) digest.update(file + '\0' + sha256(fs.readFileSync(path.join(directory, file))) + '\n');
  return { files: files.length, sha256: digest.digest('hex') };
}

(async () => {
  const source = fingerprint(path.join(root, 'miniprogram'));
  const nativeSource = fingerprint(path.join(nativeProject, 'miniprogram'));
  assert.deepEqual(nativeSource, source, '隔离工程必须与当前小程序源码完全一致');

  const scene = require('../qa/business-module-simplification-20261021/page-scenes.json')
    .filter(row => row.action === 'packet-receive').at(-1);
  assert(scene, '缺少业务移交待接收模拟数据');
  const initial = copy(scene.before);
  const businessKey = initial.find(([key]) => key.startsWith('zhongshu-business-'))[0];
  const engineeringKey = 'zhongshu-engineering-v1';
  const engineeringRow = initial.find(([key]) => key === engineeringKey);
  if (engineeringRow) engineeringRow[1] = { version: 1, projects: [] };
  else initial.push([engineeringKey, { version: 1, projects: [] }]);
  const checks = [], screenshots = [];
  const pass = (name, evidence = {}) => {
    checks.push({ name, result: 'passed', ...evidence });
    console.log('PASS ' + name);
  };
  const save = result => fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(result, null, 2));
  const r = await require('./franchise-native-runtime.cjs').connect('engineering-receive-project', {
    directory: out,
    wsEndpoint: process.env.FRANCHISE_WS,
    realEvents: true
  });
  const storage = async key => (await r.snapshot()).find(([name]) => name === key)?.[1];
  const shot = async name => {
    screenshots.push(await r.capture(name));
  };
  async function tapHeaderWorkbench() {
    r.mp.pageMap?.clear();
    const page = await r.mp.currentPage();
    const header = await page.$('wx-business-header') || await page.$('business-header') || await page.$('wx-brand-header') || await page.$('brand-header');
    assert(header, '当前页面缺少带工作台入口的页头组件');
    const button = await header.$('.return-home');
    assert(button, '业务页头缺少工作台按钮');
    const size = await button.size();
    assert(size.width > 0 && size.height > 0, '工作台按钮不可见');
    await button.tap();
    r.interactions.push({ route: page.path, selector: 'header .return-home', expectedHandler: 'workbench', via: 'Element.tap' });
    fs.writeFileSync(path.join(out, 'engineering-receive-project-interactions.json'), JSON.stringify(r.interactions, null, 2));
    await r.sleep(350);
  }

  try {
    await r.replace(initial);
    await r.open('/pages/workflow/commission/index?view=receive&id=' + encodeURIComponent(scene.id), '.commission-actions', { relaunch: true });
    assert.equal((await r.data()).error, '');
    await r.event({ selector: 'checkbox-group', dataset: { key: 'checked' } }, 'change', { value: ['yes'] });
    await shot('01-before-receive');
    await r.event('.commission-actions .commission-primary', 'submit');
    await r.until(async () => (await r.data()).view === 'progress' && !(await r.data()).error, '接收完成回执');

    const acceptedBusiness = await storage(businessKey);
    const acceptedCustomer = acceptedBusiness.customers.find(customer => customer.id === scene.id);
    const acceptedEngineering = await storage(engineeringKey);
    const project = acceptedEngineering.projects.find(item => item.sourceCustomerId === scene.id);
    assert(project, '点击接收后、返回工作台前必须已经建立工程项目');
    assert.equal(project.projectId, acceptedCustomer.projectId);
    assert.equal(acceptedEngineering.projects.filter(item => item.projectId === project.projectId).length, 1);
    assert.deepEqual(project.documents.map(item => item.name), ['正式设计成果', '正式施工图', '线下专业审核依据', '客户确认凭据', '电子交底']);
    assert.equal(project.drawingVersion, acceptedCustomer.handover.drawingVersion);
    assert.deepEqual(project.drawing, acceptedCustomer.businessFlow.packet.drawingFile);
    pass('接收按钮完成时立即建立唯一工程项目并带入五类业务资料', {
      customerId: scene.id,
      engineeringId: project.id,
      projectId: project.projectId,
      documentCount: project.documents.length
    });
    await shot('02-accepted-receipt');

    await tapHeaderWorkbench();
    await r.until(async () => {
      const current = await r.current();
      return current.route === 'pages/build/home/index' && current.data.eng && !current.data.error;
    }, '工程工作台无错误', 12000);
    const home = await r.data();
    const prepare = home.eng.tasks.find(task => task.projectId === project.id && task.url.includes('view=prepare'));
    assert(prepare, '工程工作台必须接续开工准备待办');
    assert(!home.eng.tasks.some(task => task.id === 'handover:' + scene.id), '已接收待办必须关闭');
    await r.until(async () => (await r.rects(['.eng-thumb', '.eng-primary'])).every(Boolean), '工程工作台项目与待办完成渲染');
    await r.sleep(700);
    await shot('03-engineering-home');
    pass('点击页头工作台后无错误并接续开工准备待办', { taskTitle: prepare.title });

    await r.event('.eng-primary', 'openEngineering');
    await r.until(async () => {
      const current = await r.current();
      return current.route === 'pages/build/record/index' && current.data.view === 'prepare' && current.data.p?.id === project.id;
    }, '原项目开工准备', 12000);
    assert.equal((await r.data()).p.sourceCustomerId, scene.id);
    await r.until(async () => !!(await r.rects(['.eng-link']))[0], '开工准备查看项目资料控件完成渲染');
    await r.sleep(800);
    await shot('04-project-prepare');
    pass('点击待办打开刚接收的同一项目开工准备', { route: 'pages/build/record/index', view: 'prepare' });
    await tapHeaderWorkbench();
    await r.until(async () => (await r.current()).route === 'pages/build/home/index' && !(await r.data()).error, '开工准备返回工作台', 12000);
    pass('开工准备页可再次返回工程工作台');

    const historical = copy(await r.snapshot());
    const historicalEngineering = historical.find(([key]) => key === engineeringKey)[1];
    const historicalProject = historicalEngineering.projects.find(item => item.id === project.id);
    historicalProject.managerId = 'li';
    historicalProject.manager = '李经理';
    historicalProject.assignedEngineerId = 'li';
    historicalProject.assignedEngineerName = '李经理';
    historicalProject.siteRecords = [{ id: 'native-existing-site', nodeId: historicalProject.plan.nodes[0].id, note: '历史施工记录' }];
    historicalProject.payments = [{ id: 'native-existing-payment', cents: 12300, status: '已付款' }];
    historicalProject.collections = [{ id: 'native-existing-collection', cents: 45600, status: '已核算' }];
    const preserved = copy({
      managerId: historicalProject.managerId,
      manager: historicalProject.manager,
      assignedEngineerId: historicalProject.assignedEngineerId,
      assignedEngineerName: historicalProject.assignedEngineerName,
      siteRecords: historicalProject.siteRecords,
      payments: historicalProject.payments,
      collections: historicalProject.collections
    });
    await r.replace(historical);
    await r.open('/pages/build/home/index', '.engineering-home', { relaunch: true });
    await r.until(async () => {
      const current = await r.current();
      return current.route === 'pages/build/home/index' && current.data.eng && !current.data.error;
    }, '历史项目工作台无归属错误', 12000);
    const historicalAfter = (await storage(engineeringKey)).projects.find(item => item.id === project.id);
    for (const key of Object.keys(preserved)) assert.deepEqual(historicalAfter[key], preserved[key], key + ' 不得由业务刷新覆盖');
    assert.equal((await storage(engineeringKey)).projects.filter(item => item.projectId === project.projectId).length, 1);
    assert(!String((await r.data()).error || '').includes('工程归属与负责人不可从业务表单覆盖'));
    await shot('05-historical-project-home');
    pass('历史已接收项目负责人不一致时工作台正常且工程事实保持不变', {
      preservedManagerId: historicalAfter.managerId,
      siteRecords: historicalAfter.siteRecords.length,
      payments: historicalAfter.payments.length,
      collections: historicalAfter.collections.length
    });

    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    save({
      result: 'passed',
      scope: '隔离微信开发者工具；真实 Element.tap/trigger 完成接收、返回工作台和打开开工准备；历史项目使用明确模拟夹具。',
      sourceFingerprint: source,
      nativeSourceFingerprint: nativeSource,
      checks,
      screenshots,
      interactions: r.interactions,
      exceptions: r.errors
    });
  } catch (error) {
    save({ result: 'failed', error: error.stack, sourceFingerprint: source, nativeSourceFingerprint: nativeSource, checks, screenshots, interactions: r.interactions, exceptions: r.errors });
    try { await shot('failure'); } catch (_) {}
    throw error;
  } finally {
    await r.restore();
    const beforeStorage = path.join(out, 'engineering-receive-project-before-storage.json');
    if (fs.existsSync(beforeStorage)) fs.unlinkSync(beforeStorage);
  }
})().then(() => process.exit(0)).catch(error => {
  console.error(error.stack);
  process.exit(1);
});
