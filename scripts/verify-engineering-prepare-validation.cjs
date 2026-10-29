const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Engineering = require('../miniprogram/pages/build/model');
  const Management = require('../miniprogram/pages/admin/model');
  const Business = require('../miniprogram/utils/business');
  const scenes = JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json', 'utf8'));
  const fixture = scenes.find(scene => scene.action === 'prepare');
  assert(fixture, '缺少开工准备页面夹具');

  const copy = value => JSON.parse(JSON.stringify(value));
  const checks = [];
  const protectedKeys = [Business.KEY, Engineering.KEY, Management.KEY];
  let clicks = 0;

  function pass(name) {
    checks.push(name);
    console.log('PASS ' + name);
  }

  function install() {
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of copy(fixture.before)) wx.setStorageSync(key, value);
    Account.mutate(state => Account.loginDemo(state, 'engineering-dual'));
    Account.mutate(state => Account.switchIdentity(state, state.session.companyId, 'engineer'));
  }

  function snapshot() {
    return Object.fromEntries(protectedKeys.map(key => [key, wx.getStorageSync(key)]));
  }

  async function openPrepare() {
    wx.reLaunch({ url: fixture.url });
    const page = getCurrentPages().at(-1);
    assert.equal(page.data.view, 'prepare');
    assert(page.data.own, '当前工程人员应能办理开工准备');
    return { page, ui: await runtime.currentPage() };
  }

  async function input(ui, key, value) {
    const field = await ui.$((key === 'note' ? 'textarea' : 'input') + '[data-key="' + key + '"]');
    assert(field, '缺少页面字段 ' + key);
    await field.input(value);
  }

  async function sample(ui) {
    const button = await ui.$('button[data-sample-key="file"]');
    assert(button, '开工准备应提供本项目参考合同入口');
    await button.tap();
  }

  async function submit(ui) {
    const button = await ui.$('[data-action="prepare"]');
    assert(button, '缺少保存开工准备按钮');
    await button.tap();
    clicks += 1;
  }

  async function invalid({ total = '100000', first = '60000', withFile = true, note = '现场、班组和进场条件已核对', error }) {
    install();
    const { page, ui } = await openPrepare();
    const before = snapshot();
    await input(ui, 'total', total);
    await input(ui, 'first', first);
    if (withFile) await sample(ui);
    if (note !== null) await input(ui, 'note', note);
    await submit(ui);
    assert.equal(page.data.error, error);
    assert.deepEqual(snapshot(), before, '校验失败不能写入业务、工程或管理数据');
  }

  await invalid({ total: '0', error: '已签工程合同总额必须大于0' });
  await invalid({ first: '0', error: '开工款必须大于0' });
  await invalid({ total: '100000', first: '100000', error: '开工款必须小于合同总额，余款作为封顶款' });
  await invalid({ total: '100000', first: '120000', error: '开工款必须小于合同总额，余款作为封顶款' });
  await invalid({ withFile: false, error: '请上传有效的双方已签合同凭据' });
  await invalid({ note: null, error: '请填写现场、班组和开工准备记录' });
  pass('金额关系、合同凭据和现场记录逐项提示，失败时三端数据均不写入');

  install();
  let { page, ui } = await openPrepare();
  const labels = page.data.fields.map(field => field.label);
  assert(labels.includes('开工款（元，须小于合同总额；余款为封顶款）'));
  await input(ui, 'total', '100000');
  await input(ui, 'first', '60000');
  await input(ui, 'note', '现场、班组和进场条件已核对');
  await sample(ui);
  const sampleFile = page.data.form.file;
  assert.equal(sampleFile.meta.customerId, page.data.p.sourceCustomerId);
  assert.equal(sampleFile.meta.projectId, page.data.p.projectId);
  assert.equal(sampleFile.meta.version, '工程合同V1');
  assert(sampleFile.text.includes('客户：' + page.data.p.customer));
  assert(sampleFile.text.includes('项目ID：' + page.data.p.projectId));
  const engineeringId = page.data.p.id;
  await submit(ui);
  assert.equal(page.data.error, '');

  const stored = wx.getStorageSync(Engineering.KEY).projects.find(project => project.id === engineeringId);
  assert(stored?.recordFlow?.prepared);
  assert.equal(stored.recordFlow.contract.total, 10000000);
  assert.equal(stored.recordFlow.contract.first, 6000000);
  const projectId = stored.projectId;
  const plans = () => (wx.getStorageSync(Management.KEY).operations?.plans || []).filter(plan => plan.projectId === projectId);
  assert.equal(plans().length, 1);
  assert.deepEqual(plans()[0].installments.map(row => [row.stage, row.amount]), [['start', 6000000], ['topping', 4000000]]);

  ({ page, ui } = await openPrepare());
  await input(ui, 'note', '现场、班组和进场条件已核对');
  await sample(ui);
  await submit(ui);
  assert.equal(page.data.error, '');
  assert.equal(plans().length, 1, '相同合同再次保存不能重复生成约定待办');
  pass('页面真实输入和参考合同可保存一次两期约定，项目、客户与合同版本一致且不重复待办');

  for (const action of ['start', 'collect']) {
    const scene = scenes.find(row => row.action === action);
    assert(scene, '缺少' + action + '页面夹具');
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of copy(scene.before)) wx.setStorageSync(key, value);
    wx.reLaunch({ url: scene.url });
    const stagePage = getCurrentPages().at(-1);
    const stageUi = await runtime.currentPage();
    await sample(stageUi);
    const contractVersion = stagePage.data.p.recordFlow.contract.version;
    assert.equal(stagePage.data.form.file.meta.version, '工程合同V' + contractVersion);
    assert(!stagePage.data.form.file.meta.version.includes('施工计划'));
  }
  pass('开工准备、实际开工和工程收款参考凭据均使用工程合同版本');

  const output = path.resolve('qa/engineering-prepare-validation-20261023/model-results.json');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify({
    result: 'passed',
    checks,
    clicks,
    projectId,
    evidence: 'Real Page input and visible WXML controls in isolated Node runtime; invalid submissions preserve business, engineering and management storage.'
  }, null, 2) + '\n');
  console.log('COMPLETE ' + checks.length + ' checks, ' + clicks + ' visible button clicks');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
