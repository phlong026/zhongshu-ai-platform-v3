// A registration may have reached the server even when the response was lost.
// Keep its exact request durably, partitioned by the authenticated operator and post.
const PREFIX = 'zs-publication-pending-v1:';
const DUPLICATE = 1114000013;
const copy = value => JSON.parse(JSON.stringify(value));
const id = value => value == null ? '' : String(value);

function identity(env) {
  const post = env.post || {};
  const userId = env.http.currentUserId();
  if (!env.http.token() || !userId || !post.orgId || !post.postId) throw Error('登录信息不完整，请重新登录后登记');
  return JSON.stringify([id(env.http.tenantId()), id(userId), id(post.orgId), id(post.postId)]);
}

function keyFor(env, workId) {
  return PREFIX + encodeURIComponent(identity(env)) + ':' + encodeURIComponent(id(workId));
}

function read(env, workId) {
  const key = keyFor(env, workId);
  const value = env.wx.getStorageSync(key);
  if (!value) return null;
  if (value.version !== 1 || value.identity !== identity(env) || !value.key || !value.payload
      || id(value.payload.workId) !== id(workId)) throw Error('上次登记的恢复信息不可用，请先到发布记录核对');
  return Object.assign(copy(value), { storageKey: key });
}

function store(env, pending) {
  const value = copy(pending);
  delete value.storageKey;
  env.wx.setStorageSync(pending.storageKey, value);
}

function forget(env, pending) {
  const saved = env.wx.getStorageSync(pending.storageKey);
  if (saved && saved.key === pending.key) env.wx.removeStorageSync(pending.storageKey);
}

function show(page, pending) {
  page.__publicationSubmission = pending;
  page.setData({ publicationPending: !!pending,
    publicationPendingTitle: pending ? pending.payload.title : '',
    publicationPendingPlatform: pending ? (pending.payload.platform === 'douyin' ? '抖音' : '视频号') : '' });
}

function restore(page, env) {
  try {
    const pending = read(env, page.data.workId);
    show(page, pending);
    return pending;
  } catch (error) {
    page.setData({ publicationPending: true, operationError: error.message });
    return null;
  }
}

function fail(page, env, message) {
  page.setData({ operationError: message });
  env.wx.showToast({ title: message, icon: 'none' });
}

async function run(page, env, pending) {
  if (page.data.publishSubmitting) return;
  const sessionAlive = env.http.sessionGuard(), scope = page.actorScope;
  const current = () => !page.__closed && sessionAlive() && page.data.view === 'publish'
    && scope === page.actorScope && scope === env.studio.scope() && identity(env) === pending.identity;
  page.setData({ publishSubmitting: true, operationError: '' });
  try {
    const result = pending.recordId ? { id: pending.recordId }
      : await env.api.publications.create(copy(pending.payload), pending.key);
    const recordId = id(result && (result.id || result.publicationId));
    if (!/^[1-9]\d*$/.test(recordId)) throw Error('登记响应未确认，请核对上次登记');
    // A late success belongs to this stored request, never to a newly opened form.
    pending.recordId = recordId;
    const saved = env.wx.getStorageSync(pending.storageKey);
    if (saved && saved.key === pending.key) store(env, pending);
    if (!current()) return;
    page.persistEditor(); // Preserve edits made after the immutable request was created.
    forget(env, pending);
    show(page, null);
    page.go('receipt', '&record=' + encodeURIComponent(recordId));
  } catch (error) {
    if (!current()) return;
    const code = Number(error && error.code);
    const duplicate = code === DUPLICATE && error.data;
    if (duplicate && /^[1-9]\d*$/.test(id(duplicate.existingPublicationId))) {
      forget(env, pending);
      show(page, null);
      const continuation = duplicate.status === 'published' ? '这条内容已发布，可以查看发布结果'
        : ['publishing', 'verify'].includes(duplicate.status) ? '这条内容已有登记，可以继续填写发布链接'
        : '这条内容已有登记，请查看已有记录';
      page.setData({ publicationExistingId: id(duplicate.existingPublicationId),
        publicationExistingStatus: id(duplicate.status),
        operationError: duplicate.sameInput ? continuation : '该作品在此账号已有登记，本页修改已保留，请先查看已有记录' });
      page.persistEditor();
      return;
    }
    const uncertain = !Number.isFinite(code) || code <= 0 || (code >= 500 && code < 600);
    if (!uncertain) { forget(env, pending); show(page, null); }
    fail(page, env, uncertain ? '登记结果待确认，请核对上次登记；本页修改会保留' : env.message(error));
  } finally {
    if (current()) page.setData({ publishSubmitting: false });
  }
}

function payload(page, env) {
  const data = page.data;
  if (data.invalidMessage || !id(data.workId).trim()) throw Error('请先选择已保存的作品');
  const invalid = env.validate({ title: data.title, publishText: data.publishText }, Date.now());
  if (invalid) throw Error(invalid);
  const platform = (env.studio.publicationPlatforms || [])[data.selectedAccount];
  if (!platform) throw Error('请选择发布平台');
  if (data.publisherAccountsLoading) throw Error('发布账号加载中，请稍候');
  if (!data.publisherAccountsLoaded || data.publisherAccountsError) throw Error('请先重新加载发布账号');
  const account = (data.publisherAccounts || []).find(row => id(row.id) === id(data.publisherAccountId)
    && row.enabled === true && row.platform === platform.platform);
  if (!account) throw Error('请选择发布账号');
  return { workId: id(data.workId), title: data.title.trim(), publishText: data.publishText.trim(),
    tags: id(data.tags).trim(), publisherAccountId: id(account.id), platform: platform.platform };
}

async function submit(page, env) {
  if (page.data.publishSubmitting) return;
  try {
    if (page.__publicationSubmission || read(env, page.data.workId)) {
      if (page.__publicationSubmission) show(page, page.__publicationSubmission);
      else restore(page, env);
      return fail(page, env, '请先核对上次登记，本页修改会保留');
    }
    const body = payload(page, env);
    const pending = { version: 1, identity: identity(env), payload: copy(body),
      key: 'publication-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      createdAt: Date.now(), storageKey: keyFor(env, body.workId) };
    // Storage failure must prevent the request, otherwise a restart could duplicate it.
    store(env, pending);
    show(page, pending);
    page.setData({ publicationExistingId: '', publicationExistingStatus: '' });
    return await run(page, env, pending);
  } catch (error) {
    fail(page, env, error.message || '无法保存登记恢复信息，请稍后重试');
  }
}

async function resume(page, env) {
  if (page.data.publishSubmitting) return;
  try {
    const pending = page.__publicationSubmission || read(env, page.data.workId);
    if (!pending) return fail(page, env, '未找到待确认登记，请到发布记录核对');
    if (pending.identity !== identity(env)) return fail(page, env, '工作身份已切换，请重新进入');
    show(page, pending);
    return await run(page, env, pending);
  } catch (error) { fail(page, env, error.message || '暂时无法核对登记，请重试'); }
}

module.exports = { identity, keyFor, read, restore, submit, resume };
