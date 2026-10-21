const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
global.wx={getStorageSync:()=>undefined};
const A=require('../miniprogram/utils/account');
const out=path.resolve('qa/engineering-guest-home-20261019');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const mp=await auto.connect({wsEndpoint:'ws://127.0.0.1:9845'}),checks=[],errors=[],metrics={};
 mp.on('exception',e=>errors.push(e));
 mp.on('console',e=>{if(e.level==='error')errors.push(e);});
 const before=await mp.evaluate(()=>wx.getStorageInfoSync().keys.map(k=>[k,wx.getStorageSync(k)]));
 const data=k=>mp.evaluate(k=>getCurrentPages().at(-1).data[k],k);
 const pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 async function open(page){await mp.callWxMethod('reLaunch',{url:'/pages/'+page+'/index'});await wait(750);return mp.currentPage();}
 async function scroll(y){await mp.pageScrollTo(y);await wait(180);}
 async function capture(name){await mp.screenshot({path:path.join(out,name+'.png')});}
 async function style(page,selector,keys){const e=await page.$(selector);assert(e,selector);const result={};for(const key of keys)result[key]=await e.style(key);return result;}
 let navigationMock=false;
 async function loginGate(selector){const p=await mp.currentPage(),e=await p.$(selector);assert(e,selector);const top=(await e.offset()).top+Number(await p.scrollTop());await scroll(Math.max(0,top-180));const count=await mp.evaluate(()=>(getApp().globalData.guestRouteAudit||[]).length);await e.tap();await wait(100);const routes=await mp.evaluate(()=>getApp().globalData.guestRouteAudit||[]);assert.equal(routes.length,count+1,selector);assert.equal(routes.at(-1),'/pages/account/index?view=login',selector);}
 try{
  await mp.evaluate(seed=>{wx.setStorageSync('zhongshu-account-v1',seed);wx.setStorageSync('zhongshu-engineering-v1',{version:1,projects:[{id:'private-audit-only',name:'私有项目禁止公开',customer:'私有客户',plan:{nodes:[]}}]});},A.seed());
  let p=await open('engineering');
  for(let i=0;i<30;i++){const state=await mp.evaluate(()=>getApp().globalData.localFonts?.dashboard||{});if(Object.values(state).length===3&&Object.values(state).every(s=>s==='loaded'))break;await wait(100);}
  assert.equal(await data('guest'),true);assert.equal((await p.$$('.guest-project')).length,1);assert.equal((await p.$$('.guest-task')).length,2);assert.equal((await p.$$('.guest-shortcut-grid button')).length,4);assert.deepEqual((await data('previewStats')).map(x=>x.value),[1,1,3]);
  assert.equal((await p.$$('.materials-banner')).length,0);assert.equal((await p.$$('.tools-grid')).length,0);
  pass('访客首页保留一项在建项目、两条今日待办和四项快捷操作，取消第二项目');
  const order=[];for(const selector of ['.guest-stats','.guest-todos','.guest-projects','.guest-shortcuts','.guest-login-button'])order.push((await(await p.$(selector)).offset()).top);assert(order.every((y,i)=>i===0||y>order[i-1]));pass('顺序为搜索统计、今日待办、在建项目、快捷操作、登录按钮');
  const families=await mp.evaluate(()=>getApp().globalData.localFonts.dashboard);assert(Object.values(families).every(x=>x==='loaded'));pass('共用ZSDashboard三个字重全部加载，新增工程字形不回退');
  await scroll(0);await capture('final-top');await scroll(100000);await capture('final-bottom');
  const last=await p.$('.guest-login-button'),offset=await last.offset(),size=await last.size(),screen=await p.size();assert(offset.top+Number(size.height)<Number(screen.height)-65);assert.equal(await last.text(),'登录后查看本人工作台');
  pass('底部登录按钮可滚动到达，固定导航不遮住操作');
  const search=await p.$('.guest-search input');await search.input('湖北');await wait(150);assert.equal((await data('previewProjects')).length,1);assert.equal((await data('previewProjects'))[0].id,'preview-chen');await scroll(0);await capture('search-match');
  await search.input('陈先生');await wait(150);assert.equal((await data('previewProjects'))[0].id,'preview-chen');pass('原生搜索支持客户项目和所在城市，结果与输入对应');
  await search.input('私有项目禁止公开');await wait(150);assert.equal((await data('previewProjects')).length,0);assert((await(await p.$('.guest-project-empty')).text()).includes('没有找到匹配项目'));await capture('search-empty');
  await(await p.$('.guest-search-clear')).tap();await wait(150);assert.equal(await data('guestKeyword'),'');assert.equal((await data('previewProjects')).length,1);pass('空结果、清空恢复及访客隔离：不展示本地真实私有项目');
  await mp.mockWxMethod('navigateTo',function(options){const g=getApp().globalData;g.guestRouteAudit=(g.guestRouteAudit||[]).concat(options.url);return{errMsg:'navigateTo:ok'};});navigationMock=true;
  const core=['.guest-new','.guest-stats button','.guest-section-link','.guest-project-photo','.guest-project-name','.guest-project-action .primary','.guest-task .primary','.guest-task .outline','.guest-shortcut-grid button','.guest-login-button'];
  for(const selector of core)await loginGate(selector);
  pass('实际按钮事件均请求登录路由：新建、统计、项目、待办、快捷操作和底部登录');
  await mp.restoreWxMethod('navigateTo');navigationMock=false;
  p=await mp.currentPage();
  const keys=['font-family','font-size','font-weight','line-height','height','border-radius','background-color','color'];
  metrics.engineering=await style(p,'.guest-task .primary',keys);metrics.engineeringLogin=await style(p,'.guest-login-button',keys);metrics.engineeringHeading=await style(p,'.section-title',['font-family','font-size','font-weight','line-height']);
  p=await open('business');metrics.business=await style(p,'[data-action="customer"]',keys);metrics.businessLogin=await style(p,'.guest-login-button',keys);metrics.businessHeading=await style(p,'.section-title',['font-family','font-size','font-weight','line-height']);await capture('business-style-check');
  p=await open('management');metrics.managementLogin=await style(p,'.guest-login-button',keys);metrics.managementHeading=await style(p,'.section-title',['font-family','font-size','font-weight','line-height']);await capture('management-style-check');
  assert.deepEqual(metrics.engineering,metrics.business);assert.deepEqual(metrics.engineeringHeading,metrics.businessHeading);assert.deepEqual(metrics.engineeringHeading,metrics.managementHeading);
  pass('工程与业务按钮的字体、字重、颜色、高度、圆角一致，三大工作台标题层级一致');
  assert.deepEqual(metrics.engineeringLogin,metrics.businessLogin);assert.deepEqual(metrics.engineeringLogin,metrics.managementLogin);pass('底部登录按钮与业务、管理工作台的字体、颜色、高度和圆角完全同规格');
  p=await open('engineering');assert.equal(await mp.evaluate(()=>getCurrentPages().at(-1).getTabBar().data.selected),2);
  await mp.evaluate(()=>{const bar=getCurrentPages().at(-1).getTabBar();setTimeout(()=>bar.switchTab({currentTarget:{dataset:{index:3}}}),80);return true;});for(let i=0;i<40;i++){if(await mp.evaluate(()=>getCurrentPages().at(-1).route)==='pages/management/index')break;await wait(100);}assert.equal(await mp.evaluate(()=>getCurrentPages().at(-1).route),'pages/management/index');pass('五项公共底部导航保留，工程高亮且管理入口仍到管理工作台');
  await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);
  await mp.callWxMethod('reLaunch',{url:'/pages/account/index?view=login'});await wait(600);await mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({selectedDemoUser:'manager-li'});setTimeout(()=>p.loginDemo(),80);return true;});await wait(1400);
  p=await open('engineering');assert.equal(await data('guest'),false);assert.equal((await p.$$('.guest-shortcut-grid button')).length,4);assert(await p.$('.guest-stats'));await scroll(0);await capture('signed-in-regression');pass('工程师登录后使用新版授权项目、待办与快捷操作布局');
  await mp.evaluate(seed=>wx.setStorageSync('zhongshu-account-v1',seed),A.seed());p=await open('engineering');await mp.evaluate(()=>{const p=getCurrentPages().at(-1);setTimeout(()=>p.openAction({currentTarget:{dataset:{action:'login'}}}),80);return true;});await wait(1200);assert.equal(await mp.evaluate(()=>getCurrentPages().at(-1).route),'pages/account/index');assert.equal(await data('view'),'login');await capture('login-gate');pass('实际登录页面打开，显示原有账号登录与本地演示入口');
  assert.equal(errors.length,0);fs.writeFileSync(path.join(out,'native-results.json'),JSON.stringify({result:'passed',checks,errors,metrics,system:await mp.systemInfo()},null,2));
 }finally{
  if(navigationMock)await mp.restoreWxMethod('navigateTo');
  await mp.evaluate(rows=>{for(const k of wx.getStorageInfoSync().keys)wx.removeStorageSync(k);for(const[k,v]of rows)wx.setStorageSync(k,v);},before);
  await mp.callWxMethod('reLaunch',{url:'/pages/engineering/index'});await mp.disconnect();
 }
})().catch(e=>{console.error(e);process.exit(1)});


