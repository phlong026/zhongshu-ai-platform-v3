const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const filename = path.resolve(__dirname, '../miniprogram/pages/workflow/commission/index.js');
const actualRequire = createRequire(filename);
function harness() {
  let definition, generation = 1, context;
  const calls = [], post = { postId: 's1', userId: '145', orgId: '119', roleCode: 'sales', orgType: 'store' };
  const http = { token: () => 'token', sessionGeneration: () => generation,
    sessionGuard: () => { const g = generation; return () => g === generation; }, requestGuard: () => http.sessionGuard() };
  const api = { commissions: {
    context: async id => { calls.push(['context', id]); return context; },
    get: async id => { calls.push(['get', id]); return context.commission; },
    submit: async (id, body) => { calls.push(['submit', id, body]); return { commissionId: '9007199254741999' }; },
    command: async (id, action, body) => { calls.push(['command', id, action, body]); return {}; }
  } };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Page: p => { definition = p; },
    require: n => n.endsWith('/api/http') ? http : n.endsWith('/api/auth') ? { currentApiPost: () => post }
      : n.endsWith('/api/franchise/commission') ? api : n.endsWith('/utils/organization') ? { apiContext: () => ({ role: 'sales', orgType: 'store' }) }
        : n.endsWith('/utils/view-container') ? { makeFormHandlers: () => ({}) } : actualRequire(n),
    wx: { showToast() {}, pageScrollTo() {} }, console });
  const page = { ...definition, data: structuredClone(definition.data), setData(patch) { for (const [key, value] of Object.entries(patch)) { const keys = key.split('.'); let target = this.data; for (const segment of keys.slice(0, -1)) target = target[segment] ||= {}; target[keys.at(-1)] = value; } } };
  return { page, api, calls, switchPost() { generation++; }, setContext(value) { context = value; } };
}
const context = (budgetVersion = 1, ruleVersion = 2) => ({ customerId: '9007199254741001', latestBudget: { version: budgetVersion, width: 12, depth: 15, floors: 2, area: 360 },
  feeOffer: { mode: 'AREA_FULL', ruleVersion, area: 360, rateCents: 1000, totalCents: 360000, firstCents: 0, tailCents: 0, scope: '施工图', recipient: '总部' }, allowedActions: ['commission-submit'] });
test('new customer uses context, version update keeps draft and forces reconfirmation', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' });
  await h.page.refresh(); assert.equal(h.calls[0][0], 'context'); assert.equal(h.page.id, '');
  h.page.data.form.text = '保留用户已填需求'; h.page.data.form.checked = true;
  h.setContext(context(2, 3)); await h.page.refresh(false);
  assert.equal(h.page.data.form.text, '保留用户已填需求'); assert.equal(h.page.data.form.checked, false);
  assert.equal(h.page.data.form.__budgetVersion, 2); assert.equal(h.page.data.form.__pricingVersion, 3);
});
test('submit uses customerId and subsequent commands use returned opaque commissionId', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' }); await h.page.refresh();
  h.page.data.form.text = 'TEST ONLY'; h.page.data.form.checked = true; h.page.refresh = () => {};
  await h.page.submit(); assert.equal(h.page.id, '9007199254741999'); assert.equal(h.calls.find(c => c[0] === 'submit')[1], '9007199254741001');
  h.page.action = 'design-complete'; h.page.data.form.checked = true; await h.page.submit();
  assert.equal(h.calls.find(c => c[0] === 'command')[1], '9007199254741999');
});
test('old session command response never changes new session state', async () => {
  const h = harness(); h.setContext(context()); h.page.onLoad({ view: 'submit', customerId: '9007199254741001' }); await h.page.refresh();
  let finish; h.api.commissions.submit = () => new Promise(resolve => { finish = resolve; });
  h.page.data.form.text = 'TEST ONLY'; h.page.data.form.checked = true;
  const pending = h.page.submit(); h.switchPost(); h.page.data.form.text = '新身份内容'; finish({ commissionId: 'old-id' }); await pending;
  assert.equal(h.page.id, ''); assert.equal(h.page.data.form.text, '新身份内容'); assert.equal(h.page.view, 'submit');
});
test('HQ detail reload stays commission-scoped rather than using CRM customer context', async () => {
  const h = harness(); h.setContext({ commission: { commissionId: 'c1', commission: { id: 'c1', customerId: 'customer1', status: 'DESIGNING' }, submissions: [], handovers: [], progress: [], dispatches: [], allowedActions: [] } });
  h.page.onLoad({ view: 'progress', commissionId: 'c1' }); await h.page.refresh(); await h.page.refresh();
  assert.deepEqual(h.calls.map(c => c[0]), ['get', 'get']);
});
