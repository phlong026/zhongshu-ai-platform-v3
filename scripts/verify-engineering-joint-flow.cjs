const test = require('node:test'),
  assert = require('node:assert/strict'),
  fs = require('fs'),
  vm = require('vm'),
  path = require('path'),
  { createRequire } = require('module');
function harness() {
  const f = path.resolve('miniprogram/utils/platform-engineering.js'),
    m = { exports: {} },
    real = createRequire(f),
    calls = [];
  global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) };
  const api = {
    plan: { prepare: async (id, body) => calls.push(body) },
    materials: { submit: async (id, body) => calls.push(body) },
    uploadVoucher: async () => '9007199254740999'
  };
  vm.runInNewContext(fs.readFileSync(f, 'utf8'), {
    module: m,
    require: (n) => (n === '../api/franchise/engineering' ? api : real(n)),
    wx: global.wx
  });
  return { P: m.exports, calls };
}
const bundle = () => ({
  detail: {
    id: '100',
    managerName: '李琦',
    nodes: [
      { id: '101', nodeIndex: 1, name: '基础工程', status: 'PENDING' },
      { id: '102', nodeIndex: 2, name: '主体封顶', status: 'PENDING', milestone: 'TOPPING' }
    ]
  },
  collections: {
    stages: [{ stage: 'SIGN_AFTER', expectedCents: 100000, confirmedCents: 0, remainingCents: 100000 }]
  },
  materials: { plans: [] },
  settle: {},
  followups: []
});
test('handover next step progresses through preparation, schedule, collection, start, node, delivery, settlement and archive', () => {
  const { P } = harness(),
    b = bundle(),
    next = () => P.recordView(b, {}).next;
  assert.match(next().url, /prepare$/);
  b.detail.preparedAt = '2026-11-17';
  assert.match(next().url, /project$/);
  b.detail.nodes.forEach((n) => Object.assign(n, { startDate: '2026-11-17', endDate: '2026-11-17' }));
  assert.match(next().url, /collection/);
  Object.assign(b.collections.stages[0], { confirmedCents: 100000, remainingCents: 0 });
  assert.match(next().url, /start$/);
  b.detail.actualStartAt = '2026-11-17';
  assert.match(next().url, /node&nodeId=101/);
  b.detail.nodes.forEach((n) => (n.status = 'DONE'));
  assert.match(next().url, /delivery$/);
  b.detail.closure = { deliveredAt: '2026-11-17' };
  assert.match(next().url, /settlement$/);
  b.detail.closure.settledAt = '2026-11-17';
  assert.match(next().url, /archive$/);
  b.detail.closure.archivedAt = '2026-11-17';
  assert.equal(next(), null);
});
test('selected material node is sent instead of the current construction node', async () => {
  const { P, calls } = harness();
  await P.perform(
    'material-plan',
    '100',
    { nodeId: '102', due: '2026-11-17', lines: [{ name: '钢筋', quantity: '2', unit: '吨' }] },
    { id: '101' },
    'k'
  );
  assert.equal(calls[0].nodeId, '102');
});
test('prepare submits edited plan atomically with the contract', async () => {
  const { P, calls } = harness();
  await P.perform(
    'prepare',
    '100',
    {
      total: '300',
      signAfter: '100',
      baseDone: '100',
      capDone: '100',
      file: { path: '/test.png' },
      nodes: [{ nodeIndex: 1, name: '基础', start: '2026-11-17', end: '2026-11-18' }]
    },
    null,
    'k'
  );
  assert.equal(calls[0].nodes[0].endDate, '2026-11-18');
  assert.equal(calls[0].nodes[0].nodeIndex, 1);
});
test('node-linked materials, arrival evidence and partial payment remain visible after the current node changes', () => {
  const { P } = harness(),
    b = bundle();
  b.materials.plans = [
    {
      materialPlanId: '90',
      arrival: '部分到货',
      totalCents: 1000,
      paidCents: 400,
      remainingCents: 600,
      lines: [
        {
          materialId: '91',
          nodeId: '102',
          supplyId: '92',
          name: '钢筋',
          unit: '吨',
          quantity: 2,
          received: 1,
          remaining: 1,
          totalCents: 1000,
          termsLocked: false,
          receipts: [
            {
              id: '93',
              actualQuantity: 1,
              qualified: true,
              occurredAt: '2026-11-17',
              fileAssetId: '94',
              corrections: '[]'
            }
          ]
        }
      ]
    }
  ];
  const v = P.recordView(b, { view: 'material', planId: '90', writeRole: true });
  assert.equal(v.node.id, '102');
  assert.equal(v.arrivals.length, 1);
  assert.equal(v.arrivals[0].file.assetId, '94');
  assert.equal(v.nodePlans.length, 1);
  assert.equal(v.nodeMoney.remainingText, '¥ 6');
  assert.equal(P.recordView(b, { view: 'materials', nodeId: '101' }).plans.length, 0);
});
test('completed lifecycle locks plan editing and exposes list progress from actual node counts', () => {
  const { P } = harness(),
    b = bundle();
  b.detail.closure = { deliveredAt: '2026-11-17' };
  assert.equal(P.recordView(b, { view: 'project', writeRole: true }).actions.length, 0);
  assert.equal(
    P.listCards([{ id: '1', status: 'IN_PROGRESS', completedNodes: 2, totalNodes: 5 }])[0].progress,
    40
  );
});
test('record page has one site-history section and no implementation/release copy', () => {
  const w = fs.readFileSync('miniprogram/pages/build/record/index.wxml', 'utf8');
  assert.equal((w.match(/class="eng-section node-site-records"/g) || []).length, 1);
  assert.doesNotMatch(w, /COS|后续批次|在线保存已开放/);
});
test('zero or fully collected installments never offer a new collection or dunning action', () => {
  const { P } = harness();
  for (const [expected, confirmed, remaining] of [
    [0, 0, 0],
    [100000, 100000, 0]
  ]) {
    const b = bundle();
    Object.assign(b.collections.stages[0], {
      expectedCents: expected,
      confirmedCents: confirmed,
      remainingCents: remaining
    });
    for (const role of [{ writeRole: true }, { financeRole: true }]) {
      const v = P.recordView(b, { view: 'collection', stage: 'start', ...role });
      assert.equal(v.actions.length, 0);
      assert.equal(v.fields.length, 0);
    }
  }
  const b = bundle();
  assert.equal(
    P.recordView(b, { view: 'collection', stage: 'start', writeRole: true }).actions[0].action,
    'dunning'
  );
  assert.equal(
    P.recordView(b, { view: 'collection', stage: 'start', financeRole: true }).actions[0].action,
    'collect'
  );
});
test('legacy project links select a project before loading detail and retain node context when present', () => {
  let page;
  const urls = [];
  vm.runInNewContext(fs.readFileSync('miniprogram/pages/build/index.js', 'utf8'), {
    Page: (p) => (page = p),
    require: () => ({ online: () => true }),
    wx: { redirectTo: (o) => urls.push(o.url) }
  });
  page.onLoad({ view: 'projects' });
  page.onLoad({ view: 'materials' });
  page.onLoad({ view: 'schedule', id: '123' });
  page.onLoad({ view: 'inspection', id: '123', nodeId: '456' });
  assert.deepEqual(urls, [
    '/pages/build/projects/index',
    '/pages/build/projects/index?destination=materials',
    '/pages/build/record/index?view=project&id=123',
    '/pages/build/record/index?view=node&id=123&nodeId=456'
  ]);
});
test('partial receipts, electronic briefing and archived preparation are represented truthfully', () => {
  const { P } = harness(),
    b = bundle();
  b.collections.stages[0].confirmedCents = 50000;
  b.collections.stages[0].remainingCents = 50000;
  b.detail.documents = [
    { name: '电子交底', fileRef: JSON.stringify({ name: '交底记录', text: '场地和水电已核对' }) }
  ];
  let v = P.recordView(b, { view: 'prepare', writeRole: true });
  assert.equal(v.installments[0].status, '部分收款');
  assert.equal(v.docs[0].file.text, '场地和水电已核对');
  b.detail.closure = { archivedAt: '2026-11-17' };
  v = P.recordView(b, { view: 'prepare', writeRole: true });
  assert.equal(v.fields.length, 0);
  assert.equal(v.actions.length, 0);
});
test('reopen is limited to the latest completed node and hides after a subsequent record or topping receipt', () => {
  const { P } = harness(),
    b = bundle();
  b.detail.nodes[0].status = 'DONE';
  const view = (id) =>
    P.recordView(b, { view: 'node', nodeId: id, writeRole: true }).actions.some(
      (a) => a.action === 'node-reopen'
    );
  assert.equal(view('101'), true);
  b.detail.siteRecords = [{ node_id: '102' }];
  assert.equal(view('101'), false);
  b.detail.siteRecords = [];
  b.detail.nodes[1].status = 'DONE';
  assert.equal(view('101'), false);
  assert.equal(view('102'), true);
  b.collections.records = [{ id: '9', stage: 'CAP_DONE', amountCents: 1000 }];
  assert.equal(view('102'), false);
});
