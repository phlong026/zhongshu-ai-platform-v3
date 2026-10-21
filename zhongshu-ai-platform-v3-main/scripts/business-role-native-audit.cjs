// Real WeChat SDK controls only. Fixture installation is confined to the tourist project.
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
async function attach(runtime,output){
 const R=runtime,mp=R.mp,file=path.join(output,'results.json');
 const report=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):{checks:[],screenshots:[],apiStubs:[],fixes:[]};
 report.resumedAt=new Date().toISOString();report.result='running';report.interactions=report.interactions||[];
 const persist=()=>fs.writeFileSync(file,JSON.stringify(report,null,2));
 const check=(name,ok,detail)=>{report.checks.push({name,result:ok?'passed':'failed',detail});persist();assert(ok,name+' '+JSON.stringify(detail||''));};
 async function find(spec){spec=typeof spec==='string'?{selector:spec}:spec;mp.pageMap?.clear();const p=await mp.currentPage();for(const el of await p.$$(spec.selector)){let match=spec.text===undefined||await el.text()===spec.text;for(const[k,v]of Object.entries(spec.dataset||{}))if(String(await el.attribute('data-'+k.replace(/[A-Z]/g,c=>'-'+c.toLowerCase())))!==String(v))match=false;if(match)return el;}return null;}
 async function event(spec,handler,detail={}){
  await R.sleep(650);spec=typeof spec==='string'?{selector:spec}:spec;const el=await R.until(()=>find(spec),'SDK control '+handler,15000),size=await el.size();assert(Number(size.width)>0&&Number(size.height)>0,'Rendered control '+spec.selector);
  const route=(await R.current()).route,input=['input','search'].includes(handler),change=['pick','chooseManager','check','inputDate','date'].includes(handler);
  if(input)await el.input(String(detail.value??''));else if(change)await el.trigger('change',detail);else await el.tap();
  report.interactions.push({route,selector:spec.selector,dataset:spec.dataset||{},text:spec.text,handler,via:input?'Element.input':change?'Element.trigger(change)':'Element.tap'});persist();await R.sleep(500);
  const d=await R.data();assert(!(d.error||d.operationError||d.financeError),d.error||d.operationError||d.financeError);
 }
 async function capture(name){await R.sleep(700);await R.capture(name);const d=await R.data();report.screenshots.push({file:name+'.png',route:(await R.current()).route,view:d.view||'',error:d.error||d.operationError||d.financeError||''});persist();return name+'.png';}
 async function login(id,role){await R.login(id,role);await R.sleep(1200);}
 async function submit(selector='.commission-actions .commission-primary'){await event(selector,'submit');await R.sleep(250);if((await R.data()).modal?.type==='confirm')await event('.sheet .solid','confirm');await R.sleep(350);}
 async function finance(who,view,id,stage,name){
  await login(who);await R.open('/pages/management/index','.fin-content');const d=(await R.data()).financeDesk;
  name=name||await mp.evaluate(id=>wx.getStorageSync('zhongshu-business-v3')?.customers?.find(c=>c.id===id)?.projectId,id);
  const task=d.tasks.find(t=>t.view===view&&(t.id===id||t.id.includes(id)||t.projectId===name)&&(!stage||t.id.endsWith(':'+stage)||t.name.includes(stage==='first'?'首款':'尾款')));
  check(who+' 自动出现 '+view+' '+(stage||''),!!task,{tasks:d.tasks.map(t=>({id:t.id,view:t.view}))});
  check(who+' 首页总数与本人全部待办一致',d.taskCount===d.tasks.length);
  await capture('finance-'+who+'-'+view+'-'+(stage||'review')+'-home');
  await event({selector:'.staff-todos .fin-text',dataset:{url:d.tasksUrl}},'financeGo');await R.until(async()=>(await R.data()).view==='tasks','Finance task list',15000);await R.sleep(800);
  check(who+' 全部待办含当前原单',(await R.data()).rows.some(t=>t.key===task.key));
  await event({selector:'.fin-task',dataset:{url:task.url}},'go');await R.until(async()=>(await R.data()).view===view,'Finance original '+view,15000);return task;
 }
 async function admin(id,view,label){
  await login('hq-design-admin');await R.open('/pages/workflow/commission/index?view=admin','.commission-card');const tasks=(await R.data()).tasks,task=tasks.find(t=>t.customerId===id);
  check(label+' 设计行政唯一待办',tasks.filter(t=>t.customerId===id).length===1&&!!task?.url.includes('view='+view));
  await capture('admin-'+label+'-home');await event({selector:'.commission-card .commission-primary',dataset:{url:task.url}},'openTask');await R.until(async()=>(await R.data()).view===view,'Admin '+view,15000);return task;
 }
 async function business(id,label){
  await login('sales-dual','sales-lead');await R.open('/pages/business/index','.sales-today');const d=await R.data(),task=d.flowPending.find(t=>t.customerId===id||t.id.endsWith(':'+id));
  check(label+' 业务唯一待办',!!task&&d.flowPending.filter(t=>t.id===task.id).length===1);await capture('business-'+label+'-home');await event({selector:'.sales-task-action',dataset:{id:task.id}},'openFlowTask');await R.sleep(700);return task;
 }
 async function fixtureRole(staffId,companyId,role){
  await mp.evaluate(({staffId,companyId,role})=>{const root=wx.getStorageSync('zhongshu-management-v1'),book=root.companyId===companyId?root:root.books[companyId],staff=book.staff.find(p=>p.id===staffId);if(!staff||staff.active===false||!staff.roles.includes(role))throw Error('Fixture identity must already hold the role');const a=wx.getStorageSync('zhongshu-account-v1');a.session={loggedIn:true,userId:'demo-'+staffId,staffId,companyId,role,demo:true,roleSchemaVersion:5};a.profile={name:staff.name,phone:'',avatar:'/assets/account-avatar.jpg'};a.companies=[{id:companyId,type:companyId==='headquarters'?'headquarters':'store',name:companyId==='headquarters'?'众墅之家总部':'湖北加盟商',store:companyId==='headquarters'?'总部':'湖北店',roles:staff.roles}];wx.setStorageSync('zhongshu-account-v1',a);},{staffId,companyId,role});
 }
 async function fixtureFinance(staffId,companyId,role,view,id,label){
  await fixtureRole(staffId,companyId,role);await R.open('/pages/management/index','.fin-content');const projectId=await mp.evaluate(id=>wx.getStorageSync('zhongshu-business-v3')?.customers?.find(c=>c.id===id)?.projectId,id),desk=(await R.data()).financeDesk,task=desk.tasks.find(t=>t.view===view&&(t.id===id||t.id.includes(id)||projectId&&t.projectId===projectId));
  check(label+' 自动出现且本人可办',!!task&&desk.previewTasks.some(t=>t.key===task.key));await capture(label+'-home');await event({selector:'.finance-todo',dataset:{url:task.url}},'financeGo');await R.until(async()=>(await R.data()).view===view,label+' original',15000);return task;
 }
 return {R,mp,report,persist,check,find,event,capture,login,submit,finance,admin,business,fixtureRole,fixtureFinance,key:(selector,key)=>({selector,dataset:{key}})};
}
module.exports={attach};
