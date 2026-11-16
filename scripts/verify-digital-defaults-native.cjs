const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),automator=require('miniprogram-automator');
const output=path.resolve(__dirname,'../docs/business-followup-20261116');fs.mkdirSync(output,{recursive:true});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 const mp=await automator.connect({wsEndpoint:process.env.CUSTOMER_FORM_WS||'ws://127.0.0.1:9976'});
 let originalPosts;const checks=[],errors=[];mp.on('exception',error=>errors.push(error));
 const data=()=>mp.evaluate(()=>getCurrentPages().slice(-1)[0].data);
 const until=async(check)=>{for(let i=0;i<80;i++){if(await check())return;await sleep(150);}throw Error('Default selection native timeout');};
 const call=async(name,arg)=>{await mp.evaluate((name,arg)=>getCurrentPages().slice(-1)[0][name](arg),name,arg);await sleep(150);};
 const route=async url=>{await mp.callWxMethod('reLaunch',{url});await sleep(500);};
 try{
  assert.equal(await mp.evaluate(()=>wx.getStorageSync('zs-api-token-v1')),'isolated-customer-registration');
  originalPosts=await mp.evaluate(()=>wx.getStorageSync('zs-api-posts-v1'));
  await mp.evaluate(()=>{
   const posts=wx.getStorageSync('zs-api-posts-v1');posts[0].roleCode='sales-lead';wx.setStorageSync('zs-api-posts-v1',posts);
   const fixture=getApp().globalData.fixture;
   fixture.personas=[{id:'21',name:'张工',enabled:true}];
   fixture.clones=[{id:'31',personaId:'21',kind:'AVATAR',title:'室内口播分身',ready:true,status:'READY',isDefault:true},
    {id:'33',personaId:'21',kind:'AVATAR',title:'工地口播分身',ready:true,status:'READY',isDefault:false},
    {id:'32',personaId:'21',kind:'VOICE',title:'讲解声音',ready:true,status:'READY',isDefault:true},
    {id:'34',personaId:'21',kind:'VOICE',title:'亲切声音',ready:true,status:'READY',isDefault:false}];
  });
  await mp.evaluate(()=>{getApp().globalData.fixture.requestBeforeDefaultTest=wx.request;wx.request=function(options){
   const state=getApp().globalData.fixture,route=String(options.url).split('/admin-api')[1]?.split('?')[0]||'',body=options.data||{};
   let data=[];
   if(route==='/franchise/content/personas')data=state.personas;
   else if(route==='/franchise/content/personas/21/clones')data=state.clones;
   else if(/\/franchise\/content\/clones\/\d+\/default$/.test(route)){
    const id=route.split('/').slice(-2)[0],row=state.clones.find(row=>row.id===id);
    state.clones.filter(item=>item.kind===row.kind).forEach(item=>{item.isDefault=item.id===id;});data=row;
   }else if(route==='/franchise/content/tasks'&&options.method==='POST'){state.submittedDefaultPayload=body;data={taskId:'901'};}
   else if(route==='/franchise/content/tasks/901')data={id:'901',kind:'digital',status:'SUCCEEDED',resultUrl:'https://example.test/not-played.mp4'};
   else if(route==='/franchise/content/tasks/901/save-work')data={workId:'902'};
   setTimeout(()=>options.success&&options.success({statusCode:200,data:{code:0,data},header:{}}),15);return{abort(){}};
  };});
  await route('/pages/studio/characters/index');await until(async()=>(await data()).rows.length===1);
  await call('detail',{currentTarget:{dataset:{id:'21'}}});await call('resources');
  await until(async()=>(await data()).cloneRows.length===4);
  for(const id of ['33','34']){
   const button=await (await mp.currentPage()).$('[data-id="'+id+'"].default-action');assert(button);await button.tap();
   await until(async()=>!(await data()).cloneBusy&&(await data()).cloneRows.find(row=>row.id===id)?.isDefault);
  }
  assert.equal((await data()).cloneRows.length,4);assert.equal((await data()).cloneRows.filter(row=>row.isDefault).length,2);
  await sleep(250);assert.equal((await (await mp.currentPage()).$$('.empty-card')).length,0);
  await mp.screenshot({path:path.join(output,'05-multiple-resources-defaults.png')});checks.push('多个分身和声音并存；分别设置默认，原资源保留');
  await route('/pages/studio/index?view=digital&persona=21');await until(async()=>!(await data()).digitalLoading&&(await data()).avatarCloneId==='33');
  assert.equal((await data()).voiceCloneId,'34');assert.equal((await data()).actorIsDefault,true);assert.equal((await data()).voiceIsDefault,true);
  await call('inputDigitalScript',{detail:{value:'这是用于验证默认分身和声音的口播稿。'}});
  await mp.screenshot({path:path.join(output,'06-digital-auto-defaults.png')});
  await call('generateDigitalOnline');
  await until(()=>mp.evaluate(()=>!!getApp().globalData.fixture.submittedDefaultPayload));
  const payload=await mp.evaluate(()=>getApp().globalData.fixture.submittedDefaultPayload);
  assert.equal(payload.avatarCloneId,'33');assert.equal(payload.voiceCloneId,'34');checks.push('生成页自动带出默认资源，提交使用默认分身和声音的准确编号');
  assert.equal(errors.length,0,JSON.stringify(errors));
  fs.writeFileSync(path.join(output,'digital-defaults-native-results.json'),JSON.stringify({passed:true,checks,errors,fixture:true,paidGeneration:false},null,2));console.log(JSON.stringify({passed:true,checks}));
 }finally{
  await mp.evaluate(()=>{const state=getApp().globalData.fixture;if(state.requestBeforeDefaultTest){wx.request=state.requestBeforeDefaultTest;delete state.requestBeforeDefaultTest;}});
  if(originalPosts)await mp.evaluate(posts=>{wx.setStorageSync('zs-api-posts-v1',posts);const page=getCurrentPages().slice(-1)[0];if(page?.actorScope)wx.removeStorageSync(page.actorScope);},originalPosts);
  await mp.disconnect();
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
