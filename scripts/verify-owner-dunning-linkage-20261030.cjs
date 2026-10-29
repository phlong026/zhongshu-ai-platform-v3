const assert = require('node:assert/strict');

async function main() {
  const { A, W, runtime } = await require('./verify-owner-workbench.cjs').fixture();
  const Engineering = require('../miniprogram/utils/engineering-records');

  const first = W.snapshot().projects[0];
  assert(first, '连续项目夹具必须包含工程记录');
  const book = wx.getStorageSync('zhongshu-management-v1');
  const plan = book.operations.plans.find(row => row.projectId === first.projectId);
  const topping = plan?.installments.find(row => row.stage === 'topping');
  assert(topping, '同一 projectId 必须包含封顶款约定');
  topping.due = '2026-10-01';
  wx.setStorageSync('zhongshu-management-v1', book);
  const engineeringState = wx.getStorageSync('zhongshu-engineering-v1');
  const engineeringProject = engineeringState.projects.find(row => row.id === first.id);
  engineeringProject.recordFlow.topping = { date:'2026-09-30', file:{ name:'封顶验收.pdf', sample:true } };
  wx.setStorageSync('zhongshu-engineering-v1', engineeringState);

  A.mutate(state => A.loginDemo(state, 'boss'));
  const snapshot = W.snapshot();
  const row = require('../miniprogram/utils/operating-report')
    .analytics(snapshot.state, snapshot.period, snapshot.today).receivableRows
    .find(item => item.projectId === first.projectId && item.id.endsWith(':topping'));
  assert(row, '同一项目必须出现封顶款应收');

  wx.reLaunch({ url:'/pages/admin/detail/index?view=receivable&id=' + encodeURIComponent(row.id) });
  let page = await runtime.currentPage();
  const rawBeforeCopy = Engineering.get(first.id);
  let clipboard = '';
  wx.setClipboardData = options => { clipboard = options.data; options.success?.({}); };
  const copy = await page.$('.owner-reminder-copy');
  assert(copy, '实时应收原单必须渲染复制催款话术按钮');
  await copy.tap();
  assert(clipboard.includes('封顶款'), '复制内容必须来自当前应收期次');
  assert.deepEqual(Engineering.get(first.id), rawBeforeCopy, '复制话术不得伪造已联系或新增催收记录');

  const link = await page.$('.owner-dunning-link');
  assert(link, '老板应收原单必须渲染原工程催收记录入口');
  await link.tap();
  page = await runtime.currentPage();
  assert.equal(page.path, 'pages/build/record/index');
  const collection = getCurrentPages().at(-1);
  assert.equal(collection.data.p.projectId, first.projectId, '跳转后必须保持同一 projectId');
  assert.equal(collection.data.p.id, first.id, '跳转必须使用工程记录 ID');
  assert.equal(collection.data.view, 'collection');
  assert.equal(collection.data.stage, 'topping');
  assert.equal(collection.data.actions.length, 0, '老板只读，不得代替工程登记催收');

  const managerId = require('../miniprogram/utils/access').managerId(Engineering.get(first.id));
  const managerAccount = require('../miniprogram/utils/demo-users').people.find(person => person.staffId === managerId);
  assert(managerAccount, '工程负责人必须有可用的测试身份');
  A.mutate(state => A.loginDemo(state, managerAccount.id));
  const beforeTasks = Engineering.tasks(Engineering.get(first.id));
  assert(beforeTasks.pending.some(task => task.id.endsWith(':record:collect-topping')), '逾期未催收前必须是工程待办');
  const engineering = Engineering.get(first.id);
  Engineering.run(first.id, 'dunning', { stage:'topping', note:'已联系业主，等待付款' }, engineering.revision);
  const afterTasks = Engineering.tasks(Engineering.get(first.id));
  assert(!afterTasks.pending.some(task => task.id.endsWith(':record:collect-topping')));
  assert(afterTasks.waiting.some(task => task.id.endsWith(':record:collect-topping') && task.title.includes('已催收登记')),
    '工程登记后，逾期催收应从待办转为等待业主付款');

  A.mutate(state => A.loginDemo(state, 'boss'));
  wx.reLaunch({ url:'/pages/admin/detail/index?view=receivable&id=' + encodeURIComponent(row.id) });
  const ownerRecord = getCurrentPages().at(-1).data.record;
  assert.equal(ownerRecord.dunning.count, 1, '老板刷新原应收后应看到同一工程催收记录');
  assert(ownerRecord.dunning.status.includes('已催收'), '老板只读状态应反映工程最新登记');

  await runtime.disconnect();
  console.log(JSON.stringify({ result:'passed', checks:13 }));
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
