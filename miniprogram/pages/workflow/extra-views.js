const B = require('../../utils/business');
const X = require('./extra-business');
const titles = { 'service-order': '设计服务单', 'service-prepare': '设计服务费核定', 'drawing-review': '施工图专业审核', 'review-submit': '施工图送审', 'review-record': '施工图审核办理', 'change-list': '设计变更', 'change-new': '变更申请', 'change-detail': '设计变更详情', 'change-approval': '设计变更审批', amendment: '合同补充协议', 'change-confirmation': '变更成果确认', 'supplement-handover': '补充资料交接', 'supplement-receive': '补充资料接收', 'start-check': '开工条件核查', 'start-receipt': '开工条件核查回执' };
const defaults = { serviceTotal: '', serviceBasis: '', serviceVerifier: '', serviceFile: null, serviceChecked: false, reviewer: '', reviewSubmitNote: '', reviewReady: false, reviewResult: '审核通过', reviewOpinion: '', reviewFile: null, reviewChecked: false, changeReason: '', changeScope: '', changeDelta: '0', changeDays: '0', changeApplicant: '', changeFile: null, changeChecked: false, changeDecision: '同意变更', changeApprover: '', changeOpinion: '', changeApprovalFile: null, changeApprovalChecked: false, agreementNo: '', agreementFile: null, agreementChecked: false, changeResultFile: null, changeConfirmationFile: null, changeConfirmationNote: '', changeConfirmationChecked: false, supplementManager: '', supplementNote: '', supplementDrawingChecked: false, supplementAgreementChecked: false, supplementBriefChecked: false, startChecker: '', startNote: '', startFile: null, startSiteChecked: false, startTeamChecked: false, startDrawingChecked: false, startMilestoneChecked: false, startBriefChecked: false };
const dates = ['serviceDate', 'reviewDate', 'changeDate', 'changeApprovalDate', 'agreementDate', 'changeConfirmationDate', 'supplementDate', 'supplementReceiveDate', 'startDate'];
const kv = (label, value) => ({ type: 'kv', label, value });
const text = value => ({ type: 'text', value });
const notice = value => ({ type: 'notice', value });
const condition = (value, ok, subtitle) => ({ type: 'condition', value, ok: !!ok, subtitle: subtitle || '' });
const file = (value, key = 'storedFile') => ({ type: 'file', name: value ? value.name : '暂无附件', key, file: value, icon: value && /\.(png|jpg|jpeg)$/i.test(value.name) ? 'image' : 'pdf' });
const input = (label, key, kind = '', unit = '') => ({ type: 'input', label, key, placeholder: '请填写' + label, kind, inputType: unit === '元' && key !== 'changeDelta' ? 'digit' : 'text', unit });
const upload = (label, key) => ({ type: 'upload', label, key });
const check = (label, key) => ({ type: 'check', label, key });
const area = key => ({ type: 'textarea', key });
const choice = (label, key, values) => ({ type: 'choice', label, key, values });
const section = (title, items, extra) => Object.assign({ title, items }, extra || {});
const links = values => ({ type: 'links', values: values.map(([title, view]) => ({ title, view })) });
const reviewNames = { pending: '审核中', passed: '审核通过', returned: '退回修改' };
const changeNames = { submitted: '待审批', approved: '待签补充协议', signed: '待客户确认', confirmed: '待资料交接', returned: '已退回', completed: '已完成交接' };
function config(view, c, f, record) {
  if (!titles[view]) return null;
  const order = X.service(c), d = X.drawing(c), r = X.review(c), ch = X.selected(c, record), current = X.activeChange(c), start = X.validStart(c);
  const out = { title: titles[view], sections: [], primary: '', secondary: '返回项目办理', badge: '', role: '', large: true, tabs: [], activeTab: 0 };
  const changeSummary = ch && !ch.baseline ? [kv('变更编号','BG-'+String(ch.version).padStart(3,'0')),text(ch.reason),text(ch.scope)] : ch ? [kv('变更编号', 'BG-' + String(ch.version).padStart(3, '0')), kv('申请人', ch.applicant), kv('申请日期', ch.date), text(ch.reason), text(ch.scope), kv('原有效金额', '￥' + B.money(ch.baseline.effectiveTotal)), kv('金额调整', (ch.delta >= 0 ? '+' : '-') + '￥' + B.money(Math.abs(ch.delta))), kv('变更后金额', '￥' + B.money(ch.total)), kv('工期增加', ch.days + '天'), file(ch.file)] : [];
  const drawingSummary = [kv('施工图版本', d ? d.version : '尚未补传'), file(d && d.file), kv('上传日期', d ? d.date : '—')];
  if (view === 'service-order') {
    out.badge = order ? '已核定' : '待核定'; out.role = '财务';
    out.sections = [section('设计服务单', order ? [{ type: 'money', value: Number(order.total).toLocaleString('en-US', { minimumFractionDigits: 2 }), subtitle: '门店 → 众墅之家总部 · 设计服务费' }, kv('服务单版本', 'V' + order.version), kv('关联需求', '电子设计需求 V' + order.requestVersion), kv('核定人', order.verifier), kv('核定日期', order.date)] : [text('服务费尚未核定'), notice('按实际设计服务约定登记总额，再据此办理首尾款。')]), section('款项节点', [condition('首款（50%）' + (order ? ' · ￥' + B.money(order.first) : ''), c.firstPayment, c.firstPayment ? '已登记付款' : '待财务办理'), condition('尾款（50%）' + (order ? ' · ￥' + B.money(order.tail) : ''), c.tailPayment, c.tailPayment ? '已登记付款' : '正式成果完成后申请')])];
    if(order&&Object.values(order.financeStates||{}).some(x=>x.status==='需补充'))out.sections.push(section('付款资料待补充',Object.values(order.financeStates).filter(x=>x.status==='需补充').map(x=>notice(x.reason||'财务已退回，请补充本版服务约定后重提。'))));
    if (order) out.sections.push(section('核定依据', [text(order.basis), file(order.file), notice('首尾款按分计量拆分，尾款补齐舍入差额；付款后核定版本锁定，历史版本保留。')]));
    out.sections.push(section('核定历史', [{ type: 'record-list', values: X.list(c, 'serviceOrders').slice().reverse().map(x => ({ title: '服务单 V' + x.version + ' · ￥' + B.money(x.total), subtitle: x.verifier + ' · ' + x.date + ' · ' + x.basis, badge: '已留存', files: [x.file] })) }]));
    out.primary = !order ? '核定设计服务费' : !c.firstPayment ? '办理设计首款' : !c.tailPayment ? c.tailApplication ? '办理设计尾款' : '查看正式设计进度' : '返回项目办理';
    if (order && !c.firstPayment) out.sections.push(section('', [links([['重新核定', 'service-prepare']])]));
  }
  if (view === 'service-prepare') {
    out.role = '财务'; out.sections = [section('关联项目', [kv('需求版本', '电子设计需求 V' + (c.request ? c.request.version : '—')), notice('设计服务费与建造合同客户收款分别核算。')]), section('核定金额', [input('服务费总额', 'serviceTotal', '', '元'), input('核定人', 'serviceVerifier'), input('核定日期', 'serviceDate', 'date')]), section('核定依据', [area('serviceBasis'), upload('上传设计服务约定文件', 'serviceFile'), check('已核对服务范围、总额及首尾款比例', 'serviceChecked')])]; out.primary = '保存服务费核定';
    if (c.firstPayment || c.tailPayment) { out.sections=[section('版本已锁定', [notice('已有付款记录，不能覆盖原核定依据。请返回设计服务单查看。')])];out.primary='查看已核定服务单'; }
  }
  if (view === 'drawing-review') {
    out.role = '审核'; out.badge = r ? reviewNames[r.status] : '待送审';
    out.sections = [section('本版施工图', drawingSummary), section('专业审核', r ? [kv('审核人', r.reviewer), kv('送审日期', r.submitDate), text(r.note), condition(reviewNames[r.status], r.status === 'passed'), ...(r.opinion ? [text(r.opinion), kv('办理日期', r.date), file(r.report)] : [notice('等待专业审核人员填写意见与审核记录。')])] : [notice('补传与抄送不会自动取得专业审核结论。')]), section('审核历史', [{ type: 'record-list', values: X.list(c, 'reviews').slice().reverse().map(x => ({ title: '施工图 ' + x.drawingVersion + ' · ' + reviewNames[x.status], subtitle: x.reviewer + ' · ' + (x.date || x.submitDate) + (x.opinion ? ' · ' + x.opinion : ''), badge: x.status === 'passed' ? '已通过' : '', files: [x.report || x.file] })) }]), section('后续办理', [links([['项目资料', 'documents'], ['设计变更', 'change-list'], ['开工条件核查', 'start-check']])])];
    out.primary = !r ? '提交专业审核' : r.status === 'pending' ? '办理专业审核' : r.status === 'returned' ? '补传修订版施工图' : !c.acceptance ? '继续工程移交' : !X.coverage(c) ? '办理补充资料交接' : '核查开工条件';
  }
  if (view === 'review-submit') { out.sections = [section('送审版本', drawingSummary), section('送审信息', [input('审核人', 'reviewer'), area('reviewSubmitNote'), check('已核对图纸版本及送审资料完整性', 'reviewReady'), notice('审核意见与结论将绑定本版文件。退回修改后须补传新版本再送审。')])]; out.primary = '提交本版专业审核'; }
  if (view === 'review-record') { out.role = '审核'; out.secondary = '返回审核记录'; out.sections = [section('审核对象', drawingSummary.concat(r ? [kv('审核人', r.reviewer), text(r.note)] : [])), section('审核结论', [choice('办理结果', 'reviewResult', ['审核通过', '退回修改']), input('审核日期', 'reviewDate', 'date'), area('reviewOpinion'), upload('上传专业审核记录', 'reviewFile'), check('已完成本版专业审核并核对结论', 'reviewChecked')])]; out.primary = r && r.status !== 'pending' ? '查看审核记录' : '保存审核结论';if(r&&r.status!=='pending')out.sections[1]=section('已归档审核结论',[kv('结论',reviewNames[r.status]),kv('审核日期',r.date),text(r.opinion),file(r.report)]); }
  if (view === 'change-list') {
    out.badge = current ? '有待办变更' : '正常办理'; out.sections = [section('合同与变更', [kv('原建造合同', '￥' + B.money(c.contract ? c.contract.total : 0)), kv('当前有效金额', '￥' + B.money(X.effectiveTotal(c))), notice('变更单、审批、补充协议、客户确认和资料交接分别留存。原合同与历史接收不覆盖。')]), section('变更记录', [{ type: 'record-list', values: X.list(c, 'changes').slice().reverse().map(x => ({ title: '设计变更 V' + x.version + ' · ' + x.reason, subtitle: x.applicant + ' · ' + x.date, badge: changeNames[x.status], view: 'change-detail', record: x.id })) }])]; out.primary = current ? '继续办理当前变更' : '新增变更申请';
  }
  if (view === 'change-new') { out.sections = [section('变更前基准', [kv('有效合同金额', '￥' + B.money(X.effectiveTotal(c))), kv('当前施工图', d ? d.version : '尚未补传'), kv('当前工程状态', c.acceptance ? '已接收 · 待开工' : B.stage(c))]), section('变更原因', [area('changeReason')]), section('变更范围与影响', [area('changeScope'), input('金额调整', 'changeDelta', '', '元'), notice('增加填写正数，减少填写负数；无金额变化填写0。'), input('增加工期', 'changeDays', '', '天'), input('申请人', 'changeApplicant'), input('申请日期', 'changeDate', 'date'), upload('上传变更依据', 'changeFile'), check('已向客户说明方案、金额与工期影响', 'changeChecked')])]; out.primary = '提交设计变更申请'; }
  if (view === 'change-detail') {
    out.badge = ch ? changeNames[ch.status] : '';
    out.sections = [section('变更申请', changeSummary), section('办理进度', [condition('变更审批', ch && ch.approval && ch.status !== 'returned', ch && ch.approval ? ch.approval.approver + ' · ' + ch.approval.decision + ' · ' + ch.approval.opinion : '待审批'), condition('双方签署补充协议', ch && ch.agreementId), condition('变更成果客户确认', ch && ch.confirmation), condition('本版资料完成交接', ch && ch.status === 'completed')]), section('相关资料', [links([['合同补充协议', 'amendment'], ['变更成果确认', 'change-confirmation'], ['项目资料', 'documents']])])];
    if (ch && ch.approval) out.sections.push(section('审批记录', [kv('审批日期', ch.approval.date), file(ch.approval.file)]));
    if (ch && ch.confirmation) out.sections.push(section('客户确认记录', [kv('确认日期', ch.confirmation.date), text(ch.confirmation.note), file(ch.result), file(ch.confirmation.file)]));
    out.primary = ch ? ({ submitted: '办理设计变更审批', approved: '登记合同补充协议', signed: '登记变更成果确认', confirmed: d && d.changeId === ch.id ? '继续本版资料交接' : '补传变更施工图', returned: '重新提交变更申请', completed: '核查开工条件' })[ch.status] : '';
  }
  if (view === 'change-approval') { out.role = '审批'; out.sections = [section('申请与影响', changeSummary), section('审批办理', [choice('审批结果', 'changeDecision', ['同意变更', '退回补充']), input('审批人', 'changeApprover'), input('审批日期', 'changeApprovalDate', 'date'), area('changeOpinion'), upload('上传审批依据', 'changeApprovalFile'), check('已核对变更范围、合同金额与工期影响', 'changeApprovalChecked')])]; out.primary = '保存变更审批';if(ch&&ch.status!=='submitted'){out.sections[1]=section('已归档审批记录',ch.approval?[kv('审批人',ch.approval.approver),kv('审批结果',ch.approval.decision),kv('审批日期',ch.approval.date),text(ch.approval.opinion),file(ch.approval.file)]:[]);out.primary='查看变更详情';} }
  if (view === 'amendment') {
    const agreement = ch && X.list(c, 'agreements').find(x => x.id === ch.agreementId);
    out.badge = agreement ? '双方已签' : '待签署';
    out.sections = [section('关联变更', [kv('变更编号', ch ? 'BG-' + String(ch.version).padStart(3, '0') : '—'), kv('原建造合同', 'V' + (c.contract ? c.contract.version : '—')), kv('变更后有效金额', '￥' + B.money(ch ? ch.total : 0)), kv('本次金额调整', (ch && ch.delta >= 0 ? '+' : '') + '￥' + B.money(ch ? ch.delta : 0)), kv('增加工期', (ch ? ch.days : 0) + '天')]), section('双方签署登记', agreement ? [kv('协议编号', agreement.number), kv('补充协议版本', 'V' + agreement.version), kv('签署日期', agreement.date), file(agreement.file), notice('本协议与原合同分别归档，原合同金额及附件保持原样。')] : [input('协议编号', 'agreementNo'), input('签署日期', 'agreementDate', 'date'), upload('上传双方已签补充协议', 'agreementFile'), check('双方已签署，金额、工期与审批一致', 'agreementChecked')])]; out.primary = agreement ? '返回变更详情' : '保存补充协议签署';
  }
  if (view === 'change-confirmation') { out.sections = [section('本次变更', [kv('变更版本', ch ? 'V' + ch.version : '—'), text(ch ? ch.scope : ''), kv('补充协议', ch && ch.agreementId ? ((X.list(c,'agreements').find(x=>x.id===ch.agreementId)||{}).number||'协议记录缺失') : '待签署')]), section('变更成果与客户确认', ch && ch.confirmation ? [file(ch.result), file(ch.confirmation.file), kv('确认日期', ch.confirmation.date), text(ch.confirmation.note)] : [upload('上传变更后的正式成果', 'changeResultFile'), upload('上传客户确认凭据', 'changeConfirmationFile'), input('确认日期', 'changeConfirmationDate', 'date'), area('changeConfirmationNote'), check('客户已核对本次变更成果及补充协议', 'changeConfirmationChecked')]), section('资料版本', [notice('变更确认完成后补传新的施工图版本，并重新审核、交接；不覆盖原正式成果的客户确认。')])]; out.primary = ch && ch.confirmation ? '补传变更施工图' : '登记变更成果确认'; }
  if (view === 'supplement-handover' || view === 'supplement-receive') {
    const transfer = B.latest(X.list(c, 'supplements').filter(x => d && x.drawingVersion === d.version));
    out.badge = transfer ? transfer.status === 'accepted' ? '补充资料已接收' : '待接收' : '待提交';
    out.sections = [section('本版补充资料', drawingSummary.concat([condition('本版专业审核通过', X.approvedReview(c)), condition('本版施工图抄送已留存', d && c.copies.some(x => x.version === d.version)), kv('关联变更',d&&d.changeId&&X.selected(c,d.changeId)?'BG-'+String(X.selected(c,d.changeId).version).padStart(3,'0'):'常规补图'), notice('原工程接收回执保留，本次仅交接补充资料。')])), section('交底与接收', view === 'supplement-handover' && !transfer ? [input('接收经理', 'supplementManager'), input('提交日期', 'supplementDate', 'date'), area('supplementNote')] : [kv('接收经理',c.currentManagerName||(transfer?transfer.manager:'—')), kv('提交日期', transfer ? transfer.date : '—'), text(transfer ? transfer.note : '')])];
    if(transfer&&transfer.files)out.sections.push(section('本次交接附件',[file(transfer.files.drawing),file(transfer.files.review)].concat(transfer.files.agreement?[file(transfer.files.agreement.file),file(transfer.files.result),file(transfer.files.confirmation)]:[])));
    if (transfer) out.sections.push(section('接收核查', transfer.status === 'accepted' ? [condition('本版资料已接收', true), kv('接收日期', transfer.acceptance.date)] : [check('已核对本版施工图与专业审核记录', 'supplementDrawingChecked'), check('已核对关联补充协议或无合同变更', 'supplementAgreementChecked'), check('已完成本次补充交底', 'supplementBriefChecked'), input('接收日期', 'supplementReceiveDate', 'date')]));
    out.primary = X.coverage(c) ? '核查开工条件' : view === 'supplement-handover' ? transfer ? '办理补充资料接收' : '提交补充资料交接' : '确认接收补充资料';
  }
  if (view === 'start-check') {
    out.role = '工程师'; out.badge = start ? '已核查 · 待开工' : '待核查';
    out.sections = [section('开工前置条件', [condition('工程已接收', c.acceptance), condition('当前施工图专业审核通过', X.approvedReview(c), d ? '施工图 ' + d.version : '尚无施工图'), condition('当前版本资料已交接', X.coverage(c)), condition('无待办设计变更', !current), links([['专业审核', 'drawing-review'], ['补充资料交接', 'supplement-handover'], ['设计变更', 'change-list']])]), section('现场与执行条件', [check('现场与施工场地条件已核对', 'startSiteChecked'), check('施工人员及负责人安排已核对', 'startTeamChecked'), check('施工使用图纸与审核通过版本一致', 'startDrawingChecked'), check('约定节点及相应款项条件已核对', 'startMilestoneChecked'), check('技术交底及现场重点已核对', 'startBriefChecked')]), section('核查记录', [input('核查人', 'startChecker'), input('核查日期', 'startDate', 'date'), area('startNote'), upload('上传现场核查凭据', 'startFile'), notice('保存核查通过记录后仍为待开工，不自动登记实际开工。资料或变更版本变化后需重新核查。')])]; out.primary = start ? '查看开工核查回执' : '保存开工条件核查';
  }
  if (view === 'start-receipt') { const s = start; out.badge = '核查通过 · 待开工'; out.sections = [section('开工条件核查回执', s ? [kv('核查版本', 'V' + s.version), kv('核查人', s.checker), kv('核查日期', s.date), kv('施工图版本', s.drawingVersion), kv('补充协议版本', s.agreementVersion ? 'V' + s.agreementVersion : '无补充协议'), ...s.checks.map(x => condition(x + '已核查', true)), text(s.note), file(s.file)] : []), section('工程状态', [text('开工条件已核查 · 待开工'), notice('实际开工需按项目约定另行办理；本次核查没有更改原工程接收记录。')]), section('核查历史', [{ type: 'record-list', values: X.list(c, 'startChecks').slice().reverse().map(x => ({ title: '核查 V' + x.version + ' · 施工图 ' + x.drawingVersion, subtitle: x.checker + ' · ' + x.date, badge: s && x.version === s.version ? '当前有效' : '历史留存', files: [x.file] })) }])]; out.primary = '返回项目办理'; }
  if(out.primary===out.secondary)out.secondary='';
  return out;
}
function decorate(view, out, c) {
  const order = X.service(c), d = X.drawing(c);
  if (['hub', 'delivery'].includes(view)) {
    if(X.validStart(c))out.sections[0].items[0].value='开工条件已核查 · 待开工';
    out.sections.push(section('审核、变更与开工', [{ type: 'steps', values: [{ title: '设计服务单', view: 'service-order', done: !!order }, { title: '施工图专业审核', view: 'drawing-review', done: !!X.approvedReview(c) }, { title: '设计变更', view: 'change-list', done: !X.activeChange(c) && X.list(c, 'changes').length > 0 }, { title: '补充资料交接', view: 'supplement-handover', done: !!X.coverage(c) }, { title: '开工条件核查', view: 'start-check', done: !!X.validStart(c) }] }]));
  }
  if (['documents', 'copies', 'handover', 'accepted'].includes(view)) out.sections.push(section('项目协同', [links([['施工图专业审核', 'drawing-review'], ['设计变更', 'change-list'], ['开工条件核查', 'start-check']])]));
  if (view === 'accepted') { out.primary = '核查开工条件'; out.sections[0].items.push(links([['查看原工程接收', 'receive']])); }
  if (view === 'contract') out.sections.push(section('合同补充', [kv('当前有效金额', '￥' + B.money(X.effectiveTotal(c))), links([['设计变更与补充协议', 'change-list']])]));
  if (['payment-first', 'payment-tail', 'tail-apply'].includes(view)) {
    out.sections.push(section('设计服务单', [kv('核定版本', order ? 'V' + order.version : '尚未核定'), kv('本次应付', order ? '￥' + B.money(view === 'payment-first' ? order.first : order.tail) : '请先核定服务费'), links([['查看设计服务单', 'service-order']])]));
    if (view === 'payment-first' && order) out.sections[0].items[2].value = '￥' + B.money(order.first) + ' · 服务单 V' + order.version;
  }
  if (view === 'receive' && c.acceptance && d && c.handover && d.version !== c.handover.drawingVersion) out.sections.push(section('补充图纸待交接', [notice('当前图纸已更新，原接收回执保持不变。'), links([['办理补充资料交接', 'supplement-handover']])]));
  return out;
}
module.exports = { titles, defaults, dates, config, decorate, changeNames };
