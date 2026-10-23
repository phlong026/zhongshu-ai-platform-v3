const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account');
  const R = require('../miniprogram/utils/engineering-records');
  const M = require('../miniprogram/pages/admin/model');
  const F = require('../miniprogram/utils/finance-source');
  const fixture = require('../qa/engineering-records-20261021/page-scenes.json').find(s => s.action === 'prepare').after;
  const copy = v => JSON.parse(JSON.stringify(v));
  const checks = [];
  const page = () => getCurrentPages().at(-1);
  const project = () => wx.getStorageSync('zhongshu-engineering-v1').projects.find(p => p.recordFlow);
  const login = (id, role) => { A.mutate(s => A.loginDemo(s, id)); if (role) A.mutate(s => A.switchIdentity(s, s.session.companyId, role)); };
  const install = () => { for (const key of wx.getStorageInfoSync().keys) wx.removeStorageSync(key); for (const [key, value] of copy(fixture)) wx.setStorageSync(key, value); };
  const open = () => { wx.reLaunch({ url: '/pages/admin/operations/index?view=receivables&id=' + project().projectId }); assert.equal(page().data.error, ''); };
  const element = async selector => { const control = await (await runtime.currentPage()).$(selector); assert(control, selector); return control; };
  const test = async (name, fn) => { try { install(); await fn(); checks.push({ name, passed: true }); console.log('PASS ' + name); } catch (e) { checks.push({ name, passed: false, error: e.message }); console.error('FAIL ' + name + ': ' + e.message); } };

  await test('老板只核对工程已提交的两期约定，审批与意见集中且没有重复表单', async () => {
    login('boss'); open();
    assert.equal(page().data.canPlan, false, '工程合同约定不得重复手工编排');
    assert.equal(page().data.canSubmitExpense, false, '合同应收不能出现费用申请按钮');
    assert.equal(page().data.receivable.canReview, true);
    assert.deepEqual(page().data.receivable.rows.map(r => r.name), ['开工款', '封顶款']);
    assert.equal(page().data.receivable.totalText, '¥ 100,000');
    assert.equal(await (await runtime.currentPage()).$('button[data-action="plan-submit"]'), null);
    assert.equal(await (await runtime.currentPage()).$('button[data-action="expense-submit"]'), null);
    await (await element('textarea[data-key="note"]')).input('核对合同100000元，开工60000元、封顶40000元，一次确认');
    await (await element('.ops-check')).tap();
    await (await element('button[data-action="plan-approve"]')).tap();
    assert.equal(page().data.error, '');
    assert.equal(page().route, 'pages/admin/home/index', '确认成功应返回本岗位工作台');
    open();
    assert.equal(page().data.receivable.status, '已确认收款约定');
    assert.equal(await (await runtime.currentPage()).$('button[data-action="plan-approve"]'), null);
  });

  await test('工程人员与财务待确认时只读同一计划，不出现跨岗审批或费用表单', async () => {
    for (const [id, role] of [['engineering-dual', 'engineer'], ['finance-wang', 'finance']]) {
      login(id, role); open();
      assert.equal(page().data.receivable.canReview, false);
      assert(page().data.receivable.rows.every(row => !row.actionUrl));
      assert.equal(await (await runtime.currentPage()).$('button[data-action="plan-approve"]'), null);
      assert.equal(await (await runtime.currentPage()).$('button[data-action="expense-submit"]'), null);
      assert.equal(await (await runtime.currentPage()).$('textarea[data-key="note"]'), null);
    }
  });

  await test('已有旧批准约定时，老板看到的金额必须是本次待确认版本', () => {
    const key = 'zhongshu-management-v1', state = wx.getStorageSync(key);
    const old = state.operations.plans.find(p => p.projectId === project().projectId);
    const pending = copy(old);
    old.status = 'approved';
    old.installments[0].amount = 5000000;
    old.installments[1].amount = 5000000;
    Object.assign(pending, { id: 'latest-pending-plan', version: old.version + 1, status: 'pending' });
    state.operations.plans.push(pending);
    wx.setStorageSync(key, state);
    login('boss'); open();
    assert.equal(page().data.plan.id, pending.id);
    assert.deepEqual(page().data.receivable.rows.map(r => r.amount), [6000000, 4000000], '审批展示不能误用旧版已批准的五万/五万');
  });

  await test('真实合同PDF与图片使用原生预览，参考合同仍可页内查看', async () => {
    login('boss'); open();
    const oldDocument = wx.openDocument, oldImage = wx.previewImage, calls = [];
    wx.openDocument = args => calls.push(['document', args]);
    wx.previewImage = args => calls.push(['image', args]);
    try {
      page().previewFile({ currentTarget: { dataset: { file: { name: '已签合同.pdf', path: 'saved://contract.pdf' } } } });
      page().previewFile({ currentTarget: { dataset: { file: { name: '合同照片.jpg', path: 'saved://contract.jpg' } } } });
      assert.equal(calls[0][0], 'document');
      assert.equal(calls[0][1].filePath, 'saved://contract.pdf');
      assert.equal(calls[1][0], 'image');
      assert.deepEqual(calls[1][1].urls, ['saved://contract.jpg']);
      calls[0][1].fail();
      assert.match(page().data.error, /合同文件暂时无法打开/);
      await (await element('button.ar-reference-link')).tap();
      assert(page().data.samplePreview.sample, '参考合同应保留页内预览');
    } finally { wx.openDocument = oldDocument; wx.previewImage = oldImage; }
  });

  await test('一次确认接续一项开工款，足额登记后关闭待办，封顶前不开放封顶款', async () => {
    login('boss'); open();
    await (await element('textarea[data-key="note"]')).input('核对本版合同及两期金额');
    await (await element('.ops-check')).tap();
    await (await element('button[data-action="plan-approve"]')).tap();
    login('finance-wang', 'finance'); open();
    const start = page().data.receivable.rows.find(r => r.stage === 'start');
    const topping = page().data.receivable.rows.find(r => r.stage === 'topping');
    assert(start.actionUrl && start.actionLabel === '登记开工款');
    assert.equal(topping.actionUrl, '');
    assert.equal(topping.status, '待主体封顶');
    assert.equal(F.tasks(M.load()).filter(t => t.view === 'engineering-collection' && t.id === project().id + ':collection:start').length, 1);
    wx.reLaunch({ url: start.actionUrl });
    await (await element('input[data-key="amount"]')).input('60000');
    await (await element('input[data-key="transaction"]')).input('RECEIVABLES-START-ONCE');
    await (await element('button[data-sample-key="file"]')).tap();
    await (await element('button[data-action="collect"]')).tap();
    assert.equal(page().data.error, '');
    open();
    assert.equal(page().data.receivable.receivedText, '¥ 60,000');
    assert.equal(page().data.receivable.rows.find(r => r.stage === 'start').status, '已收齐');
    assert.equal(page().data.receivable.rows.find(r => r.stage === 'start').actionUrl, '');
    assert(!F.tasks(M.load()).some(t => t.view === 'engineering-collection' && t.id.startsWith(project().id + ':collection:')));
    login('engineering-dual', 'engineer');
    assert(R.tasks(R.get(project().id)).pending.some(t => t.url.includes('view=start')));
  });

  const out = path.resolve('qa/engineering-prepare-receivables-20261023');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'page-results.json'), JSON.stringify({ passed: checks.every(c => c.passed), scope: 'Isolated real Page/WXML controls, not native rendering', checks }, null, 2));
  if (checks.some(c => !c.passed)) process.exitCode = 1;
})().catch(e => { console.error(e.stack); process.exitCode = 1; });
