// Public case images use authenticated B downloads, then local paths. Never persist signed URLs or tokens.
const { Sha256 } = require('./sha256-stream');
const INDEX = 'zs-case-images-v1';
const FRESH_MS = 5 * 60 * 1000;
const MAX_BYTES = 30 * 1024 * 1024;
const flights = new Map(), queue = [];
let running = 0, generation = 0;
function fs() { return wx.getFileSystemManager(); }
function root() { return wx.env.USER_DATA_PATH + '/case-images-v1/'; }
function index() { try { return wx.getStorageSync(INDEX) || {}; } catch (_) { return {}; } }
function persist(items) { try { wx.setStorageSync(INDEX, items); return true; } catch (_) { return false; } }
function remove(path) { if (typeof path === 'string' && path.startsWith(root())) try { fs().unlinkSync(path); } catch (_) {} }
function keyFor(caseId, assetId, options) {
  const http = require('../api/http');
  return [http.tenantId(), http.currentPostId(), caseId, assetId, options.version || '', options.rendition || 'COVER'].join(':');
}
function prune(items) {
  let total = 0;
  Object.entries(items).sort((a,b) => b[1].used - a[1].used).forEach(([key, item], i) => {
    total += Number(item.bytes) || 0;
    if (i >= 120 || total > MAX_BYTES || Date.now() - item.used > 86400000) { remove(item.path); delete items[key]; }
  });
  return persist(items);
}
function clear() { generation++; const items = index(); Object.values(items).forEach(i => remove(i.path)); try { fs().readdirSync(root()).forEach(name=>remove(root()+name)); } catch (_) {} persist({}); }
function invalidate(caseId, assetId, options = {}) { const items = index(), key = keyFor(caseId, assetId, options); if (items[key]) remove(items[key].path); delete items[key]; persist(items); }
function schedule(work) {
  return new Promise((resolve,reject) => { queue.push({work,resolve,reject}); drain(); });
}
function drain() {
  while (running < 3 && queue.length) {
    const item = queue.shift(); running++;
    Promise.resolve().then(item.work).then(item.resolve,item.reject).finally(() => { running--; drain(); });
  }
}
function diagnostic(stage, response, error) {
  console.warn('[case-image]', {stage, statusCode: response && response.statusCode || 0, message: error && (error.errMsg || error.message) || ''});
}
function load(caseId, assetId, options = {}) {
  const http = require('../api/http');
  if (!http.token() || !http.currentPostId()) return Promise.reject(Error('请先登录'));
  if (!/^[1-9][0-9]*$/.test(String(caseId)) || !/^[1-9][0-9]*$/.test(String(assetId))) return Promise.reject(Error('图片编号无效'));
  const key = keyFor(caseId, assetId, options), epoch = generation, session = http.sessionGeneration();
  const alive = () => epoch === generation && session === http.sessionGeneration() && !!http.token();
  if (options.force) invalidate(caseId,assetId,options);
  const items = index(), hit = items[key];
  if (hit && hit.path.startsWith(root()) && Date.now() < hit.freshUntil) {
    try { fs().accessSync(hit.path); hit.used = Date.now(); persist(items); return Promise.resolve(hit.path); } catch (_) { delete items[key]; persist(items); }
  }
  if (flights.has(key + ':' + session)) return flights.get(key + ':' + session);
  const task = schedule(async () => {
    if (!alive()) throw Error('工作身份已更新');
    const rendition = options.rendition || 'COVER';
    const url = http.base() + '/franchise/design/cases/' + caseId + '/assets/' + assetId + '/content?rendition=' + encodeURIComponent(rendition);
    let last;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const result = await new Promise((resolve,reject) => wx.downloadFile({url, timeout:25000,
          header:{Authorization:'Bearer ' + http.token(),'X-Post-Id':String(http.currentPostId()),'tenant-id':http.tenantId()},
          success:resolve, fail:reject}));
        if (!alive()) throw Error('工作身份已更新');
        if (result.statusCode !== 200 || !result.tempFilePath) { const e = Error('图片下载失败（HTTP ' + result.statusCode + '）'); e.statusCode = result.statusCode; diagnostic('download',result,e); throw e; }
        await new Promise((resolve,reject) => wx.getImageInfo({src:result.tempFilePath,success:resolve,fail:reject}));
        if (!alive()) throw Error('工作身份已更新');
        let path = result.tempFilePath;
        try {
          try { fs().mkdirSync(root(),true); } catch (_) {}
          const filename = new Sha256().update(new Uint8Array(Array.from(key, c => c.charCodeAt(0)))).hex();
          const saved = root() + filename + '.img';
          fs().copyFileSync(path,saved); path = saved;
          const fresh = index(), previous = fresh[key];
          fresh[key] = {path,bytes:fs().statSync(path).size,used:Date.now(),freshUntil:Date.now()+FRESH_MS};
          if (previous && previous.path !== path) remove(previous.path);
          if (!prune(fresh)) { remove(saved); path=result.tempFilePath; }
        } catch (_) { /* Temporary file still renders when persistent storage is full. */ }
        return path;
      } catch (error) {
        last = error;
        if (!alive() || [400,401,403,404].includes(error.statusCode)) break;
      }
    }
    diagnostic('failed',null,last); throw last;
  }).finally(() => flights.delete(key + ':' + session));
  flights.set(key + ':' + session,task); return task;
}
function watch(page, selector, rows, visit) {
  if (page.__caseImageObserver) page.__caseImageObserver.disconnect();
  const start = () => {
    if (typeof wx.createIntersectionObserver !== 'function') { rows.forEach(visit); return; }
    try {
      const observer = wx.createIntersectionObserver(page,{observeAll:true});
      page.__caseImageObserver = observer;
      observer.relativeToViewport({bottom:240,top:120}).observe(selector, entry => {
        if (entry.intersectionRatio > 0) { const row = rows.find(r => String(r.id) === String(entry.dataset.id)); if (row) visit(row); }
      });
    } catch (_) { rows.forEach(visit); }
  };
  if (wx.nextTick) wx.nextTick(start); else Promise.resolve().then(start);
}
module.exports = {load,invalidate,clear,watch,FRESH_MS};
