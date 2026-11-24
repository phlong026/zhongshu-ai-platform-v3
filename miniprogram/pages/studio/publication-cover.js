const { identity } = require('./publication-pending');
const id = value => value == null ? '' : String(value);
const validId = value => /^[1-9]\d{0,18}$/.test(id(value));
const call = (wx, method, options) => new Promise((resolve, reject) => wx[method]({ ...options, success: resolve, fail: reject }));

function guard(page, env, view = page.data.view) {
  const scope = identity(env), workId = id(page.data.workId), sessionAlive = env.http.sessionGuard();
  const current = () => !page.__closed && sessionAlive() && page.data.view === view
    && page.actorScope === env.studio.scope() && identity(env) === scope
    && (view !== 'publish' || id(page.data.workId) === workId);
  if (!current()) throw Error('工作身份已切换，请重新进入');
  return current;
}

async function preview(env, assetId, alive = () => true) {
  if (!validId(assetId)) throw Error('封面未保存，请重新选择');
  const ticket = await env.api.assets.downloadUrl(id(assetId));
  if (!alive()) throw { cancelled: true };
  const url = id(ticket && ticket.downloadUrl);
  if (!url) throw Error('封面预览暂不可用');
  const origin = id(env.http.base()).replace(/\/admin-api\/?$/, '');
  if (/^https:\/\//i.test(url) && !url.startsWith(origin + '/')) return url;
  const download = env.downloadAsset || require('../../utils/b-asset-download').downloadTicketFile;
  const result = await download({ http: env.http, downloadUrl: url, alive, label: '封面' });
  if (!alive()) throw { cancelled: true };
  return result.tempFilePath;
}

async function restore(page, env) {
  if (!page.data.coverAssetId) { page.setData({ cover: '', coverReady: false }); return; }
  let alive;
  try { alive = guard(page, env, 'publish'); } catch (_) { return; }
  const assetId = id(page.data.coverAssetId), seq = page.__publicationCoverSeq = (page.__publicationCoverSeq || 0) + 1;
  const current = () => alive() && seq === page.__publicationCoverSeq && id(page.data.coverAssetId) === assetId;
  page.setData({ cover: '', coverLoading: true, coverError: '', coverReady: false });
  try {
    const url = await preview(env, assetId, current);
    if (current()) page.setData({ cover: url, coverReady: true });
  } catch (error) { if (current()) page.setData({ coverError: env.message(error) || '封面读取失败，请重试' }); }
  finally { if (current()) page.setData({ coverLoading: false }); }
}

async function upload(page, env) {
  if (page.__publicationCoverChoosing || page.data.coverUploading || page.data.coverGenerating) return;
  let alive;
  try { alive = guard(page, env, 'publish'); } catch (_) { return; }
  page.__publicationCoverChoosing = true;
  let selected;
  try {
    // The picker may be cancelled; do not show an upload state before a file exists.
    const result = env.wx.chooseMedia
      ? await call(env.wx, 'chooseMedia', { count: 1, mediaType: ['image'], sourceType: ['album', 'camera'] })
      : await call(env.wx, 'chooseImage', { count: 1, sourceType: ['album', 'camera'] });
    if (!alive()) return;
    selected = result.tempFiles && result.tempFiles[0] && (result.tempFiles[0].tempFilePath || result.tempFiles[0].path)
      || result.tempFilePaths && result.tempFilePaths[0];
    if (!selected) return;
  } catch (error) {
    if (alive() && !/cancel|取消/i.test(id(error && (error.errMsg || error.message)))) page.setData({ coverError: '无法读取图片，请重新选择' });
    return;
  } finally { page.__publicationCoverChoosing = false; }

  const seq = page.__publicationCoverSeq = (page.__publicationCoverSeq || 0) + 1;
  const current = () => alive() && seq === page.__publicationCoverSeq;
  page.setData({ coverUploading: true, coverError: '', sheet: '' });
  try {
    const info = await call(env.wx, 'getImageInfo', { src: selected });
    if (!current()) return;
    const mime = info.type === 'png' ? 'image/png' : ['jpeg', 'jpg'].includes(info.type) ? 'image/jpeg' : '';
    if (!mime) throw Error('请选择 JPG 或 PNG 图片作为封面');
    const uploader = env.uploadContent || require('../../utils/content-upload');
    const assetId = id(await uploader(selected, mime, current, { maxBytes: 10 * 1024 * 1024 }));
    if (!current()) return;
    if (!validId(assetId)) throw Error('封面保存结果无效，请重新上传');
    let url = selected;
    try { url = await preview(env, assetId, current); } catch (error) { if (!current()) return; }
    if (!current()) return;
    page.setData({ coverAssetId: assetId, cover: url, coverReady: true, coverError: '' });
    page.persistEditor();
  } catch (error) { if (current()) page.setData({ coverError: env.message(error) || '封面上传失败，请重试' }); }
  finally { if (current()) page.setData({ coverUploading: false, coverLoading: false }); }
}

async function useAsset(page, env, assetId) {
  const alive = guard(page, env, 'publish');
  const seq = page.__publicationCoverSeq = (page.__publicationCoverSeq || 0) + 1;
  const current = () => alive() && seq === page.__publicationCoverSeq;
  page.setData({ coverLoading: true });
  try {
    const url = await preview(env, assetId, current);
    if (!current()) return false;
    page.setData({ coverAssetId: id(assetId), cover: url, coverReady: true, coverError: '', sheet: '' });
    page.persistEditor();
    return true;
  } finally { if (current()) page.setData({ coverLoading: false }); }
}

function clear(page) {
  if (page.data.coverUploading) return;
  page.__publicationCoverSeq = (page.__publicationCoverSeq || 0) + 1;
  page.setData({ coverAssetId: '', cover: '', coverReady: false, coverError: '', coverLoading: false, sheet: '' });
  page.persistEditor();
}

async function hydrate(page, env) {
  if (!['records', 'detail', 'receipt'].includes(page.data.view)) return;
  let alive;
  try { alive = guard(page, env); } catch (_) { return; }
  const list = page.data.view === 'records', rows = list ? page.data.records : [page.data.detail];
  const seq = page.__publicationPreviewSeq = (page.__publicationPreviewSeq || 0) + 1;
  const current = () => alive() && seq === page.__publicationPreviewSeq;
  const pending = (rows || []).filter(row => row && validId(row.coverAssetId));
  let next = 0;
  await Promise.all([0, 1, 2, 3].map(async () => {
    while (next < pending.length && current()) {
      const row = pending[next++];
      const patch = changes => {
        if (!current()) return;
        const index = list ? page.data.records.findIndex(item => id(item.id) === id(row.id) && id(item.coverAssetId) === id(row.coverAssetId)) : 0;
        if (list && index < 0 || !list && (!page.data.detail || id(page.data.detail.id) !== id(row.id) || id(page.data.detail.coverAssetId) !== id(row.coverAssetId))) return;
        const prefix = list ? 'records[' + index + '].' : 'detail.';
        const data = {}; for (const key of Object.keys(changes)) data[prefix + key] = changes[key];
        page.setData(data);
      };
      try { const url = await preview(env, row.coverAssetId, current); patch({ cover: url, coverError: false }); }
      catch (error) { patch({ cover: '', coverError: true }); }
    }
  }));
}

module.exports = { preview, restore, upload, useAsset, clear, hydrate };
