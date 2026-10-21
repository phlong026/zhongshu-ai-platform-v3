const fs=require('fs'),path=require('path'),auto=require('miniprogram-automator');
const out=path.resolve('qa/engineering-redesign-20261019/audit');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9845'}),results=[],errors=[];
 const previous=await mp.evaluate(()=>({rows:wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]),route:getCurrentPages().at(-1)?.route}));
 mp.on('exception',e=>errors.push(e));
 async function capture(page,role){
  await mp.callWxMethod('reLaunch',{url:'/pages/'+page+'/index'});await pause(1300);
  await mp.pageScrollTo(0);await pause(250);
  const current=await mp.currentPage();
  if(current.path!=='pages/'+page+'/index')throw Error('Wrong page '+current.path);
  const state=await mp.evaluate(()=>{const d=getCurrentPages().at(-1).data;return{guest:d.guest,role:d.roleLabel,title:d.deskTitle,projectCount:d.projectCount,activeCount:d.activeCount,todoCount:d.todoCount,tools:d.tools,todoText:d.todoText,canWork:d.canWork};});
  const name=String(results.length+1).padStart(2,'0')+'-'+page+'-'+role;
  await mp.screenshot({path:path.join(out,name+'-top.png')});
  await mp.pageScrollTo(100000);await pause(250);
  await mp.screenshot({path:path.join(out,name+'-bottom.png')});
  results.push({page,role,name,state});console.log('CAPTURE '+name);
 }
 try{
  await mp.evaluate(()=>{const a=wx.getStorageSync('zhongshu-account-v1');if(a?.session){a.session.loggedIn=false;wx.setStorageSync('zhongshu-account-v1',a);}});
  for(const page of ['engineering','business','management','home','profile'])await capture(page,'guest');
  await mp.callWxMethod('reLaunch',{url:'/pages/account/index?view=login'});await pause(800);
  await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({selectedDemoUser:'manager-li'});setTimeout(()=>p.loginDemo(),80);return true;});await pause(1800);
  await capture('engineering','engineer');
  fs.writeFileSync(path.join(out,'capture.json'),JSON.stringify({capturedAt:new Date().toISOString(),source:'current native code snapshot',system:await mp.systemInfo(),results,errors},null,2));
 }finally{
  await mp.evaluate(previous=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of previous.rows)wx.setStorageSync(k,v);return true;},previous);
  await mp.callWxMethod('reLaunch',{url:'/pages/engineering/index'});await mp.disconnect();
 }
})().catch(e=>{console.error(e);process.exit(1)});
