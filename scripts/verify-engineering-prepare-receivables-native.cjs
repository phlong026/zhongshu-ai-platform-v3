const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const mode = process.argv.includes('--baseline') ? 'baseline' : 'final';
const root = path.resolve(__dirname, '..');
const out = path.resolve(process.env.FRANCHISE_NATIVE_OUT || 'qa/engineering-prepare-receivables-20261023/native');
const nativeProject = path.resolve(process.env.ENGINEERING_PREPARE_NATIVE_PROJECT || '.tools/engineering-prepare-receivables-20261023/native-project');
process.env.FRANCHISE_NATIVE_OUT = out;
process.env.FRANCHISE_WS = process.env.FRANCHISE_WS || 'ws://127.0.0.1:10036';

const copy = value => JSON.parse(JSON.stringify(value));
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
function fingerprint(directory) {
  const walk = relative => fs.readdirSync(path.join(directory, relative), { withFileTypes: true })
    .flatMap(entry => entry.isDirectory() ? walk(path.posix.join(relative, entry.name)) : [path.posix.join(relative, entry.name)]);
  const files = walk('').sort(), digest = crypto.createHash('sha256');
  for (const file of files) digest.update(file + '\0' + sha256(fs.readFileSync(path.join(directory, file))) + '\n');
  return { files: files.length, sha256: digest.digest('hex') };
}

async function baseline() {
  const source = fingerprint(path.join(nativeProject, 'miniprogram'));
  const scenes = require('../qa/engineering-records-20261021/page-scenes.json');
  const prepare = scenes.find(scene => scene.action === 'prepare');
  assert(prepare, '缺少开工准备完成后的模拟夹具');
  const fixture = copy(prepare.after);
  const engineeringId = new URL(prepare.url, 'http://local').searchParams.get('id');
  const project = fixture.find(([key]) => key === 'zhongshu-engineering-v1')[1].projects.find(item => item.id === engineeringId);
  assert(project?.recordFlow?.contract, '开工准备夹具缺少待审批合同分期');
  const checks = [], screenshots = [];
  const r = await require('./franchise-native-runtime.cjs').connect('engineering-prepare-receivables-baseline', {
    directory: out,
    wsEndpoint: process.env.FRANCHISE_WS,
    realEvents: true
  });
  const shot = async name => {
    await r.mp.evaluate(() => { wx.hideToast(); return true; });
    await r.sleep(700);
    screenshots.push(await r.capture(name));
  };
  try {
    await r.replace(fixture);
    await r.login('boss', 'boss');
    await r.open('/pages/admin/operations/index?view=receivables&id=' + encodeURIComponent(project.projectId), '.owner-card', { relaunch: true });
    await r.until(async () => {
      const current = await r.current();
      return current.route === 'pages/admin/operations/index' && current.data.view === 'receivables' && current.data.selected?.id === project.projectId && current.data.plan?.status === 'pending' && !current.data.error;
    }, '改版前合同应收待老板审批页', 12000);
    await shot('before-receivables-first-screen');
    r.mp.pageMap?.clear();
    const page = await r.mp.currentPage(), approve = await page.$('[data-action="plan-approve"]');
    assert(approve, '改版前页面缺少本版约定审批按钮');
    const offset = await approve.offset();
    await r.mp.evaluate(top => { wx.pageScrollTo({ scrollTop: Math.max(0, top - 500), duration: 0 }); return true; }, offset.top);
    await r.sleep(800);
    await shot('before-receivables-approval');
    const data = await r.data();
    checks.push({
      name: '改版前合同应收首屏与一次审批区只读基线',
      result: 'passed',
      projectId: project.projectId,
      planVersion: data.plan.version,
      planStatus: data.plan.status,
      installmentCount: data.installments.length,
      approvalControl: 'plan-approve'
    });
    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(out, 'baseline-results.json'), JSON.stringify({
      result: 'passed',
      scope: 'd94bdc3 改版前隔离源码；真实登录老板岗位并只读查看合同应收，未执行审批或业务修改。',
      sourceCommit: 'd94bdc30f06c4795e88c951f2d3bbb87dc4fa66e',
      sourceFingerprint: source,
      checks,
      screenshots,
      interactions: r.interactions,
      exceptions: r.errors
    }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(out, 'baseline-results.json'), JSON.stringify({ result: 'failed', error: error.stack, sourceFingerprint: source, checks, screenshots, interactions: r.interactions, exceptions: r.errors }, null, 2));
    try { await shot('before-failure'); } catch (_) {}
    throw error;
  } finally {
    await r.restore();
    const beforeStorage = path.join(out, 'engineering-prepare-receivables-baseline-before-storage.json');
    if (fs.existsSync(beforeStorage)) fs.unlinkSync(beforeStorage);
  }
}

function store(rows, key) {
  return rows.find(([name]) => name === key)?.[1];
}

function projectFrom(rows, engineeringId) {
  return store(rows, 'zhongshu-engineering-v1')?.projects?.find(project => project.id === engineeringId);
}

function financeBook(rows, companyId) {
  const raw = store(rows, 'zhongshu-management-v1');
  return raw?.companyId === companyId ? raw : raw?.books?.[companyId];
}

async function final() {
  const source = fingerprint(path.join(nativeProject, 'miniprogram'));
  const scenes = require('../qa/engineering-records-20261021/page-scenes.json');
  const prepare = scenes.find(scene => scene.action === 'prepare');
  assert(prepare, '缺少开工准备模拟夹具');
  const fixture = copy(prepare.before);
  const engineeringId = new URL(prepare.url, 'http://local').searchParams.get('id');
  const initialProject = projectFrom(fixture, engineeringId);
  assert(initialProject?.recordFlow?.version === 1, '开工准备夹具缺少单人工程项目');
  assert(!initialProject.recordFlow.contract && !initialProject.recordFlow.prepared, '开工准备夹具必须是未填写状态');
  const projectId = initialProject.projectId;
  const checks = [], screenshots = [];
  const r = await require('./franchise-native-runtime.cjs').connect('engineering-prepare-receivables-final', {
    directory: out,
    wsEndpoint: process.env.FRANCHISE_WS,
    realEvents: true
  });
  const shot = async name => {
    await r.mp.evaluate(() => { wx.hideToast(); return true; });
    await r.sleep(700);
    screenshots.push(await r.capture(name));
  };
  const input = (key, value, selector = 'input') => r.event({ selector, dataset: { key } }, 'input', { value });
  const sample = () => r.event({ selector: 'button', dataset: { sampleKey: 'file' } }, 'useSampleFile');
  const action = name => r.event({ selector: 'button', dataset: { action: name } }, 'perform');
  const login = async (id, role) => {
    for (let attempt = 1; attempt <= 3; attempt++) {
      try { return await r.login(id, role); }
      catch (error) {
        if (attempt === 3 || !/SDK rendered control \.agreement-row \.check-row|Not ready: final login destination|redirectTo:fail timeout/.test(error.message)) throw error;
        await r.sleep(700);
      }
    }
  };
  const waitView = (view, label = view) => r.until(async () => {
    const current = await r.current();
    return current.route === 'pages/build/record/index' && current.data.view === view && current.data.p?.id === engineeringId && !current.data.error;
  }, label, 12000);
  const financeTasks = async () => {
    const data = await r.data();
    return (data.financeDesk?.tasks || data.financeDesk?.previewTasks || []).filter(task => task.view === 'engineering-collection' && task.id.startsWith(engineeringId + ':collection:'));
  };
  const openFinanceTask = async task => {
    const data = await r.data();
    if ((data.financeDesk?.previewTasks || []).some(row => row.url === task.url)) {
      await r.event({ selector: '.fin-task', dataset: { url: task.url } }, 'financeGo');
    } else {
      await r.event({ selector: '.fin-text', dataset: { url: data.financeDesk.tasksUrl } }, 'financeGo');
      await r.until(async () => (await r.current()).route === 'pages/admin/finance-desk/index' && (await r.data()).view === 'tasks', '财务全部待办页', 12000);
      await r.event({ selector: '.fin-task', dataset: { url: task.url } }, 'go');
    }
  };
  const collect = async transaction => {
    await input('amount', '30000');
    await r.event({ selector: 'picker', dataset: { key: 'date' } }, 'change', { value: '2026-10-23' });
    await input('receiver', '本公司');
    await input('transaction', transaction);
    await sample();
    const data = await r.data();
    assert.equal(data.form.file?.meta?.version, '工程合同V1', '工程收款模拟凭据应关联本版工程合同');
    await action('collect');
    await r.until(async () => (await r.current()).data.view === 'payments' && !(await r.current()).data.error, '工程收款登记成功', 12000);
  };
  try {
    await r.replace(fixture);
    await login('engineering-dual', 'engineer');
    await r.open(prepare.url, '.record-page', { relaunch: true });
    await waitView('prepare', '工程开工准备页');
    await input('total', '100000');
    await input('first', '100000');
    await input('note', '现场、道路水电及班组已核对', 'textarea');
    await sample();
    assert.equal((await r.data()).form.file?.meta?.version, '工程合同V1', '首次开工准备凭据应使用工程合同V1');
    const beforeInvalid = await r.snapshot();
    await action('prepare');
    await r.until(async () => (await r.data()).error === '开工款必须小于合同总额，余款作为封顶款', '开工款不能等于总额的具体提示');
    const afterInvalid = await r.snapshot();
    for (const key of ['zhongshu-business-v3', 'zhongshu-engineering-v1', 'zhongshu-management-v1']) {
      assert.deepEqual(store(afterInvalid, key), store(beforeInvalid, key), '无效开工准备不得写入 ' + key);
    }
    await r.mp.evaluate(() => { wx.pageScrollTo({ scrollTop: 0, duration: 0 }); return true; });
    await shot('01-invalid-equal-amounts');
    checks.push({ name: '开工款等于合同总额时精确提示且零写入', result: 'passed' });

    await input('first', '60000');
    await action('prepare');
    await waitView('overview', '开工准备保存后返回工程概览');
    let rows = await r.snapshot(), project = projectFrom(rows, engineeringId), book = financeBook(rows, initialProject.companyId);
    const pendingPlans = book.operations.plans.filter(plan => plan.projectId === projectId && plan.status === 'pending');
    assert.equal(project.recordFlow.contract.total, 10000000);
    assert.equal(project.recordFlow.contract.first, 6000000);
    assert.equal(project.recordFlow.contract.file.meta.version, '工程合同V1');
    assert.equal(project.recordFlow.prepared.note, '现场、道路水电及班组已核对');
    assert.equal(pendingPlans.length, 1, '正常开工准备只生成一张待审批收款计划');
    assert.deepEqual(pendingPlans[0].installments.map(item => [item.stage, item.amount]), [['start', 6000000], ['topping', 4000000]]);
    checks.push({ name: '工程人员保存有效开工准备并生成唯一两期计划', result: 'passed', contractVersion: project.recordFlow.contract.version });

    await login('boss', 'boss');
    await r.open('/pages/admin/operations/index?view=receivables&id=' + encodeURIComponent(projectId), '.ar-summary-card', { relaunch: true });
    await r.until(async () => (await r.data()).receivable?.approvalPending && (await r.data()).receivable?.canReview, '老板合同应收一次审批区', 12000);
    await shot('02-receivables-overview');
    await input('note', '已核对已签合同总额及开工款、封顶款两期约定', 'textarea');
    await r.event('.ar-review-check', 'toggle');
    await shot('03-receivables-one-approval');
    await action('plan-approve');
    await r.until(async () => (await r.current()).route === 'pages/admin/home/index', '老板一次确认后回工作台', 12000);
    rows = await r.snapshot();
    book = financeBook(rows, initialProject.companyId);
    assert.equal(book.operations.plans.filter(plan => plan.projectId === projectId && plan.status === 'approved').length, 1);
    await r.open('/pages/admin/operations/index?view=receivables&id=' + encodeURIComponent(projectId), '.ar-summary-card', { relaunch: true });
    await r.until(async () => (await r.data()).plan?.status === 'approved' && !(await r.data()).receivable?.approvalPending, '重开合同应收显示已确认', 12000);
    await shot('04-receivables-approved');
    checks.push({ name: '老板在合同应收页一次确认总额与两期约定', result: 'passed' });

    await login('finance-dual', 'finance');
    let tasks = await financeTasks();
    assert.equal(tasks.filter(task => task.id.endsWith(':collection:start')).length, 1, '开工款应只有一项待办');
    assert.equal(tasks.some(task => task.id.endsWith(':collection:topping')), false, '封顶前不得生成封顶款待办');
    await shot('05-finance-single-start-task');
    await openFinanceTask(tasks[0]);
    await waitView('collection', '加盟商财务从待办进入开工款登记');
    await collect('START-NATIVE-1');

    await r.open('/pages/management/index', '.fin-home', { relaunch: true });
    tasks = await financeTasks();
    assert.equal(tasks.filter(task => task.id.endsWith(':collection:start')).length, 1, '分笔未足额时仍只保留一项开工款待办');
    assert.equal(tasks.some(task => task.id.endsWith(':collection:topping')), false);
    await shot('06-finance-partial-still-one-task');
    await openFinanceTask(tasks[0]);
    await waitView('collection', '加盟商财务继续原开工款待办');
    await collect('START-NATIVE-2');

    rows = await r.snapshot();
    project = projectFrom(rows, engineeringId);
    assert.equal(project.collections.filter(row => row.receiptStage === 'start').length, 2);
    assert.equal(project.collections.filter(row => row.receiptStage === 'start').reduce((sum, row) => sum + row.cents, 0), 6000000);
    assert(project.collections.every(row => row.file?.meta?.version === '工程合同V1'), '两笔开工款凭据都应关联工程合同V1');
    await r.open('/pages/management/index', '.fin-home', { relaunch: true });
    tasks = await financeTasks();
    assert.equal(tasks.some(task => task.id.endsWith(':collection:start')), false, '开工款足额后待办应消失');
    assert.equal(tasks.some(task => task.id.endsWith(':collection:topping')), false, '封顶前不得出现封顶款待办');
    await shot('07-finance-start-paid-task-cleared');
    checks.push({ name: '加盟商财务两笔登记足额开工款，单待办消失且封顶款未提前出现', result: 'passed', records: 2, received: 6000000 });

    await login('engineering-dual', 'engineer');
    let data = await r.data();
    const startTasks = (data.eng?.tasks || []).filter(task => task.projectId === engineeringId && /start/.test(task.url));
    assert.equal(startTasks.length, 1, '开工款足额后工程岗应出现唯一实际开工待办');
    await shot('08-engineer-start-task');
    if ((data.eng.previewTasks || []).some(task => task.url === startTasks[0].url)) {
      await r.event({ selector: '.eng-todo button', dataset: { url: startTasks[0].url } }, 'openEngineering');
    } else {
      await r.event({ selector: '.eng-link', dataset: { action: 'todos' } }, 'openEngineering');
      await r.until(async () => (await r.current()).route === 'pages/build/projects/index' && (await r.data()).mode === 'tasks', '工程全部待办页', 12000);
      await r.event({ selector: '.eng-submit', dataset: { url: startTasks[0].url } }, 'open');
    }
    await waitView('start', '工程岗从待办打开原项目实际开工页');
    data = await r.data();
    assert.equal(data.p.projectId, projectId);
    assert.equal(data.p.recordFlow.contract.file.meta.version, '工程合同V1');
    assert.equal(data.error, '');
    await shot('09-engineer-start-ready');
    checks.push({ name: '开工款足额后自动续接工程岗实际开工待办及原项目', result: 'passed' });

    assert.equal(r.errors.length, 0, JSON.stringify(r.errors));
    fs.writeFileSync(path.join(out, 'final-results.json'), JSON.stringify({
      result: 'passed',
      scope: '隔离微信开发者工具，仅初始夹具写入存储；所有业务输入、模拟凭据和提交均通过真实 SDK Element.input/trigger/tap。',
      sourceFingerprint: source,
      project: { engineeringId, projectId, customer: initialProject.customer },
      checks,
      screenshots,
      interactionCount: r.interactions.length,
      interactions: r.interactions,
      exceptions: r.errors
    }, null, 2));
  } catch (error) {
    fs.writeFileSync(path.join(out, 'final-results.json'), JSON.stringify({ result: 'failed', error: error.stack, sourceFingerprint: source, checks, screenshots, interactions: r.interactions, exceptions: r.errors }, null, 2));
    try { await shot('final-failure'); } catch (_) {}
    throw error;
  } finally {
    let restoreError;
    for (let attempt = 1; attempt <= 3; attempt++) {
      try { await r.restore(); restoreError = null; break; }
      catch (error) { restoreError = error; await r.sleep(700); }
    }
    if (restoreError) throw restoreError;
    const beforeStorage = path.join(out, 'engineering-prepare-receivables-final-before-storage.json');
    if (fs.existsSync(beforeStorage)) fs.unlinkSync(beforeStorage);
  }
}

(mode === 'baseline' ? baseline() : final())
  .then(() => process.exit(0))
  .catch(error => { console.error(error.stack); process.exit(1); });
