const E=require('./model'),B=require('../../utils/business'),X=require('../../utils/access'),N=require('../../utils/organization'),V=require('../../utils/page-visibility');
const image='/pages/build/assets/house.jpg';
const TaskOrder=require('../../utils/task-order');
function legacyTaskTime(p,key,view,extra){
 const time=r=>r?.at||r?.date||'',base=p.acceptedAt||p.accepted||'';
 const supply=p.supplies.find(r=>r.id===extra.supplyId),receipt=p.receipts.find(r=>r.id===extra.receiptId);
 if(key.startsWith('finance:')){
  const row=p.supplies.find(r=>r.id===key.slice(8));
  return TaskOrder.latest(row?.financeReturnedAt,row?.returnedAt,row?.termsAt,row?.financeReadyAt,row?.at)||base;
 }
 if(key.startsWith('collection:')){
  const row=p.collections.find(r=>r.id===extra.collectionId);
  return row?.returnedAt||row?.financeReturnedAt||time(row)||base;
 }
 if(view==='inspection-review')return time(extra.rectificationId?p.rectifications.find(r=>r.id===extra.rectificationId):p.inspections.find(r=>r.id===extra.inspectionId))||base;
 if(view==='archive')return time(p.delivery)||base;
 if(view==='prepare'||view==='start')return TaskOrder.latest(base,...(p.checks||[]).map(time),time(p.review),p.plan.compiledAt);
 if(view==='receive')return supply?.at||base;
 if(view==='quality'||view==='reship')return time(receipt)||base;
 if(view==='reship-receive')return time(p.reships.find(r=>r.id===extra.reshipId))||base;
 const node=p.plan.nodes.find(n=>n.id===extra.nodeId),inspection=p.inspections.filter(r=>r.nodeId===node?.id).at(-1);
 if(view==='rectify')return time(inspection?.review)||time(inspection)||base;
 if(view==='delivery')return TaskOrder.latest(...p.plan.nodes.map(n=>n.completedRecordedAt||n.completedAt))||base;
 const previous=p.plan.nodes[p.plan.nodes.indexOf(node)-1];
 return TaskOrder.latest(time(inspection?.review),time(inspection),previous?.completedRecordedAt||previous?.completedAt,time(p.actualStart))||base;
}
const allowed=()=>{const a=X.context();return a.active&&(N.has(a.role,'engineer')||X.isBoss(a));};
const build=(view,p,extra={})=>p.recordFlow?require('../../utils/engineering-records').link(p,({schedule:'project',funds:'funding',inspection:'node',site:'node',materials:'materials'})[view]||view,extra):'/pages/build/index?view='+view+'&id='+encodeURIComponent(p.id)+Object.entries(extra).filter(([,v])=>v!==undefined&&v!=='').map(([k,v])=>'&'+k+'='+encodeURIComponent(v)).join('');
const business=(view,c)=>'/pages/workflow/index?view='+view+'&id='+encodeURIComponent(c.id);
const stage=p=>p.archived?'已归档':p.delivery?'已交付':!p.actualStart?'待开工':p.plan.nodes.every(n=>n.status==='已完成')?'待交付':'在建';
function current(p){return p.plan.nodes.find(n=>n.status!=='已完成')||p.plan.nodes.at(-1);}
function card(p){const RR=require('../../utils/engineering-records');const n=current(p),done=p.plan.nodes.filter(n=>n.status==='已完成').length,pace=p.recordFlow&&n?RR.progress(p).rows.find(r=>r.id===n.id)?.text||'':'';return {id:p.id,name:p.name,customer:p.customer,city:p.city,area:p.area,floors:p.floors,manager:p.manager,image:p.image||image,status:stage(p),stage:p.delivery?'已交付':!p.actualStart?'开工准备':n?.name||'施工节点待编制',progress:p.plan.nodes.length?Math.round(done/p.plan.nodes.length*100):0,date:n?.end||'',pace,overdue:p.recordFlow&&p.actualStart&&!p.delivery?RR.progress(p).overdue:0,next:!p.actualStart?'核查开工条件':p.delivery?'交付资料与款项收尾':p.plan.nodes.every(n=>n.status==='已完成')?'整理竣工资料并办理交付':E.inspectPassed(p,n.id)?'材料核对与节点完成':'现场检查与节点验收',url:build('overview',p),sample:!!p.sample,canFunds:X.engineeringExecutor(p)||['project-manager','boss','owner'].includes(X.context().role)};}
function incoming(c){const SF=require('../../utils/sales-flow'),canReceive=SF.is(c)?SF.next(c).view==='receive'&&SF.allowed('packet-receive',c):X.workflow('receive',c,true);return {id:c.id,name:c.name+' · '+c.city+'乡墅',city:c.city,area:require('../../utils/project-records').basis(c).area||'',drawingVersion:c.handover.drawingVersion||'',date:c.handover.date||'',triggeredAt:SF.triggeredAt(c,'receive'),url:business('receive',c),canReceive,action:canReceive?'核对接收':'查看移交',sample:!!c.sample};}
function snapshot(){if(!allowed())return {projects:[],incoming:[],tasks:[]};E.sync();const projects=E.load().projects,customers=B.load().customers,incomingRows=customers.filter(c=>c.handover&&!c.acceptance&&c.handover.status==='pending'&&X.customer(c)),tasks=[];
 const add=(p,key,title,button,view,extra={},date='')=>tasks.push({id:p.id+':'+key,projectId:p.id,name:p.name,city:p.city,title,button,url:build(view,p,extra),image:card(p).image,date,triggeredAt:legacyTaskTime(p,key,view,extra),sample:!!p.sample});
 for(const c of incomingRows){const row=incoming(c);if(!row.canReceive)continue;tasks.push({...row,id:'handover:'+c.id,projectId:c.projectId,title:'业务移交待接收',button:row.action,image,date:row.date});}
 const waiting=[];for(const p of projects){if(p.recordFlow){const t=require('../../utils/engineering-records').tasks(p);tasks.push(...t.pending);waiting.push(...t.waiting);continue;}
  for(const s of p.supplies.filter(s=>!s.sourceDepotId&&(!s.unitPrice||!s.basis||!s.payer||s.financeStatus==='需补充')))if(V.engineering(p,'supply-complete'))add(p,'finance:'+s.id,'原材料付款约定待完善','补充原供应','supply-payment',{supplyId:s.id,materialId:s.materialId});
  if(p.archived){for(const r of p.collections.filter(r=>r.status==='退回补充'))if(V.engineering(p,'collect'))add(p,'collection:'+r.id,'工程收款凭据退回补充','补充原单','collection',{editRecord:r.id,collectionId:r.id});continue;}
  const reviews=p.inspections.concat(p.rectifications).filter(r=>r.reviewStatus==='pending'&&r.reviewerId===X.context().staffId);
  for(const r of reviews)if(V.engineering(p,'inspection-review'))add(p,r.id,'阶段'+(r.inspectionId?'复验':'验收')+'待负责人审核','去审核','inspection-review',{nodeId:r.nodeId||p.inspections.find(i=>i.id===r.inspectionId)?.nodeId,inspectionId:r.inspectionId||r.id,rectificationId:r.inspectionId?r.id:''},r.reviewDate||r.date);
  if(p.delivery){if(V.engineering(p,'archive'))add(p,'archive','交付资料待归档','去归档','archive');continue;}
  if(!p.actualStart){if(V.engineering(p,'prepare'))add(p,'prepare',E.ready(p)?'开工条件已齐备，待登记实际开工':'开工条件待核查',E.ready(p)?'登记开工':'开工准备',E.ready(p)?'start':'prepare');}
  else{const n=current(p);if(n){const inspection=p.inspections.filter(i=>i.nodeId===n.id).at(-1);if(inspection?.reviewStatus==='pending'){}else if(inspection?.result==='需整改'&&!E.inspectPassed(p,n.id)){if(V.engineering(p,'rectify'))add(p,'rectify:'+n.id,n.name+'待整改复验','去整改','rectify',{nodeId:n.id,inspectionId:inspection.id});}else if(n.status!=='已完成'&&V.engineering(p,'inspect'))add(p,'node:'+n.id,n.name+(E.inspectPassed(p,n.id)?'待确认完成':'待检查验收'),E.inspectPassed(p,n.id)?'查看节点':'去验收',E.inspectPassed(p,n.id)?'node':'inspection',{nodeId:n.id});}
   if(p.plan.nodes.length&&p.plan.nodes.every(n=>n.status==='已完成')&&V.engineering(p,'deliver'))add(p,'delivery','施工完成，待竣工交付','去交付','delivery');}
  for(const s of p.supplies){const m=p.materials.find(m=>m.id===s.materialId),r=p.receipts.find(r=>r.supplyId===s.id);if(!r&&V.engineering(p,'receive'))add(p,'supply:'+s.id,(m?.name||'材料')+'待收货','去收货','receive',{materialId:m?.id,supplyId:s.id},s.date);if(r){const reship=p.reships.find(x=>x.receiptId===r.id);if(r.quality==='不合格'&&!r.qualityResolution&&V.engineering(p,'quality'))add(p,'quality:'+r.id,'材料质量异常待处置','去处理','quality',{receiptId:r.id,supplyId:s.id,materialId:m?.id});if(r.actual<r.expected&&(!reship||!reship.received)&&V.engineering(p,reship?'reship-receive':'reship'))add(p,'difference:'+r.id,(m?.name||'材料')+'差异待补齐',reship?'补发收货':'安排补发',reship?'reship-receive':'reship',{receiptId:r.id,reshipId:reship?.id,supplyId:s.id,materialId:m?.id});}}
  for(const r of p.collections.filter(r=>r.status==='退回补充'))if(V.engineering(p,'collect'))add(p,'collection:'+r.id,'工程收款凭据退回补充','补充原单','collection',{editRecord:r.id,collectionId:r.id});
 }
 return {projects,incoming:TaskOrder.newest(incomingRows.map(incoming)),tasks:TaskOrder.newest(tasks),waiting:TaskOrder.newest(waiting)};}
function home(){const account=require('../../utils/account').load(),s=snapshot(),a=X.context(account),cards=s.projects.map(card),active=cards.filter(p=>p.status==='在建'),pending=s.tasks.filter(t=>/验收|复验|整改/.test(t.title));const date=B.date(),d=new Date(date+'T12:00:00');return {...s,cards,active,stats:[{label:'在建项目',value:active.length,filter:'在建'},{label:'逾期节点',value:cards.reduce((m,c)=>m+(c.overdue||0),0),filter:'在建'},{label:'待验收',value:pending.length,filter:'待验收'},{label:'待接收',value:s.incoming.length,filter:'待接收'}],name:a.name||'工程同事',date:date.slice(5,7)+'月'+date.slice(8)+'日 周'+'日一二三四五六'[d.getDay()],previewTasks:s.tasks.slice(0,2),sample:!!account.session.demo,recent:cards.find(p=>p.id===wx.getStorageSync(require('../../utils/storage-keys').engineeringRecentPrefix+a.companyId+':'+a.staffId))||active[0]||cards[0]||null};}
function remember(id){const a=X.context();if(E.get(id))wx.setStorageSync(require('../../utils/storage-keys').engineeringRecentPrefix+a.companyId+':'+a.staffId,id);}
module.exports={allowed,snapshot,home,card,build,business,stage,current,remember};
