/**
 * 加盟协同域 · 绩效提成经营接入层（阶段 3，对应后端 /franchise/performance/**）。
 * 序列化事实：taskId/policyId/payableId 为 Long 字符串（不透明值）；
 * 金额一律分（money 类任务 target/collected 与三组指标金额键）；计数类任务 target/collected 为个数原值；
 * period='YYYY-MM'；写命令一律带 Idempotency-Key；错误码 1_116 段由 utils/platform-performance.js 映射文案。
 */
const http = require('../http');

const idem = (key, action) => (key
  ? { 'Idempotency-Key': key }
  : { 'Idempotency-Key': 'perf-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const goals = {
  /** 三级目标查询（含 locked 派生——归档期拒改） */
  get: (period) => http.get('/franchise/performance/goals', { period }),
  /** 保存（幂等，manage）：公司级 sales/collections/effective/customers/nodes + 部门级/人员部门级/旧标量 JSONB */
  save: (data, key) => http.put('/franchise/performance/goals', data, idem(key, 'goal'))
};

const tasks = {
  /** 派发（幂等，manage）：{period,title,assigneeUserId,assigneeName,assigneeDept,type,targetCents,due} */
  create: (data, key) => http.post('/franchise/performance/tasks', data, idem(key, 'create')),
  /** 范围列表（scope=mine|company|department|member；行含实时 auto 进度 collected/status） */
  list: (period, scope, dept, userId) => http.get('/franchise/performance/tasks', {
    period, scope: scope || 'mine', ...(dept ? { dept } : {}), ...(userId ? { userId } : {})
  }),
  /** 三组指标汇总（业务7/工程4/财务1+目标对照） */
  summary: (period) => http.get('/franchise/performance/tasks/summary', { period }),
  /** 改派（幂等，manage；manualDone 清空+留痕） */
  reassign: (id, assigneeUserId, assigneeName, key) =>
    http.post('/franchise/performance/tasks/' + id + '/reassign', { assigneeUserId, assigneeName: assigneeName || '' }, idem(key, 'reassign')),
  /** manualDone 本人补填（note≤500+photos≤6；photos 随 COS 批降级） */
  completeManual: (id, note, photos, key) =>
    http.post('/franchise/performance/tasks/' + id + '/manual-complete', { note, photos: photos || [] }, idem(key, 'manual')),
  /** 按上月实绩生成建议任务（幂等，manage）→ {created,replayed} */
  initializePrevious: (period, key) =>
    http.post('/franchise/performance/tasks/initialize-previous', { period }, idem(key, 'init')),
  /** 归档（幂等，manage）：快照 tasks+sourceSignature；有未决申诉阻断 */
  archive: (period, note, key) => http.post('/franchise/performance/tasks/archive', { period, note }, idem(key, 'archive')),
  /** 归档修订（幂等，manage；仅当 sourceSignature 变化，否则 003） */
  revise: (period, note, key) => http.post('/franchise/performance/tasks/archive/revise', { period, note }, idem(key, 'revise')),
  /** 归档快照列表（含 needsRevision 派生） */
  archives: (period) => http.get('/franchise/performance/tasks/archives', { period })
};

const cycles = {
  /** 提交复核（本人或 manage 代交；冻结签名+submission） */
  submit: (period, userId, note, key) =>
    http.post('/franchise/performance/cycles/submit', { period, userId, note }, idem(key, 'csubmit')),
  /** 复核（manage；不能复核本人 000；签名变化须退回） */
  review: (period, userId, approve, note, key) =>
    http.post('/franchise/performance/cycles/review', { period, userId, approve, note: note || '' }, idem(key, 'creview')),
  /** 申诉裁决（manage=另一位老板；采纳→adjustments 生效） */
  appealResolve: (period, userId, adopt, sourceKey, personUserId, excluded, note, key) =>
    http.post('/franchise/performance/cycles/appeal-resolve',
      { period, userId, adopt, sourceKey, personUserId: personUserId || null, excluded: !!excluded, note }, idem(key, 'appeal')),
  /** 周期列表 */
  list: (period) => http.get('/franchise/performance/cycles', { period })
};

const commission = {
  /** 对账行（每策略×两阶段五态状态机+paid/outstanding/reviewDue） */
  rows: () => http.get('/franchise/performance/commission/rows'),
  /** 提成规则（幂等；严格 boss——owner 持码越界 000；已有 release 硬锁 004） */
  policy: (projectId, totalCents, assigneeUserId, assigneeName, evidenceRef, note, key) =>
    http.post('/franchise/performance/commission/policy',
      { projectId, totalCents, assigneeUserId, assigneeName: assigneeName || '', evidenceRef, note }, idem(key, 'policy')),
  /** 生成提成应付（幂等；finance 族；净实收足额 007；agreementRef=perf-commission 兜底标识） */
  release: (projectId, stage, key) =>
    http.post('/franchise/performance/commission/release', { projectId, stage }, idem(key, 'release')),
  /** 人工核对（幂等；严格 boss；刷新收款签名；仍须足额 007） */
  reconcile: (projectId, stage, evidenceRef, note, key) =>
    http.post('/franchise/performance/commission/reconcile', { projectId, stage, evidenceRef, note }, idem(key, 'reconcile'))
};

const operating = {
  /** 实时经营分析（近6月趋势/收支构成/应收应付/回款率/资金覆盖） */
  analytics: (period) => http.get('/franchise/performance/operating/analytics', { period }),
  /** 经营快照归档（幂等；只允许过去月 008；签名未变 002；变化 version+1 supersedes） */
  snapshot: (period, note, key) => http.post('/franchise/performance/operating/snapshot', { period, note }, idem(key, 'snap')),
  /** 快照列表 */
  snapshots: (period) => http.get('/franchise/performance/operating/snapshots', { period }),
  /** 月末对账（幂等；按月覆盖写） */
  reconcile: (period, key) => http.post('/franchise/performance/operating/reconcile', { period }, idem(key, 'mreconcile')),
  /** 对账记录列表 */
  reconciliations: () => http.get('/franchise/performance/operating/reconciliations')
};

module.exports = { goals, tasks, cycles, commission, operating };
