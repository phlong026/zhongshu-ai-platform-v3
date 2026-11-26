// Invoked by the backend's miniProgramToSpringControllersOverHttp integration test.
// The supplied Spring server and test identities are disposable and bind to loopback.
const assert = require('node:assert/strict');
const [origin, planId] = process.argv.slice(2);
assert.equal(new URL(origin).hostname, '127.0.0.1');
const storage = new Map([['zs-api-base', origin], ['zs-api-token-v1','alpha'], ['zs-api-post-v1','alpha']]);
let apiRequests = 0;
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage.get(key), setStorageSync: (key,value) => storage.set(key,value),
  removeStorageSync: key => storage.delete(key),
  request: async opt => {
    apiRequests++;
    try {
      const response = await fetch(opt.url, { method: opt.method, headers: opt.header,
        body: opt.method === 'GET' ? undefined : JSON.stringify(opt.data) });
      opt.success({ statusCode: response.status, data: await response.json() });
    } catch (err) { opt.fail({ errMsg: String(err) }); }
  }
};
function page(module) {
  let definition;
  global.Page = v => { definition=v; };
  require(module);
  delete global.Page;
  return { ...definition, data: structuredClone(definition.data),
    setData(patch, callback) { Object.assign(this.data,patch); if(callback)callback(); } };
}
async function request(path, method='GET', data, token='hq', headers={}) {
  const response=await fetch(origin+path,{method, headers:{ Authorization:'Bearer '+token,'Content-Type':'application/json',...headers },
    body:data ? JSON.stringify(data):undefined});
  const body=await response.json();
  if(body.code!==0) throw Object.assign(Error(body.msg),{code:body.code});
  return body.data;
}
const update=(expectedVersion,priceFen,status)=>request('/admin-api/platform/ops/recharge-plans/'+planId,'PUT',{
  name:'门店充值套餐',expectedVersion,priceFen,basePoints:10,bonusPoints:2,sort:1,status,wxProductId:'product',reason:'本机联调'
});
async function run() {
  const recharge = page('../miniprogram/pages/wallet/company-recharge/index');
  let nextPage;
  wx.switchTab = ({ url }) => { nextPage = url; };
  await recharge.onLoad?.();
  await recharge.onShow?.();
  recharge.openCompanyPoints();
  assert.equal(nextPage, '/pages/profile/index');
  assert.equal(apiRequests, 0);
  assert.equal(recharge.checkout, undefined);
  console.log('PASS 小程序充值旧链接只引导查看公司积分');

  const base = '/franchise/company-points/recharge';
  const catalog = () => request(base + '/catalog', 'GET', undefined, 'alpha');
  const create = version => request(base + '/orders', 'POST', { planId, planVersion: version },
    'alpha', { 'Idempotency-Key': 'fixture-order-' + version });
  const sign = (id, code) => request(base + '/orders/' + id + '/pay-params', 'POST', { code }, 'alpha');
  assert.equal((await catalog()).plans[0].amountCents, 100);
  await update(1, 199, 'ACTIVE');
  assert.equal((await catalog()).plans[0].amountCents, 199);
  await assert.rejects(create(1), e => e.code === 409);
  await update(2, 199, 'DISABLED');
  assert.equal((await catalog()).plans.length, 0);
  await update(3, 199, 'ACTIVE');
  const order = await create(4);
  assert.equal((await sign(order.id, 'fixture-first')).paySig, 'fixture');
  assert.equal((await catalog()).currentOrder.status, 'PROCESSING');
  assert.equal((await sign(order.id, 'fixture-retry')).paySig, 'fixture');
  await update(4, 199, 'DISABLED');
  const pending = await catalog();
  assert.equal(pending.currentOrder.id, order.id);
  assert.equal(pending.currentOrder.quoteCurrent, false);
  assert.equal(pending.plans.length, 0);
  await assert.rejects(sign(order.id, 'stale-signature'), e => e.code === 409);
  for (let i = 0; i < 2; i++) await request('/fixture/paid', 'POST', {});
  const credited = (await catalog()).currentOrder;
  assert.equal(credited.status, 'CREDITED');
  assert.equal(credited.credits, '12');
  await update(5, 199, 'ACTIVE');
  console.log('PASS 后端仍可核对存量微信支付事实，重复回调只入账一次');
  const map=page('../miniprogram/pages/account/store-map/index');
  for(const [id,type,expected] of [['alpha','store',['甲一店','甲二店']],['beta','store',['乙一店']],['hq','headquarters',['甲一店','甲二店','乙一店']]]) {
    storage.set('zs-api-token-v1',id);storage.set('zs-api-post-v1',id);
    storage.set('zs-api-posts-v1',[{orgType:type,postId:id,roleCode:id==='hq'?'hq-admin':'boss',permissions:id==='hq'?['franchise:store-map:read-all']:[]}]);
    await map.load();
    assert.equal(map.data.error,'');
    assert.deepEqual(map.data.rows.map(row=>row.name).sort(),expected.sort());
    assert.ok(map.data.rows.every(r=>r.recordType==='FRANCHISE'&&r.regionCodes.some(code=>/^\d{6}$/.test(code))));
    assert.ok(map.data.markers.length>0&&map.data.markers.every(r=>Number.isFinite(r.latitude)&&Number.isFinite(r.longitude)));
    if(id==='hq') assert.equal(new Set(map.data.rows.map(row=>row.color)).size,2);
    const raw=await request('/franchise/store-map?brandId=other&scope=all','GET',undefined,id);
    assert.equal(raw.schemaVersion,2);
    assert.equal(raw.publicProjectionVersion,1);
    assert.ok(raw.records.every(row=>!('originalRegionText' in row)&&!('linkedOrgId' in row)&&!('ownerName' in row)&&!('phone' in row)));
    assert.deepEqual(raw.records.map(row=>row.name).sort(),expected);
    assert.ok(raw.points.every(point=>point.locationPrecision==='district'&&raw.records.some(row=>row.id===point.recordId&&row.regionCodes.includes(point.regionCode))));
    assert.deepEqual(raw.stores.map(row=>row.name).sort(),expected);
    map.onHide();
  }
  console.log('PASS 两个品牌、三家门店，服务端返回范围、三级区划、地图颜色与小程序清单一致');
  if (process.env.FRANCHISE_NATIVE_WS) await require('./verify-franchise-services-native.cjs')(origin);
}
run().catch(error=>{console.error(error);process.exitCode=1;});
