const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(process.env.ZS_TEST_ROOT || path.join(__dirname, '..'));

function loadPlatformFinance() {
  const file = path.join(ROOT, 'miniprogram/utils/platform-finance.js');
  delete require.cache[require.resolve(file)];
  return require(file);
}

function setPath(target, key, value) {
  const parts = key.split('.');
  let cursor = target;
  for (let i = 0; i < parts.length - 1; i += 1) {
    cursor[parts[i]] = cursor[parts[i]] || {};
    cursor = cursor[parts[i]];
  }
  cursor[parts.at(-1)] = value;
}

function loadFinanceDesk({ profile = { nickname: '王财务' }, orgName = '武汉旗舰店' } = {}) {
  const source = fs.readFileSync(path.join(ROOT, 'miniprogram/pages/admin/finance-desk/index.js'), 'utf8');
  const PF = loadPlatformFinance();
  const calls = [];
  let page;
  const financeApi = {
    receipts: () => Promise.resolve([]),
    payments: () => Promise.resolve([]),
    refunds: () => Promise.resolve([]),
    bankFlows: () => Promise.resolve([]),
    payables: () => Promise.resolve([]),
    reconciliation: month => Promise.resolve({ month })
  };
  const http = {
    token: () => 'token',
    requestGuard: () => () => true,
    get: url => { calls.push(url); return profile instanceof Error ? Promise.reject(profile) : Promise.resolve(profile); }
  };
  const sandbox = {
    Page(definition) { page = definition; },
    require(request) {
      if (request === '../../../utils/finance-desk') return { url: (view, id, extra = {}) => '/pages/admin/finance-desk/index?view=' + view + (extra.tab ? '&tab=' + extra.tab : '') };
      if (request === '../../../api/auth') return { currentApiPost: () => ({ postId: '9', roleCode: 'finance', orgType: 'store', orgName }) };
      if (request === '../../../api/finance') return financeApi;
      if (request === '../../../api/http') return http;
      if (request === '../../../utils/platform-finance') return PF;
      if (request === '../../../utils/access') return { context: () => ({}) };
      if (request === '../../../utils/page-navigation') return {};
      if (request === '../../../utils/view-container') return { routeValue: value => value || '' };
      if (request.includes('platform-todos-page')) return { init() {}, refresh() {} };
      if (request.includes('franchise/performance')) return { operating: {} };
      if (request.includes('fr-web-link')) return {};
      throw new Error('unexpected require: ' + request);
    },
    console,
    Date,
    Promise
  };
  vm.runInNewContext(source, sandbox, { filename: 'finance-desk/index.js' });
  page.data = { ...page.data, view: 'desk' };
  page.setData = patch => {
    for (const [key, value] of Object.entries(patch)) setPath(page.data, key, value);
  };
  return { page, calls };
}

async function flush() {
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
}

test('zero-value finance metrics render as 0.00 without changing generic money or positive amount validation', () => {
  const PF = loadPlatformFinance();
  assert.equal(PF.yuan(0), '');
  assert.deepEqual(PF.metrics([], [], [], '2026-11'), { received: '0.00', paid: '0.00', refunded: '0.00' });
  assert.equal(PF.toCents('0'), null);
  assert.equal(PF.toCents('-1'), null);
  assert.equal(PF.toCents('0.01'), 1);
});

test('non-empty records outside the current valid scope still render current-month totals as 0.00', () => {
  const PF = loadPlatformFinance();
  const metrics = PF.metrics(
    [
      { status: '已登记', receivedDate: '2026-10-31', amountCents: 10000 },
      { status: '已作废', receivedDate: '2026-11-01', amountCents: 20000 },
      { status: '已登记', receivedDate: '', amountCents: 30000 }
    ],
    [
      { status: '已核实', payDate: '2026-10-31', amountCents: 10000 },
      { status: '已作废', payDate: '2026-11-01', amountCents: 20000 }
    ],
    [
      { status: '已核实', date: '2026-10-31', amountCents: 10000 },
      { status: '已作废', date: '2026-11-01', amountCents: 20000 },
      { status: '已核实', refundDirection: 'expense', date: '2026-11-01', amountCents: 30000 }
    ],
    '2026-11'
  );
  assert.deepEqual(metrics, { received: '0.00', paid: '0.00', refunded: '0.00' });
});

test('empty API desk keeps real registration shortcuts and loads the profile nickname', async () => {
  const { page, calls } = loadFinanceDesk();
  page.loadPlatformFinance();
  assert.ok(page.data.deskHome.shortcuts.length >= 3);
  assert.ok(page.data.deskHome.shortcuts.some(item => item.type === 'receipt'));
  assert.ok(page.data.deskHome.shortcuts.some(item => item.type === 'payment'));
  let openedType = '';
  page.openRegister = event => { openedType = event.currentTarget.dataset.type; };
  page.openFinanceShortcut({ currentTarget: { dataset: { type: 'receipt', url: '' } } });
  assert.equal(openedType, 'receipt');
  assert.equal(page.data.view, 'flows', '快捷登记应先进入包含登记表单的收支视图');
  assert.equal(page.data.flowTab, 'ledger');
  await flush();
  assert.deepEqual(page.data.platformFinance.metrics, { received: '0.00', paid: '0.00', refunded: '0.00' });
  assert.equal(page.data.deskHome.name, '王财务');
  assert.deepEqual(calls, ['/platform/profile/mine']);
});

test('profile failure keeps the explicit organization-name fallback', async () => {
  const { page } = loadFinanceDesk({ profile: new Error('offline'), orgName: '武汉旗舰店' });
  page.loadPlatformFinance();
  await flush();
  assert.equal(page.data.deskHome.name, '武汉旗舰店');
});
