const base = '/franchise/finance/workbench';
const { id } = require('../accounting/model');

function projectChoices(page, form) {
  const rows = (page.data.workbenchBooks || []).filter(row => String(row.scope).startsWith('project:') &&
    id(row.customer_id) === id(form.customerId) && id(form.customerId) &&
    (!form.commissionId || id(row.commission_id) === id(form.commissionId)) &&
    (!row.book_id || id(row.book_id) === id(form.bookId)));
  const selected = rows.find(row => row.scope === form.scope) || (rows.length === 1 ? rows[0] : null);
  return { projectChoices: rows, scope: selected?.scope || '', scopeLabel: selected?.name || '' };
}

function open(page, http, api, link) {
  if (page.data.wbBusy || page.data.registerBusy || page.__disposed) return;
  const book = link ? page.data.bookResult?.book || (page.data.workbenchBooks || []).find(row => row.scope === page.data.bookScope) : null;
  if (link && (!book || page.data.bookLoading || page.data.bookError || book.project_id)) return;
  page.wbGeneration = (page.wbGeneration || 0) + 1;
  page.__bookSourceAlive = http.sessionGuard();
  page.wbKey = api.idemKey(link ? 'book-link' : 'book');
  const form = { type: link ? 'linkbook' : 'book', name: link ? '关联已有工程' : '',
    bookId: link ? id(book.id || book.book_id || String(page.data.bookScope).slice(5)) : '',
    customerId: id(book?.customer_id), commissionId: id(book?.commission_id),
    sourceCustomerLocked: !!book?.customer_id, sourceCommissionLocked: !!book?.commission_id,
    sourceKeyword: '', sourceOptions: [], sourceLoading: true };
  page.setData({ wbForm: { ...form, ...projectChoices(page, form) }, wbError: '' });
  return load(page, http);
}

async function load(page, http) {
  const form = page.data.wbForm;
  if (!form || !['book', 'linkbook'].includes(form.type) || page.data.wbBusy) return;
  const generation = page.wbGeneration, epoch = page.__financeReadEpoch, session = http.sessionGuard(), query = page.__bookSourceQuery = {};
  page.__bookSourceEpoch = epoch;
  const alive = () => session() && !page.__disposed && !page.__financeHidden && epoch === page.__financeReadEpoch &&
    page.wbGeneration === generation && page.__bookSourceQuery === query && page.data.wbForm?.type === form.type;
  page.setData({ 'wbForm.sourceLoading': true, 'wbForm.sourceFailed': false, wbError: '' });
  try {
    const result = await http.get(base + '/book-sources', form.sourceCustomerLocked
      ? { customerId: form.customerId } : { keyword: String(form.sourceKeyword || '').trim() });
    if (!alive()) return;
    const options = (result.items || []).map(row => ({ ...row, id: id(row.id), commission_id: id(row.commission_id), label: row.name + (row.phone ? ' · ' + row.phone : '') }));
    const current = page.data.wbForm, selected = options.find(row => row.id === current.customerId);
    if (current.sourceCustomerLocked && !selected) throw Error('原来源客户暂不可用，请核对客户档案后重试');
    const next = { ...current, sourceOptions: options, sourceHasMore: !!result.hasMore, sourceLoading: false,
      ...(selected ? { customerLabel: selected.label, availableCommissionId: selected.commission_id } : {}) };
    page.setData({ wbForm: { ...next, ...projectChoices(page, next) } });
  } catch (error) {
    if (alive()) page.setData({ 'wbForm.sourceLoading': false, 'wbForm.sourceFailed': true, wbError: error.message || error.msg || '来源客户加载失败，请重试' });
  }
}

function pick(page, event) {
  const form = page.data.wbForm;
  if (!form || page.data.wbBusy || form.sourceLoading) return;
  const commission = event.currentTarget.dataset.field === 'commissionId';
  if (commission && form.sourceCommissionLocked || !commission && form.sourceCustomerLocked) return;
  let next;
  if (commission) {
    next = { ...form, commissionId: Number(event.detail.value) === 1 ? id(form.availableCommissionId) : '', scope: '', scopeLabel: '' };
  } else {
    const customer = (form.sourceOptions || [])[Number(event.detail.value)];
    if (!customer) return;
    next = { ...form, customerId: customer.id, customerLabel: customer.label, availableCommissionId: customer.commission_id,
      commissionId: '', scope: '', scopeLabel: '' };
  }
  page.setData({ wbForm: { ...next, ...projectChoices(page, next) }, wbError: '' });
}

module.exports = { open, load, pick, projectChoices };
