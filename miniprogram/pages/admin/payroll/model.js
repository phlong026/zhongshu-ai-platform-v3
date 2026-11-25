const { id, money } = require('../accounting/model');
const { day } = require('../../../utils/finance-date');
const FAMILIES = [{ key: 'BUSINESS', label: '业务岗位' }, { key: 'ENGINEERING', label: '工程岗位' }];
const revision = value => Number.isSafeInteger(Number(value)) && Number(value) >= 0 ? Number(value) : -1;
const family = value => FAMILIES.some(row => row.key === value);
function month(value) { return /^\d{4}-(0[1-9]|1[0-2])$/.test(String(value || '')) ? String(value) : ''; }
function cents(value, signed = false) {
  const text = String(value ?? '').trim(); if (!(signed ? /^-?\d+(\.\d{1,2})?$/ : /^\d+(\.\d{1,2})?$/).test(text)) return null;
  const parts = text.replace(/^-/, '').split('.'), result = Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0'));
  return Number.isSafeInteger(result) && result <= 1e12 ? (text.startsWith('-') ? -result : result) : null;
}
function inputMoney(value) { const text = money(value); return text === '—' ? '' : text.replace(/,/g, ''); }
function rule(value, company) {
  if (!value || !id(value.id) || id(value.companyId) !== company || !family(value.family) || revision(value.version) < 1
      || !month(value.effectiveMonth) || value.calculationMode !== 'MANUAL_ONLY') throw Error('薪酬规则响应不匹配');
  return { id: id(value.id), companyId: company, family: value.family, version: revision(value.version), effectiveMonth: value.effectiveMonth,
    baseSalaryNote: String(value.baseSalaryNote || ''), assessmentNote: String(value.assessmentNote || ''), commissionNote: String(value.commissionNote || ''),
    evidenceNote: String(value.evidenceNote || ''), date: day(value.createdAt) };
}
function context(value, company, period) {
  if (!value || id(value.companyId) !== company || value.month !== period || value.manualOnly !== true || typeof value.canWrite !== 'boolean'
      || !Array.isArray(value.rules) || !Array.isArray(value.staff) || !Array.isArray(value.projects)) throw Error('薪酬工作范围响应不匹配');
  const rules = value.rules.map(item => {
    if (!family(item.family) || revision(item.latestVersion) < 0 || !['UNCONFIGURED', 'CONFIGURED'].includes(item.status)
        || (item.status === 'UNCONFIGURED') !== (item.effectiveRule == null)) throw Error('薪酬规则状态不明确');
    const effective = item.effectiveRule ? rule(item.effectiveRule, company) : null;
    if (effective && (effective.family !== item.family || effective.effectiveMonth > period || effective.version > revision(item.latestVersion))) throw Error('薪酬规则生效范围不匹配');
    return { family: item.family, status: item.status, latestVersion: revision(item.latestVersion), effectiveRule: effective };
  });
  if (rules.length !== 2 || new Set(rules.map(row => row.family)).size !== 2) throw Error('业务和工程规则状态不完整');
  return { companyId: company, month: period, canWrite: value.canWrite, rules,
    staff: value.staff.map(person => { if (!id(person.id) || !family(person.family)) throw Error('员工范围无效'); return { id: id(person.id), name: String(person.name || '本公司员工'), family: person.family }; }),
    projects: value.projects.map(project => { if (!id(project.id)) throw Error('项目编号无效'); return { id: id(project.id), name: String(project.name || '工程项目') }; }) };
}
function record(value, company) {
  const facts = value?.snapshot;
  if (!value || !id(value.id) || id(value.companyId) !== company || !id(value.userId) || !month(value.month) || !family(value.family)
      || revision(value.version) < 1 || id(facts?.companyId) !== company || id(facts?.userId) !== id(value.userId) || facts?.month !== value.month || facts?.family !== value.family
      || facts?.calculationMode !== 'MANUAL_ONLY' || facts?.cashFact !== false || !Array.isArray(facts.commissions)) throw Error('薪酬核算记录响应不匹配');
  for (const key of ['baseSalaryCents', 'commissionCents', 'adjustmentCents', 'totalCents']) if (money(facts[key]) === '—') throw Error('薪酬金额无效');
  if (!['UNCONFIGURED', 'CONFIGURED'].includes(facts.ruleStatus) || (facts.ruleStatus === 'UNCONFIGURED') !== (facts.rule == null)) throw Error('历史规则依据无效');
  const appliedRule = facts.rule ? rule(facts.rule, company) : null;
  if (appliedRule && (appliedRule.family !== value.family || appliedRule.effectiveMonth > value.month)) throw Error('历史规则生效范围不匹配');
  const lines = facts.commissions.map((line, index) => {
    if (!id(line.projectId) || money(line.amountCents) === '—' || line.stage && !id(line.stage.stageId)) throw Error('提成依据无效');
    return { key: String(index), projectId: id(line.projectId), projectName: String(line.projectName || ''), stageId: line.stage ? id(line.stage.stageId) : '',
      stageName: line.stage ? String(line.stage.label || line.stage.stageKey) : '未关联付款节点', netText: line.stage ? money(line.stage.netCollectedCents) : '',
      amountText: money(line.amountCents), amount: inputMoney(line.amountCents), basis: String(line.basis || '') };
  });
  return { id: id(value.id), companyId: company, userId: id(value.userId), userName: String(facts.userName || '员工'), family: value.family, month: value.month,
    version: revision(value.version), date: day(value.createdAt), note: String(value.note || ''), rule: appliedRule,
    ruleLabel: appliedRule ? '规则 V' + appliedRule.version : '规则待配置 · 人工确认',
    baseText: money(facts.baseSalaryCents), commissionText: money(facts.commissionCents), adjustmentText: money(facts.adjustmentCents), totalText: money(facts.totalCents),
    base: inputMoney(facts.baseSalaryCents), adjustment: inputMoney(facts.adjustmentCents), commissions: lines };
}
function page(value, expectedPage, company, kind) {
  if (!value || !Array.isArray(value.list) || Number(value.pageNo) !== expectedPage || !Number.isSafeInteger(Number(value.total)) || Number(value.total) < 0) throw Error('薪酬分页响应无效');
  return { rows: value.list.map(item => kind === 'rules' ? rule(item, company) : record(item, company)), total: Number(value.total) };
}
function stages(value, projectId) {
  if (!value || id(value.projectId) !== projectId || !Array.isArray(value.stages)) throw Error('提成项目响应不匹配');
  return [{ stageId: '', label: '不关联付款节点', netText: '' }, ...value.stages.map(row => {
    if (!id(row.stageId) || !id(row.contractId) || money(row.expectedCents) === '—' || money(row.netCollectedCents) === '—') throw Error('付款节点数据无效');
    return { stageId: id(row.stageId), label: String(row.label || row.stageKey || '付款节点'), netText: money(row.netCollectedCents), expectedText: money(row.expectedCents) };
  })];
}
function total(form) {
  const base = cents(form?.base), adjustment = cents(form?.adjustment, true), lines = form?.commissions || [];
  if (base == null || adjustment == null || lines.some(line => cents(line.amount) == null)) return '—';
  return money(String(base + adjustment + lines.reduce((sum, line) => sum + cents(line.amount), 0)));
}
function recordPayload(form, ctx, selectedFamily) {
  if (!ctx.canWrite || !ctx.staff.some(person => person.id === form.userId && person.family === selectedFamily)) throw Error('请选择本公司当前岗位的员工');
  const base = cents(form.base), adjustment = cents(form.adjustment, true), note = String(form.note || '').trim();
  if (base == null || adjustment == null || !form.confirmed || !note || note.length > 1000 || revision(form.expectedVersion) < 0) throw Error('请填写底薪、调整及说明，并确认人工核算金额');
  if (form.commissions.length > 30) throw Error('提成明细最多30项');
  const commissions = form.commissions.map(line => {
    const amount = cents(line.amount), basis = String(line.basis || '').trim();
    if (!ctx.projects.some(p => p.id === line.projectId) || !amount || !basis || basis.length > 1000 || line.stageId && !(line.stages || []).some(s => s.stageId === line.stageId))
      throw Error('请核对每项提成的项目、付款节点、人工金额及依据');
    return { projectId: line.projectId, stageId: line.stageId || null, amountCents: amount, basis };
  });
  const sum = base + adjustment + commissions.reduce((sum, row) => sum + row.amountCents, 0);
  if (sum < 0 || sum > 1e12) throw Error('核算合计超出允许范围');
  return { userId: form.userId, family: selectedFamily, month: ctx.month, expectedVersion: form.expectedVersion,
    ruleId: ctx.rules.find(r => r.family === selectedFamily)?.effectiveRule?.id || null, baseSalaryCents: base, adjustmentCents: adjustment, commissions, note, manuallyConfirmed: true };
}
function rulePayload(form, ctx, selectedFamily) {
  if (!ctx.canWrite || !form.confirmed || !month(form.effectiveMonth)) throw Error('请填写生效月份，并确认规则由管理层提供');
  const body = { family: selectedFamily, expectedVersion: ctx.rules.find(r => r.family === selectedFamily).latestVersion, effectiveMonth: form.effectiveMonth, managementConfirmed: true };
  for (const key of ['baseSalaryNote', 'assessmentNote', 'commissionNote', 'evidenceNote']) {
    body[key] = String(form[key] || '').trim(); if (!body[key] || body[key].length > (key === 'evidenceNote' ? 1000 : 2000)) throw Error('请完整填写底薪、考核、提成规则及管理层确认依据');
  }
  return body;
}
module.exports = { FAMILIES, id, money, month, cents, context, record, rule, page, stages, total, recordPayload, rulePayload };
