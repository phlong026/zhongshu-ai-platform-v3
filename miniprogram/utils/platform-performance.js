/**
 * 绩效提成经营纯函数层（阶段 3 / Codex R1 修复版）：后端英文字段 → 页面展示态适配、
 * period 工具、金额/计数双单位任务视图、提成对账行适配、1_116 错误码文案、在线编排。
 * 后端契约见 api/franchise/performance.js；离线或失败由页面展示错误态，不回落本地账簿。
 *
 * 红线（Codex v3 评审 R1 全修）：
 * - Long id 全程十进制字符串（禁 Number()——雪花 id 超 2^53 精度丢失）；金额/计数单独转换
 * - API 身份源=auth.currentApiPost()（roleCode/orgId）；demo 身份（X.context()）只用于演示分支
 * - 幂等键贯穿：payload.idem 显式传 api 层（form 键失败重试复用，成功后由页面换键）
 * - 单位口径（后端 R1-9 修后定稿）：money 类=cents；计数类=个×100 存储（与任务书 D-10 一致），
 *   适配层对外统一换算为「元 / 个」展示值
 * - 成员选人=org 真源（/platform/org/staff），部门由 roleCode 映射；选人缺失降级任务行派生
 */
const api = require('../api/franchise/performance');
const http = require('../api/http');
const auth = require('../api/auth');
const orgApi = require('../api/org');

// ===================== 常量与工具 =====================

/** 任务类型标签（后端合法集；financeReviews 不建——19 §九） */
const TYPES = [
  { id: 'manual', label: '自定义任务', unit: '', manual: true },
  { id: 'auto:collectionsAmount', label: '回款金额', unit: '元', money: true },
  { id: 'auto:contract', label: '签约金额', unit: '元', money: true },
  { id: 'auto:completedNodes', label: '完成节点', unit: '个' },
  { id: 'auto:onTimeNodes', label: '按期节点', unit: '个' },
  { id: 'auto:publications', label: '内容发布', unit: '条' },
  { id: 'auto:effectiveLeads', label: '有效线索', unit: '条' },
  { id: 'auto:qualifiedDeliveries', label: '到货合格批次', unit: '批' },
  { id: 'auto:approvedDeliverables', label: '审核通过设计成果', unit: '套' },
  { id: 'auto:financeRegistrations', label: '实际收付登记', unit: '笔' }
];
const typeMeta = id => TYPES.find(t => t.id === id) || { id, label: id, unit: '', manual: false };
const isMoneyType = id => !!typeMeta(id).money;
/** 三组指标键 → 展示名（summary.departments 键形如 metric:commissionAmount） */
const METRIC_LABEL = {
  'metric:commissionAmount': '设计委托金额', 'auto:collectionsAmount': '项目实际回款',
  'metric:effectiveCustomers': '有效跟进客户', 'metric:activityTotal': '发布与有效线索',
  'auto:publications': '内容发布', 'auto:effectiveLeads': '有效线索',
  'auto:approvedDeliverables': '审核通过成果', 'auto:completedNodes': '完成节点',
  'auto:onTimeNodes': '按期节点', 'metric:firstPassNodes': '首验通过节点',
  'auto:qualifiedDeliveries': '合格供货批次', 'auto:financeRegistrations': '实际收付登记'
};
const METRIC_MONEY = new Set(['metric:commissionAmount', 'auto:collectionsAmount']);
const DEPT_LABEL = { business: '业务组', engineering: '工程组', finance: '财务组' };
/** 岗位角色 → 绩效分组（蓝本 DEPARTMENTS roles 映射） */
const ROLE_DEPT = {
  sales: 'business', 'sales-lead': 'business', 'hq-operations': 'business',
  'hq-operations-lead': 'business', 'hq-design-admin': 'business',
  engineer: 'engineering', 'project-manager': 'engineering',
  finance: 'finance', 'finance-lead': 'finance'
};

const validPeriod = period => /^\d{4}-(0[1-9]|1[0-2])$/.test(String(period || ''));
const currentPeriod = () => {
  const d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
};
const monthEnd = period => new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
const previousPeriod = period => {
  const d = new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)) - 2, 1));
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0');
};
/** 分→元文本 */
const centsText = cents => cents == null ? '—' : '¥ ' + (cents / 100).toLocaleString('en-US');
/** 表单元输入 → 后端 target（money 类元→分；计数类个→×100 存储对齐 D-10/后端 R1-9） */
const toTargetRaw = (type, raw) => {
  const v = Number(String(raw == null ? '' : raw).trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return Math.round(typeMeta(type).money ? v * 100 : v * 100);
};
/** 后端存储值 → 展示值（money 类分→元；计数类 /100 回个） */
const displayValue = (type, stored) => typeMeta(type).money
  ? (stored || 0) / 100
  : Math.round((stored || 0) / 100 * 1000) / 1000;
/** 后端 JSONB 字符串安全解析 */
function safeParse(value, fallback) {
  if (value == null) return fallback;
  if (typeof value === 'object') return value;
  try {
    const parsed = JSON.parse(value);
    return parsed == null ? fallback : parsed;
  } catch (_) {
    return fallback;
  }
}

/** API 岗位身份（null=未登录后端；演示分支用 demo 身份 X.context()） */
function apiPost() {
  try {
    return online() ? auth.currentApiPost() : null;
  } catch (_) {
    return null;
  }
}

/** API 态可用性（阶段 1.5/2 同款 http.token() 分流） */
const online = () => !!http.token();

/** 1_116 错误码 → 展示文案（未识别透传 msg） */
const errorText = err => {
  const raw = err && (err.data || err);
  const code = raw && raw.code;
  const map = {
    1116000000: '当前身份无权办理本操作（越权或不能复核本人）',
    1116000002: '目标已随归档锁定，或经营快照签名未变化',
    1116000003: '周期操作前置不满足（退回条件/申诉阻断归档/修订签名未变）',
    1116000004: '提成规则已生成应付，整行锁定不可修改',
    1116000005: '任务操作前置不满足（归档禁改/改派校验/补填条件）',
    1116000006: '本阶段尚未生成提成应付',
    1116000007: '该期净实收尚未足额，不能办理本操作',
    1116000008: '周期或日期不合法（截止日须在任务月份内/快照须为过去月）'
  };
  if (map[code]) return map[code];
  return (raw && raw.msg) || (err && err.message) || '操作失败，请稍后重试';
};

// ===================== 任务视图适配 =====================

/** 后端任务行 → tasks 页 row 形状（progress 圆环/文本标签；manualDone/history 安全解析） */
function taskView(row) {
  const meta = typeMeta(row.type);
  const manual = row.type === 'manual';
  const completed = row.status === '已完成';
  const target = displayValue(row.type, row.targetCents);
  const collected = displayValue(row.type, row.collected);
  const raw = manual ? (completed ? 100 : 0)
    : target > 0 ? collected / target * 100 : 0;
  const percent = Math.round(raw * 10) / 10;
  const degrees = Math.max(0, Math.min(100, percent)) * 3.6;
  const historyRows = safeParse(row.history, []);
  const lastHistory = historyRows[historyRows.length - 1] || {};
  const suggestionRow = historyRows.find(h => h.action === 'create' && h.suggestion);
  const manualDone = safeParse(row.manualDone, null);
  if (manualDone && manualDone.photos && !Array.isArray(manualDone.photos)) {
    manualDone.photos = [];
  }
  return {
    ...row,
    id: String(row.id),
    assigneeId: String(row.assigneeUserId == null ? '' : row.assigneeUserId),
    assigneeUserId: String(row.assigneeUserId == null ? '' : row.assigneeUserId),
    target, collected,
    status: completed ? '已完成' : '进行中',
    manualDone,
    suggestion: suggestionRow ? suggestionRow.suggestion : null,
    history: historyRows.map(h => ({ ...h, from: h.fromUserId, to: h.toUserId })),
    percent, progressRight: Math.min(180, degrees), progressLeft: Math.max(0, degrees - 180),
    typeLabel: meta.label, unit: meta.unit,
    targetText: manual ? '提交说明（照片选填）' : String(target) + meta.unit,
    collectedText: manual ? (completed ? '已提交凭证' : '待提交凭证') : String(collected) + meta.unit,
    suggestionText: suggestionRow ? '依据 ' + suggestionRow.suggestion.period + ' 真实实绩 '
      + displayValue(row.type, suggestionRow.suggestion.value) + (suggestionRow.suggestion.unit || '') : '',
    suggestionSources: [],
    historyText: lastHistory.action === 'reassign' ? '任务已改派'
      : lastHistory.action === 'manual-complete' ? '已提交手动完成凭证' : '已派发'
  };
}

/** 归档快照 → tasks 页 archive 形状（needsRevision 透传——修订按钮依赖，Codex P1-9） */
function archiveView(data) {
  const list = (data && data.archives) || [];
  const latest = list[0];
  if (!latest) return { latest: null, archives: [], needsRevision: !!data && !!data.needsRevision };
  const tasks0 = safeParse(latest.tasks, []);
  return {
    latest: {
      ...latest,
      id: String(latest.id),
      revision: latest.archiveNo,
      completedCount: tasks0.filter(t => t.status === '已完成').length,
      tasks: tasks0.map(t => ({ ...t, id: String(t.id), assigneeId: String(t.assigneeUserId == null ? '' : t.assigneeUserId) }))
    },
    archives: list.map(a => ({ ...a, id: String(a.id) })),
    needsRevision: !!data.needsRevision
  };
}

// ===================== 成员（org 真源，Codex P1-3/4） =====================

/** org staff 行 → 选人视图：[{id:userId 字符串, name, roleCode, dept, postId}] */
function memberView(rows) {
  return (rows || []).filter(r => r.active !== false).map(r => ({
    id: String(r.userId != null ? r.userId : r.postId),
    postId: String(r.postId),
    name: r.name || '成员',
    roleCode: r.roleCode || '',
    dept: ROLE_DEPT[r.roleCode] || 'business',
    deptLabel: DEPT_LABEL[ROLE_DEPT[r.roleCode] || 'business']
  }));
}

/** 组织成员装载（API 真源；失败抛错由调用方降级任务行派生） */
async function loadMembers() {
  const post = apiPost();
  if (!post) return [];
  const rows = await orgApi.orgStaff(post.orgId);
  return memberView(rows);
}

// ===================== 提成视图适配 =====================

/**
 * 提成对账行视图（assigneeUserId 字符串保留——Codex P1-6；API 态 canPay=false——
 * 付款走财务工作台在线流程 M5 接管，不跳本地付款页 Codex P1-8）。
 */
function commissionRowView(row, roleCode, projectName) {
  const finance = roleCode === 'finance' || roleCode === 'finance-lead';
  const actualBoss = roleCode === 'boss';
  return {
    id: 'policy:' + row.policyId,
    projectId: String(row.projectId),
    project: projectName || ('项目 ' + String(row.projectId).slice(-6)),
    totalText: centsText(row.totalCents),
    assigneeUserId: String(row.assigneeUserId == null ? '' : row.assigneeUserId),
    assignee: row.assigneeName,
    version: row.version,
    stages: (row.stages || []).map(stage => ({
      // M10-T05：三期映射（SIGN_AFTER→start/BASE_DONE→base/CAP_DONE→topping；存量 START/TOPPING 兼容）
      stage: stage.stage === 'SIGN_AFTER' || stage.stage === 'START' ? 'start'
        : stage.stage === 'BASE_DONE' ? 'base' : 'topping',
      label: stage.label,
      status: stage.status,
      payableId: stage.payableId == null ? '' : String(stage.payableId),
      reviewDue: stage.reviewDue,
      requiredText: centsText(stage.requiredCents),
      receivedText: centsText(stage.receivedCents),
      amountText: centsText(stage.amountCents),
      paidText: centsText(stage.paidCents),
      outstandingText: centsText(stage.outstandingCents),
      canRelease: finance && stage.eligible && !stage.payableId,
      canPay: false,
      // M10-T05/R2 P2：已付差额核对入口（paid>0 即使净额不足也可核对——后端已付留痕路径）
      canReconcile: actualBoss && stage.reviewDue && (stage.eligible || (stage.paidCents || 0) > 0),
      reason: stage.reviewDue ? '原收款已变化，未付部分暂停；已付差额只待人工核对，不代表已追回。'
        : !stage.eligible ? '阶段款尚未收齐，暂不能发放。'
        : stage.payableId ? '应付已生成，请到财务工作台登记付款。' : '阶段款已足额，可由财务生成本期应付。'
    }))
  };
}

/**
 * 提成面装载（Codex P1-5）：对账行 + 工程项目列表关联——
 * 零规则项目也在列（可建首条规则）；项目名取工程域真源。
 */
async function loadCommission() {
  const post = apiPost();
  if (!post) throw { code: 0, msg: '请先登录后端账号' };
  const [rows, projects] = await Promise.all([
    api.commission.rows(),
    require('../api/franchise/engineering').projects.page()
  ]);
  const nameByProject = {};
  (projects || []).forEach(p => {
    nameByProject[String(p.id)] = p.name || p.customer || ('项目 ' + String(p.id).slice(-6));
  });
  const policyByProject = {};
  (rows || []).forEach(row => {
    policyByProject[String(row.projectId)] = row;
  });
  const ids = Array.from(new Set(Object.keys(nameByProject).concat(Object.keys(policyByProject)))).sort();
  return ids.map(pid => {
    const row = policyByProject[pid];
    if (row) return commissionRowView(row, post.roleCode, nameByProject[pid]);
    return {
      id: '', projectId: pid, project: nameByProject[pid], totalText: '—', assigneeUserId: '',
      assignee: '', version: 0, noPolicy: true,
      stages: []
    };
  });
}

// ===================== owner-page 绩效 Tab 平台视图（形状对齐 wxml） =====================

/** 组级指标键顺序（departmentCards 渲染顺序） */
const PLAT_GROUP_METRICS = {
  business: ['metric:commissionAmount', 'auto:collectionsAmount', 'metric:effectiveCustomers',
    'metric:activityTotal', 'auto:approvedDeliverables'],
  engineering: ['auto:completedNodes', 'auto:onTimeNodes', 'metric:firstPassNodes', 'auto:qualifiedDeliveries'],
  finance: ['auto:financeRegistrations']
};

/**
 * 平台绩效视图（Codex P1-1 修复：对齐 performance/index.wxml 全部绑定）——
 * departmentCards[].metrics[].{label,display,displayUnit,targetDisplay,width} +
 * targets/filterOptions/selectedDepartment/headcount/cutoff + groups（空=明细引导任务页）。
 */
function platPerformance(bundle, period, selectedDepartment) {
  const summary = bundle && bundle.summary;
  const goals = bundle && bundle.goals;
  const byDept = (summary && summary.departments) || {};
  const deptGoals = safeParse(goals && goals.departmentGoals, {});
  const fmt = money => '¥ ' + Number(money).toLocaleString('en-US');
  const departmentCards = Object.keys(PLAT_GROUP_METRICS).map(dept => {
    const actuals = byDept[dept] || {};
    const metrics = PLAT_GROUP_METRICS[dept].map(key => {
      const money = METRIC_MONEY.has(key);
      const unit = money ? '元' : (key === 'metric:effectiveCustomers' || key === 'metric:activityTotal' ? '条' : '个');
      const actual = money ? (actuals[key] || 0) / 100 : Math.round((actuals[key] || 0) / 100 * 100) / 100;
      // R2 P1-1：目标 JSONB 键=指标裸键（completedNodes 等，原单位存储）——summary 键去前缀映射
      const goalKey = key.replace(/^metric:/, '').replace(/^auto:/, '');
      const targetRaw = (deptGoals[dept] || {})[goalKey];
      const target = targetRaw == null ? 0 : targetRaw;
      const percent = target > 0 ? Math.round(actual / target * 1000) / 10 : null;
      return {
        id: key, label: METRIC_LABEL[key] || key,
        display: money ? fmt(actual) : String(actual),
        displayUnit: money ? '元' : unit,
        targetDisplay: targetRaw == null ? '' : (money ? fmt(target) : String(target)),
        percent, width: Math.max(0, Math.min(100, percent || 0))
      };
    });
    return { key: dept, label: DEPT_LABEL[dept], metrics };
  });
  const cards = selectedDepartment && selectedDepartment !== 'all'
    ? departmentCards.filter(card => card.key === selectedDepartment) : departmentCards;
  return {
    platSummary: true,
    departmentCards: cards,
    filterOptions: [{ id: 'all', label: '全部' },
      { id: 'business', label: DEPT_LABEL.business },
      { id: 'engineering', label: DEPT_LABEL.engineering },
      { id: 'finance', label: DEPT_LABEL.finance }],
    selectedDepartment: selectedDepartment || 'all',
    headcount: 0,
    cutoff: period + ' · 平台实时汇总',
    targets: [],
    groups: [],
    summaryNote: '成员明细与任务办理请前往「绩效任务 · 团队任务」（平台在线数据）'
  };
}

// ===================== 在线编排 =====================

/** 任务范围装载：view → scope（tasks 页 tab 映射） */
async function loadTasks(period, view, memberUserId) {
  const scope = view === 'team' ? 'company' : view === 'member' ? 'member' : 'mine';
  const data = await api.tasks.list(period, scope, null, view === 'member' ? memberUserId : undefined);
  return (data || []).map(taskView);
}

// 请求正文与幂等指纹共用同一序列化，避免显示字段或数字格式变化产生不同判断。
function taskPayload(action, p) {
  switch (action) {
    case 'create': return {
      period: p.period, title: p.title, assigneeUserId: String(p.assigneeId),
      assigneeName: p.assigneeName || '', assigneeDept: p.assigneeDept || 'business',
      type: p.type, targetCents: p.type === 'manual' ? 0 : toTargetRaw(p.type, p.target), due: p.due
    };
    case 'reassign': return { assigneeUserId: String(p.assigneeUserId), assigneeName: p.assigneeName || '' };
    case 'manual': return { note: p.note, photos: (p.photos || []).filter(x => typeof x === 'string' && x.indexOf('wxfile') !== 0 && x.indexOf('http') !== 0) };
    case 'archive': return { period: p.period, note: p.note || '月度绩效任务归档' };
    case 'revise': return { period: p.period, note: p.note || '原业务事实变化后重新冻结' };
    default: throw new Error('未知任务操作');
  }
}

/**
 * 写动作在线编排（payload.idem 显式幂等键贯穿——Codex P1-10）。
 * 全部 id 保持字符串（禁 Number()——Codex P1-7）；错误抛原始对象由页面 errorText 展示。
 */
async function perform(action, payload) {
  const p = payload || {};
  const key = p.idem;
  switch (action) {
    case 'create':
      return api.tasks.create(taskPayload(action, p), key);
    case 'reassign': {
      const body = taskPayload(action, p);
      return api.tasks.reassign(String(p.id), body.assigneeUserId, body.assigneeName, key);
    }
    case 'manual': {
      const body = taskPayload(action, p);
      return api.tasks.completeManual(String(p.id), body.note, body.photos, key);
    }
    case 'initialize':
      return api.tasks.initializePrevious(p.period, key);
    case 'archive':
      return api.tasks.archive(p.period, taskPayload(action, p).note, key);
    case 'revise':
      return api.tasks.revise(p.period, taskPayload(action, p).note, key);
    case 'policy':
      return api.commission.policy(String(p.projectId), toTargetRaw('auto:collectionsAmount', p.amount),
        String(p.assigneeUserId), p.assigneeName,
        typeof p.evidenceRef === 'string' ? p.evidenceRef : JSON.stringify(p.evidenceRef || { name: '提成确认凭据' }),
        p.note, key);
    case 'release':
      // M10-T05：三期期次映射（BASE_DONE→BASE_DONE；CAP_DONE→CAP_DONE；存量 start→SIGN_AFTER）
      const releaseStage = p.stage === 'topping' ? 'CAP_DONE'
        : p.stage === 'base' ? 'BASE_DONE' : 'SIGN_AFTER';
      return api.commission.release(String(p.projectId), releaseStage, key);
    case 'reconcile':
      const reconcileStage = p.stage === 'topping' ? 'CAP_DONE'
        : p.stage === 'base' ? 'BASE_DONE' : 'SIGN_AFTER';
      return api.commission.reconcile(String(p.projectId), reconcileStage,
        typeof p.evidenceRef === 'string' ? p.evidenceRef : JSON.stringify(p.evidenceRef || { name: '人工核对凭据' }),
        p.note, key);
    case 'month-reconcile':
      return api.operating.reconcile(p.period, key);
    default:
      throw { code: 0, msg: '该操作暂未开通在线办理' };
  }
}

module.exports = {
  TYPES, typeMeta, isMoneyType, METRIC_LABEL, METRIC_MONEY, DEPT_LABEL, ROLE_DEPT,
  validPeriod, currentPeriod, previousPeriod, monthEnd, centsText, valueText: displayValue,
  toTargetCents: toTargetRaw, safeParse,
  online, apiPost, errorText, taskView, archiveView, memberView, loadMembers,
  commissionRowView, loadCommission, platPerformance,
  api, loadTasks, taskPayload, perform
};
