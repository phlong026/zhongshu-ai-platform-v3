const http = require('../../../api/http');
const sales = require('../../../api/franchise/sales');
const draftPath = id => '/franchise/sales/customers/' + encodeURIComponent(id) + '/payment-plan-draft';
const path = id => '/franchise/sales/contracts/' + encodeURIComponent(id) + '/payment-plan';
module.exports = {
  draft: id => http.get(draftPath(id)),
  saveDraft: (id, body, key) => http.put(draftPath(id), body, { 'Idempotency-Key': key }),
  get: id => http.get(path(id)),
  versions: id => http.get(path(id) + '/versions'),
  save: (id, body, key) => http.post(path(id), body, { 'Idempotency-Key': key }),
  create: (body, key) => sales.contracts.create(body, key),
  prepare: async customerId => {
    const [contracts, requirements, quotes, draft] = await Promise.all([
      sales.contracts.list(customerId), sales.requirements.list(customerId), sales.quotes.list(customerId), http.get(draftPath(customerId))
    ]);
    if (![contracts, requirements, quotes].every(Array.isArray)) throw Error('合同基础资料不完整，请重新加载');
    return { contracts, requirements, quotes, draft };
  }
};
