const test = require('node:test');
const assert = require('node:assert/strict');
const { connect, render } = require('./role-page-runtime.cjs');
const path = require('node:path');
const auth = require('../miniprogram/api/auth');
const eng = require('../miniprogram/api/franchise/engineering');
const route = '/pages/build/material-flow/index';
const project = '901', plan = '902', material = '90071992547409931', supply = '904', batch = '90071992547409935';
const prefix = '/franchise/engineering/';
const tick = () => new Promise(resolve => setImmediate(resolve));
const texts = nodes => nodes.map(n => (n.text || '') + texts(n.children || [])).join('');
async function open(role = 'engineer') {
  const runtime = await connect({ lifecycles: true });
  const calls = [], post = { postId: '42', userId: '71', tenantId: '1', orgId: '31', orgType: 'store', roleCode: role };
  auth.saveLogin({ accessToken: 'TEST-ONLY-flow', userId: '71' }); auth.savePosts([post]); auth.switchPost('42');
  const row = { materialId: material, supplyId: supply, name: '水泥', unit: '吨', received: 2.5, availableQuantity: 1.25,
    consumedQuantity: 1, movements: [{ id: '801', kind: 'RETURN', quantity: 0.25, settlementStatus: 'PENDING_SETTLE',
      note: '退原供应商', occurredAt: '2026-11-25T10:00:00Z', authorUserId: '71' }],
    pendingReships: [{ id: batch, parentReceiptId: '700', expectedQuantity: 0.5 }],
    receipts: [{ id: '701', quality: 'REJECTED', qualified: false, actualQuantity: 0.25 }] };
  const state = { row, calls, fail: false };
  wx.request = request => {
    const url = new URL(request.url).pathname.replace('/admin-api', '');
    const write = request.method === 'POST';
    let data;
    if (write) { calls.push({ url, body: request.data, key: request.header['Idempotency-Key'] }); data = {}; }
    else if (url === prefix + project) data = { id: project, status: 'IN_PROGRESS', managerUserId: '71' };
    else if (url.endsWith('/materials/summary')) data = { projectId: project, plans: [{ materialPlanId: plan, lines: [row] }] };
    else if (url.endsWith('/handlers')) data = [{ id: '71', name: '现场负责人' }];
    else if (url === '/platform/posts/mine') data = [post];
    else throw Error(url);
    setImmediate(() => write && state.fail ? request.fail({ errMsg: 'request:fail timeout' })
      : request.success({ statusCode: 200, data: { code: 0, data } }));
    return {};
  };
  await runtime.callWxMethod('navigateTo', { url: route + '?project=' + project + '&plan=' + plan + '&material=' + material });
  for (let i=0;i<9;i++) await tick();
  state.page = getCurrentPages().at(-1);
  state.text = () => texts(render(path.resolve(__dirname, '../miniprogram' + route + '.wxml'), state.page.data));
  return state;
}
const input = (page,key,value) => page.input({ currentTarget: { dataset: { key } }, detail: { value } });

test('工程原单可领用、退料、查看待结算和进入原收货质检，财务仅读', async () => {
  const s = await open(), p=s.page;
  assert.match(s.text(), /可分配/); assert.match(s.text(), /待财务结算/);
  assert.equal(p.data.editable, true); assert.equal(p.data.qualityRows[0].url,
    '/pages/build/issues/index?project=901&supply=904&receipt=701');
  input(p, 'quantity', '1.251'); input(p, 'note', '现场一层');
  await p.submitMovement(); assert.equal(s.calls.length, 0);
  input(p, 'quantity', '1.250'); s.fail=true;
  await p.submitMovement(); assert.equal(p.data.uncertain, true);
  input(p, 'quantity', '.100'); s.row.availableQuantity=0; s.fail=false;
  await p.retry();
  assert.equal(s.calls.length,2); assert.equal(s.calls[0].key,s.calls[1].key);
  assert.deepEqual(s.calls[1].body,s.calls[0].body);
  assert.equal(s.calls[0].url,prefix+project+'/movements');
  assert.equal(s.calls[0].body.materialId,material); assert.equal(Number(s.calls[0].body.quantity),1.25);
  assert.equal(s.calls[0].body.kind,'CONSUME');
  const read = await open('finance');
  assert.equal(read.page.data.editable,false);
  await read.page.submitMovement(); assert.equal(read.calls.length,0);
});

test('退料仅登记原材料去向，补发实收绑定待补原行及凭据', async () => {
  const s=await open(),p=s.page;
  p.pickKind({ detail: { value: '2' } }); input(p,'quantity','0.250'); input(p,'note','退供应商');
  await p.submitMovement();
  assert.equal(s.calls[0].body.kind,'RETURN'); assert.equal(s.calls[0].url,prefix+project+'/movements');
  const old=eng.uploadVoucher;
  try {
    eng.uploadVoucher=async(file,type)=>{assert.equal(type,'ENG_SITE_PHOTO');assert.equal(file,'/proof.jpg');return '905';};
    p.pickReship({ currentTarget:{ dataset:{ id:batch } } });
    input(p,'arrivalQuantity','0.500');input(p,'transaction','补发流水-1');p.setData({ photo:'/proof.jpg' });
    await p.submitReship();
    assert.equal(s.calls[1].url,prefix+'supplies/'+supply+'/arrival'); assert.equal(s.calls[1].body.parentReceiptId,batch);
    assert.equal(s.calls[1].body.fileAssetId,'905');assert.equal(Number(s.calls[1].body.actual),0.5);
  } finally {eng.uploadVoucher=old;}
});
