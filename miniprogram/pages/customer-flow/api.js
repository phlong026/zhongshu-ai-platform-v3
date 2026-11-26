const http = require('../../api/http');
const base = id => '/franchise/customers/' + encodeURIComponent(id) + '/workflow';
const step = (id, code) => base(id) + '/steps/' + encodeURIComponent(code);
const headers = key => ({ 'Idempotency-Key': key });
module.exports = {
  get: id => http.get(base(id)),
  listQuotes: id => http.get(base(id) + '/quotes'),
  entryCustomer: id => require('../../api/franchise/crm').customers.get(id),
  entryProfile: () => http.get('/platform/profile/mine'),
  save: (id, code, body, key) => http.put(step(id, code), body, headers(key)),
  complete: (id, code, body, key) => http.post(step(id, code) + '/complete', body, headers(key)),
  skipVisit: (id, body, key) => http.post(step(id, 'SITE_VISIT') + '/skip', body, headers(key)),
  reopen: (id, code, body, key) => http.post(step(id, code) + '/reopen', body, headers(key)),
  /** 流程状态：{revision,status,reasonCode?,reason?,nextFollowOn?} → 最新八步视图 */
  status: (id, body, key) => http.post(base(id) + '/status', body, headers(key)),
  records: (id, pageNo) => http.get(base(id) + '/records', { pageNo, pageSize: 20 }),
  listSupplements: (id, code, pageNo) => http.get(step(id, code) + '/supplements', { pageNo, pageSize: 20 }),
  addSupplement: (id, code, body, key) => http.post(step(id, code) + '/supplements', body, headers(key)),
  download: (id, code, assetId) => http.post(step(id, code) + '/assets/' + encodeURIComponent(assetId) + '/download-tickets', {})
};
