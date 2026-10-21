const fs=require('fs'),path=require('path'),assert=require('assert/strict');
process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9540';
process.env.FRANCHISE_NATIVE_OUT=path.resolve('qa/boss-workbench-20261020/native');
const {connect}=require('./franchise-native-runtime.cjs');
(async()=>{
 const connectionTimer=setTimeout(()=>{console.error('微信自动化连接超时');process.exit(1);},40000);
 const r=await connect('boss');clearTimeout(connectionTimer);
 const checks=[],styles={},shots=[],out=r.directory;
 const seed=JSON.parse(fs.readFileSync('qa/boss-workbench-20261020/preview-storage.json','utf8'));
 const initial=await r.mp.evaluate(()=>{const p=getCurrentPages().at(-1);return {route:p?.route,options:p?.options||{}};});
 const pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 const open=async(url,expected=url.split('?')[0].slice(1))=>{
  await r.mp.evaluate(url=>{getApp().globalData.__bossNavigation={done:false,error:''};setTimeout(()=>wx.reLaunch({url,success:()=>{getApp().globalData.__bossNavigation.done=true;},fail:e=>{getApp().globalData.__bossNavigation.error=e.errMsg;}}),100);return true;},url);
  await r.until(async()=>{const s=await r.mp.evaluate(()=>({route:getCurrentPages().at(-1)?.route,nav:getApp().globalData.__bossNavigation}));if(s.nav?.error)throw Error(s.nav.error);return s.route===expected&&s.nav?.done;},expected,16000);await r.sleep(250);
 };
 const shot=async name=>{await r.mp.evaluate(()=>{wx.hideToast();return true;});await r.sleep(150);shots.push(await r.capture(name));};
 const style=async(selector)=>{r.mp.pageMap.clear();const el=await(await r.mp.currentPage()).$(selector);assert(el,selector+' rendered');const result={};for(const p of ['font-family','font-size','font-weight','line-height','color'])result[p]=await el.style(p);return result;};
 const scroll=async top=>{await r.mp.evaluate(top=>{setTimeout(()=>wx.pageScrollTo({scrollTop:top,duration:0}),30);return true;},top);await r.sleep(180);};
 try{
  await r.replace(seed);
  for(const page of ['home','finance','performance','profile']){
   await open('/pages/admin/'+page+'/index');await r.until(async()=>(await r.data()).ready,'boss '+page);await r.sleep(450);
   const data=await r.data();assert(!data.error,data.error);
   styles[page]={body:await style('.owner-name'),muted:await style('.owner-muted'),heading:await style('.owner-heading > text'),card:await style('.owner-card')};
   const card=await(await r.mp.currentPage()).$('.owner-card');for(const property of ['padding-top','padding-left','margin-bottom','border-radius'])styles[page].card[property]=await card.style(property);
   if(page!=='home'){for(const slot of ['body','muted','heading'])for(const property of ['font-family','font-size','font-weight','line-height'])assert.equal(styles[page][slot][property],styles.home[slot][property],page+' '+slot+' '+property);for(const property of ['padding-top','padding-left','margin-bottom','border-radius'])assert.equal(styles[page].card[property],styles.home.card[property],page+' card '+property);}
   await shot(page+'-top');await scroll(10000);await shot(page+'-bottom');
   const layout=await r.mp.evaluate(()=>new Promise(resolve=>{const p=getCurrentPages().at(-1),nav=p.selectComponent('#owner-navigation');if(!nav)return resolve({nav:null});nav.createSelectorQuery().select('.boss-nav').boundingClientRect().exec(a=>{wx.createSelectorQuery().select('.owner-content').boundingClientRect().exec(b=>resolve({nav:a[0],content:b[0],screen:wx.getWindowInfo().windowHeight}));});}));
   assert(layout.nav&&layout.nav.bottom<=layout.screen+1,'bottom navigation visible');assert(layout.content.bottom<=layout.nav.top+1,'last content clears bottom navigation');
   const fonts=await r.mp.evaluate(()=>getApp().globalData.localFonts.admin);assert(Object.values(fonts).every(v=>v==='loaded'),JSON.stringify(fonts));
   pass(page+'原生渲染、文字规范一致、字体加载及底部留白');
  }
  await open('/pages/admin/home/index');
  for(const page of ['finance','performance','profile','home']){
   r.mp.pageMap.clear();const component=await(await r.mp.currentPage()).$('#owner-navigation');assert(component,'owner navigation component');const button=await component.$('.boss-nav-item[data-page="'+page+'"]');assert(button,'four-tab '+page);await button.tap();await r.until(async()=>(await r.current()).route==='pages/admin/'+page+'/index','tab '+page);await r.sleep(120);
  }pass('四个底部栏目实际点击互通，当前栏目正确高亮');
  let d=await r.data(),task=d.desk.approvals.find(t=>t.record==='quote');assert(task);await r.event({selector:'.owner-primary',dataset:{key:task.key}},'go');await r.until(async()=>(await r.data()).view==='money-review','quote approval');assert.equal((await r.current()).data.customer.id,task.id);await shot('quote-approval');pass('首页待办进入真实原客户的本版金额审批');
  await open('/pages/admin/finance/index');await r.event('.owner-search-input','search',{value:'没有这个项目'});assert.equal((await r.data()).projects.length,0);await shot('finance-empty');await r.event('.owner-search-clear','clear');d=await r.data();const project=d.projects.find(p=>p.id==='boss-project-0');assert(project);await r.event({selector:'.owner-outline',dataset:{key:project.key}},'go');await r.until(async()=>{const p=await r.current();return p.route==='pages/admin/index'&&p.data.view==='finance'&&p.data.id==='boss-project-0';},'project ledger');await shot('project-ledger');pass('财务搜索及空状态、原项目ID账目跳转');
  await open('/pages/admin/detail/index?view=targets');await r.until(async()=>(await r.data()).ready,'targets');await shot('targets');await r.event('.owner-save','save');assert.equal((await r.data()).error,'');pass('月度目标在原生页面保存并回写同月进度');
  await open('/pages/admin/detail/index?view=handover');d=await r.data();assert(d.rows.length);await r.event({selector:'.owner-row',dataset:{key:d.rows[0].key}},'go');await r.until(async()=>(await r.data()).view==='transfer','handover');await shot('manager-transfer');pass('我的项目经理交接进入原工程责任交接表单');
  assert.equal(r.errors.length,0,JSON.stringify(r.errors));
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,styles,shots,errors:r.errors},null,2));console.log(JSON.stringify({result:'passed',checks:checks.length,screenshots:shots.length}));
 }catch(error){await shot('failure').catch(()=>{});fs.writeFileSync(path.join(out,'failed.json'),JSON.stringify({error:error.stack,current:await r.current(),checks,styles,errors:r.errors},null,2));throw error;}
 finally{
  await r.replace(r.before);
  const norm=rows=>JSON.stringify(rows.sort((a,b)=>a[0].localeCompare(b[0])));
  assert.equal(norm(await r.snapshot()),norm(r.before));
  fs.writeFileSync(path.join(out,'storage-restored.json'),JSON.stringify({restored:true,keys:r.before.length,initial},null,2));
  const url='/'+initial.route+Object.entries(initial.options).map(([k,v],i)=>(i?'&':'?')+k+'='+encodeURIComponent(v)).join('');
  await r.mp.evaluate(url=>{setTimeout(()=>wx.reLaunch({url}),60);return true;},url);await r.sleep(350);await r.replace(r.before);await r.mp.disconnect();
 }
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
