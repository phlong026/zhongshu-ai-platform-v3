const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const Source = require('../miniprogram/pages/admin/finance-desk/book-source');
const customer = '9007199254740993', commission = '9007199254740995', book = '9007199254740997';
const first = '9007199254741001', second = '9007199254741003';
const event = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value: String(value) } });
const project = (value, extra = {}) => ({ scope: 'project:' + value, project_id: value, customer_id: customer, name: '工程 ' + value, ...extra });

function harness() {
  const state = { session: 1, sources: [{ id: customer, name: '原客户', phone: '13800000001', commission_id: commission }], posts: [], gets: [] };
  const http = {
    sessionGuard() { const session = state.session; return () => session === state.session; },
    requestGuard() { return this.sessionGuard(); },
    async get(url, query) { state.gets.push({ url, query }); if (state.read) return state.read(url, query); return { items: state.sources, hasMore: false }; },
    async post(url, body, headers) { state.posts.push(JSON.parse(JSON.stringify({ url, body, headers }))); return { id: book }; }
  };
  const page = { data: { workbenchBooks: [], wbForm: null }, options: {}, __financeReadEpoch: 1,
    setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let target = this.data; for (const part of parts.slice(0, -1)) target = target[part] ||= {}; target[parts.at(-1)] = value; } },
    loadPlatformFinance() {} };
  const module = { exports: {} }, api = { idemKey: prefix => prefix + '-fixed-key', receipts: async () => [] };
  const modules = {
    '../../../api/http': http, '../../../api/auth': { currentApiPost: () => ({ roleCode: 'finance' }) },
    '../../../utils/platform-finance': require('../miniprogram/utils/platform-finance'),
    '../../../utils/finance-workbench-view': require('../miniprogram/utils/finance-workbench-view'),
    '../../../utils/finance-date': require('../miniprogram/utils/finance-date'),
    '../../../utils/account': { localDate: () => '2026-11-28' },
    '../accounting/model': require('../miniprogram/pages/admin/accounting/model'), './book-source': Source,
    './allocation-candidates': require('../miniprogram/pages/admin/finance-desk/allocation-candidates')
  };
  vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/workbench.js', 'utf8'), {
    module, require: name => { if (!(name in modules)) throw Error('Unexpected dependency ' + name); return modules[name]; },
    wx: { showToast() {}, pageScrollTo() {} }, console
  });
  module.exports(page, api); const refreshWorkbench=page.loadWorkbench.bind(page);page.loadWorkbench = () => {}; page.loadFinanceProjects = () => {};
  return { page, state, http, refreshWorkbench };
}

test('new preliminary book requires its selected customer and submits the exact optional commission identity', async () => {
  const { page, state } = harness();
  await page.createFinanceBook(); page.wbInput(event('name', '设计前测量费'));
  await page.submitWorkbench(); assert.equal(state.posts.length, 0); assert.match(page.data.wbError, /来源客户/);
  page.pickBookSource(event('customerId', 0)); assert.equal(page.data.wbForm.customerId, customer);
  page.pickBookSource(event('commissionId', 1)); await page.submitWorkbench();
  assert.deepEqual(state.posts, [{ url: '/franchise/finance/workbench/books',
    body: { name: '设计前测量费', customerId: customer, commissionId: commission }, headers: { 'Idempotency-Key': 'book-fixed-key' } }]);
  assert.equal(page.data.wbForm, null);
});

test('known customer and commission stay locked and only their available original project is selectable', async () => {
  const { page, state } = harness(); page.data.bookScope = 'book:' + book;
  page.data.bookResult = { book: { id: book, name: '原前期账', customer_id: customer, commission_id: commission } };
  page.data.workbenchBooks = [project(first, { commission_id: commission }), project(second),
    project('9007199254741005', { customer_id: '77', commission_id: commission }),
    project('9007199254741007', { commission_id: commission, book_id: '88' }), { scope: 'company', name: '日常账' }];
  await page.linkFinanceBook();
  assert.deepEqual(state.gets[0].query, { customerId: customer });
  assert.deepEqual(page.data.wbForm.projectChoices.map(row => row.scope), ['project:' + first]);
  page.pickBookSource(event('commissionId', 0)); page.pickBookSource(event('customerId', 1));
  assert.equal(page.data.wbForm.commissionId, commission); assert.equal(page.data.wbForm.customerId, customer);
  await page.submitWorkbench();
  assert.equal(state.posts[0].url, '/franchise/finance/workbench/books/' + book + '/project/' + first);
  assert.deepEqual(state.posts[0].body, { customerId: customer, commissionId: commission });
});

test('legacy book fills its source in the existing link action and multiple customer projects require explicit selection', async () => {
  const { page, state } = harness(); page.data.bookScope = 'book:' + book;
  page.data.bookResult = { book: { id: book, name: '旧账' } };
  page.data.workbenchBooks = [project(first), project(second), project('99', { customer_id: '88' })];
  await page.linkFinanceBook(); page.pickBookSource(event('customerId', 0));
  assert.equal(page.data.wbForm.scope, ''); assert.equal(page.data.wbForm.projectChoices.length, 2);
  await page.submitWorkbench(); assert.equal(state.posts.length, 0); assert.match(page.data.wbError, /选择已有工程/);
  page.wbPick(event('scope', 1)); await page.submitWorkbench();
  assert.equal(state.posts[0].url, '/franchise/finance/workbench/books/' + book + '/project/' + second);
  assert.deepEqual(state.posts[0].body, { customerId: customer });
});

test('a late customer lookup cannot replace a newer search, reopened form, or hidden-page epoch', async () => {
  for (const transition of ['newer-search', 'reopened', 'hidden']) {
    const { page, state } = harness(), pending = [];
    state.read = () => new Promise(resolve => pending.push(resolve));
    const old = page.createFinanceBook();
    if (transition === 'hidden') { page.__financeReadEpoch++; page.__financeHidden = true; page.__financeHidden = false; }
    else {
      const current = transition === 'reopened' ? page.createFinanceBook() : page.searchBookSources();
      pending[1]({ items: [{ id: '88', name: '当前查询' }] }); await current;
    }
    const before = JSON.stringify(page.data.wbForm);
    pending[0]({ items: state.sources }); await old;
    assert.equal(JSON.stringify(page.data.wbForm), before, transition);
  }
});

test('customer lookup failure is retryable and changed work identity cannot submit its previous source', async () => {
  const { page, state } = harness(); state.read = () => { throw Error('网络中断'); };
  await page.createFinanceBook(); assert.equal(page.data.wbForm.sourceFailed, true);
  page.wbInput(event('name', '前期事项')); await page.submitWorkbench(); assert.equal(state.posts.length, 0);
  state.read = null; await page.searchBookSources(); page.pickBookSource(event('customerId', 0));
  state.session++; await page.submitWorkbench(); assert.equal(state.posts.length, 0); assert.match(page.data.wbError, /工作身份/);
});

test('returning from a hidden page restarts its pending source query and the same form can finish', async () => {
  for(const locked of [false,true])for(const settledWhileHidden of [false,true]){
    const { page, state, refreshWorkbench }=harness(),pending=[];let resolveBooks;
    page.data.bookScope='book:'+book;page.data.bookResult={book:{id:book,customer_id:customer}};
    state.read=url=>url.endsWith('/book-sources')?new Promise(resolve=>pending.push(resolve)):
      url.endsWith('/books')?new Promise(resolve=>resolveBooks=resolve):[];
    const original=locked?page.linkFinanceBook():page.createFinanceBook();
    page.__financeHidden=true;page.__financeReadEpoch++;
    if(settledWhileHidden){pending[0]({items:[{id:'77',name:'旧响应客户'}]});await original;}
    page.__financeHidden=false;
    // This is the existing onShow refresh, using the actual workbench implementation.
    const refresh=refreshWorkbench();assert.equal(pending.length,2);
    pending[1]({items:state.sources});await new Promise(resolve=>setImmediate(resolve));
    assert.equal(page.data.wbForm.sourceLoading,false);assert.equal(page.data.wbForm.sourceFailed,false);
    // A faster source response must still use the project list that finishes afterwards.
    resolveBooks([project(first)]);await refresh;
    if(!settledWhileHidden){pending[0]({items:[{id:'77',name:'旧响应客户'}]});await original;}
    assert.equal(page.data.wbForm.sourceOptions[0].id,customer);
    if(locked)assert.equal(page.data.wbForm.scope,'project:'+first);
    if(!locked){page.pickBookSource(event('customerId',0));page.wbInput(event('name','恢复后的前期账'));}
    await page.submitWorkbench();assert.equal(state.posts.length,1);assert.equal(page.data.wbForm,null);
  }
});

test('linked old book URL resolves its saved project and no longer offers a second link', async () => {
  const { page, state } = harness(); page.options.bookScope = 'book:' + book;
  state.read = url => url.endsWith('/books') ? [project(first)] : {
    book: { id: book, name: '原前期账', customer_id: customer, project_id: first }, entries: [],
    summary: { received_cents: '0', paid_cents: '0', committed_cents: '0' }, reimbursementIds: [], reimbursementBalanceComplete: true
  };
  await page.loadBookLedger();
  assert.equal(page.data.bookName, '原前期账'); assert.equal(page.data.canLinkBook, false);
  assert.match(page.data.bookExpensesUrl, new RegExp('id=' + first));
  await page.linkFinanceBook(); assert.equal(page.data.wbForm, null);
});
