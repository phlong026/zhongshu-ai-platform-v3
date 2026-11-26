const sections = [
  { key: 'startSite', label: '四通一平现场情况' },
  { key: 'startOwner', label: '业主确认情况' },
  { key: 'startManager', label: '项目经理确认情况' }
];
const noteLimit = 1000;

function command(form) {
  const entries = sections.filter(field => form[field.key]?.trim());
  let note = form.note || '';
  if (entries.length) {
    const lines = entries.map(field => field.label + '：' + form[field.key]);
    if (note.trim()) lines.push('现场备注：' + note);
    note = lines.join('\n');
  }
  if (note.length > noteLimit) throw Error('开工现场记录连同栏目名最多 1000 字符，当前 ' + note.length + ' 字符');
  return { date: form.date, file: form.file, note };
}

function proofIds(stored) {
  // 历史 JSONB 可能含未加引号的 19 位整数，在 JSON.parse 前保留原始数字串。
  let ids = stored;
  if (typeof ids === 'string') {
    try { ids = JSON.parse(ids.replace(/([\[,])\s*(\d+)\s*(?=[,\]])/g, '$1"$2"')); }
    catch (_) { return []; }
  }
  return Array.isArray(ids) ? ids.filter(id => (typeof id === 'string' || Number.isSafeInteger(id)) && /^[1-9]\d*$/.test(id)).map(String) : [];
}

function apply(data, detail) {
  if (data.actions.some(action => action.action === 'start')) {
    const textareas = sections.map(field => ({ ...field, type: 'textarea', maxLength: noteLimit }));
    data.fields = data.fields.flatMap(field => field.key === 'note'
      ? [...textareas, { ...field, label: '其他现场备注', maxLength: noteLimit }] : [field]);
  }
  const actual = data.p.actualStart;
  if (!actual) return;
  data.startBlocked = '';
  // 开工日也可能有普通巡场或其他工序，单据必须对应唯一的 START 完成记录。
  const matches = data.node?.milestone === 'start'
    ? data.siteRecords.filter(record => record.completion && record.date === actual.date) : [];
  const row = matches.length === 1 && (detail.siteRecords || []).find(record => String(record.id) === matches[0].id);
  actual.note = row?.note || '';
  const actor = detail.timelineActors?.['eng-start'] || String(row?.author_user_id || '');
  actual.actor = actor.replace(/^经办人 (\d+)$/, '$1');
  actual.assetIds = proofIds(row?.file_asset_ids);
}

// A session can open more than one record page for the same project.
const byClient = new WeakMap();
const blankState = { startSaveState: '', startCanRetry: false };

function scope(page, http) {
  const identity = String(http.currentPostId()) + ':' + http.sessionGeneration();
  return page.data.view === 'start' && page.id && page.__identity === identity ? identity + ':' + page.id : '';
}

function store(http) {
  if (!byClient.has(http)) byClient.set(http, new Map());
  const entries = byClient.get(http);
  for (const [key, ticket] of entries) if (!ticket.session()) entries.delete(key);
  return entries;
}

function enter(page, http) {
  const key = scope(page, http);
  if (!key || page.__startContext === key) return;
  if (page.__startContext) {
    const earlier = store(http).get(page.__startContext);
    if (earlier?.page === page) { earlier.page = null; earlier.permitted = false; }
    page.initialized = false;
    page.__fileSelectionGeneration = (page.__fileSelectionGeneration || 0) + 1;
    page.setData({ ...blankState, p: null, form: {}, fields: [], actions: [], fileNames: {}, busy: false, error: '' });
  }
  page.__startContext = key;
}

function visible(page, ticket, http) {
  return page && !page.__inactive && !page.__disposed && scope(page, http) === ticket.scope && ticket.session();
}

function hide(page, http) {
  const ticket = store(http).get(scope(page, http));
  if (ticket?.page === page) {
    ticket.permitted = false;
    ticket.page = null;
  }
  if (!page.__disposed) page.setData({ p: null, actions: [], fields: [], startCanRetry: false,
    ...(ticket ? { form: {}, fileNames: {} } : {}) });
}

function lockView(data, ticket) {
  data.startSaveState = ticket.state;
  data.startCanRetry = ticket.state === 'unknown' && ticket.permitted;
  data.busy = ticket.state === 'sending';
  data.fields = [];
  data.actions = [];
}

function reconcile(page, data, detail, http) {
  if (String(detail.id) !== String(page.id)) throw Error('开工原单与当前工程不匹配，请重新进入工程');
  apply(data, detail);
  Object.assign(data, blankState);
  const entries = store(http), key = scope(page, http), ticket = entries.get(key);
  if (!data.own || data.p.actualStart) {
    data.form.note = '';
    data.form.file = null;
    for (const field of sections) delete data.form[field.key];
    data.fileNames = {};
  }
  if (!ticket) return;
  if (!data.own || data.p.actualStart) {
    const actual = data.p.actualStart, submitted = ticket.body;
    if (actual && submitted && (actual.date !== submitted.date || !actual.assetIds.includes(String(submitted.fileAssetId))
        || actual.note !== (submitted.note.trim() ? submitted.note : '正式开工'))) {
      data.error = '工程已有另一份开工记录，请核对实际开工原单';
    }
    entries.delete(key);
    return;
  }
  ticket.page = page;
  ticket.permitted = data.actions.some(action => action.action === 'start');
  if (ticket.state === 'failed') {
    entries.delete(key);
    if (ticket.permitted) {
      Object.assign(data.form, ticket.draft);
      data.fileNames = { ...data.fileNames, file: ticket.draft.file?.name || '' };
    }
    data.error = ticket.error;
    return;
  }
  lockView(data, ticket);
}

function readFailed(page, http, error) {
  const entries = store(http), key = scope(page, http), ticket = entries.get(key);
  if (!ticket) return;
  ticket.permitted = false;
  if ([401, 403, 404, 1099000002, 1115000000].includes(Number(error?.code ?? error?.data?.code))) entries.delete(key);
  page.setData({ ...blankState, busy: false });
}

function begin(page, http, api, form) {
  const key = scope(page, http), entries = store(http);
  if (!key || page.__disposed || page.__inactive || entries.has(key) ||
      !page.data.own || !page.data.actions.some(action => action.action === 'start')) return;
  let input;
  try { input = command(form); } catch (error) { return page.fail(error.message); }
  if (!input.file?.path) return page.fail('请选择开工凭据文件');
  const draft = { date: form.date, note: form.note, file: { ...form.file } };
  for (const field of sections) draft[field.key] = form[field.key] || '';
  const ticket = { scope: key, projectId: String(page.id), session: http.sessionGuard(), page,
    key: 'eng-start-' + Date.now() + '-' + Math.floor(Math.random() * 1e8), draft,
    date: input.date, note: input.note, body: null, permitted: true, state: 'sending' };
  entries.set(key, ticket);
  page.__paymentRead = (page.__paymentRead || 0) + 1;
  page.__fileSelectionGeneration = (page.__fileSelectionGeneration || 0) + 1;
  return send(ticket, entries, http, api);
}

function retry(page, http, api) {
  const entries = store(http), ticket = entries.get(scope(page, http));
  if (!ticket || ticket.state !== 'unknown' || ticket.page !== page || !ticket.permitted || !visible(page, ticket, http)) return;
  return send(ticket, entries, http, api);
}

async function send(ticket, entries, http, api) {
  ticket.state = 'sending';
  const initialPage = ticket.page;
  initialPage.setData({ busy: true, error: '', startSaveState: 'sending', startCanRetry: false, fields: [], actions: [] });
  const current = () => entries.get(ticket.scope) === ticket && ticket.session();
  try {
    if (!ticket.body) {
      const id = await api.uploadVoucher(ticket.draft.file.path, 'ENG_DRAWING');
      if (!current()) return;
      ticket.body = Object.freeze({ date: ticket.date || null, note: ticket.note, fileAssetId: id });
      // If the upload ended after leaving the page, recheck access before sending the business command.
      if (!ticket.permitted || !visible(ticket.page, ticket, http)) { ticket.state = 'unknown'; return; }
    }
    await api.plan.start(ticket.projectId, ticket.body, ticket.key);
    if (!current()) return;
    ticket.state = 'saved';
  } catch (error) {
    if (!current()) return;
    const code = Number(error?.code ?? error?.data?.code);
    const uncertain = ticket.body && (!Number.isFinite(code) || code <= 0 || (code >= 500 && code < 600) || code === 1099000000);
    ticket.state = uncertain ? 'unknown' : 'failed';
    ticket.error = error?.msg || error?.message || '开工记录未保存，请核对';
  } finally {
    const page = ticket.page;
    if (current() && visible(page, ticket, http)) {
      if (ticket.state === 'unknown') {
        page.setData({ busy: false, error: '开工回执待核对，请使用原记录重试', startSaveState: 'unknown', startCanRetry: ticket.permitted });
      } else {
        await page.refreshOnline(ticket.state === 'saved');
      }
    }
  }
}

module.exports = { enter, reconcile, readFailed, begin, retry, hide };
