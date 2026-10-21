const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const directory=path.resolve(process.env.BUSINESS_FLOW_NATIVE_OUT||'qa/business-flow-optimization-20261020/native');
process.env.FRANCHISE_NATIVE_OUT=directory;process.env.FRANCHISE_WS=process.env.FRANCHISE_WS||'ws://127.0.0.1:9430';
const steps=JSON.parse(fs.readFileSync(path.resolve(process.env.BUSINESS_FLOW_TRACE||'qa/business-flow-optimization-20261020/independent-trace.json'),'utf8')).steps;
const scenes=steps.filter(s=>['explore','workflow','admin','operations'].includes(s.area));
const end=scenes.findIndex(s=>new URL(s.url,'http://local').searchParams.get('view')==='start-check');scenes.splice(end+1);
const copy=x=>JSON.parse(JSON.stringify(x)),book=(rows,id)=>{const b=rows.find(r=>r[0]==='zhongshu-management-v1')?.[1];return b?.companyId===id?b:b?.books?.[id];};
const business=rows=>rows.find(r=>r[0]==='zhongshu-business-v3')?.[1];
const payments=rows=>[...new Map([...(book(rows,'quanzhou')?.payments||[]),...(business(rows)?.customers||[]).flatMap(c=>c.designPayments||[])].map(p=>[p.id,p])).values()];
const customerId=business(scenes[0].before).customers.find(c=>c.id.includes('from')).id;
const checks=[],screens=[],ids=new Map(),entries=[];
const translate=s=>{for(const [model,actual]of [...ids].sort((a,b)=>b[0].length-a[0].length))s=s.split(model).join(actual);return s;};
function summary(rows){const c=business(rows)?.customers.find(c=>c.id===customerId);if(!c)return null;return Object.fromEntries([...['budgets','quotes','serviceOrders','deliverables','deliverableReviews','drawings','reviews','designReceipts'].map(k=>[k,c[k]?.length||0]),['request',c.request?.version||0],['release',c.release?.version||0],['confirmation',c.confirmation?.version||0],['contract',c.contract?.quoteVersion||0],['handover',c.handover?.status||''],['acceptance',!!c.acceptance],['firstPaid',c.firstPayment?.amount||0],['tailPaid',c.tailPayment?.amount||0]]);}
(async()=>{const r=await require('./franchise-native-runtime.cjs').connect('business-continuous');
 async function shot(name){await r.mp.evaluate(()=>{wx.hideToast();return true;});await r.sleep(130);screens.push(await r.capture(name));}
 async function tap(selector,handler){await r.event(selector,handler);}
 async function navigate(url){for(let i=0;i<3;i++){try{return await r.open(url);}catch(e){if(i===2)throw e;await r.sleep(400);}}}
 try{
  const initial=copy(scenes[0].before);business(initial).customers.find(c=>c.id===customerId).name='陈先生';await r.mp.evaluate(()=>new Promise(resolve=>wx.reLaunch({url:'/pages/account/index?view=policy',success:()=>resolve(true)})));await r.until(async()=>(await r.current()).route==='pages/account/index','连续流程起点');await r.replace(initial);
  for(let i=0;i<scenes.length;i++){
   if(process.env.NATIVE_MAX_STEPS&&i>=Number(process.env.NATIVE_MAX_STEPS))break;
   const s=scenes[i],u=new URL(s.url,'http://local'),view=u.searchParams.get('view');if(u.searchParams.get('id'))u.searchParams.set('id',translate(u.searchParams.get('id')));
   const account=s.before.find(r=>r[0]==='zhongshu-account-v1')?.[1];await r.mp.evaluate(account=>{wx.setStorageSync('zhongshu-account-v1',account);return true;},account);
   const fromDesignDesk=process.env.BUSINESS_FLOW_FROM_WORKBENCH==='1'&&s.area==='workflow'&&['assign','deliverable','deliverable-review','release','documents','review-record'].includes(view);
   if(fromDesignDesk){
    await r.open('/pages/business/index','.design-desk');
    const task=(await r.data()).designHomeTasks.find(t=>t.id===customerId);
    assert(task&&new URL(task.url,'http://local').searchParams.get('view')===view,'总部岗位首页待办应进入 '+view+'，实际 '+JSON.stringify(task));
    await r.event({selector:'.design-home-action',dataset:{id:customerId}},'continueDesign');
    await r.until(async()=>{const p=await r.current();return p.route==='pages/workflow/index'&&p.data.view===view;},'从岗位待办进入 '+view,15000);
    entries.push({view,actor:s.actor.staffId,role:s.actor.role,action:task.action,result:'passed'});
   }else{const current=await r.current();if(!(s.area==='explore'&&current.route===u.pathname.slice(1)&&current.data.view===view))await navigate(u.pathname+u.search);}
   const before=await r.snapshot(),state=await r.data();assert.equal(state.view,view);
   const form={...s.form};for(const key of Object.keys(form))if(key.startsWith('__')||key==='expectedSignature')delete form[key];
   await r.mp.evaluate(({form,installments})=>{const p=getCurrentPages().at(-1);p.setData({form:{...p.data.form,...form},error:'',...(installments?{installments}:{})});return true;},{form,installments:s.installments});
   const prefix=String(i+1).padStart(2,'0')+'-'+(s.area==='explore'?s.handler:view);
   if(s.area!=='admin')await shot(prefix+'-before');
   if(s.area==='explore'){
    if(s.handler==='calculate')await tap('.calculate-explore','calculate');
    else if(s.handler==='save')await tap('.save-explore','save');
    else if(s.handler==='saveCustomer')await r.event({selector:'.explore-save-customer',dataset:{id:customerId}},'saveCustomer');
    else if(s.handler==='continueFormal')await tap('.explore-formal','continueFormal');
   }else if(s.area==='workflow'){
    if(s.handler==='saveModal')await tap('.sheet-save','saveModal');else if(['ai-config','facade-config','budget'].includes(view))await tap('.bottom-inline','submit');else await tap('.primary-action',view==='budget-result'?'continueBudget':'submit');
   }else if(s.area==='operations'){
    await r.event({selector:'.owner-primary',dataset:{action:s.action}},'perform');
   }else{
    assert((await r.data()).primary,'Missing financial primary action '+view);await tap('.page-actions .solid','submit');if((await r.data()).modal?.type==='confirm')await tap('.sheet .solid','confirm');
   }
   await r.sleep(200);if(s.resultRoute&&s.resultView)await r.until(async()=>{const p=await r.current();return p.route===s.resultRoute&&p.data.view===s.resultView;},'navigation after '+s.label,10000);const afterState=await r.data();assert(!afterState.error,prefix+': '+afterState.error);
   let after=await r.snapshot();
   // Only generated identifiers are translated; customer/project/business state is never replaced between steps.
   const modelNew=payments(s.after).filter(p=>!payments(s.before).some(o=>o.id===p.id));
   const actualNew=payments(after).filter(p=>!payments(before).some(o=>o.id===p.id));
   assert.equal(actualNew.length,modelNew.length,'new payment count '+view);modelNew.forEach((p,j)=>ids.set(p.id,actualNew[j].id));
   assert.deepEqual(summary(after),summary(s.after),'continuous state after '+s.label);
   if(['ai-config','facade-config'].includes(view)&&(await r.data()).modal){
    await tap('.sheet-save','saveModal');const next=scenes[i+1];if(next)await r.until(async()=>JSON.stringify(summary(await r.snapshot()))===JSON.stringify(summary(next.before)),'本地模拟方案生成并保存',15000);
   }
   if(s.area!=='admin'){await shot(prefix+'-after');await r.mp.evaluate(()=>{wx.pageScrollTo({scrollTop:10000,duration:0});return true;});await r.sleep(120);await shot(prefix+'-bottom');}
   checks.push({step:i+1,area:s.area,view,action:s.handler||s.action||'submit',label:s.label,actor:s.actor.staffId,role:s.actor.role,result:'passed'});console.log('PASS '+(i+1)+' '+s.label);
  }
  if(!process.env.NATIVE_MAX_STEPS){
   const final=await r.snapshot(),c=business(final).customers.find(c=>c.id===customerId);assert.equal(c.plans.length,0);assert.equal(c.contract.area,540);assert.equal(c.handover.basis.area,540);assert.equal(c.acceptance.handoverVersion,c.handover.version);assert(c.startChecks.length);assert.equal(c.handover.documents.length,7);
   fs.writeFileSync(path.join(directory,'continuous-final-state.json'),JSON.stringify(final,null,2));
  }
  assert.equal(r.errors.length,0,JSON.stringify(r.errors));fs.writeFileSync(path.join(directory,'results.json'),JSON.stringify({result:'passed',scope:'原生微信模拟器连续办理：仅开始时安装一份模拟客户/账号状态，之后业务、设计、财务和工程动作沿同一项目连续执行；跨岗切换模拟账号，示例凭据不涉及真实转账。',checks,entries,interactions:r.interactions,screens,exceptions:r.errors,complete:!process.env.NATIVE_MAX_STEPS},null,2));
 }catch(e){try{await shot('failure');fs.writeFileSync(path.join(directory,'failed.json'),JSON.stringify({error:e.stack,current:await r.current(),checks,exceptions:r.errors},null,2));fs.writeFileSync(path.join(directory,'failed-state.json'),JSON.stringify(await r.snapshot(),null,2));}catch(_){}throw e;}finally{await r.restore();
 }
})().then(()=>process.exit(0)).catch(e=>{console.error(e.stack);process.exit(1);});
