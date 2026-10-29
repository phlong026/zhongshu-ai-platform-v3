const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const pageSource = fs.readFileSync('miniprogram/pages/build/record/index.js', 'utf8');
  assert(pageSource.includes("canPay=O.has(a.role,'finance')"), '付款入口须严格按财务角色投影');
  assert(!pageSource.includes("canPay=O.has(a.role,'finance')||X.isBoss(a)"), '老板不得被投影为财务付款人');
  const scenes = JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json', 'utf8'));
  const fixture = scenes.find(scene => scene.action === 'material-terms')?.after;
  assert(fixture, '需要已记录采购金额且待付款的材料计划夹具');
  for (const [key, value] of fixture) wx.setStorageSync(key, value);

  const A = require('../miniprogram/utils/account');
  const E = require('../miniprogram/pages/build/model');
  const R = require('../miniprogram/utils/engineering-records');
  const login = (id, role) => {
    A.mutate(account => A.loginDemo(account, id));
    if (role) A.mutate(account => A.switchIdentity(account, account.session.companyId, role));
  };
  const openMaterial = () => {
    const project = E.load().projects.find(row => row.recordFlow);
    assert(project, '夹具须包含原工程项目');
    const plan = R.plans(project).find(row => row.lines.some(line => line.payUrl?.includes('view=pay&') && line.paymentStatus !== '已付清'));
    assert(plan, '夹具须包含可办理的材料应付');
    wx.reLaunch({ url: R.link(project, 'material', { planId: plan.id }) });
    const page = getCurrentPages().at(-1);
    assert(!page.data.error, page.data.error);
    return { page, line: page.data.plan.lines.find(row => row.payUrl?.includes('view=pay&')) };
  };

  login('boss', 'boss');
  const opened = openMaterial();
  assert.equal(opened.page.data.canPay, false, '老板可看材料原单，但不获得财务付款权');
  assert.equal(opened.page.data.canTerms, true, '保留老板核对并记录采购金额的现有能力');
  assert.equal(await (await runtime.currentPage()).$('button[data-url="' + opened.line.payUrl + '"]'), null, '老板不显示必然跳转 DENIED 的“办理付款”');
  const readOnlyRow = await (await runtime.currentPage()).$('.eng-section .eng-row');
  assert(readOnlyRow && (await readOnlyRow.text()).includes(opened.line.paymentStatus), '老板仍能查看材料、到货与付款状态原单');

  login('finance-dual', 'finance');
  await runtime.mountFixture({
    file: 'miniprogram/pages/build/record/index.wxml',
    data: { ...opened.page.data, canPay: true },
    methods: {
      go(event) {
        wx.navigateTo({ url: event.currentTarget.dataset.url });
      }
    }
  });
  const payButton = await (await runtime.currentPage()).$('button[data-url="' + opened.line.payUrl + '"]');
  assert(payButton, '财务应看到“办理付款”');
  assert.equal(await payButton.text(), '办理付款');
  await payButton.tap();
  const payPage = getCurrentPages().at(-1);
  assert.equal(payPage.route, 'pages/admin/index');
  assert.equal(payPage.data.action, 'pay');
  assert(!payPage.data.error, payPage.data.error);

  console.log('PASS 老板材料详情只读下钻，普通财务保留付款办理');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
