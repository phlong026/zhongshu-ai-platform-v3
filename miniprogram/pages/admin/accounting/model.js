const { day } = require('../../../utils/finance-date');
const maxId = '9223372036854775807';
function id(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const text = String(value ?? '');
  return /^[1-9]\d{0,18}$/.test(text) && (text.length < 19 || text <= maxId) ? text : '';
}
function money(value) {
  if (value == null || typeof value === 'number' && !Number.isSafeInteger(value)) return '—';
  const raw = String(value); if (!/^-?\d{1,19}$/.test(raw)) return '—';
  const negative = raw.startsWith('-'), digits = raw.replace(/^-/, '').replace(/^0+(?=\d)/, '').padStart(3, '0');
  return (negative && /[1-9]/.test(digits) ? '-' : '') + digits.slice(0, -2).replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + digits.slice(-2);
}
const version = value => Number.isSafeInteger(Number(value)) && Number(value) >= 0 ? Number(value) : -1;
const hash = value => /^[0-9a-f]{64}$/.test(String(value || ''));
const definitions = [
  ['contractRevenueCents', '已签合同金额'], ['confirmedCostCents', '已确认项目费用'],
  ['netReceivedCents', '客户净收款'], ['companyNetPaidCents', '公司净付款'],
  ['receivableCents', '合同待收款'], ['payableOutstandingCents', '项目待付款']
];
function amounts(row) {
  if (!row || row.basisCode !== 'CONTRACT_CONFIRMED_COST_V1') throw Error('核算口径尚未确认，请重新加载');
  for (const key of ['confirmedCostCents', 'netReceivedCents', 'companyNetPaidCents', 'cashNetCents', 'receivableCents']) {
    if (money(row[key]) === '—') throw Error('项目核算金额无效，请重新加载');
  }
  for (const key of ['contractRevenueCents', 'grossProfitCents']) if (row[key] != null && money(row[key]) === '—') throw Error('项目核算金额无效，请重新加载');
  const outstandingText = money(row.payableOutstandingCents);
  if (row.payableOutstandingCents !== undefined && (outstandingText === '—' || outstandingText.startsWith('-'))) throw Error('项目待付款金额无效，请重新加载');
  return { cashText: money(row.cashNetCents), profitText: money(row.grossProfitCents), costText: money(row.confirmedCostCents),
    outstandingText, metrics: definitions.map(([key, label]) => ({ key, label, value: money(row[key]) })), basis: String(row.basisLabel || '') };
}
function snapshot(row, projectId) {
  if (!row || !id(row.id) || version(row.version) < 1 || !hash(row.sourceHash) || id(row.snapshot?.projectId) !== projectId
      || row.snapshot?.sourceHash !== row.sourceHash) throw Error('核算历史响应不匹配，请重新加载');
  return { id: id(row.id), version: version(row.version), date: day(row.confirmedAt), note: String(row.note || ''),
    sourceHash: row.sourceHash, ...amounts(row.snapshot) };
}
function view(row, projectId) {
  if (!row || id(row.projectId) !== projectId || version(row.version) < 0 || !hash(row.sourceHash)
      || !['UNCONFIRMED', 'CURRENT', 'STALE'].includes(row.state) || typeof row.ready !== 'boolean' || typeof row.canConfirm !== 'boolean')
    throw Error('项目核算响应不匹配，请重新加载');
  const latest = row.latestSnapshot == null ? null : snapshot(row.latestSnapshot, projectId);
  if ((row.state === 'UNCONFIRMED') !== (latest == null) || latest && latest.version !== version(row.version)) throw Error('核算版本不一致，请重新加载');
  return { projectId, projectName: String(row.projectName || '项目核算'), sourceHash: row.sourceHash, version: version(row.version),
    state: row.state, stateText: { UNCONFIRMED: '尚未确认', CURRENT: '已确认', STALE: '账目有变化，待复核' }[row.state],
    ready: row.ready, canConfirm: row.canConfirm, latest, ...amounts(row),
    notice: !id(row.contractId) ? '尚无有效已签合同，暂不能计算项目毛利或确认核算。'
      : !row.engineeringSettled ? '当前为过程核算，工程完成结算后可核对并保存确认版本。'
      : row.state === 'STALE' ? '原账、合同或归属已有变化。历史确认保留原金额，请核对当前记录后重新确认。'
      : row.state === 'UNCONFIRMED' ? '请核对项目费用是否完整归集，再保存结项核算版本。' : '已保存核算快照；后续补录或更正会提示再次复核。' };
}
function history(response, projectId, pageNo) {
  if (!response || !Array.isArray(response.list) || Number(response.pageNo) !== pageNo || !Number.isSafeInteger(Number(response.total)) || Number(response.total) < 0)
    throw Error('核算历史分页响应无效');
  return { rows: response.list.map(row => snapshot(row, projectId)), total: Number(response.total) };
}
module.exports = { id, money, view, snapshot, history };
