const test = require('node:test');
const assert = require('node:assert/strict');
const { readSnapshot, mapView } = require('../miniprogram/pages/account/store-map/model');
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
const key = () => '1:p';
const points = require('../miniprogram/api/company-points');
let definition;
global.Page = page => { definition = page; };
require('../miniprogram/pages/wallet/company-recharge/index');
delete global.Page;
const page = () => ({ ...definition, data: structuredClone(definition.data), actor: key(), foreground: true,
  setData(patch) { Object.assign(this.data, patch); } });
const store = { id: 's1', name: '测试店', brandId: 'b1', brandName: '甲', brandColor: '#995511',
  provinceCode: '33', cityCode: '3301', districtCode: '330110', provinceName: '浙江省', cityName: '杭州市',
  districtName: '余杭区', latitude: 30.3, longitude: 120.0, locationPrecision: 'district' };
const catalog = plan => ({ enabled: true, companyName: '测试公司', plans: plan ? [plan] : [] });
const offer = { id: 'p1', name: '门店套餐', amountCents: 1200, version: 1, credits: '120' };

test('同品牌跨店可见，混入其他品牌或错误的全品牌响应时关闭展示', () => {
  const response = { brandId: 'b1', scope: 'brand', stores: [store, { ...store, id: 's2' }] };
  const post = { orgType: 'store', brandId: 'b1' };
  assert.equal(readSnapshot(response, post).stores.length, 2);
  assert.throws(() => readSnapshot({ ...response, stores: [{ ...store, brandId: 'b2' }] }, post));
  assert.throws(() => readSnapshot({ ...response, scope: 'all' }, post));
  assert.throws(() => readSnapshot({ ...response, scope: 'all' }, { orgType: 'external' }));
  assert.equal(readSnapshot({ ...response, scope: 'all' }, { orgType: 'headquarters', roleCode: 'finance' }).admin, true);
});

test('管理员同址落点合并，品牌和三级区划共同决定标记和清单', () => {
  const org = { orgType: 'headquarters', roleCode:'hq-admin', permissions: ['franchise:store-map:read-all'] };
  const snapshot = readSnapshot({ scope: 'all', stores: [store, { ...store, id: 's2', brandId: 'b2' }] }, org);
  assert.equal(mapView(snapshot, {}).markers[0].storeIds.length, 2);
  const view = mapView(snapshot, { brand: 'b2', province: '33', city: '3301', district: '330110' });
  assert.deepEqual(view.markers[0].storeIds, ['s2']);
  assert.equal(view.rows.length, 1);
  assert.equal(view.pickers[2].choices[view.pickers[2].index].name, '余杭区');
});

test('确认时重新取套餐，改价、改积分、下架均清除旧选择并阻止建单', async t => {
  t.mock.method(points, 'fingerprint', key);
  const create = t.mock.method(http, 'post', () => assert.fail('旧套餐不能建单'));
  let current;
  t.mock.method(http, 'get', async () => catalog(current));
  for (const changed of [{ ...offer, amountCents: 2100, version: 2 }, { ...offer, credits: '150' }, null]) {
    const p = page(); current = offer; await p.refresh(); p.choose({ currentTarget: { dataset: { id: 'p1' } } });
    current = changed; await p.checkout();
    assert.equal(p.data.selected, null);
    assert.equal(p.data.plans.length, changed ? 1 : 0);
    if (changed) assert.match(p.data.note, /重新选择/);
    else assert.equal(p.data.note, '');
  }
  assert.equal(create.mock.callCount(), 0);
});

test('旧接口可展示后台上架套餐，支付能力就绪前仅展示', async t => {
  t.mock.method(points, 'fingerprint', key);
  t.mock.method(auth, 'currentApiPost', () => ({ orgName: '测试店' }));
  t.mock.method(http, 'get', async path => {
    if (path.endsWith('/catalog')) throw { code: 404 };
    assert.equal(path, '/platform/wallet/recharge-plans');
    return [{ id: 'p', name: '试用', priceFen: 199, basePoints: '10', bonusPoints: '2' }];
  });
  const p = page();
  await p.refresh();
  assert.equal(p.data.plans[0].amount, '¥1.99');
  assert.equal(p.data.plans[0].credits, '12');
  assert.equal(p.data.browseOnly, true);
  p.choose({ currentTarget: { dataset: { id: 'p' } } });
  assert.equal(p.data.selected, null);
});

test('旧响应和切岗前的套餐均不能覆盖当前页面', async t => {
  let identity = key(), release;
  t.mock.method(points, 'fingerprint', () => identity);
  const mock = t.mock.method(http, 'get', async () => catalog(offer));
  mock.mock.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  const p = page(), first = p.refresh();
  await p.refresh(); release(catalog(null)); await first;
  assert.equal(p.data.plans.length, 1);
  mock.mock.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  const beforeSwitch = p.refresh(); identity = '2:q'; release(catalog(null)); await beforeSwitch;
  assert.equal(p.data.plans.length, 1);
});

test('连续点击只建一单，支付返回后仍以公司积分订单状态判定到账', async t => {
  t.mock.method(points, 'fingerprint', key);
  let order = null;
  t.mock.method(http, 'get', async () => ({ ...catalog(offer), currentOrder: order }));
  const calls = [];
  t.mock.method(http, 'post', async (path, data, headers) => {
    calls.push(path);
    if (path.endsWith('/orders')) {
      assert.deepEqual(data, { planId: 'p1', planVersion: '1' });
      assert.ok(headers['Idempotency-Key']);
      order = { id: 'o1', amountCents: 1200, credits: '120', status: 'CREATED' };
      return order;
    }
    assert.equal(path, '/franchise/company-points/recharge/orders/o1/pay-params');
    assert.deepEqual(data, { code: 'fixture-code' });
    return { mode: 'short_series_goods', signData: 'fixture', signature: 'fixture', paySig: 'fixture' };
  });
  const originalWx = global.wx;
  global.wx = { login: options => options.success({ code: 'fixture-code' }), requestVirtualPayment: options => {
    order.status = 'PAID';
    options.success({});
  } };
  try {
    const p = page(); await p.refresh(); p.choose({ currentTarget: { dataset: { id: 'p1' } } });
    await Promise.all([p.checkout(), p.checkout()]);
    assert.equal(calls.length, 2);
    assert.equal(p.data.order.label, '积分到账中');
    order.status = 'CREDITED'; await p.refresh();
    assert.equal(p.data.order.label, '积分已到账');
    p.dismissOrder(); await p.refresh(); assert.equal(p.data.order, null);
  } finally { global.wx = originalWx; }
});
