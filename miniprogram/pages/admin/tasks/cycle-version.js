const P = require('../../../utils/platform-performance');

const statuses = { PENDING_REVIEW: '待复核', REVIEWED: '已通过', RETURNED: '已退回' };
const goalNames = { sales: '签约金额', collections: '回款金额', effective: '有效跟进客户', customers: '客户数', nodes: '完成节点' };
const sourceNames = { contract: '销售合同', node: '工程节点', material: '材料到货', commission: '设计委托', customer: '客户档案', publication: '发布记录', lead: '客资', receipt: '财务收款', refund: '财务退款', payment: '财务付款' };

function exact(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  return value == null ? '' : String(value);
}

function id(value) {
  const text = exact(value);
  return /^[1-9]\d*$/.test(text) ? text : '';
}

function object(raw) {
  if (raw && typeof raw === 'object') return raw;
  if (typeof raw !== 'string') return {};
  // Historical JSONB may contain bare bigint totals. Protect numeric tokens without changing quoted text.
  const safe = raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token =>
    /^-?\d{16,}$/.test(token) ? '"' + token + '"' : token);
  try { return JSON.parse(safe) || {}; } catch (_) { return {}; }
}

function valueText(type, value) {
  const raw = exact(value);
  if (!/^-?\d+$/.test(raw)) return raw || '未记录';
  const meta = P.typeMeta(type);
  const money = meta.money || P.METRIC_MONEY.has(type) || ['sales', 'collections'].includes(type);
  const unit = money ? '元' : meta.unit || (type.startsWith('metric:') ? '个' : '');
  if (!unit) return raw;
  const digits = raw.replace(/^-/, '').padStart(3, '0');
  const fraction = digits.slice(-2);
  const amount = (raw.startsWith('-') ? '-' : '') + digits.slice(0, -2) +
    (money ? '.' + fraction : fraction === '00' ? '' : '.' + fraction.replace(/0$/, ''));
  return amount + ' ' + unit;
}

function sourceUrl(src, role) {
  const source = id(src.sourceId), customer = id(src.customerId), project = id(src.projectId), supply = id(src.supplyId);
  if (!source) return '';
  switch (src.sourceType) {
    case 'customer': return customer === source ? '/pages/workflow/index?view=customer&id=' + customer : '';
    case 'contract': return customer ? '/pages/workflow/index?view=contract&id=' + customer + '&contractId=' + source : '';
    case 'commission': return customer ? '/pages/workflow/commission/index?commissionId=' + source + '&customerId=' + customer + '&view=progress' : '';
    case 'node': return project ? '/pages/build/record/index?id=' + project + '&view=node&nodeId=' + source : '';
    case 'material': return project && supply ? '/pages/build/issues/index?project=' + project + '&supply=' + supply + '&receipt=' + source : '';
    case 'publication': return '/pages/studio/index?view=detail&record=' + source;
    case 'receipt':
    case 'payment':
    case 'refund':
      return ['finance','finance-lead'].includes(role)
        ? '/pages/admin/finance-desk/index?view=flows&tab=ledger&scope=all&messageType=fin_' + src.sourceType + '&messageObject=' + source : '';
    default: return '';
  }
}

function metrics(groups) {
  const lines = [];
  for (const [dept, fields] of Object.entries(groups || {})) {
    if (!fields || typeof fields !== 'object') continue;
    for (const [type, value] of Object.entries(fields)) {
      if (value == null || typeof value === 'object') continue;
      lines.push({ key: dept + '/' + type, label: [P.DEPT_LABEL[dept], P.METRIC_LABEL[type] || goalNames[type] || P.typeMeta(type).label].filter(Boolean).join(' · '),
        value: valueText(type, value) });
    }
  }
  return lines;
}

function versions(row, role) {
  const submission = object(row.submission);
  const history = Array.isArray(submission.history) ? submission.history.slice().reverse() : [];
  return [row, ...history].map((entry, index) => {
    const evidence = object(entry.submission), review = object(entry.review);
    const goals = metrics(evidence.goals && evidence.goals.departments);
    if (evidence.goals && evidence.goals.legacy != null) goals.push({key:'legacy', label:'原版目标', value:exact(evidence.goals.legacy)});
    const hasSources = Array.isArray(evidence.sources);
    return {
      label: (index ? '历史' : '当前') + ' · 版本 ' + entry.revision + ' · ' + (statuses[entry.status] || '未提交'),
      note: evidence.note || '', at: evidence.at || '', review, goalVersion: evidence.goalVersion,
      goals, actuals: metrics(evidence.actuals), hasSources,
      sources: (hasSources ? evidence.sources : []).map(src => ({
        key: src.key, name: sourceNames[src.sourceType] || '业务原单', sourceId: id(src.sourceId) || src.businessId || src.key,
        date: src.date || '', valueText: valueText(src.type || '', src.value), url: sourceUrl(src, role) }))
    };
  });
}
module.exports = { id, statuses, versions };
