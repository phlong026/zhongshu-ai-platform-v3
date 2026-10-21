const B = require('../../utils/business');
const X = require('./extra-business');
const titles = { 'service-order': '设计服务单', 'service-prepare': '设计服务费核定', 'drawing-review': '施工图专业审核', 'review-submit': '施工图送审', 'review-record': '施工图审核办理', 'start-check': '开工条件核查', 'start-receipt': '开工条件核查回执' };
const defaults = { serviceTotal: '', serviceBasis: '', serviceVerifier: '', serviceFile: null, serviceChecked: false, reviewer: '', reviewSubmitNote: '', reviewReady: false, reviewResult: '审核通过', reviewOpinion: '', reviewFile: null, reviewChecked: false, startChecker: '', startNote: '', startFile: null, startSiteChecked: false, startTeamChecked: false, startDrawingChecked: false, startMilestoneChecked: false, startBriefChecked: false };
const dates = ['serviceDate', 'reviewDate', 'startDate'];
const kv = (label, value) => ({ type: 'kv', label, value });
const text = value => ({ type: 'text', value });
const notice = value => ({ type: 'notice', value });
const condition = (value, ok, subtitle) => ({ type: 'condition', value, ok: !!ok, subtitle: subtitle || '' });
const file = (value, key = 'storedFile') => ({ type: 'file', name: value ? value.name : '暂无附件', key, file: value, icon: value && /\.(png|jpg|jpeg)$/i.test(value.name) ? 'image' : 'pdf' });
const input = (label, key, kind = '', unit = '') => ({ type: 'input', label, key, placeholder: '请填写' + label, kind, inputType: unit === '元' ? 'digit' : 'text', unit });
const upload = (label, key) => ({ type: 'upload', label, key });
const check = (label, key) => ({ type: 'check', label, key });
const area = key => ({ type: 'textarea', key });
const choice = (label, key, values) => ({ type: 'choice', label, key, values });
const section = (title, items, extra) => Object.assign({ title, items }, extra || {});
const links = values => ({ type: 'links', values: values.map(([title, view]) => ({ title, view })) });
const reviewNames = { pending: '审核中', passed: '审核通过', returned: '退回修改' };
function config(view, c, f, record) {
  if (!titles[view]) return null;
  const order = X.service(c), d = X.drawing(c), r = X.review(c), start = X.validStart(c);
  const out = { title: titles[view], sections: [], primary: '', secondary: '返回项目办理', badge: '', role: '', large: true, tabs: [], activeTab: 0 };
  const drawingSummary = [kv('施工图版本', d ? d.version : '尚未补传'), file(d && d.file), kv('上传日期', d ? d.date : '—')];
  if (view === 'service-order') {
    out.badge = order ? '已核定' : '待核定'; out.role = '财务';
    out.sections = [section('设计服务单', order ? [{ type: 'money', value: Number(order.total).toLocaleString('en-US', { minimumFractionDigits: 2 }), subtitle: '设计服务费 · '+(order.recipient||'总部设计服务中心') }, kv('服务单版本', 'V' + order.version), kv('关联需求', '电子设计需求 V' + order.requestVersion), kv('核定人', order.verifier), kv('核定日期', order.date)] : [text('服务费尚未核定'), notice('按实际设计服务约定登记总额，再据此办理首尾款。')]), section('款项节点', [condition('首款（50%）' + (order ? ' · ￥' + B.money(order.first) : ''), c.firstPayment, c.firstPayment ? '已登记付款' : '待财务办理'), condition('尾款（50%）' + (order ? ' · ￥' + B.money(order.tail) : ''), c.tailPayment, c.tailPayment ? '已登记付款' : '正式成果完成后申请')])];
    if(order&&Object.values(order.financeStates||{}).some(x=>x.status==='需补充'))out.sections.push(section('付款资料待补充',Object.values(order.financeStates).filter(x=>x.status==='需补充').map(x=>notice(x.reason||'财务已退回，请补充本版服务约定后重提。'))));
    if (order) out.sections.push(section('核定依据', [text(order.basis), file(order.file), notice('首尾款按分计量拆分，尾款补齐舍入差额；付款后核定版本锁定，历史版本保留。')]));
    out.sections.push(section('核定历史', [{ type: 'record-list', values: X.list(c, 'serviceOrders').slice().reverse().map(x => ({ title: '服务单 V' + x.version + ' · ￥' + B.money(x.total), subtitle: x.verifier + ' · ' + x.date + ' · ' + x.basis, badge: '已留存', files: [x.file] })) }]));
    out.primary = !order ? '核定设计服务费' : !c.firstPayment ? '办理设计首款' : !c.tailPayment ? c.tailApplication ? '办理设计尾款' : '查看正式设计进度' : '返回项目办理';
    if (order && !c.firstPayment) out.sections.push(section('', [links([['重新核定', 'service-prepare']])]));
  }
  if (view === 'service-prepare') {
    out.role = '财务'; out.sections = [section('关联项目', [kv('需求版本', '电子设计需求 V' + (c.request ? c.request.version : '—')), notice('设计服务费与建造合同客户收款分别核算。')]), section('核定金额', [input('服务费总额', 'serviceTotal', '', '元'), ...(c.designOrganizationId===(c.companyId||'quanzhou')?[input('实际收款人','serviceRecipient')]:[]), input('核定人', 'serviceVerifier'), input('核定日期', 'serviceDate', 'date')]), section('核定依据', [area('serviceBasis'), upload('上传设计服务约定文件', 'serviceFile'), check('已核对服务范围、总额及首尾款比例', 'serviceChecked')])]; out.primary = '保存服务费核定';
    if (c.firstPayment || c.tailPayment) { out.sections=[section('版本已锁定', [notice('已有付款记录，不能覆盖原核定依据。请返回设计服务单查看。')])];out.primary='查看已核定服务单'; }
  }
  if (view === 'drawing-review') {
    out.role = '审核'; out.badge = r ? reviewNames[r.status] : '待送审';
    out.sections = [section('本版施工图', drawingSummary), section('专业审核', r ? [kv('审核人', r.reviewer), kv('送审日期', r.submitDate), text(r.note), condition(reviewNames[r.status], r.status === 'passed'), ...(r.opinion ? [text(r.opinion), kv('办理日期', r.date), file(r.report)] : [notice('等待专业审核人员填写意见与审核记录。')])] : [notice('补传与抄送不会自动取得专业审核结论。')]), section('审核历史', [{ type: 'record-list', values: X.list(c, 'reviews').slice().reverse().map(x => ({ title: '施工图 ' + x.drawingVersion + ' · ' + reviewNames[x.status], subtitle: x.reviewer + ' · ' + (x.date || x.submitDate) + (x.opinion ? ' · ' + x.opinion : ''), badge: x.status === 'passed' ? '已通过' : '', files: [x.report || x.file] })) }]), section('后续办理', [links([['项目资料', 'documents'], ['开工条件核查', 'start-check']])])];
    out.primary = !r ? '提交专业审核' : r.status === 'pending' ? '办理专业审核' : r.status === 'returned' ? '补传修订版施工图' : c.acceptance ? '核查开工条件' : '继续工程移交';
  }
  if (view === 'review-submit') { out.sections = [section('送审版本', drawingSummary), section('送审信息', [input('审核人', 'reviewer'), area('reviewSubmitNote'), check('已核对图纸版本及送审资料完整性', 'reviewReady'), notice('审核意见与结论将绑定本版文件。退回修改后须补传新版本再送审。')])]; out.primary = '提交本版专业审核'; }
  if (view === 'review-record') { out.role = '审核'; out.secondary = '返回审核记录'; out.sections = [section('审核对象', drawingSummary.concat(r ? [kv('审核人', r.reviewer), text(r.note)] : [])), section('审核结论', [choice('办理结果', 'reviewResult', ['审核通过', '退回修改']), input('审核日期', 'reviewDate', 'date'), area('reviewOpinion'), upload('上传专业审核记录', 'reviewFile'), check('已完成本版专业审核并核对结论', 'reviewChecked')])]; out.primary = r && r.status !== 'pending' ? '查看审核记录' : '保存审核结论';if(r&&r.status!=='pending')out.sections[1]=section('已归档审核结论',[kv('结论',reviewNames[r.status]),kv('审核日期',r.date),text(r.opinion),file(r.report)]); }
  if (view === 'start-check') {
    out.role = '工程负责人'; out.badge = start ? '已核查 · 待开工' : '待核查';
    out.sections = [section('开工前置条件', [condition('工程已接收', c.acceptance), condition('建造合同已签署', !!c.contract), condition('当前施工图专业审核通过', X.approvedReview(c), d ? '施工图 ' + d.version : '尚无施工图'), condition('本版资料已随移交交接', X.coverage(c)), links([['专业审核', 'drawing-review']])]), section('现场与执行条件', [check('现场与施工场地条件已核对', 'startSiteChecked'), check('施工人员及负责人安排已核对', 'startTeamChecked'), check('施工使用图纸与审核通过版本一致', 'startDrawingChecked'), check('约定节点及相应款项条件已核对', 'startMilestoneChecked'), check('技术交底及现场重点已核对', 'startBriefChecked')]), section('核查记录', [input('核查人', 'startChecker'), input('核查日期', 'startDate', 'date'), area('startNote'), upload('上传现场核查凭据', 'startFile'), notice('保存核查通过记录后仍为待开工，不自动登记实际开工。资料版本变化后需重新核查。')])]; out.primary = start ? '查看开工核查回执' : '保存开工条件核查';
  }
  if (view === 'start-receipt') { const s = start; out.badge = '核查通过 · 待开工'; out.sections = [section('开工条件核查回执', s ? [kv('核查版本', 'V' + s.version), kv('核查人', s.checker), kv('核查日期', s.date), kv('施工图版本', s.drawingVersion), ...s.checks.map(x => condition(x + '已核查', true)), text(s.note), file(s.file)] : []), section('工程状态', [text('开工条件已核查 · 待开工'), notice('实际开工前由工程负责人指派施工人员并按约定另行登记开工；本次核查没有更改原工程接收记录。')]), section('核查历史', [{ type: 'record-list', values: X.list(c, 'startChecks').slice().reverse().map(x => ({ title: '核查 V' + x.version + ' · 施工图 ' + x.drawingVersion, subtitle: x.checker + ' · ' + x.date, badge: s && x.version === s.version ? '当前有效' : '历史留存', files: [x.file] })) }])]; out.primary = '返回项目办理'; }
  if(out.primary===out.secondary)out.secondary='';
  return out;
}
function decorate(view, out, c) {
  const order = X.service(c), d = X.drawing(c);
  if (['hub', 'delivery'].includes(view)) {
    const stage=out.sections.find(s=>s.title==='当前阶段');
    if(X.validStart(c)&&stage)stage.items[0].value='开工条件已核查 · 待开工';
    out.sections.push(section('审核与开工', [{ type: 'steps', values: [{ title: '设计服务单', view: 'service-order', done: !!order }, { title: '施工图专业审核', view: 'drawing-review', done: !!X.approvedReview(c) }, { title: '开工条件核查', view: 'start-check', done: !!X.validStart(c) }] }]));
  }
  if (['documents', 'copies', 'handover', 'accepted'].includes(view)) out.sections.push(section('项目协同', [links([['施工图专业审核', 'drawing-review'], ['开工条件核查', 'start-check']])]));
  if (view === 'accepted') { out.primary = '核查开工条件'; out.sections[0].items.push(links([['查看原工程接收', 'receive']])); }
  if (['payment-first', 'payment-tail', 'tail-apply'].includes(view)) {
    out.sections.push(section('设计服务单', [kv('核定版本', order ? 'V' + order.version : '尚未核定'), kv('本次应付', order ? '￥' + B.money(view === 'payment-first' ? order.first : order.tail) : '请先核定服务费'), links([['查看设计服务单', 'service-order']])]));
    if (view === 'payment-first' && order) out.sections[0].items[2].value = '￥' + B.money(order.first) + ' · 服务单 V' + order.version;
  }
  return out;
}
module.exports = { titles, defaults, dates, config, decorate };
