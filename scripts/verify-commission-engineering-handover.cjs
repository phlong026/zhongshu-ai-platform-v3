const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const clone = value => JSON.parse(JSON.stringify(value));

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Business = require('../miniprogram/utils/business');
  const Organization = require('../miniprogram/utils/organization');
  const SalesFlow = require('../miniprogram/utils/sales-flow');
  const EngineeringWorkbench = require('../miniprogram/pages/build/workbench');
  const scenes = JSON.parse(fs.readFileSync(path.join(__dirname, '../qa/business-module-simplification-20261021/page-scenes.json')));
  const scene = scenes.find(row => row.action === 'packet-submit');
  assert(scene, '缺少 packet-submit 回归起点');

  const page = () => getCurrentPages().at(-1);
  const current = () => runtime.currentPage();
  const rawCustomer = id => (wx.getStorageSync(Business.KEY).customers || []).find(row => row.id === id);
  const install = rows => {
    for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key);
    for (const [key, value] of clone(rows)) wx.setStorageSync(key, value);
  };
  const login = (id, role) => {
    Account.mutate(state => Account.loginDemo(state, id));
    if (role) Account.mutate(state => Account.switchIdentity(state, state.session.companyId, role));
  };
  const configureEngineeringDefault = engineerId => {
    const root = clone(wx.getStorageSync('zhongshu-management-v1'));
    root.managementSettings = root.managementSettings || { version: 1, company: {}, assignments: {}, history: [], events: [] };
    root.managementSettings.assignments = { ...(root.managementSettings.assignments || {}), engineering: engineerId };
    wx.setStorageSync('zhongshu-management-v1', root);
  };
  const element = async (selector, message = selector) => {
    const control = await (await current()).$(selector);
    assert(control, 'Visible control missing: ' + message);
    return control;
  };
  const screenshotText = async name => {
    const file = path.join(os.tmpdir(), name + '.json');
    await runtime.screenshot({ path: file });
    return JSON.parse(fs.readFileSync(file, 'utf8')).renderedText;
  };

  install(scene.before);
  configureEngineeringDefault('engineering-dual');
  login('sales-dual', 'sales-lead');
  wx.reLaunch({ url: `/pages/workflow/commission/index?view=handover&id=${encodeURIComponent(scene.id)}` });

  assert.equal(page().route, 'pages/workflow/commission/index');
  assert.equal(page().data.view, 'handover');
  assert.equal(Organization.defaultAssignee(require('../miniprogram/utils/access').rawBook('quanzhou'), 'engineering'), 'engineering-dual');
  assert(!page().data.fields.some(field => field.key === 'managerId'), '移交页不得要求业务人员指定工程人员');
  assert.equal(await (await current()).$('picker[data-key="managerId"]'), null, '移交页不得渲染工程人员选择器');
  assert.equal(page().data.primary, '移交给工程');
  assert.equal(page().data.canDraft, false, '正式资料移交不显示暂存入口');
  assert.equal(page().data.canFeedback, false, '正式资料移交不显示客户修改入口');
  assert.equal(await (await current()).$('button[bindtap="saveDraft"]'), null);
  assert.equal(await (await current()).$('button[bindtap="openFeedback"]'), null);
  const handoverText = await screenshotText('commission-engineering-handover');
  assert(!handoverText.includes('资料可暂存，齐备后一次提交'), '移交主按钮下方不得保留暂存说明');
  assert(!handoverText.includes('客户需修改，登记意见'), '移交页面不得保留客户修改按钮');

  const originalProjectId = rawCustomer(scene.id).projectId;
  const submitted = { ...scene.input };
  delete submitted.managerId;
  page().setData({ form: { ...page().data.form, ...submitted, managerId: 'hq-design-admin' } });
  await (await element('button[bindtap="submit"]', '移交给工程')).tap();
  assert.equal(page().data.error, '');
  const handedOver = rawCustomer(scene.id);
  assert.equal(handedOver.projectId, originalProjectId, '资料移交必须保留原项目');
  assert.equal(handedOver.handover.managerId, 'engineering-dual', '页面残留或恶意传入的人员不得劫持组织默认工程负责人');
  assert.equal(handedOver.handover.manager, '张工程');
  assert.equal(handedOver.businessFlow.packet.resultFile.name, scene.input.resultFile.name);
  assert.equal(handedOver.businessFlow.packet.drawingFile.name, scene.input.drawingFile.name);
  assert.equal(handedOver.businessFlow.packet.reviewFile.name, scene.input.reviewFile.name);
  assert.equal(handedOver.businessFlow.packet.confirmationFile.name, scene.input.confirmationFile.name);
  assert(handedOver.handover.documents.every(file => file.file), '正式资料包必须完整保存到原项目移交单');

  login('engineering-dual', 'engineer');
  const handoverTask = EngineeringWorkbench.snapshot().tasks.find(task => task.id === 'handover:' + scene.id);
  assert(handoverTask, '组织默认工程负责人必须收到原项目接收待办');
  wx.reLaunch({ url: handoverTask.url });
  assert.equal(page().data.view, 'receive');
  assert.equal(page().data.customer.id, scene.id);
  await (await element('checkbox-group[data-key="checked"]', '工程接收核对')).trigger('change', { value: ['yes'] });
  await (await element('button[bindtap="submit"]', '确认接收')).tap();
  const accepted = rawCustomer(scene.id);
  assert.equal(accepted.handover.status, 'accepted');
  assert.equal(accepted.acceptance.managerId, 'engineering-dual');
  assert.equal(accepted.projectId, handedOver.projectId, '工程接收必须继续办理同一原项目');

  const returnedState = wx.getStorageSync(Business.KEY);
  const returned = clone(handedOver);
  returned.handover.status = 'returned';
  returnedState.customers = returnedState.customers.map(row => row.id === scene.id ? returned : row);
  wx.setStorageSync(Business.KEY, returnedState);
  configureEngineeringDefault('engineering-lead');
  const changedBook = wx.getStorageSync('zhongshu-management-v1');
  changedBook.staff.find(person => person.id === 'engineering-dual').active = false;
  wx.setStorageSync('zhongshu-management-v1', changedBook);
  login('sales-dual', 'sales-lead');
  wx.reLaunch({ url: SalesFlow.url(scene.id, 'handover') });
  page().setData({ form: { ...page().data.form, ...scene.input } });
  await (await element('button[bindtap="submit"]', '向在岗工程岗位重新移交')).tap();
  assert.equal(page().data.error, '', '原负责人离岗后应能移交给当前配置的有效工程负责人');
  assert.equal(rawCustomer(scene.id).handover.managerId, 'engineering-lead');
  login('engineering-lead', 'project-manager');
  assert(SalesFlow.tasks().some(task => task.customerId === scene.id && task.actionable && task.url.includes('view=receive')), '替任工程负责人应收到原单接收待办');

  install(scene.before);
  configureEngineeringDefault('');
  login('sales-dual', 'sales-lead');
  const before = rawCustomer(scene.id);
  assert.equal(before.handover, null);
  assert.throws(
    () => SalesFlow.run(scene.id, 'packet-submit', submitted, before.revision),
    /在岗工程负责人|工程负责人.*设置|职责分工/,
    '缺少组织默认工程负责人时必须给出明确处理提示'
  );
  assert.equal(rawCustomer(scene.id).handover, null, '缺少在岗工程负责人不得制造移交记录');

  console.log('PASS 设计资料按组织默认工程负责人移交：页面无人员选择/暂存/修改入口，原项目待办可接收');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
