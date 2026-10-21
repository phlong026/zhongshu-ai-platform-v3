const X=require('../../utils/access'),O=require('../../utils/organization'),T=require('../../utils/intercompany'),Task=require('../../utils/design-tasks');
const s=(title,items)=>({title,items}),kv=(label,value)=>({type:'kv',label,value:value||'—'}),notice=value=>({type:'notice',value}),links=values=>({type:'links',values});
const file=value=>({type:'file',key:'storedDesignFile',name:value.name,file:value}),upload=(label,key)=>({type:'upload',label,key});
function demand(c){return s('本次设计需求',[kv('委托版本',c.request?'V'+c.request.version:'尚未提交'),notice(c.request?.text||'等待门店补齐'),kv('交付范围',c.request?.scope||'按原设计约定'),...(c.request?.attachment?[file(c.request.attachment)]:[]),links([{title:'查看完整需求与依据',view:'request'}])]);}
function decorate(view,c,f,out){
 const a=X.context();if(!O.has(a.role,'hq-designer')||c.designOrganizationId!==a.companyId)return;
 const task=Task.current(c,a),d=c.deliverables?.at(-1),review=c.deliverableReviews?.filter(r=>r.version===d?.version).at(-1),first=T.condition(c,'first');
 const funding=s('财务到账反馈',[kv('设计首款',first.ready?'总部财务已确认，正式设计条件已满足':'待财务完成本期足额到账核对'),notice(first.ready?'本项目已进入正式设计，由设计负责人指定对接设计师。':'登记凭据或部分到账不代表本期核对完成。足额核对后开放设计指派和开始操作。')]);
 if(['formal-progress','delivery','hub'].includes(view)){
  out.title='正式设计';out.badge=task.state;out.taskSummary={state:task.state,owner:task.owner};out.primary=task.actionable?task.label:'返回工作台';out.primaryView=task.actionable?task.view:'';out.primaryUrl='';out.secondary='';
  const stages=[['总部财务确认到账',first.ready,''],['指定对接设计师',!!c.assignment,'assign'],['设计师开始设计',!!c.designStarted,'deliverable'],['提交本版正式成果',!!d,'deliverable'],['设计负责人专业审核',review?.status==='passed','deliverable-review'],['正式成果下发',!!c.release,'release']];
  const firstPending=stages.findIndex(x=>!x[1]);
  out.sections=[funding,s('项目设计对接',[kv('对接设计师',c.assignment?.designer||'待总部设计负责人指定'),kv('计划完成',c.assignment?.date),...(c.assignment?.note?[notice(c.assignment.note)]:[])]),s('设计办理进度',[{type:'checklist',values:stages.map(([title,done,target],i)=>({title,state:done?'done':i===firstPending?'current':'todo',meta:done?'已完成':i===firstPending?task.state:'待前序完成',view:target&&target===task.view&&task.actionable?target:'',action:task.label}))}]),demand(c)];
  if(d)out.sections.splice(2,0,s('本版正式成果',[kv('成果版本','V'+d.version),file(d.file),notice(d.note),kv('审核状态',review?.status==='passed'?'审核通过':review?.status==='returned'?'退回修改':'待独立审核'),...(review?[notice(review.note),...(review.file?[file(review.file)]:[])]:[])]));
  if(review?.status==='passed')out.sections.push(s('加盟商尾款与下发衔接',[kv('业务尾款申请',c.tailApplication?'已提交':'待加盟商业务发起'),kv('尾款核对',T.condition(c,'tail').text),notice(c.release?'本版图纸已正式下发，加盟商业务继续核查收图。':T.condition(c,'tail').ready?'尾款已足额核对，由获授权设计人员正式下发。':'审核通过后，加盟商业务首页显示尾款申请待办；老板批准后由财务登记及核对，款项齐备再正式下发。')]));
  const feedback=c.designFeedbacks?.at(-1);if(feedback&&!c.release)out.sections.splice(1,0,s('本轮修改意见',[notice(feedback.note),...(feedback.file?[file(feedback.file)]:[])]));
 }
 if(view==='assign'){
  out.title='指定对接设计师';out.primaryView='';out.secondary='';out.badge=c.assignment?'已指定对接设计师':'待负责人指派';
  out.sections=[funding,demand(c),s('指定项目对接',[kv('办理负责人',a.name),...(c.assignment?[kv('对接设计师',c.assignment.designer),kv('计划完成',c.assignment.date),notice(c.assignment.note||'按本次委托要求对接')]:[{type:'input',label:'对接设计师',key:'designer',placeholder:'请选择本团队设计师'},{type:'input',kind:'date',label:'计划完成',key:'dueDate',placeholder:'请选择日期'},{type:'textarea',label:'对接说明（选填）',key:'assignmentNote',placeholder:'交接客户重点、沟通要求及需关注的现场条件'},...(!Task.intakeReady(c)?[{type:'check',label:'已核对本版需求、现场资料和交付范围',key:'intakeChecked'}]:[])])])];
  out.primary=c.assignment?'查看正式设计任务':'确认指派对接设计师';if(c.assignment)out.primaryView='formal-progress';
 }
 if(view==='deliverable'){
  out.primaryView='';out.secondary='';out.badge=!c.designStarted?'待开始设计':review?.status==='returned'?'退回修改':'设计中';
  if(!c.designStarted){out.title='开始项目设计';out.sections=[s('本人对接项目',[kv('对接设计师',c.assignment?.designer),kv('计划完成',c.assignment?.date),notice(c.assignment?.note||'按本次委托需求开展设计')]),demand(c),s('开始设计',[notice('确认开始后记录实际经办人和开始时间，进入成果编辑与提交。')])];out.primary='开始本次设计';}
  else {out.title='提交设计成果';out.sections=[s('本次正式成果',[kv('提交版本','V'+((c.deliverables?.length||0)+1)),kv('计划完成',c.assignment?.date),upload('选择本版设计成果','deliverableFile'),{type:'textarea',key:'deliverableNote',label:'本版成果说明',placeholder:'说明交付内容、满足的需求及需要核对的事项'}]),demand(c)];if(review?.status==='returned')out.sections.unshift(s('负责人退回意见',[notice(review.note)]));out.primary='提交本版成果审核';}
 }
 if(view==='deliverable-review'){
  out.title='审核设计成果';out.sections.unshift(s('本次审核文件',[kv('本版成果',d?'V'+d.version:'尚未提交'),...(d?.file?[file(d.file)]:[]),kv('对接设计师',c.assignment?.designer)]));
 }
}
module.exports={decorate};
