const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const B = require('../miniprogram/utils/business');
  const E = require('../miniprogram/pages/build/model');
  const R = require('../miniprogram/utils/engineering-records');
  const SF = require('../miniprogram/utils/sales-flow');
  const clone = value => JSON.parse(JSON.stringify(value));
  const read = file => JSON.parse(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'));
  const login = id => A.mutate(state => A.loginDemo(state, id));
  const current = () => getCurrentPages().at(-1);
  const pageText = async selector => {
    const element = await (await runtime.currentPage()).$(selector);
    assert(element, '页面存在 ' + selector);
    return element.text();
  };
  const open = url => runtime.callWxMethod('reLaunch', { url });

  const scenes = read('qa/business-module-simplification-20261021/page-scenes.json');
  for (const [key, value] of scenes.filter(row => row.action === 'packet-receive').at(-1).before) wx.setStorageSync(key, clone(value));
  wx.setStorageSync(E.KEY, { version: 1, projects: [] });
  login('engineering-dual');
  const incoming = B.load().customers.find(customer => customer.businessFlow && customer.handover && !customer.acceptance);
  assert(incoming, '回归来源为真实移交待接收记录');
  SF.run(incoming.id, 'packet-receive', { checked: true }, incoming.revision);
  E.sync();
  const project = E.load().projects.find(item => item.recordFlow);
  assert(project);
  const engineering = wx.getStorageSync(E.KEY);
  engineering.projects.find(item => item.id === project.id).documents.push({ name: '历史凭据', file: { name: '工程记录示例凭据.pdf', sample: true } });
  wx.setStorageSync(E.KEY, engineering);
  const engineeringBefore = wx.getStorageSync(E.KEY);
  await open(R.link(project, 'documents'));
  assert.equal(current().data.error, '');
  assert(current().data.docs.some(row => row.file?.name === '工程记录参考凭据.pdf'));
  assert((await pageText('.record-page')).includes('工程记录参考凭据.pdf'));
  await (await (await runtime.currentPage()).$('[data-file]')).tap();
  assert.deepEqual(wx.getStorageSync(E.KEY), engineeringBefore, '工程展示及预览不改写旧附件缓存');
  console.log('PASS 工程项目资料真实页面显示参考文件名，原始工程缓存保持不变');

  const beforePlan = R.get(project.id);
  R.run(project.id, 'material-plan', { nodeId: beforePlan.plan.nodes[0].id, due: A.localDate(), lines: [{ name: '水泥', quantity: '10', unit: '包' }] }, beforePlan.revision);
  const withMaterials = wx.getStorageSync(E.KEY);
  const storedProject = withMaterials.projects.find(item => item.id === project.id);
  const materialPlan = storedProject.recordFlow.materialPlans.at(-1);
  storedProject.supplies.find(item => item.planId === materialPlan.id).basis = { name: '模拟供应方报价.pdf', sample: true };
  wx.setStorageSync(E.KEY, withMaterials);
  const materialBefore = wx.getStorageSync(E.KEY);
  await open(R.link(R.get(project.id), 'material', { planId: materialPlan.id }));
  assert((await pageText('[data-key="termsFile"]')).includes('参考供应方报价.pdf'));
  assert.deepEqual(wx.getStorageSync(E.KEY), materialBefore);
  console.log('PASS 材料付款凭据按钮使用新展示名，旧供应报价缓存保持不变');

  await open(R.link(R.get(project.id), 'start'));
  const originalSheet = wx.showActionSheet;
  const originalChoose = wx.chooseMessageFile;
  const originalSave = wx.saveFile;
  try {
    wx.showActionSheet = options => options.success({ tapIndex: 1 });
    await (await (await runtime.currentPage()).$('[data-key="file"]')).tap();
    assert((await pageText('[data-key="file"]')).includes('工程记录参考凭据.pdf'));
    wx.showActionSheet = options => options.success({ tapIndex: 0 });
    wx.chooseMessageFile = options => options.success({ tempFiles: [{ name: '现场原图.pdf', path: '/temporary-proof.pdf' }] });
    wx.saveFile = options => options.success({ savedFilePath: '/saved-proof.pdf' });
    await (await (await runtime.currentPage()).$('[data-key="file"]')).tap();
    assert((await pageText('[data-key="file"]')).includes('现场原图.pdf'));
    assert.equal(current().data.form.file.path, '/saved-proof.pdf');
    assert.deepEqual(wx.getStorageSync(E.KEY), materialBefore, '选择表单文件不提交工程记录');
  } finally {
    wx.showActionSheet = originalSheet;
    wx.chooseMessageFile = originalChoose;
    wx.saveFile = originalSave;
  }
  console.log('PASS 选择参考或本机文件后，工程表单按钮立即更新文件名');

  login('sales-dual');
  const customer = B.load().customers.find(item => item.id === incoming.id);
  const business = wx.getStorageSync(B.KEY);
  business.customers.find(item => item.id === customer.id).events.push({ id: 'legacy-copy-event', title: '预置本地模拟跟进', by: '历史经办人', date: A.localDate() });
  wx.setStorageSync(B.KEY, business);
  const businessBefore = wx.getStorageSync(B.KEY);
  await open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(customer.id));
  assert.equal(current().route, 'pages/workflow/index');
  assert.equal(current().data.customer.events.at(-1).title, '预置客户跟进记录');
  assert.deepEqual(wx.getStorageSync(B.KEY), businessBefore, '业务展示不改写历史事件');
  console.log('PASS 业务工作区派生旧跟进事件新文案，历史业务记录保持不变');

  const finance = read('qa/finance-implementation-20261021/fixtures.json');
  for (const [key, value] of finance.fixtures['hq-staff']) wx.setStorageSync(key, clone(value));
  A.load();
  const management = wx.getStorageSync('zhongshu-management-v1');
  const book = management.companyId === 'headquarters' ? management : management.books.headquarters;
  const receipt = book.receipts.find(item => item.file);
  assert(receipt, '财务回归来源为既有到账记录');
  receipt.file = { ...receipt.file, name: '图稿示例凭据.pdf', sample: true };
  wx.setStorageSync('zhongshu-management-v1', management);
  const financeBefore = wx.getStorageSync('zhongshu-management-v1');
  await open('/pages/admin/index?view=receipt&id=' + encodeURIComponent(receipt.id));
  assert.equal(current().data.error, '');
  const pictures = current().data.sections.flatMap(section => section.rows).filter(row => row.type === 'picture');
  const pictureIndex = pictures.findIndex(row => row.file?.name === '图稿参考凭据.pdf');
  assert.equal(pictureIndex, 0, '原收款凭据为第一张可预览图片');
  await (await (await runtime.currentPage()).$('.picture')).tap();
  assert.equal(current().data.modal.title, '图稿参考凭据.pdf');
  assert((await pageText('.sheet')).includes('图稿参考凭据.pdf'));
  assert.deepEqual(wx.getStorageSync('zhongshu-management-v1'), financeBefore, '财务展示不改写到账原始凭据');
  console.log('PASS 财务到账真实页面显示参考凭据，原始账簿保持不变');

  login('sales-dual');
  await open('/pages/account/index?view=order&id=legacy-order');
  assert.equal(current().data.view, 'services', '服务下单与订单仍按既有边界回到展示页');
  console.log('PASS 原有订单展示边界保留，清理文案未重新开放下单或订单办理');
  await runtime.disconnect();
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
