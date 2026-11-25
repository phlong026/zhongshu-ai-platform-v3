const platform = require('../../api/http');
const design = require('../../api/franchise/design');
const context = require('./context');
function getToken() { return platform.token(); }
function captureSession() { return getToken() ? context.scope() + ':' + platform.sessionGeneration() : ''; }
function isSameSession(expected) { return !!expected && expected === captureSession(); }
function remember(value) {
  if (!value || typeof value !== 'object') return value;
  const id = value.caseId || value.id;
  if (id && (value.floorPlans || value.coverAssetId) && !value.projectId) {
    const registry = context.getApp().catalogAssets;
    const add = asset => { if (asset) registry[String(asset)] = String(id); };
    add(value.coverAssetId); add(value.elevationAssetId);
    (value.floorPlanAssetIds || []).forEach(add);
    (value.floorPlans || []).forEach(plan => add(plan.assetId));
  }
  if (Array.isArray(value)) value.forEach(remember);
  else ['list', 'candidates'].forEach(key => { if (Array.isArray(value[key])) value[key].forEach(remember); });
  return value;
}
function request(method, url, data, headers) {
  const expected = captureSession();
  if (!expected) return Promise.reject({ code: 401, msg: '请先登录' });
  const path = url.split('?')[0].replace(/^\/app-api\/design\/v1/, '');
  if(require('../../utils/design-retirement').blocked(method,path))return require('../../utils/design-retirement').reject();
  const query = {};
  (url.split('?')[1] || '').split('&').filter(Boolean).forEach(pair => {
    const split = pair.indexOf('=');
    const key = decodeURIComponent(split < 0 ? pair : pair.slice(0, split));
    const value = decodeURIComponent(split < 0 ? '' : pair.slice(split + 1));
    if (value !== '' && value !== 'undefined' && value !== 'null') query[key] = value;
  });
  const key = headers && headers['Idempotency-Key'];
  let task;
  if (path === '/point-account') task = require('../../api/company-points').summary().then(value => ({ availablePoints: value.balance == null ? value.company && value.company.balance : value.balance }));
  else if (path === '/generation-price-quotes' || path === '/budget-point-quotes') {
    const subject = path === '/budget-point-quotes' ? 'budget_estimate' : query.stage === 'ELEVATION' ? 'ai_elevation' : 'ai_flat';
    task = platform.get('/franchise/design/flow-price', { subject, quantity: path === '/budget-point-quotes' ? 1 : Number(query.count) }).then(quote => ({
      companyPrice: quote, stage: query.stage, count: Number(query.count), resolution: query.resolution,
      totalPointCost: Number(quote.credits), pointCost: Number(quote.credits), product: 'BUDGET_ESTIMATE'
    }));
  } else if (path === '/assets/upload-tickets') task = platform.post('/franchise/design/flow-upload-ticket', data);
  else if (/^\/assets\/\d+\/complete$/.test(path)) task = design.assets.complete(path.split('/')[2]).then(result => {
    if (!result || result.uploadStatus !== 'ACCEPTED') throw { msg: result && result.rejectedReason || '图片校验中或未通过，请稍后重试' };
    return true;
  });
  else if (/^\/assets\/\d+\/download-tickets$/.test(path)) {
    const asset = path.split('/')[2];
    task = design.assets.downloadUrl(asset);
  } else if (path === '/ai-design/note-polish') task = design.projects.notePolish(data);
  else if (/^\/ai-jobs\/\d+$/.test(path)) task = platform.get('/franchise/design/flow-ai-jobs/' + path.split('/')[2]);
  else if (/^\/ai-jobs\/\d+\/cancellation-requests$/.test(path)) task = platform.post('/franchise/design/flow-ai-jobs/' + path.split('/')[2] + '/cancellation-requests', {}, key ? { 'Idempotency-Key': key } : undefined);
  else task = platform.post('/franchise/design/flow', { method, path, query, body: data || {} }, key ? { 'Idempotency-Key': key } : undefined);
  return Promise.resolve(task).then(value => {
    if (!isSameSession(expected)) throw { code: 'SESSION_CHANGED', msg: '工作身份已更新，请重新进入' };
    return remember(value);
  }).catch(error => {
    if (!isSameSession(expected)) throw { code: 'SESSION_CHANGED', msg: '工作身份已更新，请重新进入' };
    throw Object.assign({}, error, { code: Number(error.code) === 1072000016 ? 'DESIGN_PRICE_CHANGED' : error.code, msg: error.msg || error.message || '请求失败，请稍后重试' });
  });
}
module.exports = { getToken, captureSession, isSameSession, request,
  get: (url) => request('GET', url), post: (url, body, headers) => request('POST', url, body, headers),
  put: (url, body, headers) => request('PUT', url, body, headers), patch: (url, body, headers) => request('PATCH', url, body, headers),
  del: (url, body, headers) => request('DELETE', url, body, headers) };
