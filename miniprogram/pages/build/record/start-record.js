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
  for (const field of sections) delete data.form[field.key];
  // 开工日也可能有普通巡场或其他工序，单据必须对应唯一的 START 完成记录。
  const matches = data.node?.milestone === 'start'
    ? data.siteRecords.filter(record => record.completion && record.date === actual.date) : [];
  const row = matches.length === 1 && (detail.siteRecords || []).find(record => String(record.id) === matches[0].id);
  actual.note = row?.note || '';
  const actor = detail.timelineActors?.['eng-start'] || String(row?.author_user_id || '');
  actual.actor = actor.replace(/^经办人 (\d+)$/, '$1');
  actual.assetIds = proofIds(row?.file_asset_ids);
}

module.exports = { apply, command };
