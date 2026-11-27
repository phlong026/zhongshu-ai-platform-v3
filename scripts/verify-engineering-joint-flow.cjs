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
    plan: { prepare: async (id, body) => calls.push(body), complete: async (id,node,body,key) => calls.push({id,node,body,key}) },
    materials: { submit: async (id, body) => calls.push(body) },
    uploadVoucher: async () => '9007199254740999'
  };
  vm.runInNewContext(fs.readFileSync(f, 'utf8'), {
    module: m,
    require: (n) => (n === '../api/franchise/engineering' ? api : real(n)),
    wx: global.wx
  });
  return { P: m.exports, calls, api };
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

test('three completion photos are all submitted and remain available in a fresh server read',async()=>{
 const {P,api,calls}=harness(),uploads=[];
 api.uploadVoucher=async(file,type)=>{uploads.push({file,type});return String(9007199254740993n+BigInt(uploads.length));};
 await P.perform('node','100',{date:'2026-11-17',note:'验收完成',files:[{path:'one.jpg'},{path:'two.jpg'},{path:'three.jpg'}]},{id:'101'},'original-key');
 assert.equal(uploads.length,3);assert.ok(uploads.every(r=>r.type==='ENG_SITE_PHOTO'));assert.equal(calls.length,1);
 const ids=['9007199254740994','9007199254740995','9007199254740996'];
 assert.equal(JSON.stringify(calls[0].body.fileAssetIds),JSON.stringify(ids));assert.equal(calls[0].key,'original-key');
 const b=bundle();b.detail.siteRecords=[{id:'record',node_id:'101',occurred_at:'2026-11-17',completion:true,note:'验收完成',file_asset_ids:JSON.stringify(ids)}];
 const saved=P.recordView(b,{view:'node',nodeId:'101'});
 assert.equal(JSON.stringify(saved.inspections[0].files.map(f=>f.assetId)),JSON.stringify(ids));
 assert.equal(JSON.stringify(saved.siteRecords[0].files.map(f=>f.assetId)),JSON.stringify(ids));
 const {render}=require('./role-page-runtime.cjs');const flat=rows=>rows.flatMap(r=>[r,...flat(r.children||[])]);
 const nodes=flat(render(path.resolve('miniprogram/pages/build/record/index.wxml'),{...saved,view:'node',expanded:{inspections:true}}));
 assert.equal(nodes.filter(r=>r.tag==='button'&&r.attrs?.bindtap==='preview'&&ids.includes(r.attrs['data-file']?.assetId)).length,3);
});
test('completion bounds and any upload failure prevent a partially saved node',async()=>{
 const {P,api,calls}=harness();let uploads=0;
 api.uploadVoucher=async()=>{uploads++;if(uploads===2)throw Error('上传失败');return '9007199254740994';};
 await assert.rejects(P.perform('node','100',{files:[]},{id:'101'},'empty'),/1至9/);
 await assert.rejects(P.perform('node','100',{files:Array.from({length:10},()=>({path:'x'}))},{id:'101'},'too-many'),/1至9/);
 assert.equal(uploads,0);
 await assert.rejects(P.perform('node','100',{files:[{path:'a'},{path:'b'},{path:'c'}]},{id:'101'},'original'),/上传失败/);
 assert.equal(calls.length,0);assert.equal(uploads,2);
});
test('a node chat file appends to earlier photos, while correction targets its own field',()=>{
 let page;const file=path.resolve('miniprogram/pages/build/record/index.js'),real=createRequire(file);
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:p=>page=p,require:real});
 page.data={view:'node',form:{files:[{path:'old-a'},{path:'old-b'}]}};
 page.setData=patch=>{for(const[k,v]of Object.entries(patch)){const parts=k.split('.');let target=page.data;for(const part of parts.slice(0,-1))target=target[part]||(target[part]={});target[parts.at(-1)]=v;}};
 page.setFile('file',{name:'验收单.pdf',path:'new.pdf'});
 assert.deepEqual(Array.from(page.data.form.files,f=>f.path),['old-a','old-b','new.pdf']);
 page.setFile('nodeFile',{name:'更正件.pdf',path:'correction.pdf'});
 assert.equal(page.data.form.nodeFile.path,'correction.pdf');
 assert.deepEqual(Array.from(page.data.form.files,f=>f.path),['old-a','old-b','new.pdf']);
});
test('handover next step progresses through preparation, schedule, collection, start, node, delivery, settlement and archive', () => {
  const { P } = harness(),
    b = bundle(),
    next = () => P.recordView(b, {}).next;
  // Real templates retain one START node; preparation does not complete it.
  const start = { id: '103', nodeIndex: 1, name: '正式开工', milestone: 'START', status: 'PENDING' };
  b.detail.nodes = [start, ...b.detail.nodes.map((n, i) => ({ ...n, nodeIndex: i === 0 ? 5 : 8 }))];
  const base = '/pages/build/record/index?id=100&view=';
  assert.match(next().url, /prepare$/);
  b.detail.preparedAt = '2026-11-17';
  assert.equal(next().url, base + 'project');
  // Only the planned start date is needed; later construction dates stay unknown.
  Object.assign(start, { startDate: '2026-11-17', endDate: '2026-11-17' });
  assert.equal(next().url, base + 'collection&stage=start');
  const collection = P.recordView(b, { view: 'collection', stage: 'start', financeRole: true });
  assert.equal(collection.form.stage, 'SIGN_AFTER');
  assert.equal(collection.actions[0].action, 'collect');
  assert.equal(P.recordView(b, { view: 'start', writeRole: true }).actions.length, 0);
  Object.assign(b.collections.stages[0], { confirmedCents: 100000, remainingCents: 0 });
  assert.equal(next().url, base + 'start');
  assert.equal(P.recordView(b, { view: 'start', writeRole: true }).actions[0].action, 'start');
  b.detail.actualStartAt = '2026-11-17';
  Object.assign(start, { status: 'DONE', completedAt: '2026-11-17' });
  b.detail.nodes[1].status = 'IN_PROGRESS';
  assert.equal(next().url, base + 'node&nodeId=101');
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

