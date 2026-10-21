const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const root=path.resolve('qa/business-flow-optimization-20261020'),out=path.join(root,'navigation-native');
process.env.FRANCHISE_NATIVE_OUT=out;process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
const checks=[],screens=[];
(async()=>{const r=await require('./franchise-native-runtime.cjs').connect('navigation');
 const norm=x=>JSON.stringify(x.slice().sort((a,b)=>a[0].localeCompare(b[0]))),pass=name=>{checks.push({name,result:'passed'});console.log('PASS '+name);};
 const rows=JSON.parse(fs.readFileSync(path.join(root,'accepted-fixture.json'))),quote=JSON.parse(fs.readFileSync(path.join(root,'returned-quote-fixture.json'))),trace=JSON.parse(fs.readFileSync(path.join(root,'independent-trace.json'))).steps;
 const sales=quote.find(x=>x[0]==='zhongshu-account-v1')[1],engineer=rows.find(x=>x[0]==='zhongshu-account-v1')[1],id=rows.find(x=>x[0]==='zhongshu-business-v3')[1].customers.find(c=>c.id.includes('from')).id;
 async function actor(account){await r.mp.evaluate(a=>{wx.setStorageSync('zhongshu-account-v1',a);return true;},account);}
 async function shot(name){screens.push(await r.capture(name));}
 async function header(tag,method='back'){
  const rect=await r.mp.evaluate(tag=>new Promise(resolve=>{const p=getCurrentPages().at(-1),c=p.selectComponent('#workflow-header')||p.selectComponent('#creation-page-header')||p.selectComponent('#subpage-header');if(!c)return resolve(null);wx.createSelectorQuery().in(c).select(tag==='owner-header'?'.boss-back':'.back').boundingClientRect().exec(a=>resolve(a[0]));}),tag);
  assert(rect&&rect.width>=44&&rect.height>=44,'Visible 44px return control '+tag);
  await r.mp.evaluate(({tag,method})=>{const p=getCurrentPages().at(-1);const c=p.selectComponent('#workflow-header')||p.selectComponent('#creation-page-header')||p.selectComponent('#subpage-header');c[method]();return true;},{tag,method});await r.sleep(400);
 }
 try{
  await r.replace(rows);await actor(sales);
  await r.open('/pages/workflow/explore/index?view=budget');await r.event('.calculate-explore','calculate');await r.until(async()=>(await r.data()).view==='budget-result','budget result');await shot('01-budget-fixed-navigation');await header('business-header');assert.equal((await r.data()).view,'budget');pass('预算结果固定返回按钮回到原参数表单');
  await r.open('/pages/workflow/explore/index?view=search');await r.event('.explore-case','openCase');assert.equal((await r.data()).view,'case');await header('business-header');assert.equal((await r.data()).view,'search');pass('平面检索详情返回原检索视图，不跳到灵感首页');
  await r.open('/pages/workflow/index?view=customer&id='+id);
  await r.mp.evaluate(()=>{const p=getCurrentPages().at(-1);p.setData({keyword:'保留筛选','form.requestText':'原输入保留'});p.openView('delivery');return true;});await r.until(async()=>(await r.data()).view==='delivery','delivery');await header('business-header');assert.equal((await r.data()).view,'customer');assert.equal((await r.data()).keyword,'保留筛选');pass('业务子页面返回原页面并保留筛选和输入');
  // More than eight distinct pages exercises the actual native redirect threshold.
  for(const view of ['delivery','quote','contract','history','documents','copies','drawing-review','accepted']){await r.mp.evaluate(view=>{getCurrentPages().at(-1).openView(view);return true;},view);await r.until(async()=>(await r.data()).view===view,view);}
  const depth=await r.mp.evaluate(()=>getCurrentPages().length);assert.equal(depth,8);await header('business-header');await r.until(async()=>(await r.data()).view==='drawing-review','deep return to drawing review');await header('business-header');await r.until(async()=>(await r.data()).view==='copies','deep return to copies');await shot('02-deep-return');pass('原生八层页面替换后仍逐级返回直接来源');
  await r.open('/pages/account/index?view=course&id=ai-video');await shot('03-account-navigation');await header('account-header','workbench');await r.until(async()=>(await r.current()).route==='pages/business/index','sales workbench');pass('账号子页面工作台入口返回当前业务岗位');
  await r.open('/pages/studio/index?view=works');await shot('04-creation-navigation');await header('creation-header','workbench');await r.until(async()=>(await r.current()).route==='pages/business/index','creation workbench');pass('创作子页面统一固定返回与工作台入口');
  const bossScene=trace.find(s=>s.area==='workflow'&&new URL(s.url,'http://local').searchParams.get('view')==='money-review');await actor(bossScene.before.find(x=>x[0]==='zhongshu-account-v1')[1]);await r.open('/pages/admin/detail/index?view=business');await shot('05-owner-navigation');await header('owner-header','workbench');await r.until(async()=>(await r.current()).route==='pages/admin/home/index','owner workbench');pass('管理详情固定导航可返回老板工作台');
  await r.open('/pages/admin/index?view=data');await shot('06-admin-navigation');await header('admin-header','workbench');await r.until(async()=>(await r.current()).route==='pages/admin/home/index','admin workbench');pass('管理办理页统一导航不再回到个人页');
  await actor(engineer);const project=rows.find(x=>x[0]==='zhongshu-engineering-v1')[1].projects.find(p=>p.sourceCustomerId===id);assert(project);await r.open('/pages/build/index?view=overview&id='+project.id);await shot('07-engineering-navigation');await header('business-header','workbench');await r.until(async()=>(await r.current()).route==='pages/build/home/index','engineering workbench');pass('工程子页面工作台入口按工程岗位返回');
  assert.equal(r.errors.length,0,JSON.stringify(r.errors));fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({result:'passed',checks,screens,exceptions:r.errors,scope:'实际微信原生组件返回和工作台入口；检索与预算同页返回；业务页面原生栈及八层重定向回退。'},null,2));
 }finally{try{await r.replace(r.before);assert.equal(norm(await r.snapshot()),norm(r.before));await r.open('/pages/business/index').catch(()=>{});await r.replace(r.before);fs.writeFileSync(path.join(out,'restored.json'),JSON.stringify({restored:norm(await r.snapshot())===norm(r.before),keys:r.before.length}));}finally{await r.mp.disconnect();}}
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
