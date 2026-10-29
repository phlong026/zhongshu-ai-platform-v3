const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const output = path.resolve('qa/owner-workbench-port-20261023');
const clone = value => JSON.parse(JSON.stringify(value));

async function fixture() {
  const runtime = await require('./role-page-runtime.cjs').connect();
  const scenes = JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json', 'utf8'));
  const storage = scenes.find(scene => scene.action === 'material-terms')?.after;
  assert(storage, '材料金额已登记、原应收未收齐的连续流程夹具');
  for (const [key, value] of storage) wx.setStorageSync(key, clone(value));
  const A = require('../miniprogram/utils/account');
  const M = require('../miniprogram/pages/admin/model');
  const W = require('../miniprogram/pages/admin/owner-model');
  const X = require('../miniprogram/utils/access');
  const N = require('../miniprogram/utils/organization');
  A.mutate(state => A.loginDemo(state, 'boss'));
  const period = A.localDate().slice(0, 7);
  M.mutate(state => { state.period = period; });
  return { A, M, W, X, N, period, runtime };
}

async function main() {
  fs.mkdirSync(output, { recursive: true });
  const { M, W, X, N, period } = await fixture();
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };
  const open = url => { wx.reLaunch({ url }); const page = getCurrentPages().at(-1); assert(!page.data.error, page.data.error); return page; };

  let snapshot = W.snapshot();
  assert.deepEqual(N.navigation(X.context()).tabs.map(tab => tab.text), ['工作台', '业务', '工程', '我的']);
  assert(snapshot.commissions.length > 0);
  assert.equal(snapshot.metrics[0].value, snapshot.sales);
  pass('四栏目导航与设计委托金额使用同一事实口径');

  let page = open('/pages/admin/home/index');
  assert.equal(page.data.desk.home.six[2].value, String(snapshot.commissions.length));
  assert.deepEqual(page.data.desk.home.business.stages.map(row => row.label), require('../miniprogram/utils/business').STAGES);
  assert(page.data.homeTodos.length <= 4);
  pass('首页六项经营、分组待办与 V3 七阶段正常渲染');

  page = open('/pages/admin/finance/index');
  assert(page.data.desk.business);
  assert(page.data.desk.business.payable.rows.every(row => !/待审批/.test(row.tag)));
  assert.equal(require('../miniprogram/pages/admin/owner-business').rows('finance', snapshot).length, page.data.desk.business.receivable.rows.length + page.data.desk.business.payable.rows.length);
  pass('经营页仅汇总已批准未付应付，全部原记录保留可达 URL');

  page = open('/pages/admin/performance/index?history=1');
  assert.deepEqual(page.data.desk.performance.targets.map(row => row.label), ['设计委托金额', '回款金额', '有效客户', '工程节点']);
  assert(page.data.desk.performance.groups.flatMap(group => group.rows).every(row => row.url));
  pass('绩效公司目标与每位成员原记录入口完整');

  page.month({ detail: { value: '2026-01' } });
  assert.equal(page.data.desk.period, '2026-01');
  assert.equal(page.data.desk.performance.targets[0].value, '0');
  page.month({ detail: { value: period } });
  assert.equal(page.data.desk.performance.targets[0].value, snapshot.metrics[0].value);
  pass('月份切换重新按所选月份汇总，不混入当前月数据');

  W.saveGoals(period, { sales: '100000', collections: '80000', effective: '6', customers: '8', nodes: '5', staff: Object.fromEntries(snapshot.team.map(member => [member.id, member.business ? '50000' : '5'])) });
  const goals = W.snapshot().goals;
  assert.equal(goals.collections, 80000);
  assert.equal(goals.effective, 6);
  assert.equal(Object.keys(goals.staff).length, snapshot.team.length);
  pass('新增回款和有效客户目标保留原有个人目标与校验');

  snapshot = W.snapshot();
  assert(snapshot.state.receipts.filter(row => ['已核算', '已备案'].includes(row.status)).every(row => !row.registrationMode || row.registrationMode === 'record-only-v1'));
  pass('管理聚合保留财务实际登记与 record-only-v1 事实');

  const storage = wx.getStorageInfoSync().keys.filter(key => key.startsWith('zhongshu-')).map(key => [key, wx.getStorageSync(key)]);
  fs.writeFileSync(path.join(output, 'preview-storage.json'), JSON.stringify(storage, null, 2));
  fs.writeFileSync(path.join(output, 'workbench-results.json'), JSON.stringify({ result: 'passed', checks }, null, 2));
  console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
}

module.exports = { fixture };
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1; });
