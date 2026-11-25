const http = require('../../../api/http');
const sales = require('../../../api/franchise/sales');
const path = id => '/franchise/sales/contracts/' + encodeURIComponent(id) + '/payment-plan';
module.exports = {
  get: id => http.get(path(id)),
  versions: id => http.get(path(id) + '/versions'),
  save: (id, body, key) => http.post(path(id), body, { 'Idempotency-Key': key }),
  create: (body, key) => sales.contracts.create(body, key),
  prepare: async customerId => {
    const [contracts, requirements, quotes] = await Promise.all([
      sales.contracts.list(customerId), sales.requirements.list(customerId), sales.quotes.list(customerId)
    ]);
    if (![contracts, requirements, quotes].every(Array.isArray)) throw Error('合同基础资料不完整，请重新加载');
    return { contracts, requirements, quotes };
  }
};
