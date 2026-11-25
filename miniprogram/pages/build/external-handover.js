const http = require('../../api/http');
const scope = require('../../utils/api-scope');
const id = value => typeof value === 'number' && !Number.isSafeInteger(value) ? '' : /^[1-9]\d{0,18}$/.test(String(value || ''))
  && (String(value).length < 19 || String(value) <= '9223372036854775807') ? String(value) : '';
const slots = ['RESULT', 'DRAWING', 'REVIEW', 'CONFIRMATION'];
const base = commissionId => '/franchise/commissions/' + encodeURIComponent(commissionId) + '/external-handover';
const get = commissionId => http.get(base(commissionId));
const revoked = error => error?.postInvalid || [401, 403, 404, 1099000002, 1110000000, 1110000001, 1112000000].includes(Number(error?.code));
function packet(value, projectId, commissionId, version) {
  if (!id(projectId) || !id(commissionId) || !Number.isSafeInteger(version) || version < 1
      || value?.mode !== 'EXTERNAL' || id(value.commissionId) !== commissionId || id(value.projectId) !== projectId || !Array.isArray(value.packets))
    throw { code: 1099000002, msg: '工程与移交版本不匹配，请重新进入项目' };
  const record = value.packets.find(row => row.version === version && row.status === 'ACCEPTED');
  if (!record || !Array.isArray(record.files) || record.files.length !== 4 || new Set(record.files.map(file => file.slot)).size !== 4
      || record.files.some(file => !slots.includes(file.slot) || !id(file.assetId))) throw { code: 1099000002, msg: '当前身份不能查看该版工程移交资料' };
  return record;
}
async function bind(detail, data, alive) {
  data.externalHandoverUrl = ''; data.externalHandoverVersion = 0;
  if (detail.handoverMode !== 'EXTERNAL') return;
  if (detail.canReadHandover !== true) {
    data.docs = (data.docs || []).slice((detail.documents || []).length);
    if (data.p) data.p.documents = data.docs;
    return;
  }
  const projectId = id(detail.id), commissionId = id(detail.sourceCommissionId), version = detail.externalHandoverVersion;
  if (!projectId || !commissionId || !Number.isSafeInteger(version) || version < 1) throw { code: 1099000002, msg: '工程移交编号缺失，请重新核对' };
  const response = await get(commissionId); if (!alive()) return;
  const record = packet(response, projectId, commissionId, version), documents = detail.documents || [];
  const converted = documents.map(doc => {
    // fileRef is an opaque decimal asset ID. Never parse it as a JS number or use a generic BUSINESS_DOCUMENT ticket.
    const assetId = id(doc.fileRef), file = record.files.find(row => id(row.assetId) === assetId);
    if (!assetId || !file) throw { code: 1099000002, msg: '工程资料未绑定当前移交版本' };
    return { name: doc.name, date: '', file: { name: doc.name || file.title, assetId, commissionId, externalVersion: version, slot: file.slot, externalHandover: true } };
  });
  data.docs = converted.concat((data.docs || []).slice(documents.length));
  if (data.p) data.p.documents = data.docs;
  data.externalHandoverUrl = '/pages/external-handover/index?commissionId=' + encodeURIComponent(commissionId);
  data.externalHandoverVersion = version;
}
function clear(page, error) {
  page.__accessEpoch = (page.__accessEpoch || 0) + 1; page.__externalPreview = null; page.__previewClosed = true;
  page.__fileSelectionGeneration = (page.__fileSelectionGeneration || 0) + 1; page.__idemKeys = {}; page.initialized = false;
  const patch = {};
  for (const [key, value] of Object.entries(page.data)) {
    if (['view', 'title', 'expenseMode', 'platEng'].includes(key)) continue;
    patch[key] = ['form', 'expanded', 'fileNames'].includes(key) ? {} : Array.isArray(value) ? [] : typeof value === 'boolean' ? false
      : typeof value === 'number' ? 0 : typeof value === 'string' ? '' : null;
  }
  page.setData({ ...patch, p: null, error });
}
async function preview(page, data) {
  const savedScope = scope(), projectId = id(page.id), commissionId = id(data.commissionId), version = data.externalVersion, assetId = id(data.assetId), slot = data.slot;
  const referenced = () => (page.data.docs || []).some(row => row.file?.externalHandover && row.file.assetId === assetId
    && row.file.commissionId === commissionId && row.file.externalVersion === version && row.file.slot === slot);
  if (!savedScope || !projectId || !commissionId || !assetId || !Number.isSafeInteger(version) || version < 1 || !slots.includes(slot) || !referenced()) return;
  const request = page.__externalPreview = {}, epoch = page.__accessEpoch || 0;
  const alive = () => page.__externalPreview === request && !page.__disposed && !page.__inactive && !page.__previewClosed
    && id(page.id) === projectId && scope() === savedScope && (page.__accessEpoch || 0) === epoch && referenced();
  try {
    const ticket = await http.post(base(commissionId) + '/versions/' + version + '/assets/' + slot + '/download-tickets'); if (!alive()) return;
    const file = await require('../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket?.downloadUrl, alive, label: '工程移交资料' }); if (!alive()) return;
    const fresh = await get(commissionId); if (!alive()) return;
    if (!packet(fresh, projectId, commissionId, version).files.some(row => row.slot === slot && id(row.assetId) === assetId)) throw { code: 1099000002, msg: '移交资料权限已变化' };
    if (/^image\//i.test(file.contentType)) wx.previewImage({ urls: [file.tempFilePath] });
    else wx.openDocument({ filePath: file.tempFilePath, fileType: 'pdf', showMenu: true, fail: () => { if (alive()) page.fail('文件暂不能打开，请重试'); } });
  } catch (error) {
    if (!alive()) return;
    if (revoked(error)) return clear(page, String(error.msg || error.message || '工程资料权限已变化'));
    try { packet(await get(commissionId), projectId, commissionId, version); if (!alive()) return; }
    catch (accessError) { if (alive() && revoked(accessError)) return clear(page, String(accessError.msg || accessError.message || '工程资料权限已变化')); }
    if (alive()) page.fail(String(error.msg || error.message || '工程移交资料查看失败，请重试'));
  }
}
module.exports = { bind, preview, clear, revoked };
