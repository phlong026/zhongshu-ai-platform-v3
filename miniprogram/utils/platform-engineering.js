/**
 * 工程全周期纯函数层（阶段 2）：后端英文态→页面中文态映射、日期双形状归一、
 * 分→元文本、汇总派生、脱敏视图适配（record 页 data 形状适配层——wxml 不动）。
 * 后端契约见 api/franchise/engineering.js；离线或失败由页面展示错误态，不回落本地账簿。
 */
const api = require('../api/franchise/engineering');
const http = require('../api/http');
const epochMsOf = require('./epoch-ms').epochMsOf;

/**
 * 凭据类动作集合（M07-T04 批次#36：uploadVoucher 已接基-1 票据真传——ticket→bytes→complete），
 * 保留集合作为「哪些动作带凭据」的口径清单；真实 COS 渠道验收随批次 #40（E4）。
 */
const VOUCHER_ACTIONS = ['prepare', 'start', 'node', 'site', 'node-correct', 'site-correct', 'collect', 'arrival', 'deliver', 'material-terms'];

/** LocalDate 双形状归一：'2026-11-20' | [2026,11,20] → '2026-11-20'（null 透传） */
function dateOf(v) {
  if (Array.isArray(v) && v.length >= 3) return v[0] + '-' + String(v[1]).padStart(2, '0') + '-' + String(v[2]).padStart(2, '0');
  return v == null ? null : String(v).slice(0, 10);
}

/** createTime（毫秒时间戳主形态，TimestampLocalDateTimeSerializer）→ 'YYYY-MM-DD'（北京时间；非法回 ''） */
function timestampDate(v) {
  const ms = epochMsOf(v);
  return Number.isFinite(ms) ? new Date(ms + 8 * 3600000).toISOString().slice(0, 10) : '';
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

// 两个首页共用项目状态与顺序；未加载时用占位符，不能误报为零。
function homeStats(cards, available = true) {
  return [
    ['IN_PROGRESS', '在建'], ['PENDING_START', '待开工'],
    ['READY_DELIVER', '待交付'], ['', '全部项目']
  ].map(([status, label]) => ({
    label, value: available ? (status ? cards.filter(card => card.statusKey === status).length : cards.length) : '—',
    filter: status ? label : '全部'
  }));
}

/** 节点状态映射（PENDING/IN_PROGRESS/DONE → 蓝本中文三态） */
const NODE_VIEW = { PENDING: '待开工', IN_PROGRESS: '施工中', DONE: '已完成' };
function nodeStatusView(s) { return NODE_VIEW[s] || s || ''; }

/** 节点视图（含 TOPPING 里程碑标记与日期归一；record 页 nodeRows 形状） */
function nodeRows(nodes) {
  return (nodes || []).map(n => ({
    id: String(n.id), nodeIndex: n.nodeIndex, name: n.name,
    start: dateOf(n.startDate), end: dateOf(n.endDate),
    status: nodeStatusView(n.status), milestone: n.milestone === 'TOPPING' ? 'topping' : n.milestone === 'START' ? 'start' : '',
    baselineEnd:dateOf(n.baselineEndDate),deviationDays:n.deviationDays,delayReason:n.delayReason||'',delayOwnerUserId:String(n.delayOwnerUserId||''),delayOwnerName:n.delayOwnerUserId?n.delayOwnerName||'':'',recoveryDate:dateOf(n.recoveryDate),recoveryAction:n.recoveryAction||'',delayHistory:parseJsonList(n.delayHistory),
    pace:n.deviationDays==null?'':n.deviationDays>0?(n.status==='DONE'?'延期完成 ':'延期 ')+n.deviationDays+'天':n.status==='DONE'?(n.deviationDays<0?'提前 '+Math.abs(n.deviationDays)+'天':'按期完成'):'按计划',
    completedAt: dateOf(n.completedAt), corrections: n.corrections, reopenHistory: n.reopenHistory
  }));
}

/** 两期收款汇总视图（蓝本 installments 形状：stage/name/expected/confirmed/remaining/due/status/url）。
 * expected/confirmed/remaining 照离线分支口径输出分→元文本（wxml 直接渲染），Text 系键同源不另造实现；
 * url 经可选 urlOf(stage) 注入（settle 汇总复用本函数时不传）。 */
function stagesView(stages, urlOf) {
  // M07-T02：三期映射（SIGN_AFTER 签约后/BASE_DONE 基础完成后/CAP_DONE 封顶后；存量 START/TOPPING 兼容）
  const STAGE_NAME = { SIGN_AFTER: '签约后期款', BASE_DONE: '基础完成后期款', CAP_DONE: '封顶后期款',
    START: '开工款', TOPPING: '封顶款', tail:'交付尾款' };
  const STAGE_URL = { SIGN_AFTER: 'start', BASE_DONE: 'base', CAP_DONE: 'topping', START: 'start', TOPPING: 'topping', tail:'tail' };
  return (stages || []).map(s => {
    const expected = centsText(s.expectedCents), confirmed = centsText(s.confirmedCents), remaining = centsText(s.remainingCents);
    return {
      stage: STAGE_URL[s.stage] || String(s.stage || ''), key: String(s.stage || ''), trigger: s.trigger, waitingForWork: !!s.waitingForWork, stageId: s.stageId ? String(s.stageId) : '',
      name: s.label || STAGE_NAME[s.stage] || s.stage,
      expected, confirmed, remaining, due: dateOf(s.due),
      expectedText: expected, confirmedText: confirmed, remainingText: remaining,
      settled: (s.confirmedCents || 0) >= (s.expectedCents || 0) && (s.expectedCents || 0) > 0,
      collectible: Number(s.expectedCents) > 0 && Number(s.remainingCents) > 0,
      status: s.expectedCents === 0 ? '未约定收款' : (s.expectedCents || 0) > 0 && (s.remainingCents || 0) <= 0 ? '已收齐' : (s.confirmedCents || 0) > 0 ? '部分收款' : '待财务登记收款',
      url: urlOf ? urlOf(STAGE_URL[s.stage] || String(s.stage || '')) : ''
    };
  });
}

/** 材料计划单汇总视图（到货/付款派生态 → 蓝本中文） */
function plansView(summary) {
  return (summary && summary.plans || []).map(planRow => ({
    id: String(planRow.materialPlanId), nodeId: String(planRow.nodeId || (planRow.lines || [])[0]?.nodeId || ''), due: '',
    // R2 P1-3：供应事项 id（行级 lines[].supplyId 聚合——arrival 提交路径 /supplies/{supplyId}/arrival）
    supplyId: (planRow.lines || []).map(l => l.supplyId).filter(Boolean)[0] || null,
    arrival: planRow.arrival, payment: paymentText(planRow),
    known: (planRow.lines || []).filter(l => !l.cancelled).every(l => l.totalCents > 0), complete: planRow.arrival === '已验收齐全',
    total: planRow.totalCents || 0, paid: planRow.paidCents || 0,
    remaining: planRow.remainingCents || 0,
    totalText: centsText(planRow.totalCents), paidText: centsText(planRow.paidCents),
    remainingText: centsText(planRow.remainingCents),
    lines: (planRow.lines || []).map(l => ({
      id: String(l.materialId), name: l.name, spec: l.spec || '', unit: l.unit,
      supplyId: l.supplyId || null, remainingQty: l.remaining, // R3 P1-4：行级供应事项与余量（arrival 选行依据）
      quantity: l.quantity, received: l.received, remaining: l.remaining,
      requiredDate: dateOf(l.requiredDate), cancelled: !!l.cancelled,
      supplyId: l.supplyId ? String(l.supplyId) : '',
      supplier: l.supplier || '', paymentStatus: l.paymentStatus || '',
      payableId: l.payableId ? String(l.payableId) : '',
      receipts: l.receipts || [],
      // 对照版按仍有效的付款事实锁定，退款净额为零不解锁；缺投影时关闭编辑。
      locked: l.termsLocked !== false,
      termsEditable: l.termsLocked === false && !l.cancelled && !!l.supplyId
    }))
  }));
}
function paymentText(planRow) {
  if (planRow.totalCents == null || planRow.totalCents === 0) return '待记录采购金额';
  const paid = planRow.paidCents || 0, total = planRow.totalCents;
  return paid >= total ? '已付清' : paid > 0 ? '部分已付' : '待付款';
}

function settleView(view) {
  const review = Array.isArray(view.expenseReviewRows) ? view.expenseReviewRows : [];
  return {
    expenseReview: review.map(r => ({
      id: r.expenseId, name: r.name, revised: centsText(r.revisedCents), booked: centsText(r.bookedCents),
      paid: centsText(r.netPaidCents), above: r.netAboveRevisedCents > 0 ? centsText(r.netAboveRevisedCents) : ''
    })),
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
  return (err && (err.msg || err.message)) || '操作失败，请稍后重试'; // api/http reject 形状 {code,message}（platform-crm 同款兜底）
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
    managerId: detail.managerUserId, manager: detail.managerName || (detail.managerUserId ? '已指派工程人员' : '待指派'),
    archived: !!closure.archivedAt, delivery: closure.deliveredAt ? {
      date: dateOf(closure.deliveredAt), recipient: closure.recipient || '', note: closure.deliverNote || '',
      // M09-T02 批次#37：签收凭据真实可读——交付/业主签收 assetId（授权查看数据源）
      fileAssetId: closure.fileAssetId || null, ownerAssetId: closure.ownerAssetId || null
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
    plan: { version: 0, nodes: nodes.map(n => ({ ...n, id: n.id, start: n.start || '', end: n.end || '' })) },
    documents: (detail.documents || []).map(d => {
      let ref = {};
      try { const parsed = typeof d.fileRef === 'string' ? JSON.parse(d.fileRef) : d.fileRef; if (parsed && typeof parsed === 'object') ref = parsed; } catch (error) { /* 历史文本没有可授权字节。 */ }
      return {name:d.name, file:{name:ref.name || d.name, ...(ref.text ? {text:String(ref.text)} : {}), ...(ref.assetId ? {assetId:String(ref.assetId)} : {}),
        ...(ref.commissionId && ref.dispatchSeq && ref.slot ? {commissionId:String(ref.commissionId),dispatchSeq:Number(ref.dispatchSeq),slot:ref.slot} : {})}, date:''};
    }).concat(detail.contractAssetId ? [{name:'工程合同附件',file:{name:'工程合同附件',assetId:String(detail.contractAssetId)},date:dateOf(detail.contractDate)}] : []),
    status: statusView(detail.status), statusRaw: detail.status,
    stageText: statusView(detail.status)
  };
}

module.exports = {
  api, online, dateOf, timestampDate, centsText, statusView, statusMatches, homeStats, nodeRows, nodeStatusView,
  stagesView, plansView, settleView, errorText, projectView,
  uploadVoucher, loadProject, pageData, recordView, perform, listCards, VOUCHER_ACTIONS, threeStageSumError,
  idemFingerprint
};

// ===================== 在线编排（record/projects/home 页 API 态；wx 依赖仅限上传 helper） =====================

/**
 * 凭据上传（基-1 票据链：ticket→字节→complete）。
 * M07-T04 批次#36：委托 api.uploadVoucher（基-1 票据真传——sha 幂等可重试；
 * 未 complete 的票据不产生有效资产引用，取消/超时不落业务记录）。
 */
function uploadVoucher(filePath, assetType) {
  return api.uploadVoucher(filePath, assetType);
}

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
    transaction: r.transactionNo || '', registrar: '',
    fileAssetId: r.fileAssetId || null, // R1 P1-5：授权查看数据源（downloadTicket→预览）
    amountText: centsText(r.amountCents), status: '', url: '' // wxml 行消费键（后端登记记录无中文状态流/原记录页跳转）
  }));
  return {
    p, installments,
    plans: plansView(bundle.materials),
    settle: settleView(bundle.settle || {}),
    dunning: (bundle.followups || []).filter(f => f.kind === 'COLLECTION').map(f => ({
      id: String(f.id == null ? '' : f.id), stage: String(f.stage || ''), label: installments.find(i=>i.key===f.stage)?.name || f.stage || '',
      date: timestampDate(f.createTime), // createTime 毫秒数字主形态（数字调 slice 曾整页崩）
      promiseDate: dateOf(f.promiseDate), note: f.note || ''
    })).reverse(),
    materialFollowups: (bundle.followups || []).filter(f => f.kind === 'MATERIAL').map(f => ({
      id: String(f.id == null ? '' : f.id), planId: String(f.materialPlanId || ''), date: timestampDate(f.createTime),
      promiseDate: dateOf(f.promiseDate), note: f.note || ''
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

/** prepare 三期 Σ 前端校验（签约后>0；基础完成后/封顶后≥0；Σ=总额）——纯函数可测；null=通过。
 * 金额口径与后端对齐：字符串精确转分（最多两位小数，拒 1.005/2.001 类值——后端 longValueExact 会拒，
 * Math.round(v*100) 浮点口径会误放），不走 Number 浮点乘法。 */
function threeStageSumError(f) {
  const stageCents = (key, allowEmpty) => {
    const raw = String(f[key] == null ? '' : f[key]).trim();
    if (!raw) return allowEmpty ? 0 : NaN;
    if (!/^\d+(\.\d{1,2})?$/.test(raw)) return NaN;
    const [yuan, frac = ''] = raw.split('.');
    return Number(yuan) * 100 + Number(((frac + '00').slice(0, 2)) || '0');
  };
  const totalCents = stageCents('total', false), signAfterCents = stageCents('signAfter', false);
  const baseDoneCents = stageCents('baseDone', true), capDoneCents = stageCents('capDone', true);
  if (!Number.isFinite(totalCents) || totalCents <= 0) return { code: 0, msg: '合同总额须为大于 0 的有效金额（最多两位小数）' };
  if (!Number.isFinite(signAfterCents) || signAfterCents <= 0) return { code: 0, msg: '签约后金额须为大于 0 的有效金额（最多两位小数）' };
  if (!Number.isFinite(baseDoneCents) || !Number.isFinite(capDoneCents)) return { code: 0, msg: '基础完成后/封顶后金额须为不小于 0 的数字（未约定填 0，最多两位小数）' };
  const sum = signAfterCents + baseDoneCents + capDoneCents;
  if (sum !== totalCents) {
    const gap = (Math.abs(totalCents - sum) / 100).toFixed(2);
    return { code: 0, msg: '三期金额之和须等于合同总额（当前差 ' + gap + ' 元）' };
  }
  return null;
}

/**
 * 幂等命令指纹（Codex R4-R6）：同目标同内容=同一命令（失败重试复用键）；改用户输入=新命令。
 * 按 action 投影实际提交字段——排除服务端回流/展示字段（supplyOptions/supplyLabel/行余量统计），
 * 但保留行内用户输入：material-adjust 的 {id,adjust}、material-plan 的提交行、plan 的节点输入。
 * 纯函数导出供 verify-idem-fingerprint.cjs 行为级验证（响应丢失→刷新回流变化→同内容重试指纹必须稳定）。
 */
function idemFingerprint(action, form) {
  const FP_LINES = {
    'material-adjust': l => ({ id: l.id, adjust: l.adjust }),
    'material-terms': l => ({ id: l.id, supplyId: l.supplyId, supplier: l.supplier, unitPrice: l.unitPrice, freight: l.freight }),
    'material-plan': l => ({ name: l.name, spec: l.spec, unit: l.unit, quantity: l.quantity })
  };
  const FP_NODES = n => ({ nodeIndex: n.nodeIndex ?? n.index, name: n.name, startDate: n.start, endDate: n.end });
  return JSON.stringify(form || {}, (k, v) => {
    if (k === 'supplyOptions' || k === 'supplyLabel') return undefined; // 展示选项/标签（服务端回流）
    if (k === 'lines') {
      const project = FP_LINES[action];
      if (!project) return undefined; // 其余 action 的 lines 为服务端回流行（余量统计）——不入指纹
      // R7 P2：与提交端同一有效行过滤（material-plan 提交时丢弃空名称行）——提交后响应丢失、
      // 用户补点空行未填再重试，请求正文不变，指纹须同样稳定
      return { rows: (v || []).map(project).filter(r => (r.name != null && String(r.name).trim() !== '') || r.id != null || r.adjust != null) };
    }
    // 计划表仅 plan 动作入指纹（内部键改名 ns——避免 replacer 对返回值内同名键递归命中自身死循环）
    if (k === 'nodes') return action === 'plan' ? { ns: (v || []).map(FP_NODES) } : undefined;
    return v;
  });
}

/**
 * 在线动作映射（record 页 perform 的 API 态；form 沿用页面现有字段名）。
 * key 为调用方一次性 Idempotency-Key（重试同一动作复用）；返回 Promise；错误抛 {code,msg} 由页面 errorText 展示。
 */
async function perform(action, id, form, node, key, batchExpected) {
  const f = form || {};
  // M07-T02：prepare 三期 Σ 校验（金额填错即时报差额）——批次#36 凭据门禁已摘（真传通道开放）
  if (action === 'prepare') { const sumErr = threeStageSumError(f); if (sumErr) throw sumErr; }
  const dateStr = f.date || null;
  switch (action) {
    case 'prepare': {
      // M07-T02：三期约定（签约后>0；基础完成后/封顶后≥0 允许 0=未约定，未填默认 0；
      // Σ须=总额，后端并与统一合同对齐校验）——R1 P2-8：0 值不被 yuan() 的 >0 校验拒绝
      const stageAmount = (label) => {
        const raw = String(f[label] == null ? '' : f[label]).trim();
        if (!raw) return 0;
        const v = Number(raw);
        if (!Number.isFinite(v) || v < 0) throw { code: 0, msg: label + '金额须为不小于 0 的数字' };
        return v;
      };
      return api.plan.prepare(id, {
        total: yuan(f, 'total', '合同总额'),
        signAfter: yuan(f, 'signAfter', '签约后'),
        baseDone: stageAmount('baseDone'),
        capDone: stageAmount('capDone'),
        contractAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING'),
        note: f.note, workers: f.workers || '',
        nodes: (f.nodes || []).map(n => ({nodeIndex:Number(n.nodeIndex ?? n.index),name:n.name,startDate:dateOf(n.start),endDate:dateOf(n.end)}))
      }, key);
    }
    case 'delay':
      if(!f.delayOwnerUserId||!f.recoveryDate)throw Error('请选择处理责任人及预计完成日期');
      return api.progress.save(id,node.id,{reason:f.delayReason,ownerUserId:f.delayOwnerUserId,recoveryDate:f.recoveryDate,action:f.recoveryAction},key);
    case 'camera-save':
      return api.cameras.save(id,{cameraId:f.cameraId||null,name:f.cameraName,location:f.cameraLocation,url:f.cameraUrl||'',enabled:f.cameraEnabled!==false},key);
    case 'start':
      return api.plan.start(id, { date: dateStr, note: f.note || '',
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING') }, key);
    case 'node': {
      const files = f.files && f.files.length ? f.files : f.file ? [f.file] : [];
      if (!files.length || files.length > 9) throw new Error('请选择1至9份现场验收凭据');
      const assetIds = [];
      for (const file of files) assetIds.push(await uploadVoucher(file.path, 'ENG_SITE_PHOTO'));
      return api.plan.complete(id, node.id, {
        date: dateStr, note: f.note, fileAssetIds: assetIds, noMaterials: !!f.noMaterials
      }, key);
    }
    case 'node-reopen':
      return api.plan.reopen(id, node.id, f.nodeReason || f.reason, key);
    case 'node-correct': {
      const completedAt = f.nodeDate || dateStr, reason = String(f.nodeReason || f.reason || '').trim();
      if (!completedAt || !reason) throw new Error('请填写完成日期和更正原因');
      const body = { completedAt, reason }, note = String(f.nodeNote || f.note || '').trim();
      if (note) body.note = note;
      const file = f.nodeFile || f.file;
      if (f.nodeRecordId || f.nodeReplaceAssetId || file) {
        if (!f.nodeRecordId || !f.nodeReplaceAssetId || !file || !file.path) throw new Error('请选择要替换的原凭据和新凭据');
        body.replacementRecordId = String(f.nodeRecordId);
        body.replaceFileAssetId = String(f.nodeReplaceAssetId);
        body.fileAssetId = await uploadVoucher(file.path, 'ENG_SITE_PHOTO');
      }
      return api.plan.correct(id, node.id, body, key);
    }
    case 'site': {
      const files = f.files && f.files.length ? f.files : f.file ? [f.file] : [];
      const assetIds = [];
      for (const file of files) assetIds.push(await uploadVoucher(file.path, 'ENG_SITE_PHOTO'));
      return api.site.create(id, node.id, f.note, assetIds, dateStr, key);
    }
    case 'site-correct':
      return api.site.correct(f.siteId || f.recordId, f.siteNote || f.note,
        f.siteFile ? [await uploadVoucher(f.siteFile.path, 'ENG_SITE_PHOTO')] : [],
        f.siteReason || f.reason, key);
    case 'collect':
      if (!f.stage) throw new Error('请选择付款节点');
      return api.collections.register(id, {
        stage: ({start:'SIGN_AFTER',base:'BASE_DONE',topping:'CAP_DONE'})[f.stage] || f.stage,
        amount: yuan(f, 'amount', '收款金额'),
        transactionNo: String(f.transaction || '').trim(), occurredAt: dateStr,
        // R2 P1-2：收款凭据走 FIN_VOUCHER 通道（finance 岗在 FIN 白名单内；ENG 类通道签票即 403）
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'FIN_VOUCHER')
      }, key);
    case 'dunning':
      if (!f.stage) throw new Error('请选择付款节点');
      return api.collections.dunning(id, ({start:'SIGN_AFTER',base:'BASE_DONE',topping:'CAP_DONE'})[f.stage] || f.stage,
        f.promiseDate || null, String(f.note || '').trim(), key);
    case 'material-plan': {
      const lines = (f.lines || []).filter(l => String(l.name || '').trim());
      return api.materials.submit(id, {
        nodeId: f.nodeId || (node && node.id), due: f.due,
        lines: lines.map(l => ({ name: l.name, spec: l.spec || '', unit: l.unit, quantity: Number(l.quantity) }))
      }, key);
    }
    case 'material-terms': {
      const sessionAlive = http.sessionGuard(), postId = String(http.currentPostId());
      const credential = http.token(), tenantId = String(http.tenantId());
      const requireAlive = () => {
        if (!sessionAlive() || String(http.currentPostId()) !== postId
          || http.token() !== credential || String(http.tenantId()) !== tenantId)
          throw { code: 0, msg: '身份已切换，请切回原岗位核对采购记录后重试' };
      };
      const rows = (f.lines || []).filter(l => !l.cancelled && !l.locked && !l.termsLocked && String(l.unitPrice == null ? '' : l.unitPrice).trim());
      if (!rows.length) throw { code: 0, msg: '请填写至少一项实际采购金额' };
      if (!f.file || !f.file.path) throw { code: 0, msg: '请选择真实采购凭据' };
      // 在任何写请求前校验整批；部分成功后用同一行幂等键重放，不重复创建应付。
      const commands = rows.map(l => {
        const supplier = String(l.supplier || '').trim(), raw = String(l.freight == null || l.freight === '' ? '0' : l.freight).trim();
        if (!l.supplyId || !supplier || !/^\d+(\.\d{1,2})?$/.test(String(l.unitPrice).trim()) || !/^\d+(\.\d{1,2})?$/.test(raw))
          throw { code: 0, msg: '请填写实际供应方和有效采购金额（最多两位小数）' };
        return { supplyId: String(l.supplyId), supplier, unitPrice: yuan(l, 'unitPrice', '单价'), freight: Number(raw) };
      });
      requireAlive();
      const assetId = await uploadVoucher(f.file.path, 'FIN_VOUCHER');
      requireAlive();
      const basis = JSON.stringify({ assetId: String(assetId), name: f.file.name || '采购凭据' });
      const results = [];
      for (const c of commands) {
        // 页面回包守卫无法阻止后续循环使用新岗位发送；每次 await 两侧都锁定入口身份。
        requireAlive();
        const suffix = '-s' + c.supplyId;
        results.push(await api.materials.terms(c.supplyId, {
          supplier: c.supplier, unitPrice: c.unitPrice, freight: c.freight, basis
        }, String(key).slice(-(80 - suffix.length)) + suffix));
        requireAlive();
      }
      return results;
    }
    case 'arrival': {
      if (!f.supplyId) throw { code: 0, msg: '请选择确认到货的供应事项' };
      // 沿用开发版数量精度；校验先于上传，避免空值或 NaN 进入到货命令。
      const raw = String(f.actual == null ? '' : f.actual).trim(), actual = Number(raw);
      if (!/^\d+(\.\d{1,3})?$/.test(raw) || !Number.isFinite(actual) || actual <= 0)
        throw { code: 0, msg: '请填写大于0、最多三位小数的本次实际到货数量' };
      if (!String(f.transaction || '').trim() || !f.file?.path)
        throw { code: 0, msg: '请填写收货流水并选择到货凭据' };
      return api.materials.arrival(f.supplyId, {
        expected: batchExpected ?? actual, actual, quality: f.quality === '不合格' ? 'REJECTED' : 'OK',
        transactionNo: String(f.transaction).trim(), occurredAt: dateStr,
        fileAssetId: await uploadVoucher(f.file.path, 'ENG_SITE_PHOTO'), note: f.note || ''
      }, key);
    }
    case 'material-adjust': {
      // 后端为单材料维度（PUT /materials/{id}）；页面表单按计划单逐行填调整量，此处逐行提交
      // Codex R2 遗留：每行派生独立幂等子键（同键=同命令去重，共用一键多行会被后端去重丢更新；部分成功后整单重试时已成功行同子键幂等返回原结果）
      const rows = (f.lines || []).filter(l => String(l.adjust == null ? '' : l.adjust).trim() !== '');
      if (!rows.length) throw { code: 0, msg: '请填写要调整的需用数量' };
      for (const l of rows) await api.materials.adjust(l.id, Number(String(l.adjust).trim()), f.adjustReason || f.reason, key + '-m' + l.id);
      return { adjusted: rows.length };
    }
    case 'arrival-correct':
      return api.materials.arrivalCorrection(f.receiptId, Number(f.correctActual), f.correctReason, key);
    case 'material-followup':
      return api.materials.followup(id, f.planId, f.promiseDate || null, String(f.note || '').trim(), key);
    // 直接项目费用四动作（M07-T07/M09-T04 + 批次#38 收尾：cancel 前端接线——此前四 API 函数无消费方）
    case 'expense-register':
      return api.materials.expenseRegister(id, {
        name: String(f.expenseName || '').trim(), purpose: f.purposeCode,
        amount: yuan(f, 'expenseAmount', '费用金额'), note: f.note
      }, key);
    case 'expense-terms':
      return api.materials.expenseTerms(f.expenseId, {
        supplier: String(f.supplier || '').trim(), basis: String(f.basis || '').trim() || '费用凭据'
      }, key);
    case 'expense-revise':
      return api.materials.expenseRevise(f.expenseId, {
        newAmount: yuan(f, 'newAmount', '正确费用金额'), note: f.note
      }, key);
    case 'expense-cancel':
      return api.materials.expenseCancel(f.expenseId, String(f.cancelReason || '').trim(), key);
    case 'deliver': {
      // M07-T04 批次#36：同一凭据文件上传一次，交付/业主签收两引用同 assetId（省一次上传）
      const deliverAssetId = await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING');
      return api.closure.deliver(id, {
        date: dateStr, recipient: f.recipient,
        ownerAssetId: deliverAssetId,
        fileAssetId: deliverAssetId, note: f.note || ''
      }, key);
    }
    case 'settle':
      return api.closure.settle(id, { date: dateStr, note: f.note }, key);
    case 'archive':
      return api.closure.archive(id, key);
    case 'project':
      return api.projects.changeManager(id, String(f.engineerId), f.note || '', key);
    case 'plan': {
      const nodes = (f.nodes || []).map(n => ({
        nodeIndex: Number(n.nodeIndex || n.index || 0), name: n.name,
        startDate: dateOf(n.start), endDate: dateOf(n.end)
      }));
      return api.plan.save(id, nodes, key);
    }
    default:
      throw { code: 0, msg: '该操作暂未开通在线办理' };
  }
}

/** projects 页列表视图（卡片形状适配现有 wxml：W.card 兼容字段）。
 * 列表行无节点数据：进度按状态粗粒度派生（在建/待交付未知 → null，wxml 护栏隐藏进度条），
 * next/pace 按离线卡片（workbench.card）同分支口径降级。 */
function listCards(rows) {
  return (rows || []).map(r => {
    const status = statusView(r.status);
    const done = status === '已交付' || status === '已归档';
    const started = !!r.actualStartAt;
    return {
      id: String(r.id), name: r.name, city: r.city || '', customer: r.customer || '',
      status, statusText: status, statusKey: r.status, statusRaw: r.status,
      stage: status, title: status, engineer: '', actualStart: dateOf(r.actualStartAt),
      contractTotal: r.contractTotalCents != null ? r.contractTotalCents / 100 : null,
      image: '/pages/build/assets/house.jpg', area: r.area == null ? '' : r.area, floors: r.floors == null ? '' : r.floors,
      date: '', pace: '',
      progress: Number(r.totalNodes)>0 ? Math.round(Number(r.completedNodes||0)*100/Number(r.totalNodes)) : done ? 100 : (status === '待开工' ? 0 : -1),
      next: !started ? '核查开工条件' : done ? '交付资料与款项收尾' : '现场检查与节点验收',
      canFunds: false,
      url: '/pages/build/record/index?id=' + encodeURIComponent(String(r.id)) + '&view=overview'
    };
  });
}

/** 页面 field 构造（record 页同款形状，纯函数内联） */
const viewField = (key, label, type) => ({ key, label: ({
  '已签工程合同总额（元）':'合同总额（元）','签约后（元，大于 0）':'签约后款（元）',
  '基础完成后（元，≥0，未约定填 0）':'基础完成款（元）','封顶后（元，≥0，未约定填 0）':'封顶款（元）',
  '现场、班组和开工准备记录':'开工准备情况','收款流水或凭据编号':'收款凭据编号',
  '催收情况说明（选填）':'催收备注（选填）','确认到货的供应事项':'到货材料',
  '结算说明（收付款与成本核对情况）':'结算核对记录',
  '交付凭据（jpg/png/pdf，业主签收同文件）':'交付凭据（含业主签收）'
})[label] || label.replace('（jpg/png/pdf）',''), type,
  placeholder: ({signAfter:'金额须大于 0',baseDone:'未约定填 0',capDone:'未约定填 0'})[key] || '' });
const parseJsonList = raw => { try { const value=typeof raw==='string'?JSON.parse(raw):raw;return Array.isArray(value)?value:[]; } catch (_) { return []; } };

/**
 * record 页全量视图构造（A1：在线数据源按离线 wxml 同构出 fields/actions/form 与各区块键）。
 * 输入：loadProject 聚合 + 页面上下文（view/stage/planId/nodeId/现 form/是否已初始化/今天/是否可写身份）。
 * 输出：setData 增量（纯函数不触 wx）。凭据类动作默认不进 fields/actions（COS 批开放前在线仅读，防填完表单才失败）；
 * **M07-T02 例外=prepare**：三期表单可见可填（Σ 前端校验即时报差额），但提交入口同样随 COS 批（#36）恢复——
 * 在线当前不出保存按钮（R1 P1：不设必失败入口）；可写身份=engineer/project-manager（856 write 口径，后端另校验负责人/指定工程师本人）。
 */
function recordView(bundle, ctx) {
  const opts = ctx || {};
  const data = pageData(bundle);
  const d = bundle.detail;
  const actor = key => d.timelineActors?.[key] || '经办人未留存';
  const view = opts.view || 'overview';
  // M07-T02 三期：base（基础完成后期款）随 BASE_DONE 收款入口进入
  const stage = opts.stage || 'start';
  const today = opts.today || '';
  const base = '/pages/build/record/index?id=' + encodeURIComponent(String(d.id)) + '&view=';
  const p = data.p;
  const nodes = p.plan.nodes;
  const materialNodeId = data.plans.find(r => r.id === String(opts.planId || ''))?.nodeId;
  const node = (view === 'start' && nodes.find(n => n.milestone === 'start'))
    || ((opts.nodeId || materialNodeId) && nodes.find(n => n.id === String(opts.nodeId || materialNodeId)))
    || nodes.find(n => n.status !== '已完成') || nodes[nodes.length - 1] || null;
  if(opts.nodeId&&['node','delay'].includes(view)&&node?.id!==String(opts.nodeId))throw Error('原施工节点已变化，请返回刷新');
  if(node?.delayOwnerUserId&&!node.delayOwnerName)
    node.delayOwnerName=(opts.handlers||[]).find(h=>String(h.id)===node.delayOwnerUserId)?.name||node.delayOwnerUserId;
  const inspections = (d.siteRecords || []).filter(r => r.completion && String(r.node_id) === node?.id).map(r => ({
    id: String(r.id), date: timestampDate(r.occurred_at), result: r.note || '已完成',
    person: r.operator_name || r.author_name || (r.author_user_id ? '经办人 ' + r.author_user_id : '经办人未留存'),
    file: { assetId: parseJsonList(r.file_asset_ids)[0] }, files: parseJsonList(r.file_asset_ids).map(assetId => ({ assetId: String(assetId) })),
    corrections: parseJsonList(r.corrections).map((c, index) => ({
      id: String(index), date: dateOf(c.occurredAt), note: c.note || '', reason: c.reason || '', correctedAt: dateOf(c.date),
      files: parseJsonList(c.fileAssetIds).map(assetId => ({ assetId: String(assetId) }))
    }))
  }));
  const nodeEvidenceOptions = [{ recordId: '', assetId: '', label: '保留全部凭据' },
    ...inspections.flatMap((record, recordIndex) => record.files.map((file, index) => ({
      recordId: record.id, assetId: file.assetId,
      label: (inspections.length > 1 ? '验收记录' + (recordIndex + 1) + ' · ' : '') + '替换凭据' + (index + 1)
    })))];
  const own = !!opts.writeRole && (!opts.userId || [String(d.managerUserId),String(d.assignedEngineerUserId)].includes(String(opts.userId)));
  const financeRole = !!opts.financeRole; // M07-T04 批次#36：在线收款登记（856 collect 码=finance 系）
  const delivery = p.delivery, settlement = p.settlement, archived = p.archived;
  const toppingReached = nodes.some(n => n.milestone === 'topping' && n.status === '已完成');
  const part = data.installments.find(i => i.stage === stage || i.key === stage);

  // 计划单行补 url/due（后端汇总行无计划级 due，取首行需用日期派生）
  const plans = data.plans.map(r => ({
    ...r,
    due: r.due || (r.lines && r.lines[0] && r.lines[0].requiredDate) || '',
    url: base + 'material&planId=' + encodeURIComponent(r.id)
  }));
  const plan = plans.find(r => r.id === opts.planId) || null;

  // 表单沿用页面字段名；未初始化时给全量缺省（file 类键在线不可上传，占位 null）
  let form = { ...(opts.form || {}) };
  if (!opts.initialized || !form.nodes) {
    form = {
      ...form,
      // R4 P1-2/R5：到货供应事项显式选定（多行待到货不再自动错指；选项=余量>0 行）
      supplyOptions: (plan ? (plan.lines || []) : []).filter(l => l.supplyId && Number(l.remainingQty) > 0)
        .map(l => ({ id: String(l.supplyId), label: l.name + ' · 余 ' + l.remainingQty + l.unit })),
      supplyId: null, supplyLabel: '', // R5 P1-1：WXML 禁方法调用——label 预计算（wcc find() 编译失败教训）
      date: form.date || today, note: '', file: null, files: [],
      nodeId: (view==='materials'&&node?.milestone==='start'?nodes.find(n=>n.milestone!=='start'):node)?.id, nodes: nodes.map(n => ({ ...n })),
      lines: [{ name: '', spec: '', quantity: '', unit: '包' }], due: form.due || today,
      stage, transaction: '', amount: '', total: '', first: '', receiver: '', recipient: '',
      noMaterials: false, adjustReason: '', correctId: '', correctActual: '', correctReason: '',
      siteId: '', siteNote: '', siteFile: null, siteReason: '',
      nodeDate: (node && node.completedAt) || today, nodeNote: '', nodeFile: null, nodeReason: '', nodeRecordId: '', nodeReplaceAssetId: '',
      delayReason:node?.delayReason||'',delayOwnerUserId:node?.delayOwnerUserId||'',delayOwnerName:node?.delayOwnerName||'',recoveryDate:node?.recoveryDate||'',recoveryAction:node?.recoveryAction||'',
      promiseDate: ''
    };
    if (view === 'prepare') {
      form.total = d.contractTotalCents ? String(d.contractTotalCents / 100) : '';
      for (const [key,stageCode] of [['signAfter','SIGN_AFTER'],['baseDone','BASE_DONE'],['capDone','CAP_DONE']]) {
        const term = (bundle.collections?.stages || []).find(s => s.stage === stageCode);
        form[key] = term && term.expectedCents != null ? String(term.expectedCents / 100) : '';
      }
      if (!form.total && (bundle.collections?.stages || []).some(s=>s.expectedCents>0)) form.total=String((bundle.collections.stages||[]).reduce((sum,s)=>sum+Number(s.expectedCents||0),0)/100);
    }
  }

  if (part) form.stage = part.key;
  let fields = [], actions = [];
  if (view === 'collection') {
    // M07-T04 批次#36/R1 P1-4：在线收款登记恢复（finance 系岗——856 collect 码；封顶门禁后端裁决）
    if (financeRole && part && part.collectible && (part.stage !== 'topping' || toppingReached)) {
      fields = [viewField('amount', '本次收款金额（元）', 'digit'), viewField('date', '收款日期', 'date'),
        viewField('transaction', '收款流水或凭据编号'), viewField('file', '收款凭据（jpg/png/pdf）', 'file')];
      actions = [{ action: 'collect', label: '登记实际收款' }];
    } else if (own && part && part.collectible && !part.waitingForWork && (part.stage !== 'topping' || toppingReached)) {
      fields = [viewField('promiseDate', '业主承诺付款日（选填）', 'date'), viewField('note', '催收情况说明（选填）', 'textarea')];
      actions = [{ action: 'dunning', label: '登记已催收' }];
    }
  } else if (view === 'prepare') {
    // M07-T02：在线三期 prepare 表单（签约后/基础完成后/封顶后，Σ=总额；Σ 前端校验即时报差额）。
    // R1 P1：提交入口随凭据通道开放——当前 COS 门禁未摘（VOUCHER_ACTIONS），在线不出保存按钮，
    // 避免「填完表单必失败」；#36 摘除门禁恢复 actions 时须带状态门禁 own && !archived && !p.actualStart
    //（与本地分支一致——后端拒绝开工后修改）+ uploadVoucher 实装。
    fields = [
      viewField('total', '已签工程合同总额（元）', 'digit'),
      viewField('signAfter', '签约后（元，大于 0）', 'digit'),
      viewField('baseDone', '基础完成后（元，≥0，未约定填 0）', 'digit'),
      viewField('capDone', '封顶后（元，≥0，未约定填 0）', 'digit'),
      viewField('file', '双方已签合同', 'file'),
      viewField('note', '现场、班组和开工准备记录', 'textarea')
    ];
    // M07-T04 批次#36：凭据门禁已摘——恢复在线提交入口（状态门禁与本地分支一致：own+未归档+未开工；后端拒绝开工后修改）
    if (own && !archived && !p.actualStart) actions = [{ action: 'prepare', label: '提交开工准备' }]; // R1 P2-7：适配字段名（projectView 映射为 actualStart 对象）
    else fields = [];
  } else if (view === 'start') {
    // R1 P1-4：实际开工记录恢复（own；开工后后端拒绝修改）
    if (own && !archived && !p.actualStart && d.preparedAt && nodes.every(n=>n.start&&n.end) && data.installments.some(i=>i.stage==='start'&&i.settled)) {
      fields = [viewField('date', '实际开工日期', 'date'), viewField('note', '现场情况', 'textarea'),
        viewField('file', '开工凭据（jpg/png/pdf）', 'file')];
      actions = [{ action: 'start', label: '记录实际开工' }];
    }
  } else if (view === 'delay' && node) {
    if(own && !archived && !delivery && node.status!=='已完成' && node.deviationDays>0){
      fields=[viewField('delayReason','延期原因','textarea'),viewField('delayOwnerUserId','处理责任人','handler'),viewField('recoveryDate','预计完成日期','date'),viewField('recoveryAction','处理措施','textarea')];
      actions=[{action:'delay',label:'保存处理记录'}];
    }
  } else if (view === 'node' && node && node.milestone !== 'start') {
    // R1 P1-4：节点完成/更正/重开恢复（own；完成态切换表单）
    if (own && !archived && !delivery && p.actualStart && node.status !== '已完成' && nodes.find(n=>n.status!=='已完成')?.id===node.id) {
      fields = [viewField('date', '节点完成日期', 'date'), viewField('note', '节点完成情况', 'textarea'),
        viewField('file', '节点完成凭据（jpg/png/pdf）', 'file'), viewField('noMaterials', '本节点材料无需到场', 'check')];
      // R2 P1-4：现场记录创建恢复（多张照片，perform site→api.site.create）
      actions = [{ action: 'node', label: '标记节点完成' }, { action: 'site', label: '留存现场照片' }];
    } else if (own && !archived && !delivery && node.status === '已完成') {
      // 完成态只使用明确保留/替换原凭据的更正表单，不能再展示无目标的通用上传控件。
      actions = [{ action: 'node-correct', label: '更正节点记录' }];
      const index = nodes.findIndex(n => n.id === node.id);
      const nextId = nodes[index + 1]?.id;
      if (!nodes.slice(index + 1).some(n => n.status === '已完成')
        && !(d.siteRecords || []).some(r => String(r.node_id) === nextId)
        && !(node.milestone === 'topping' && (bundle.collections?.records || []).some(c => c.stage === 'CAP_DONE'))) {
        actions.push({ action: 'node-reopen', label: '撤回节点完成' });
      }
    }
  } else if (view === 'delivery') {
    // R1 P1-4：工程交付恢复（own；门禁=全节点+材料齐+全累计收款，后端裁决）
    if (own && !archived && !p.delivery) {
      fields = [viewField('date', '交付日期', 'date'), viewField('recipient', '交付接收人'),
        viewField('file', '交付凭据（jpg/png/pdf，业主签收同文件）', 'file'), viewField('note', '交付说明（选填）', 'textarea')];
      actions = [{ action: 'deliver', label: '登记工程交付' }];
    }
  } else if (view === 'project') {
    if (own && !delivery && !archived) actions = [{ action: 'plan', label: '保存施工计划' }];
  } else if (view === 'materials') {
    if (own && !delivery && (!opts.nodeId || (node && node.status !== '已完成'))) {
      fields = [viewField('due', '需到场日期', 'date'), viewField('note', '材料说明（选填）', 'textarea')];
      actions = [{ action: 'material-plan', label: '提交材料计划' }];
    }
  } else if (view === 'material' && plan) {
    form.planId = plan.id;
    const previousLines = form.lines || [];
    form.lines = plan.lines.map(l => ({ ...(previousLines.find(old => old.id === l.id) || {}), id: l.id, supplyId: l.supplyId, name: l.name, spec: l.spec || '', unit: l.unit,
      quantity: l.quantity, received: l.received, remaining: l.remaining, cancelled: !!l.cancelled,
      locked: l.locked, termsLocked: !l.termsEditable, supplier: (previousLines.find(old => old.id === l.id) || {}).supplier || l.supplier || '', actual: '', adjust: '' }));
    if (own && !delivery && plan.arrival !== '已验收齐全' && plan.arrival !== '已取消') {
      // R1 P1-4/R4 P1-2/R5 P1-2：确认到货恢复——供应事项显式选定（选项每次刷新；
      // 用户已选仍有效则保留，余量归零行落选后回落首项——刷新不再指回首行）
      // R5 P1-2：选项每次刷新（最新余量——落选行退出；用户已选仍有效则保留）
      form.supplyOptions = (plan.lines || []).filter(l => l.supplyId && Number(l.remainingQty) > 0)
        .map(l => ({ id: String(l.supplyId), label: l.name + ' · 余 ' + l.remainingQty + l.unit }));
      if (form.supplyOptions.length) {
        const stillValid = form.supplyOptions.find(o => o.id === form.supplyId);
        form.supplyId = (stillValid && form.supplyId) || form.supplyOptions[0].id;
        // R6 P2：label 从最新选项同步（选中行余量变化时显示不再过期）
        form.supplyLabel = stillValid ? stillValid.label : form.supplyOptions[0].label;
        fields = [viewField('supplyId', '确认到货的供应事项', 'supply'),
          viewField('actual', '实际到货数量', 'digit'), viewField('date', '到货日期', 'date'),
          viewField('transaction', '收货流水号'), viewField('file', '到货凭据（jpg/png/pdf）', 'file'),
          viewField('note', '到货说明（选填）', 'textarea')];
        actions = [{ action: 'arrival', label: '确认本次到货' }];
      } else fields = [];
    } else fields = [];
  } else if (view === 'settlement') {
    if (own && delivery && !settlement) {
      fields = [viewField('date', '结算确认日期', 'date'), viewField('note', '结算说明（收付款与成本核对情况）', 'textarea')];
      actions = [{ action: 'settle', label: '确认项目结算' }];
    }
  } else if (view === 'archive') {
    if (own && delivery && settlement && !archived) actions = [{ action: 'archive', label: '归档项目资料' }];
  } else if (view === 'expenses') {
    // 直接项目费用四动作（登记即计成本/挂应付/修订/取消）——费用清单由 record 页预拉（opts.expenses）
    form.purposeCode = form.purposeCode || 'LABOR';
    const mode = opts.expenseMode || 'register';
    if (own && !archived) {
      if (mode === 'terms') {
        fields = [viewField('supplier', '收款方（供应商）'), viewField('basis', '费用凭据（名称或编号）')];
        actions = [{ action: 'expense-terms', label: '挂接应付' }];
      } else if (mode === 'revise') {
        fields = [viewField('newAmount', '正确费用金额（元）', 'digit'), viewField('note', '变更说明', 'textarea')];
        actions = [{ action: 'expense-revise', label: '确认费用变更' }];
      } else if (mode === 'cancel') {
        fields = [viewField('cancelReason', '取消原因', 'textarea')];
        actions = [{ action: 'expense-cancel', label: '取消费用' }];
      } else {
        fields = [viewField('expenseName', '费用名称'), viewField('purposeCode', '费用用途', 'purpose'),
          viewField('expenseAmount', '费用金额（元）', 'digit'), viewField('note', '费用说明（选填）', 'textarea')];
        actions = [{ action: 'expense-register', label: '登记费用（确认即计成本）' }];
      }
    }
  }

  const current = nodes.find(n => n.status !== '已完成');
  const links = [['项目记录与计划', 'project'], ['项目资料', 'documents'], ['材料计划', 'materials'], ['项目收付款', 'payments'],
    ['交付与归档', delivery ? 'archive' : 'delivery'], ...(delivery ? [['项目结算', 'settlement']] : [])]
    .map(([label, v]) => ({ label, url: base + v }));
  const next = archived ? null
    : !d.preparedAt ? { title: '填写开工准备与施工计划', url: base + 'prepare' }
    : !p.actualStart && nodes.some(n=>!n.start || !n.end) ? {title:'补全施工计划日期',url:base+'project'}
    : !p.actualStart && data.installments.some(i=>i.stage==='start'&&!i.settled) ? {title:'跟进签约后期款',url:base+'collection&stage=start'}
    : !p.actualStart ? {title:'记录实际开工',url:base+'start'}
    : !delivery && current ? { title: current.name + '待记录', url: base + 'node&nodeId=' + encodeURIComponent(current.id) }
    : !delivery ? {title:'核对工程交付',url:base+'delivery'}
    : delivery && !settlement ? { title: '确认项目结算', url: base + 'settlement' }
    : settlement && !archived ? { title: '归档项目资料', url: base + 'archive' } : null;
  return {
    p, form, fields, actions, fileNames: {},
    // M07-T02：期次行补 collection 跳转（BASE_DONE 收款入口；stage 已是 STAGE_URL 归一值 start/base/topping）
    installments: data.installments.map(i => ({ ...i, url: i.stage === 'tail' && financeRole ? '/pages/admin/finance-desk/index?view=flows' + (i.stageId ? '&homeStage=' + encodeURIComponent(i.stageId) : '') : base + 'collection&stage=' + encodeURIComponent(i.key || i.stage) })),
    plans: view === 'materials' && opts.nodeId ? plans.filter(r=>r.nodeId===String(opts.nodeId)) : plans, settle: data.settle,
    // R2 P1-4：现场记录透出（后端 detail.siteRecords——JSONB 文本解析；site-correct 更正入口数据源）
    siteRecords: (d.siteRecords || []).filter(r => String(r.node_id) === String((node && node.id) || '')).map(r => {
      let files = [];
      try { files = JSON.parse(r.file_asset_ids || '[]').map(a => ({ assetId: a })); } catch (e) { files = []; }
      let corrections = [];
      try { corrections = JSON.parse(r.corrections || '[]'); } catch (e2) { corrections = []; }
      return { id: String(r.id), nodeId: String(r.node_id), note: r.note || '',
        date: timestampDate(r.occurred_at), completion: !!r.completion,
        files, corrections, canCorrect: own && !delivery && !r.completion };
    }),
    dunning: data.dunning, materialFollowups: data.materialFollowups, collections: p.collections,
    // 直接项目费用清单（行内 label/操作位预计算——WXML 禁方法调用）
    expenseRows:(opts.expenses||[]).map(e=>({
      id:String(e.expenseId),name:e.name||'',
      purposeText:({LABOR:'劳务',TRAVEL:'差旅',OTHER:'其它'})[e.purpose]||e.purpose||'',
      amountText:e.amountCents==null?'—':(e.amountCents/100).toFixed(2),
      statusText:e.status==='CANCELLED'?'已取消':(e.payableId?'已挂应付':'已登记'),
      canTerms:own&&!archived&&e.status!=='CANCELLED'&&!e.payableId,
      canRevise:own&&!archived&&e.status!=='CANCELLED',
      canCancel:own&&!archived&&e.status!=='CANCELLED'&&!e.payableId
    })),
    expenseMode:opts.expenseMode||'register',
    expenseTarget:form.expenseName||'',
    purposes:[{code:'LABOR',label:'劳务'},{code:'TRAVEL',label:'差旅'},{code:'OTHER',label:'其它'}],
    purposeLabel:({LABOR:'劳务',TRAVEL:'差旅',OTHER:'其它'})[form.purposeCode]||'劳务',
    docs:p.documents,events:[
      ...(d.sourceCommissionId?[{id:'handover',title:'业务移交建档 · 委托 '+d.sourceCommissionId,time:d.drawingVersion||'',by:actor('eng-project-from-handover')}]:[]),
      ...(d.preparedAt?[{id:'prepare',title:'开工准备已保存',time:dateOf(d.preparedAt),by:actor('eng-prepare')}]:[]),
      ...(p.actualStart?[{id:'start',title:'实际开工已记录',time:p.actualStart.date,by:actor('eng-start')}]:[]),
      ...nodes.filter(n=>n.status==='已完成').map(n=>({id:'node-'+n.id,title:n.name+'已完成',time:n.completedAt,by:actor(n.milestone==='start'?'eng-start':'eng-node-complete:'+n.id)})),
      ...(delivery?[{id:'deliver',title:'工程已交付 · '+delivery.recipient,time:delivery.date,by:actor('eng-deliver')}]:[]),
      ...(settlement?[{id:'settle',title:'项目已确认结算',time:settlement.date,by:actor('eng-settle')}]:[]),
      ...(archived?[{id:'archive',title:'项目资料已归档',time:dateOf(d.closure.archivedAt),by:actor('eng-archive')}]:[])
    ],
    nodeRows:nodes.map(n=>({...n,url:base+(n.milestone==='start'?'start':'node&nodeId='+encodeURIComponent(n.id)),delayUrl:base+'delay&nodeId='+encodeURIComponent(n.id)})),
    cameraUrl:base+'cameras',delayUrl:node?base+'delay&nodeId='+encodeURIComponent(node.id):'',
    startBlocked:!p.actualStart&&!actions.length&&view==='start'?(next?.title||'请先确认签约后期款'):'',
    startPrerequisiteUrl:next?.url&&next.url!==base+'start'?next.url:base+'collection&stage=start',
    canManageCameras:own&&!archived&&!delivery,
    canTransferManager:view==='project'&&own&&!archived&&!!opts.userId
      &&String(opts.userId)===String(d.managerUserId),
    handlers:opts.handlers||[],cameras:opts.cameras||[],cameraError:opts.cameraError||'',
    delayHistory:(node?.delayHistory||[]).map((r,i)=>({...r,id:String(i),recordedAt:timestampDate(r.recordedAt),recoveryDate:dateOf(r.recoveryDate),ownerName:r.ownerName||(opts.handlers||[]).find(h=>String(h.id)===String(r.ownerUserId))?.name||'原责任人'})),
    nodePlans:plans.filter(r=>r.nodeId===node?.id),
    nodeMoney:node?(()=>{const rows=plans.filter(r=>r.nodeId===node.id),sum=k=>rows.reduce((n,r)=>n+Number(r[k]||0),0);return{known:rows.every(r=>r.known),plannedText:centsText(sum('total')),committedText:centsText(sum('total')),paidText:centsText(sum('paid')),remainingText:centsText(sum('remaining')),expenses:[]};})():null,
    inspections,nodeEvidenceOptions,
    nodeEvidenceIndex:Math.max(0,nodeEvidenceOptions.findIndex(option=>option.recordId===(form.nodeRecordId||'')&&option.assetId===(form.nodeReplaceAssetId||''))),
    arrivals:(plan?.lines||[]).flatMap(l=>(l.receipts||[]).map(r=>({id:String(r.id),materialName:l.name,unit:l.unit,date:dateOf(r.occurredAt),actual:r.actualQuantity,note:r.transactionNo||'',qualityText:r.qualified?'合格实收':'实收待质检处理',file:r.fileAssetId?{assetId:String(r.fileAssetId)}:null,corrections:parseJsonList(r.corrections),canCorrect:own&&!delivery&&!!r.id}))),
    pending:own&&!archived?[
      ...(!delivery?nodes.filter(n=>n.status!=='已完成'&&n.deviationDays>0).map(n=>({id:'delay-'+n.id,title:n.name+'延期'+n.deviationDays+'天',status:n.delayOwnerName,url:base+'delay&nodeId='+encodeURIComponent(n.id)})):[]),
      ...(next?[{id:'next',title:next.title,url:next.url}]:[]),
      ...(!delivery?plans.filter(r=>r.lines.some(l=>Number(l.remainingQty)>0)).map(r=>({
        id:'material-'+r.id,title:'材料到货待核对',status:nodes.find(n=>n.id===r.nodeId)?.name||'',url:r.url
      })):[])
    ]:[],waiting:[],engineers:[],engineerIndex:0,templates:[],templateIndex:0,
    links, next,
    node: node || null, plan: plan || null,
    materialNodes:nodes.filter(n=>n.milestone!=='start'),
    nodeIndex: Math.max(0, (view==='materials'?nodes.filter(n=>n.milestone!=='start'):nodes).findIndex(n => n.id === (form.nodeId || (node && node.id)))),
    nodeMaterialUrl: node ? base + 'materials&nodeId=' + encodeURIComponent(node.id) : '',
    nodeFundingUrl: node ? base + 'funding&nodeId=' + encodeURIComponent(node.id) : '',
    nodeRecordUrl: node ? base + 'node&nodeId=' + encodeURIComponent(node.id) : '',
    nodeUrl: current ? base + (current.milestone==='start'?'start':'node&nodeId='+encodeURIComponent(current.id)) : base + (delivery ? 'archive' : 'delivery'),
    settlementUrl: base + 'settlement',
    paceDone: nodes.filter(n => n.status === '已完成').length, paceTotal: nodes.length,
    paceOverdue: nodes.filter(n => n.status !== '已完成' && n.deviationDays>0).length,
    stage, stageText: p.stageText, partLabel: part?.name || (stage === 'base' ? '基础完成后期款'
      : stage === 'topping' ? '封顶后期款' : stage === 'tail' ? '交付尾款' : '签约后期款'),
    own, canPay: false, finance: financeRole,
    canAdjust: own && !delivery && !!plan,
    canTerms: (opts.termsRole == null ? financeRole : !!opts.termsRole) && !archived && !!plan && plan.lines.some(l => l.termsEditable),
    termsLocked: !!plan && !plan.lines.some(l => l.termsEditable),
    canCorrectNode: actions.some(action => action.action === 'node-correct'),
    canReopen: actions.some(action => action.action === 'node-reopen'),
    demo: false
  };
}
