const assert = require('assert/strict');
const fs = require('fs');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const SalesFlow = require('../miniprogram/utils/sales-flow');
  const Files = require('../miniprogram/utils/sample-files');
  const AdminModel = require('../miniprogram/pages/admin/model');
  const FinanceSource = require('../miniprogram/utils/finance-source');
  const scenes = JSON.parse(fs.readFileSync('qa/business-module-simplification-20261021/page-scenes.json', 'utf8'));
  const engineeringScenes = JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json', 'utf8'));
  const install = (rows, loginId = 'sales-dual', role = 'sales-lead') => {
    wx.getStorageInfoSync().keys.forEach(key => wx.removeStorageSync(key));
    rows.forEach(([key, value]) => wx.setStorageSync(key, value));
    Account.mutate(state => Account.loginDemo(state, loginId));
    Account.mutate(state => Account.switchIdentity(state, state.session.companyId, role));
  };
  const current = () => getCurrentPages().at(-1);
  const open = (scene, view) => wx.reLaunch({ url: SalesFlow.url(scene.id, view) });

  const submitScene = scenes.find(scene => scene.action === 'commission-submit');
  install(submitScene.before);
  open(submitScene, 'submit');
  let ui = await runtime.currentPage();
  const requestSample = await ui.$('button[data-sample-key="attachment"]');
  assert(requestSample, '设计委托应提供页面可见的本项目模拟附件按钮');
  await requestSample.tap();
  const requestFile = current().data.form.attachment;
  assert(requestFile.sample && requestFile.text, '设计委托模拟附件必须包含可预览文本');
  assert(requestFile.text.includes(current().data.customer.name));
  assert(requestFile.text.includes(current().data.customer.projectId));
  assert(requestFile.text.includes('设计委托需求附件'));

  const handoverScene = scenes.find(scene => scene.action === 'packet-submit');
  install(handoverScene.before);
  open(handoverScene, 'handover');
  ui = await runtime.currentPage();
  const drawingVersion = 'V' + current().data.form.dispatchVersion;
  await (await ui.$('input[data-key="drawingVersion"]')).input(drawingVersion);
  const purposes = {
    resultFile: '正式设计成果',
    drawingFile: '本版施工图',
    reviewFile: '线下专业审核依据',
    confirmationFile: '客户对本版成果的确认凭据'
  };
  for (const [key, purpose] of Object.entries(purposes)) {
    const button = await ui.$('button[data-sample-key="' + key + '"]');
    assert(button, key + ' 应提供页面可见模拟文件按钮');
    await button.tap();
    const file = current().data.form[key];
    assert.equal(file.sample, true);
    assert(file.text.includes(current().data.customer.name));
    assert(file.text.includes(current().data.customer.id));
    assert(file.text.includes(current().data.customer.projectId));
    assert(file.text.includes(drawingVersion));
    assert(file.text.includes(Account.load().profile.name));
    assert(file.text.includes(purpose));
    assert(file.text.includes('来源责任人：林设计'), purpose + ' 应对应本版设计责任人');
    assert.equal(file.meta.projectId, current().data.customer.projectId);
    assert.equal(file.meta.version, drawingVersion);
  }
  assert(current().data.form.confirmationFile.text.includes('确认方：' + current().data.customer.name));
  assert(current().data.form.confirmationFile.text.includes('客户确认日期：' + current().data.form.confirmationDate));
  const previewButton = await ui.$('.commission-file[data-key="resultFile"]');
  assert(previewButton, '已生成文件应提供预览按钮');
  await previewButton.tap();
  assert(current().data.preview.text.includes('正式设计成果'));

  current().closePreview();
  await (await (await runtime.currentPage()).$('button[bindtap="saveDraft"]')).tap();
  const stored = wx.getStorageSync(Business.KEY).customers.find(customer => customer.id === handoverScene.id);
  const draft = stored.drafts[Business.draftKey('offline:handover')];
  assert(draft.resultFile.text.includes(drawingVersion), '暂存后应保留模拟文件正文');
  assert.equal(draft.confirmationFile.meta.projectId, current().data.customer.projectId);
  open(handoverScene, 'handover');
  assert(current().data.form.reviewFile.text.includes('线下专业审核依据'), '重新打开应恢复模拟文件正文');

  const prepareScene = engineeringScenes.find(scene => scene.action === 'prepare');
  install(prepareScene.before, 'engineering-dual', 'engineer');
  wx.reLaunch({ url: prepareScene.url });
  ui = await runtime.currentPage();
  await (await ui.$('input[data-key="total"]')).input('680000');
  await (await ui.$('input[data-key="first"]')).input('300000');
  await (await ui.$('button[data-sample-key="file"]')).tap();
  const contractFile = current().data.form.file;
  assert(contractFile.text.includes(current().data.p.projectId));
  assert(contractFile.text.includes('合同总额：'));
  await (await (await runtime.currentPage()).$('.record-fields button[bindtap="preview"]')).tap();
  assert(current().data.samplePreview.text.includes('双方已签工程合同'));

  const termsScene = engineeringScenes.find(scene => scene.action === 'material-terms');
  install(termsScene.before, 'finance-dual', 'finance');
  wx.reLaunch({ url: termsScene.url });
  ui = await runtime.currentPage();
  const termsSample = await ui.$('button[data-sample-key="termsFile"]');
  assert(termsSample, '实际采购凭据应提供页面可见模拟入口');
  await termsSample.tap();
  assert(current().data.form.termsFile.text.includes('材料计划：'));
  assert(current().data.form.termsFile.text.includes(current().data.plan.id));

  install([], 'finance-dual', 'finance');
  const financeState = AdminModel.load();
  const payable = financeState.payables.find(row => row.id === 'payable-chen-design');
  payable.approval = { status: 'approved', role: 'boss', actorId: 'zhao-boss', personId: 'zhao-boss', signature: FinanceSource.signature(payable) };
  payable.serviceApprovalValid = true;
  payable.approvalValid = true;
  wx.setStorageSync(AdminModel.KEY, financeState);
  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [{ id: 'customer-finance-exact', projectId: payable.projectId, name: payable.customer }] });
  wx.reLaunch({ url: '/pages/admin/index?view=pay&id=' + payable.id });
  ui = await runtime.currentPage();
  await (await ui.$('input[data-key="amount"]')).input('4000');
  await (await ui.$('input[data-key="transaction"]')).input('PAY-SAMPLE-001');
  const financeSample = await ui.$('button[data-sample-key="file"]');
  assert(financeSample, '财务登记应提供页面可见模拟凭据入口');
  const originalRecord = AdminModel.record;
  AdminModel.record = () => ({ ...payable, version: 2, source: { module: 'intercompany', kind: 'design', customerId: 'customer-finance-exact', projectId: payable.projectId, serviceVersion: 1, requestVersion: 3 } });
  try { await financeSample.tap(); } finally { AdminModel.record = originalRecord; }
  assert(current().data.form.file.text.includes('财务事项：'));
  assert(current().data.form.file.text.includes('本次金额：4000元'));
  assert(current().data.form.file.text.includes('交易流水：PAY-SAMPLE-001'));
  assert.equal(current().data.form.file.meta.customerId, 'customer-finance-exact');
  assert.equal(current().data.form.file.meta.projectId, payable.projectId);
  assert.equal(current().data.form.file.meta.version, '设计服务V1');
  assert(current().data.form.file.text.includes('需求版本：V3'));
  await (await (await runtime.currentPage()).$('.upload-row button[bindtap="preview"]')).tap();
  assert(current().data.modal.text.includes('当前登记人：王财务'));

  install(termsScene.before, 'finance-dual', 'finance');
  const operationsProject = wx.getStorageSync('zhongshu-engineering-v1').projects.find(project => project.recordFlow);
  wx.reLaunch({ url: '/pages/admin/operations/index?view=expenses&id=' + encodeURIComponent(operationsProject.projectId) });
  ui = await runtime.currentPage();
  await (await ui.$('input[data-key="name"]')).input('施工节点人工费');
  await (await ui.$('input[data-key="amount"]')).input('6800');
  await (await ui.$('input[data-key="reference"]')).input('EXP-SAMPLE-001');
  const operationsSample = await ui.$('button[data-sample-key="file"]');
  assert(operationsSample, '项目费用登记应提供页面可见模拟凭据入口');
  await operationsSample.tap();
  assert(current().data.form.file.text.includes('项目ID：' + operationsProject.projectId));
  assert(current().data.form.file.text.includes('本次金额：6800元'));
  assert(current().data.form.file.text.includes('凭据编号：EXP-SAMPLE-001'));
  assert(current().data.form.file.text.includes('发生日期：'));
  assert.equal(current().data.form.file.meta.customerId, operationsProject.sourceCustomerId);
  assert.equal(current().data.form.file.meta.projectId, operationsProject.projectId);
  await (await (await runtime.currentPage()).$('button[bindtap="previewFile"]')).tap();
  assert(current().data.samplePreview.text.includes('项目费用'));

  install([], 'finance-dual', 'finance');
  wx.reLaunch({ url: '/pages/admin/operations/index?view=expenses&id=company%3Aquanzhou' });
  ui = await runtime.currentPage();
  const companySample = await ui.$('button[data-sample-key="file"]');
  assert(companySample, '公司费用应保留独立的本公司模拟凭据入口');
  assert((await companySample.text()).includes('本公司'));
  await companySample.tap();
  assert.equal(current().data.form.file.meta.scope, 'company');
  assert.equal(current().data.form.file.meta.companyId, 'quanzhou');
  assert.equal(current().data.form.file.meta.projectId, '');

  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [{ id: 'customer-business-only', projectId: 'project-business-only', name: '仅业务客户' }] });
  wx.setStorageSync('zhongshu-engineering-v1', { version: 1, projects: [] });
  current().setData({ selected: { id: 'project-business-only', name: '仅业务客户项目' }, plan: null, form: { date: '2026-10-23' }, error: '' });
  current().useSampleFile({ currentTarget: { dataset: { sampleKey: 'file' } } });
  assert.match(current().data.error, /具体版本/, '只有业务项目但没有任何版本时应拒绝生成，不得伪造施工计划版本');
  assert.equal(current().data.form.file, undefined);
  wx.setStorageSync('zhongshu-business-v3', { version: 1, customers: [{ id: 'customer-business-only', projectId: 'project-business-only', name: '仅业务客户', request: { version: 4 } }] });
  current().setData({ error: '' });
  current().useSampleFile({ currentTarget: { dataset: { sampleKey: 'file' } } });
  assert.equal(current().data.form.file.meta.version, '业务需求V4');

  const engineeringWxml = fs.readFileSync('miniprogram/pages/build/record/index.wxml', 'utf8');
  for (const key of ['nodeFile', 'siteFile']) assert(engineeringWxml.includes('data-sample-key="' + key + '"'), key + ' 更正场景应保留可见模拟凭据入口');
  assert.throws(() => Files.reference({ scope: 'project', projectId: operationsProject.projectId, version: 'V1', purpose: '缺少客户ID' }), /客户ID/);
  assert.throws(() => Files.reference({ scope: 'project', customerId: operationsProject.sourceCustomerId, version: '当前版本', purpose: '泛版本' }), /项目ID|具体版本/);
  const companyFile = Files.reference({ scope: 'company', companyId: 'quanzhou', version: '账务期间2026-10', purpose: '公司日常费用凭据' });
  assert.equal(companyFile.meta.scope, 'company');
  assert.equal(companyFile.meta.companyId, 'quanzhou');
  assert(companyFile.text.includes('公司ID：quanzhou'));

  wx.getAccountInfoSync = () => ({ miniProgram: { envVersion: 'release' } });
  wx.reLaunch({ url: '/pages/admin/operations/index?view=expenses&id=company%3Aquanzhou' });
  assert.equal(await (await runtime.currentPage()).$('button[data-sample-key="file"]'), null, '正式版不能显示模拟文件按钮');
  assert.throws(() => Files.reference({ projectId: 'project-release', purpose: '禁止生成' }), /不能生成模拟文件/);
  delete wx.getAccountInfoSync;
  Account.mutate(state => { state.session.demo = false; });
  assert.deepEqual(Files.items(['选择实际文件', '使用参考凭据']), ['选择实际文件'], '非 demo 菜单不能出现模拟选项');
  wx.reLaunch({ url: '/pages/admin/operations/index?view=expenses&id=company%3Aquanzhou' });
  assert.equal(await (await runtime.currentPage()).$('button[data-sample-key="file"]'), null, '非 demo 账号不能看到模拟文件按钮');
  assert.throws(() => Files.reference({ name: '禁止生成.pdf', projectId: 'project-forbidden', purpose: '禁止生成' }), /实际文件|模拟文件/);

  console.log('PASS project sample files: visible clicks, preview text, same project/version, persistence, non-demo rejection');
})().catch(error => {
  console.error(error.stack || error);
  process.exit(1);
});
