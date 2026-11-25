const http = require('../../api/http');
const base = id => '/franchise/customers/' + encodeURIComponent(id) + '/external-entrust';
module.exports = {
  get: id => http.get(base(id)),
  resolve: id => http.get('/franchise/commissions/' + encodeURIComponent(id)),
  register: (id, body, key) => http.post(base(id), body, { 'Idempotency-Key': key }),
  convert: (id, body, key) => http.post(base(id) + '/convert-legacy', body, { 'Idempotency-Key': key }),
  results: (id, body, key) => http.post(base(id) + '/results', body, { 'Idempotency-Key': key }),
  download: (id, kind, version, assetId) => http.post(base(id) + '/' + kind + '/' + version + '/assets/' + encodeURIComponent(assetId) + '/download-tickets')
};
