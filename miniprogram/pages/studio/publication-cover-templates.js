const { identity } = require('./publication-pending');
const PREFIX = 'zs-publication-cover-generation-v1:';
const PRESETS = ['9:16', '3:4'];
const STATUSES = ['READY', 'FAILED', 'PROCESSING', 'UNCERTAIN', 'ARCHIVE_PENDING'];
const id = value => value == null ? '' : String(value);
const copy = value => JSON.parse(JSON.stringify(value));
const validAsset = value => /^[1-9]\d{0,18}$/.test(id(value));

function context(page, env) {
  const owner = identity(env), workId = id(page.data.workId), sessionAlive = env.http.sessionGuard();
  if (!workId) throw Error('请先选择已保存的作品');
  const alive = () => !page.__closed && sessionAlive() && page.data.view === 'publish'
    && page.actorScope === env.studio.scope() && identity(env) === owner && id(page.data.workId) === workId;
  if (!alive()) throw Error('工作身份已切换，请重新进入');
  return { owner, workId, alive, storageKey: PREFIX + encodeURIComponent(owner) + ':' + encodeURIComponent(workId) };
}

function belongs(ctx, pending) { return ctx.owner === pending.identity && ctx.workId === id(pending.workId); }

function read(env, ctx) {
  const saved = env.wx.getStorageSync(ctx.storageKey);
  if (!saved) return null;
  const body = saved.payload;
  if (saved.version !== 1 || saved.identity !== ctx.owner || id(saved.workId) !== ctx.workId
      || !saved.key || !body || typeof body.templateId !== 'string' || !body.templateId
      || typeof body.title !== 'string' || !body.title.trim() || body.title.length > 200
      || !PRESETS.includes(body.canvasPreset)) throw Error('上次封面生成的恢复信息不可用，请联系管理员核对');
  const pending = { ...copy(saved), storageKey: ctx.storageKey };
  if (pending.result) {
    try { pending.result = resultOf(pending.result); } catch (_) { delete pending.result; }
  }
  return pending;
}

function active(page, env, ctx) {
  const saved = read(env, ctx), cached = page.__coverGeneration;
  // Another open page may have started the next request. Its durable key wins.
  // A known terminal response may be kept in memory only for that same key.
  if (saved && cached && saved.key === cached.key && cached.result && cached.result.terminal
      && !(saved.result && saved.result.terminal)) saved.result = copy(cached.result);
  return saved;
}

function store(env, pending) {
  const saved = copy(pending); delete saved.storageKey;
  env.wx.setStorageSync(pending.storageKey, saved);
}

function forget(env, pending) {
  const saved = env.wx.getStorageSync(pending.storageKey);
  if (saved && saved.key === pending.key) env.wx.removeStorageSync(pending.storageKey);
}

function description(result) {
  if (!result) return '上次生成结果待确认，请继续核对';
  if (result.status === 'READY') return '封面已生成，请核对预览后使用';
  if (result.status === 'PROCESSING') return '封面正在生成，请稍后继续核对';
  if (result.status === 'UNCERTAIN') return '生成结果待核实，请联系管理员；核实前请勿重新生成';
  if (result.status === 'ARCHIVE_PENDING') return '封面已生成，保存暂未完成，请继续核对';
  return result.message || '封面生成失败，可以调整后重新生成';
}

function show(page, pending, restoreInputs = false) {
  page.__coverGeneration = pending;
  const result = pending && pending.result;
  const terminal = !!(result && result.terminal);
  const patch = { coverGenerationPending: !!pending, coverGenerationTerminal: terminal,
    coverGenerationStatus: result ? result.status : pending ? 'PENDING' : '',
    coverGenerationLocked: !!pending && !(terminal && result.status === 'FAILED'),
    coverGenerationMessage: pending ? description(result) : '',
    coverGenerationRequestTitle: pending ? pending.payload.title : '',
    coverGeneratedAssetId: result && result.status === 'READY' ? id(result.coverAssetId) : '' };
  if (!patch.coverGeneratedAssetId || patch.coverGeneratedAssetId !== page.data.coverGeneratedAssetId) {
    patch.coverGeneratedPreview = ''; patch.coverGeneratedPreviewError = '';
  }
  if (pending && restoreInputs) Object.assign(patch, { coverTemplateId: pending.payload.templateId,
    coverGenerationTitle: pending.payload.title, coverCanvasPreset: pending.payload.canvasPreset });
  page.setData(patch);
}

function error(page, message) { page.setData({ coverGenerationError: message || '封面服务暂不可用，请稍后重试' }); }

async function previewCandidate(page, env) {
  let ctx, pending;
  try { ctx = context(page, env); pending = active(page, env, ctx); } catch (_) { return; }
  if ((pending && pending.key) !== (page.__coverGeneration && page.__coverGeneration.key)) show(page, pending, true);
  if (!pending || !pending.result || pending.result.status !== 'READY') return;
  if (!belongs(ctx, pending)) return;
  const assetId = id(pending.result.coverAssetId);
  const seq = page.__coverCandidateSeq = (page.__coverCandidateSeq || 0) + 1;
  const alive = () => ctx.alive() && seq === page.__coverCandidateSeq && page.__coverGeneration
    && page.__coverGeneration.key === pending.key && page.data.coverGeneratedAssetId === assetId;
  page.setData({ coverGeneratedPreviewLoading: true, coverGeneratedPreviewError: '' });
  try {
    const preview = env.previewCover || require('./publication-cover').preview;
    const url = await preview(env, assetId, alive);
    if (alive()) page.setData({ coverGeneratedPreview: url });
  } catch (failure) {
    if (alive()) page.setData({ coverGeneratedPreview: '', coverGeneratedPreviewError: env.message(failure) || '封面预览读取失败，请重试' });
  } finally { if (alive()) page.setData({ coverGeneratedPreviewLoading: false }); }
}

async function restore(page, env) {
  try {
    const ctx = context(page, env);
    const pending = read(env, ctx);
    page.__coverGenerationStorageKey = ctx.storageKey;
    show(page, pending, true);
    if (pending && pending.result && pending.result.status === 'READY') await previewCandidate(page, env);
    return pending;
  } catch (failure) {
    page.setData({ coverGenerationPending: true, coverGenerationLocked: true });
    error(page, failure.message);
    return null;
  }
}

async function open(page, env) {
  if (page.data.coverUploading || page.data.coverLoading) return;
  let ctx;
  try { ctx = context(page, env); } catch (failure) { return error(page, failure.message); }
  page.setData({ sheet: 'cover-templates', sheetTitle: '模板生成封面', coverGenerationTitle: page.data.coverGenerationTitle || page.data.title || '' });
  if (page.__coverGenerationStorageKey !== ctx.storageKey) await restore(page, env);
  else {
    try {
      const pending = active(page, env, ctx);
      const changed = (pending && pending.key) !== (page.__coverGeneration && page.__coverGeneration.key);
      show(page, pending, changed);
      if (changed) page.setData({ coverGenerationError: '' });
      if (pending && pending.result && pending.result.status === 'READY') await previewCandidate(page, env);
    } catch (failure) { return error(page, failure.message); }
  }
  if (ctx.alive() && !page.data.coverGenerationLocked && !page.data.coverTemplatesLoaded && !page.data.coverTemplatesLoading) return load(page, env, false);
}

async function load(page, env, more = false) {
  if (page.data.coverTemplatesLoading) return;
  let ctx;
  try { ctx = context(page, env); } catch (failure) { return error(page, failure.message); }
  const nextPage = more ? (page.data.coverTemplatesPage || 1) + 1 : 1, size = 12;
  const seq = page.__coverTemplatesSeq = (page.__coverTemplatesSeq || 0) + 1;
  const alive = () => ctx.alive() && seq === page.__coverTemplatesSeq;
  page.setData({ coverTemplatesLoading: true, coverTemplatesError: '' });
  try {
    const response = await env.api.covers.list({ page: nextPage, size });
    if (!alive()) return;
    if (!response || !Array.isArray(response.list)) throw Error('封面模板响应不可用，请重试');
    const rows = response.list.filter(row => row && id(row.id)).map(row => ({ id: id(row.id),
      title: id(row.title) || '封面模板', url: /^https:\/\//i.test(id(row.url)) ? id(row.url) : '' }));
    const all = more ? (page.data.coverTemplates || []).concat(rows) : rows;
    const templates = all.filter((row, index) => all.findIndex(other => other.id === row.id) === index);
    const total = Number(response.total);
    page.setData({ coverTemplates: templates, coverTemplatesLoaded: true, coverTemplatesPage: nextPage,
      coverTemplatesHasMore: Number.isFinite(total) && nextPage * size < total });
  } catch (failure) {
    if (alive()) page.setData({ coverTemplatesError: Number(failure && failure.code) === 1114000006
      ? '封面服务尚未配置，请联系管理员' : env.message(failure) || '封面模板加载失败，请重试' });
  } finally { if (alive()) page.setData({ coverTemplatesLoading: false }); }
}

function edit(page, field, value) {
  if (page.data.coverGenerating || page.data.coverGenerationLocked || page.data.coverUsing) return;
  if (field === 'coverTemplateId' && !(page.data.coverTemplates || []).some(row => row.id === id(value))) return;
  if (field === 'coverCanvasPreset' && !PRESETS.includes(value)) return;
  if (!['coverTemplateId', 'coverGenerationTitle', 'coverCanvasPreset'].includes(field)) return;
  page.setData({ [field]: id(value), coverGenerationError: '' });
}

function resultOf(response) {
  const terminal = response && ['READY', 'FAILED'].includes(response.status);
  if (!response || !STATUSES.includes(response.status) || response.terminal !== terminal
      || !id(response.requestId) || (response.status === 'READY' && !validAsset(response.coverAssetId))) {
    throw Error('生成结果尚未确认，请继续核对');
  }
  return { requestId: id(response.requestId), status: response.status, terminal,
    message: id(response.message), ...(response.status === 'READY' ? { coverAssetId: id(response.coverAssetId) } : {}) };
}

async function run(page, env, pending) {
  const ctx = context(page, env);
  if (!belongs(ctx, pending)) throw Error('工作身份或作品已切换，请重新进入');
  if (page.data.coverGenerating || page.data.coverUsing) return;
  if (pending.result && pending.result.terminal) return previewCandidate(page, env);
  const alive = () => ctx.alive() && page.__coverGeneration && page.__coverGeneration.key === pending.key;
  page.__coverGenerationFlight = pending.key;
  page.setData({ coverGenerating: true, coverGenerationError: '' });
  try {
    const result = resultOf(await env.api.covers.generate(copy(pending.payload), pending.key));
    pending.result = result;
    let storageError = false, saved;
    try {
      saved = read(env, ctx);
      if (saved && saved.key === pending.key) store(env, pending);
    } catch (_) { storageError = true; }
    if (!storageError && (!saved || saved.key !== pending.key)) {
      if (ctx.alive()) { show(page, saved, true); error(page, '封面生成记录已更新，请核对当前结果'); }
      return;
    }
    if (!alive()) return;
    show(page, pending);
    if (storageError) error(page, '生成结果已返回；重新进入后请继续核对上次结果');
    if (result.status === 'READY') await previewCandidate(page, env);
  } catch (failure) {
    if (alive()) error(page, (env.message(failure) || '请求暂未完成') + '；请继续核对上次结果，不要重复生成');
  } finally { if (ctx.alive() && page.__coverGenerationFlight === pending.key) page.setData({ coverGenerating: false }); }
}

async function generate(page, env) {
  if (page.data.coverGenerating || page.data.coverUsing) return;
  try {
    const ctx = context(page, env), prior = active(page, env, ctx);
    if (prior && !(prior.result && prior.result.terminal && prior.result.status === 'FAILED')) {
      show(page, prior);
      return error(page, prior.result && prior.result.status === 'READY' ? '请先使用此封面，或选择重新生成' : '请先继续核对上次生成结果');
    }
    const title = id(page.data.coverGenerationTitle).trim(), templateId = id(page.data.coverTemplateId);
    if (!templateId || !(page.data.coverTemplates || []).some(row => row.id === templateId)) throw Error('请选择封面模板');
    if (!title || title.length > 200) throw Error('请输入 1 至 200 字的封面标题');
    if (!PRESETS.includes(page.data.coverCanvasPreset)) throw Error('请选择封面比例');
    const pending = { version: 1, identity: ctx.owner, workId: ctx.workId, storageKey: ctx.storageKey,
      key: 'cover-' + Date.now() + '-' + Math.random().toString(36).slice(2), createdAt: Date.now(),
      payload: { templateId, title, canvasPreset: page.data.coverCanvasPreset } };
    store(env, pending); // Persist the original request before any provider call can occur.
    page.__coverGenerationStorageKey = ctx.storageKey;
    show(page, pending);
    return await run(page, env, pending);
  } catch (failure) { error(page, failure.message || '无法保存封面生成恢复信息，请稍后重试'); }
}

async function resume(page, env) {
  if (page.data.coverGenerating || page.data.coverUsing) return;
  try {
    const pending = active(page, env, context(page, env));
    if (!pending) throw Error('没有待核对的封面生成记录');
    show(page, pending);
    return await run(page, env, pending);
  } catch (failure) { error(page, failure.message || '暂时无法核对封面生成结果'); }
}

async function use(page, env) {
  if (page.data.coverGenerating || page.data.coverUsing || page.data.coverUploading || page.data.coverLoading) return;
  let ctx, pending;
  try { ctx = context(page, env); pending = active(page, env, ctx); } catch (failure) { return error(page, failure.message); }
  if ((pending && pending.key) !== (page.__coverGeneration && page.__coverGeneration.key)) {
    show(page, pending, true);
    error(page, '封面生成记录已更新，请核对当前结果后使用');
    return previewCandidate(page, env);
  }
  if (!pending || !pending.result || pending.result.status !== 'READY' || !page.data.coverGeneratedPreview) return;
  if (!belongs(ctx, pending)) return error(page, '工作身份或作品已切换，请重新进入');
  page.setData({ coverUsing: true, coverGenerationError: '' });
  try {
    const apply = env.useCover || require('./publication-cover').useAsset;
    if (!await apply(page, env, pending.result.coverAssetId) || !ctx.alive()) return;
    const latest = active(page, env, ctx);
    page.__coverCandidateSeq = (page.__coverCandidateSeq || 0) + 1;
    if (latest && latest.key !== pending.key) {
      show(page, latest, true);
      error(page, '封面已使用；另有生成记录可继续核对');
    } else { forget(env, pending); show(page, null); }
  } catch (failure) { if (ctx.alive()) error(page, env.message(failure) || '封面使用失败，请重试'); }
  finally { if (ctx.alive()) page.setData({ coverUsing: false }); }
}

async function reset(page, env) {
  if (page.data.coverGenerating || page.data.coverUsing) return;
  let ctx, pending;
  try {
    ctx = context(page, env);
    pending = active(page, env, ctx);
    if ((pending && pending.key) !== (page.__coverGeneration && page.__coverGeneration.key)) {
      show(page, pending, true);
      return error(page, '封面生成记录已更新，请先核对当前结果');
    }
    if (!pending || !pending.result || !pending.result.terminal) return;
    if (!belongs(ctx, pending)) return error(page, '工作身份或作品已切换，请重新进入');
    if (pending.result.status === 'READY') {
      const answer = await new Promise(resolve => env.wx.showModal({ title: '重新生成封面',
        content: '这张生成封面尚未使用，确认重新选择模板和标题？', confirmText: '重新选择', success: resolve,
        fail: () => resolve({ confirm: false }) }));
      if (!answer.confirm || !ctx.alive()) return;
    }
    const latest = active(page, env, ctx);
    if (!latest || latest.key !== pending.key) {
      show(page, latest, true);
      return error(page, '封面生成记录已更新，请先核对当前结果');
    }
    forget(env, pending);
    page.__coverCandidateSeq = (page.__coverCandidateSeq || 0) + 1;
    show(page, null);
    page.setData({ coverGenerationError: '', coverGeneratedPreviewLoading: false });
    if (!page.data.coverTemplatesLoaded) await load(page, env, false);
  } catch (failure) { if (!ctx || ctx.alive()) error(page, failure.message || '恢复信息保存失败，请稍后重试'); }
}

module.exports = { restore, open, load, edit, generate, resume, use, reset, previewCandidate };
