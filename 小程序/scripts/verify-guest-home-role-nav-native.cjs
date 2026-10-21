const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
global.wx={getStorageSync:()=>undefined};
const A=require('../miniprogram/utils/account'),B=require('../miniprogram/utils/business'),E=require('../miniprogram/pages/build/model'),M=require('../miniprogram/pages/admin/model');
const out=path.resolve('qa/guest-home-role-nav-20261019'),pause=ms=>new Promise(r=>setTimeout(r,ms));
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9430'}),checks=[],errors=[];
 // DevTools can reuse a webview ID after redirectTo. This SDK caches Page.path by
 // that ID, so discard its wrapper cache before reading the real current page.
 const nativeCurrentPage=mp.currentPage.bind(mp);mp.currentPage=async()=>{mp.pageMap.clear();return nativeCurrentPage();};
 const currentPath=()=>mp.evaluate(()=>getCurrentPages().at(-1)?.route);
 mp.on('exception',e=>errors.push(e));
 const snapshot=()=>mp.evaluate(()=>wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]));
 const before=await snapshot();fs.writeFileSync(path.join(out,'storage-before.json'),JSON.stringify(before));
 const originalRoute=(await currentPath());
 const data=key=>mp.evaluate(key=>getCurrentPages().at(-1).data[key],key);
 async function until(fn,label){for(let i=0;i<45;i++){if(await fn())return;await pause(160);}throw Error('Not ready: '+label);}
 async function route(url,expected){await mp.callWxMethod('reLaunch',{url});await until(async()=>(await currentPath())===expected,expected);await pause(650);return mp.currentPage();}
 async function nav(){return mp.evaluate(()=>{const p=getCurrentPages().at(-1),selector={'pages/creation/index':'#creation-workspace-nav','pages/design/index':'#design-workspace-nav','pages/customers/index':'#customers-workspace-nav'}[p.route],c=selector?p.selectComponent(selector):p.getTabBar?.();return c?{tabs:c.data.tabs,selected:c.data.selected}:null;});}
 async function tab(index,expected){await mp.evaluate(index=>{const p=getCurrentPages().at(-1),selector={'pages/creation/index':'#creation-workspace-nav','pages/design/index':'#design-workspace-nav','pages/customers/index':'#customers-workspace-nav'}[p.route],c=selector?p.selectComponent(selector):p.getTabBar?.();if(!c)throw Error('Missing navigation');c.switchTab({currentTarget:{dataset:{index}}});},index);await until(async()=>(await currentPath())===expected,expected);await pause(650);return mp.currentPage();}
 async function capture(name){await mp.screenshot({path:path.join(out,name+'.png')});}
 function pass(name){checks.push({name,result:'passed'});console.log('PASS '+name);}
 async function login(id,expected){await route('/pages/account/index?view=login','pages/account/index');await mp.evaluate(id=>{const p=getCurrentPages().at(-1);p.setData({selectedDemoUser:id,checked:true});p.login();p.confirmModal();},id);await until(async()=>(await currentPath())===expected,'login '+id);await pause(300);}
 try{
  await mp.evaluate(rows=>{for(const[k,v]of rows)wx.setStorageSync(k,v);},[[A.KEY,A.seed()],[B.KEY,B.initial()],[E.KEY,E.initial()],[M.KEY,M.seed()]]);
  let p=await route('/pages/home/index','pages/home/index');
  assert.equal(await(await p.$('.home-brand-title')).text(),'众墅之家');assert.equal(await(await p.$('.home-platform-title')).text(),'AI赋能平台');
  assert.equal((await p.$$('.home-role')).length,4);assert.equal((await p.$$('.ai-card')).length,2);
  assert.deepEqual((await nav()).tabs.map(x=>x.text),['首页','业务','工程','管理','我的']);
  const layout=await mp.evaluate(()=>new Promise(resolve=>{const q=wx.createSelectorQuery();q.select('.home-page').boundingClientRect();q.select('.home-header').boundingClientRect();q.select('.home-hero').boundingClientRect();q.select('.home-roles').boundingClientRect();q.select('.ai-section').boundingClientRect();q.select('.home-flow').boundingClientRect();q.select('.home-login-banner').boundingClientRect();q.selectViewport().scrollOffset();q.exec(resolve);}));
  const bar=await mp.evaluate(()=>new Promise(resolve=>getCurrentPages().at(-1).getTabBar().createSelectorQuery().select('.tabbar').boundingClientRect(resolve).exec()));
  fs.writeFileSync(path.join(out,'home-layout.json'),JSON.stringify({layout,bar},null,2));assert(layout[6].bottom<=bar.top+1,'登录栏完整位于底部导航上方');assert(layout.slice(2,7).every(r=>r.height>0),'全部首页模块可见');
  await capture('01-guest-home-top');await mp.pageScrollTo(10000);await pause(200);await capture('02-guest-home-bottom');const scrollOffset=await mp.evaluate(()=>new Promise(resolve=>wx.createSelectorQuery().selectViewport().scrollOffset(r=>resolve(r.scrollTop)).exec()));assert.equal(scrollOffset,0,'固定首页不能上下滚动');
  const loginButton=await p.$('.home-login-button'),offset=await loginButton.offset(),size=await loginButton.size(),screen=await p.size();assert(offset.top+Number(size.height)<Number(screen.height)-60,'Login remains clear of tabs');
  pass('固定一屏首页：四岗位、两项AI能力、协同流程与登录栏全部可见，导航不遮挡且不能滚动');
  console.log('STEP homepage login handler');await mp.evaluate(()=>getCurrentPages().at(-1).login());console.log('STEP homepage login route');await until(async()=>(await currentPath())==='pages/account/index','homepage login');assert.equal(await data('view'),'login');console.log('STEP homepage login verified');
  await route('/pages/home/index','pages/home/index');await mp.mockWxMethod('showModal',function(options){getApp().globalData.homeIntroAudit={title:options.title,content:options.content};return{confirm:true,cancel:false,errMsg:'showModal:ok'};});await mp.evaluate(()=>getCurrentPages().at(-1).learnPlatform());assert.equal((await mp.evaluate(()=>getApp().globalData.homeIntroAudit)).title,'众墅之家AI赋能平台');await mp.restoreWxMethod('showModal');pass('首页了解平台弹窗与立即登录按钮可用');
  for(const[index,area]of [[1,'business'],[2,'engineering'],[3,'management'],[4,'profile']]){p=await tab(index,'pages/'+area+'/index');if(area!=='profile')assert.equal(await data('guest'),true);await capture('guest-'+area);}
  pass('原有业务、工程、管理及我的游客页面保留并可切换');
  await login('zhang','pages/business/index');assert.deepEqual((await nav()).tabs.map(x=>x.text),['首页','创作','设计','客户','我的']);assert.equal((await nav()).selected,1);await capture('03-sales-home');
  p=await tab(5,'pages/creation/index');assert.equal((await nav()).selected,5);await capture('04-sales-creation');
  p=await tab(6,'pages/design/index');assert.equal((await nav()).selected,6);assert.equal((await p.$$('.design-entry')).length,4);await capture('05-sales-design');
  await mp.evaluate(()=>getCurrentPages().at(-1).open({currentTarget:{dataset:{view:'search'}}}));await until(async()=>(await currentPath())==='pages/workflow/index','design customer selection');assert.equal(await data('selectingCustomer'),true);
  await route('/pages/design/index','pages/design/index');p=await tab(7,'pages/customers/index');assert.equal((await nav()).selected,7);await capture('06-sales-customers');
  const total=(await data('customers')).length;assert(total>0);await(await p.$('.customer-search input')).input('不存在的客户navcheck');await pause(100);assert.equal((await data('filteredCustomers')).length,0);await(await p.$('.customer-search input')).input('');await pause(100);assert.equal((await data('filteredCustomers')).length,total);
  await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.open({currentTarget:{dataset:{id:p.data.filteredCustomers[0].id}}});});await until(async()=>(await currentPath())==='pages/workflow/index','customer detail');assert.equal(await data('view'),'customer');
  await route('/pages/customers/index','pages/customers/index');await mp.evaluate(()=>getCurrentPages().at(-1).register());await until(async()=>(await currentPath())==='pages/workflow/index','register customer');assert.equal(await data('modal'),'register');
  await route('/pages/customers/index','pages/customers/index');for(let i=0;i<8;i++){await tab(6,'pages/design/index');await tab(7,'pages/customers/index');}assert(await mp.evaluate(()=>getCurrentPages().length)<=2,'Switching virtual tabs never grows stack');
  await tab(4,'pages/profile/index');assert.equal((await nav()).selected,4);await tab(1,'pages/business/index');
  pass('业务五页导航、选中态、设计关联客户、客户搜索详情登记及反复切换均正常');
  await route('/pages/home/index','pages/business/index');pass('已登录访问公开首页自动回本人工作台');
  for(const[id,expected]of [['sales-lead','business'],['manager-li','engineering'],['engineering-lead','engineering'],['finance-wang','management'],['finance-lead','management'],['store-owner','management']]){await login(id,'pages/'+expected+'/index');const n=await nav();assert.equal(n.tabs[0].text,'首页');assert.equal(n.tabs[0].key,expected);assert(!n.tabs.some(t=>t.key==='home'));await capture('role-'+id);}
  pass('业务负责人、工程及负责人、财务及负责人、门店负责人登录后进入各自工作台');
  await route('/pages/design/index','pages/management/index');await route('/pages/customers/index','pages/management/index');pass('不匹配的身份不能停留在业务设计或客户页');
  const previous=JSON.parse(fs.readFileSync(path.join(out,'guest-pages-before-hashes.json'),'utf8').replace(/^\uFEFF/,''));const crypto=require('crypto');for(const f of previous)assert.equal(crypto.createHash('sha256').update(fs.readFileSync(f.Path)).digest('hex').toUpperCase(),f.Hash);pass('原业务、工程、管理、我的页面源文件校验一致');
  assert.equal(errors.length,0,JSON.stringify(errors));
  fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify({result:'passed',checks,errors},null,2));
 }catch(e){await capture('failed');const actual=await mp.evaluate(()=>({pages:getCurrentPages().map(p=>({route:p.route,keys:Object.keys(p.data||{}).slice(0,8)}))}));fs.writeFileSync(path.join(out,'native-failed.json'),JSON.stringify({message:e.message,stack:e.stack,actual,checks,errors},null,2));throw e;}
 finally{await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);const after=await snapshot(),norm=rows=>JSON.stringify(rows.sort((a,b)=>a[0].localeCompare(b[0])));fs.writeFileSync(path.join(out,'storage-restored.json'),JSON.stringify({restored:norm(before)===norm(after)}));await mp.callWxMethod('reLaunch',{url:'/'+originalRoute});await mp.disconnect();}
})().catch(e=>{console.error(e);process.exitCode=1;});

