const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const B = require('../miniprogram/utils/business');
  const A = require('../miniprogram/utils/account');
  const E = require('../miniprogram/pages/build/model');
  const SF = require('../miniprogram/utils/sales-flow');
  const R = require('../miniprogram/utils/engineering-records');
  const W = require('../miniprogram/pages/build/workbench');
  const X = require('../miniprogram/utils/access');
  const scene = require('../qa/business-module-simplification-20261021/page-scenes.json').find(s => s.action === 'packet-receive');
  const copy = value => JSON.parse(JSON.stringify(value));
  const raw = () => wx.getStorageSync(B.KEY).customers.find(c => c.id === scene.id);
  const projects = () => wx.getStorageSync(E.KEY)?.projects || [];
  const page = () => getCurrentPages().at(-1);
  const install = () => {
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of copy(scene.before)) wx.setStorageSync(key, value);
    A.mutate(s => A.loginDemo(s, 'engineering-dual'));
    A.mutate(s => A.switchIdentity(s, s.session.companyId, 'engineer'));
    wx.setStorageSync(E.KEY, { version: 1, projects: [] });
  };
  const accept = () => SF.run(scene.id, 'packet-receive', { checked: true }, raw().revision);
  const control = async selector => {
    const result = await (await runtime.currentPage()).$(selector);
    assert(result, '缺少可点击控件 ' + selector);
    return result;
  };
  const acceptedCustomer = () => {
    const state = wx.getStorageSync(B.KEY), c = state.customers.find(c => c.id === scene.id);
    c.handover.status = 'accepted';
    c.acceptance = { managerId: 'engineering-dual', manager: '张工程', date: A.localDate(), handoverVersion: c.handover.version, drawingVersion: c.handover.drawingVersion };
    wx.setStorageSync(B.KEY, state);
  };
  const existing = values => E.project({ id: 'existing-engineering', projectId: raw().projectId, companyId: raw().companyId, managerId: 'engineering-dual', manager: '张工程', ...values });
  const checks = [];
  const test = async (name, fn) => {
    try { install(); await fn(); checks.push({ name, passed: true }); console.log('PASS ' + name); }
    catch (error) { checks.push({ name, passed: false, error: error.message }); console.error('FAIL ' + name + ': ' + error.message); }
  };

  await test('点击接收后立即建档、带入五类资料并生成开工准备待办', async () => {
    wx.reLaunch({ url: SF.url(scene.id, 'receive') });
    await (await control('checkbox-group[data-key="checked"]')).trigger('change', { value: ['yes'] });
    await (await control('button[bindtap="submit"]')).tap();
    assert.equal(page().data.error, '');
    const p = projects().find(p => p.sourceCustomerId === scene.id);
    assert(p, '接收成功返回前必须已有工程项目，不能等工作台刷新才创建');
    assert.equal(p.projectId, raw().projectId);
    assert.equal(p.managerId, 'engineering-dual');
    assert(R.is(p));
    assert.deepEqual(p.documents.map(d => [d.name, d.file]), SF.packageRows(raw()).map(d => [d.label, d.file]));
    assert.equal(p.drawingVersion, raw().handover.drawingVersion);
    assert.deepEqual(p.drawing, raw().businessFlow.packet.drawingFile);
    wx.switchTab({ url: '/pages/engineering/index' });
    assert.equal(page().data.error, '');
    const task = W.snapshot().tasks.find(t => t.projectId === p.id);
    assert(task && task.url.includes('view=prepare'), '接收待办完成后必须接续开工准备待办');
    assert(!W.snapshot().tasks.some(t => t.id === 'handover:' + scene.id));
    assert.equal(projects().filter(p => p.projectId === raw().projectId).length, 1);
    assert.throws(accept, /变化|办理|刷新|权限/);
    assert.equal(projects().length, 1, '重复接收不能重复建档');
  });

  await test('复现截图旧工程刷新错误，刷新不得覆盖工程负责人和施工事实', () => {
    acceptedCustomer();
    const old = existing({ sourceCustomerId: scene.id, managerId: 'li', manager: '李经理', contractTotal: 98765, siteRecords: [{ id: 'site-existing', note: '原施工记录' }], payments: [{ id: 'pay-existing', cents: 100 }], collections: [{ id: 'collection-existing', cents: 300 }] });
    R.init(old);
    old.recordFlow.contract = { total: 9876500, first: 1000000 };
    wx.setStorageSync(E.KEY, { version: 1, projects: [old] });
    A.mutate(s => A.switchIdentity(s, s.session.companyId, 'project-manager'));
    wx.reLaunch({ url: '/pages/build/home/index' });
    assert.equal(page().data.error, '', '返回工程工作台不能因业务资料同步覆盖负责人而报错');
    const p = projects()[0];
    for (const key of ['managerId', 'manager', 'assignedEngineerId', 'contractTotal', 'siteRecords', 'payments', 'collections']) assert.deepEqual(p[key], old[key], key);
    E.sync();
    assert.equal(projects().length, 1);
  });

  await test('同项目旧档复用原编号，保留付款收款和现场记录', () => {
    const old = existing({ payments: [{ id: 'pay-keep', cents: 100 }], collections: [{ id: 'income-keep', cents: 200 }], siteRecords: [{ id: 'site-keep' }] });
    wx.setStorageSync(E.KEY, { version: 1, projects: [old] });
    accept();
    assert.equal(projects().length, 1);
    const p = projects()[0];
    assert.equal(p.id, old.id);
    assert.equal(p.sourceCustomerId, scene.id);
    for (const key of ['payments', 'collections', 'siteRecords']) assert.deepEqual(p[key], old[key]);
    assert.equal(p.documents.length, 5);
  });

  await test('工程存储失败则回滚接收状态，原单可重新接收', () => {
    const before = copy([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY), wx.getStorageSync('zhongshu-management-v1')]);
    const save = wx.setStorageSync;
    let fail = true;
    wx.setStorageSync = (key, value) => { if (key === E.KEY && fail) { fail = false; throw Error('模拟工程存储失败'); } return save(key, value); };
    try { assert.throws(accept, /工程存储失败/); }
    finally { wx.setStorageSync = save; }
    assert.deepEqual([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY), wx.getStorageSync('zhongshu-management-v1')], before);
    accept();
    assert.equal(projects().length, 1);
  });

  await test('既有项目身份冲突不得另建重复项目，接收整体回滚', () => {
    for (const values of [{ managerId: 'li', manager: '李经理' }, { sourceCustomerId: 'other-customer' }, { companyId: 'other-company' }]) {
      install();
      wx.setStorageSync(E.KEY, { version: 1, projects: [existing(values)] });
      const before = copy([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY)]);
      assert.throws(accept, /负责人|关联|公司|归属|权限/);
      assert.deepEqual([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY)], before);
    }
  });

  await test('历史已接收但未建工程可补建，重复刷新不重复建档', () => {
    acceptedCustomer();
    E.sync(); E.sync();
    assert.equal(projects().length, 1);
    assert.equal(projects()[0].documents.length, 5);
  });

  await test('首次进入尚无工程缓存时，建档失败不遗留接收记录或模拟项目', () => {
    wx.removeStorageSync(E.KEY);
    const before = copy(wx.getStorageSync(B.KEY)), save = wx.setStorageSync;
    let fail = true;
    wx.setStorageSync = (key, value) => { if (key === E.KEY && fail) { fail = false; throw Error('模拟首次建档失败'); } return save(key, value); };
    try { assert.throws(accept, /首次建档失败/); }
    finally { wx.setStorageSync = save; }
    assert.equal(wx.getStorageSync(E.KEY), undefined);
    assert.deepEqual(wx.getStorageSync(B.KEY), before);
  });

  await test('客户来源与项目编号分别命中两个工程时不覆盖或新增', () => {
    wx.setStorageSync(E.KEY, { version: 1, projects: [existing({ id: 'source-match', sourceCustomerId: scene.id, projectId: 'other-project' }), existing({ id: 'project-match' })] });
    const before = copy([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY)]);
    assert.throws(accept, /多个工程/);
    assert.deepEqual([wx.getStorageSync(B.KEY), wx.getStorageSync(E.KEY)], before);
  });

  await test('工程内部合法改派后刷新保留新经办人，普通保存仍不能改派', () => {
    accept(); E.sync();
    let p = projects()[0];
    R.run(p.id, 'project', { engineerId: 'engineering-lead', date: A.localDate(), nodes: p.plan.nodes });
    p = projects()[0];
    assert.equal(p.managerId, 'engineering-lead');
    assert.equal(raw().managerId, p.managerId);
    A.mutate(s => A.loginDemo(s, 'engineering-lead'));
    A.mutate(s => A.switchIdentity(s, s.session.companyId, 'project-manager'));
    E.sync(); E.sync();
    assert.equal(projects()[0].managerId, p.managerId);
    const state = E.load();
    state.projects[0].managerId = 'li';
    assert.throws(() => E.save(state), /授权|状态|归属|负责人/);
    assert.equal(X.managerId(projects()[0]), p.managerId);
  });

  const out = path.resolve('qa/engineering-receive-project-20261023');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'model-results.json'), JSON.stringify({ passed: checks.every(c => c.passed), evidence: 'Isolated real Page handlers and model regression; not native device acceptance', checks }, null, 2));
  if (checks.some(c => !c.passed)) process.exitCode = 1;
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
