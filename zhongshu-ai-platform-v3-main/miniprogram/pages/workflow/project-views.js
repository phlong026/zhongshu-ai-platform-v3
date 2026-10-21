const B=require('../../utils/business'),X=require('../../utils/access'),Job=require('../../utils/organization');
const R=require('../../utils/project-records'),D=require('../../utils/design-coordination'),M=require('../../utils/commercial-approval');
const section=(title,items,extra={})=>({title,items,...extra});
const kv=(label,value)=>({type:'kv',label,value:value??'待补齐'});
const notice=value=>({type:'notice',value});
const links=values=>({type:'links',values});
const field=(label,key,placeholder,extra={})=>({type:'input',label,key,placeholder,...extra});
const area=(label,key,placeholder)=>({type:'textarea',label,key,placeholder});
const money=n=>'￥'+B.money(n);
const date=value=>String(value||'').replace('T',' ').replace(/\.\d+Z$/,'').slice(0,16);
const short=id=>String(id||'').length>24?String(id).slice(0,8)+'…'+String(id).slice(-8):id;
const hints={requestText:['设计需求','说明各层功能、家庭成员与现场条件'],serviceBasis:['费用核定依据','填写服务范围、约定总额及首尾款安排'],designReceiveNote:['收图备注（选填）','可补充文件缺项或核查情况'],designFeedbackNote:['本版修改意见','说明需要修改的位置和预期结果'],handoverNote:['电子交底与现场重点','现场条件、施工重点、客户约定、待协调事项'],tailApplyNote:['尾款申请说明','说明本版成果及申请依据'],startNote:['核查记录','说明现场核查情况及待协调事项'],reviewSubmitNote:['送审说明','说明图纸范围及需要重点核查的内容'],reviewOpinion:['审核意见','填写本次审核结论及依据'],moneyNote:['审批意见','填写金额依据及批准或退回原因'],deliverableNote:['成果说明','说明本次交付内容和对应需求']};
function packet(c,snapshot=false){
 const rows=snapshot&&c.handover?.documents?c.handover.documents:R.packageRows(c);
 return {type:'record-list',values:rows.map(row=>({title:row.label+(row.version?' · '+row.version:''),subtitle:row.summary||'',badge:row.ready?'已齐备':'待补齐',view:row.view&&X.workflow(row.view,c,false)?row.view:'',files:row.file?[row.file]:[]}))};
}
function quote(c,f,out){
 const q=B.latest(c.quotes),b=R.quoteBudget(c),approval=M.latest(c,'quote'),stale=q&&D.first(c)&&!D.quoteCurrent(c);
 const editable=(!q||approval?.status==='returned'||stale)&&!c.contract&&X.workflow('quote',c,true);
 out.title='建造报价';out.secondary='';out.badge=q?({pending:'待老板审批',returned:'老板已退回',approved:'老板已批准'})[approval?.status]||'待审批':'待生成';
 const basis=[kv('参考预算',b?'V'+b.version+' · '+money(b.total):'待保存'),kv('计价面积',b?b.area+'㎡':'待补齐'),kv('已确认设计',c.confirmation?'V'+c.confirmation.version:'待确认')];
 if(editable){
  out.sections=[section(q?'修订报价草稿':'建造报价草稿',[...basis,notice('当前为可编辑草稿；生成后提交老板审批，通过后用于建造合同。')]),section('分项金额',[field('主体工程','quoteMain','填写主体工程总额',{unit:'元',inputType:'digit'}),field('外装工程','quoteExterior','填写外装工程总额',{unit:'元',inputType:'digit'}),field('其他约定','quoteOther','无其他项目填0',{unit:'元',inputType:'digit'})]),section('计价范围与调整依据',[area('报价范围','quoteScope','填写包含与不包含的工程范围'),area('差异说明（金额调整时必填）','quoteReason','说明相对参考预算增加或减少的项目与原因')])];
  if(approval?.status==='returned')out.sections.unshift(section('老板退回意见',[notice(approval.note)]));
  out.primary=approval?.status==='returned'?'保存修改并重新提交':'生成并提交老板审批';
  try{out.quoteDraftTotal=B.money(R.quoteTotal(f));}catch(_){out.quoteDraftTotal='待核对';}out.sections.find(s=>s.title.includes('草稿')).items.push({type:'quote-draft-total'});
 }else if(q){
  const budget=(c.budgets||[]).find(b=>b.version===q.budgetVersion),delta=budget?Math.round((q.total-budget.total)*100)/100:null;
  out.sections=[section('建造报价 V'+q.version,[{type:'money',value:B.money(q.total),subtitle:'计价建筑面积 '+q.area+'㎡'}, {type:'quote-table',rows:[{label:'主体工程',formula:q.area+'㎡ · 汇总计价',value:B.money(q.main),icon:'quote-house'},{label:'外装工程',formula:'按约定范围',value:B.money(q.exterior),icon:'quote-window'},...(q.other?[{label:'其他约定',formula:'按约定范围',value:B.money(q.other),icon:'quote-other'}]:[])],total:B.money(q.total)},notice(q.scope||'按本版报价及原约定范围')],{badge:out.badge}),section('报价依据',[kv('参考预算',budget?'V'+budget.version+' · '+money(budget.total):'历史依据'),kv('设计版本',q.designVersion?'V'+q.designVersion:'历史版本'),...(delta!==null?[kv('相对预算差额',(delta>=0?'+':'−')+money(Math.abs(delta)))]:[]),...(q.reason?[notice(q.reason)]:[])]),section('老板审批',[kv('本版状态',out.badge),notice(approval?.note||'等待本组织老板核对金额和依据。')]),section('报价文件',[{type:'quote-export',kind:'quote'},links([{title:'查看历史版本',view:'history'}])])];
  out.primary=M.approved(c,'quote')?(c.contract?'查看建造合同':'登记建造合同签署'):'返回项目办理';out.primaryView=M.approved(c,'quote')?(c.contract?'contract':'contract-prepare'):'delivery';
 }else{out.sections=[section('报价准备',basis)];out.primary='返回项目办理';out.primaryView='delivery';}
}
function flow(c,out){
 const n=D.next(c),current=n.view;
 const steps=[
  ['方案预算',[['参考预算','budget-result',!!c.budgets?.length]]],
  ['设计服务',[['设计委托','request',!!c.request&&c.request.status!=='returned'],['服务费核定','service-order',!!c.serviceOrders?.length],['费用审批','service-order',M.approved(c,'service')],['首款办理','payment-first',require('../../utils/intercompany').condition(c,'first').ready]]],
  ['正式设计',[['设计与专业审核','formal-progress',!!c.deliverableReviews?.some(r=>r.version===B.latest(c.deliverables)?.version&&r.status==='passed')],['业务申请尾款','tail-apply',!!c.tailApplication||require('../../utils/intercompany').condition(c,'tail').ready],['老板审批与尾款核对','payment-tail',require('../../utils/intercompany').condition(c,'tail').ready],['正式图纸下发','formal-progress',!!c.release],['门店收图与客户签认',D.first(c)&&!D.receipt(c)?'design-receipt':'confirmation',D.confirmed(c)]]],
  ['工程交接',[['施工图与专业审核','drawing-review',R.drawingReview(c)?.status==='passed'],['工程移交','handover',!!c.handover&&c.handover.status!=='returned'],['工程负责人接收','accepted',!!c.acceptance]]]
 ];
 out.title='项目办理';out.sections=steps.map(([title,rows])=>section(title,[{type:'checklist',values:rows.map(([title,view,done])=>{const canRead=X.workflow(view,c,false),active=!done&&(view===current||view==='service-order'&&current==='service-prepare'||view==='handover'&&c.handover?.status==='returned');return {title,state:done?'done':active?'current':'todo',meta:done?'已完成':active?n.state:'未完成',view:canRead&&(done||active||['formal-progress','drawing-review'].includes(view))?view:'',action:done?'查看':active&&n.actionable&&X.workflow(current,c,true)?'去办理':'查看进度'};})}]));
 const pending=(c.returns||[]).filter(r=>r.status==='pending');if(pending.length)out.sections.unshift(section('待补充事项',pending.map(r=>notice(r.reason))));
 out.primary=n.actionable&&X.workflow(n.view,c,true)?n.label:'查看当前办理进度';out.primaryView=n.view;out.secondary='';
 if(!X.workflow(n.view,c,false)){out.primary='返回客户工作区';out.primaryView='customer';}
}
function decorate(view,c,f,out){
 out.primaryView='';const a=X.context(),basis=R.basis(c),n=D.next(c);
 out.projectSummary=[c.city?.replace(/市$/,''),basis.floors?basis.floors+'层乡墅':'层数待补齐',basis.area?basis.area+'㎡':'面积待补齐'].filter(Boolean).join(' · ');
 out.projectNumber=short(c.projectId);out.projectBasis=basis;
 const financeBook=X.rawBook(a.companyId);out.canProjectFinance=c.companyId===a.companyId&&(Job.has(a.role,'sales')||Job.has(a.role,'engineer')||Job.has(a.role,'finance')||X.isBoss(a))&&!!financeBook&&require('../../utils/operations').canRead(c.projectId,{...financeBook,companyId:a.companyId});
 const leaf=['request','service-prepare','service-order','payment-first','payment-tail','money-review','intake','assign','deliverable','deliverable-review','release','task-access','design-part','formal-progress','tail-apply','design-receipt','confirmation','quote','contract','contract-prepare','documents','copies','drawing-review','review-submit','review-record','handover','receive','accepted','start-check','start-receipt','delivery','hub'];
 out.compactProject=leaf.includes(view);
 out.taskSummary=leaf.includes(view)?{state:n.state,owner:n.owner}:null;
 if(['delivery','hub'].includes(view)&&Job.has(a.role,'sales'))flow(c,out);
 if(view==='intake')out.title='设计受理';
 if(view==='assign')out.title='安排设计师';
 if(view==='confirmation'&&c.confirmation){out.primary='返回项目办理';out.primaryView='delivery';}
 if(['documents','copies'].includes(view))for(const s of out.sections||[])if(s.title==='签约附件'){s.badge=c.projectContractArchived?'已归档':'待签约归档';s.badgeGreen=!!c.projectContractArchived;}
 if(view==='quote')quote(c,f,out);
 if(view==='request')for(const s of out.sections)for(const i of s.items){if(i.type==='kv'&&i.label==='项目')i.value=short(c.projectId);if(i.key==='requestScope')i.long=true;}
 if(view==='request')out.sections[0].items.push(links([{title:'复制完整项目编号',action:'copy-project'}]));
 if(view==='formal-progress'){out.badge=n.state;for(const s of out.sections)if(s.title==='设计进度')s.badge=n.state;const target=n.view!=='formal-progress'&&X.workflow(n.view,c,false);out.primary=target?n.label:'返回项目办理';out.primaryView=target?n.view:'delivery';}
 if(view==='tail-apply'){
  out.title='设计尾款申请';out.primary=c.tailApplication?'查看尾款办理进度':'提交尾款申请';
  const d=B.latest(c.deliverables),review=c.deliverableReviews?.find(r=>r.version===d?.version);
  out.sections[0].badge=review?.status==='passed'?'本版成果审核通过':'待本版成果审核';
  out.sections=out.sections.map(s=>s.title==='申请设计尾款'&&c.tailApplication?section('尾款申请回执',[kv('申请版本','V'+c.tailApplication.version),kv('申请日期',c.tailApplication.date),notice(c.tailApplication.note),notice(n.state+' · '+n.owner)]):s);
  for(const s of out.sections)for(const i of s.items||[])if(i.type==='notice'&&i.value==='提交后进入财务待办，同一尾款事项不重复创建。')i.value='提交后先进入加盟商老板审批待办，批准后由财务登记实际付款；同一服务单不重复申请。';
 }
 if(['payment-first','payment-tail'].includes(view))out.title=view==='payment-first'?'设计首款办理':'设计尾款办理';
 if(['service-order','formal-progress','tail-apply'].includes(view))for(const s of out.sections||[]){
  if(s.title==='款项节点')for(let index=0;index<s.items.length;index++){const stage=index?'tail':'first',progress=require('../../utils/design-payment-progress').progress(c,stage);s.items[index].ok=progress.condition.ready;s.items[index].subtitle=progress.status+' · 有效已付 '+money(progress.paid/100)+' · 剩余 '+money(progress.remaining/100);}
  if(s.title==='对外设计服务款')for(const item of s.items||[])if(item.type==='kv'&&/首款|尾款/.test(item.label)){const stage=item.label.includes('首款')?'first':'tail';item.value=c.serviceOrders?.length?require('../../utils/design-payment-progress').progress(c,stage).status:require('../../utils/intercompany').condition(c,stage).text;}
 }
 if(view==='service-prepare'){
  const formSection=(out.sections||[]).find(s=>(s.items||[]).some(i=>i.key==='serviceTotal'));
  if(formSection){formSection.items=formSection.items.map(i=>i.key==='serviceVerifier'?kv('核定人',a.name):i);formSection.items.push(notice('首款50%，尾款50%；付款后保留原费用版本。'));}
  if(c.firstPayment||c.tailPayment)out.primaryView='service-order';
 }
 if(view==='contract'||view==='contract-prepare')out.sections[0].items.push(kv('已确认设计',c.contract?.designVersion?'V'+c.contract.designVersion:c.confirmation?'V'+c.confirmation.version:'待确认'));
 if(view==='drawing-review'&&!X.workflow('review-record',c,true)){out.primary='返回项目办理';out.primaryView='delivery';}
 if(view==='handover'){
  const sent=c.handover&&c.handover.status!=='returned',ready=R.handoverReady(c);
  out.sections=[section('移交准备',[kv('接收岗位','工程负责人'),kv('本版资料',ready?'已齐备':'待补齐'),...(!R.leaders(c.companyId).length?[notice('本组织尚未配置在岗工程负责人，请由门店负责人完善人员配置。')]:[])]),section('电子交底',sent?[kv('接收负责人',c.handover.manager),notice(c.handover.note)]:[field('接收负责人','manager','请选择工程负责人'),area('电子交底与现场重点','handoverNote',hints.handoverNote[1])]),section('本版交接资料',[packet(c,!!sent)]),section('移交记录',[kv('移交版本','V'+(c.handover?.version||1)),kv('提交日期',c.handover?.date||B.date()),notice('提交后由指定工程负责人核查接收；接收后仍待开工。')])];
  const returns=(c.returns||[]).filter(r=>r.view==='receive'&&r.status==='pending');if(returns.length)out.sections.unshift(section('工程退回意见',returns.map(r=>notice(r.reason))));
  out.badge=sent?c.acceptance?'已接收':'待工程接收':c.handover?'待补充重提':'待提交';out.primary=sent?'查看移交进度':c.handover?'补充后重新提交':'提交工程移交';if(sent)out.primaryView=c.acceptance?'accepted':'delivery';
 }
 if(view==='receive'){
  out.headerItems=[kv('接收负责人',c.handover?.manager||'待指定'),kv('移交日期',c.handover?.date||'—')];
  out.sections=[section('电子交底',[notice(c.handover?.note||'尚未提交')]),section('本次交接资料',[packet(c,true)]),section('接收核查',[{type:'check',label:'合同、报价和签认版本一致',key:'receiveContract'},{type:'check',label:'施工图及本版专业审核、抄送记录已核对',key:'receiveDrawing'},{type:'check',label:'电子交底及现场重点已核对',key:'receiveBrief'},field('核查日期','receiveDate','请选择日期',{kind:'date'}),notice('确认接收后进入待开工；资料有缺项时退回原业务单补充。')])];
  out.taskSummary={state:c.acceptance?'工程已接收 · 待开工':'待工程负责人接收',owner:c.handover?.manager||'待指定'};
  if(c.acceptance){out.primary='查看移交回执';out.primaryView='accepted';out.secondary='';}
  else if(c.handover?.status==='returned'){
   out.sections=out.sections.slice(0,2).concat(section('待业务补充',[notice('已退回原移交单，待业务补充并重新提交后再接收。')]));
   out.taskSummary={state:'工程移交待补充',owner:'业务人员'};out.primary='返回工作台';out.secondary='';
  }
 }
 if(view==='accepted'){out.primary=X.workflow('start-check',c,false)?'核查开工条件':'返回项目办理';out.primaryView=X.workflow('start-check',c,false)?'start-check':'delivery';}
 if(c.contract&&['contract','handover'].includes(view)&&(Job.has(a.role,'sales')||X.isBoss(a)))out.sections.push(section('合同收款条件',[notice('按已签合同配置收款期次并提交老板批准；开工、交付按批准的收款条件核查。'),links([{title:'查看与配置合同收款期次',url:'/pages/admin/operations/index?view=receivables&id='+encodeURIComponent(c.projectId)}])]));
 for(const s of out.sections||[])for(const i of s.items||[]){
  if(i.type==='textarea'&&hints[i.key]){i.label=i.label||hints[i.key][0];i.placeholder=i.placeholder||hints[i.key][1];}
  if(i.type==='input'&&i.label.length>6)i.long=true;
  if(i.type==='timeline')for(const p of i.values)p.date=date(p.date);
  if(i.type==='file'&&!i.file){
   if(!c[i.key]&&!f[i.key]&&s.items.some(row=>row.type==='upload'&&row.key===i.key)){i.type='omit';continue;}
   const actual=c[i.key]||f[i.key]||(i.key==='contractFile'?c.contract?.file:i.key==='briefFile'?c.handover?.brief:null);
   if(actual?.name){i.file=actual;i.name=actual.name;}
   else if(i.key==='requestFile'&&c.request){i.file={name:'设计需求 V'+c.request.version+' 摘要',text:c.request.text+'\n'+(c.request.scope||'')};i.name=i.file.name;}
   else if(i.key==='copyFile'){i.file=R.packageRows(c).find(r=>r.key==='copies')?.file;if(i.file)i.name=i.file.name;else Object.assign(i,notice('本版施工图抄送记录待补齐'));}
   else Object.assign(i,notice(i.key==='signedFile'?(c.projectContractArchived?'签约附件已归档，由有权岗位查看原文件。':'尚未签约归档，设计阶段可继续补图。'):i.name.replace(/\.pdf$/i,'')+'待上传'));
  }
  if(i.type==='notice'&&typeof i.value==='string')i.value=i.value.replaceAll('返回设计协同','返回项目办理');
  if(i.type==='notice'&&view==='request')i.value=i.value.replace('总部核对首款后受理和派单','设计团队按费用核对结果受理并安排设计师');
  if(i.type==='links')for(const link of i.values||[])if(link.title==='返回设计协同')link.title='返回项目办理';
 }
 for(const s of out.sections||[])s.items=s.items.filter(i=>i.type!=='omit');
 if(out.primary==='返回设计协同'){out.primary='返回项目办理';out.primaryView='delivery';}
 if(out.secondary==='返回项目办理')out.secondary='返回项目办理';
}
module.exports={decorate};
