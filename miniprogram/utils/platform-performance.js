/**
 * 绩效提成经营纯函数层（阶段 3）：后端英文态/字段 → 页面展示态适配、period 工具、
 * 金额/计数双单位任务视图、提成对账行适配、1_116 错误码文案、在线编排。
 * 后端契约见 api/franchise/performance.js；离线/失败一律降级本地演示（utils/performance-tasks.js 等）不白屏。
 * 单位事实（D-10）：money 类任务/指标 = 分；计数类 = 个原值（后端 target_cents 字段对计数类存个数）。
 */
const api = require('../api/franchise/performance');
const http = require('../api/http');

// ===================== 常量与工具 =====================

/** 任务类型标签（后端合法集；financeReviews 不建——19 §九） */
const TYPES = [
  { id: 'manual', label: '手动任务', unit: '', manual: true },
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
/** 三组指标键 → 展示名（summary.departments 的键形如 metric:commissionAmount / auto:completedAmount 等） */
const METRIC_LABEL = {
  'metric:commissionAmount': '设计委托金额', 'auto:collectionsAmount': '项目实际回款',
  'metric:effectiveCustomers': '有效跟进客户', 'metric:activityTotal': '发布与有效线索',
  'auto:publications': '内容发布', 'auto:effectiveLeads': '有效线索',
  'auto:approvedDeliverables': '审核通过成果', 'auto:completedNodes': '完成节点',
  'auto:onTimeNodes': '按期节点', 'metric:firstPassNodes': '首验通过节点',
  'auto:qualifiedDeliveries': '合格供货批次', 'auto:financeRegistrations': '实际收付登记'
};
const DEPT_LABEL = { business: '业务组', engineering: '工程组', finance: '财务组' };

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
/** 分→元文本（money 类任务/指标） */
const centsText = cents => cents == null ? '—' : '¥ ' + (cents / 100).toLocaleString('en-US');
/** 目标/实绩展示值（money 类分→元数字文本；计数类原值） */
const valueText = (type, value) => typeMeta(type).money ? String((value || 0) / 100) : String(value || 0);
/** 表单元输入 → 后端 targetCents（money 类元→分；计数类原值） */
const toTargetCents = (type, raw) => {
  const v = Number(String(raw == null ? '' : raw).trim());
  if (!Number.isFinite(v) || v <= 0) return null;
  return typeMeta(type).money ? Math.round(v * 100) : Math.round(v);
};

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

/** 后端任务行 → tasks 页 row 形状（含 progress 圆环/文本标签；API 态 history 简化显示） */
function taskView(row) {
  const meta = typeMeta(row.type);
  const manual = row.type === 'manual';
  const completed = row.status === '已完成';
  const raw = manual ? (completed ? 100 : 0)
    : Number(row.targetCents) > 0 ? Number(row.collected || 0) / Number(row.targetCents) * 100 : 0;
  const percent = Math.round(raw * 10) / 10;
  const degrees = Math.max(0, Math.min(100, percent)) * 3.6;
  const historyRows = row.history || [];
  const lastHistory = historyRows[historyRows.length - 1] || {};
  const suggestionRow = historyRows.find(h => h.action === 'create' && h.suggestion);
  return {
    ...row,
    id: String(row.id),
    assigneeId: row.assigneeUserId,
    target: meta.money ? (row.targetCents || 0) / 100 : (row.targetCents || 0),
    collected: meta.money ? (row.collected || 0) / 100 : (row.collected || 0),
    status: completed ? '已完成' : '进行中',
    manualDone: row.manualDone || null,
    suggestion: suggestionRow ? suggestionRow.suggestion : null,
    history: historyRows.map(h => ({ ...h, from: h.fromUserId, to: h.toUserId })),
    percent, progressRight: Math.min(180, degrees), progressLeft: Math.max(0, degrees - 180),
    typeLabel: meta.label, unit: meta.unit,
    targetText: manual ? '提交说明（照片选填）' : valueText(row.type, row.targetCents) + meta.unit,
    collectedText: manual ? (completed ? '已提交凭证' : '待提交凭证') : valueText(row.type, row.collected) + meta.unit,
    suggestionText: suggestionRow ? '依据 ' + suggestionRow.suggestion.period + ' 真实实绩 '
      + valueText(row.type, suggestionRow.suggestion.value) + (suggestionRow.suggestion.unit || '') : '',
    suggestionSources: [],
    historyText: lastHistory.action === 'reassign' ? '任务已改派'
      : lastHistory.action === 'manual-complete' ? '已提交手动完成凭证' : '已派发'
  };
}

/** 归档快照 → tasks 页 archive 形状 */
function archiveView(data) {
  const list = data && data.archives || [];
  const latest = list[0];
  if (!latest) return { latest: null, archives: [] };
  let tasks = [];
  try { tasks = JSON.parse(latest.tasks || '[]'); } catch (_) { tasks = []; }
  return {
    latest: {
      ...latest,
      revision: latest.archiveNo,
      completedCount: tasks.filter(t => t.status === '已完成').length,
      tasks: tasks.map(t => ({ ...t, id: String(t.id), assigneeId: t.assigneeUserId }))
    },
    archives: list
  };
}

// ===================== 提成视图适配 =====================

/** 后端对账行 → commission 页 row 形状（五态+按钮矩阵+reason 文案） */
function commissionRowView(row, role) {
  const finance = role === 'finance' || role === 'finance-lead';
  const actualBoss = role === 'boss';
  return {
    id: 'policy:' + row.policyId,
    projectId: row.projectId,
    project: '项目 ' + String(row.projectId).slice(-6),
    totalText: centsText(row.totalCents),
    assignee: row.assigneeName,
    version: row.version,
    stages: (row.stages || []).map(stage => ({
      stage: stage.stage === 'TOPPING' ? 'topping' : 'start',
      label: stage.label,
      status: stage.status,
      payableId: stage.payableId,
      reviewDue: stage.reviewDue,
      requiredText: centsText(stage.requiredCents),
      receivedText: centsText(stage.receivedCents),
      amountText: centsText(stage.amountCents),
      paidText: centsText(stage.paidCents),
      outstandingText: centsText(stage.outstandingCents),
      canRelease: finance && stage.eligible && !stage.payableId,
      canPay: finance && !!stage.payableId && !stage.reviewDue && stage.outstandingCents > 0,
      canReconcile: actualBoss && stage.reviewDue && stage.eligible,
      reason: stage.reviewDue ? '原收款已变化，未付部分暂停；已付差额只待人工核对，不代表已追回。'
        : !stage.eligible ? '阶段款尚未收齐，暂不能发放。'
        : stage.payableId ? '应付已生成，可登记付款。' : '阶段款已足额，可由财务生成本期应付。'
    }))
  };
}

// ===================== 在线编排 =====================

/** 任务范围装载：view → scope（tasks 页 tab 映射） */
async function loadTasks(period, view, memberUserId) {
  const scope = view === 'team' ? 'company' : view === 'member' ? 'member' : 'mine';
  const data = await api.tasks.list(period, scope, null, view === 'member' ? memberUserId : undefined);
  return (data || []).map(taskView);
}

/** tasks 页写动作在线编排（错误抛 1_116 映射文案由页面 errorText 展示） */
async function perform(action, payload) {
  const p = payload || {};
  switch (action) {
    case 'create':
      return api.tasks.create({
        period: p.period, title: p.title, assigneeUserId: Number(p.assigneeId),
        assigneeName: p.assigneeName || '', assigneeDept: p.assigneeDept || 'business',
        type: p.type, targetCents: toTargetCents(p.type, p.target), due: p.due
      });
    case 'reassign':
      return api.tasks.reassign(p.id, Number(p.assigneeUserId), p.assigneeName);
    case 'manual':
      return api.tasks.completeManual(p.id, p.note, (p.photos || []).filter(x => typeof x === 'string' && x.indexOf('http') !== 0));
    case 'initialize':
      return api.tasks.initializePrevious(p.period);
    case 'archive':
      return api.tasks.archive(p.period, p.note || '月度绩效任务归档');
    case 'revise':
      return api.tasks.revise(p.period, p.note || '原业务事实变化后重新冻结');
    case 'goal-save':
      return api.goals.save(p.data);
    case 'policy':
      return api.commission.policy(Number(p.projectId), toTargetCents('auto:collectionsAmount', p.amount),
        Number(p.assigneeUserId), p.assigneeName, JSON.stringify(p.evidenceRef || { name: '提成确认凭据' }), p.note);
    case 'release':
      return api.commission.release(Number(p.projectId), p.stage === 'topping' ? 'TOPPING' : 'START');
    case 'reconcile':
      return api.commission.reconcile(Number(p.projectId), p.stage === 'topping' ? 'TOPPING' : 'START',
        JSON.stringify(p.evidenceRef || { name: '人工核对凭据' }), p.note);
    case 'month-reconcile':
      return api.operating.reconcile(p.period);
    default:
      throw { code: 0, msg: '该操作暂未开通在线办理' };
  }
}

/** API 态成员清单（派发/改派选人；从任务行+三组汇总无法取全——留待 org 接口，首批由页面传本地 staff） */
function memberOptionsFromRows(rows) {
  const seen = new Map();
  (rows || []).forEach(row => {
    if (row.assigneeUserId && !seen.has(String(row.assigneeUserId))) {
      seen.set(String(row.assigneeUserId), { id: String(row.assigneeUserId), name: row.assigneeName || '成员' });
    }
  });
  return Array.from(seen.values());
}

module.exports = {
  TYPES, typeMeta, isMoneyType, METRIC_LABEL, DEPT_LABEL, platGroups,
  validPeriod, currentPeriod, previousPeriod, monthEnd, centsText, valueText, toTargetCents,
  online, errorText, taskView, archiveView, commissionRowView,
  api, loadTasks, perform, memberOptionsFromRows
};

// ===================== owner-page 绩效 Tab 平台视图（API 态合成；wxml 不动） =====================

/** 三组指标键顺序（组卡渲染顺序） */
const PLAT_GROUP_METRICS = {
  business: ['metric:commissionAmount', 'auto:collectionsAmount', 'metric:effectiveCustomers',
    'metric:activityTotal', 'auto:approvedDeliverables'],
  engineering: ['auto:completedNodes', 'auto:onTimeNodes', 'metric:firstPassNodes', 'auto:qualifiedDeliveries'],
  finance: ['auto:financeRegistrations']
};
/** 指标金额类键（分→元展示） */
const MONEY_METRICS = new Set(['metric:commissionAmount', 'auto:collectionsAmount']);

/**
 * 平台三组视图（API 态 performance Tab）：groups 形状与 P.build 对齐（rows 留空——
 * 成员明细走绩效任务页 team 视图；metrics 数值=平台真源，目标对照=fr_perf_goal）。
 */
function platGroups(bundle) {
  const summary = bundle && bundle.summary;
  const goals = bundle && bundle.goals;
  const byDept = (summary && summary.departments) || {};
  const groups = [];
  Object.keys(PLAT_GROUP_METRICS).forEach(dept => {
    const actuals = byDept[dept] || {};
    const goalDept = (goals && goals.departmentGoals && safeParse(goals.departmentGoals)) || {};
    const metrics = PLAT_GROUP_METRICS[dept].map(key => {
      const money = MONEY_METRICS.has(key);
      const actual = money ? (actuals[key] || 0) / 100 : (actuals[key] || 0);
      const targetRaw = (goalDept[dept] || {})[key];
      const target = targetRaw == null ? '' : (money ? targetRaw / 100 : targetRaw);
      const percent = target ? Math.round(actual / target * 1000) / 10 : null;
      return {
        id: key, label: METRIC_LABEL[key] || key,
        actual, target,
        actualText: money ? '¥ ' + Number(actual).toLocaleString('en-US') : String(actual),
        targetText: target === '' ? '' : (money ? '¥ ' + Number(target).toLocaleString('en-US') : String(target)),
        percent, width: Math.max(0, Math.min(100, percent || 0))
      };
    });
    groups.push({
      key: dept, label: DEPT_LABEL[dept],
      metric: dept === 'business' ? '运营、设计、销售统一考核' : '',
      metrics, rows: []
    });
  });
  return { platSummary: true, groups, summaryNote: '成员明细请前往「绩效任务 · 团队任务」查看在线数据' };
}

function safeParse(json) {
  try { return JSON.parse(json || '{}'); } catch (_) { return {}; }
}
