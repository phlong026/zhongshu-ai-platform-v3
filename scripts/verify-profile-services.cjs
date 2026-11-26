const test = require('node:test');
const assert = require('node:assert/strict');
const { readSnapshot, mapView } = require('../miniprogram/pages/account/store-map/model');
const store = { id: 's1', name: '测试店', brandId: 'b1', brandName: '甲', brandColor: '#995511',
  provinceCode: '33', cityCode: '3301', districtCode: '330110', provinceName: '浙江省', cityName: '杭州市',
  districtName: '余杭区', latitude: 30.3, longitude: 120.0, locationPrecision: 'district' };

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
