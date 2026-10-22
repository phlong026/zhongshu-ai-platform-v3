// Read-only owner operating report. Money values stay as integer cents.
const A = require('./account');
const F = require('./finance-source');

const clone = value => JSON.parse(JSON.stringify(value));
const value = row => row.revisions?.length ? row.revisions.at(-1).amount : row.amount;
const cashDate = row => F.cashDate(row);
const validReceipt = (row, end) => ['已核算', '已备案'].includes(row.status) && cashDate(row) <= end;
const validPayment = (row, end) => row.status === '已核实' && row.date <= end;
const endOfMonth = period => new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
const monthBefore = period => {
  const [year, month] = period.split('-').map(Number);
  return month === 1 ? year - 1 + '-12' : year + '-' + String(month - 1).padStart(2, '0');
};
const monthsThrough = period => {
  const out = [];
  let cursor = period;
  for (let index = 0; index < 6; index += 1) { out.unshift(cursor); cursor = monthBefore(cursor); }
  return out;
};
const sum = rows => rows.reduce((total, row) => total + row, 0);
const basisPoints = (numerator, denominator) => denominator > 0 ? Math.round(numerator * 10000 / denominator) : null;
const validRefund = (row, end) => row.status === '已核实' && row.date <= end;

function refunded(state, receipt, end) {
  return Math.min(value(receipt), sum((state.refunds || []).filter(row => row.receiptId === receipt.id && validRefund(row, end)).map(row => row.amount)));
}

function netReceipt(state, receipt, end) {
  return Math.max(0, value(receipt) - refunded(state, receipt, end));
}

function group(rows, keyFor) {
  const totals = new Map();
  for (const row of rows) {
    const key = keyFor(row) || '未分类';
    totals.set(key, (totals.get(key) || 0) + row.amountCents);
  }
  return [...totals].map(([key, amountCents]) => ({ key, label: key, amountCents })).sort((a, b) => b.amountCents - a.amountCents || a.key.localeCompare(b.key));
}

function incomeKind(row) {
  if (row.purposeCode) return row.purposeCode;
  if (row.source?.module === 'engineering') return '工程回款';
  if (row.source?.module === 'intercompany') return '总部门店往来';
  if (row.source?.kind === 'design') return '设计服务';
  return '未分类';
}

function paidKind(state, row) {
  if (row.refund) return '退款';
  const payable = (state.payables || []).find(item => item.id === row.payableId);
  const expense = payable?.expenseId && (state.operations?.expenses || []).find(item => item.id === payable.expenseId);
  if (expense?.purpose || payable?.purpose) return expense?.purpose || payable.purpose;
  if (payable?.source?.kind === 'supply') return '材料采购';
  if (payable?.source?.kind === 'design') return '设计服务';
  if (String(payable?.id || '').startsWith('payroll-payable:')) return '人员绩效';
  return '未分类';
}

function raw(companyId) {
  return {
    customers: (wx.getStorageSync('zhongshu-business-v3')?.customers || []).filter(row => (row.companyId || 'quanzhou') === companyId),
    projects: (wx.getStorageSync('zhongshu-engineering-v1')?.projects || []).filter(row => (row.companyId || 'quanzhou') === companyId)
  };
}

function projectIds(state) {
  const source = raw(state.companyId);
  return [...new Set([
    ...source.customers.map(row => row.projectId),
    ...source.projects.map(row => row.projectId || row.id),
    ...(state.operations?.plans || []).map(row => row.projectId),
    ...(state.receipts || []).map(row => row.projectId)
  ].filter(id => id && !String(id).startsWith('company:')))];
}

function approvedPlan(state, projectId, asOf) {
  const Ops = require('./operations');
  const signature = Ops.contract(Ops.project(projectId, state)).signature;
  const historical = String(asOf).slice(0, 7) < A.localDate().slice(0, 7);
  return (state.operations?.plans || []).filter(row => row.projectId === projectId && row.status === 'approved' && (historical ? row.approval?.at && String(row.approval.at).slice(0, 10) <= asOf : row.signature === signature)).at(-1) || null;
}

function projectReceivable(state, projectId, asOf) {
  const Ops = require('./operations');
  const plan = approvedPlan(state, projectId, asOf);
  const historical = String(asOf).slice(0, 7) < A.localDate().slice(0, 7);
  const total = historical ? plan ? sum(plan.installments.map(item => item.amount)) : 0 : Ops.contract(Ops.project(projectId, state)).total;
  const recordFlow = !!Ops.project(projectId, state).engineering?.recordFlow;
  const receipts = (state.receipts || []).filter(row => (!recordFlow || ['start', 'topping'].includes(row.receiptStage)) && row.projectId === projectId && validReceipt(row, asOf) && row.source?.module !== 'intercompany' && !/独立设计|独立服务/.test(row.purpose || row.name || ''));
  let remainingReceipts = sum(receipts.map(row => netReceipt(state, row, asOf)));
  const stageRemaining = new Map();
  if (recordFlow) for (const row of receipts) stageRemaining.set(row.receiptStage, (stageRemaining.get(row.receiptStage) || 0) + netReceipt(state, row, asOf));
  let installments = (plan?.installments || []).slice().sort((a, b) => (a.due || '9999').localeCompare(b.due || '9999')).map(item => {
    const available = recordFlow ? stageRemaining.get(item.stage) || 0 : remainingReceipts;
    const paid = Math.min(item.amount, available);
    if (recordFlow) stageRemaining.set(item.stage, available - paid);
    else remainingReceipts -= paid;
    return { ...clone(item), paid, remaining: item.amount - paid };
  });
  if (recordFlow) {
    let surplus = sum([...stageRemaining.values()]);
    installments = installments.map(item => {
      const extra = Math.min(item.remaining, surplus);
      surplus -= extra;
      return { ...item, paid: item.paid + extra, remaining: item.remaining - extra };
    });
  }
  const received = sum(receipts.map(row => netReceipt(state, row, asOf)));
  return { projectId, plan, total, received, remaining: Math.max(0, total - received), installments };
}

function bucketFor(due, asOf) {
  if (!due) return 'undated';
  if (due >= asOf) return 'upcoming';
  const days = Math.max(1, Math.floor((Date.parse(asOf) - Date.parse(due)) / 86400000));
  if (days <= 30) return 'overdue-1-30';
  if (days <= 60) return 'overdue-31-60';
  if (days <= 90) return 'overdue-61-90';
  return 'overdue-90-plus';
}

const AGING = [
  ['upcoming', '未到期'], ['overdue-1-30', '逾期1-30天'], ['overdue-31-60', '逾期31-60天'],
  ['overdue-61-90', '逾期61-90天'], ['overdue-90-plus', '逾期90天以上'], ['undated', '未约定']
];
function aging(rows, asOf) {
  const totals = new Map(AGING.map(([key]) => [key, 0]));
  for (const row of rows) totals.set(bucketFor(row.due, asOf), totals.get(bucketFor(row.due, asOf)) + row.amountCents);
  return { totalCents: sum(rows.map(row => row.amountCents)), buckets: AGING.map(([key, label]) => ({ key, label, amountCents: totals.get(key) })) };
}

function approvedPayables(state, asOf) {
  const historical = String(asOf).slice(0, 7) < A.localDate().slice(0, 7);
  const knownDate = row => String(row.approval?.at || (state.operations?.expenses || []).find(expense => expense.id === row.expenseId)?.date || '').slice(0, 10);
  return (state.payables || []).filter(row => row.approvalValid && (historical ? knownDate(row) && knownDate(row) <= asOf : row.status !== '已付清')).map(row => {
    const paid = sum((state.payments || []).filter(payment => payment.payableId === row.id && validPayment(payment, asOf)).map(payment => payment.amount));
    return { id: row.id, due: row.due || '', amountCents: Math.max(0, row.total - paid), totalCents: row.total, paidCents: paid, source: clone(row) };
  }).filter(row => row.amountCents > 0);
}

function cashAt(state, asOf) {
  const opening = (state.operations?.funds || []).filter(row => row.date <= asOf).slice().sort((a, b) => a.date.localeCompare(b.date) || String(a.at || '').localeCompare(String(b.at || ''))).at(-1);
  if (!opening) return { cashCents: null, opening: null };
  const income = sum((state.receipts || []).filter(row => validReceipt(row, asOf) && cashDate(row) >= opening.date).map(value));
  const paid = sum([...(state.payments || []), ...(state.refunds || [])].filter(row => validPayment(row, asOf) && row.date >= opening.date).map(row => row.amount));
  return { cashCents: opening.amount + income - paid, opening };
}

function profit(state, ids, asOf) {
  const Ops = require('./operations');
  const historical = String(asOf).slice(0, 7) < A.localDate().slice(0, 7);
  const rows = ids.map(id => {
    const cost = Ops.cost(state, id);
    if (!historical) return { id, cost };
    const check = (state.operations?.costChecks || []).filter(row => row.projectId === id && row.signature === cost.signature && row.at && String(row.at).slice(0, 10) <= asOf).at(-1);
    const remaining = check ? sum(Object.values(check.remaining)) : null;
    return { id, cost: { ...cost, check, remaining, complete: !!check && !cost.unresolved, profit: check && !cost.unresolved ? Ops.contract(Ops.project(id, state)).total - cost.total - remaining : null } };
  }).filter(row => Ops.contract(Ops.project(row.id, state)).total > 0);
  const available = rows.length > 0 && rows.every(row => row.cost.complete);
  const contractCents = available ? sum(rows.map(row => Ops.contract(Ops.project(row.id, state)).total)) : null;
  const profitCents = available ? sum(rows.map(row => row.cost.profit)) : null;
  return { available, contractCents, profitCents, marginBasisPoints: available ? basisPoints(profitCents, contractCents) : null, incompleteProjects: rows.filter(row => !row.cost.complete).length };
}

function effectiveCustomers(state, period) {
  return raw(state.companyId).customers.filter(customer => (customer.follows || []).some(follow => String(follow.date || '').slice(0, 7) === period && !String(follow.note || follow.content || '').startsWith('登记客资'))).length;
}

function flowFor(state, period) {
  const receipts = (state.receipts || []).filter(row => String(cashDate(row)).slice(0, 7) === period && ['已核算', '已备案'].includes(row.status));
  const payments = (state.payments || []).filter(row => String(row.date).slice(0, 7) === period && row.status === '已核实');
  const refunds = (state.refunds || []).filter(row => String(row.date).slice(0, 7) === period && row.status === '已核实');
  const incomeCents = sum(receipts.map(value));
  const paidCents = sum(payments.map(row => row.amount)) + sum(refunds.map(row => row.amount));
  return { receipts, payments, refunds, incomeCents, paidCents, netCents: incomeCents - paidCents };
}

function analytics(state, period, today = A.localDate()) {
  const asOf = period === String(today).slice(0, 7) ? today : endOfMonth(period);
  const trend = monthsThrough(period).map(month => ({ period: month, ...flowFor(state, month) })).map(({ receipts, payments, refunds, ...row }) => row);
  const flow = flowFor(state, period);
  const incomeComposition = { totalCents: flow.incomeCents, rows: group(flow.receipts.map(row => ({ row, amountCents: value(row) })), item => incomeKind(item.row)) };
  const paidRows = flow.payments.map(row => ({ row, amountCents: row.amount })).concat(flow.refunds.map(row => ({ row: { ...row, refund: true }, amountCents: row.amount })));
  const paidComposition = { totalCents: flow.paidCents, rows: group(paidRows, item => paidKind(state, item.row)) };
  const ids = projectIds(state);
  const projectRows = ids.map(id => projectReceivable(state, id, asOf));
  const receivableRows = projectRows.flatMap(project => project.plan
    ? project.installments.filter(item => item.remaining > 0).map(item => ({ id: project.projectId + ':' + item.id, projectId: project.projectId, due: item.due || '', amountCents: item.remaining, totalCents: item.amount, paidCents: item.paid, installment: item.name }))
    : project.remaining > 0 ? [{ id: project.projectId, projectId: project.projectId, due: '', amountCents: project.remaining, totalCents: project.total, paidCents: project.received, installment: '收款计划待确认' }] : []);
  const payableRows = approvedPayables(state, asOf);
  const dueCents = sum(projectRows.flatMap(row => row.plan?.installments || []).filter(item => String(item.due || '').slice(0, 7) === period).map(item => item.amount));
  const constructionReceipt = row => row?.source?.module === 'engineering' && !/独立设计|独立服务/.test(row.purpose || row.name || '');
  const constructionReceipts = (state.receipts || []).filter(row => constructionReceipt(row) && validReceipt(row, asOf));
  const beforePeriod = endOfMonth(monthBefore(period));
  const refundCents = sum(constructionReceipts.map(row => refunded(state, row, asOf) - refunded(state, row, beforePeriod)));
  const collectedCents = Math.max(0, sum(constructionReceipts.filter(row => String(cashDate(row)).slice(0, 7) === period).map(value)) - refundCents);
  const cash = cashAt(state, asOf);
  const payableCents = sum(payableRows.map(row => row.amountCents));
  const promotionRows = (state.operations?.expenses || []).filter(row => row.projectId === 'company:' + state.companyId && row.status === 'approved' && row.purpose === '推广获客' && String(row.date).slice(0, 7) === period);
  const promotionCents = sum(promotionRows.map(row => row.amount));
  const customers = effectiveCustomers(state, period);
  return {
    period, asOf, trend, incomeComposition, paidComposition,
    receivableAging: aging(receivableRows, asOf), payableAging: aging(payableRows, asOf), receivableRows, payableRows,
    collectionRate: { numeratorCents: collectedCents, denominatorCents: dueCents, basisPoints: basisPoints(collectedCents, dueCents), available: dueCents > 0 },
    coverage: { cashCents: cash.cashCents, payableCents, milli: cash.cashCents !== null && payableCents > 0 ? Math.round(cash.cashCents * 1000 / payableCents) : null, available: cash.cashCents !== null && payableCents > 0 },
    profit: profit(state, ids, asOf),
    acquisition: { promotionCents, effectiveCustomers: customers, costCents: promotionRows.length && customers > 0 ? Math.round(promotionCents / customers) : null, available: promotionRows.length > 0 && customers > 0 },
    opening: cash.opening ? clone(cash.opening) : null
  };
}

function sourceSignature(state, period) {
  const Ops = require('./operations');
  const within = date => String(date || '').slice(0, 7) <= period;
  const expenseDate = id => (state.operations?.expenses || []).find(row => row.id === id)?.date || '';
  const approvedAt = row => row.approval?.at || expenseDate(row.expenseId);
  const sources = raw(state.companyId);
  const projects = projectIds(state).map(id => {
    const project = Ops.project(id, state), contract = Ops.contract(project), cost = Ops.cost(state, id);
    return [id, contract.signature, contract.total, cost.signature, cost.total, cost.unresolved];
  });
  const facts = {
    receipts: (state.receipts || []).filter(row => within(cashDate(row))).map(row => [row.id, row.version || 1, row.projectId || '', value(row), cashDate(row), row.status, row.receiptStage || '', row.purposeCode || '', row.purpose || '', row.name || '', row.source?.module || '', row.source?.kind || '']),
    payments: (state.payments || []).filter(row => within(row.date)).map(row => [row.id, row.payableId || '', row.amount, row.date, row.status]),
    refunds: (state.refunds || []).filter(row => within(row.date)).map(row => [row.id, row.receiptId || '', row.amount, row.date, row.status]),
    plans: (state.operations?.plans || []).filter(row => row.status === 'approved' && row.approval?.at && within(row.approval.at)).map(row => [row.id, row.projectId, row.signature, row.installments]),
    payables: (state.payables || []).filter(row => approvedAt(row) && within(approvedAt(row))).map(row => [row.id, row.total, row.due || '', row.approvalValid, row.purpose || '', row.stage || '', row.name || '', row.source?.kind || '', sum((state.payments || []).filter(payment => payment.payableId === row.id && validPayment(payment, endOfMonth(period))).map(payment => payment.amount))]),
    costs: (state.operations?.costChecks || []).filter(row => row.at && within(row.at)).map(row => [row.id, row.projectId, row.signature, row.remaining]),
    funds: (state.operations?.funds || []).filter(row => within(row.date)).map(row => [row.id, row.date, row.amount]),
    expenses: (state.operations?.expenses || []).filter(row => within(row.date)).map(row => [row.id, row.amount, row.category, row.purpose || '', row.name || '', row.status]),
    payroll: (state.operations?.payrollSettlements || []).filter(row => row.period <= period).map(row => [row.id, row.period, row.amount, row.status]),
    customers: sources.customers.map(row => [row.id, row.projectId || '', (row.follows || []).filter(follow => within(follow.date)).map(follow => [follow.id || '', follow.date || '', follow.note || follow.content || '']).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))]),
    projects
  };
  for (const rows of Object.values(facts)) rows.sort((a, b) => String(a[0]).localeCompare(String(b[0])));
  return JSON.stringify(facts);
}

function currentSnapshot(state, period) {
  const rows = (state.operations?.periodSnapshots || []).filter(row => row.period === period && row.status === 'final');
  return rows.filter(row => !rows.some(next => next.supersedes === row.id)).at(-1) || null;
}

function archive(state, period, form) {
  const currentPeriod = A.localDate().slice(0, 7);
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period) || period >= currentPeriod) throw Error('经营快照只能归档过去月份');
  if (!form.checked || !String(form.note || '').trim()) throw Error('请核对经营来源并填写归档说明');
  const latest = currentSnapshot(state, period);
  const expected = latest?.version || 0;
  if (Number(form.version) !== expected) throw Error('经营快照版本已变化，请刷新后重试');
  const signature = sourceSignature(state, period);
  if (form.expectedSignature !== undefined && form.expectedSignature !== signature) throw Error('经营来源已变化，请刷新后重新核对');
  if (latest?.sourceSignature === signature) return { id: latest.id, version: latest.version, idempotent: true };
  const version = expected + 1;
  const report = analytics(state, period, endOfMonth(period));
  const id = 'operating-snapshot:' + state.companyId + ':' + period + ':v' + version;
  const record = { id, companyId: state.companyId, period, version, status: 'final', asOf: endOfMonth(period), sourceSignature: signature, supersedes: latest?.id || '', report: clone(report), note: String(form.note).trim(), ...require('./access').actor() };
  state.operations.periodSnapshots.push(record);
  return { id, version, idempotent: false };
}

function reminder(row, today = A.localDate()) {
  if (!row?.due) return { ready: false, text: '', file: null, reason: '到期日待补，暂不能生成催款话术' };
  const project = raw(require('./access').context().companyId || 'quanzhou').projects.find(item => (item.projectId || item.id) === row.projectId);
  const site = (project?.siteRecords || []).filter(item => item.file && !item.file.sample).slice().sort((a, b) => String(a.date).localeCompare(String(b.date))).at(-1);
  const days = Math.floor((Date.parse(today) - Date.parse(row.due)) / 86400000);
  const dateText = row.due.slice(0, 4) + '年' + Number(row.due.slice(5, 7)) + '月' + Number(row.due.slice(8, 10)) + '日';
  const amountText = (Number(row.remainingCents || 0) / 100).toFixed(2) + '元';
  const timing = days > 30 ? '该款项已逾期较久' : days > 7 ? '该款项已逾期' : days > 0 ? '该款项已到期' : days === 0 ? '该款项今日到期' : '该款项即将到期';
  const text = (row.customer || '您好') + '，您好。按原付款约定，' + (row.installment || '本期款项') + '应于' + dateText + '支付，当前待收金额为' + amountText + '。' + timing + '，请您核对原合同或付款约定并告知付款安排，谢谢。';
  return { ready: true, text, file: site ? clone(site.file) : null, reason: site ? '' : '暂无真实现场照片', overdueDays: Math.max(0, days) };
}

module.exports = { analytics, archive, currentSnapshot, sourceSignature, reminder, monthsThrough, endOfMonth };
