const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
global.wx={getStorageSync:()=>undefined};const A=require('../miniprogram/utils/account');
const out=path.resolve('qa/workbench-followups-20261019');fs.mkdirSync(out,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9845'}),checks=[],errors=[],headers={};
 mp.on('exception',e=>errors.push(e));mp.on('console',e=>{if(e.level==='error')errors.push(e);});
 const before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]));
 const pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 const data=()=>mp.evaluate(()=>getCurrentPages().at(-1).data);
 async function open(url){await mp.callWxMethod('reLaunch',{url});await wait(750);return mp.currentPage();}
 async function capture(name){await mp.screenshot({path:path.join(out,name+'.png')});}
 async function input(p,value){await(await p.$('.guest-search-box input')).input(value);await wait(140);return data();}
 try{
  await mp.evaluate(seed=>{wx.setStorageSync('zhongshu-account-v1',seed);wx.setStorageSync('zhongshu-management-v1',{version:1,companyId:'quanzhou',staff:[],receipts:[{id:'PRIVATE-ID',customer:'PRIVATE-CUSTOMER',amount:999999999}],payments:[],payables:[],refunds:[]});},A.seed());
  let p=await open('/pages/account/index?view=login');
  const copy=await(await p.$('.account')).text();
  for(const text of ['企业成员权限由管理员分配','同一账号','手机电脑信息共享','随时随地查看公司与项目信息','仅查看有权限的公司与项目','保障企业数据安全'])assert(!copy.includes(text),text);
  assert.equal((await p.$$('image[src="/pages/account/assets/login-devices.jpg"]')).length,0);assert.equal((await p.$$('image[src="/pages/account/assets/login-lock.jpg"]')).length,0);
  assert.equal((await p.$$('.login-card input')).length,2);assert.equal(await(await p.$('.login-card .solid')).text(),'登录并继续');assert(await p.$('.agreement-row'));
  await mp.pageScrollTo(0);await capture('login-top');await mp.pageScrollTo(100000);await wait(120);await capture('login-bottom');
  pass('登录页已无截图中的权限提示、共享说明和安全说明，账号表单、协议与登录入口保留');
  await(await p.$('.login-card .solid')).tap();await wait(100);assert.equal((await data()).error,'请先阅读并同意用户协议和隐私政策');
  await(await p.$('.agreement-row .check-row')).tap();await(await p.$('.login-card .solid')).tap();await wait(100);assert.equal((await data()).modal.type,'login-demo');await mp.evaluate(()=>getCurrentPages().at(-1).closeModal());
  pass('删除说明后，协议校验与现有登录确认流程正常');
  p=await open('/pages/management/index');const d=await data();assert(d.guest);assert(await p.$('.login-guide'));assert(!await p.$('.guest-search-box'));assert(!await p.$('.guest-todo'));assert(!await p.$('.guest-entry'));assert(!JSON.stringify(d).includes('999999999'));
  await capture('management-top');await mp.pageScrollTo(100000);await wait(120);await capture('management-bottom');
  pass('管理访客页仅显示登录引导，不渲染游客搜索、示例待办或本地公司财务数据');
  for(const name of ['business','engineering','management']){
   p=await open('/pages/'+name+'/index');headers[name]=await mp.evaluate(name=>{const c=getCurrentPages().at(-1).selectComponent('#'+name+'-workbench-header');return c?{divider:c.properties.divider,accountFont:c.properties.accountFont,title:c.properties.title}:null;},name);
   assert(headers[name]);assert(headers[name].divider);assert(headers[name].accountFont);assert.equal(headers[name].title,({business:'业务工作台',engineering:'工程工作台',management:'管理工作台'})[name]);await capture(name+'-header');
  }
  pass('业务、工程、管理统一启用同一品牌标题组件的竖线和字体，保存三页截图供视觉复核');
  await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);
  await open('/pages/account/index?view=login');await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({selectedDemoUser:'boss'});setTimeout(()=>p.loginDemo(),80);return true;});await wait(1200);
  p=await open('/pages/management/index');d=await data();assert(!d.guest);assert(d.allowed);assert(!await p.$('.guest-search-box'));assert(await p.$('.staff-summary'));await capture('management-signed-in');
  pass('老板登录后管理工作台继续显示原岗位的待办与经营收支');
  assert.equal(errors.length,0);fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify({result:'passed',checks,errors,headers},null,2));
 }catch(e){await capture('failed').catch(()=>{});fs.writeFileSync(path.join(out,'failed.json'),JSON.stringify({error:e.message,checks,errors},null,2));throw e;}
 finally{
  await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);
  await open('/pages/engineering/index');await mp.disconnect();
 }
})().catch(e=>{console.error(e);process.exit(1);});
