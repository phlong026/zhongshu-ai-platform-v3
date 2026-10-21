const assert=require('assert/strict');
const fs=require('fs');const path=require('path');const automator=require('miniprogram-automator');
const root=path.resolve(__dirname,'..');const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function main(){
  const mp=await automator.connect({wsEndpoint:process.env.WECHAT_AUTOMATION_ENDPOINT||'ws://127.0.0.1:9420'});const checks=[],errors=[],mocks=new Set();
  const metadataOnly=process.argv.includes('--metadata-only');
  if(metadataOnly){const prior=JSON.parse(fs.readFileSync(path.join(root,'qa/creation-supplemental-results.json'),'utf8'));assert.equal(prior.checks.length,5);assert(prior.checks.every(c=>c.result==='passed'));checks.push(...prior.checks);console.log('Retained 5 unaffected navigation/form checks; retrying metadata checks.');}
  mp.on('exception',e=>errors.push(e));mp.on('console',e=>{if(e.level==='error')errors.push(e)});
  const keys=['zhongshu-studio','zhongshu-video-draft','zhongshu-creation-draft'];
  const before=await mp.evaluate(keys=>keys.map(key=>({key,value:wx.getStorageSync(key)})),keys);
  const cdp=mp.CDP();
  let phase='navigation and form checks';
  async function nodes(){const doc=await cdp.sendCommand('DOM','getDocument',{depth:-1,pierce:true});const list=[];function visit(n,scoped){const attrs={};for(let i=0;i<(n.attributes||[]).length;i+=2)attrs[n.attributes[i]]=n.attributes[i+1];scoped=scoped||attrs.id==='creation-workspace-nav';list.push({...n,attrs,scoped});for(const child of [...(n.children||[]),...(n.shadowRoots||[])])visit(child,scoped)}visit(doc.root,false);return list}
  async function tap(selector){let el;for(let i=0;i<35;i++){el=await(await mp.currentPage()).$(selector);if(el)break;await delay(200)}assert(el,selector);await el.tap();await delay(400)}
  async function data(key){return(await mp.currentPage()).data(key)}
  async function invoke(method,...args){return mp.evaluate(({method,args})=>getCurrentPages().slice(-1)[0][method](...args),{method,args})}
  async function mock(method,result){await mp.mockWxMethod(method,result);mocks.add(method)}
  async function waitRoute(route){for(let i=0;i<40;i++){if((await mp.currentPage()).path===route)return;await delay(200)}assert.equal((await mp.currentPage()).path,route)}
  async function until(predicate,label){for(let i=0;i<50;i++){if(await predicate())return;await delay(200)}throw new Error('Timed out: '+label)}
  function pass(name){checks.push({name,result:'passed'});console.log('PASS '+name)}
  try{
    await mp.reLaunch('/pages/business/index');
    await mp.evaluate(keys=>keys.forEach(key=>wx.removeStorageSync(key)),keys);
    if(!metadataOnly){
    for(const [index,key] of ['home','business','engineering','management','profile'].entries()){
      await mp.reLaunch('/pages/creation/index');await delay(600);
      let controls=[];for(let i=0;i<30;i++){controls=(await nodes()).filter(n=>n.scoped&&n.nodeName==='button'&&(n.attrs.class||'').split(' ').includes('tab'));if(controls.length===5)break;await delay(200)}
      assert.equal(controls.length,5);assert(controls.find(n=>n.attrs['data-index']==='1').attrs.class.includes('active'));
      const button=controls.find(n=>n.attrs['data-index']===String(index));await cdp.sendCommand('MDPElement','tap',{nodeId:button.nodeId});await waitRoute('pages/'+key+'/index');
    }
    pass('creation bottom navigation: five actual buttons, business highlight and return to business');
    await mp.reLaunch('/pages/creation/index');await tap('.manual-link');
    assert.equal((await nodes()).filter(n=>n.scoped&&(n.attrs.class||'').split(' ').includes('tabbar')).length,0);
    await tap('.creation-sheet-close');assert.equal((await nodes()).filter(n=>n.scoped&&(n.attrs.class||'').split(' ').includes('tabbar')).length,1);
    pass('manual editor hides navigation and closing restores it');
    await mp.reLaunch('/pages/ai-video/index');await tap('[data-mode="frames"]');await mock('showActionSheet',{tapIndex:1});
    await tap('[data-target="firstFrame"]');await tap('.creation-sheet [data-id="house"]');assert.equal(await data('firstFrame'),'/assets/creation-house.jpg');
    await mock('showActionSheet',{tapIndex:2});await tap('[data-target="firstFrame"]');assert.equal(await data('firstFrame'),'');
    pass('first-frame asset selection and removal');
    await mp.reLaunch('/pages/studio/index?view=result&task=task-complete');await tap('.result-actions .studio-primary');await until(async()=>await data('view')==='publish','saved work publication settings');await tap('.publish-row');
    await invoke('scheduleDate',{detail:{value:'2000-01-01'}});await invoke('scheduleTime',{detail:{value:'10:30'}});await tap('.sheet-confirm');assert.equal(await data('sheet'),'schedule');
    await invoke('scheduleDate',{detail:{value:'2099-10-17'}});await tap('.sheet-confirm');assert.equal(await data('scheduled'),'2099-10-17 10:30');
    await tap('.publish-row');await tap('.creation-sheet-row');assert.equal(await data('scheduled'),'');
    pass('publication scheduling rejects the past, accepts a future date, and restores immediate mode');
    await invoke('persistEditor');const count=await mp.evaluate(()=>wx.getStorageSync('zhongshu-studio').records.length);
    await mock('showModal',{confirm:false,cancel:true});await tap('.publish-submit');assert.equal(await mp.evaluate(()=>wx.getStorageSync('zhongshu-studio').records.length),count);
    pass('cancelled publication creates no record');
    }
    phase='open AI metadata case';await mp.reLaunch('/pages/ai-video/index');await invoke('inputDescription',{detail:{value:'庭院镜头测试'}});
    await invoke('changeParameter',{currentTarget:{dataset:{index:0}},detail:{value:1}});await mock('showModal',{confirm:true,cancel:false});await tap('.generate-button');await waitRoute('pages/studio/index');
    const task=await mp.evaluate(()=>wx.getStorageSync('zhongshu-studio').tasks[0]);assert.equal(task.configuration.parameters[0].value,'1080P');
    phase='complete AI task';await tap('.task-action[data-id="'+task.id+'"]');await until(async()=>await mp.evaluate(id=>wx.getStorageSync('zhongshu-studio').tasks.find(t=>t.id===id).status==='completed',task.id),'task completion');
    phase='open completed AI task';await tap('.task-action[data-id="'+task.id+'"]');await until(async()=>await data('view')==='result','result page');
    assert.deepEqual(await data('resultInfo'),[{label:'分辨率',value:'1080P'},{label:'画面比例',value:'9:16'}]);
    phase='save AI result';await invoke('saveResult');await until(async()=>await mp.evaluate(id=>wx.getStorageSync('zhongshu-studio').works.some(w=>w.taskId===id),task.id),'saved work');
    phase='open updated creation center';await mp.reLaunch('/pages/creation/index');await until(async()=>(await data('recent')).some(item=>item.taskId===task.id),'recent saved task');const recent=(await data('recent')).find(item=>item.taskId===task.id);
    phase='open recent result';await tap('.recent-action[data-id="'+recent.id+'"]');await until(async()=>await data('view')==='result','recent result');assert.equal(await data('taskId'),task.id);
    pass('AI parameters, saved work and recent-creation entry refer to the same task');
    assert.equal(errors.length,0);pass('no captured exceptions or error console messages in supplemental checks');
  }catch(error){errors.push({assertion:error.message,phase});console.error('Failed phase: '+phase);throw error}
  finally{
    for(const method of mocks)await mp.restoreWxMethod(method);
    await mp.reLaunch('/pages/creation/index');
    await mp.evaluate(before=>{for(const {key,value}of before){if(value)wx.setStorageSync(key,value);else wx.removeStorageSync(key)}},before);
    fs.writeFileSync(path.join(root,'qa/creation-supplemental-results.json'),JSON.stringify({result:errors.length?'failed':'passed',retestedScope:metadataOnly?'Metadata case; retained five unaffected passed navigation/form cases':'All supplemental cases',checks,errors,system:await mp.systemInfo()},null,2));
    await mp.disconnect();
  }
  console.log(checks.length+' supplemental checks passed.');
}
main().catch(error=>{console.error(error);process.exitCode=1});
