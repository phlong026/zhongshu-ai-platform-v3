/**
 * 工程全周期纯函数层（阶段 2）：后端英文态→页面中文态映射、日期双形状归一、
 * 分→元文本、汇总派生、脱敏视图适配（record 页 data 形状适配层——wxml 不动）。
 * 后端契约见 api/franchise/engineering.js；离线/失败一律降级本地演示（utils/engineering-records.js）不白屏。
 */
const api = require('../api/franchise/engineering');
const http = require('../api/http');

/** LocalDate 双形状归一：'2026-11-20' | [2026,11,20] → '2026-11-20'（null 透传） */
function dateOf(v) {
  if (Array.isArray(v) && v.length >= 3) return v[0] + '-' + String(v[1]).padStart(2, '0') + '-' + String(v[2]).padStart(2, '0');
  return v == null ? null : String(v).slice(0, 10);
}

/** 分→元展示文本（1_150_000_000 段金额一律 cents；null→'—'） */
function centsText(cents) {
  if (cents == null) return '—';
  return '¥ ' + (cents / 100).toLocaleString('en-US');
}

/** 工程状态映射（F2 派生态 → 页面中文态） */
const STATUS_VIEW = {
  PENDING_START: '待开工', IN_PROGRESS: '在建', READY_DELIVER: '待交付', DELIVERED: '已交付', ARCHIVED: '已归档'
};
function statusView(status) { return STATUS_VIEW[status] || status || ''; }
/** 列表过滤条件匹配（projects 页 filter=全部|在建|待交付…） */
function statusMatches(status, filter) {
  if (!filter || filter === '全部') return true;
  return statusView(status) === filter;
}

/** 节点状态映射（PENDING/IN_PROGRESS/DONE → 蓝本中文三态） */
const NODE_VIEW = { PENDING: '待开工', IN_PROGRESS: '施工中', DONE: '已完成' };
function nodeStatusView(s) { return NODE_VIEW[s] || s || ''; }

/** 节点视图（含 TOPPING 里程碑标记与日期归一；record 页 nodeRows 形状） */
function nodeRows(nodes) {
  return (nodes || []).map(n => ({
    id: String(n.id), nodeIndex: n.nodeIndex, name: n.name,
    start: dateOf(n.startDate), end: dateOf(n.endDate),
    status: nodeStatusView(n.status), milestone: n.milestone === 'TOPPING' ? 'topping' : '',
    completedAt: dateOf(n.completedAt), corrections: n.corrections, reopenHistory: n.reopenHistory
  }));
}

/** 两期收款汇总视图（蓝本 installments 形状：stage/name/expected/confirmed/remaining/due） */
function stagesView(stages) {
  return (stages || []).map(s => ({
    stage: s.stage === 'TOPPING' ? 'topping' : 'start',
    name: s.stage === 'TOPPING' ? '封顶款' : '开工款',
    expected: s.expectedCents || 0, confirmed: s.confirmedCents || 0,
    remaining: s.remainingCents || 0, due: dateOf(s.due),
    expectedText: centsText(s.expectedCents), confirmedText: centsText(s.confirmedCents),
    remainingText: centsText(s.remainingCents),
    settled: (s.confirmedCents || 0) >= (s.expectedCents || 0) && (s.expectedCents || 0) > 0
  }));
}

/** 材料计划单汇总视图（到货/付款派生态 → 蓝本中文） */
function plansView(summary) {
  return (summary && summary.plans || []).map(planRow => ({
    id: String(planRow.materialPlanId), nodeId: '', due: '',
    arrival: planRow.arrival, payment: paymentText(planRow),
    known: true, complete: planRow.arrival === '已验收齐全',
    total: planRow.totalCents || 0, paid: planRow.paidCents || 0,
    remaining: planRow.remainingCents || 0,
    totalText: centsText(planRow.totalCents), paidText: centsText(planRow.paidCents),
    remainingText: centsText(planRow.remainingCents),
    lines: (planRow.lines || []).map(l => ({
      id: String(l.materialId), name: l.name, spec: l.spec || '', unit: l.unit,
      quantity: l.quantity, received: l.received, remaining: l.remaining,
      cancelled: !!l.cancelled, supplyId: l.supplyId ? String(l.supplyId) : '',
      supplier: l.supplier || '', paymentStatus: l.paymentStatus || ''
    }))
  }));
}
function paymentText(planRow) {
  if (planRow.totalCents == null || planRow.totalCents === 0) return '待记录采购金额';
  const paid = planRow.paidCents || 0, total = planRow.totalCents;
  return paid >= total ? '已付清' : paid > 0 ? '部分已付' : '待付款';
}

/** 结算屏视图（材料三组数+费用 0 占位+两期汇总 → 蓝本 settleSummary 形状） */
function settleView(view) {
  return {
    materialsTotal: view.materialsTotalCents || 0, materialsPaid: view.materialsPaidCents || 0,
    materialsRemaining: view.materialsRemainingCents || 0,
    expensesTotal: view.expensesTotalCents || 0, expensesPaid: view.expensesPaidCents || 0,
    expensesRemaining: view.expensesRemainingCents || 0,
    materialsTotalText: centsText(view.materialsTotalCents), materialsPaidText: centsText(view.materialsPaidCents),
    materialsRemainingText: centsText(view.materialsRemainingCents),
    expensesTotalText: centsText(view.expensesTotalCents), expensesPaidText: centsText(view.expensesPaidCents),
    expensesRemainingText: centsText(view.expensesRemainingCents),
    expensesNote: view.expensesNote || '', installments: stagesView(view.collectionStages)
  };
}

/** 后端错误文案（1_115 段十码 → 展示短句；未识别透传 msg） */
function errorText(err) {
  const code = err && err.code;
  const map = {
    1115000000: '当前身份无权办理本工程操作',
    1115000002: '施工计划日期缺失或重叠，请核对',
    1115000003: '节点操作前置不满足（撤回/更正条件）',
    1115000004: '材料操作不合法（调整越界或已付款锁定）',
    1115000005: '收货流水号已登记',
    1115000006: '本次金额超过本期尚未登记的应收金额',
    1115000007: '资金或材料门禁未满足',
    1115000008: '同名项目已存在',
    1115000009: '尾段顺序不正确（交付→结算→归档）',
    1115000010: '负责人不在岗，请重新选择'
  };
  if (map[code]) return map[code];
  return (err && err.msg) || '操作失败，请稍后重试';
}

/** API 态可用性：已登录平台身份（阶段 1.5 同款 http.token() 分流；无 token 走本地演示态） */
function online() { return !!http.token(); }

/** 详情聚合 → record 页 p 形状适配（脱敏字段缺省=不展示；completion 留痕/文档/尾段全量映射） */
function projectView(detail) {
  const closure = detail.closure || {};
  const nodes = nodeRows(detail.nodes);
  return {
    id: String(detail.id), projectId: String(detail.id),
    name: detail.name, customer: detail.customer || '', city: detail.city || '',
    phone: detail.phone || '', area: detail.area, floors: detail.floors,
    sourceType: detail.sourceType, drawingVersion: detail.drawingVersion || '',
    managerId: detail.managerUserId, manager: '',
    archived: !!closure.archivedAt, delivery: closure.deliveredAt ? {
      date: dateOf(closure.deliveredAt), recipient: closure.recipient || '', note: closure.deliverNote || ''
    } : null,
    settlement: closure.settledAt ? { date: dateOf(closure.settledAt), note: closure.settleNote || '' } : null,
    contractTotal: detail.contractTotalCents != null ? detail.contractTotalCents / 100 : 0,
    recordFlow: {
      version: 1,
      contract: detail.contractTotalCents ? {
        total: detail.contractTotalCents, first: detail.firstCents,
        version: detail.contractVersion || 1, date: dateOf(detail.contractDate)
      } : null,
      prepared: detail.preparedAt ? { note: detail.preparedNote || '', workers: detail.preparedWorkers } : null
    },
    actualStart: detail.actualStartAt ? { date: dateOf(detail.actualStartAt) } : null,
    plan: { version: 0, nodes: nodes.map(n => ({ ...n, id: n.id.replace(/^\D+/, ''), start: n.start || '', end: n.end || '' })) },
    documents: (detail.documents || []).map(d => ({ name: d.name, file: { name: d.name }, date: '' })),
    status: statusView(detail.status), statusRaw: detail.status,
    stageText: statusView(detail.status)
  };
}

module.exports = {
  api, online, dateOf, centsText, statusView, statusMatches, nodeRows, nodeStatusView,
  stagesView, plansView, settleView, errorText, projectView,
  uploadVoucher, loadProject, pageData, perform, listCards
};

// ===================== 在线编排（record/projects/home 页 API 态；wx 依赖仅限上传 helper） =====================

/**
 * 凭据上传（基-1 票据链：ticket→字节→complete）。
 * 字节通道随 COS 凭据批开放（阶段 1.5 同款遗留）——当前统一降级提示，
 * 需凭据的写动作在 API 态提示后仍可走本地演示态办理；无凭据动作不受影响。
 */
function uploadVoucher() {
  return Promise.reject({ code: 0, msg: '在线凭据上传通道随 COS 批开放，本操作暂请在本地演示态办理' });
}

/** 汇总装载：detail+两期收款+材料汇总+结算屏+催办（record 页一次拉齐） */
async function loadProject(id) {
  const [detail, collections, materials, settle, followups] = await Promise.all([
    api.projects.get(id), api.collections.page(id), api.materials.summary(id),
    api.closure.settleSummary(id), api.collections.followups(id).catch(() => [])
  ]);
  return { detail, collections, materials, settle, followups };
}

/** record 页 data 增量（p 形状适配+两期/计划单/结算/催办/收款列表；脱敏字段缺省即不展示） */
function pageData(bundle) {
  const d = bundle.detail, c = bundle.collections || {};
  const p = projectView(d);
  const installments = stagesView(c.stages);
  p.collections = (c.records || []).map(r => ({
    id: String(r.id), receiptStage: (r.stage || 'START').toLowerCase(),
    purpose: r.purpose || '', cents: r.amountCents || 0, date: dateOf(r.occurredAt),
    transaction: r.transactionNo || '', registrar: ''
  }));
  return {
    p, installments,
    plans: plansView(bundle.materials),
    settle: settleView(bundle.settle),
    dunning: (bundle.followups || []).filter(f => f.kind === 'COLLECTION').map(f => ({
      stage: (f.stage || 'START').toLowerCase(), date: dateOf((f.createTime || '').slice(0, 10)) || '',
      promiseDate: dateOf(f.promiseDate), note: f.note || ''
    })).reverse(),
    materialFollowups: (bundle.followups || []).filter(f => f.kind === 'MATERIAL').map(f => ({
      planId: String(f.materialPlanId || ''), date: '', promiseDate: dateOf(f.promiseDate), note: f.note || ''
    })).reverse()
  };
}

/** 元字符串→数字（表单金额字段转换；空/非法 throw） */
function yuan(form, key, label) {
  const raw = String(form[key] == null ? '' : form[key]).trim();
  const v = Number(raw);
  if (!raw || !Number.isFinite(v) || v <= 0) throw { code: 0, msg: (label || key) + '须为有效金额' };
  return v;
}

/**
 * 在线动作映射（record 页 perform 的 API 态；form 沿用页面现有字段名）。
 * 返回 Promise；错误抛 {code,msg} 由页面 errorText 展示。
 */
async function perform(action, id, form, node) {
  const f = form || {};
  const dateStr = f.date || null;
  switch (action) {
    case 'prepare':
      return api.plan.prepare(id, {
        total: yuan(f, 'total', '合同总额'), first: yuan(f, 'first', '开工款'),
        contractAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING'),
        note: f.note, workers: f.workers || ''
      });
    case 'start':
      return api.plan.start(id, { date: dateStr, note: f.note || '',
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING') });
    case 'node': {
      const files = f.files && f.files.length ? f.files : f.file ? [f.file] : [];
      const assetIds = [];
      for (const file of files.slice(0, 1)) assetIds.push(await uploadVoucher(file.path, 'ENG_SITE_PHOTO'));
      return api.plan.complete(id, node.id, {
        date: dateStr, note: f.note, fileAssetId: assetIds[0], noMaterials: !!f.noMaterials
      });
    }
    case 'node-reopen':
      return api.plan.reopen(id, node.id, f.nodeReason || f.reason);
    case 'node-correct':
      return api.plan.correct(id, node.id, {
        completedAt: f.nodeDate || dateStr, note: f.nodeNote || f.note,
        fileAssetId: await uploadVoucher((f.nodeFile || f.file) && (f.nodeFile || f.file).path, 'ENG_SITE_PHOTO'),
        reason: f.nodeReason || f.reason
      });
    case 'site': {
      const files = f.files && f.files.length ? f.files : f.file ? [f.file] : [];
      const assetIds = [];
      for (const file of files) assetIds.push(await uploadVoucher(file.path, 'ENG_SITE_PHOTO'));
      return api.site.create(id, node.id, f.note, assetIds, dateStr);
    }
    case 'site-correct':
      return api.site.correct(f.siteId || f.recordId, f.siteNote || f.note,
        f.siteFile ? [await uploadVoucher(f.siteFile.path, 'ENG_SITE_PHOTO')] : [],
        f.siteReason || f.reason);
    case 'collect':
      return api.collections.register(id, {
        stage: f.stage === 'topping' ? 'TOPPING' : 'START', amount: yuan(f, 'amount', '收款金额'),
        transactionNo: String(f.transaction || '').trim(), occurredAt: dateStr,
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING')
      });
    case 'dunning':
      return api.collections.dunning(id, f.stage === 'topping' ? 'TOPPING' : 'START',
        f.promiseDate || null, String(f.note || '').trim());
    case 'material-plan': {
      const lines = (f.lines || []).filter(l => String(l.name || '').trim());
      return api.materials.submit(id, {
        nodeId: node && node.id, due: f.due,
        lines: lines.map(l => ({ name: l.name, spec: l.spec || '', unit: l.unit, quantity: Number(l.quantity) }))
      });
    }
    case 'material-terms':
      return api.materials.terms(f.supplyId, {
        supplier: f.supplier, unitPrice: yuan(f, 'unitPrice', '单价'), freight: Number(f.freight || 0),
        basis: JSON.stringify(f.file || { name: '采购凭据' })
      });
    case 'arrival':
      return api.materials.arrival(f.supplyId, {
        actual: Number(f.actual), quality: f.quality === '不合格' ? 'REJECTED' : 'OK',
        transactionNo: f.transaction, occurredAt: dateStr,
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_SITE_PHOTO'), note: f.note || ''
      });
    case 'material-adjust':
      return api.materials.adjust(f.materialId, Number(f.adjust), f.adjustReason || f.reason);
    case 'arrival-correct':
      return api.materials.arrivalCorrection(f.receiptId, Number(f.correctActual), f.correctReason);
    case 'material-followup':
      return api.materials.followup(id, f.planId, f.promiseDate || null, String(f.note || '').trim());
    case 'deliver':
      return api.closure.deliver(id, {
        date: dateStr, recipient: f.recipient,
        ownerAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING'),
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING'), note: f.note || ''
      });
    case 'settle':
      return api.closure.settle(id, { date: dateStr, note: f.note });
    case 'archive':
      return api.closure.archive(id);
    case 'project':
      return api.projects.changeManager(id, Number(f.engineerId), f.note || '');
    case 'plan': {
      const nodes = (f.nodes || []).map(n => ({
        nodeIndex: Number(n.nodeIndex || n.index || 0), name: n.name,
        startDate: dateOf(n.start), endDate: dateOf(n.end)
      }));
      return api.plan.save(id, nodes);
    }
    default:
      throw { code: 0, msg: '该操作暂未开通在线办理，请在本地演示态查看' };
  }
}

/** projects 页列表视图（卡片形状适配现有 wxml：W.card 兼容字段） */
function listCards(rows) {
  return (rows || []).map(r => ({
    id: String(r.id), name: r.name, city: r.city || '', customer: r.customer || '',
    status: statusView(r.status), stage: statusView(r.status),
    title: statusView(r.status), engineer: '', actualStart: dateOf(r.actualStartAt),
    contractTotal: r.contractTotalCents != null ? r.contractTotalCents / 100 : null
  }));
}
