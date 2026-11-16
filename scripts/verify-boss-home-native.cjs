// Run against the isolated native project: all requests are mocked before App starts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const automator = require('miniprogram-automator');
const directory = path.resolve('docs/boss-home-20261116');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  fs.mkdirSync(path.join(directory,'screenshots'), {recursive:true});
  let mp;
  for (let attempt=0; !mp; attempt++) {
    try { mp = await automator.connect({wsEndpoint:process.env.BOSS_HOME_WS || 'ws://127.0.0.1:9961'}); }
    catch (error) { if (attempt === 9) throw error; await sleep(400); }
  }
  const errors = [], checks = [];
  mp.on('exception', error => errors.push(error));
  const until = async (check, label) => {
    for (let i=0;i<100;i++) { if (await check()) return; await sleep(200); }
    throw new Error('Timed out: ' + label);
  };
  const data = () => mp.evaluate(() => getCurrentPages().slice(-1)[0]?.data);
  const rects = selector => mp.evaluate(selector => new Promise(resolve => wx.createSelectorQuery().selectAll(selector).boundingClientRect().exec(rows => resolve(rows[0] || []))),selector);
  const tap = async selector => {
    mp.pageMap?.clear();
    const control = await (await mp.currentPage()).$(selector);
    assert(control, 'native control exists: ' + selector);
    await control.tap(); await sleep(250);
  };
  const shot = async name => {
    await mp.evaluate(() => { wx.hideToast(); return true; }); await sleep(250);
    await mp.screenshot({path:path.join(directory,'screenshots',name + '.png')});
  };
  try {
    await until(() => mp.evaluate(() => typeof getApp === 'function' && !!getApp()), 'app startup');
    assert.equal(await mp.evaluate(() => wx.getStorageSync('zs-api-token-v1')), 'isolated-home-preview', 'native checks require the isolated mock project');
    await mp.evaluate(() => { setTimeout(()=>wx.reLaunch({url:'/pages/admin/home/index'}),50); return true; });
    await until(async () => { const d=await data(); return d?.desk?.home?.business?.available && d?.desk?.home?.engineering?.available; }, 'real home render');
    await sleep(500);
    assert.equal((await data()).homeFinanceTab,'operating');
    assert.equal((await rects('.home-six-item')).length,4);
    assert.equal((await rects('.home-ledger-stat')).length,0);
    assert.equal((await rects('.home-detail')).length,0);
    const metrics = await rects('.home-metric');
    assert.equal(metrics.length,8);
    for (const row of [metrics.slice(0,4),metrics.slice(4)]) {
      assert(row.every(rect => Math.abs(rect.top-row[0].top)<1),'four cards in one row');
      assert(row.every((rect,i) => !i || rect.left>=row[i-1].right),'cards do not overlap');
    }
    await shot('01-default-home'); checks.push('默认经营Tab；业务工程均为一排四卡、默认收起');

    await tap('.home-finance-tab[data-tab="ledger"]');
    assert.equal((await data()).homeFinanceTab,'ledger');
    assert.equal((await rects('.home-six-item')).length,0);
    const ledger = await rects('.home-ledger-stat'); assert.equal(ledger.length,4);
    assert(ledger.every(rect=>Math.abs(rect.top-ledger[0].top)<1));
    await shot('02-ledger-tab'); checks.push('账本切换替换经营内容，已收已付待收待付一排显示');

    await tap('.home-finance-tab[data-tab="operating"]');
    await tap('.home-metric[data-module="business"][data-key="due"]');
    assert.equal((await data()).expandedBusiness,'due');
    assert.equal((await rects('.home-detail')).length,1);
    await tap('.home-metric[data-module="engineering"][data-key="在建"]');
    assert.equal((await data()).expandedEngineering,'在建');
    assert.equal((await rects('.home-detail')).length,2);
    await shot('03-inline-expanded'); checks.push('点击业务工程指标，原生模板内向下展开，空态正常');
    await mp.evaluate(() => { wx.pageScrollTo({scrollTop:9999,duration:0}); return true; }); await sleep(300);
    const expanded = await rects('.home-detail-empty');
    const height = await mp.evaluate(() => wx.getWindowInfo().windowHeight);
    assert(expanded.at(-1).bottom < height - 60, 'expanded engineering content scrolls clear of fixed navigation');
    await shot('04-expanded-engineering'); checks.push('展开内容可滚动完整查看，底部导航不遮挡内容');

    await tap('.home-collapse[data-module="business"]');
    assert.equal((await data()).expandedBusiness,'');
    assert.equal((await data()).expandedEngineering,'在建');
    await tap('.home-metric[data-module="engineering"][data-key="在建"]');
    assert.equal((await rects('.home-detail')).length,0);
    await mp.evaluate(() => { wx.pageScrollTo({scrollTop:0,duration:0}); return true; });
    checks.push('独立收起及重复点击指标收起均正常');

    const geometry = {metrics,ledger,sections:await rects('.owner-card')};
    assert.equal(errors.length,0,JSON.stringify(errors));
    fs.writeFileSync(path.join(directory,'native-results.json'),JSON.stringify({result:'passed',mode:'isolated native WeChat renderer; all requests mocked before startup',checks,geometry,exceptions:errors},null,2));
    console.log(JSON.stringify({result:'passed',checks},null,2));
  } finally { await mp.disconnect(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
