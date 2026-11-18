// Invoked by the backend's miniProgramToSpringControllersOverHttp integration test.
// The supplied server is disposable and binds to loopback. Only the WeChat SDK and test identities are substituted.
const assert = require('node:assert/strict');
const { setTimeout: sleep } = require('node:timers/promises');
const [origin, planId] = process.argv.slice(2);
assert.equal(new URL(origin).hostname, '127.0.0.1');
const storage = new Map([['zs-api-base', origin], ['zs-api-token-v1','alpha'], ['zs-api-post-v1','alpha']]);
let paymentAttempts = 0;
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage.get(key), setStorageSync: (key,value) => storage.set(key,value),
  removeStorageSync: key => storage.delete(key),
  request: async opt => {
    try {
      const response = await fetch(opt.url, { method: opt.method, headers: opt.header,
        body: opt.method === 'GET' ? undefined : JSON.stringify(opt.data) });
      opt.success({ statusCode: response.status, data: await response.json() });
    } catch (err) { opt.fail({ errMsg: String(err) }); }
  },
  login: o => o.success({ code:'sdk-fixture' }),
  requestVirtualPayment: o => {
    assert.equal(o.paySig,'fixture');
    if (++paymentAttempts === 1) o.fail({errMsg:'requestVirtualPayment:fail cancel'});
    else o.success({});
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
async function until(check,timeout=14000) {
  for(let end=Date.now()+timeout;Date.now()<end;){if(check())return;await sleep(100);}
  assert.fail('等待界面条件超时');
}
async function run() {
  const recharge=page('../miniprogram/pages/wallet/company-recharge/index');
  recharge.onShow();
  try {
    await until(()=>!recharge.data.loading);
    assert.equal(recharge.data.error,'');
    const choose=()=>recharge.choose({currentTarget:{dataset:{id:planId}}});
    choose(); assert.equal(recharge.data.selected.amountCents,100);
    await update(1,199,'ACTIVE');
    await until(()=>recharge.data.plans[0]?.amountCents===199);
    assert.equal(recharge.data.selected,null);
    await assert.rejects(request('/franchise/company-points/recharge/orders','POST',
      {planId,planVersion:'1'},'alpha',{'Idempotency-Key':'stale-order'}),e=>e.code===409);
    choose(); await update(2,199,'DISABLED');
    await until(()=>!recharge.data.plans.length); assert.equal(recharge.data.selected,null);
    await update(3,199,'ACTIVE');
    recharge.onHide(); recharge.onShow(); await until(()=>recharge.data.plans.length===1);
    choose(); await recharge.checkout();
    assert.equal(recharge.data.order?.status,'PROCESSING',JSON.stringify(recharge.data));
    const signedOrder = recharge.data.order.id;
    assert.equal(recharge.data.order.canPay,true);
    await recharge.checkout();
    assert.equal(paymentAttempts,2);
    assert.equal(recharge.data.order.id,signedOrder);
    await update(4,199,'DISABLED');
    await recharge.refresh();
    assert.equal(recharge.data.order.quoteExpired,true);
    assert.equal(recharge.data.plans.length,0);
    await assert.rejects(request('/franchise/company-points/recharge/orders/'+signedOrder+'/pay-params','POST',
      {code:'stale-signature'},'alpha'),e=>e.code===409);
    recharge.dismissOrder();
    for(let i=0;i<2;i++) await request('/fixture/paid','POST',{});
    await recharge.refresh(); assert.equal(recharge.data.order.label,'积分已到账');
    assert.equal(recharge.data.order.credits,'12');
    await update(5,199,'ACTIVE');
    console.log('PASS 真实套餐管理接口改价 1.00 → 1.99 元，页面自动更新；旧版本拒单；下架自动移除；上架恢复；同单支付事实重放后显示 12 积分已到账');
  } finally { recharge.onUnload(); }
  const map=page('../miniprogram/pages/account/store-map/index');
  for(const [id,type,expected] of [['alpha','store',['甲一店','甲二店']],['beta','store',['乙一店']],['hq','headquarters',['甲一店','甲二店','乙一店']]]) {
    storage.set('zs-api-token-v1',id);storage.set('zs-api-post-v1',id);
    storage.set('zs-api-posts-v1',[{orgType:type,postId:id,permissions:id==='hq'?['franchise:store-map:read-all']:[]}]);
    await map.load();
    assert.equal(map.data.error,'');
    assert.deepEqual(map.data.rows.map(row=>row.name).sort(),expected.sort());
    assert.ok(map.data.rows.every(r=>Number.isFinite(r.latitude)&&r.districtCode.length===6));
    if(id==='hq') assert.equal(new Set(map.data.rows.map(row=>row.color)).size,2);
    const raw=await request('/franchise/store-map?brandId=other&scope=all','GET',undefined,id);
    assert.deepEqual(raw.stores.map(row=>row.name).sort(),expected);
    map.onHide();
  }
  console.log('PASS 两个品牌、三家门店，服务端返回范围、三级区划、地图颜色与小程序清单一致');
  if (process.env.FRANCHISE_NATIVE_WS) await require('./verify-franchise-services-native.cjs')(origin,update);
}
run().catch(error=>{console.error(error);process.exitCode=1;});
