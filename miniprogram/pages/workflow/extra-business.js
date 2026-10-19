const B = require('../../utils/business');
const list = (c, key) => c[key] || [];
const last = (c, key) => B.latest(list(c, key));
const selected = (c, id) => id ? list(c, 'changes').find(x => x.id === id) : last(c, 'changes');
const activeChange = c => list(c, 'changes').find(x => !['returned', 'completed'].includes(x.status));
const drawing = c => B.latest(c.drawings);
const service = c => last(c, 'serviceOrders');
const review = c => { const d = drawing(c); return d && B.latest(list(c, 'reviews').filter(x => x.drawingVersion === d.version)); };
const approvedReview = c => { const r = review(c); return r && r.status === 'passed' ? r : null; };
function requireValue(ok, message) { if (!ok) throw new Error(message); }
function validDate(value) { return /^\d{4}-\d{2}-\d{2}$/.test(value || '') && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value; }
function cents(value, signed = false) { requireValue((signed ? /^-?\d+(\.\d{1,2})?$/ : /^\d+(\.\d{1,2})?$/).test(String(value)) && Number.isFinite(Number(value)) && Math.abs(Number(value)) <= 100000000, '金额最多两位小数，且不得超过一亿元。'); return Math.round(Number(value) * 100); }
function ensure(c) { for (const key of ['serviceOrders', 'reviews', 'changes', 'agreements', 'supplements', 'startChecks']) c[key] = c[key] || []; }
function originalHandover(c) { return [c.handover].concat(list(c, 'handoverHistory')).find(x => x && c.acceptance && x.version === c.acceptance.handoverVersion); }
function coverage(c) {
  const d = drawing(c); if (!d || !c.acceptance) return null;
  const supplementary = B.latest(list(c, 'supplements').filter(x => x.status === 'accepted' && x.drawingVersion === d.version));
  if (supplementary) return { key: supplementary.id, date: supplementary.acceptance.date, manager: supplementary.manager };
  const original = originalHandover(c);
  return original && original.drawingVersion === d.version ? { key: 'handover-' + original.version, date: c.acceptance.date, manager: c.acceptance.manager } : null;
}
function fingerprint(c) { const d = drawing(c), r = approvedReview(c), covered = coverage(c), agreement = last(c, 'agreements'); return [d && d.version, r && r.id, covered && covered.key, agreement && agreement.version || 0].join('|'); }
function validStart(c) { const check = last(c, 'startChecks'); return check && !activeChange(c) && approvedReview(c) && coverage(c) && check.fingerprint === fingerprint(c) ? check : null; }
function effectiveTotal(c) { const agreement = last(c, 'agreements'); return agreement ? agreement.total : c.contract ? c.contract.total : 0; }
function context(view, c, record) {
  if (['review-submit', 'review-record', 'supplement-handover', 'supplement-receive'].includes(view)) return (drawing(c) || {}).version || 'none';
  if (['change-approval', 'amendment', 'change-confirmation'].includes(view)) return (selected(c, record) || {}).id || 'none';
  if (view === 'start-check') return fingerprint(c) + '|' + ((activeChange(c) || {}).id || '');
  if (view === 'change-new') return String(list(c, 'changes').length);
  return (c.request || {}).version || 'none';
}
function block(view, c, record) {
  const b = (message, target) => ({ message, target, label: '去补齐前置资料' });
  if (['service-order', 'service-prepare'].includes(view) && !c.request) return b('请先提交电子设计需求，再核定设计服务费。', 'request');
  if (['drawing-review', 'review-submit', 'review-record'].includes(view) && !drawing(c)) return b('请先补传有明确版本号的施工图。', 'documents');
  if (view === 'review-record' && !review(c)) return b('本版施工图尚未送审。', 'review-submit');
  if (view === 'review-submit' && review(c)) return b(review(c).status === 'returned' ? '本版已退回，请补传修订后的新版本再送审。' : '本版已有专业审核记录，请查看当前办理状态。', review(c).status === 'returned' ? 'documents' : 'drawing-review');
  if (view === 'change-new' && activeChange(c)) return b('已有未完成的设计变更，请先继续办理。', 'change-detail');
  if (['change-new', 'change-list', 'change-detail', 'change-approval', 'amendment', 'change-confirmation'].includes(view) && !c.contract) return b('签署合同后可办理设计变更及补充协议。', 'contract');
  const ch = selected(c, record);
  if (['change-detail', 'change-approval', 'amendment', 'change-confirmation'].includes(view) && !ch) return b('变更记录不存在或尚未提交。', 'change-list');
  if (view === 'amendment' && !['approved', 'signed', 'confirmed', 'completed'].includes(ch.status)) return b('请先完成本次变更审批。', 'change-detail');
  if (view === 'change-confirmation' && !ch.agreementId) return b('双方签署补充协议后才能登记变更成果确认。', 'amendment');
  if (['supplement-handover', 'supplement-receive', 'start-check', 'start-receipt'].includes(view) && !c.acceptance) return b('请先完成工程师的工程接收。', 'receive');
  if (view === 'supplement-receive' && !list(c, 'supplements').some(x => drawing(c) && x.drawingVersion === drawing(c).version)) return b('本版资料尚未提交补充交接。', 'supplement-handover');
  if (view === 'start-receipt' && !validStart(c)) return b('当前资料尚未通过开工条件核查，或核查后的版本已变化。', 'start-check');
  return null;
}
function validateLegacy(view, c, f) {
  if (view === 'contract-prepare' && c.contract) throw new Error('已签合同已归档，请通过设计变更及补充协议办理，不能覆盖原件。');
  if (['payment-first', 'payment-tail'].includes(view) && !(view === 'payment-first' ? c.firstPayment : c.tailPayment)) {
    const order = service(c); requireValue(order, '请先进入设计服务单，完成服务费核定。');
    const stage=view==='payment-first'?'first':'tail';requireValue(((order.financeStates||{})[stage]||{}).status!=='需补充','该付款事项已退回，请先补充服务约定资料。');
    const expected = view === 'payment-first' ? order.first : order.tail;
    requireValue(validDate(f.paymentDate), '请选择有效实际付款日期。');
    const reference=String(f.transaction||'').trim();requireValue(!B.referenceUsed('expense',reference), '该付款流水已登记，请核对原付款。');
    requireValue(cents(f.amount || 0) === Math.round(expected * 100), '付款金额须与本版服务单核定金额一致：￥' + B.money(expected) + '。');
  }
  const ch = activeChange(c);
  if (view === 'documents' && ch) requireValue(ch.status === 'confirmed', '请先完成变更审批、补充协议签署和变更成果客户确认，再补传新图。');
  if (view === 'deliverable' && ch) throw new Error('存在设计变更，请从设计变更页面办理并保留原成果。');
  if (view === 'handover' && ch) requireValue(ch.status === 'confirmed' && drawing(c) && drawing(c).changeId === ch.id && approvedReview(c), '变更图纸需确认、补传并通过专业审核后再移交。');
}
function afterLegacy(view, c) {
  ensure(c);
  if (view === 'payment-first' || view === 'payment-tail') { const order = service(c), payment = c[view === 'payment-first' ? 'firstPayment' : 'tailPayment']; if (order && payment) payment.serviceVersion = order.version; }
  if (view === 'documents') { const ch = activeChange(c); if (ch && ch.status === 'confirmed') drawing(c).changeId = ch.id; }
  if (view === 'receive' && c.acceptance) {
    const ch = activeChange(c); c.acceptance.drawingVersion = c.handover.drawingVersion;
    if (ch && drawing(c) && drawing(c).changeId === ch.id && c.handover.drawingVersion === drawing(c).version) { ch.status = 'completed'; ch.completedDate = B.date(); }
  }
}
function transition(c, action, f, record) {
  ensure(c); const ch = selected(c, record), d = drawing(c), r = review(c);
  if (action === 'service') {
    requireValue(c.request, '请先提交电子设计需求。');
    requireValue(!c.firstPayment && !c.tailPayment, '付款办理后服务费核定版本已锁定，请保留原服务单。');
    const total = cents(f.serviceTotal); requireValue(total > 0, '请填写大于零的设计服务费总额。');
    requireValue(f.serviceBasis.trim() && f.serviceVerifier.trim() && validDate(f.serviceDate) && f.serviceFile && f.serviceChecked, '请填写核定依据、核定人、日期，上传约定文件并完成核对。');
    const first = Math.round(total / 2), order = { id: 'service-' + (c.serviceOrders.length + 1), version: c.serviceOrders.length + 1, total: total / 100, first: first / 100, tail: (total - first) / 100, basis: f.serviceBasis.trim(), verifier: f.serviceVerifier.trim(), date: f.serviceDate, file: B.clone(f.serviceFile), requestVersion: c.request.version };
    c.serviceOrders.push(order); return { event: '核定设计服务费 V' + order.version, next: 'service-order' };
  }
  if (action === 'review-submit') {
    requireValue(d && (!r || r.status !== 'pending'), '当前施工图已在审核中，请勿重复送审。');
    requireValue(!r || r.status !== 'passed', '本版施工图已通过审核；文件变化请补传新版本。');
    requireValue(!r || r.status !== 'returned', '本版已退回，请补传修订后的新版本再送审。');
    requireValue(f.reviewSubmitNote.trim() && f.reviewer.trim() && f.reviewReady, '请填写送审说明、审核人，并核对资料完整性。');
    c.reviews.push({ id: 'review-' + (c.reviews.length + 1), drawingVersion: d.version, file: B.clone(d.file), changeId: d.changeId || '', reviewer: f.reviewer.trim(), submitDate: B.date(), note: f.reviewSubmitNote.trim(), status: 'pending' });
    return { event: '施工图 ' + d.version + ' 送审', next: 'drawing-review' };
  }
  if (action === 'review-record') {
    requireValue(r && r.status === 'pending', '本版没有待办理的专业审核。');
    requireValue(['审核通过', '退回修改'].includes(f.reviewResult) && f.reviewOpinion.trim() && validDate(f.reviewDate) && f.reviewFile && f.reviewChecked, '请填写审核结论、意见和日期，上传审核记录并完成核对。');
    Object.assign(r, { status: f.reviewResult === '审核通过' ? 'passed' : 'returned', opinion: f.reviewOpinion.trim(), date: f.reviewDate, report: B.clone(f.reviewFile) });
    return { event: '登记施工图专业审核：' + f.reviewResult, next: 'drawing-review' };
  }
  if (action === 'change-new') {
    requireValue(c.contract && !activeChange(c), '请先完成当前变更，勿重复提交并行变更。');
    const delta = cents(f.changeDelta || 0, true), total = Math.round(effectiveTotal(c) * 100) + delta;
    requireValue(total > 0 && /^\d{1,3}$/.test(String(f.changeDays)), '请核对变更后的合同金额及工期调整天数（0–999）。');
    requireValue(f.changeReason.trim() && f.changeScope.trim() && f.changeApplicant.trim() && validDate(f.changeDate) && f.changeFile && f.changeChecked, '请完整填写变更原因、范围、申请人、日期和附件，并确认影响。');
    const version = c.changes.length + 1;
    c.changes.push({ id: 'change-' + version, version, reason: f.changeReason.trim(), scope: f.changeScope.trim(), applicant: f.changeApplicant.trim(), date: f.changeDate, delta: delta / 100, days: Number(f.changeDays), total: total / 100, file: B.clone(f.changeFile), status: 'submitted', baseline: { contractVersion: c.contract.version, contractTotal: c.contract.total, effectiveTotal: effectiveTotal(c), agreementVersion: (last(c, 'agreements') || {}).version || 0, releaseVersion: (c.release || {}).version || null, drawingVersion: d ? d.version : '', acceptance: B.clone(c.acceptance) } });
    return { event: '提交设计变更 V' + version, next: 'change-detail' };
  }
  requireValue(ch || !['change-approval', 'amendment', 'change-confirmation'].includes(action), '变更记录不存在。');
  if (action === 'change-approval') {
    requireValue(ch.status === 'submitted', '本次变更已经审批，不得覆盖原审批记录。');
    requireValue(['同意变更', '退回补充'].includes(f.changeDecision) && f.changeApprover.trim() && f.changeOpinion.trim() && validDate(f.changeApprovalDate) && f.changeApprovalFile && f.changeApprovalChecked, '请填写审批人、意见与日期，上传审批依据并核对。');
    ch.approval = { decision: f.changeDecision, approver: f.changeApprover.trim(), opinion: f.changeOpinion.trim(), date: f.changeApprovalDate, file: B.clone(f.changeApprovalFile) };
    ch.status = f.changeDecision === '同意变更' ? 'approved' : 'returned';
    return { event: '设计变更审批：' + f.changeDecision, next: 'change-detail', record: ch.id };
  }
  if (action === 'amendment') {
    requireValue(!Number(ch.delta)||require('../../utils/commercial-approval').approved(c,ch.id),'变更金额须先经老板审批。');
    requireValue(ch.status === 'approved' && !ch.agreementId, '请先审批变更，已签补充协议不能重复登记。');
    requireValue(f.agreementNo.trim() && !c.agreements.some(x => x.number === f.agreementNo.trim()) && validDate(f.agreementDate) && f.agreementFile && f.agreementChecked, '请填写唯一的协议编号、签署日期，上传双方已签文件并核对。');
    const agreement = { id: 'agreement-' + (c.agreements.length + 1), version: c.agreements.length + 1, changeId: ch.id, number: f.agreementNo.trim(), date: f.agreementDate, file: B.clone(f.agreementFile), baseContractVersion: c.contract.version, previousAgreementVersion: ch.baseline.agreementVersion, delta: ch.delta, total: ch.total, days: ch.days };
    c.agreements.push(agreement); ch.agreementId = agreement.id; ch.status = 'signed';
    return { event: '登记双方已签补充协议 ' + agreement.number, next: 'change-detail', record: ch.id };
  }
  if(action==='change-confirmation'){requireValue(ch.agreementId,'请先签署补充协议');const event=require('../../utils/signoff').process(c,ch,f);return {event,next:ch.confirmation?'documents':'change-confirmation',record:ch.id};}
  if (action === 'supplement-handover') {
    requireValue(c.acceptance && d && approvedReview(c), '请先完成原工程接收，并取得本版施工图专业审核通过记录。');
    requireValue(c.copies.some(x => x.version === d.version), '请先留存本版施工图抄送记录。');
    requireValue(!coverage(c), '本版资料已完成接收，无需重复交接。');
    requireValue(!c.supplements.some(x => x.drawingVersion === d.version && x.status === 'pending'), '本版补充交接已经提交，请等待接收。');
    const pending = activeChange(c); requireValue(!pending || pending.status === 'confirmed' && d.changeId === pending.id, '变更成果确认及本版图纸关联尚未完成。');
    requireValue(f.supplementManager.trim() && f.supplementNote.trim() && validDate(f.supplementDate), '请填写接收经理、交底说明和日期。');
    c.supplements.push({ id: 'supplement-' + (c.supplements.length + 1), version: c.supplements.length + 1, drawingVersion: d.version, reviewId: approvedReview(c).id, changeId: d.changeId || '', manager: f.supplementManager.trim(), note: f.supplementNote.trim(), date: f.supplementDate, status: 'pending', files: { drawing: B.clone(d.file), agreement: pending ? B.clone(c.agreements.find(x => x.id === pending.agreementId)) : null, review: B.clone(approvedReview(c).report), result: pending ? B.clone(pending.result) : null, confirmation: pending ? B.clone(pending.confirmation.file) : null } });
    return { event: '提交补充资料交接 ' + d.version, next: 'supplement-receive' };
  }
  if (action === 'supplement-receive') {
    const transfer = B.latest(c.supplements.filter(x => d && x.drawingVersion === d.version));
    requireValue(transfer && transfer.status === 'pending' && approvedReview(c) && approvedReview(c).id === transfer.reviewId, '本版补充交接尚未提交，或审核版本已变化。');
    requireValue(f.supplementDrawingChecked && f.supplementAgreementChecked && f.supplementBriefChecked && validDate(f.supplementReceiveDate), '请完成全部补充资料接收核查并填写日期。');
    const actual=B.Access.context();transfer.acceptance = { manager: actual.active?actual.name:transfer.manager, managerId:actual.active?actual.staffId:transfer.managerId, date: f.supplementReceiveDate }; transfer.status = 'accepted';
    const linked = c.changes.find(x => x.id === transfer.changeId); if (linked) { linked.status = 'completed'; linked.completedDate = f.supplementReceiveDate; }
    return { event: '工程师接收补充资料 ' + transfer.drawingVersion, next: 'start-check' };
  }
  if (action === 'start-check') {
    requireValue(c.acceptance && approvedReview(c) && coverage(c), '请完成工程接收、本版专业审核及本版资料交接。');
    requireValue(!activeChange(c), '存在未完成的设计变更，请完成协议、确认及资料交接。');
    requireValue(f.startSiteChecked && f.startTeamChecked && f.startDrawingChecked && f.startMilestoneChecked && f.startBriefChecked && f.startChecker.trim() && f.startNote.trim() && validDate(f.startDate) && f.startFile, '请逐项核对现场、人员、图纸、约定节点和交底，补齐核查人、记录、日期及凭据。');
    requireValue(!validStart(c), '本版开工条件已核查，请查看回执。');
    c.startChecks.push({ version: c.startChecks.length + 1, fingerprint: fingerprint(c), drawingVersion: d.version, reviewId: approvedReview(c).id, coverage: B.clone(coverage(c)), agreementVersion: (last(c, 'agreements') || {}).version || 0, checker: f.startChecker.trim(), date: f.startDate, note: f.startNote.trim(), file: B.clone(f.startFile), checks: ['现场条件', '人员安排', '审核图纸', '约定节点', '技术交底'], stage: '待开工' });
    return { event: '开工条件核查通过，工程仍待开工', next: 'start-receipt' };
  }
  throw new Error('不支持的办理事项');
}
const views = ['service-order', 'service-prepare', 'drawing-review', 'review-submit', 'review-record', 'change-list', 'change-new', 'change-detail', 'change-approval', 'amendment', 'change-confirmation', 'supplement-handover', 'supplement-receive', 'start-check', 'start-receipt'];
function submit(page, c, f) {
  const view = page.data.view; if (!views.includes(view)) return false;
  const ch = selected(c, page.options.record), r = review(c), order = service(c);
  let target = '';
  if (view === 'service-prepare' && (c.firstPayment || c.tailPayment)) target = 'service-order';
  if (view === 'service-order') target = !order ? 'service-prepare' : !c.firstPayment ? 'payment-first' : !c.tailPayment ? (c.tailApplication ? 'payment-tail' : 'formal-progress') : 'hub';
  if (view === 'drawing-review') target = !r ? 'review-submit' : r.status === 'pending' ? 'review-record' : r.status === 'returned' ? 'documents' : !c.acceptance?'handover':!coverage(c)?'supplement-handover':'start-check';
  if (view === 'change-list') target = activeChange(c) ? 'change-detail' : 'change-new';
  if (view === 'change-detail') target = ({ submitted: 'change-approval', approved: 'amendment', signed: 'change-confirmation', confirmed: !drawing(c)||drawing(c).changeId!==ch.id?'documents':!approvedReview(c)?'drawing-review':c.acceptance?'supplement-handover':'handover', returned: 'change-new', completed: 'start-check' })[ch.status];
  if (view === 'change-approval' && ch.status !== 'submitted') target = 'change-detail';
  if (view === 'amendment' && ch.agreementId) target = 'change-detail';
  if (view === 'change-confirmation' && ch.confirmation) target = 'documents';
  if (view === 'review-record' && r && r.status !== 'pending') target = 'drawing-review';
  if (view === 'supplement-handover' && coverage(c)) target = 'start-check';
  if (view === 'supplement-handover' && !target && list(c, 'supplements').some(x => drawing(c) && x.drawingVersion === drawing(c).version && x.status === 'pending')) target = 'supplement-receive';
  if (view === 'supplement-receive' && coverage(c)) target = 'start-check';
  if (view === 'start-check' && validStart(c)) target = 'start-receipt';
  if (view === 'start-receipt') target = 'hub';
  if (target && !B.Access.workflow(target,c,false)) {page.setData({busy:false});wx.switchTab({url:'/pages/'+B.Access.home()+'/index'});return true;}
  if (target) { page.setData({ busy: false }); page.openView(target, true, null, ['change-detail','change-approval','amendment','change-confirmation'].includes(target) && ch ? ch.id : ''); return true; }
  const next = B.clone(c), result = transition(next, view === 'service-prepare' ? 'service' : view, f, page.options.record);
  if (page.update(x => {for(const key of Object.keys(next))if(JSON.stringify(next[key])!==JSON.stringify(c[key])){if(Array.isArray(next[key])&&Array.isArray(c[key])){const old=c[key];x[key]=next[key].map(row=>{const prior=old.find(r=>r.id&&r.id===row.id),raw=x[key].find(r=>r.id&&r.id===row.id);if(!prior||!raw)return row;const patch={};for(const k of Object.keys(row))if(JSON.stringify(row[k])!==JSON.stringify(prior[k]))patch[k]=row[k];return Object.assign({},raw,patch);});}else x[key]=next[key];}}, result.event)) {if(B.Access.workflow(result.next,c,false))page.openView(result.next,true,null,result.record||'');else wx.switchTab({url:'/pages/'+B.Access.home()+'/index'});}
  return true;
}
module.exports = { list, last, selected, activeChange, drawing, service, review, approvedReview, coverage, validStart, effectiveTotal, context, block, validateLegacy, afterLegacy, transition, submit, views };
