const { identity } = require('./publication-pending');
const text = value => value == null ? '' : String(value);

function platform(page, env) {
  return ((env.studio.publicationPlatforms || [])[page.data.selectedAccount] || {}).platform || '';
}

function guard(page, env) {
  const scope = identity(env), sessionAlive = env.http.sessionGuard();
  const current = () => !page.__closed && sessionAlive() && page.data.view === 'publish'
    && page.actorScope === env.studio.scope() && identity(env) === scope;
  if (!current()) throw Error('工作身份已切换，请重新进入');
  return current;
}

async function load(page, env, preferredId) {
  let alive;
  try { alive = guard(page, env); } catch (_) { return; }
  const selectedPlatform = platform(page, env), seq = page.__publisherAccountSeq = (page.__publisherAccountSeq || 0) + 1;
  const preferred = text(preferredId || page.data.publisherAccountId);
  const current = () => alive() && seq === page.__publisherAccountSeq && selectedPlatform === platform(page, env);
  page.setData({ publisherAccountsLoading: true, publisherAccountsLoaded: false, publisherAccountsError: '',
    publisherAccounts: [], publisherAccountsAll: [], publisherAccountId: preferred, publicationAccount: page.data.publicationAccount || '' });
  try {
    const response = await env.api.publisherAccounts.list(selectedPlatform);
    if (!current()) return;
    if (!Array.isArray(response)) throw Error('账号列表返回异常，请重试');
    const all = response.filter(row => row && text(row.id) && row.platform === selectedPlatform)
      .map(row => ({ ...row, id: text(row.id), displayName: text(row.displayName), identifier: text(row.identifier), enabled: row.enabled === true }));
    const available = all.filter(row => row.enabled);
    const selected = available.find(row => row.id === preferred) || (available.length === 1 ? available[0] : null);
    page.setData({ publisherAccountsAll: all, publisherAccounts: available, publisherAccountsLoaded: true,
      publisherAccountId: selected ? selected.id : '', publicationAccount: selected ? selected.displayName : '' });
  } catch (error) {
    if (current()) page.setData({ publisherAccountsError: env.message(error) || '账号加载失败，请重试' });
  } finally { if (current()) page.setData({ publisherAccountsLoading: false }); }
}

function selectPlatform(page, env, index) {
  const next = Number(index);
  if (!(env.studio.publicationPlatforms || [])[next]) return;
  if (next === page.data.selectedAccount) { page.setData({ sheet: '' }); return; }
  page.setData({ selectedAccount: next, publisherAccountId: '', publicationAccount: '', sheet: '',
    publicationExistingId: '', publisherAccounts: [], publisherAccountsAll: [], publisherAccountsError: '' });
  page.persistEditor();
  return load(page, env);
}

function select(page, accountId) {
  const row = page.data.publisherAccounts.find(item => item.id === text(accountId) && item.enabled);
  if (!row) return;
  page.setData({ publisherAccountId: row.id, publicationAccount: row.displayName, publicationExistingId: '' });
  page.persistEditor();
}

function manage(page, env) {
  page.setData({ sheet: 'publisher-accounts', sheetTitle: '发布账号', publisherAccountEditError: '' });
  return load(page, env);
}

function edit(page, accountId) {
  if (page.data.publisherAccountBusy) return;
  const row = accountId ? page.data.publisherAccountsAll.find(item => item.id === text(accountId)) : null;
  if (accountId && !row) return;
  page.setData({ sheet: 'publisher-account-edit', sheetTitle: row ? '修改账号名称' : '添加发布账号',
    publisherAccountEditingId: row ? row.id : '', publisherAccountNameInput: row ? row.displayName : '',
    publisherAccountIdentifierInput: row ? row.identifier : '', publisherAccountEditError: '' });
}

async function save(page, env) {
  if (page.data.publisherAccountBusy) return;
  let alive;
  try { alive = guard(page, env); } catch (_) { return; }
  const displayName = text(page.data.publisherAccountNameInput).trim(), identifier = text(page.data.publisherAccountIdentifierInput).trim();
  if (!displayName || displayName.length > 64) return page.setData({ publisherAccountEditError: '账号名称须为1–64个字符' });
  if (!identifier || identifier.length > 100) return page.setData({ publisherAccountEditError: '请填写1–100个字符的平台账号标识' });
  const accountId = text(page.data.publisherAccountEditingId), selectedPlatform = platform(page, env);
  page.setData({ publisherAccountBusy: true, publisherAccountEditError: '' });
  try {
    const result = accountId
      ? await env.api.publisherAccounts.update(accountId, { displayName })
      : await env.api.publisherAccounts.create({ platform: selectedPlatform, displayName, identifier });
    if (!alive()) return;
    page.setData({ sheet: 'publisher-accounts', sheetTitle: '发布账号' });
    await load(page, env, accountId || result && result.id);
    if (alive()) page.persistEditor();
  } catch (error) {
    if (alive()) page.setData({ publisherAccountEditError: env.message(error) });
  } finally { if (alive()) page.setData({ publisherAccountBusy: false }); }
}

async function toggle(page, env, accountId) {
  if (page.data.publisherAccountBusy) return;
  const row = page.data.publisherAccountsAll.find(item => item.id === text(accountId));
  if (!row) return;
  let alive;
  try { alive = guard(page, env); } catch (_) { return; }
  page.setData({ publisherAccountBusy: true, publisherAccountEditError: '' });
  try {
    if (row.enabled) {
      const result = await new Promise((resolve, reject) => env.wx.showModal({ title: '停用这个账号？',
        content: '已有发布记录会保留，停用后不能用于新登记。', confirmText: '停用', success: resolve, fail: reject }));
      if (!result.confirm || !alive()) return;
    }
    if (!alive()) return;
    await env.api.publisherAccounts.update(row.id, { enabled: !row.enabled });
    if (alive()) { await load(page, env); page.persistEditor(); }
  } catch (error) { if (alive()) page.setData({ publisherAccountEditError: env.message(error) }); }
  finally { if (alive()) page.setData({ publisherAccountBusy: false }); }
}

module.exports = { load, selectPlatform, select, manage, edit, save, toggle };
