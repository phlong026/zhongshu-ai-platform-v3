const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const automator = require('miniprogram-automator');
const root = path.resolve(__dirname, '..');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const mp = await automator.connect({ wsEndpoint: process.env.WECHAT_AUTOMATION_ENDPOINT||'ws://127.0.0.1:9720' });
  const errors = [], checks = [];
  mp.on('exception', e => errors.push(e));
  mp.on('console', e => { if (e.level === 'error') errors.push(e); });
  const cdp = mp.CDP();
  async function ready(fn,label){for(let i=0;i<35;i++){const value=await fn();if(value)return value;await delay(160);}throw Error('Not ready: '+label);}
  async function relaunch(url){console.log('OPEN '+url);const target=new URL(url,'http://local');for(let attempt=0;attempt<2;attempt++){try{await mp.callWxMethod('reLaunch',{url});}catch(e){if(!/timeout waiting/.test(e.message))throw e;console.log('Navigation RPC timed out; checking the destination.');}await delay(800);try{await ready(async()=>{const page=await mp.currentPage();return page.path===target.pathname.slice(1)&&(!target.searchParams.get('view')||await page.data('view')===target.searchParams.get('view'));},'route '+url);await ready(async()=>await(await mp.currentPage()).$('.creation-page'),'native render '+url);await delay(400);return await mp.currentPage();}catch(e){if(attempt)throw e;console.log('Retry read-only navigation after a pending transition');}}}
  async function nodes() {
    const doc = await cdp.sendCommand('DOM', 'getDocument', { depth: -1, pierce: true }); const all = [];
    function visit(node) { const attrs = {}; for (let i=0;i<(node.attributes||[]).length;i+=2) attrs[node.attributes[i]]=node.attributes[i+1]; all.push({ ...node, attrs }); for(const child of [...(node.children||[]),...(node.shadowRoots||[])]) visit(child); }
    visit(doc.root); return all;
  }
  async function input(selector, value) {
    const page = await mp.currentPage(); const element = await page.$('.' + selector);
    assert(element, 'Native input exists: ' + selector);
    await element.input(value); await delay(160);
  }
  async function tap(selector) {
    let el;
    for(let i=0;i<35;i++){ const page=await mp.currentPage();el=await page.$(selector);if(el)break;await delay(200); }
    assert(el,'Control exists: '+selector);console.log('TAP '+selector);const oldPage=await mp.currentPage(),oldView=await oldPage.data('view');try{await el.tap();}catch(e){if(!/timeout waiting/.test(e.message)||((await mp.currentPage()).path===oldPage.path&&await(await mp.currentPage()).data('view')===oldView))throw e;console.log('Observed the new route without replaying the action.');}await delay(500);
  }
  async function open(view) { const page=await relaunch('/pages/studio/index?view='+view);await delay(300);return page; }
  async function state() { return await mp.evaluate(()=>wx.getStorageSync('zhongshu-studio')); }
  async function data(key) { return (await mp.currentPage()).data(key); }
  async function expectView(view) {
    for(let i=0;i<30;i++){ if(await mp.evaluate(()=>getCurrentPages().slice(-1)[0].data.view)===view) { await delay(350); return; } await delay(300); }
    assert.equal(await data('view'),view);
  }
  async function invoke(method,...args) { return mp.evaluate(({method,args})=>getCurrentPages().slice(-1)[0][method](...args),{method,args}); }
  async function modal(confirm) { await mp.mockWxMethod('showModal', { confirm, cancel: !confirm }); }
  function pass(name) { checks.push({ name, result:'passed' }); console.log('PASS '+name); }
  const keys=['zhongshu-studio','zhongshu-video-draft','zhongshu-creation-draft','zhongshu-account-v1','zhongshu-management-v1'];
  const previous=await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(key=>({key,value:wx.getStorageSync(key)})));
  try {
    await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).forEach(k=>wx.removeStorageSync(k)));const testAccount=require('../miniprogram/utils/account').seed();testAccount.session.loggedIn=true;await mp.evaluate(x=>{wx.setStorageSync('zhongshu-account-v1',x.a);wx.setStorageSync('zhongshu-management-v1',x.m);},{a:testAccount,m:require('../miniprogram/pages/admin/model').seed()});
    await modal(false);
    let page=await relaunch('/pages/creation/index');await delay(300);
    await tap('.extract-button');assert.equal((await mp.currentPage()).path,'pages/creation/index');
    pass('empty share link stays in creation center');
    await mp.mockWxMethod('getClipboardData',{data:'https://example.com/demo-video'});
    await tap('.paste-button');assert.equal(await data('link'),'https://example.com/demo-video');
    await modal(true);await tap('.extract-button');await expectView('remix');
    pass('paste + explicit demo extraction opens remix');
    await tap('.rewrite-tabs [data-index="1"]');assert.equal(await data('rewriteMode'),1);
    await input('custom-ip','乡墅设计师');assert.equal(await data('customIp'),'乡墅设计师');
    await tap('.soft-icon-button');await input('creation-sheet-input','测试口播文案：采光与生活动线。');await tap('.sheet-confirm');
    assert.equal((await state()).script,'测试口播文案：采光与生活动线。');
    pass('custom rewrite settings and native script editing persist');
    await tap('.studio-bottom-pair .studio-primary');await expectView('digital');
    assert.equal(await data('script'),'测试口播文案：采光与生活动线。');
    await tap('.change-actor');await tap('.creation-sheet-row');assert.equal(await data('actor'),'李顾问');
    await tap('.voice-change');await tap('.creation-sheet-row');assert.equal(await data('voice'),'李顾问原声');
    pass('remix carries script into presenter and voice selection');
    await tap('.speech-card .soft-text-button');await input('creation-sheet-input','往返编辑后保留的口播文案');await tap('.sheet-confirm');
    await mp.navigateBack();await expectView('remix');assert.equal(await data('script'),'往返编辑后保留的口播文案');
    await tap('.studio-bottom-pair .studio-primary');await expectView('digital');
    pass('returning from presenter keeps the latest script in remix');
    await modal(false);await tap('.digital-generate');assert.equal((await state()).tasks.length,3);
    await modal(true);await tap('.digital-generate');await expectView('tasks');
    let tasks=(await state()).tasks;const id=tasks[0].id;assert.equal(tasks.length,4);assert.equal(tasks[0].status,'generating');
    pass('cancel generation creates no task; demo confirmation creates task');
    await tap('.task-action[data-id="'+id+'"]');
    tasks=(await state()).tasks;assert.equal(tasks[0].status,'completed');
    await tap('.task-action');await expectView('result');assert.equal(await data('taskId'),id);
    pass('task progress moves explicitly to completion then result');
    await tap('.result-actions .studio-outline');assert.equal((await state()).works.length,1);
    await tap('.result-actions .studio-outline');assert.equal((await state()).works.length,1);
    assert.equal(await data('saved'),true);
    pass('save result is persistent and idempotent');
    await tap('.result-actions .studio-primary');await expectView('publish');
    assert((await data('workId')).startsWith('work-'));
    await tap('[data-index="1"].account-option');assert.equal(await data('selectedAccount'),1);
    await tap('.publish-saved-line .soft-text-button');await tap('[data-path="/assets/creation-courtyard.jpg"]');assert.equal(await data('cover'),'/assets/creation-courtyard.jpg');
    await tap('.publish-title-input button');await tap('.publish-submit');await expectView('publish');
    await input('publish-title-text','测试乡墅作品');
    pass('publication auto-save, account, cover and required title checks');
    await tap('.publish-submit');await expectView('records');
    let records=(await state()).records;const record=records[0];assert.equal(record.platform,'channels');assert.equal(record.status,'publishing');assert.equal(record.title,'测试乡墅作品');
    await tap('[data-id="'+record.id+'"]');await expectView('detail');
    await tap('.detail-primary');assert.equal((await state()).records[0].status,'published');
    await tap('.detail-return');await expectView('records');
    await mp.screenshot({path:path.join(root,'qa/role-complete-20261018/native-creation-receipt.png')});pass('demo submit → record → progress → receipt → records closes publication loop');
    await open('works');assert.equal((await data('works')).length,1);await tap('.saved-work');await expectView('result');
    await tap('.result-actions .studio-primary');await expectView('publish');await tap('.publish-submit');await expectView('records');assert.equal((await state()).records.length,5);
    pass('saved works reopen results; duplicate publication is blocked');
    await open('tasks');await tap('[data-index="3"]');assert.equal((await data('tasks')).length,1);await tap('.task-action');
    assert.equal((await state()).tasks.find(task=>task.id==='task-retry').saved,true);
    pass('failed save retries locally without regenerating video');
    await open('records');await tap('[data-index="3"]');assert.equal((await data('records')).length,2);
    await tap('[data-id="record-verify"]');await tap('.detail-primary');assert.equal((await state()).records.find(record=>record.id==='record-verify').status,'published');
    pass('exception filter and pending verification have a result path');
    await open('detail&record=record-error');await tap('.detail-primary');await expectView('publish');
    pass('expired account failure returns to publication settings for recovery');
    page=await relaunch('/pages/ai-video/index');await delay(300);
    await tap('.generate-button');assert.equal((await mp.currentPage()).path,'pages/ai-video/index');
    await input('description-textarea','乡墅庭院，镜头缓慢推进。');
    await tap('[data-mode="reference"]');await tap('.generate-button');assert.equal((await mp.currentPage()).path,'pages/ai-video/index');
    await tap('.library-button');await tap('.creation-sheet [data-id="house"]');assert.equal((await data('references')).length,1);
    await tap('.library-button');await tap('.creation-sheet [data-id="house"]');assert.equal((await data('references')).length,1);
    pass('video prompt validation, required references and duplicate asset prevention');
    await invoke('addReferences',['creation-courtyard.jpg','creation-ai-cover.jpg','facade.jpg','floor-plan.png','home-video.jpg','home-design.jpg','course.jpg','business-video.jpg'].map((file,i)=>({id:'test-'+i,path:'/assets/'+file,type:'image'})));
    assert.equal((await data('references')).length,8);
    await invoke('addReferences',Array.from({length:5},(_,i)=>({id:'v-'+i,path:'video-test-'+i,type:'video'})));
    assert.equal((await data('references')).length,11);
    await invoke('addReferences',Array.from({length:5},(_,i)=>({id:'a-'+i,path:'audio-test-'+i,type:'audio'})));
    assert.equal((await data('references')).length,12);
    pass('media capacity checks: images 8, videos 3, total 12');
    await invoke('changeParameter',{currentTarget:{dataset:{index:0}},detail:{value:1}});
    assert.equal((await data('parameters'))[0].value,1);
    await tap('.generate-button');await expectView('tasks');
    await relaunch('/pages/ai-video/index');assert.equal(await data('mode'),'reference');assert.equal(await data('description'),'乡墅庭院，镜头缓慢推进。');
    pass('AI video parameters and draft survive navigation; task shares same workflow');
    await relaunch('/pages/creation/index');await tap('.manual-link');await input('creation-sheet-input','手动创作的乡墅文案');await tap('.creation-sheet-button');
    await expectView('remix');assert.equal(await data('original'),'手动创作的乡墅文案');
    pass('manual copy path carries local content into remix');
    assert.equal(errors.length,0,'No runtime exceptions or error-level console messages');pass('no captured runtime exceptions or console errors');
  } catch(error) { errors.push({assertion:error.message});try{await mp.screenshot({path:path.join(root,'qa/role-complete-20261018/native-creation-failed.png')});fs.writeFileSync(path.join(root,'qa/role-complete-20261018/native-creation-failed-state.json'),JSON.stringify({page:(await mp.currentPage()).path,data:await(await mp.currentPage()).data()},null,2));}catch(_){}throw error; }
  finally {
    for(const method of ['showModal','getClipboardData'])await mp.restoreWxMethod(method);
    // Leave any editor first so onUnload cannot overwrite restored data.
    await relaunch('/pages/creation/index');
    await mp.evaluate(previous=>{wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).forEach(k=>wx.removeStorageSync(k));for(const {key,value} of previous){if(value)wx.setStorageSync(key,value);else wx.removeStorageSync(key);}},previous);
    const restoredRows=await mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(key=>({key,value:wx.getStorageSync(key)})));const normalizeRows=rows=>JSON.stringify(rows.sort((a,b)=>a.key.localeCompare(b.key)));fs.writeFileSync(path.join(root,'qa/role-complete-20261018/native-creation-restored.json'),JSON.stringify({restored:normalizeRows(previous)===normalizeRows(restoredRows)}));
    fs.writeFileSync(path.join(root,'qa/role-complete-20261018/native-creation-results.json'),JSON.stringify({result:errors.length?'failed':'passed',system:await mp.systemInfo(),checks,errors},null,2));
    await mp.callWxMethod('reLaunch',{url:'/pages/home/index'});await mp.disconnect();
  }
  console.log(checks.length+' creation workflow checks passed.');
}
main().catch(error=>{console.error(error);process.exitCode=1;});
