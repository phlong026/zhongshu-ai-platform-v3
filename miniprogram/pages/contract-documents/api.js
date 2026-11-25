const http = require('../../api/http');
const base = id => '/franchise/sales/contracts/' + encodeURIComponent(id);
module.exports = {
  get: id => http.get(base(id) + '/payment-plan'),
  sign: (id, body, key) => http.post(base(id) + '/sign', body, { 'Idempotency-Key': key }),
  evidence: (id, body, key) => http.post(base(id) + '/evidence', body, { 'Idempotency-Key': key }),
  download: (id, assetId) => http.post(base(id) + '/assets/' + encodeURIComponent(assetId) + '/download-tickets')
};
