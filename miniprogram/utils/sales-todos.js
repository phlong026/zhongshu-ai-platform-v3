// One projection of actionable and waiting business matters; IDs are stable on refresh.
const X=require('./access'),Job=require('./organization'),B=require('./business'),SF=require('./sales-flow');
function list(){
 const a=X.context();if(!a.active||!Job.has(a.role,'sales'))return {pending:[],waiting:[]};
 const rows=[],add=r=>rows.push({...r,key:r.id}),S=require('./studio').read(),G=require('./growth').load(),today=B.date();
 for(const t of S.tasks||[]){
  if(t.saved||(S.works||[]).some(w=>w.taskId===t.id))continue;
  if(['completed','save-failed'].includes(t.status))add({id:'generation:'+t.id,name:t.title,state:t.status==='save-failed'?'成品保存失败，待重试':'视频生成已完成，待保存成品',action:'保存成品',actionable:true,url:'/pages/studio/index?view=result&task='+encodeURIComponent(t.id)});
  else if(t.status==='generating')add({id:'generation:'+t.id,name:t.title,state:'视频生成中',action:'查看生成进度',actionable:false,url:'/pages/studio/index?view=tasks&task='+encodeURIComponent(t.id)});
 }
 for(const w of S.works||[]){const records=(S.records||[]).filter(r=>r.workId===w.id),open=records.find(r=>['publishing','scheduled'].includes(r.status));
  if(open)add({id:'publish:'+open.id,name:w.title,state:open.status==='scheduled'?'等待计划发布':'待登记真实发布链接',action:open.status==='scheduled'?'查看发布计划':'登记发布链接',actionable:open.status!=='scheduled',url:'/pages/studio/index?view=receipt&record='+encodeURIComponent(open.id)});
  else if(!records.some(r=>r.status==='published'))add({id:'work:'+w.id,name:w.title,state:'成品已保存，待发布',action:'发布作品',actionable:true,url:'/pages/studio/index?view=publish&work='+encodeURIComponent(w.id)});
 }
 for(const c of B.load().customers){
  if(!X.workflow('follow',c,true))continue;
  const f=(c.follows||[]).filter(f=>f.kind!=='capture'&&!String(f.note||'').startsWith('登记客资')).at(-1),day=f?.appointment?f.appointment.slice(0,10):'',due=!!day&&day<=today,visit=/到店/.test(f?.next||''),urgency=!due?4:day<today?1:visit?0:2;
  if(!c.acceptance&&(!c.request&&!c.budgets?.length&&!c.salesIntent?.ready||due))add({id:'follow:'+c.id,name:c.name,state:due?'约定回访已到期':f?'待完善客户需求':'待首次沟通',action:'跟进客户',actionable:true,urgency,tag:['今日到店','已逾期','今日回访'][urgency]||'',url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});
  else if(!c.request&&!c.budgets?.length&&c.salesIntent?.ready)add({id:'budget:'+c.id,name:c.name,state:'需求已明确，待方案预算',action:'方案与预算',actionable:true,url:'/pages/workflow/index?view=budget&id='+encodeURIComponent(c.id)});
  else if(!c.acceptance&&f?.appointment&&f.appointment.slice(0,10)>today)add({id:'appointment:'+c.id,name:c.name,state:'预约 '+f.appointment,owner:a.name,action:'查看预约',actionable:false,url:'/pages/workflow/index?view=customer&id='+encodeURIComponent(c.id)});
  if(c.request&&!SF.is(c)&&!c.acceptance){const n=require('./design-coordination').next(c);add({id:'legacy:'+c.id,customerId:c.id,name:c.name,state:'历史委托 · '+n.state,owner:n.owner,action:n.actionable?n.label:'查看进度',actionable:n.actionable,url:'/pages/workflow/index?view='+n.view+'&id='+encodeURIComponent(c.id)});}
 }
 for(const t of SF.tasks())add(t);
 // Published contents remain available for consultation registration; they do not create fictional clients.
 for(const c of G.contents||[])if(c.publishedUrl)add({id:'content:'+c.id,name:c.pack.title,state:'已发布，可登记真实咨询',owner:a.name,action:'内容跟踪',actionable:false,url:'/pages/growth/index?view=content&id='+encodeURIComponent(c.id)});
 const group=r=>/^(generation|work|publish|content):/.test(r.id)?'creation':r.kind==='commission'||/^legacy:/.test(r.id)?'commission':'customer';
 const unique=[...new Map(rows.map(r=>[r.id,r])).values()].map(r=>({...r,group:group(r),urgency:r.urgency??(!r.actionable?8:group(r)==='creation'?5:3)})).sort((x,y)=>x.urgency-y.urgency);return {pending:unique.filter(r=>r.actionable),waiting:unique.filter(r=>!r.actionable)};
}
module.exports={list};
