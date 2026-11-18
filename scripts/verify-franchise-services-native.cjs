// Optional verification in a dedicated WeChat DevTools project connected to the same test backend.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { setTimeout: sleep } = require('node:timers/promises');
const automator = require('miniprogram-automator');

module.exports = async function verifyNative(origin, update) {
  const ws = new URL(process.env.FRANCHISE_NATIVE_WS);
  assert.equal(ws.hostname, '127.0.0.1');
  const out = path.resolve(__dirname, '../docs/加盟商服务联调-20261118');
  await fs.mkdir(out, {recursive:true});
  const app = await automator.connect({wsEndpoint:ws.href});
  async function waitFor(page, check) {
    let state;
    for (let end = Date.now() + 16500; Date.now() < end;) {
      state = await app.evaluate(()=>getCurrentPages().slice(-1)[0].data);
      if (check(state)) return state;
      if (state.error) throw Error(state.error);
      await sleep(200);
    }
    throw Error('原生页面等待超时 ' + page.path);
  }
  async function login(id, orgType) {
    await app.evaluate(({origin,id,orgType}) => {
      wx.setStorageSync('zs-api-base', origin);
      wx.setStorageSync('zs-api-token-v1', id);
      wx.setStorageSync('zs-api-post-v1', id);
      wx.setStorageSync('zs-api-posts-v1', [{postId:id,orgType,
        roleCode:orgType==='store'?'owner':'boss',permissions:id==='hq'?['franchise:store-map:read-all']:[]}]);
    }, {origin,id,orgType});
  }
  async function shot(name) {
    await sleep(350);
    await app.screenshot({path:path.join(out,name+'.png')});
  }
  try {
    assert.equal(await app.evaluate(()=>typeof wx.request), 'function');
    for (const [id,type,count,file] of [['alpha','store',2,'01-品牌甲门店'],['beta','store',1,'02-品牌乙门店'],['hq','headquarters',3,'03-各品牌门店']]) {
      await login(id,type);
      const page = await app.reLaunch('/pages/account/store-map/index');
      const state = await waitFor(page, s => !s.loading && s.rows?.length===count);
      const header = await (await page.$('.scope')).text();
      assert.equal(header, id==='hq'?'各品牌门店':state.brandName);
      await shot(file);
    }
    await login('alpha','store');
    const page = await app.reLaunch('/pages/wallet/company-recharge/index');
    await waitFor(page,s=>s.order?.status==='CREDITED');
    await shot('04-充值到账');
    await app.evaluate(()=>{getCurrentPages().slice(-1)[0].dismissOrder();return true;});
    await waitFor(page,s=>!s.order && s.plans?.[0]?.amountCents===199);
    await app.evaluate(()=>{const p=getCurrentPages().slice(-1)[0];p.choose({currentTarget:{dataset:{id:p.data.plans[0].id}}});return true;});
    await waitFor(page,s=>!!s.selected);
    await update(6,299,'ACTIVE');
    await waitFor(page,s=>!s.selected && s.plans?.[0]?.amountCents===299);
    await shot('05-后台改价后');
    await update(7,299,'DISABLED');
    await waitFor(page,s=>!s.hasPlans);
    const visible = await app.evaluate(()=>new Promise(done=>wx.createSelectorQuery().selectAll('.plan').boundingClientRect(r=>done(r.length)).exec()));
    assert.equal(visible,0);
    await shot('06-后台下架后');
  } finally {
    await app.evaluate(()=>{for(const key of ['zs-api-base','zs-api-token-v1','zs-api-post-v1','zs-api-posts-v1']) wx.removeStorageSync(key);});
    app.disconnect();
  }
};
