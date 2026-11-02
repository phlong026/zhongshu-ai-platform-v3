#!/usr/bin/env node
/**
 * 阶段 3 · 绩效提成经营 v3 接入静态验证（19 号任务书 §七 接入面 / AC14）。
 * 断言：契约层端点齐全（五组）、纯函数层纯性与单位口径、三页 API 分支与降级路径、
 * 蓝本拍板一致性（financeReviews 不建/目标页只读不接/提成命名避让 commission 裸名）、check 回归另跑。
 * 用法：node scripts/verify-performance-pages.cjs
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const ROOT = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
let pass = 0, fail = 0;
function ok(label, cond) {
  if (cond) { pass++; console.log('  ✅ ' + label); }
  else { fail++; console.log('  ❌ ' + label); }
}

console.log('[1] 契约层 api/franchise/performance.js');
const api = read('miniprogram/api/franchise/performance.js');
for (const [label, endpoint] of [
  ['goals.get', '/franchise/performance/goals'],
  ['goals.save', 'http.put'],
  ['tasks.create', "idem(key, 'create')"],
  ['tasks.list', "/franchise/performance/tasks'"],
  ['tasks.summary', '/tasks/summary'],
  ['tasks.reassign', '/reassign'],
  ['tasks.manual', '/manual-complete'],
  ['tasks.initialize', '/initialize-previous'],
  ['tasks.archive', '/tasks/archive\''],
  ['tasks.revise', '/archive/revise'],
  ['tasks.archives', '/tasks/archives'],
  ['cycles.submit', '/cycles/submit'],
  ['cycles.review', '/cycles/review'],
  ['cycles.appeal', '/appeal-resolve'],
  ['commission.rows', '/commission/rows'],
  ['commission.policy', '/commission/policy'],
  ['commission.release', '/commission/release'],
  ['commission.reconcile', '/commission/reconcile'],
  ['operating.analytics', '/operating/analytics'],
  ['operating.snapshot', '/operating/snapshot'],
  ['operating.reconcile', '/operating/reconcile'],
  ['operating.reconciliations', '/operating/reconciliations']
]) ok(label, api.includes(endpoint));
ok('写命令一律带 Idempotency-Key', (api.match(/idem\(key/g) || []).length >= 15);
ok('提成命名避让 commission 裸名（文件为 performance.js）', fs.existsSync(path.join(ROOT, 'miniprogram/api/franchise/performance.js')));

console.log('[2] 纯函数层 utils/platform-performance.js');
const P = require(path.join(ROOT, 'miniprogram/utils/platform-performance.js'));
ok('模块可加载（无 wx 顶层依赖崩溃）', typeof P.taskView === 'function');
ok('类型表排除 financeReviews（19 §九）', !P.TYPES.some(t => t.id === 'auto:financeReviews'));
ok('money 类双单位口径', P.isMoneyType('auto:collectionsAmount') && !P.isMoneyType('auto:completedNodes'));
ok('toTargetCents：元→分 / 计数原值', P.toTargetCents('auto:collectionsAmount', '100') === 10000
  && P.toTargetCents('auto:completedNodes', '3') === 3);
ok('valueText：分→元 / 计数原值', P.valueText('auto:contract', 9340000) === '93400'
  && P.valueText('auto:completedNodes', 2) === '2');
ok('period 工具', P.validPeriod('2026-11') && !P.validPeriod('2026-13')
  && P.previousPeriod('2026-01') === '2025-12' && /^\d{4}-\d{2}$/.test(P.currentPeriod()));
ok('错误码八码映射', P.errorText({ code: 1116000004 }).includes('锁定')
  && P.errorText({ code: 1116000007 }).includes('净实收尚未足额'));
ok('taskView：状态/进度/manualDone 透传', (() => {
  const v = P.taskView({ id: 5, type: 'auto:completedNodes', targetCents: 2, collected: 2, status: '已完成',
    assigneeUserId: 9, assigneeName: '张三', history: [] });
  return v.id === '5' && v.status === '已完成' && v.percent === 100 && v.typeLabel === '完成节点';
})());
ok('taskView：money 类 collected/target 除以 100', (() => {
  const v = P.taskView({ id: 6, type: 'auto:collectionsAmount', targetCents: 100000000, collected: 40000000,
    status: '进行中', assigneeUserId: 9, history: [] });
  return v.target === 1000000 && v.collected === 400000 && Math.round(v.percent) === 40;
})());
ok('commissionRowView：五态按钮矩阵', (() => {
  const v = P.commissionRowView({ policyId: 1, projectId: 7, totalCents: 20000, assigneeName: '张', version: 1,
    stages: [{ stage: 'START', label: '开工款', status: '可生成应付', eligible: true, payableId: null,
      reviewDue: false, requiredCents: 40000000, receivedCents: 40000000, amountCents: 10000,
      paidCents: 0, outstandingCents: 10000 }] }, 'finance');
  const st = v.stages[0];
  return st.canRelease === true && st.canPay === false && st.canReconcile === false
    && st.reason.includes('足额');
})());
ok('platGroups：三组合成+目标对照（rows 空、wxml 不动）', (() => {
  const v = P.platGroups({ summary: { departments: { engineering: { 'auto:completedNodes': 2 } } },
    goals: { departmentGoals: JSON.stringify({ engineering: { 'auto:completedNodes': 3 } }) } });
  const eng = v.groups.find(g => g.key === 'engineering');
  return v.groups.length === 3 && eng.metrics[0].actual === 2 && eng.metrics[0].target === 3
    && eng.rows.length === 0 && v.summaryNote.includes('团队任务');
})());
ok('API 态可用性判定', typeof P.online === 'function');

console.log('[3] 三页接入与降级路径');
const tasks = read('miniprogram/pages/admin/tasks/index.js');
ok('tasks 页 API 分流（refreshOnline）', tasks.includes('refreshOnline'));
ok('tasks 页六动作分流（P.perform）', ['create', 'reassign', 'manual', 'initialize', 'archive', 'revise']
  .every(a => tasks.includes("P.perform('" + a + "'")));
ok('tasks 页失败降级（errorText）', tasks.includes('P.errorText'));
const commission = read('miniprogram/pages/admin/commission/index.js');
ok('commission 页 API 分流（refreshOnline）', commission.includes('refreshOnline'));
ok('commission 页三分流（policy/release/reconcile）', ['policy', 'release', 'reconcile']
  .every(a => commission.includes("P.perform('" + a + "'")));
ok('commission 页严格角色透传（role→按钮矩阵）', commission.includes('P.commissionRowView(row, actor.role)'));
const ownerPage = read('miniprogram/pages/admin/owner-page.js');
ok('owner-page 绩效 Tab 平台汇总（platGroups+refreshPlatSummary）',
  ownerPage.includes('platGroups') && ownerPage.includes('refreshPlatSummary'));
ok('owner-page 降级本地（非 online 走 P.build）', ownerPage.includes('P.build(desk'));

console.log('[4] 拍板一致性');
const pf = read('miniprogram/utils/platform-performance.js');
ok('蓝本目标页只读不接（无 goals 编辑 UI 面——O6 拍板一致）',
  !read('miniprogram/pages/admin/owner-page.js').includes("P.perform('goal-save'"));
ok('Long 字符串不透明', pf.includes('String(row.id)'));
ok('周期提交/复核/申诉在 cycles 组（本批后端就绪、UI 面随任务页归档流）',
  api.includes('/cycles/submit') && api.includes('/cycles/review'));

console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败');
process.exit(fail ? 1 : 0);
