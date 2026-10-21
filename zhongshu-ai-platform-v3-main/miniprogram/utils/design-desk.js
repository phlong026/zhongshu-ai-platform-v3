const X=require('./access'),B=require('./business'),O=require('./organization'),Task=require('./design-tasks'),R=require('./project-records'),T=require('./intercompany');
const allowed=(a=X.context())=>a.active&&a.orgType==='headquarters'&&O.has(a.role,'hq-designer');
const url=(id,view='formal-progress')=>'/pages/workflow/index?view='+view+'&id='+encodeURIComponent(id);
const engineering=(a=X.context())=>a.role==='hq-design-lead'?((wx.getStorageSync('zhongshu-engineering-v1')||{}).projects||[]).filter(p=>X.project(p,a)&&p.review):[];
function phase(c){const d=c.deliverables?.at(-1),r=(c.deliverableReviews||[]).filter(r=>r.version===d?.version).at(-1),f=c.designFeedbacks?.at(-1);if(!c.request||c.request.status==='returned'||!T.condition(c,'first').ready)return 'wait';if(!c.assignment||!Task.intakeReady(c))return 'assign';if(!d||f&&d.feedbackId!==f.id||r?.status==='returned')return c.designStarted?'work':'start';if(!r)return 'review';if(!c.release)return T.condition(c,'tail').ready?'release':'wait';const drawing=c.drawings?.at(-1),review=(c.reviews||[]).filter(r=>r.drawingVersion===drawing?.version).at(-1);return review?.status==='pending'?'review':!drawing?'work':'done';}
function projects(){
 const a=X.context();if(!allowed(a))return [];
 const rows=B.load().customers.map(c=>{const t=Task.current(c,a),b=R.basis(c);return {id:c.id,name:c.name,city:c.city||'地点待补充',basis:[b.floors?b.floors+'层':'',b.area?b.area+'㎡':''].filter(Boolean).join(' · '),state:t.state,owner:t.owner,kind:t.kind,phase:phase(c),actionable:t.actionable,action:t.actionable?t.label:'查看设计进度',url:url(c.id,t.actionable?t.view:'formal-progress'),detailUrl:url(c.id),designer:c.assignment?.designer||'待指定',dueDate:c.assignment?.date||'',version:c.request?'需求 V'+c.request.version:'需求待补齐',note:c.assignment?.note||c.request?.scope||'按本次委托需求办理'};});
 for(const p of engineering(a)){const actionable=require('./page-visibility').engineering(p,'review',a),pending=p.review.status==='pending',target='/pages/build/index?view=review&id='+encodeURIComponent(p.id);rows.push({id:'engineering:'+p.id,name:p.name,city:p.city||'地点待补充',basis:[p.floors?p.floors+'层':'',p.area?p.area+'㎡':''].filter(Boolean).join(' · '),state:pending?'工程图纸待专业审核':p.review.status==='passed'?'工程图纸审核通过':'工程图纸退回修改',owner:actionable?a.name:pending?'独立专业审核人':p.review.status==='returned'?'原提交人员':'审核已完成',kind:actionable?'review':pending?'wait':'done',phase:pending?'review':p.review.status==='passed'?'done':'wait',actionable,action:actionable?'审核本版工程图纸':'查看工程审图记录',url:target,detailUrl:target,designerLabel:'审核人',designer:a.name,dueDate:'',dueLabel:'按工程送审原单'});}
 return rows.sort((a,b)=>Number(b.actionable)-Number(a.actionable)||(a.dueDate||'9999').localeCompare(b.dueDate||'9999'));
}
function files(){
 if(!allowed())return [];const rows=[];
 for(const c of B.load().customers){
  const add=(key,kind,label,version,file,status,date,current)=>{if(!file)return;rows.push({key:c.id+':'+key,id:c.id,project:c.name,kind,label,version:String(version||''),name:file.name||label,status,date:date||'',current:!!current,sample:!!file.sample,file,detailUrl:url(c.id)});};
  const ds=c.deliverables||[],drawings=c.drawings||[];
  for(const d of ds){const review=(c.deliverableReviews||[]).filter(r=>r.version===d.version).at(-1);add('deliverable:'+d.version,'design','设计成果','V'+d.version,d.file,review?.status==='passed'?'审核通过':review?.status==='returned'?'退回修改':'待审核',d.date,d===ds.at(-1));if(review)add('review:'+d.version,'review','成果审核','V'+d.version,review.file,review.status==='passed'?'审核通过':'退回修改',review.date,d===ds.at(-1));}
  if(c.release?.files)add('release:'+c.release.version,'release','正式下发成果','V'+c.release.version,c.release.files,'已正式下发',c.release.date,true);
  for(const d of drawings){const review=(c.reviews||[]).filter(r=>r.drawingVersion===d.version).at(-1);add('drawing:'+d.version,'drawing','施工图',d.version,d.file,review?.status==='passed'?'审核通过':review?.status==='returned'?'退回修改':review?'待审核':'待送审',d.date,d===drawings.at(-1));if(review)add('drawing-review:'+d.version,'review','施工图审核',d.version,review.report,review.status==='passed'?'审核通过':review.status==='returned'?'退回修改':'待审核',review.date,d===drawings.at(-1));}
 }
 for(const p of engineering()){const add=(key,kind,label,file)=>{if(file)rows.push({key:'engineering:'+p.id+':'+key,id:'engineering:'+p.id,project:p.name,kind,label,version:p.drawingVersion||'',name:file.name||label,status:p.review.status==='passed'?'审核通过':p.review.status==='returned'?'退回修改':'待审核',date:p.review.date||'',current:true,sample:!!file.sample,file,detailUrl:'/pages/build/index?view=review&id='+encodeURIComponent(p.id)});};add('drawing','drawing','工程施工图',p.drawing);add('review','review','工程图纸审核',p.review.file);}
 return rows.reverse();
}
function home(){
 const a=X.context(),rows=projects(),lead=a.role==='hq-design-lead',actions=rows.filter(r=>r.actionable),counts=id=>rows.filter(r=>r.kind===id).length;
 const metrics=(lead?[['assign','待指派',counts('assign')],['review','待我审核',counts('review')],['all','团队项目',rows.length]]:[['start','待开始',counts('start')],['work','设计中',counts('work')],['all','本人项目',rows.length]]).map(([id,label,count])=>({id,label,count}));
 return {isHqDesign:allowed(a),designName:a.name,designRole:lead?'总部设计负责人':'总部设计师',designDescription:lead?'先指派对接，再审核本版成果':'从本人任务开始，按版本提交成果',designMetrics:metrics,designPending:actions.length,designHomeTasks:actions.slice(0,3),designWaitCount:rows.filter(r=>r.kind==='wait').length,designRecent:files().filter(f=>f.current).slice(0,2)};
}
module.exports={allowed,projects,files,home,url};
