// Collection commissions are fixed by the boss, then released from original bank facts.
const X = require('./access');
const O = require('./organization');
const Ops = require('./operations');
const F = require('./finance-source');
const parseCents = require('./money').parseCents;

const clone = require('./clone');
const assert = X.assert;
const STAGES = ['start', 'topping'];
const labels = { start:'开工款', topping:'封顶款' };
const policyId = projectId => 'collection-commission-policy:' + projectId;
const payableId = (projectId, stage) => 'collection-commission-payable:' + projectId + ':' + stage;

function init(s) {
  s.operations = s.operations || {};
  s.operations.collectionCommissionPolicies = s.operations.collectionCommissionPolicies || [];
  return s.operations.collectionCommissionPolicies;
}

function cents(value) {
  assert(/^\d+(\.\d{1,2})?$/.test(String(value)) && Number(value) > 0 && Number(value) <= 100000000, '提成总额须大于0、最多两位小数且不超过一亿元');
  return parseCents(value);
}

function originalProject(s, id) {
  const project = Ops.project(id, s);
  assert(project.engineering?.recordFlow && project.engineering.companyId === s.companyId, '仅支持本组织真实工程记录项目');
  return project;
}

function policyFor(s, id) {
  return init(s).find(row => row.id === policyId(id));
}

function receiptAmount(row) {
  return Number(row.revisions?.at(-1)?.amount ?? row.amount ?? 0);
}

function stageFacts(s, projectId, stage) {
  assert(STAGES.includes(stage), '请选择开工款或封顶款阶段');
  const receivable = Ops.receivable(s, projectId);
  const installments = (receivable.plan?.installments || []).filter(row => row.stage === stage);
  const required = installments.reduce((sum, row) => sum + Number(row.amount || 0), 0);
  const receipts = (s.receipts || []).filter(row => row.projectId === projectId && row.receiptStage === stage && ['已核算', '已备案'].includes(row.status));
  const receiptIds = new Set(receipts.map(row => row.id));
  const refunds = (s.refunds || []).filter(row => receiptIds.has(row.receiptId) && row.status === '已核实');
  const received = Math.max(0, receipts.reduce((sum, row) => sum + receiptAmount(row), 0) - refunds.reduce((sum, row) => sum + Number(row.amount || 0), 0));
  const signature = JSON.stringify([
    receivable.plan?.id || '', receivable.plan?.version || 0, stage, required,
    receipts.map(row => [row.id, receiptAmount(row), row.status, row.revisions?.length || 0]).sort((a,b) => a[0].localeCompare(b[0])),
    refunds.map(row => [row.id, row.receiptId, row.amount, row.status]).sort((a,b) => a[0].localeCompare(b[0]))
  ]);
  return { stage, required, received, eligible:required > 0 && received >= required, signature, receiptIds:[...receiptIds].sort() };
}

function assertVersion(policy, expected) {
  assert(Number(expected) === Number(policy?.version || 0), '提成规则版本已变化，请刷新后重试');
}

function policyPayables(s, policy) {
  return (s.payables || []).filter(row => row.commissionSource?.policyId === policy.id);
}

function apply(s, action, id, form = {}) {
  if (!['commission-policy', 'commission-release', 'commission-reconcile'].includes(action)) return null;
  const actor = X.context();
  assert(actor.active && actor.companyId === s.companyId, '当前组织未授权');
  const project = originalProject(s, id), policies = init(s), current = policyFor(s, id);

  if (action === 'commission-policy') {
    assert(actor.role === 'boss', '仅本组织实际老板可以确认项目提成规则');
    assertVersion(current, form.expectedVersion);
    const member = (s.staff || []).find(row => row.id === form.assignee && row.active !== false);
    assert(member && member.personId, '请选择本组织在岗且身份明确的提成归属人');
    assert(form.file && form.checked && String(form.note || '').trim(), '请上传提成确认依据、填写说明并完成核对');
    if (current) assert(!policyPayables(s, current).length, '原提成规则已生成应付或付款，不能覆盖');
    const total = cents(form.amount), version = (current?.version || 0) + 1;
    const confirmation = X.actor();
    const next = {
      id:policyId(id), projectId:id, engineeringId:project.engineering.id, customer:project.engineering.customer, project:project.name,
      total, assigneeId:member.id, personId:member.personId, assignee:member.name,
      stages:{ start:{ amount:Math.floor(total / 2) }, topping:{ amount:total - Math.floor(total / 2) } },
      version, history:[...(current?.history || []), ...(current ? [{ ...clone(current), history:undefined }] : [])],
      file:clone(form.file), note:String(form.note).trim(), confirmedBy:confirmation,
      actorId:confirmation.actorId, userId:confirmation.userId, companyId:confirmation.companyId, role:confirmation.role, by:confirmation.by, at:confirmation.at
    };
    if (current) policies.splice(policies.indexOf(current), 1, next); else policies.push(next);
    return { id:next.id };
  }

  assert(current, '请先由老板确认本项目提成总额及归属人');
  assertVersion(current, form.expectedVersion);
  const stage = form.stage;
  const facts = stageFacts(s, id, stage);

  if (action === 'commission-release') {
    assert(O.has(actor.role, 'finance'), '请由本组织财务按原收款事实生成提成应付');
    assert(facts.eligible, labels[stage] + '净实收尚未足额，不能生成本期提成应付');
    const idempotent = (s.payables || []).find(row => row.id === payableId(id, stage));
    if (idempotent) {
      assert(idempotent.commissionSource?.policyId === current.id && idempotent.commissionSource?.policyVersion === current.version, '稳定应付编号已被其他规则占用');
      return { id:idempotent.id };
    }
    const amount = current.stages[stage].amount;
    const payable = {
      id:payableId(id, stage), projectId:id, customer:project.engineering.customer, name:'项目回款提成', stage:labels[stage] + '足额到账后提成',
      total:amount, recipient:current.assignee, agreement:clone(current.file), applicantId:current.actorId, applicantPersonId:current.confirmedBy?.personId,
      commissionSource:{ kind:'collection-commission', policyId:current.id, policyVersion:current.version, stage, receiptSignature:facts.signature, receiptIds:facts.receiptIds.slice() },
      status:'待办理'
    };
    payable.approval = { id:'collection-commission-approval:' + id + ':' + stage, status:'approved', role:'boss', signature:F.signature(payable), actorId:current.actorId, personId:current.confirmedBy?.personId, userId:current.userId, companyId:current.companyId, by:current.by, at:current.at, note:current.note };
    payable.approvalValid = true;
    s.payables.push(payable);
    return { id:payable.id };
  }

  assert(actor.role === 'boss', '仅本组织实际老板可以核对提成收款变化');
  assert(form.file && form.checked && String(form.note || '').trim(), '请上传人工核对依据、填写说明并完成核对');
  assert(facts.eligible, labels[stage] + '退款、冲正或更正后净实收仍未足额，不能确认继续付款');
  const payable = (s.payables || []).find(row => row.id === payableId(id, stage));
  assert(payable && payable.commissionSource?.policyId === current.id, '本阶段尚未生成原提成应付');
  payable.reconciliations = (payable.reconciliations || []).concat({ previousReceiptSignature:payable.commissionSource.receiptSignature, receiptSignature:facts.signature, receiptIds:facts.receiptIds.slice(), file:clone(form.file), note:String(form.note).trim(), ...X.actor() });
  payable.commissionSource.receiptSignature = facts.signature;
  payable.commissionSource.receiptIds = facts.receiptIds.slice();
  return { id:payable.id };
}

function assertPayment(s, payable) {
  if (payable?.commissionSource?.kind !== 'collection-commission') return true;
  const policy = policyFor(s, payable.projectId);
  assert(policy && payable.commissionSource.policyId === policy.id && payable.commissionSource.policyVersion === policy.version, '提成规则版本已变化，请停止付款并人工核对');
  assert(payable.total === policy.stages[payable.commissionSource.stage]?.amount && payable.recipient === policy.assignee, '提成应付金额或归属人已变化，请停止付款并人工核对');
  assert(payable.approval?.role === 'boss' && payable.approval.signature === F.signature(payable), '提成应付缺少老板原规则授权');
  const facts = stageFacts(s, payable.projectId, payable.commissionSource.stage);
  assert(facts.eligible, labels[payable.commissionSource.stage] + '发生退款、冲正或更正，净实收不足，未付提成已暂停');
  assert(facts.signature === payable.commissionSource.receiptSignature, '原收款事实已发生退款、冲正或更正，请老板人工核对后继续付款');
  return true;
}

function rows(s) {
  return init(s).map(policy => {
    const project = Ops.project(policy.projectId, s);
    const stages = STAGES.map(stage => {
      const facts = stageFacts(s, policy.projectId, stage);
      const payable = (s.payables || []).find(row => row.id === payableId(policy.projectId, stage));
      const paid = payable ? F.paid(s, payable.id) : 0;
      const reviewDue = !!payable && (!facts.eligible || payable.commissionSource?.receiptSignature !== facts.signature || payable.commissionSource?.policyVersion !== policy.version);
      return {
        stage, label:labels[stage], amount:policy.stages[stage].amount, required:facts.required, received:facts.received, eligible:facts.eligible,
        payableId:payable?.id || '', paid, outstanding:Math.max(0, policy.stages[stage].amount - paid), reviewDue,
        receiptIds:facts.receiptIds, status:reviewDue?'待人工核对':paid >= policy.stages[stage].amount?'已付清':payable?'待付款':facts.eligible?'可生成应付':'待足额到账'
      };
    });
    return { id:policy.id, policy, projectId:policy.projectId, project:policy.project, customer:project.engineering?.customer || policy.customer, total:policy.total, assigneeId:policy.assigneeId, personId:policy.personId, assignee:policy.assignee, version:policy.version, stages };
  });
}

module.exports = { STAGES, apply, rows, assertPayment, stageFacts, policyFor };
