const X=require('./access'),O=require('./organization'),T=require('./intercompany'),C=require('./task-access'),P=require('./approval-policy');
const last=rows=>rows?.[rows.length-1];
const intakeReady=c=>c.intake?.checked===true&&c.intake.requestVersion===c.request?.version;
function current(c,a=X.context()){
 const result=(state,owner,label='',view='formal-progress',kind='wait')=>({state,owner,label,view,kind,actionable:!!label&&view!=='formal-progress'&&X.workflow(view,c,true,a)});
 if(!O.has(a.role,'hq-designer')||c.designOrganizationId!==a.companyId)return null;
 const lead=a.role==='hq-design-lead'&&X.designTeam(c,a),mine=c.assignment?.designerId===a.staffId,first=T.condition(c,'first'),d=last(c.deliverables),review=last((c.deliverableReviews||[]).filter(r=>r.version===d?.version)),feedback=last(c.designFeedbacks);
 if(!c.request||c.request.status==='returned')return result('待门店补齐设计需求','门店业务人员');
 if(!first.ready)return result('等待首款足额到账核对','总部财务');
 if(!c.assignment)return lead?result('首款已确认 · 待指定设计师','总部设计负责人','指定对接设计师','assign','assign'):result('待负责人指定对接设计师','总部设计负责人');
 if(!intakeReady(c))return lead?result('待核对设计需求','总部设计负责人','核对设计需求','intake','assign'):result('待负责人核对需求','总部设计负责人');
 const revision=!!(feedback&&d?.feedbackId!==feedback.id||review?.status==='returned');
 if(!d||revision){
  if(mine)return result(revision?'成果需修改':c.designStarted?'正在设计':'已指派 · 待开始设计',a.name,revision?'修改并提交新版本':c.designStarted?'继续设计并提交成果':'开始本次设计','deliverable',c.designStarted?'work':'start');
  if(C.allows(c,'design','design-part',a))return result('协作分项待提交',a.name,'提交本人设计分项','design-part','work');
  return result(c.designStarted?'设计师正在设计':'待对接设计师开始',c.assignment.designer);
 }
 if(!review){
  if(lead&&P.designReview(c,require('./business-rules-v2').authors(c),a))return result('本版成果待专业审核','总部设计负责人','审核本版设计成果','deliverable-review','review');
  return result('成果已提交 · 待独立审核','另一名总部设计负责人');
 }
 if(!c.release){
  if(!T.condition(c,'tail').ready)return result('审核已通过 · 待尾款核对','门店业务／财务、总部财务');
  if(C.allows(c,'design','release',a))return result('本版成果可正式下发',a.name,'下发本版正式成果','release','release');
  return result('待获授权设计人员正式下发',c.assignment.designer);
 }
 const drawing=last(c.drawings),drawingReview=last((c.reviews||[]).filter(r=>r.drawingVersion===drawing?.version));
 if(drawingReview?.status==='pending'&&drawingReview.reviewerId===a.staffId&&X.workflow('review-record',c,true,a)&&P.designReview(c,require('./business-rules-v2').authors(c),a))return result('施工图待专业审核',a.name,'审核本版施工图','review-record','review');
 if(mine&&!drawing)return result('正式成果已下发 · 待补齐施工图',a.name,'补齐项目施工图','documents','work');
 return result('正式成果已下发',c.assignment.designer,'','formal-progress','done');
}
function designers(c,a=X.context()){
 return (X.rawBook(c.designOrganizationId)?.staff||[]).filter(p=>p.active!==false&&O.staffHas(p,'hq-designer')&&a.managedTeamIds.includes(p.teamIds?.design||'design'));
}
function assign(c,f,date){
 if(c.assignment)throw Error('本项目已指定对接设计师，请查看当前设计任务。');
 if(!c.request||c.request.status==='returned'||!T.condition(c,'first').ready)throw Error('请等待本次设计首款足额到账核对，并补齐有效设计需求。');
 if(!intakeReady(c)&&!f.intakeChecked)throw Error('请先核对本版设计需求、现场资料及交付范围。');
 if(!designers(c).some(p=>p.name===f.designer))throw Error('请选择本团队在岗的总部设计师。');
 if(!require('./business').validDate(f.dueDate))throw Error('请选择有效的计划完成日期。');
 if(!intakeReady(c))c.intake={...c.intake,date,requestVersion:c.request.version,checked:true};
 c.assignment={designer:f.designer,date:f.dueDate,note:String(f.assignmentNote||'').trim(),requestVersion:c.request.version};
}
module.exports={current,designers,assign,intakeReady};
