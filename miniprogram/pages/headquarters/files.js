const http = require('../../api/http');
const api = require('./api');
const session = require('./session');
const MAX_FILE = 32 * 1024 * 1024;
const MAX_EXPORT = 48 * 1024 * 1024;
const stale = () => ({ silent: true, message: '身份或页面已变化，请重新打开文件' });
const headers = () => ({ Authorization: 'Bearer ' + http.token(), 'tenant-id': http.tenantId(), 'X-Post-Id': http.currentPostId() });
function ensure(page, ticket) { if (!session.alive(page, ticket) || ticket.fileEpoch !== undefined && ticket.fileEpoch !== page._fileEpoch) throw stale(); }
async function authorize(page, ticket) {
  ensure(page, ticket);
  const context = await api.context();
  if (!session.context(page, ticket, context)) throw stale();
  ensure(page, ticket);
  return context;
}
function authorizedCompanies(context, filters = {}) {
  if (!Array.isArray(context.companies)) throw { code: 403, message: '公司授权范围不完整，请重新进入工作台' };
  const companies = context.companies.filter(row => (!filters.companyId || String(row.id) === filters.companyId)
    && (!filters.brandId || row.brandState === 'RESOLVED' && String(row.brandId) === filters.brandId)).map(row => api.id(row.id)).sort();
  if (filters.companyId && !companies.includes(filters.companyId)) throw { code: 403, message: '该公司已不在当前授权范围内' };
  return companies;
}
function remove(filePath) {
  if (!filePath) return;
  try { wx.getFileSystemManager().unlinkSync(filePath); } catch (_) { /* A temporary file may already have been released by WeChat. */ }
}
function own(page, filePath) {
  if (!page._hqFiles) page._hqFiles = new Set();
  page._hqFiles.add(filePath);
}
function cleanup(page) {
  for (const filePath of page._hqFiles || []) remove(filePath);
  page._hqFiles = new Set();
}
function cancel(page) {
  const task = page._hqFileTask; page._hqFileTask = null;
  if (task && typeof task.abort === 'function') task.abort();
}
function contentType(header) {
  const key = Object.keys(header || {}).find(name => name.toLowerCase() === 'content-type');
  return key ? String(header[key]).split(';')[0].toLowerCase() : '';
}
function bytes(data) {
  if (data instanceof ArrayBuffer || Object.prototype.toString.call(data) === '[object ArrayBuffer]') return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  throw Error('服务端未返回文件内容');
}
function responseError(data, status) {
  let body;
  try {
    const input = bytes(data).slice(0, 4096);
    body = JSON.parse(decodeURIComponent(Array.from(input, b => '%' + b.toString(16).padStart(2, '0')).join('')));
  } catch (_) { /* A binary or oversized error is reported by status only. */ }
  return { code: body?.code || status, postInvalid: Number(body?.code) === 1103000002, message: body?.msg || '文件读取失败，请重新加载后重试' };
}
function localPath(value) {
  const path = String(value || ''), root = wx.env && wx.env.USER_DATA_PATH;
  if (!path || !(path.startsWith('wxfile://') || path.startsWith('http://tmp/') || root && path.startsWith(root + '/'))) throw Error('下载文件路径无效');
  return path;
}
function verifyFile(path, mime) {
  const fs = wx.getFileSystemManager(), size = fs.statSync(path).size;
  if (!Number.isSafeInteger(size) || size <= 0 || size > MAX_FILE) throw Error('附件为空或超过 32 MB，请联系资料上传人');
  const prefix = bytes(fs.readFileSync(path, undefined, 0, Math.min(size, 4096)));
  if (mime.includes('json') || [123, 91].includes(prefix[0])) throw responseError(prefix, 400);
  const pdf = prefix[0] === 37 && prefix[1] === 80 && prefix[2] === 68 && prefix[3] === 70 && prefix[4] === 45;
  const png = [137, 80, 78, 71, 13, 10, 26, 10].every((value, index) => prefix[index] === value);
  const jpeg = prefix[0] === 255 && prefix[1] === 216 && prefix[2] === 255;
  if (mime === 'application/pdf' && pdf) return 'pdf';
  if (mime === 'image/png' && png || mime === 'image/jpeg' && jpeg) return 'image';
  throw Error('附件格式校验未通过，仅支持已验收的 PDF、PNG 和 JPEG');
}
async function download(page, action, ticket) {
  const path = api.attachmentPath(action);
  await authorize(page, ticket);
  return new Promise((resolve, reject) => {
    let mime = '';
    const task = wx.downloadFile({ url: http.base() + path, header: headers(), timeout: 120000,
      success(response) {
        let filePath;
        try {
          filePath = localPath(response.tempFilePath); own(page, filePath); ensure(page, ticket);
          if (response.statusCode !== 200) {
            const fs = wx.getFileSystemManager(), size = fs.statSync(filePath).size;
            throw responseError(fs.readFileSync(filePath, undefined, 0, Math.min(size, 4096)), response.statusCode);
          }
          const type = verifyFile(filePath, mime || contentType(response.header));
          ensure(page, ticket); resolve({ path: filePath, type });
        } catch (error) { if (filePath) remove(filePath); reject(error); }
      },
      fail(error) { reject(session.alive(page, ticket) ? { message: error?.errMsg || '附件下载失败，请重试' } : stale()); }
    });
    page._hqFileTask = task;
    task?.onHeadersReceived?.(response => { mime = contentType(response.header); });
  });
}
async function preview(page, action, ticket) {
  const file = await download(page, action, ticket); ensure(page, ticket);
  return new Promise((resolve, reject) => {
    const callbacks = { success: resolve, fail: () => reject(Error('文件已下载，但微信未能打开，请重试')) };
    if (file.type === 'pdf') wx.openDocument({ filePath: file.path, fileType: 'pdf', showMenu: false, ...callbacks });
    else wx.previewImage({ current: file.path, urls: [file.path], ...callbacks });
  });
}
async function exportCsv(page, dataset, filters, ticket) {
  const path = api.exportPath(dataset, filters);
  const companyIds = authorizedCompanies(await authorize(page, ticket), filters);
  const response = await new Promise((resolve, reject) => {
    page._hqFileTask = wx.request({ url: http.base() + path, method: 'POST', header: headers(), responseType: 'arraybuffer', timeout: 120000,
      success: resolve, fail: () => reject(Error('导出请求未完成，请重试')) });
  });
  ensure(page, ticket);
  const data = bytes(response.data);
  if (response.statusCode !== 200 || contentType(response.header) !== 'text/csv') throw responseError(data, response.statusCode);
  if (data.length < 3 || data.length > MAX_EXPORT || data[0] !== 239 || data[1] !== 187 || data[2] !== 191) throw Error('导出文件格式或大小无效，请缩小筛选范围');
  const currentCompanies = authorizedCompanies(await authorize(page, ticket), filters);
  if (JSON.stringify(companyIds) !== JSON.stringify(currentCompanies)) throw { code: 403, message: '导出期间公司范围已变化，请重新查询后导出' };
  const pathName = wx.env.USER_DATA_PATH + '/hq-export-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9) + '.csv';
  own(page, pathName);
  try {
    wx.getFileSystemManager().writeFileSync(pathName, response.data);
    ensure(page, ticket);
    return { path: pathName, scope: ticket.scope, companyIds, name: '总部' + ({ customers: '客户', projects: '工程', receipts: '收款', payables: '应付', payments: '付款', refunds: '退款' }[dataset]) + '记录.csv' };
  } catch (error) { remove(pathName); throw error; }
}
async function share(page, file, ticket) {
  if (!file || file.scope !== ticket.scope || !page._hqFiles?.has(file.path)) throw Error('请先重新生成当前筛选的导出文件');
  if (typeof wx.shareFileMessage !== 'function') throw Error('当前微信版本不支持文件分享，请更新微信后重试');
  const companies = new Set(authorizedCompanies(await authorize(page, ticket)));
  if (!Array.isArray(file.companyIds) || file.companyIds.some(id => !companies.has(id))) {
    remove(file.path); page._hqFiles.delete(file.path);
    throw { code: 403, message: '导出所含公司已不在授权范围，文件已失效，请重新导出' };
  }
  ensure(page, ticket);
  return new Promise((resolve, reject) => wx.shareFileMessage({ filePath: file.path, fileName: file.name, success: resolve,
    fail: error => reject(/cancel/i.test(error?.errMsg || '') ? { silent: true } : Error('文件分享未完成，可重新操作')) }));
}
module.exports = { cleanup, cancel, preview, exportCsv, share, download };
