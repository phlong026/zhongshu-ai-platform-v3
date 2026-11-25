const http = require('../http');
const retired = require('../../utils/design-retirement');

const idem = (key, action) => (key ? { 'Idempotency-Key': key } : { 'Idempotency-Key': 'design-' + action + '-' + Date.now() + '-' + Math.floor(Math.random() * 1e8) });

const cases = {
  page: (params) => http.get('/franchise/design/cases', params),
  styles: () => http.get('/franchise/design/cases/styles'),
  detail: (caseId) => http.get('/franchise/design/cases/' + encodeURIComponent(caseId)),
  downloadUrl: (caseId, assetId) => http.post('/franchise/design/cases/' + encodeURIComponent(caseId) + '/assets/' + encodeURIComponent(assetId) + '/download-tickets'),
  favorite: (caseId) => http.put('/franchise/design/cases/' + caseId + '/favorite'),
  unfavorite: (caseId) => http.del('/franchise/design/cases/' + caseId + '/favorite'),
  favorites: (cursor, limit) => http.get('/franchise/design/favorites', { cursor: cursor || undefined, limit: limit || 20 })
};

const assets = {
  uploadTicket: (data) => http.post('/franchise/design/assets/upload-tickets', data),
  complete: (assetId) => http.post('/franchise/design/assets/' + assetId + '/complete'),
  downloadUrl: (assetId) => http.post('/franchise/design/assets/' + assetId + '/download-tickets')
};

const projects = {
  create: (data, key) => http.post('/franchise/design/projects', data, idem(key, 'proj')),
  list: (params) => http.get('/franchise/design/projects', params),
  detail: (projectId, jobId) => http.get('/franchise/design/projects/' + projectId, jobId ? { jobId } : undefined),
  createFlatJob: retired.reject,
  createElevationJob: retired.reject,
  aiJob: (jobId) => http.get('/franchise/design/ai-jobs/' + jobId),
  cancelJob: (jobId) => http.post('/franchise/design/ai-jobs/' + jobId + '/cancellation-requests', {}),
  notePolish: retired.reject,
  selectFlat: (projectId, data) => http.post('/franchise/design/projects/' + projectId + '/flat-selections', data),
  selectElevation: (projectId, data) => http.post('/franchise/design/projects/' + projectId + '/elevation-selections', data),
  resultVersions: (projectId) => http.get('/franchise/design/projects/' + projectId + '/result-versions'),
  revision: retired.reject,
  saveToCustomer: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/save-to-customer', data, idem(key, 'save'))
};

const budget = {
  ruleInfo: (regionCode, structureType, materialGrade) =>
    http.get('/franchise/design/budget/rule-info', { regionCode, structureType, materialGrade }),
  estimate: (projectId, data, key) => http.post('/franchise/design/projects/' + projectId + '/budget-estimates', data, idem(key, 'budget'))
};

module.exports = { cases, assets, projects, budget };
