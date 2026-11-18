const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),a=require('miniprogram-automator');
const phase=process.argv[2],sleep=ms=>new Promise(r=>setTimeout(r,ms)),timer=setTimeout(()=>process.exit(2),45000);
(async()=>{
 const mp=await a.connect({wsEndpoint:'ws://127.0.0.1:9989'});
 assert.ok(await mp.evaluate(()=>!!globalThis.__creationFixture&&wx.getStorageSync('zs-api-token-v1')==='isolated-native-test'),'isolated fixture required');
 if(phase==='resume')assert.equal(await mp.evaluate(()=>!!globalThis.__restartPhase),false,'restart must discard the JavaScript runtime');
 await sleep(2500); // Let the isolated cold-start authentication redirect settle.
 await mp.evaluate(phase=>{
  const key='isolated-extract-restart-review',link='https://v.douyin.com/isolated-restart-review';
  if(phase==='create'){
   for(const k of wx.getStorageInfoSync().keys){if(k.startsWith('zhongshu-creation-extract-v1:')&&k.includes(':993:992:991:')){const r=wx.getStorageSync(k);if(r.url===link)wx.removeStorageSync(k);}}
   wx.setStorageSync(key,{parses:0,transcriptions:0,charges:0,retries:0,ready:false});
  }
  globalThis.__restartPhase=phase;
  const post=globalThis.__creationFixture.post;post.roleCode='boss';wx.setStorageSync('zs-api-posts-v1',[post]);
  const request=wx.request;wx.request=o=>{
   const counts=wx.getStorageSync(key),route=o.url.split('/admin-api')[1]?.split('?')[0];let data;
   if(route==='/franchise/content/links/parse'){counts.parses++;data={taskId:'881'};}
   else if(route==='/franchise/content/scripts/extract'){counts.transcriptions++;counts.charges++;data={taskId:'882'};}
   else if(route==='/franchise/content/tasks/881')data={id:'881',kind:'link_parse',status:'SUCCEEDED',result:{audioUrl:'https://media.example/native-restart'}};
   else if(route==='/franchise/content/tasks/882/retry-save'){counts.retries++;counts.ready=true;data={id:'882',status:'ARCHIVING'};}
   else if(route==='/franchise/content/tasks/882')data={id:'882',kind:'asr',generationStatus:'SUCCEEDED',status:counts.ready?'SUCCEEDED':'FAILED',resultState:counts.ready?'READY':'FAILED',saveRetryable:!counts.ready,result:{text:'重启后恢复原任务，只保存一次。'}};
   else return request(o);
   wx.setStorageSync(key,counts);setTimeout(()=>o.success({statusCode:200,data:{code:0,data}}),10);return{abort(){}};
  };
  setTimeout(()=>wx.reLaunch({url:'/pages/creation/index'}),50);
 },phase);
 for(let i=0;i<24;i++){await sleep(500);if(await mp.evaluate(()=>getCurrentPages().slice(-1)[0]?.route==='pages/creation/index'&&typeof getCurrentPages().slice(-1)[0]?.inputLink==='function'))break;}
 assert.ok(await mp.evaluate(()=>typeof getCurrentPages().slice(-1)[0]?.inputLink==='function'),'creation page must finish loading');
 await mp.evaluate(async()=>{const p=getCurrentPages().slice(-1)[0];p.inputLink({detail:{value:'https://v.douyin.com/isolated-restart-review'}});await p.extractOnline();return true;});
 await sleep(800);
 const counts=await mp.evaluate(()=>wx.getStorageSync('isolated-extract-restart-review'));
 assert.equal(counts.transcriptions,1);assert.equal(counts.charges,1);assert.equal(counts.parses,1);assert.equal(counts.retries,phase==='resume'?1:0);
 if(phase==='create')assert.match(await mp.evaluate(()=>getCurrentPages().slice(-1)[0].data.extractError),/保存失败/);
 else {
  for(let i=0;i<24;i++){await sleep(500);if(await mp.evaluate(()=>getCurrentPages().slice(-1)[0]?.route==='pages/studio/index'))break;}
  assert.equal(await mp.evaluate(()=>getCurrentPages().slice(-1)[0]?.route),'pages/studio/index');
  await mp.screenshot({path:path.resolve('docs/creation-repair-20261117/24-重启后恢复原转写任务.png')});
  fs.writeFileSync('docs/creation-repair-20261117/extract-restart-native-result.json',JSON.stringify({passed:true,runtimeRestarted:true,...counts,scope:'微信开发者工具重启运行时、保留本地存储；隔离接口和积分计数，未调用供应商'},null,2));
 }
 await mp.disconnect();clearTimeout(timer);console.log(JSON.stringify({phase,...counts}));
})().catch(e=>{clearTimeout(timer);console.error(e.stack);process.exit(1);});
