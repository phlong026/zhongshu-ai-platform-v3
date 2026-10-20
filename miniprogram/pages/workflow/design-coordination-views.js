const D=require('../../utils/design-coordination'),X=require('../../utils/access'),T=require('../../utils/intercompany');
const section=(title,items)=>({title,items}),kv=(label,value)=>({type:'kv',label,value:value||'—'}),notice=value=>({type:'notice',value}),links=values=>({type:'links',values}),file=(name,value)=>({type:'file',name:value?.name||name,file:value}),area=key=>({type:'textarea',key}),check=(label,key)=>({type:'check',label,key}),upload=(label,key)=>({type:'upload',label,key});
function decorate(view,c,f,out){
 const a=X.context(),sales=a.orgType!=='headquarters'&&!require('../../utils/organization').has(a.role,'hq-designer'),r=c.request,b=r?.basis||D.basis(c),n=D.next(c),received=D.receipt(c);
 if(view==='request'){
  const submitted=r&&r.status!=='returned';out.title='需求书与设计委托';out.badge=submitted?'委托已提交':'待提交设计委托';
  out.sections=[section('委托关联',[kv('客户',c.name),kv('项目',c.projectId),kv('设计委托',r?.commissionId||'提交后生成'),kv('参考预算',r?.budgetVersion?'V'+r.budgetVersion:c.budgets?.length?'V'+c.budgets[c.budgets.length-1].version:'未关联预算'),notice('设计委托独立办理，设计确认后再核算建造报价及签署建造合同。')]),section('设计依据',[kv('资料来源','预算参数／客户提供，现场条件仍需核实'),kv('面宽／进深',b?b.width+'m × '+b.depth+'m':'待完善'),kv('层数／建筑面积',b?b.floors+'层 · '+b.area+'㎡':'待完善')]),section('设计需求',submitted?[notice(r.text),kv('交付范围',r.scope||'按原服务约定')]:[area('requestText'),{type:'input',label:'设计交付范围',key:'requestScope',placeholder:'填写平面、效果图、施工图等本次约定范围'},upload('补充现场及需求资料','requestAttachment'),check('已核对需求、尺寸、资料来源及本次交付范围','designBasisChecked')]),section('后续办理',[notice('提交后由业务负责人核定设计服务费，老板审批金额，财务办理付款；总部核对首款后受理和派单。')])];
  if(r?.attachment)out.sections.push(section('已提交附件',[file('需求附件',r.attachment)]));
  const returned=(c.returns||[]).filter(x=>x.view==='intake'&&x.status==='pending');if(returned.length)out.sections.unshift(section('总部退回原因',returned.map(x=>notice(x.reason))));
  out.primary=submitted?'查看设计协同':'提交设计委托';out.secondary='';
 }
 if(view==='intake'){
  out.sections=[section('受理条件',[kv('委托版本',r?'V'+r.version:'未提交'),kv('设计首款',T.condition(c,'first').text),notice('核对本次设计需求及现场依据；建造合同在设计确认后签署。')]),section('需求与交付范围',[notice(r?.text||''),kv('交付范围',r?.scope||'按原设计约定'),kv('资料来源','预算参数／客户提供'),...(r?.attachment?[file('需求附件',r.attachment)]:[]),check('已核对本版需求、尺寸及约定设计资料完整可读','intakeChecked')])];
 }
 if(view==='design-receipt'){
  out.title='门店收图确认';out.badge=received?'本版已收图':'待核查收图';out.sections=[section('本次正式下发',[kv('成果版本',c.release?'V'+c.release.version:'尚未下发'),kv('下发日期',c.release?.date),...(c.release?.files?[file('正式设计文件',c.release.files)]:[]),notice('核对文件完整和可读取后登记收图；客户成果签认另行办理。')]),section('收图记录',received?[kv('确认日期',received.date),notice(received.note||'本版文件完整且可读取')]:[check('本版正式设计文件已收齐且可读取','designReceiveChecked'),area('designReceiveNote')])];out.primary=received?'继续客户成果签认':'确认收到本版图纸';out.secondary='';
 }
 if(view==='design-feedback'){
  out.title='设计修改意见';out.sections=[section('本版成果',[kv('正式下发版本',c.release?'V'+c.release.version:'尚未下发'),...(c.release?.files?[file('正式设计文件',c.release.files)]:[]),notice('修改意见沿原委托回到设计师；新版本重新审核、下发和确认，已核对款项保留。新增收费须另核对服务约定。')]),section('修改意见',[area('designFeedbackNote'),upload('补充修改说明','designFeedbackFile')])];out.primary='提交本版修改意见';out.secondary='';
 }
 if(['delivery','hub'].includes(view)&&sales){
  const customerView=out.sections.filter(s=>s.title==='客户视角 · 这一单走到哪了');
  out.title='设计协同';out.sections=customerView.concat([section('当前办理',[notice(n.state),kv('当前责任',n.owner),notice('设计委托 → 服务费及首款 → 总部设计审核 → 尾款及下发 → 收图与客户签认 → 建造报价合同')]),section('需求与费用',[links([{title:'需求书与设计委托',view:'request'},{title:'设计服务费及约定',view:'service-order'},{title:'设计首款进度',view:'payment-first'},{title:'设计尾款进度',view:'payment-tail'}])]),section('设计与成果',[links([{title:'总部设计进度',view:'formal-progress'},{title:'申请设计尾款',view:'tail-apply'},...(D.first(c)?[{title:'门店收图确认',view:'design-receipt'}]:[]),{title:'客户成果签认',view:'confirmation'},...(c.release&&!c.confirmation?[{title:'提出本版修改意见',view:'design-feedback'}]:[])])]),section('建造与工程',[links([{title:'建造报价',view:'quote'},{title:'建造合同与资金',view:'contract'},{title:'施工图及审核记录',view:'drawing-review'},{title:'工程移交',view:'handover'}])])]);
  out.primary=n.label;out.secondary='';
 }
 if(view==='formal-progress'&&sales){out.sections.unshift(section('设计协同进度',[notice(n.state),kv('当前责任',n.owner),kv('设计师',c.assignment?.designer),kv('计划完成',c.assignment?.date),links([{title:'返回设计协同',view:'delivery'},...(c.release?[{title:'查看正式下发成果',view:D.first(c)?'design-receipt':'confirmation'}]:[])])]));out.primary=n.view==='formal-progress'?'返回设计协同':n.label;}
 if(['formal-progress','deliverable','delivery'].includes(view)&&(c.designFeedbacks||[]).length){const feedback=c.designFeedbacks[c.designFeedbacks.length-1];out.sections.push(section('本轮修改意见',[kv('对应成果','V'+feedback.version),notice(feedback.note),...(feedback.file?[file('修改说明',feedback.file)]:[])]));}
 if(view==='confirmation'&&sales){if(!c.confirmation&&D.first(c)&&c.release)out.sections[0]=section('正式成果 · V'+c.release.version,[file('本版正式设计成果',c.release.files),kv('关联需求','V'+c.request.version),notice('请核对本次正式下发文件，客户签认绑定此版本。')]);if(c.confirmation){out.primary=c.contract?'返回设计协同':'继续建造报价';}else if(c.release)out.sections.push(section('本版反馈',[links([{title:'提出设计修改意见',view:'design-feedback'}])]));}
 if(view==='handover'&&c.handover&&c.handover.status!=='returned'){out.primary=c.acceptance?'查看工程接收回执':'返回设计协同';out.secondary='';}
 if(view==='contract'){out.primary='返回设计协同';out.sections=out.sections.map(s=>({...s,items:s.items.map(i=>i.type==='notice'&&i.value==='已签文件归档后可提交正式设计需求。'?notice('建造合同关联已确认的设计成果，资料齐备后继续工程移交。'):i)}));}
 if(view==='drawing-review'&&!c.contract&&D.confirmed(c))out.primary='返回设计协同';
 if(view==='service-order'&&!c.serviceOrders?.length&&!X.workflow('service-prepare',c,true)){out.primary='返回设计协同';out.sections.push(section('核定责任',[notice('设计服务费由业务负责人核定，老板审批。核定完成后在此查看首尾款办理进度。')]));}
}
module.exports={decorate};
