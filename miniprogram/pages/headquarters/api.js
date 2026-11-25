const http = require('../../api/http');
const PREFIX = '/platform/headquarters';
const DATASETS = ['customers', 'projects', 'receipts', 'payables', 'payments', 'refunds'];
const STEPS = ['INVITATION', 'INTRODUCTION', 'HOUSE_SELECTION', 'SITE_VISIT', 'MEASUREMENT', 'QUOTATION', 'CONTRACT', 'START'];

function id(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw Error('记录编号无效，请重新打开页面');
  const result = String(value == null ? '' : value);
  if (!/^[1-9]\d{0,18}$/.test(result) || result.length === 19 && result > '9223372036854775807') throw Error('记录编号无效，请重新打开页面');
  return result;
}
function dataset(value) {
  if (!DATASETS.includes(value)) throw Error('查询类别无效');
  return value;
}
function bookScope(value) {
  if (value === 'company') return value;
  const match = /^(project|book):([1-9]\d*)$/.exec(String(value || ''));
  if (!match) throw Error('账本范围无效');
  return match[1] + ':' + id(match[2]);
}
function filters(input = {}, paging = false) {
  const result = {};
  for (const key of ['brandId', 'companyId']) if (input[key]) result[key] = id(input[key]);
  for (const [key, max] of [['keyword', 100], ['status', 40]]) {
    const value = String(input[key] || '').trim();
    if (value.length > max) throw Error('筛选内容过长');
    if (value) result[key] = value;
  }
  for (const key of ['dateFrom', 'dateTo']) if (input[key]) {
    const value = String(input[key]);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value) throw Error('请选择有效日期');
    result[key] = value;
  }
  if (result.dateFrom && result.dateTo && result.dateFrom > result.dateTo) throw Error('开始日期不能晚于结束日期');
  if (paging) {
    result.pageNo = Number(input.pageNo || 1); result.pageSize = Number(input.pageSize || 20);
    if (!Number.isInteger(result.pageNo) || result.pageNo < 1 || result.pageNo > 1000000 || !Number.isInteger(result.pageSize) || result.pageSize < 1 || result.pageSize > 100) throw Error('分页参数无效');
  }
  return result;
}
const listPath = value => ['customers', 'projects'].includes(dataset(value)) ? '/' + value : '/finance/' + value;
const encodeQuery = values => Object.keys(values).map(key => encodeURIComponent(key) + '=' + encodeURIComponent(values[key])).join('&');
const get = (path, params) => http.get(PREFIX + path, params);
function attachmentPath(action) {
  if (!action) throw Error('附件引用无效');
  const resource = id(action.id);
  if (action.type === 'external' && ['requests', 'results'].includes(action.kind)
      && Number.isInteger(action.version) && action.version > 0 && action.version <= 2147483647)
    return PREFIX + '/customers/' + resource + '/external-entrust/' + action.kind + '/' + action.version + '/assets/' + id(action.assetId) + '/content';
  if (action.type === 'workflow' && STEPS.includes(action.step)) return PREFIX + '/customers/' + resource + '/workflow/' + action.step + '/assets/' + id(action.assetId) + '/content';
  if (action.type === 'document') return PREFIX + '/projects/' + resource + '/documents/' + id(action.documentId) + '/content';
  if (action.type === 'project' && ['CONTRACT', 'SITE_RECORD', 'CLOSURE_OWNER', 'CLOSURE_FILE', 'CLOSURE_SETTLEMENT'].includes(action.section)) return PREFIX + '/projects/' + resource + '/assets/' + id(action.assetId) + '/content?section=' + action.section;
  if (action.type === 'finance' && ['receipts', 'payments', 'refunds'].includes(action.kind)) return PREFIX + '/finance/' + action.kind + '/' + resource + '/assets/' + id(action.assetId) + '/content';
  throw Error('附件引用无效');
}
module.exports = {
  PREFIX, DATASETS, STEPS, id, dataset, bookScope, filters, attachmentPath,
  context: () => get('/context'),
  summary: input => get('/summary', filters({ brandId: input.brandId, companyId: input.companyId })),
  list: (kind, input) => get(listPath(kind), filters(input, true)),
  detail: (kind, value) => get(listPath(kind) + '/' + id(value)),
  records: (value, pageNo) => get('/customers/' + id(value) + '/workflow-records', filters({ pageNo }, true)),
  externalVersions: (value, kind, pageNo) => {
    if (!['requests', 'results'].includes(kind)) throw Error('委托资料类别无效');
    return get('/customers/' + id(value) + '/external-entrust/' + kind, filters({ pageNo }, true));
  },
  projectItems: (value, kind, pageNo) => {
    if (!['documents', 'site-records'].includes(kind)) throw Error('工程资料类别无效');
    return get('/projects/' + id(value) + '/' + kind, filters({ pageNo }, true));
  },
  books: (value, keyword) => get('/companies/' + id(value) + '/books', filters({ keyword })),
  ledger: (value, scope) => get('/companies/' + id(value) + '/ledger', { scope: bookScope(scope) }),
  exportPath: (kind, input) => PREFIX + '/exports?' + encodeQuery({ dataset: dataset(kind), ...filters(input) })
};
