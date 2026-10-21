const B = require('../../utils/business');
const list = (c, key) => c[key] || [];
const last = (c, key) => B.latest(list(c, key));
const drawing = c => B.latest(c.drawings);
const service = c => last(c, 'serviceOrders');
const review = c => { const d = drawing(c); return d && B.latest(list(c, 'reviews').filter(x => x.drawingVersion === d.version)); };
const approvedReview = c => { const r = review(c); return r && r.status === 'passed' ? r : null; };
function requireValue(ok, message) { if (!ok) throw new Error(message); }
function validDate(value) { return /^\d{4}-\d{2}-\d{2}$/.test(value || '') && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value; }
function cents(value, signed = false) { requireValue((signed ? /^-?\d+(\.\d{1,2})?$/ : /^\d+(\.\d{1,2})?$/).test(String(value)) && Number.isFinite(Number(value)) && Math.abs(Number(value)) <= 100000000, '金额最多两位小数，且不得超过一亿元。'); return Math.round(Number(value) * 100); }
function ensure(c) { for (const key of ['serviceOrders', 'reviews', 'startChecks']) c[key] = c[key] || []; }
function originalHandover(c) { return [c.handover].concat(list(c, 'handoverHistory')).find(x => x && c.acceptance && x.version === c.acceptance.handoverVersion); }
function coverage(c) {
  const d = drawing(c); if (!d || !c.acceptance) return null;
  const original = originalHandover(c);
  return original && original.drawingVersion === d.version ? { key: 'handover-' + original.version, date: c.acceptance.date, manager: c.acceptance.manager } : null;
}
function fingerprint(c) { const d = drawing(c), r = approvedReview(c), covered = coverage(c); return [d && d.version, r && r.id, covered && covered.key].join('|'); }
function validStart(c) { const check = last(c, 'startChecks'); return check && approvedReview(c) && coverage(c) && check.fingerprint === fingerprint(c) ? check : null; }
function effectiveTotal(c) { return c.contract ? c.contract.total : 0; }
function context(view, c, record) {
  if (['review-submit', 'review-record'].includes(view)) return (drawing(c) || {}).version || 'none';
  if (view === 'start-check') return fingerprint(c);
  return (c.request || {}).version || 'none';
}
function block(view, c, record) {
  const b = (message, target) => ({ message, target, label: '去补齐前置资料' });
  if (['service-order', 'service-prepare'].includes(view) && !c.request) return b('请先提交电子设计需求，再核定设计服务费。', 'request');
  if (['drawing-review', 'review-submit', 'review-record'].includes(view) && !drawing(c)) return b('请先补传有明确版本号的施工图。', 'documents');
  if (view === 'review-record' && !review(c)) return b('本版施工图尚未送审。', 'review-submit');
  if (view === 'review-submit' && review(c)) return b(review(c).status === 'returned' ? '本版已退回，请补传修订后的新版本再送审。' : '本版已有专业审核记录，请查看当前办理状态。', review(c).status === 'returned' ? 'documents' : 'drawing-review');
  if (['start-check', 'start-receipt'].includes(view) && !c.acceptance) return b('请先完成工程负责人的工程接收。', 'receive');
  if (view === 'start-receipt' && !validStart(c)) return b('当前资料尚未通过开工条件核查，或核查后的版本已变化。', 'start-check');
  return null;
}
function validateLegacy(view, c, f) {
  if (view === 'contract-prepare' && c.contract) throw new Error('已签合同已归档，不能覆盖原件；合同调整请线下办理并重新登记。');
  if (['payment-first', 'payment-tail'].includes(view) && !(view === 'payment-first' ? c.firstPayment : c.tailPayment)) {
    const order = service(c); requireValue(order, '请先进入设计服务单，完成服务费核定。');
    const stage=view==='payment-first'?'first':'tail';requireValue(((order.financeStates||{})[stage]||{}).status!=='需补充','该付款事项已退回，请先补充服务约定资料。');
    const expected = view === 'payment-first' ? order.first : order.tail;
    requireValue(validDate(f.paymentDate), '请选择有效实际付款日期。');
    const reference=String(f.transaction||'').trim();requireValue(!B.referenceUsed('expense',reference), '该付款流水已登记，请核对原付款。');
    requireValue(cents(f.amount || 0) === Math.round(expected * 100), '付款金额须与本版服务单核定金额一致：￥' + B.money(expected) + '。');
  }
}
function afterLegacy(view, c) {
  ensure(c);
  if (view === 'payment-first' || view === 'payment-tail') { const order = service(c), payment = c[view === 'payment-first' ? 'firstPayment' : 'tailPayment']; if (order && payment) payment.serviceVersion = order.version; }
  if (view === 'receive' && c.acceptance) c.acceptance.drawingVersion = c.handover.drawingVersion;
}
function transition(c, action, f, record) {
  ensure(c); const d = drawing(c), r = review(c);
  if (action === 'service') {
    requireValue(c.request, '请先提交电子设计需求。');
    requireValue(!c.firstPayment && !c.tailPayment, '付款办理后服务费核定版本已锁定，请保留原服务单。');
    const total = cents(f.serviceTotal); requireValue(total > 0, '请填写大于零的设计服务费总额。');
    requireValue(f.serviceBasis.trim() && f.serviceVerifier.trim() && validDate(f.serviceDate) && f.serviceFile && f.serviceChecked, '请填写核定依据、核定人、日期，上传约定文件并完成核对。');
    const local=c.designOrganizationId===(c.companyId||'quanzhou');requireValue(!local||String(f.serviceRecipient||'').trim(),'请填写本店设计服务实际收款人，不需要登记组织内部往来到款。');const first = Math.round(total / 2), order = { id: 'service-' + (c.serviceOrders.length + 1), version: c.serviceOrders.length + 1, total: total / 100, recipient:local?f.serviceRecipient.trim():'总部设计服务中心', first: first / 100, tail: (total - first) / 100, basis: f.serviceBasis.trim(), verifier: f.serviceVerifier.trim(), date: f.serviceDate, file: B.clone(f.serviceFile), requestVersion: c.request.version };
    c.serviceOrders.push(order); return { event: '核定设计服务费 V' + order.version, next: 'service-order' };
  }
  if (action === 'review-submit') {
    requireValue(d && (!r || r.status !== 'pending'), '当前施工图已在审核中，请勿重复送审。');
    requireValue(!r || r.status !== 'passed', '本版施工图已通过审核；文件变化请补传新版本。');
    requireValue(!r || r.status !== 'returned', '本版已退回，请补传修订后的新版本再送审。');
    requireValue(f.reviewSubmitNote.trim() && f.reviewer.trim() && f.reviewReady, '请填写送审说明、审核人，并核对资料完整性。');
    c.reviews.push({ id: 'review-' + (c.reviews.length + 1), drawingVersion: d.version, file: B.clone(d.file), reviewer: f.reviewer.trim(), submitDate: B.date(), note: f.reviewSubmitNote.trim(), status: 'pending' });
    return { event: '施工图 ' + d.version + ' 送审', next: 'drawing-review' };
  }
  if (action === 'review-record') {
    requireValue(r && r.status === 'pending', '本版没有待办理的专业审核。');
    requireValue(['审核通过', '退回修改'].includes(f.reviewResult) && f.reviewOpinion.trim() && validDate(f.reviewDate) && f.reviewFile && f.reviewChecked, '请填写审核结论、意见和日期，上传审核记录并完成核对。');
    Object.assign(r, { status: f.reviewResult === '审核通过' ? 'passed' : 'returned', opinion: f.reviewOpinion.trim(), date: f.reviewDate, report: B.clone(f.reviewFile) });
    return { event: '登记施工图专业审核：' + f.reviewResult, next: 'drawing-review' };
  }
  if (action === 'start-check') {
    requireValue(c.acceptance && approvedReview(c) && coverage(c), '请完成工程接收、本版专业审核及本版资料交接。');
    requireValue(f.startSiteChecked && f.startTeamChecked && f.startDrawingChecked && f.startMilestoneChecked && f.startBriefChecked && f.startChecker.trim() && f.startNote.trim() && validDate(f.startDate) && f.startFile, '请逐项核对现场、人员、图纸、约定节点和交底，补齐核查人、记录、日期及凭据。');
    requireValue(!validStart(c), '本版开工条件已核查，请查看回执。');
    c.startChecks.push({ version: c.startChecks.length + 1, fingerprint: fingerprint(c), drawingVersion: d.version, reviewId: approvedReview(c).id, coverage: B.clone(coverage(c)), checker: f.startChecker.trim(), date: f.startDate, note: f.startNote.trim(), file: B.clone(f.startFile), checks: ['现场条件', '人员安排', '审核图纸', '约定节点', '技术交底'], stage: '待开工' });
    return { event: '开工条件核查通过，工程仍待开工', next: 'start-receipt' };
  }
  throw new Error('不支持的办理事项');
}
const views = ['service-order', 'service-prepare', 'drawing-review', 'review-submit', 'review-record', 'start-check', 'start-receipt'];
function submit(page, c, f) {
  const view = page.data.view; if (!views.includes(view)) return false;
  const r = review(c), order = service(c);
  let target = '';
  if (view === 'service-prepare' && (c.firstPayment || c.tailPayment)) target = 'service-order';
  if (view === 'service-order') target = !order ? 'service-prepare' : !c.firstPayment ? 'payment-first' : !c.tailPayment ? (c.tailApplication ? 'payment-tail' : 'formal-progress') : 'hub';
  if (view === 'drawing-review') target = !r ? 'review-submit' : r.status === 'pending' ? 'review-record' : r.status === 'returned' ? 'documents' : !c.acceptance ? 'handover' : 'start-check';
  if (view === 'review-record' && r && r.status !== 'pending') target = 'drawing-review';
  if (view === 'start-check' && validStart(c)) target = 'start-receipt';
  if (view === 'start-receipt') target = 'hub';
  if (target && !B.Access.workflow(target,c,false)) {page.setData({busy:false});wx.switchTab({url:'/pages/'+B.Access.home()+'/index'});return true;}
  if (target) { page.setData({ busy: false }); page.openView(target, true); return true; }
  const next = B.clone(c), result = transition(next, view === 'service-prepare' ? 'service' : view, f, page.options.record);
  if (page.update(x => {for(const key of Object.keys(next))if(JSON.stringify(next[key])!==JSON.stringify(c[key])){if(Array.isArray(next[key])&&Array.isArray(c[key])){const old=c[key];x[key]=next[key].map(row=>{const prior=old.find(r=>r.id&&r.id===row.id),raw=x[key].find(r=>r.id&&r.id===row.id);if(!prior||!raw)return row;const patch={};for(const k of Object.keys(row))if(JSON.stringify(row[k])!==JSON.stringify(prior[k]))patch[k]=row[k];return Object.assign({},raw,patch);});}else x[key]=next[key];}}, result.event)) {if(B.Access.workflow(result.next,c,false))page.openView(result.next,true,null,result.record||'');else wx.switchTab({url:'/pages/'+B.Access.home()+'/index'});}
  return true;
}
module.exports = { list, last, drawing, service, review, approvedReview, coverage, validStart, effectiveTotal, context, block, validateLegacy, afterLegacy, transition, submit, views };
