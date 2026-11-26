const api = require('./api');
const { money } = require('../../utils/money');
const SECTIONS = [{ key: 'overview', label: '概览' }, { key: 'customers', label: '客户' }, { key: 'projects', label: '工程' }, { key: 'books', label: '账本' }, { key: 'finance', label: '财务' }];
const FINANCE = [{ key: 'receipts', label: '收款' }, { key: 'payables', label: '应付' }, { key: 'payments', label: '付款' }, { key: 'refunds', label: '退款' }];
const STEP_NAMES = ['邀约进店', '店面讲解', '户型选择', '带看工地', '现场测量', '别墅报价', '合同签约', '开工大吉'];
const STATUS = { DRAFT: '草稿', PENDING: '待处理', NOT_STARTED: '未开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', COMPLETE: '已完成',
  SIGNED: '已签约', ACTIVE: '进行中', OPEN: '进行中', CLOSED: '已关闭', VOID: '已作废', VOIDED: '已作废', CANCELLED: '已取消',
  PAID: '已付清', UNPAID: '未付款', PARTIAL: '部分完成', PARTIALLY_PAID: '部分付款', RECEIVED: '已收款', CONFIRMED: '已确认',
  APPROVED: '已通过', REJECTED: '已退回', ARCHIVED: '已归档', DELIVERED: '已交付', SETTLED: '已结算', NEW: '新客户', FOLLOWING: '跟进中', LOST: '已流失' };
const SITE_OBSERVATIONS = { orientation: '实测朝向', siteElevationNote: '影响设计的现场高差或说明' };
const FIELD_NAMES = {
  contactMethod: '联系渠道', invitationResult: '邀约结果', appointmentAt: '预约到店时间', nextContactOn: '下次联系日期',
  receptionist: '接待人员', requirements: '建房需求', feedback: '客户反馈', referenceCaseId: '参考户型编号', referenceCaseName: '参考户型',
  selectionMode: '选型方式', customDirection: '定制方向', selectionResult: '户型选择说明',
  versionLabel: '方案 / 报价版本', confirmed: '客户已确认', siteName: '参观工地', address: '地址',
  measurer: '测量人员', width: '开间（米）', depth: '进深（米）', area: '面积（平方米）', floors: '层数', ...SITE_OBSERVATIONS, checked: '已复核测量',
  amountCents: '报价金额', scope: '报价范围', communicated: '已向客户讲解', result: '报价沟通结果',
  contractId: '合同编号', signedDate: '签约日期', totalCents: '合同金额', projectId: '工程编号', actualStartAt: '实际开工日期',
  managerUserId: '工程负责人编号', status: '来源状态', sourceRevision: '来源版本'
};
const FINANCE_FIELDS = { name: '名称', status: '状态', date: '业务日期', amountCents: '金额', dueDate: '到期日期',
  counterparty: '往来单位 / 人员', category: '类别', sourceType: '来源', fundingType: '资金来源', accountName: '账户',
  transactionNo: '交易流水号', refundDirection: '退款方向', projectId: '工程编号', customerId: '客户编号', payableId: '应付编号',
  receiptId: '原收款编号', paymentId: '原付款编号', replacesId: '更正的原单', replacedById: '后续更正单', createTime: '登记时间' };
const ENUM = { CONFIRMED: '确认到店', PENDING: '待确认', DECLINED: '暂不进店', CONTRACT: '合同', ENGINEERING: '工程',
  CUSTOMER: '客户退款', EXPENSE: '支出退回', COMPANY: '公司资金', PERSONAL: '个人垫付', ADVANCE: '垫付款', EXTERNAL_CUSTOM: '外部定制' };
const text = value => value == null || value === '' ? '—' : ['string', 'number', 'boolean'].includes(typeof value) ? String(value) : '—';
const status = value => STATUS[value] || text(value);
const date = value => text(value).replace('T', ' ').replace(/\.\d+(Z)?$/, '$1');
const brand = row => row.brandState === 'AMBIGUOUS' ? '品牌归属待核对' : row.brandState === 'UNASSIGNED' ? '未关联品牌' : text(row.brandName);
const title = kind => SECTIONS.concat(FINANCE).find(item => item.key === kind)?.label || '总部详情';
function fields(row, names) {
  return Object.entries(names).filter(([key]) => row[key] !== null && row[key] !== undefined && row[key] !== '').map(([key, label]) => ({
    label, value: key.endsWith('Cents') ? money(row[key]) : typeof row[key] === 'boolean' ? row[key] ? '是' : '否'
      : key === 'customDirection' || SITE_OBSERVATIONS[key] ? text(row[key]) : key === 'status' ? status(row[key]) : ENUM[row[key]] || date(row[key])
  }));
}
const measurementObservations = row => fields(row, SITE_OBSERVATIONS);
function context(value) {
  if (!Array.isArray(value.brands) || !Array.isArray(value.companies)) throw Error('总部筛选数据格式不完整');
  return {
    brands: value.brands.map(row => ({ id: api.id(row.id), name: text(row.name) })),
    companies: value.companies.map(row => ({ id: api.id(row.id), name: text(row.name), orgType: row.orgType, brandId: row.brandId == null ? '' : api.id(row.brandId),
      brandState: row.brandState, brandName: brand(row) }))
  };
}
function page(value, expectedPage, readonly = true) {
  if (!value || !Array.isArray(value.list) || !Number.isSafeInteger(value.total) || value.total < 0
      || readonly && (value.readOnly !== true || value.pageNo !== expectedPage)) throw Error('分页数据不完整，请刷新重试');
  return value;
}
function card(kind, row) {
  const result = { id: api.id(row.id), name: text(row.name), companyId: api.id(row.companyId), companyName: text(row.companyName),
    brandName: brand(row), status: status(row.status), amount: '', lines: [] };
  if (kind === 'customers') {
    result.lines = [row.phone && '电话 ' + row.phone, row.ownerName && '跟进人 ' + row.ownerName, row.nextVisitDate && '下次联系 ' + date(row.nextVisitDate)].filter(Boolean);
    const workflow = row.workflow || {};
    result.progress = Number.isFinite(workflow.progressPercent) ? Math.max(0, Math.min(100, workflow.progressPercent)) : null;
    result.step = workflow.currentStepName || STEP_NAMES[api.STEPS.indexOf(workflow.currentStepCode)] || '八步记录待建立';
  } else if (kind === 'projects') {
    result.amount = money(row.contractTotalCents, { na: '合同金额待完善' });
    result.lines = [row.customerName && '客户 ' + row.customerName, row.managerName && '负责人 ' + row.managerName, row.actualStartAt && '开工 ' + date(row.actualStartAt)].filter(Boolean);
  } else {
    result.amount = money(row.amountCents); result.lines = [row.date && date(row.date), row.counterparty, row.dueDate && '到期 ' + row.dueDate].filter(Boolean);
  }
  return result;
}
function workflowFiles(customerId, step, attachments, register) {
  if (!api.STEPS.includes(step)) return [];
  return (Array.isArray(attachments) ? attachments : []).map(file => register({ type: 'workflow', id: customerId, step, assetId: api.id(file.assetId) }, file.title || '节点附件'));
}
function customer(row, register) {
  if (row.readOnly !== true) throw Error('当前客户不是总部只读视图');
  const id = api.id(row.id), workflow = row.workflow;
  if (workflow && (api.id(workflow.customerId) !== id || api.id(workflow.companyId) !== api.id(row.companyId) || workflow.canWrite !== false
      || !Array.isArray(workflow.steps) || workflow.steps.some(step => step.canWrite || step.canComplete || step.canReopen))) throw Error('客户步骤权限格式不正确');
  return {
    id, companyId: api.id(row.companyId), name: text(row.name), companyName: text(row.companyName), brandName: brand(row), status: status(row.status),
    fields: fields(row, { phone: '电话', wechat: '微信', ownerName: '跟进人员', source: '客户来源', nextVisitDate: '下次联系', lastFollowAt: '最近跟进', summary: '客户需求' }).concat([
      { label: '建房地址', value: [row.province, row.city, row.district, row.town, row.village, row.addressDetail].filter(Boolean).join(' ') || '—' }
    ]),
    progress: workflow ? Number(workflow.progressPercent) || 0 : 0,
    completed: workflow ? workflow.completedCount : 0,
    steps: (workflow?.steps || []).map(step => ({
      code: step.code, number: String(step.order).padStart(2, '0'), name: text(step.name), status: status(step.status), completed: step.status === 'COMPLETED',
      source: step.source === 'CONTRACT' ? '以已签合同为准' : step.source === 'ENGINEERING' ? '以工程实际开工记录为准' : '业务跟进记录',
      occurredOn: step.occurredOn || '', note: step.note || '', fields: fields(step.fields || {}, FIELD_NAMES),
      files: workflowFiles(id, step.code, step.attachments, register).concat(step.code === 'CONTRACT' && step.fields?.assetId
        ? [register({ type: 'workflow', id, step: 'CONTRACT', assetId: api.id(step.fields.assetId) }, '已签合同附件')] : [])
    }))
  };
}
function records(rows, customerId, register) {
  const types = { SAVE: '保存记录', COMPLETE: '完成步骤', REOPEN: '重新打开', STATUS: '更新跟进状态',
    SAVED: '保存记录', COMPLETED: '完成步骤', REOPENED: '重新打开', SOURCE_UPDATED: '来源更新', CREATED: '建立流程' };
  return rows.map(row => ({ id: api.id(row.id), step: STEP_NAMES[api.STEPS.indexOf(row.stepCode)] || text(row.stepCode), type: types[row.type] || text(row.type),
    date: date(row.createdAt), occurredOn: row.occurredOn || '', actor: text(row.actorName), note: row.note || '', fields: fields(row.fields || {}, FIELD_NAMES),
    files: workflowFiles(customerId, row.stepCode, row.attachments, register) }));
}
function documents(rows, projectId, register) {
  return rows.map(row => ({ id: api.id(row.id), name: text(row.name), kind: text(row.kind), version: text(row.version), date: date(row.createTime),
    file: row.downloadAvailable === true ? register({ type: 'document', id: projectId, documentId: api.id(row.id) }, row.name || '工程资料') : null }));
}
function siteRecords(rows, projectId, register) {
  return rows.map(row => ({ id: api.id(row.id), date: date(row.occurredAt), note: text(row.note), recordType: row.completion === true ? '节点验收记录' : row.completion === false ? '现场进度记录' : '', result: status(row.result),
    files: (row.assetIds || []).map((assetId, index) => register({ type: 'project', id: projectId, section: 'SITE_RECORD', assetId: api.id(assetId) }, '现场附件 ' + (index + 1))) }));
}
function project(row, register) {
  if (row.readOnly !== true) throw Error('当前工程不是总部只读视图');
  const id = api.id(row.id), contract = row.saleContract || {}, closure = row.closure || {};
  const files = [];
  const contractAsset = row.contractAssetId || contract.assetId;
  if (contractAsset) files.push(register({ type: 'project', id, section: 'CONTRACT', assetId: api.id(contractAsset) }, '工程合同'));
  for (const [key, section, label] of [['ownerAssetId', 'CLOSURE_OWNER', '业主签收资料'], ['fileAssetId', 'CLOSURE_FILE', '竣工交付资料'], ['settleAssetId', 'CLOSURE_SETTLEMENT', '工程结算资料']]) {
    if (closure[key]) files.push(register({ type: 'project', id, section, assetId: api.id(closure[key]) }, label));
  }
  return { id, name: text(row.name), companyId: api.id(row.companyId), companyName: text(row.companyName), brandName: brand(row), status: status(row.status),
    fields: fields(row, { customerName: '客户', phone: '联系电话', managerName: '工程负责人', actualStartAt: '实际开工', city: '地区', area: '面积（平方米）', floors: '层数', contractTotalCents: '工程合同金额' }),
    contractFields: fields(contract, { id: '合同编号', status: '合同状态', signedDate: '签约日期', totalCents: '签约金额', paymentPlanVersion: '付款方案版本' }),
    nodes: (row.nodes || []).map(node => ({ id: api.id(node.id), name: text(node.name), status: status(node.status), start: date(node.startDate), end: date(node.completedAt || node.endDate) })),
    stages: (row.paymentStages || []).map((stage, index) => ({ id: api.id(stage.stageId), name: stage.label || ({ first: '签约款', foundation: '基础完工款', topping: '封顶款' }[stage.stage]) || '第 ' + (stage.sequence || index + 1) + ' 期', amount: money(stage.amountCents),
      received: money(stage.collectedCents), refunded: money(stage.refundedCents), balance: money(stage.balanceCents), dueDate: stage.dueDate || '',
      ratio: stage.ratioBps == null ? '' : (Number(stage.ratioBps) / 100) + '%',
      trigger: stage.trigger === 'NODE_COMPLETED' ? '工程节点 ' + stage.nodeIndex + ' 完成' : ({ ON_SIGN: '合同签约', BEFORE_START: '开工前', ON_FINISH: '工程完工', DATE: '约定日期', MANUAL: '手动确认', LEGACY_BASE_DONE: '基础完工', LEGACY_TOPPING: '封顶' }[stage.trigger]) || stage.trigger || '' })),
    closureFields: fields(closure, { deliveredAt: '交付日期', recipient: '签收人', deliverNote: '交付说明', settledAt: '结算日期', settleNote: '结算说明', archivedAt: '归档时间' }), files
  };
}
function finance(kind, row, register) {
  if (row.readOnly !== true) throw Error('当前财务记录不是总部只读视图');
  return { id: api.id(row.id), name: text(row.name), companyId: api.id(row.companyId), companyName: text(row.companyName), brandName: brand(row), status: status(row.status),
    fields: fields(row, FINANCE_FIELDS), files: kind !== 'payables' && row.assetId ? [register({ type: 'finance', kind, id: api.id(row.id), assetId: api.id(row.assetId) }, '登记凭证')] : [] };
}
function books(rows) {
  if (!Array.isArray(rows)) throw Error('账本列表格式不正确');
  return rows.map(row => ({ scope: api.bookScope(row.scope), name: text(row.name), status: status(row.status), type: row.scope === 'company' ? '公司总账' : row.scope.startsWith('project:') ? '项目账本' : '前期账本' }));
}
function accounting(value,scope) {
  if (!value) return null;
  if (value.canConfirm !== false || value.basisCode !== 'CONTRACT_CONFIRMED_COST_V1'
      || !['UNCONFIRMED','CURRENT','STALE'].includes(value.state) || !api.id(value.projectId)
      || String(scope).startsWith('project:') && api.id(value.projectId) !== String(scope).slice(8)) throw Error('总部项目核算响应不匹配');
  const exact = cents => {
    if (cents == null || typeof cents === 'number' && !Number.isSafeInteger(cents) || !/^-?\d{1,19}$/.test(String(cents))) return '—';
    const digits=String(cents).replace(/^-/, '').padStart(3,'0');
    return '¥ '+(String(cents).startsWith('-')?'-':'')+digits.slice(0,-2).replace(/\B(?=(\d{3})+(?!\d))/g,',')+'.'+digits.slice(-2);
  };
  const latest=value.latestSnapshot;
  if(latest && (api.id(latest.snapshot?.projectId)!==api.id(value.projectId) || latest.snapshot.sourceHash!==latest.sourceHash)) throw Error('核算确认记录不匹配');
  return { profit:exact(value.grossProfitCents),cash:exact(value.cashNetCents),cost:exact(value.confirmedCostCents),basis:text(value.basisLabel),
    status:{UNCONFIRMED:'尚未确认',CURRENT:'已确认',STALE:'账目有变化，待复核'}[value.state],
    latest:latest?{version:latest.version,date:date(latest.confirmedAt),note:text(latest.note),profit:exact(latest.snapshot.grossProfitCents),
      cash:exact(latest.snapshot.cashNetCents),outstanding:exact(latest.snapshot.payableOutstandingCents)}:null };
}
function ledger(value,scope) {
  if (!value || !value.summary || !Array.isArray(value.entries)) throw Error('账本数据不完整');
  const names = { received_cents: '已收款', paid_cents: '已付款', refunded_cents: '客户退款', expense_refunded_cents: '支出退回', committed_cents: '应付总额', outstanding_cents: '待付款', receivable_cents: '待收款' };
  const types = { RECEIPT: '收款', PAYABLE: '应付', PAYMENT: '付款', ADVANCE: '垫付', REFUND: '客户退款', EXPENSE_REFUND: '支出退回' };
  const kinds = { RECEIPT: 'receipts', PAYABLE: 'payables', PAYMENT: 'payments', ADVANCE: 'payments', REFUND: 'refunds', EXPENSE_REFUND: 'refunds' };
  return { accounting:accounting(value.accounting,scope), summary: Object.entries(names).map(([key, label]) => ({ label, value: money(value.summary[key]) })), truncated: value.truncated === true,
    entries: value.entries.map((row, index) => ({ id: api.id(row.id), key: row.type + ':' + row.id + ':' + index, kind: kinds[row.type] || '', type: types[row.type] || text(row.type),
      name: text(row.name), date: date(row.date), amount: money(row.amount_cents), status: status(row.status), note: row.note || '', counterparty: row.counterparty || '' })) };
}
module.exports = { SECTIONS, FINANCE, STEP_NAMES, context, page, card, customer, records, documents, siteRecords, project, finance, books, ledger, title, text, money, measurementObservations };
