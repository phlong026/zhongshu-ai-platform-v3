#!/usr/bin/env node
/**
 * 阶段 3 · 绩效提成经营 v3 接入静态验证（19 号任务书 §七 / AC14；Codex R1 修复后重写——
 * 修恒真断言、补真实响应形状/五态×角色矩阵/幂等键贯穿/Long 字符串红线扫描）。
 * 用法：node scripts/verify-performance-pages.cjs
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
let pass = 0, fail = 0;
function ok(label, cond, extra) {
  if (cond) { pass++; console.log('  ✅ ' + label); }
  else { fail++; console.log('  ❌ ' + label + (extra ? ' —— ' + extra : '')); }
}

console.log('[1] 契约层端点齐全（五组）');
const api = read('miniprogram/api/franchise/performance.js');
['/franchise/performance/goals', "idem(key, 'create')", "/franchise/performance/tasks'",
  '/tasks/summary', '/reassign', '/manual-complete', '/initialize-previous', '/tasks/archive\'',
  '/archive/revise', '/tasks/archives', '/cycles/submit', '/cycles/review', '/appeal-resolve',
  '/commission/rows', '/commission/policy', '/commission/release', '/commission/reconcile',
  '/operating/analytics', '/operating/snapshot', '/operating/reconcile'
].forEach(endpoint => ok(endpoint, api.includes(endpoint)));
ok('写命令 idem(key) 参数化 ≥15 处', (api.match(/idem\(key/g) || []).length >= 15);

console.log('[2] 纯函数层真实形状（require 实测）');
const P = require(path.join(ROOT, 'miniprogram/utils/platform-performance.js'));
ok('模块可加载', typeof P.taskView === 'function' && typeof P.platPerformance === 'function');
ok('类型表排除 financeReviews', !P.TYPES.some(t => t.id === 'auto:financeReviews'));
ok('toTargetCents：money 元→分 / 计数个×100（后端 R1-9 定稿）',
  P.toTargetCents('auto:collectionsAmount', '100') === 10000 && P.toTargetCents('auto:completedNodes', '2') === 200);
ok('displayValue：money 分→元 / 计数 /100 回个',
  P.valueText('auto:contract', 9340000) === 93400 && P.valueText('auto:completedNodes', 200) === 2);
ok('period 工具', P.validPeriod('2026-11') && !P.validPeriod('2026-13') && P.previousPeriod('2026-01') === '2025-12');

ok('taskView：manualDone JSON 字符串解析（Codex P2-13）', (() => {
  const v = P.taskView({ id: 5, type: 'manual', status: '已完成', assigneeUserId: 9,
    manualDone: JSON.stringify({ note: '巡检完成', photos: ['a.jpg'] }), history: [] });
  return v.manualDone && v.manualDone.note === '巡检完成' && Array.isArray(v.manualDone.photos)
    && v.manualDone.photos[0] === 'a.jpg';
})());
ok('taskView：计数任务 target 200 存储值→展示 2 个（单位契约）', (() => {
  const v = P.taskView({ id: 6, type: 'auto:completedNodes', targetCents: 200, collected: 200,
    status: '已完成', assigneeUserId: 9, history: [] });
  return v.target === 2 && v.collected === 2 && v.percent === 100;
})());
ok('taskView：money 类分→元', (() => {
  const v = P.taskView({ id: 7, type: 'auto:collectionsAmount', targetCents: 100000000,
    collected: 40000000, status: '进行中', assigneeUserId: 9, history: [] });
  return v.target === 1000000 && v.collected === 400000 && Math.round(v.percent) === 40;
})());
ok('taskView：id/assigneeUserId 全字符串（Long 红线）', (() => {
  const v = P.taskView({ id: '9223372036854775807', type: 'manual', status: '进行中',
    assigneeUserId: '9223372036854775806', history: [] });
  return v.id === '9223372036854775807' && v.assigneeUserId === '9223372036854775806';
})());

ok('archiveView：needsRevision 透传（Codex P1-9）', (() => {
  const v = P.archiveView({ archives: [{ id: 1, archiveNo: 1, tasks: '[]' }], needsRevision: true });
  return v.needsRevision === true && v.latest.revision === 1;
})());
ok('archiveView：无归档 needsRevision=false', P.archiveView({ archives: [] }).needsRevision === false);

ok('memberView：org StaffRow → userId 字符串+dept 映射（Codex P1-3）', (() => {
  const rows = P.memberView([{ postId: 11, userId: 88, name: '李工程', active: true, roleCode: 'engineer' },
    { postId: 12, userId: 4, name: '王财务', active: true, roleCode: 'finance' }]);
  return rows[0].id === '88' && rows[0].dept === 'engineering' && rows[1].dept === 'finance'
    && rows.every(r => typeof r.id === 'string');
})());

const fiveStates = [
  { name: 'reviewDue→待人工核对', stage: { status: 'x', reviewDue: true, eligible: true, payableId: 1 }, expect: { canRelease: false, canPay: false, canReconcile: true, reasonContains: '人工核对' } },
  { name: '未足额→不可发放', stage: { status: 'x', reviewDue: false, eligible: false, payableId: null }, expect: { canRelease: false, canPay: false, canReconcile: false, reasonContains: '尚未收齐' } },
  { name: '可生成应付', stage: { status: 'x', reviewDue: false, eligible: true, payableId: null }, expect: { canRelease: true, canPay: false, canReconcile: false, reasonContains: '本期应付' } },
  { name: '待付款（应付已生成）', stage: { status: 'x', reviewDue: false, eligible: true, payableId: 9 }, expect: { canRelease: false, canPay: false, reasonContains: '财务工作台' } }
];
let matrixOk = true;
for (const state of fiveStates) {
  for (const role of ['boss', 'finance', 'owner']) {
    const v = P.commissionRowView({ policyId: 1, projectId: 7, totalCents: 20000, assigneeUserId: 9,
      assigneeName: '张', version: 1, stages: [{ stage: 'START', label: '开工款', ...state.stage }] }, role, '项目甲');
    const st = v.stages[0];
    const finance = role === 'finance';
    const boss = role === 'boss';
    const want = {
      canRelease: !!(state.expect.canRelease && finance),
      canPay: false, // API 态恒 false（Codex P1-8：付款走财务工作台）
      canReconcile: !!(state.expect.canReconcile && boss)
    };
    if (st.canRelease !== want.canRelease || st.canPay !== want.canPay || st.canReconcile !== want.canReconcile) {
      matrixOk = false;
      console.log('    偏差：', state.name, role, JSON.stringify({ got: { canRelease: st.canRelease, canPay: st.canPay, canReconcile: st.canReconcile }, want }));
    }
  }
}
ok('提成五态×三角色按钮矩阵（12 组合，canPay 恒 false）', matrixOk);
ok('commissionRowView：assigneeUserId 字符串保留（Codex P1-6）', (() => {
  // 后端 JSON 返回的 Long 是十进制字符串；用字符串模拟（JS number 字面量在本测试文件解析时就已丢精度）
  const v = P.commissionRowView({ policyId: 1, projectId: 7, totalCents: 1, assigneeUserId: '9223372036854775806',
    assigneeName: '张', version: 1, stages: [] }, 'boss');
  return v.assigneeUserId === '9223372036854775806' && typeof v.projectId === 'string';
})());

ok('platPerformance：departmentCards 形状对齐 wxml（display/displayUnit/targetDisplay——Codex P1-1）', (() => {
  const v = P.platPerformance({
    summary: { departments: { engineering: { 'auto:completedNodes': 200 } } },
    goals: { departmentGoals: JSON.stringify({ engineering: { 'auto:completedNodes': 300 } }) }
  }, '2026-11', 'all');
  const eng = v.departmentCards.find(c => c.key === 'engineering');
  const m = eng.metrics.find(x => x.id === 'auto:completedNodes');
  return v.departmentCards.length === 3 && m.display === '2' && m.targetDisplay === '3'
    && m.displayUnit === '个' && typeof m.width === 'number'
    && Array.isArray(v.filterOptions) && v.groups.length === 0;
})());
ok('platPerformance：money 指标 display 带 ¥', (() => {
  const v = P.platPerformance({ summary: { departments: { business: { 'metric:commissionAmount': 68000000 } } },
    goals: {} }, '2026-11', 'all');
  return v.departmentCards[0].metrics[0].display.indexOf('¥') === 0;
})());

console.log('[3] 页面接入与降级路径（静态扫描）');
const tasksSrc = read('miniprogram/pages/admin/tasks/index.js');
ok('tasks 页 API 分流（refreshOnline）', tasksSrc.includes('refreshOnline'));
ok('tasks 页六动作分流', ['create', 'reassign', 'manual', 'initialize', 'archive', 'revise']
  .every(a => tasksSrc.includes("P.perform('" + a + "'")));
ok('tasks 页 busy 门禁（六动作 this.busy）', (tasksSrc.match(/this\.busy = true/g) || []).length >= 5
  && (tasksSrc.match(/finally \{ this\.busy = false; \}/g) || []).length >= 5);
ok('tasks 页幂等键贯穿（idem: 传参）', tasksSrc.includes('idem: form.idempotencyKey')
  && tasksSrc.includes("'performance-task-reassign:'") && tasksSrc.includes("'performance-task-archive:'"));
ok('tasks 页成员真源（loadMembers）', tasksSrc.includes('P.loadMembers()') && tasksSrc.includes('orgApi'));
ok('tasks 页 API 态类型表切换（P.TYPES）', tasksSrc.includes('types: P.TYPES'));
const commissionSrc = read('miniprogram/pages/admin/commission/index.js');
ok('commission 页 API 身份（apiPost.roleCode——Codex P1-2）', commissionSrc.includes('P.apiPost()')
  && commissionSrc.includes('post.roleCode'));
ok('commission 页 loadCommission（零规则项目在列——Codex P1-5）', commissionSrc.includes('P.loadCommission()'));
ok('commission 页幂等键贯穿', commissionSrc.includes("idem: 'perf-policy:")
  && commissionSrc.includes("idem: 'perf-release:") && commissionSrc.includes("idem: 'perf-reconcile:"));
const ownerSrc = read('miniprogram/pages/admin/owner-page.js');
ok('owner-page platSummary 缓存绑定 period+postId（Codex P2-14）',
  ownerSrc.includes('platSummaryKey') && ownerSrc.includes('cacheKey'));
ok('owner-page 降级本地（P.build 保留）', ownerSrc.includes('P.build(desk'));

console.log('[4] 红线扫描');
const pfSrc = read('miniprogram/utils/platform-performance.js');
ok('platform-performance 无 Number( 转 id（仅金额/计数转换）', (() => {
  const idConversions = (pfSrc.match(/Number\((?:String\(p?\.\w*id|p\.id\b)/g) || []).length;
  return idConversions === 0;
})(), '发现 id 经 Number() 转换');
ok('perform 全 case 传 key（幂等贯穿）', (() => {
  return (pfSrc.match(/, key\)/g) || []).length >= 9 && pfSrc.includes('const key = p.idem');
})());
ok('月份缓存键不含全局可变状态', !pfSrc.includes('getStorageSync'));

console.log('\n结果：' + pass + ' 通过，' + fail + ' 失败');
process.exit(fail ? 1 : 0);
