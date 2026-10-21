const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const automator = require('miniprogram-automator');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'qa');
const delay = ms => new Promise(resolve => setTimeout(resolve,ms));
async function main() {
  const mp = await automator.connect({ wsEndpoint: process.env.WECHAT_AUTOMATION_ENDPOINT || 'ws://127.0.0.1:9420' });
  const errors=[], checks=[];
  mp.on('exception', error => errors.push(error));
  mp.on('console', event => { if(event.level==='error') errors.push(event); });
  const cdp = mp.CDP();
  async function nodes() {
    const tree=await cdp.sendCommand('DOM','getDocument',{depth:-1,pierce:true});
    const all=[];
    function visit(node) {
      const attrs={};
      for(let i=0;i<(node.attributes||[]).length;i+=2) attrs[node.attributes[i]]=node.attributes[i+1];
      all.push({...node,attrs});
      for(const child of node.children||[])visit(child);
      for(const child of node.shadowRoots||[])visit(child);
    }
    visit(tree.root);return all;
  }
  async function input(key,value) {
    const node=(await nodes()).find(n=>n.attrs['data-key']===key);
    assert(node,'Input exists: '+key);
    await cdp.sendCommand('MDPForm','input',{nodeId:node.nodeId,value,clear:true});
    await delay(180);
  }
  async function tapClass(className) {
    const node=(await nodes()).find(n=>(n.attrs.class||'').split(' ').includes(className));
    assert(node,'Control exists: '+className);
    await cdp.sendCommand('MDPElement','tap',{nodeId:node.nodeId});await delay(250);
  }
  function passed(name) { checks.push({name,result:'passed'}); console.log('PASS '+name); }
  async function tapSelector(selector) {
    let element;
    for(let i=0;i<35;i++){element=await(await mp.currentPage()).$(selector);if(element)break;await delay(200);}
    assert(element,'Control exists: '+selector);await element.tap();
  }
  const previous=await mp.evaluate(()=>({records:wx.getStorageSync('zhongshu-local-records'),design:wx.getStorageSync('zhongshu-saved-design')}));
  try {
    for (const [index,key] of ['home','business','engineering','management','profile'].entries()) {
      const page=await mp.switchTab('/pages/'+key+'/index');
      assert.equal(page.path,'pages/'+key+'/index');
      const state=await mp.evaluate(()=>({tab:getCurrentPages()[getCurrentPages().length-1].getTabBar().data.selected,font:getApp().globalData.fontLoaded}));
      assert.equal(state.tab,index);assert.equal(state.font,true);
      passed(key+' navigation, selected tab and embedded font');
    }
    let page=await mp.switchTab('/pages/business/index');
    const search=await page.$('.search-box input');
    await search.input('李');await delay(200);
    assert.deepEqual((await page.data()).filteredCustomers.map(c=>c.name),['李女士']);
    await search.input('找不到的客户');await delay(200);
    assert.equal((await page.data()).filteredCustomers.length,0);
    await mp.screenshot({path:path.join(out,'screenshots/search-empty.png')});
    await search.input('');await delay(200);
    assert.equal((await page.data()).filteredCustomers.length,2);
    passed('customer search, empty state and reset');

    await (await page.$('[data-action="customer"]')).tap();await delay(250);
    assert.equal((await page.data()).panel.key,'customer');
    assert.equal(await mp.evaluate(()=>getCurrentPages()[getCurrentPages().length-1].getTabBar().data.hidden),true);
    await tapClass('confirm');
    assert.equal((await page.data()).panel.key,'customer');
    passed('customer form required-field validation and tab occlusion fix');
    await input('name','测试客户');await input('phone','13800000000');await input('village','演示村');
    await mp.screenshot({path:path.join(out,'screenshots/customer-form-filled.png')});
    await tapClass('confirm');
    let data=await page.data();
    assert.equal(data.panel,null);assert.equal(data.customers.length,3);
    assert.equal(data.customers[2].name,'测试客户');
    assert.equal(await mp.evaluate(()=>getCurrentPages()[getCurrentPages().length-1].getTabBar().data.hidden),false);
    const records=await mp.evaluate(()=>wx.getStorageSync('zhongshu-local-records'));
    assert.equal(records[records.length-1].values.name,'测试客户');
    passed('native customer form input, save and local persistence');

    for (const [action, route] of [['remix','pages/creation/index'],['digital','pages/studio/index'],['video','pages/ai-video/index'],['works','pages/studio/index']]) {
      await tapSelector('[data-action="'+action+'"]');
      for(let i=0;i<40;i++){if((await mp.currentPage()).path===route)break;await delay(200);}
      assert.equal((await mp.currentPage()).path, route);
      await mp.navigateBack();
      for(let i=0;i<40;i++){if((await mp.currentPage()).path==='pages/business/index')break;await delay(200);}
      page=await mp.currentPage();
    }
    passed('business creation entries navigate to native creation pages');
    for(const action of ['plan','facade','quote','contract','delivery']) {
      await tapSelector('[data-action="'+action+'"]');await delay(150);
      assert.equal((await page.data()).panel.key,action);
      await tapClass('close');assert.equal((await page.data()).panel,null);
    }
    passed('business design and process entry panels');
    await (await page.$('.save-design')).tap();await delay(150);
    assert.equal(await mp.evaluate(()=>wx.getStorageSync('zhongshu-saved-design')),'/assets/facade.jpg');
    passed('save design');

    page=await mp.switchTab('/pages/engineering/index');
    await (await page.$('[data-action="siteConfirm"]')).tap();await delay(200);
    assert.equal((await page.data()).panel.key,'siteConfirm');await tapClass('close');
    passed('engineering project confirmation entry');
    page=await mp.switchTab('/pages/management/index');
    await (await page.$('[data-action="receipts"]')).tap();await delay(200);
    assert.equal((await page.data()).panel.items.length,3);await tapClass('close');
    passed('management pending receipts');
    page=await mp.switchTab('/pages/profile/index');
    await (await page.$('[data-action="continueCourse"]')).tap();await delay(200);
    await tapClass('confirm');assert.equal((await page.data()).learned,3);
    await mp.screenshot({path:path.join(out,'screenshots/course-progress.png')});
    passed('continue course and update learning progress');

    // Exercise the actual custom navigation button across its shadow boundary.
    const tabNode=(await nodes()).find(n=>n.nodeName==='custom-tab-bar/index');
    assert(tabNode);
    const all=await nodes();
    const businessTab=all.find(n=>n.nodeName==='button'&&n.attrs['data-index']==='1');
    assert(businessTab);
    await cdp.sendCommand('MDPElement','tap',{nodeId:businessTab.nodeId});await delay(700);
    assert.equal((await mp.currentPage()).path,'pages/business/index');
    passed('tap custom tab button changes native page');
    assert.equal(errors.length,0,'No runtime exceptions or console errors');
    passed('no captured runtime exceptions or console errors');
  } catch (error) {
    errors.push({ assertion: error.message });
    throw error;
  } finally {
    // Restore only the demo records touched by this run.
    await mp.evaluate(previous=>{
      if(previous.records) wx.setStorageSync('zhongshu-local-records',previous.records);else wx.removeStorageSync('zhongshu-local-records');
      if(previous.design) wx.setStorageSync('zhongshu-saved-design',previous.design);else wx.removeStorageSync('zhongshu-saved-design');
    },previous);
    await mp.reLaunch('/pages/home/index');
    fs.writeFileSync(path.join(out,'interaction-results.json'),JSON.stringify({result:errors.length?'failed':'passed',system:await mp.systemInfo(),checks,errors},null,2));
    await mp.disconnect();
  }
  console.log(`${checks.length} simulator checks passed.`);
}
main().catch(error=>{console.error(error);process.exitCode=1;});
