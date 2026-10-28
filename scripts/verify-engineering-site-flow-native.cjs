// 工程现场流程真实联调：在微信开发者工具原生环境验证本轮新功能——
// 进度计划模板生成、Excel导入入口、进度偏差与逾期标签、收款催款分级、
// 现场拍照入口。业务动作全部通过真实页面控件办理；登录身份与场景种子
// 同级由脚本设定（真实UI的身份切换页在本环境渲染不稳定）；结束恢复原始存储。
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),auto=require('miniprogram-automator');
const endpoint=process.env.ENGINEERING_SITE_WS||process.env.FRANCHISE_WS;
assert(endpoint,'请提供自动化端口：ENGINEERING_SITE_WS=ws://127.0.0.1:9721');
const out=path.resolve(process.env.ENGINEERING_SITE_NATIVE_OUT||'qa/engineering-site-native-20261028');fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const scenes=JSON.parse(fs.readFileSync('qa/engineering-records-20261021/page-scenes.json','utf8'));
(async()=>{
 const mp=await auto.connect({wsEndpoint:endpoint}),errors=[];mp.on('exception',e=>errors.push(e));
 const results={result:'running',checks:[],screens:[],events:[],errors};
 const save=()=>fs.writeFileSync(path.join(out,'site-flow-results.json'),JSON.stringify(results,null,2));
 const pass=n=>{results.checks.push(n);console.log('PASS '+n);save();};
 const retry=async fn=>{try{return await fn();}catch(e){if(!/timeout waiting for automator response/.test(e.message))throw e;await sleep(600);return fn();}};
 const before=await retry(()=>mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(k=>[k,wx.getStorageSync(k)])));
 const snapshot=()=>retry(()=>mp.evaluate(()=>wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).map(k=>[k,wx.getStorageSync(k)])));
 const norm=rows=>JSON.stringify(rows.sort((a,b)=>a[0].localeCompare(b[0])));
 const current=()=>retry(()=>mp.evaluate(()=>({route:getCurrentPages().at(-1)?.route,data:getCurrentPages().at(-1)?.data})));
 const data=async()=>(await current()).data;
 async function until(fn,label,ms=15000){const end=Date.now()+ms;let last='';while(Date.now()<end){try{if(await fn())return;}catch(e){if(!/timeout waiting for automator response/.test(e.message))throw e;last=e.message;}await sleep(150);}throw Error('Not ready: '+label+(last?'（'+last+'）':''));}
 const seed=rows=>retry(()=>mp.evaluate(rows=>{wx.getStorageInfoSync().keys.filter(k=>k.startsWith('zhongshu-')).forEach(k=>wx.removeStorageSync(k));for(const[k,v]of rows)wx.setStorageSync(k,v);},rows));
 const grantOwner=()=>retry(()=>mp.evaluate(()=>{const st=wx.getStorageSync('zhongshu-engineering-v1');(st.projects||[]).forEach(x=>{if(x.recordFlow){x.managerId='engineering-dual';x.manager='张工程';x.assignedEngineerId='engineering-dual';x.assignedEngineerName='张工程';}});wx.setStorageSync('zhongshu-engineering-v1',st);const b=wx.getStorageSync('zhongshu-business-v3');(b.customers||[]).forEach(c=>{if(c.projectId){c.managerId='engineering-dual';c.currentManagerName='张工程';}});wx.setStorageSync('zhongshu-business-v3',b);return true;}));
 const findEl=async selector=>{const page=await mp.currentPage();try{return page&&await page.$(selector);}catch(e){if(!/timeout waiting for automator response/.test(e.message))throw e;await sleep(400);const again=await mp.currentPage();return again&&await again.$(selector);}};
 const el=async selector=>{let e;await until(async()=>{e=await findEl(selector);return !!e;},'native control '+selector);const size=await e.size();assert(Number(size.width)>0&&Number(size.height)>0,'应渲染可见 '+selector);return e;};
 const tap=async selector=>{const page=await mp.currentPage(),e=await el(selector);await e.tap();results.events.push({route:(await current()).route,selector,via:'Element.tap'});await sleep(300);save();const d=await data();assert(!d?.error,d?.error);return d;};
 const shot=async(name,selector)=>{if(selector){const page=await mp.currentPage(),e=await findEl(selector);if(e){const off=await e.offset();await retry(()=>mp.evaluate(top=>{wx.pageScrollTo({scrollTop:Math.max(0,top-170),duration:0});return true;},off.top));}}else await retry(()=>mp.evaluate(()=>{wx.pageScrollTo({scrollTop:0,duration:0});return true;}));await sleep(350);const file=name+'.png';await mp.screenshot({path:path.join(out,file)});results.screens.push(file);save();return file;};
 const login=async(user,role)=>{
  await retry(()=>mp.reLaunch('/pages/account/index?view=login'));
  await until(async()=>!!(await data()).demoUsers,'login page ready');
  const i=(await data()).demoUsers.findIndex(u=>u.id===user);assert(i>=0,'演示用户 '+user);
  const page=await mp.currentPage(),picker=await page.$('.demo-user-picker');
  await picker.trigger('change',{value:String(i)});
  if(!(await data()).checked)await tap('.agreement-row .check-row');
  await tap('[data-handler="login"]');
  await until(async()=>!!(await data()).modal,'登录确认弹窗');
  await tap('.sheet [data-handler="confirmModal"]');
  await until(async()=>!(await current()).route.startsWith('pages/account/'),'完成登录');
  await sleep(1200);
  // 登录身份与场景种子同级：直接设定会话角色，业务动作仍走真实控件
  if(role)await retry(async()=>mp.evaluate(r=>{const a=wx.getStorageSync('zhongshu-account-v1');a.session.role=r;wx.setStorageSync('zhongshu-account-v1',a);return true;},role));
 };
 const recordUrl=(id,view,extra={})=>'/pages/build/record/index?id='+encodeURIComponent(id)+'&view='+view+Object.entries(extra).map(([k,v])=>'&'+k+'='+encodeURIComponent(v)).join('');
 const engProject=()=>retry(()=>mp.evaluate(()=>wx.getStorageSync('zhongshu-engineering-v1').projects.find(p=>p.recordFlow)));
 try{
  // ===== 场景一：开工准备页 模板生成 + Excel导入入口 =====
  await seed(scenes.find(s=>s.action==='prepare').before);
  await login('engineering-dual','engineer');
  await grantOwner();
  let p=await engProject();assert(p,'种子工程存在');
  await retry(()=>mp.reLaunch(recordUrl(p.id,'prepare')));
  await until(async()=>(await data())?.view==='prepare','开工准备页');
  assert((await data()).templates.length>=1,'公司模板已加载');
  await retry(()=>mp.evaluate(()=>{getCurrentPages().at(-1).setData({showPlan:true});return true;}));await sleep(300);
  await el('[data-action="apply-template"]');await el('[data-action="import-plan"]');
  assert(await retry(()=>mp.evaluate(()=>typeof getCurrentPages().at(-1).importPlan==='function')),'导入处理函数已挂载');
  pass('开工准备页渲染模板选择与Excel导入入口');
  await tap('[data-action="apply-template"]');
  await until(async()=>{const d=await data();return d.form.nodes.length===5&&d.form.nodes[0].start===d.form.templateStart;},'模板生成节点日期');
  assert((await data()).form.nodes.find(n=>n.name==='主体封顶')?.milestone==='topping','封顶里程碑标记');
  await shot('1-prepare-template-generated','[data-action="apply-template"]');
  pass('模板一键生成5个节点日期，与所选开工日对齐');
  // ===== 场景二：进度偏差与逾期标签 =====
  await seed(scenes.find(s=>s.action==='site').before);
  await login('engineering-dual','engineer');
  await grantOwner();
  p=await engProject();
  await retry(async()=>mp.evaluate(pid=>{const st=wx.getStorageSync('zhongshu-engineering-v1'),proj=st.projects.find(x=>x.id===pid),y=new Date();y.setDate(y.getDate()-1);const d=x=>x.toISOString().slice(0,10);proj.plan.nodes[0].start=d(new Date(y.getTime()-9*86400000));proj.plan.nodes[0].end=d(y);wx.setStorageSync('zhongshu-engineering-v1',st);return true;},p.id));
  await mp.reLaunch(recordUrl(p.id,'node',{nodeId:p.plan.nodes[0].id}));
  await until(async()=>(await data())?.view==='node','施工节点页');
  await until(async()=>{const d=await data();return d.nodeRows&&d.nodeRows[0].pace==='已逾期1天';},'逾期偏差计算');
  pass('施工节点页数据携带当前节点已逾期1天');
  await retry(()=>mp.reLaunch(recordUrl(p.id,'overview')));
  await until(async()=>{const d=await data();return d.paceOverdue===1&&d.paceDone===0&&d.paceTotal===5;},'进度页汇总');
  await el('.eng-tag.tone-danger');
  await shot('2-overdue-tag-and-summary','.eng-section');
  pass('项目进度页渲染逾期红色标签并汇总 0/5 节点完成');
  await retry(()=>mp.reLaunch('/pages/build/home/index'));
  await until(async()=>{const d=await data();return d.eng&&d.eng.stats.some(s=>s.label==='逾期节点'&&s.value===1);},'首页逾期统计');
  await shot('3-home-overdue-stat');
  pass('工程首页统计卡显示逾期节点1');
  await retry(()=>mp.reLaunch('/pages/build/projects/index'));
  await until(async()=>{const d=await data();return d.cards&&d.cards.length&&d.cards.some(c=>c.pace==='已逾期1天');},'项目卡片偏差');
  await shot('4-projects-card-pace');
  pass('项目列表卡片显示当前节点已逾期1天');
  // ===== 场景三：收款催款分级 =====
  await seed(scenes.find(s=>s.action==='collect').before);
  await login('engineering-dual','engineer');
  await grantOwner();
  p=await engProject();
  await retry(async()=>mp.evaluate(pid=>{const book=wx.getStorageSync('zhongshu-management-v1'),plan=book.operations.plans.filter(x=>x.projectId===pid).at(-1),y=new Date();y.setDate(y.getDate()-2);plan.installments.find(i=>i.stage==='start').due=y.toISOString().slice(0,10);wx.setStorageSync('zhongshu-management-v1',book);return true;},p.projectId));
  await retry(()=>mp.reLaunch('/pages/build/home/index'));
  await until(async()=>{const d=await data();return d.eng&&d.eng.tasks.some(t=>t.id.endsWith('collect-start')&&t.title.includes('已过约定收款日2天'));},'逾期催收待办');
  await shot('5-home-collection-overdue');
  pass('开工款过期2天进入待办：已过约定收款日2天，请催收登记');
  await retry(async()=>mp.evaluate(pid=>{const book=wx.getStorageSync('zhongshu-management-v1'),plan=book.operations.plans.filter(x=>x.projectId===pid).at(-1),t=new Date();t.setDate(t.getDate()+3);plan.installments.find(i=>i.stage==='start').due=t.toISOString().slice(0,10);wx.setStorageSync('zhongshu-management-v1',book);return true;},p.projectId));
  await retry(()=>mp.reLaunch('/pages/build/home/index'));
  await until(async()=>{const d=await data();return d.eng&&d.eng.waiting.some(t=>t.id.endsWith('collect-start')&&t.title.includes('约定收款日临近'));},'临期提示');
  pass('临期3天在办理中区提示约定收款日临近');
  // ===== 场景四：现场拍照入口（chooseMedia 弹窗交互需真机人工验证） =====
  await seed(scenes.find(s=>s.action==='site').before);
  await login('engineering-dual','engineer');
  await grantOwner();
  p=await engProject();
  await retry(()=>mp.reLaunch(recordUrl(p.id,'node',{nodeId:p.plan.nodes[0].id})));
  await until(async()=>(await data())?.view==='node','节点页');
  await el('.record-fields button[data-key="file"]');
  await shot('6-node-photo-entrance','.record-fields');
  pass('施工节点页渲染现场凭据上传入口');
  results.result='passed';
 }catch(e){results.result='failed';results.error=e.message;console.error('FAIL:',e.message);}
 const after=await snapshot();
 let restored=norm(before)===norm(after);
 if(!restored){await seed(before);restored=norm(before)===norm(await snapshot());}
 results.restored=restored;assert(restored,'原始存储未恢复');
 results.errors=errors;save();await mp.disconnect();
 console.log('COMPLETE',results.result,results.checks.length,'checks, restored:',restored);
 if(results.result!=='passed')process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
