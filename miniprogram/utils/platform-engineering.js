/**
 * 工程全周期纯函数层（阶段 2）：后端英文态→页面中文态映射、日期双形状归一、
 * 分→元文本、汇总派生、脱敏视图适配（record 页 data 形状适配层——wxml 不动）。
 * 后端契约见 api/franchise/engineering.js；离线/失败一律降级本地演示（utils/engineering-records.js）不白屏。
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

/** 两期收款汇总视图（蓝本 installments 形状：stage/name/expected/confirmed/remaining/due/status/url）。
 * expected/confirmed/remaining 照离线分支口径输出分→元文本（wxml 直接渲染），Text 系键同源不另造实现；
 * url 经可选 urlOf(stage) 注入（settle 汇总复用本函数时不传）。 */
function stagesView(stages, urlOf) {
  // M07-T02：三期映射（SIGN_AFTER 签约后/BASE_DONE 基础完成后/CAP_DONE 封顶后；存量 START/TOPPING 兼容）
  const STAGE_NAME = { SIGN_AFTER: '签约后期款', BASE_DONE: '基础完成后期款', CAP_DONE: '封顶后期款',
    START: '开工款', TOPPING: '封顶款' };
  const STAGE_URL = { SIGN_AFTER: 'start', BASE_DONE: 'base', CAP_DONE: 'topping', START: 'start', TOPPING: 'topping' };
  return (stages || []).map(s => {
    const expected = centsText(s.expectedCents), confirmed = centsText(s.confirmedCents), remaining = centsText(s.remainingCents);
    return {
      stage: STAGE_URL[s.stage] || 'start',
      name: STAGE_NAME[s.stage] || s.stage,
      expected, confirmed, remaining, due: dateOf(s.due),
      expectedText: expected, confirmedText: confirmed, remainingText: remaining,
      settled: (s.confirmedCents || 0) >= (s.expectedCents || 0) && (s.expectedCents || 0) > 0,
      status: (s.expectedCents || 0) > 0 && (s.remainingCents || 0) <= 0 ? '已收齐' : '待财务登记收款',
      url: urlOf ? urlOf(STAGE_URL[s.stage] || 'start') : ''
    };
  });
}

/** 材料计划单汇总视图（到货/付款派生态 → 蓝本中文） */
function plansView(summary) {
  return (summary && summary.plans || []).map(planRow => ({
    id: String(planRow.materialPlanId), nodeId: '', due: '',
    // R2 P1-3：供应事项 id（行级 lines[].supplyId 聚合——arrival 提交路径 /supplies/{supplyId}/arrival）
    supplyId: (planRow.lines || []).map(l => l.supplyId).filter(Boolean)[0] || null,
    arrival: planRow.arrival, payment: paymentText(planRow),
    known: true, complete: planRow.arrival === '已验收齐全',
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
    managerId: detail.managerUserId, manager: '',
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
    plan: { version: 0, nodes: nodes.map(n => ({ ...n, id: n.id.replace(/^\D+/, ''), start: n.start || '', end: n.end || '' })) },
    documents: (detail.documents || []).map(d => ({ name: d.name, file: { name: d.name }, date: '' })),
    status: statusView(detail.status), statusRaw: detail.status,
    stageText: statusView(detail.status)
  };
}

module.exports = {
  api, online, dateOf, timestampDate, centsText, statusView, statusMatches, nodeRows, nodeStatusView,
  stagesView, plansView, settleView, errorText, projectView,
  uploadVoucher, loadProject, pageData, recordView, perform, listCards, VOUCHER_ACTIONS, threeStageSumError
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
    transaction: r.transactionNo || '', registrar: '',
    fileAssetId: r.fileAssetId || null, // R1 P1-5：授权查看数据源（downloadTicket→预览）
    amountText: centsText(r.amountCents), status: '', url: '' // wxml 行消费键（后端登记记录无中文状态流/原记录页跳转）
  }));
  return {
    p, installments,
    plans: plansView(bundle.materials),
    settle: settleView(bundle.settle || {}),
    dunning: (bundle.followups || []).filter(f => f.kind === 'COLLECTION').map(f => ({
      id: String(f.id == null ? '' : f.id), stage: (f.stage || 'START').toLowerCase(),
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
 * 在线动作映射（record 页 perform 的 API 态；form 沿用页面现有字段名）。
 * key 为调用方一次性 Idempotency-Key（重试同一动作复用）；返回 Promise；错误抛 {code,msg} 由页面 errorText 展示。
 */
async function perform(action, id, form, node, key) {
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
        note: f.note, workers: f.workers || ''
      }, key);
    }
    case 'start':
      return api.plan.start(id, { date: dateStr, note: f.note || '',
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_DRAWING') }, key);
    case 'node': {
      const files = f.files && f.files.length ? f.files : f.file ? [f.file] : [];
      const assetIds = [];
      for (const file of files.slice(0, 1)) assetIds.push(await uploadVoucher(file.path, 'ENG_SITE_PHOTO'));
      return api.plan.complete(id, node.id, {
        date: dateStr, note: f.note, fileAssetId: assetIds[0], noMaterials: !!f.noMaterials
      }, key);
    }
    case 'node-reopen':
      return api.plan.reopen(id, node.id, f.nodeReason || f.reason, key);
    case 'node-correct':
      return api.plan.correct(id, node.id, {
        completedAt: f.nodeDate || dateStr, note: f.nodeNote || f.note,
        fileAssetId: await uploadVoucher((f.nodeFile || f.file) && (f.nodeFile || f.file).path, 'ENG_SITE_PHOTO'),
        reason: f.nodeReason || f.reason
      }, key);
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
      return api.collections.register(id, {
        stage: f.stage === 'topping' ? 'CAP_DONE' : f.stage === 'base' ? 'BASE_DONE' : 'SIGN_AFTER',
        amount: yuan(f, 'amount', '收款金额'),
        transactionNo: String(f.transaction || '').trim(), occurredAt: dateStr,
        // R2 P1-2：收款凭据走 FIN_VOUCHER 通道（finance 岗在 FIN 白名单内；ENG 类通道签票即 403）
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'FIN_VOUCHER')
      }, key);
    case 'dunning':
      return api.collections.dunning(id, f.stage === 'topping' ? 'CAP_DONE' : f.stage === 'base' ? 'BASE_DONE' : 'SIGN_AFTER',
        f.promiseDate || null, String(f.note || '').trim(), key);
    case 'material-plan': {
      const lines = (f.lines || []).filter(l => String(l.name || '').trim());
      return api.materials.submit(id, {
        nodeId: node && node.id, due: f.due,
        lines: lines.map(l => ({ name: l.name, spec: l.spec || '', unit: l.unit, quantity: Number(l.quantity) }))
      }, key);
    }
    case 'material-terms':
      return api.materials.terms(f.supplyId, {
        supplier: f.supplier, unitPrice: yuan(f, 'unitPrice', '单价'), freight: Number(f.freight || 0),
        basis: (f.file && f.file.name) || '采购凭据' // 展示文本口径：只送文件名字符串，不送 JSON 串
      }, key);
    case 'arrival':
      if (!f.supplyId) return Promise.reject({ code: 0, msg: '请选择确认到货的供应事项' }); // R4 P1-2 显式选定
      return api.materials.arrival(f.supplyId, {
        actual: Number(f.actual), quality: f.quality === '不合格' ? 'REJECTED' : 'OK',
        transactionNo: f.transaction, occurredAt: dateStr,
        fileAssetId: await uploadVoucher(f.file && f.file.path, 'ENG_SITE_PHOTO'), note: f.note || ''
      }, key);
    case 'material-adjust': {
      // 后端为单材料维度（PUT /materials/{id}）；页面表单按计划单逐行填调整量，此处逐行提交
      const rows = (f.lines || []).filter(l => String(l.adjust == null ? '' : l.adjust).trim() !== '');
      if (!rows.length) throw { code: 0, msg: '请填写要调整的需用数量' };
      for (const l of rows) await api.materials.adjust(l.id, Number(String(l.adjust).trim()), f.adjustReason || f.reason, key);
      return { adjusted: rows.length };
    }
    case 'arrival-correct':
      return api.materials.arrivalCorrection(f.receiptId, Number(f.correctActual), f.correctReason, key);
    case 'material-followup':
      return api.materials.followup(id, f.planId, f.promiseDate || null, String(f.note || '').trim(), key);
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
      return api.projects.changeManager(id, Number(f.engineerId), f.note || '', key);
    case 'plan': {
      const nodes = (f.nodes || []).map(n => ({
        nodeIndex: Number(n.nodeIndex || n.index || 0), name: n.name,
        startDate: dateOf(n.start), endDate: dateOf(n.end)
      }));
      return api.plan.save(id, nodes, key);
    }
    default:
      throw { code: 0, msg: '该操作暂未开通在线办理，请在本地演示态查看' };
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
      status, stage: status, title: status, engineer: '', actualStart: dateOf(r.actualStartAt),
      contractTotal: r.contractTotalCents != null ? r.contractTotalCents / 100 : null,
      image: '/pages/build/assets/house.jpg', area: r.area == null ? '' : r.area, floors: r.floors == null ? '' : r.floors,
      date: '', pace: '',
      progress: done ? 100 : (status === '待开工' ? 0 : -1), // -1=列表行无节点数据不可知（wxml 护栏隐藏进度条）
      next: !started ? '核查开工条件' : done ? '交付资料与款项收尾' : '现场检查与节点验收',
      canFunds: false
    };
  });
}

/** 页面 field 构造（record 页同款形状，纯函数内联） */
const viewField = (key, label, type) => ({ key, label, type });

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
  const view = opts.view || 'overview';
  // M07-T02 三期：base（基础完成后期款）随 BASE_DONE 收款入口进入
  const stage = opts.stage === 'topping' ? 'topping' : opts.stage === 'base' ? 'base' : 'start';
  const today = opts.today || '';
  const base = '/pages/build/record/index?id=' + encodeURIComponent(String(d.id)) + '&view=';
  const p = data.p;
  const nodes = p.plan.nodes;
  const node = (opts.nodeId && nodes.find(n => n.id === String(opts.nodeId)))
    || nodes.find(n => n.status !== '已完成') || nodes[nodes.length - 1] || null;
  const own = !!opts.writeRole;
  const financeRole = !!opts.financeRole; // M07-T04 批次#36：在线收款登记（856 collect 码=finance 系）
  const delivery = p.delivery, settlement = p.settlement, archived = p.archived;
  const toppingReached = nodes.some(n => n.milestone === 'topping' && n.status === '已完成');
  const part = data.installments.find(i => i.stage === stage);

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
      nodeId: node && node.id, nodes: nodes.map(n => ({ ...n })),
      lines: [{ name: '', spec: '', quantity: '', unit: '包' }], due: form.due || today,
      stage, transaction: '', amount: '', total: '', first: '', receiver: '', recipient: '',
      noMaterials: false, adjustReason: '', correctId: '', correctActual: '', correctReason: '',
      siteId: '', siteNote: '', siteFile: null, siteReason: '',
      nodeDate: (node && node.completedAt) || today, nodeNote: '', nodeFile: null, nodeReason: '',
      promiseDate: ''
    };
  }

  let fields = [], actions = [];
  if (view === 'collection') {
    // M07-T04 批次#36/R1 P1-4：在线收款登记恢复（finance 系岗——856 collect 码；封顶门禁后端裁决）
    if (financeRole && part && !part.settled && (stage !== 'topping' || toppingReached)) {
      fields = [viewField('amount', '本次收款金额（元）', 'digit'), viewField('date', '收款日期', 'date'),
        viewField('transaction', '收款流水或凭据编号'), viewField('file', '收款凭据（jpg/png/pdf）', 'file')];
      actions = [{ action: 'collect', label: '登记实际收款' }];
    } else if (own && part && part.status !== '已收齐' && (stage !== 'topping' || toppingReached)) {
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
  } else if (view === 'start') {
    // R1 P1-4：实际开工记录恢复（own；开工后后端拒绝修改）
    if (own && !archived && !p.actualStart) {
      fields = [viewField('date', '实际开工日期', 'date'), viewField('note', '现场情况', 'textarea'),
        viewField('file', '开工凭据（jpg/png/pdf）', 'file')];
      actions = [{ action: 'start', label: '记录实际开工' }];
    }
  } else if (view === 'node' && node) {
    // R1 P1-4：节点完成/更正/重开恢复（own；完成态切换表单）
    if (own && !archived && !delivery && node.status !== '已完成') {
      fields = [viewField('date', '节点完成日期', 'date'), viewField('note', '节点完成情况', 'textarea'),
        viewField('file', '节点完成凭据（jpg/png/pdf）', 'file'), viewField('noMaterials', '本节点材料无需到场', 'check')];
      // R2 P1-4：现场记录创建恢复（多张照片，perform site→api.site.create）
      actions = [{ action: 'node', label: '标记节点完成' }, { action: 'site', label: '留存现场照片' }];
    } else if (own && !archived && !delivery && node.status === '已完成') {
      fields = [viewField('nodeDate', '正确完成日期', 'date'), viewField('nodeNote', '正确节点情况', 'textarea'),
        viewField('nodeFile', '正确凭据（jpg/png/pdf）', 'file'), viewField('nodeReason', '更正原因', 'textarea')];
      actions = [{ action: 'node-correct', label: '更正节点记录' }];
    }
  } else if (view === 'delivery') {
    // R1 P1-4：工程交付恢复（own；门禁=全节点+材料齐+全累计收款，后端裁决）
    if (own && !archived && !p.delivery) {
      fields = [viewField('date', '交付日期', 'date'), viewField('recipient', '交付接收人'),
        viewField('file', '交付凭据（jpg/png/pdf，业主签收同文件）', 'file'), viewField('note', '交付说明（选填）', 'textarea')];
      actions = [{ action: 'deliver', label: '登记工程交付' }];
    }
  } else if (view === 'project') {
    if (own && !archived) actions = [{ action: 'plan', label: '保存施工计划' }];
  } else if (view === 'materials') {
    if (own && !delivery && (!opts.nodeId || (node && node.status !== '已完成'))) {
      fields = [viewField('due', '需到场日期', 'date'), viewField('note', '材料说明（选填）', 'textarea')];
      actions = [{ action: 'material-plan', label: '提交材料计划' }];
    }
  } else if (view === 'material' && plan) {
    form.planId = plan.id;
    form.lines = plan.lines.map(l => ({ id: l.id, name: l.name, spec: l.spec || '', unit: l.unit,
      quantity: l.quantity, received: l.received, remaining: l.remaining, cancelled: !!l.cancelled,
      supplier: l.supplier || '', actual: '', adjust: '' }));
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
  }

  const current = nodes.find(n => n.status !== '已完成');
  const links = [['项目记录与计划', 'project'], ['项目资料', 'documents'], ['材料计划', 'materials'], ['项目收付款', 'payments'],
    ['交付与归档', delivery ? 'archive' : 'delivery'], ...(delivery ? [['项目结算', 'settlement']] : [])]
    .map(([label, v]) => ({ label, url: base + v }));
  const next = !delivery && current ? { title: current.name + '待记录', url: base + 'node&nodeId=' + encodeURIComponent(current.id) }
    : delivery && !settlement ? { title: '确认项目结算', url: base + 'settlement' }
    : settlement && !archived ? { title: '归档项目资料', url: base + 'archive' } : null;
  return {
    p, form, fields, actions, fileNames: {},
    // M07-T02：期次行补 collection 跳转（BASE_DONE 收款入口；stage 已是 STAGE_URL 归一值 start/base/topping）
    installments: data.installments.map(i => ({ ...i, url: base + 'collection&stage=' + i.stage })),
    plans, settle: data.settle,
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
    docs: p.documents, events: [],
    nodeRows: nodes.map(n => ({ ...n, pace: '', url: base + 'node&nodeId=' + encodeURIComponent(n.id) })),
    nodePlans: [], nodeMoney: null, inspections: [], arrivals: [],
    pending: [], waiting: [], engineers: [], engineerIndex: 0, templates: [], templateIndex: 0,
    links, next,
    node: node || null, plan: plan || null,
    nodeIndex: Math.max(0, nodes.findIndex(n => n.id === (form.nodeId || (node && node.id)))),
    nodeMaterialUrl: node ? base + 'materials&nodeId=' + encodeURIComponent(node.id) : '',
    nodeFundingUrl: node ? base + 'funding&nodeId=' + encodeURIComponent(node.id) : '',
    nodeRecordUrl: node ? base + 'node&nodeId=' + encodeURIComponent(node.id) : '',
    nodeUrl: current ? base + 'node&nodeId=' + encodeURIComponent(current.id) : base + (delivery ? 'archive' : 'delivery'),
    settlementUrl: base + 'settlement',
    paceDone: nodes.filter(n => n.status === '已完成').length, paceTotal: nodes.length,
    paceOverdue: nodes.filter(n => n.status !== '已完成' && n.end && today && n.end < today).length,
    stage, stageText: p.stageText, partLabel: stage === 'base' ? '基础完成后期款'
      : stage === 'topping' ? '封顶后期款' : '签约后期款',
    own, canPay: false, finance: false, // 收款登记/采购金额为凭据类动作，随 COS 批开放
    canAdjust: own && !delivery && !!plan, canTerms: false,
    termsLocked: !!(plan && plan.payment === '已付清'),
    canCorrectNode: false, canReopen: false,
    demo: false
  };
}
