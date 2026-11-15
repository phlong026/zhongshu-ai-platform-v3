const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..');
const CRM = require(path.join(ROOT, 'miniprogram/utils/platform-crm.js'));
const flush = () => new Promise(resolve => setImmediate(resolve));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

function loadPage(platformCrm = CRM) {
  let definition;
  const source = fs.readFileSync(path.join(ROOT, 'miniprogram/pages/workflow/index.js'), 'utf8');
  vm.runInNewContext(source, {
    Page(value) { definition = value; },
    require(id) {
      if (id === '../../utils/organization') return { roles: { sales: '业务员' } };
      if (id === '../../utils/view-container') return { routeValue: value => value || '', fail() {} };
      if (id === '../../utils/platform-crm') return platformCrm;
      if (id === '../../api/http') return { token: () => 'token' };
      if (id === './views') return { titles: {} };
      throw new Error('unexpected require: ' + id);
    },
    wx: { showToast() {} },
    console,
    Promise,
    Date,
    setTimeout,
    clearTimeout
  }, { filename: 'miniprogram/pages/workflow/index.js' });
  return definition;
}

function harness(options = {}) {
  const platformCrm = options.platformCrm || CRM;
  const definition = loadPage(platformCrm);
  let sessionGeneration = 1;
  const detailCalls = [];
  const requirementListCalls = [];
  const requirementCreates = [];
  const quoteCreates = [];
  const contractCreates = [];
  const detailResponse = options.detailResponse || Promise.resolve({
    customer: {
      id: 'customer-9', name: '王先生', phone: '13800138000', city: '武汉市',
      summary: '两层自住', status: 'FOLLOWING', ownerUserId: '145', revision: 3
    },
    follows: [],
    schemes: [{ id: 'scheme-1', kind: 'PLAN', version: 1, width: 12, depth: 15, floors: 2 }]
  });
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.');
        let target = this.data;
        while (parts.length > 1) {
          const part = parts.shift();
          target = target[part] || (target[part] = {});
        }
        target[parts[0]] = value;
      }
    }
  };
  const http = {
    token: () => 'token',
    sessionGeneration: () => sessionGeneration,
    clearSession() { sessionGeneration += 1; }
  };
  const sales = {
    requirements: {
      list() {
        requirementListCalls.push('list');
        if (typeof options.requirementsResponse === 'function') return options.requirementsResponse();
        return options.requirementsResponse || Promise.resolve(options.requirements || []);
      },
      create(body) { requirementCreates.push(body); return Promise.resolve({ version: 1 }); }
    },
    quotes: {
      list: () => Promise.resolve([]),
      create(body) { quoteCreates.push(body); return Promise.resolve({}); }
    },
    contracts: {
      list: () => Promise.resolve([]),
      create(body) { contractCreates.push(body); return Promise.resolve({}); }
    }
  };
  Object.assign(page, {
    id: 'customer-9',
    __post: { postId: 'post-1', roleCode: 'sales' },
    __postKey: 'post-1',
    __http: http,
    __pf: platformCrm,
    __crmSales: sales,
    __crmApi: {
      leads: { capture: () => Promise.resolve(options.leadCapture || { result: 'CREATED', customerId: 'customer-9' }) },
      customers: {
        get(id) {
          detailCalls.push(id);
          if (typeof options.customerGet === 'function') return options.customerGet(id);
          return typeof detailResponse === 'function' ? detailResponse() : detailResponse;
        }
      }
    }
  });
  return {
    page, detailCalls, requirementListCalls, requirementCreates, quoteCreates, contractCreates,
    currentSessionGeneration: () => sessionGeneration,
    switchPost() { sessionGeneration += 1; page.__postKey = 'post-2'; page.__post = { postId: 'post-2', roleCode: 'sales' }; }
  };
}

for (const view of ['requirement', 'budget', 'contract']) {
  test(view + ' loads the real CRM customer context before rendering the trade page', async () => {
    const h = harness({ requirements: view === 'budget' ? [{ width: 12, depth: 15, floors: 2 }] : [] });
    if (view === 'requirement') h.page.prepareRequirement();
    if (view === 'budget') h.page.loadPlatformBudget();
    if (view === 'contract') h.page.loadPlatformContract();
    await flush(); await flush();
    assert.deepEqual(h.detailCalls, ['customer-9']);
    assert.equal(h.page.data.customer.id, 'customer-9');
    assert.equal(h.page.data.customer.name, '王先生');
    assert.equal(h.page.data.customerInitial, '王');
    assert.equal(h.page.data.platformStatus, 'ready');
  });
}

test('customer detail initializes saleWrite and refreshes it after a role switch', async () => {
  const h = harness();
  h.page.loadPlatformDetail('customer');
  await flush(); await flush();
  assert.equal(h.page.data.saleWrite, true);

  h.page.__post = { postId: 'post-owner', roleCode: 'owner' };
  h.page.__postKey = 'post-owner';
  h.page.loadPlatformDetail('customer');
  await flush(); await flush();
  assert.equal(h.page.data.saleWrite, false);
});

test('customer context failure is visible and never injects a placeholder customer', async () => {
  const h = harness({ detailResponse: () => Promise.reject({ code: 1110000001, message: '客户不存在或无权访问' }) });
  h.page.prepareRequirement();
  await flush(); await flush();
  assert.equal(h.page.data.customer, null);
  assert.equal(h.page.data.platformStatus, 'degraded');
  assert.match(h.page.data.error, /客户/);
});

test('customer detail arriving after a post switch cannot refill the page', async () => {
  const pending = deferred();
  const h = harness({ detailResponse: pending.promise });
  h.page.loadPlatformBudget();
  h.switchPost();
  pending.resolve({ customer: { id: 'customer-9', name: '旧岗位客户', status: 'REGISTERED' }, follows: [], schemes: [] });
  await flush(); await flush();
  assert.equal(h.page.data.customer, null);
});

test('late customer success preserves an earlier requirement loading error', async () => {
  const pending = deferred();
  const h = harness({
    detailResponse: pending.promise,
    requirementsResponse: () => Promise.reject({ message: '需求接口不可用' })
  });
  h.page.prepareRequirement();
  await flush(); await flush();
  assert.match(h.page.data.error, /需求加载失败/);

  pending.resolve({ customer: { id: 'customer-9', name: '王先生', status: 'FOLLOWING' }, follows: [], schemes: [] });
  await flush(); await flush();
  assert.equal(h.page.data.customer.name, '王先生');
  assert.match(h.page.data.error, /需求加载失败/);
});

for (const view of ['requirement', 'budget']) {
  test(view + ' does not overwrite user edits when the latest requirement arrives late', async () => {
    const pending = deferred();
    const h = harness({ requirementsResponse: pending.promise });
    if (view === 'requirement') h.page.prepareRequirement();
    else h.page.loadPlatformBudget();
    await flush();
    h.page.input({ currentTarget: { dataset: { key: 'width' } }, detail: { value: '18' } });
    pending.resolve([{ width: 12, depth: 15, floors: 2, style: '新中式' }]);
    await flush(); await flush();
    assert.equal(h.page.data.form.width, '18');
  });
}

test('requirement loading and failure block writes until an explicit retry succeeds', async () => {
  const first = deferred();
  let calls = 0;
  const h = harness({ requirementsResponse: () => ++calls === 1 ? first.promise : Promise.resolve([]) });
  h.page.prepareRequirement();
  await flush();
  assert.equal(h.page.data.requirementsStatus, 'loading');
  h.page.data.form = { width: '12', depth: '15', floors: '2', style: '现代简约', prompt: '', note: '' };
  h.page.platformSubmitRequirement();
  h.page.data.form = { width: '12', depth: '15', floors: '2', footprint: '180', area: '360', roofArea: '198', budgetChecked: true };
  h.page.platformSubmitQuote();
  h.page.data.contractForm = { total: '100', signAfter: '100', baseDone: '0', capDone: '0', signedDate: '' };
  h.page.platformSubmitContract();
  assert.equal(h.requirementCreates.length, 0);
  assert.equal(h.quoteCreates.length, 0);
  assert.equal(h.contractCreates.length, 0);
  assert.match(h.page.data.error, /加载/);

  first.reject({ message: '需求接口不可用' });
  await flush(); await flush();
  assert.equal(h.page.data.requirementsStatus, 'error');
  h.page.platformSubmitRequirement();
  h.page.platformSubmitQuote();
  h.page.platformSubmitContract();
  assert.equal(h.requirementCreates.length, 0);
  assert.equal(h.quoteCreates.length, 0);
  assert.equal(h.contractCreates.length, 0);
  assert.match(h.page.data.error, /重试/);

  h.page.retryRequirements();
  await flush(); await flush();
  assert.equal(h.page.data.requirementsStatus, 'ready');
  assert.equal(calls, 2);
});

test('owner deep links neither load trade APIs nor submit requirement, quote, or contract writes', async () => {
  const h = harness();
  h.page.__post = { postId: 'post-owner', roleCode: 'owner' };
  h.page.__postKey = 'post-owner';
  h.page.prepareRequirement();
  await flush(); await flush();
  assert.equal(h.page.data.saleWrite, false);
  assert.equal(h.page.data.requirementsStatus, 'blocked');
  assert.equal(h.requirementListCalls.length, 0);

  h.page.data.form = { width: '12', depth: '15', floors: '2', footprint: '180', area: '360', roofArea: '198', style: '现代简约', prompt: '', note: '', budgetChecked: true };
  h.page.platformSubmitRequirement();
  h.page.platformSubmitQuote();
  h.page.data.contractForm = { total: '100', signAfter: '100', baseDone: '0', capDone: '0', signedDate: '' };
  h.page.platformSubmitContract();
  assert.equal(h.requirementCreates.length, 0);
  assert.equal(h.quoteCreates.length, 0);
  assert.equal(h.contractCreates.length, 0);
  assert.match(h.page.data.error + h.page.data.contractError, /权限/);
});

test('a LINKED customer probe arriving after a post switch cannot navigate', async () => {
  const pending = deferred();
  const h = harness({
    leadCapture: { result: 'LINKED', customerId: 'linked-8' },
    customerGet: id => id === 'linked-8' ? pending.promise : Promise.resolve({ customer: { id }, follows: [], schemes: [] })
  });
  const navigations = [];
  h.page.openView = (...args) => navigations.push(args);
  h.page.data.form = { name: '王先生', phone: '13800138000', source: '到店咨询', summary: '两层自住' };
  h.page.platformSubmitRegister();
  await flush();
  h.switchPost();
  pending.resolve({ customer: { id: 'linked-8' }, follows: [], schemes: [] });
  await flush(); await flush();
  assert.equal(navigations.length, 0);
});

test('a current-session 401 from the LINKED customer probe clears the session and never navigates', async () => {
  const h = harness({
    leadCapture: { result: 'LINKED', customerId: 'linked-8' },
    customerGet: () => Promise.reject({ code: 401 })
  });
  const navigations = [];
  h.page.openView = (...args) => navigations.push(args);
  h.page.data.form = { name: '王先生', phone: '13800138000', source: '到店咨询', summary: '两层自住' };
  h.page.platformSubmitRegister();
  await flush(); await flush();
  assert.equal(h.currentSessionGeneration(), 2);
  assert.equal(h.page.data.platformStatus, 'degraded');
  assert.equal(navigations.length, 0);
});

test('trade views expose loading, retry, and saleWrite button gates', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'miniprogram/pages/workflow/index.wxml'), 'utf8');
  assert.match(wxml, /requirementsStatus==='loading'/);
  assert.match(wxml, /bindtap="retryRequirements"/);
  assert.match(wxml, /wx:if="\{\{saleWrite\}\}"[^>]*bindtap="submit">登记需求/);
  assert.match(wxml, /wx:if="\{\{saleWrite\}\}"[^>]*bindtap="submit">开始测算/);
  assert.match(wxml, /saleWrite&&requirementsStatus==='ready'&&contractForm/);
});

test('budget never derives roof area from the requirement width', async () => {
  const h = harness({ requirements: [{ width: 12, depth: 15, floors: 2 }] });
  h.page.loadPlatformBudget();
  await flush(); await flush();
  assert.equal(h.page.data.form.roofArea, '');
  assert.equal(h.page.data.form.budgetChecked, false);

  const entered = harness({ requirements: [{ width: 12, depth: 15, floors: 2 }] });
  entered.page.data.form = { roofArea: '220', budgetChecked: true };
  entered.page.loadPlatformBudget();
  await flush(); await flush();
  assert.equal(entered.page.data.form.roofArea, '220');
  assert.equal(entered.page.data.form.budgetChecked, true);
});

test('quote requires footprint and building area before compilation or API write', () => {
  let compiled = 0;
  const platformCrm = Object.assign({}, CRM, { compileQuoteItems() { compiled += 1; return { items: {} }; } });
  const h = harness({ platformCrm });
  h.page.data.requirementsStatus = 'ready';
  h.page.data.form = {
    width: '12', depth: '15', floors: '2', footprint: '', area: '', roofArea: '198',
    foundation: '独立基础', structure: '框架结构', roof: '坡屋顶', budgetChecked: true
  };
  h.page.platformSubmitQuote();
  assert.match(h.page.data.error, /占地面积/);
  assert.equal(compiled, 0);
  assert.equal(h.quoteCreates.length, 0);

  h.page.data.form.footprint = '180';
  h.page.platformSubmitQuote();
  assert.match(h.page.data.error, /建筑面积/);
  assert.equal(compiled, 0);
  assert.equal(h.quoteCreates.length, 0);
});
