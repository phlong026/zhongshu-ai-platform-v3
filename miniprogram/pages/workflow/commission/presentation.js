/**
 * 设计委托页展示映射层（API 态）：
 * 后端 /franchise/commissions、/franchise/design-policies DTO → 页面 data 键
 * （customer/rows/fields/form/pricingQuote 及设计行政工作台视图）。
 * 按 R03 冻结的 CommissionDetail / CommissionSummary / context 映射，保留已有展示键。
 * 不含本地持久化、模拟支付与 sample-files 假文件逻辑。
 */
const Money = require('../../../utils/money');
const Status = require('../../../utils/status-labels').commission;

const money = cents => Money.money(cents, { symbol: false });
const today = () => require('../../../utils/account').localDate();

/** 视图 → API 动作（与 api/franchise/commission.js 注释的命令枚举一致；submit 走独立提交端点） */
const ACTIONS = { submit: 'commission-submit', feedback: 'commission-feedback', complete: 'design-complete', dispatch: 'design-dispatch', handover: 'packet-submit', receive: 'packet-receive' };
const FILTERS = ['全部', Status.designing, Status.modifying, Status.pay, Status.dispatch, Status.handover, Status.receive, Status.dispatched];
const FILTER_STATUSES = { [Status.designing]:'DESIGNING', [Status.modifying]:'MODIFYING', '设计与修改':'DESIGNING,MODIFYING',
  [Status.pay]:'FEE_PENDING,FEE_RETURNED', [Status.dispatch]:'DESIGN_COMPLETED', [Status.handover]:'DISPATCHED,HANDOVER_RETURNED',
  [Status.receive]:'HANDOVER_SUBMITTED', [Status.dispatched]:'ACCEPTED' };
const STATUS_ORDER = { [Status.modifying]: 0, [Status.designing]: 1, [Status.pay]: 2, [Status.dispatch]: 3, [Status.handover]: 4, [Status.receive]: 5, [Status.dispatched]: 6 };
const KNOWN_VIEWS = ['progress', 'submit', 'complete', 'dispatch', 'handover', 'receive'];

const url = (id, view = 'progress') => '/pages/workflow/commission/index?view=' + view + '&commissionId=' + encodeURIComponent(id);

/* ---- 宽容取值 ---- */
function pick(obj) {
  for (let i = 1; i < arguments.length; i++) {
    const v = obj ? obj[arguments[i]] : undefined;
    if (v !== undefined && v !== null && v !== '') return v;
  }
  return undefined;
}
/** 金额：优先 nameCents（分），回退 nameYuan（元取整为分） */
function centsOf(obj, ...names) {
  if (!obj) return null;
  for (const n of names) {
    const c = obj[n + 'Cents'];
    if (c !== undefined && c !== null && c !== '') return Number(c) || 0;
  }
  for (const n of names) {
    const y = obj[n + 'Yuan'];
    if (y !== undefined && y !== null && y !== '') return Math.round(Number(y) * 100) || 0;
  }
  return null;
}
/** 金额回填表单（元字符串）；无值返回 '' */
function formMoney(obj, ...names) {
  const cents = centsOf(obj, ...names);
  return cents == null ? '' : String(cents / 100);
}
function calendarDate(value) {
  if (Array.isArray(value) && value.length === 3) return value.map((n, i) => i ? String(n).padStart(2, '0') : String(n)).join('-');
  return typeof value === 'string' ? value.slice(0, 10) : '';
}
const recordTime = value => require('../../../utils/platform-messages').formatTime(value);

/* ---- DTO 归一化 ---- */
function normalizeQuote(raw) {
  if (!raw) return null;
  const rate = centsOf(raw, 'rate'), amount = centsOf(raw, 'amount', 'total');
  if (rate == null && amount == null && !pick(raw, 'scope')) return null;
  return {
    area: Number(pick(raw, 'area', 'buildingArea') || 0),
    rateCents: rate, amountCents: amount,
    scope: String(pick(raw, 'scope') || ''),
    recipient: String(pick(raw, 'recipient', 'payee') || '众墅之家总部'),
    pricingVersion: Number(pick(raw, 'pricingVersion', 'version') || 0)
  };
}
function normalizeFee(raw) {
  if (!raw) return null;
  return {
    mode: String(pick(raw, 'mode', 'type') || ''),
    status: String(pick(raw, 'status', 'feeStatus') || ''),
    totalCents: centsOf(raw, 'total', 'totalAmount'),
    firstCents: centsOf(raw, 'first', 'firstAmount'),
    tailCents: centsOf(raw, 'tail', 'tailAmount'),
    recipient: String(pick(raw, 'recipient', 'payee') || ''),
    detail: String(pick(raw, 'detail', 'statusText') || ''),
    quote: normalizeQuote(raw.quote || raw.areaPricing)
  };
}
function normalize(dto = {}) {
  if (dto.commission && !dto.submissions) dto = { ...dto.commission, commissionId: dto.commissionId, allowedActions: dto.allowedActions, stateText: dto.displayStatus };
  if (dto.commission && dto.submissions) {
    const head = dto.commission, s = dto.submissions.find(x => x.version === head.submissionVersion) || dto.submissions[dto.submissions.length - 1];
    const progress = (dto.progress || []).slice().sort((a, b) => Number(a.seq) - Number(b.seq)), dispatches = dto.dispatches || [];
    const dispatch = dispatches.find(d => d.dispatchSeq === head.dispatchSeq);
    const files = d => d && ({ ...d, date: recordTime(d.createTime), version: d.dispatchSeq, requestVersion: d.submissionVersion,
      resultFile: d.resultAssetId ? { name: '正式设计成果', slot: 'RESULT', dispatchSeq: d.dispatchSeq } : null,
      drawingFile: d.drawingAssetId ? { name: '本版施工图', slot: 'DRAWING', dispatchSeq: d.dispatchSeq } : null,
      reviewFile: d.reviewAssetId ? { name: '专业审核依据', slot: 'REVIEW', dispatchSeq: d.dispatchSeq } : null });
    const handovers = (dto.handovers || []).map(h => ({ ...h, confirmationDate: calendarDate(h.confirmationDate) })).sort((a, b) => Number(a.version) - Number(b.version));
    const handover = handovers.slice(-1)[0];
    const completion = progress.filter(p => p.kind === 'COMPLETE').slice(-1)[0];
    const delivery = progress.filter(p => p.kind === 'DISPATCH').slice(-1)[0];
    let confirmation = null;
    if (handover && handover.confirmationFile) {
      try { const ref = JSON.parse(handover.confirmationFile); if (ref.assetId) confirmation = { assetId: String(ref.assetId), name: ref.name || '客户确认凭据' }; } catch (e) { /* 旧文件仅显示缺失，不能把任意字符串当资产 */ }
    }
    dto = { ...head, simulatedPayment:dto.simulatedPayment||null,commissionId: dto.commissionId || head.id, id: head.customerId,
      request: s ? { ...s, schemeSnapshot:s.schemeSnapshot, attachment: s.attachmentAssetId ? { assetId: String(s.attachmentAssetId), name: '需求附件' } : null, basis: { version: s.budgetVersion, width: s.width, depth: s.depth, floors: s.floors, area: s.area } } : null,
      fee: s ? { ...s, status: dto.feeApproval ? String(dto.feeApproval.status).toLowerCase() : 'approved', tailCents: s.mode === 'AREA_FULL' ? 0 : s.totalCents - s.firstCents,
        quote: s.mode === 'AREA_FULL' ? { area: s.area, rateCents: s.rateCents, totalCents: s.totalCents, scope: s.scope, recipient: s.recipient, pricingVersion: s.pricingVersion } : null } : null,
      completed: completion ? { ...completion, date: recordTime(completion.createTime) } : null,
      dispatch: dispatch ? { ...files(dispatch), note: delivery && delivery.note || '' } : null, dispatchHistory: dispatches.filter(d => d !== dispatch).map(files),
      feedbacks: progress.filter(p => p.kind === 'FEEDBACK').map(p => ({ ...p, status: String(p.feedbackStatus || '').toLowerCase() })),
      handover: handover ? { ...handover, status: String(handover.status).toLowerCase() } : null,
      packet: handover ? { ...handover, ...files(dispatches.find(d => d.dispatchSeq === handover.dispatchSeq)), version: handover.version, dispatchVersion: handover.dispatchSeq,
        confirmationFile: confirmation ? { ...confirmation, slot: 'CONFIRMATION', dispatchSeq: handover.dispatchSeq } : null } : null,
      acceptance: handover && handover.status === 'ACCEPTED' ? handover : null,
      returns: handover && handover.status === 'RETURNED' ? [{ status: 'pending', reason: handover.decisionNote }] : [],
      handoverHistory: handovers.filter(h => h !== handover && h.status === 'RETURNED'),
      allowedActions: dto.allowedActions, stateText: dto.displayStatus, settlement: dto.settlement };
  }
  return {
    id: String(pick(dto, 'customerId', 'id') || ''),
    commissionId: String(pick(dto, 'commissionId', 'id') || ''),
    projectId: String(pick(dto, 'projectId') || ''),
    name: String(pick(dto, 'customerName', 'name') || ''),
    city: String(pick(dto, 'city') || ''),
    company: String(pick(dto, 'companyName', 'company') || ''),
    owner: String(pick(dto, 'owner', 'ownerName') || ''),
    revision: Number(pick(dto, 'revision') || 0),
    status: dto.status || '',
    settlement: dto.settlement || null,
    request: dto.request || null,
    fee: normalizeFee(dto.fee || dto.feeInfo || dto.cost),
    dispatch: dto.dispatch || null,
    completed: dto.completed || null,
    feedbacks: dto.feedbacks || [],
    dispatchHistory: dto.dispatchHistory || [],
    packet: dto.packet || null,
    handover: dto.handover || null,
    handoverHistory: dto.handoverHistory || [],
    returns: (dto.returns || []).filter(r => r && r.status === 'pending'),
    acceptance: dto.acceptance || null,
    events: dto.events || [],
    simulatedPayment:dto.simulatedPayment||null,
    allowedActions: (dto.allowedActions || []).map(String),
    stateText: String(pick(dto, 'stateText', 'statusText') || ''),
    nextView: String(pick(dto, 'nextView') || ''),
    nextOwner: String(pick(dto, 'nextOwner', 'nextOwnerName') || '')
  };
}

/* ---- 状态推导（优先后端显式提示，缺省按归一化状态推导） ---- */
function derive(c) {
  if(c.simulatedPayment&&c.simulatedPayment.status==='PENDING')return{state:'历史模拟记录待核对',owner:'财务',view:'progress',actionable:false};
  const summary = { FEE_PENDING: ['费用待老板审批', '门店老板', 'progress'], FEE_RETURNED: ['委托费用待修改', '业务', 'submit'], DESIGNING: [Status.designing, '总部设计行政', 'complete'], DESIGN_COMPLETED: [Status.dispatch, '总部设计行政', 'dispatch'], DISPATCHED: [Status.handover, '业务', 'handover'], HANDOVER_SUBMITTED: [Status.receive, '工程', 'receive'], HANDOVER_RETURNED: [Status.handover, '业务', 'handover'], ACCEPTED: ['业务移交完成', '已完成', 'progress'] }[c.status];
  if (summary) return { state: summary[0], owner: summary[1], view: summary[2], actionable: c.allowedActions.includes(actionFor(summary[2])) };
  const fb = c.feedbacks[c.feedbacks.length - 1];
  if (c.acceptance) return { state: '业务移交完成', owner: '已完成', view: 'progress', actionable: false };
  if (c.handover && c.handover.status === 'returned') return { state: Status.handover, owner: '经办业务', view: 'handover', actionable: true, roleState: '移交资料待补充' };
  if (c.handover) return { state: Status.receive, owner: String(pick(c.handover, 'manager', 'managerName')) || '工程负责人', view: 'receive', actionable: true, roleState: '资料待接收' };
  if (c.fee && c.fee.status === 'returned') return { state: '委托费用待修改', owner: '经办业务', view: 'submit', actionable: true };
  if (!c.request) return { state: Status.submit, owner: '经办业务', view: 'submit', actionable: true };
  if (c.fee && ['pending', 'pending-approval', 'pending_approval', 'approving'].includes(c.fee.status)) return { state: '费用待老板审批', owner: '加盟商老板', view: 'progress', actionable: false, detail: '委托费用待加盟商老板审批' };
  if (fb && fb.status === 'pending') return { state: Status.modifying, owner: '总部设计', view: 'complete', actionable: true, roleState: '客户新增修改意见 · 待继续设计' };
  if (!c.completed) return { state: Status.designing, owner: '总部设计', view: 'complete', actionable: true, roleState: '待登记设计完成' };
  if (!c.dispatch) return { state: Status.dispatch, owner: '总部设计', view: 'dispatch', actionable: true, roleState: '待正式成果下发' };
  return { state: Status.handover, owner: '经办业务', view: 'handover', actionable: true, roleState: '已下发 · 待收图并移交' };
}
function stateOf(c) {
  const n = derive(c);
  return { ...n, view: KNOWN_VIEWS.includes(c.nextView) ? c.nextView : n.view, state: c.stateText || n.state, owner: c.nextOwner || n.owner };
}

/** 视图 → API 动作名 */
const actionFor = view => ACTIONS[view] || '';

/** 按服务端动作清单显示，空清单必须关闭办理入口。 */
function canDo(c, view) {
  return !!actionFor(view) && c.allowedActions.indexOf(actionFor(view)) >= 0;
}

/** 工作台项目状态（设计行政七态口径） */
function statusOf(c) {
  const status = { FEE_PENDING: Status.pay, FEE_RETURNED: Status.pay, DESIGNING: Status.designing, DESIGN_COMPLETED: Status.dispatch, DISPATCHED: Status.handover, HANDOVER_SUBMITTED: Status.receive, HANDOVER_RETURNED: Status.handover, ACCEPTED: Status.dispatched }[c.status];
  if (status) return status;
  const fb = c.feedbacks[c.feedbacks.length - 1];
  if (c.acceptance) return Status.dispatched;
  if (c.handover) return c.handover.status === 'returned' ? Status.handover : Status.receive;
  if (!c.request || (c.fee && (['pending', 'pending-approval', 'pending_approval', 'approving', 'returned'].includes(c.fee.status)))) return Status.pay;
  if (fb && fb.status === 'pending') return Status.modifying;
  if (!c.completed) return Status.designing;
  if (!c.dispatch) return Status.dispatch;
  return Status.handover;
}

/* ---- 展示行 ---- */
function feeNotice(c) {
  if(c.simulatedPayment)return '历史模拟记录不计实际收支；实际付款由财务登记流水和凭证';
  if (!c.request) return '提交委托后费用按授权规则执行；到账登记由财务在财务工作台办理';
  if (c.fee && ['pending', 'pending-approval', 'pending_approval', 'approving'].includes(c.fee.status)) return '委托费用待加盟商老板审批';
  if (c.fee && c.fee.status === 'returned') return '委托费用待修改后重新提交';
  return '费用按授权规则执行；到账登记由财务在财务工作台办理';
}
function packageRows(c) {
  const p = c.packet;
  if (!p) return [];
  return [['正式设计成果', 'resultFile'], ['本版施工图', 'drawingFile'], ['线下专业审核依据', 'reviewFile'], ['客户确认凭据', 'confirmationFile']]
    .map(pair => ({ key: pair[1], label: pair[0], file: p[pair[1]] || null }))
    .filter(r => r.file);
}
function rowsFor(c, n) {
  const fb = c.feedbacks[c.feedbacks.length - 1];
  const rows = [{ label: '当前进度', value: n.state }, { label: '当前办理', value: n.owner }];
  const basis = c.request && c.request.basis;
  rows.push({ label: '交付范围', value: (c.request && c.request.scope) || '随委托确认' });
  if (n.detail) rows.push({ label: '办理说明', value: n.detail });
  rows.push({ label: '费用执行', value: feeNotice(c) });
  const fee = c.fee;
  if (fee && fee.totalCents != null) {
    rows.push({ label: '设计服务总额', value: '¥ ' + money(fee.totalCents) });
    if (fee.mode === 'LEGACY_SPLIT' && fee.firstCents != null) rows.push({ label: '首款', value: '¥ ' + money(fee.firstCents) });
    if (fee.mode === 'LEGACY_SPLIT' && fee.tailCents != null) rows.push({ label: '尾款', value: '¥ ' + money(fee.tailCents) });
    if (fee.recipient) rows.push({ label: c.simulatedPayment?'设计服务主体':'总部实际收款方', value: fee.recipient });
    if (fee.detail) rows.push({ label: '到账进度', value: fee.detail });
  }
  if (c.settlement) (c.settlement.stages || []).forEach(stage => rows.push({ label: stage.stage === 'FULL' ? '一次性设计费到账' : stage.stage === 'FIRST' ? '首款到账' : '尾款到账', value: '应付 ¥' + money(stage.expectedCents) + ' · 门店净付 ¥' + money(stage.storePaidNetCents) + ' · 平台净收 ¥' + money(stage.headquartersNetCents) }));
  if (c.request) {
    rows.push({ label: '设计需求', value: c.request.text });
    if (basis) {
      const dimensions = [basis.version ? '预算 V' + basis.version : '',
        basis.width && basis.depth ? basis.width + 'm × ' + basis.depth + 'm' : '',
        basis.floors ? basis.floors + '层' : '', basis.area ? basis.area + '㎡' : ''].filter(Boolean);
      if (dimensions.length) rows.push({ label: '尺寸依据', value: dimensions.join(' · ') });
    }
    const att = c.request.attachment;
    if (att) rows.push({ label: '需求附件', value: att.name, file: att });
  }
  if (c.completed && (c.completed.date || c.completed.by)) rows.push({ label: '设计完成', value: (c.completed.date || '') + (c.completed.by ? ' · ' + c.completed.by : '') });
  const dispatch = c.dispatch;
  if (dispatch) {
    if (dispatch.date || dispatch.note) rows.push({ label: '交付记录', value: (dispatch.date || '') + (dispatch.note ? ' · ' + dispatch.note : '') });
    [['正式设计成果', 'resultFile'], ['本版施工图', 'drawingFile'], ['线下审核依据', 'reviewFile']].forEach(pair => {
      const f = dispatch[pair[1]];
      if (f && !(c.packet && c.packet.dispatchVersion === dispatch.version)) rows.push({ label: pair[0], value: f.name, file: f });
    });
    rows.push({ label: '确认下发版本', value: '第 ' + dispatch.version + ' 次下发 · ' + (dispatch.drawingVersion || '线下成果') + '（冻结需求 V' + dispatch.requestVersion + '）' });
    if (c.request && dispatch.requestVersion != null && c.request.version > dispatch.requestVersion) rows.push({ label: '版本提示', value: '客户需求已更新至 V' + c.request.version + '，未经正式确认不能替换已下发的 ' + (dispatch.drawingVersion || '线下成果') });
  }
  c.dispatchHistory.forEach(h => {
    rows.push({ label:'历史下发', value:'第 ' + h.version + ' 次 · ' + (h.drawingVersion || '线下成果') + ' · 历史版本仅供追溯' });
    [['设计成果','resultFile'],['施工图','drawingFile'],['审核依据','reviewFile']].forEach(([label,key]) => {
      if(h[key]) rows.push({label:'历史第 ' + h.version + ' 次 · ' + label,value:h[key].name,file:h[key]});
    });
  });
  if (fb && fb.note) rows.push({ label: '本次修改意见', value: fb.note });
  if (c.packet) {
    packageRows(c).forEach(r => rows.push({ label: r.label, value: r.file.name, file: r.file }));
    rows.push({ label: '移交包版本', value: '第 ' + c.packet.version + ' 包 · 冻结第 ' + c.packet.dispatchVersion + ' 次下发 · ' + c.packet.drawingVersion });
  }
  c.handoverHistory.forEach(h => rows.push({ label: '历史移交包', value: '第 ' + h.version + ' 包 · ' + (h.drawingVersion || '') + ' · 已退回补件' }));
  if (c.handover && c.handover.note) rows.push({ label: '电子交底', value: c.handover.note });
  c.returns.forEach(r => rows.splice(2, 0, { label: '待补充', value: r.reason }));
  return rows;
}

/* ---- 表单 ---- */
function formInit(c, view) {
  const requestVersion = (c.request && c.request.version) || 0;
  const version = 'V' + (requestVersion + c.feedbacks.length + 1);
  if (view === 'submit') {
    const basis = c.request && c.request.basis;
    const form = {
      text: (c.request && c.request.text) || '',
      scope: (c.request && c.request.scope) || (c.fee && c.fee.quote && c.fee.quote.scope) || '',
      attachment: (c.request && c.request.attachment) || null,
      checked: false
    };
    if (basis) form.__budgetVersion = basis.version;
    if (c.fee && c.fee.quote) form.__pricingVersion = c.fee.quote.pricingVersion;
    form.__mode = c.fee && c.fee.mode;
    if (c.fee && c.fee.mode === 'LEGACY_SPLIT') {
      form.total = String(c.fee.totalCents / 100);
      form.first = String(c.fee.firstCents / 100);
      form.recipient = c.fee.recipient || '';
    }
    return form;
  }
  if (view === 'complete') return { note: '', drawingVersion: version, recipient: c.owner, checked: false };
  if (view === 'feedback') return { note: '', attachment: null };
  if (view === 'dispatch') return { recipient: c.owner, drawingVersion: version, note: '', checked: false };
  if (view === 'handover') {
    const prior = c.packet && c.dispatch && c.packet.dispatchVersion === c.dispatch.version ? c.packet : null;
    const p = prior || c.dispatch || {};
    return {
      resultFile: p.resultFile || null,
      drawingFile: p.drawingFile || null,
      reviewFile: p.reviewFile || null,
      drawingVersion: p.drawingVersion || '',
      confirmationFile: (prior && prior.confirmationFile) || null,
      confirmationDate: (prior && prior.confirmationDate) || today(),
      note: (prior && prior.note) || '',
      received: false,
      confirmed: false,
      dispatchVersion: c.dispatch && c.dispatch.version,
      managerUserId: (prior && prior.managerUserId) ? String(prior.managerUserId) : '',
      __fromDispatch: !c.packet && !!c.dispatch
    };
  }
  if (view === 'receive') return { note: '', checked: false };
  return {};
}
function fieldsFor(c, view, can) {
  const fields = [];
  const add = (key, label, kind = 'input', locked = false) => fields.push({ key, label, kind, locked });
  const hasSplit = !!(c.fee && c.fee.mode === 'LEGACY_SPLIT');
  if (view === 'submit' && can) {
    add('text', '客户设计需求', 'textarea');
    if (hasSplit) {
      add('scope', '本次交付范围', 'textarea');
      add('total', '设计服务总额（元）', 'money');
      add('first', '首款金额（元，余款为尾款）', 'money');
      add('recipient', '总部实际收款方');
    }
    add('attachment', '需求附件（选填）', 'file');
    add('checked', hasSplit ? '已核对需求、尺寸来源、范围及首尾款约定' : '已核对需求、尺寸来源、交付范围及费用授权规则', 'check');
  }
  if (view === 'complete' && can) { add('note', '线下完成说明（选填）', 'textarea'); add('checked', '本次设计及内部专业审核已完成', 'check'); }
  if (view === 'feedback' && can) add('note', '需线下沟通的修改意见', 'textarea');
  if (view === 'dispatch' && can) {
    add('recipient', '实际成果接收人');
    add('drawingVersion', '本次施工图版本');
    add('resultFile', '正式设计成果', 'file');
    add('drawingFile', '本版施工图', 'file');
    add('reviewFile', '对应版本专业审核依据', 'file');
    add('note', '实际交付清单、渠道及说明', 'textarea');
    add('checked', '本次正式成果已经实际下发', 'check');
  }
  if (view === 'handover' && can) {
    const locked = true;
    add('resultFile', '正式设计成果', 'file', locked);
    add('drawingFile', '本版施工图', 'file', locked);
    add('drawingVersion', '本次施工图版本', 'input', locked);
    add('reviewFile', '对应版本的线下专业审核依据', 'file', locked);
    add('confirmationFile', '客户对本版成果的确认凭据', 'file');
    add('confirmationDate', '客户确认日期', 'date');
    add('managerUserId', '指定工程接收人', 'manager');
    add('note', '现场重点与电子交底', 'textarea');
    add('received', '正式文件已收齐且可读取', 'check');
    add('confirmed', '客户已确认本次采用的成果版本', 'check');
  }
  if (view === 'receive' && can) { add('note', '退回原因（退回时必填）', 'textarea'); add('checked', '本版图纸、客户确认、审核依据及交底已核对', 'check'); }
  return fields;
}

/** 自动计价卡片（服务端费用直通报价） */
function quoteOf(c) {
  const q = c.fee && c.fee.quote;
  if (!q) return null;
  return { area: q.area, rateText: money(q.rateCents), amountText: money(q.amountCents), scope: q.scope, recipient: q.recipient };
}

/* ---- 设计行政工作台（commissions.page 列表 → 首页/项目视图） ---- */
function statusDetail(status, n) {
  if (status === Status.modifying) return '客户新增修改意见 · 待继续设计';
  if (status === Status.designing) return '核对需求与资料 · 待登记设计完成';
  if (status === Status.pay) return (n.detail || n.roleState || n.state) + ' · 下一位：' + n.owner;
  if (status === Status.dispatch) return '设计完成 · 登记正式成果下发';
  if (status === Status.handover) return '正式成果已下发 · 待经办业务移交';
  if (status === Status.receive) return '业务已移交 · 待工程负责人接收';
  return '正式成果与移交记录可查';
}
function actionLabel(status) {
  if (status === Status.modifying) return '处理修改';
  if (status === Status.designing) return '查看委托';
  if (status === Status.dispatch) return '上传并下发';
  if (status === Status.dispatched) return '查看记录';
  return '查看进度';
}
function latestEvent(c, actions) {
  return (c.events || [])
    .filter(e => actions.indexOf(String(e && e.action)) >= 0)
    .sort((a, b) => String(b.at || '').localeCompare(String(a.at || '')))[0] || null;
}
function eventTime(at) {
  const date = new Date(at);
  if (Number.isNaN(date.getTime())) return '';
  return String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0') + ' ' + String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0');
}
function recentRows(list) {
  if (!Array.isArray(list)) throw Error('办理记录加载失败');
  return list.map(row => {
    if (typeof row?.commissionId !== 'string' || !/^\d+$/.test(row.commissionId)
      || typeof row.customerName !== 'string' || typeof row.handledAt !== 'string'
      || !['COMPLETE','DISPATCH'].includes(row.kind) || !eventTime(row.handledAt)) throw Error('办理记录加载失败');
    return { id:row.commissionId, name:row.customerName, initial:String(row.customerName || '项').slice(0,1),
      url:url(row.commissionId,'progress'), recentWhen:eventTime(row.handledAt),
      recentText:row.kind === 'DISPATCH' ? '成果已下发' : '已登记设计完成' };
  });
}
function workbench(list, statusCounts) {
  const rows = list.filter(c => c.commissionId).map(c => {
    const n = stateOf(c), status = statusOf(c);
    const actionable = !!(n.actionable && ['complete', 'dispatch'].indexOf(n.view) >= 0);
    const event = latestEvent(c, ['design-complete', 'design-dispatch']);
    return {
      id: c.id,
      projectId: c.projectId || c.id,
      name: c.name,
      initial: String(c.name || '项').slice(0, 1),
      city: c.city,
      company: c.company || '',
      owner: c.owner || '',
      status,
      tone: status === Status.dispatched || status === Status.dispatch ? 'done' : [Status.pay, Status.handover, Status.receive].indexOf(status) >= 0 ? 'waiting' : '',
      state: n.state,
      detail: statusDetail(status, n),
      nextOwner: n.owner,
      url: url(c.commissionId, 'progress'),
      actionUrl: url(c.commissionId, actionable ? n.view : 'progress'),
      action: actionLabel(status),
      actionable,
      recentAt: (event && event.at) || '',
      recentWhen: event ? eventTime(event.at) : '',
      recentText: event ? (event.action === 'design-dispatch' ? '成果已下发' : '已登记设计完成') : ''
    };
  }).sort((a, b) => (STATUS_ORDER[a.status] || 0) - (STATUS_ORDER[b.status] || 0) || String(a.name).localeCompare(String(b.name), 'zh-CN'));
  const counts = {};
  FILTERS.forEach(label => { counts[label] = label === '全部' ? rows.length : rows.filter(r => r.status === label).length; });
  if (statusCounts) FILTERS.forEach(label => {
    counts[label] = (label === '全部' ? Object.keys(statusCounts) : FILTER_STATUSES[label].split(','))
      .reduce((sum, status) => sum + Number(statusCounts[status] || 0), 0);
  });
  const byRecency = rows.slice().sort((a, b) => String(b.recentAt || '').localeCompare(String(a.recentAt || '')));
  const actionable = byRecency.filter(r => r.actionable);
  const tasks = actionable.map((r, index) => ({ ...r, url: r.actionUrl, primary: index === 0 }));
  const recent = byRecency.filter(r => r.recentAt).slice(0, 5);
  const metrics = [
    { label: '设计与修改', value: counts[Status.designing] + counts[Status.modifying], filter: '设计与修改', icon: '/assets/home-design-icon.png' },
    { label: Status.pay, value: counts[Status.pay], filter: Status.pay, icon: '/assets/money.png' },
    { label: Status.dispatch, value: counts[Status.dispatch], filter: Status.dispatch, icon: '/assets/contract.png' },
    { label: '全部项目', value: counts['全部'], filter: '全部', icon: '/assets/folder.png' }
  ];
  return { rows, counts, tasks, taskCount: actionable.length, recent, metrics, filters: FILTERS.map(label => ({ label, count: counts[label] })) };
}
function paginate(list, keyword = '', filter = '全部', limit = 20) {
  const query = String(keyword || '').trim().toLowerCase();
  const filtered = (list || []).filter(c => c.commissionId).filter(c => {
    const status = statusOf(c), n = stateOf(c);
    const statusOk = filter === '全部' || (filter === '设计与修改' ? [Status.designing, Status.modifying].indexOf(status) >= 0 : status === filter);
    if (!statusOk) return false;
    if (!query) return true;
    return [c.name, c.projectId, c.company, c.owner, c.city].join(' ').toLowerCase().includes(query);
  }).map(c => {
    const n = stateOf(c), status = statusOf(c);
    const actionable = !!(n.actionable && ['complete', 'dispatch'].indexOf(n.view) >= 0);
    return {
      id: c.id, name: c.name, city: c.city, status, company: c.company || '', owner: c.owner || '',
      tone: status === Status.dispatched || status === Status.dispatch ? 'done' : [Status.pay, Status.handover, Status.receive].indexOf(status) >= 0 ? 'waiting' : '',
      detail: statusDetail(status, n),
      url: url(c.commissionId, actionable ? n.view : 'progress')
    };
  });
  const size = Math.max(20, Number(limit) || 20);
  return { rows: filtered.slice(0, size), total: filtered.length, hasMore: filtered.length > size };
}
function dateLabel(date = today()) {
  const value = new Date(date + 'T00:00:00');
  return (value.getMonth() + 1) + '月' + value.getDate() + '日 周' + '日一二三四五六'[value.getDay()];
}
function errorMessage(err) {
  return (err && (err.message || err.msg)) || '网络异常，请稍后重试';
}

module.exports = { ACTIONS, FILTERS, FILTER_STATUSES, url, normalize, stateOf, actionFor, canDo, statusOf, rowsFor, packageRows, formInit, fieldsFor, quoteOf, feeNotice, formMoney, workbench, recentRows, paginate, dateLabel, errorMessage };
