const http = require('../../api/http');
const base = id => '/franchise/commissions/' + encodeURIComponent(id) + '/external-handover';
module.exports = {
  get: id => http.get(base(id)),
  receivers: id => http.get('/franchise/commissions/' + encodeURIComponent(id) + '/handover-receivers'),
  source: id => http.get('/franchise/customers/' + encodeURIComponent(id) + '/external-entrust'),
  submit: (id, body, key) => http.post(base(id), body, { 'Idempotency-Key': key }),
  return: (id, body, key) => http.post(base(id) + '/return', body, { 'Idempotency-Key': key }),
  receive: (id, body, key) => http.post(base(id) + '/receive', body, { 'Idempotency-Key': key }),
  download: (id, version, slot) => http.post(base(id) + '/versions/' + version + '/assets/' + encodeURIComponent(slot) + '/download-tickets'),
  sourceDownload: (id, version, assetId) => http.post('/franchise/customers/' + encodeURIComponent(id) + '/external-entrust/results/' + version + '/assets/' + encodeURIComponent(assetId) + '/download-tickets')
};
