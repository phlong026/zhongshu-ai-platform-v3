// Business-only commission protocol. Existing commissions retain their original rules.
const X=require('./access'),Job=require('./organization');
const TYPE='sales-offline-v1',KEY='zhongshu-business-v3',copy=v=>JSON.parse(JSON.stringify(v)),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const is=c=>c?.businessFlow?.type===TYPE;
// Old records remain operable; new customers can only start the simplified protocol.
const legacy=c=>!!c&&!is(c)&&!!(c.workflowVersion==='legacy-v1'||c.request||c.contract||c.quotes?.length||c.serviceOrders?.length||c.firstPayment||c.tailPayment||c.handover||c.acceptance);
const legacyActions=['request','tail-apply','service-prepare','intake','assign','deliverable','deliverable-review','release','design-part','review-submit','review-record','quote','contract','contract-prepare','confirmation','design-receipt','design-feedback','documents','copies','handover','receive'];
const legacyViews=[...legacyActions,'delivery','hub','formal-progress','drawing-review','accepted','service-order','payment-first','payment-tail','funds'];
function entry(c,view){
 if(!c||legacy(c)||!legacyViews.includes(view)&&!(is(c)&&view==='history'))return '';
 let target='progress';
 if(view==='request'&&(!c.request||next(c).view==='submit')||!is(c)&&['delivery','hub'].includes(view))target='submit';
 else if(is(c)&&['handover','receive'].includes(view))target=view;
 else if(is(c)&&view==='design-feedback')target='feedback';
 return url(c.id,target);
}
const actions=['commission-submit','commission-feedback','design-complete','design-dispatch','packet-submit','packet-return','packet-receive'];
const url=(id,view='progress')=>'/pages/workflow/commission/index?view='+view+'&id='+encodeURIComponent(id);
const date=()=>require('./account').localDate();
const last=c=>c.serviceOrders?.at(-1);
function cents(value){X.assert(/^\d+(\.\d{1,2})?$/.test(String(value))&&Number(value)>0&&Number(value)<=100000000,'请填写有效金额，最多两位小数');return Math.round(Number(value)*100);}
function validDate(v){return /^\d{4}-\d{2}-\d{2}$/.test(v||'')&&!Number.isNaN(Date.parse(v))&&new Date(v).toISOString().slice(0,10)===v&&v<=date();}
function file(f){return !!(f&&String(f.name||'').trim()&&(f.path||f.tempFilePath||f.fileID||f.url||f.text||f.sample&&X.context().demo));}
function raw(c){return ((wx.getStorageSync(KEY)||{}).customers||[]).find(r=>r.id===c.id&&(r.companyId||'quanzhou')===(c.companyId||'quanzhou'))||c;}
function policy(companyId){return X.rawBook(companyId)?.businessDesignPolicies?.at(-1)||null;}
function savePolicy(f){
 const a=X.context();X.assert(a.active&&a.role==='boss'&&a.orgType==='store','请由加盟商老板配置标准设计费用');
 const total=cents(f.total),first=cents(f.first);X.assert(first<total&&String(f.recipient||'').trim()&&String(f.scope||'').trim(),'请填写交付范围、总部实际收款方，首款须小于总额');
 const root=copy(wx.getStorageSync('zhongshu-management-v1'));X.assert(root,'组织账簿未初始化');const b=root.companyId===a.companyId?root:root.books?.[a.companyId];X.assert(b,'当前组织账簿不存在');
 const rows=b.businessDesignPolicies||(b.businessDesignPolicies=[]),row={id:'design-policy:'+a.companyId+':'+(rows.length+1),version:rows.length+1,active:f.active!==false,total:total/100,first:first/100,tail:(total-first)/100,scope:f.scope.trim(),recipient:f.recipient.trim(),approvedAt:new Date().toISOString(),approvedById:a.staffId,approvedPersonId:a.personId,companyId:a.companyId};rows.push(row);wx.setStorageSync('zhongshu-management-v1',root);return copy(row);
}
function standardApproved(c){
 if(!is(c))return false;const o=last(c),auth=c.businessFlow.authorization;if(!o||auth?.kind!=='standard')return false;
 const p=X.rawBook(c.companyId)?.businessDesignPolicies?.find(p=>p.id===auth.policyId&&p.version===auth.policyVersion);
 return !!(p?.active&&p.approvedById&&p.companyId===c.companyId&&same([o.total,o.first,o.tail,o.recipient,o.basis],[p.total,p.first,p.tail,p.recipient,p.scope])&&auth.serviceVersion===o.version);
}
function allowed(action,c,a=X.context()){
 if(!a.active)return false;
 if(action==='commission-submit')return !legacy(c)&&a.orgType==='store'&&Job.has(a.role,'sales')&&X.customer(c,a)&&(X.ownerId(c)===a.staffId||a.role==='sales-lead'&&X.teamAccess(c,a));
 if(!is(c))return false;
 if(['design-complete','design-dispatch'].includes(action))return a.role==='hq-design-admin'&&a.orgType==='headquarters'&&c.designOrganizationId===a.companyId;
 if(['packet-return','packet-receive'].includes(action))return Job.has(a.role,'engineer')&&X.sameCompany(c,a)&&c.handover?.managerId===a.staffId;
 if(['packet-submit','commission-feedback'].includes(action))return allowed('commission-submit',c,a);
 return false;
}
function feeApproved(c){return standardApproved(c)||require('./commercial-approval').latest(c,'service')?.status==='approved'&&require('./commercial-approval').latest(c,'service')?.approverRole==='boss';}
function paymentAuthorization(c,signature){
 if(!is(c)||!feeApproved(c))return null;
 const f=c.businessFlow,a=standardApproved(c)?X.rawBook(c.companyId).businessDesignPolicies.find(p=>p.id===f.authorization.policyId):require('./commercial-approval').latest(c,'service');
 return {status:'approved',role:'boss',signature,kind:standardApproved(c)?'standing-design-policy':'commission-service-authorization',actorId:a.approvedById||a.approverId,personId:a.approvedPersonId||a.approverPersonId,at:a.approvedAt||a.reviewedAt,sourceId:a.id,serviceVersion:last(c).version};
}
function next(c){
 return nextRecord(raw(c));
}
function receiptWaiting(c){
 const release=require('./design-receipts').release(c),pair=!release.first.ready?['首款',release.first]:['尾款',release.tail],stage=pair[1];
 const storePending=stage.paid<stage.expected,gap=Math.max(0,stage.expected-(storePending?stage.paid:stage.received));
 const money=value=>{const cents=Math.max(0,Math.round(Number(value)||0));return '¥'+Math.floor(cents/100).toLocaleString('zh-CN')+'.'+String(cents%100).padStart(2,'0');};
 return {ready:release.ready,state:'设计'+pair[0]+(storePending?'待门店付款':'待总部核收'),detail:'应收 '+money(stage.expected)+' · 门店已付 '+money(stage.paid)+' · 总部实收 '+money(stage.received)+' · 差额 '+money(gap),owner:storePending?'门店财务':'总部财务'};
}
// Workbench batches already have current records; avoid reading all customers once per row.
function nextRecord(c){
 const f=c.businessFlow||{},r=(state,owner,view,actionable=false,detail='')=>({state,owner,view,actionable,label:view==='submit'?'提交设计委托':view==='handover'?'收图并移交':view==='receive'?'核查接收':view==='complete'?'设计完成':view==='dispatch'?'成果已下发':'查看进度',...(detail?{detail}:{})});
 if(!is(c))return r('待提交设计委托','经办业务','submit',true);
 if(c.acceptance)return r('业务移交完成','已完成','progress');
 if(c.handover?.status==='returned')return r('移交资料待补充','经办业务','handover',true);
 if(c.handover)return r('资料待接收',c.handover.manager,'receive',true);
 if(require('./commercial-approval').latest(c,'service')?.status==='returned')return r('委托费用待修改','经办业务','submit',true);
 if(!feeApproved(c))return r('费用待老板审批','加盟商老板','progress');
 if(!f.completed||f.feedbacks?.at(-1)?.status==='pending')return r(f.feedbacks?.at(-1)?.status==='pending'?'线下修改中':'线下设计中','设计行政','complete',true);
 if(!f.dispatch){const waiting=receiptWaiting(c);if(!waiting.ready)return r(waiting.state,waiting.owner,'progress',false,waiting.detail);}
 if(!f.dispatch)return r('待正式成果下发','设计行政','dispatch',true);
 return r('待收图并移交','经办业务','handover',true);
}
function transition(b,action,input,at=new Date().toISOString()){
 X.assert(allowed(action,b),'当前身份无本委托的办理权限');const a=X.context(),n=copy(b),f=copy(input||{});let flow=n.businessFlow;
 if(action==='commission-submit'){
  const approved=require('./commercial-approval');
  X.assert(!b.request||is(b)&&approved.latest(b,'service')?.status==='returned','已有委托请沿原流程继续办理');
  const ledger=X.rawBook(b.companyId);X.assert(!b.firstPayment&&!b.tailPayment&&!(b.designPayments||[]).length&&!(ledger?.payments||[]).some(p=>p.source?.id===b.id&&!['确认未付款','已撤销'].includes(p.status)),'已有付款记录，费用约定不能覆盖');
  const basis=require('./design-coordination').basis(b);X.assert(basis&&String(f.text||'').trim()&&String(f.scope||'').trim()&&f.checked,'请核对有效预算、需求、尺寸和交付范围');
  const total=cents(f.total),first=cents(f.first),recipient=String(f.recipient||'').trim();X.assert(first<total&&recipient,'请填写有效首尾款和实际总部收款方');
  const version=(b.request?.version||0)+1,serviceVersion=(b.serviceOrders?.at(-1)?.version||0)+1,p=policy(b.companyId);
  if(b.request)(n.requestHistory||(n.requestHistory=[])).push(copy(b.request));
  n.request={version,flow:'design-first',commissionId:b.request?.commissionId||'design:'+b.projectId,text:f.text.trim(),scope:f.scope.trim(),basis,basisChecked:true,budgetVersion:b.budgets.at(-1).version,planVersion:b.plans?.at(-1)?.version||null,facadeVersion:b.facades?.at(-1)?.version||null,attachment:f.attachment||null,date:date()};
  n.designOrganizationId='headquarters';n.designTeamId='design';
  const order={id:'service-'+serviceVersion,version:serviceVersion,responsibleId:X.ownerId(b),submittedById:a.staffId,submittedAt:at,total:total/100,first:first/100,tail:(total-first)/100,recipient,basis:f.scope.trim(),verifier:a.name,date:date(),requestVersion:version,file:{name:'设计委托费用约定 V'+serviceVersion,text:'本次范围：'+f.scope+'；总额 '+total/100+' 元，首款 '+first/100+' 元，尾款 '+(total-first)/100+' 元；实际收款方：'+recipient}};
  (n.serviceOrders||(n.serviceOrders=[])).push(order);
  const match=p?.active&&same([order.total,order.first,order.tail,recipient,order.basis],[p.total,p.first,p.tail,p.recipient,p.scope]);
  flow=n.businessFlow={type:TYPE,requestVersion:version,submittedAt:at,responsibleId:X.ownerId(b),submittedById:a.staffId,authorization:match?{kind:'standard',policyId:p.id,policyVersion:p.version,serviceVersion}:{kind:'exception',serviceVersion},events:flow?.events||[]};
 }else{
  X.assert(is(b),'当前委托仍使用历史流程');
  if(action==='commission-feedback'){
   X.assert(flow.dispatch&&!b.handover&&!b.acceptance,'正式成果下发后、提交移交前可登记修改意见');
   X.assert(String(f.note||'').trim(),'请填写需要线下沟通的修改意见');
   const feedbacks=flow.feedbacks||(flow.feedbacks=[]);feedbacks.push({id:'feedback:'+(feedbacks.length+1),status:'pending',note:f.note.trim(),attachment:f.attachment||null,dispatchVersion:flow.dispatch.version,at,date:date(),actorId:a.staffId,personId:a.personId});
   (flow.dispatchHistory||(flow.dispatchHistory=[])).push(copy(flow.dispatch));delete flow.dispatch;
  }
  if(action==='design-complete'){
   const feedback=flow.feedbacks?.at(-1);X.assert(!flow.dispatch&&(!flow.completed||feedback?.status==='pending'),'设计完成已登记，请查看原记录');X.assert(feeApproved(b),'请等待设计费用授权');
   X.assert(f.checked,'请确认本次线下设计及内部审核已完成');if(flow.completed)(flow.completedHistory||(flow.completedHistory=[])).push(copy(flow.completed));flow.completed={date:date(),at,by:a.name,actorId:a.staffId,personId:a.personId,note:String(f.note||'').trim(),requestVersion:b.request.version,feedbackId:feedback?.id||null};
   if(feedback?.status==='pending'){feedback.status='completed';feedback.completedAt=at;}
  }
  if(action==='design-dispatch'){
   X.assert(flow.completed&&!flow.dispatch&&flow.feedbacks?.at(-1)?.status!=='pending','请先完成设计；已下发的记录不能重复登记');X.assert(feeApproved(b),'请等待设计费用授权');
   const receipt=require('./design-receipts').release(b);X.assert(receipt.ready,'请等待总部财务确认本版首尾款足额到账');
   X.assert(String(f.recipient||'').trim()&&String(f.note||'').trim()&&f.checked,'请填写实际接收人、交付清单或渠道，并确认已经下发');
   flow.dispatch={version:(flow.dispatchHistory?.length||0)+1,requestVersion:b.request.version,at,date:date(),recipient:f.recipient.trim(),note:f.note.trim(),actorId:a.staffId,personId:a.personId,financeConfirmation:{serviceVersion:last(b).version,requestVersion:b.request.version,confirmationVersion:receipt.confirmationVersion,confirmedAt:receipt.confirmedAt}};
  }
  if(action==='packet-submit'){
   X.assert(flow.dispatch&&!b.acceptance&&(!b.handover||b.handover.status==='returned'),'请等待正式成果下发；待接收或已接收资料不能覆盖');
   X.assert(f.dispatchVersion===flow.dispatch.version,'正式成果版本已更新，请重新收图并核对本版客户确认');
   X.assert(file(f.resultFile)&&file(f.drawingFile)&&file(f.reviewFile)&&file(f.confirmationFile),'请补齐正式成果、施工图、线下审核依据和客户确认凭据');
   X.assert(String(f.drawingVersion||'').trim()&&validDate(f.confirmationDate)&&f.received&&f.confirmed&&String(f.note||'').trim(),'请核对文件、图纸版本、客户确认日期及交底内容');
   const manager=(X.rawBook(b.companyId)?.staff||[]).find(p=>p.active!==false&&p.id===f.managerId&&Job.staffHas(p,'engineer'));X.assert(manager,'请选择本组织在岗工程负责人');
   const version=(b.handover?.version||0)+1;
   flow.packet={version,dispatchVersion:flow.dispatch.version,resultFile:f.resultFile,drawingFile:f.drawingFile,reviewFile:f.reviewFile,confirmationFile:f.confirmationFile,drawingVersion:f.drawingVersion.trim(),confirmationDate:f.confirmationDate,note:f.note.trim(),date:date(),actorId:a.staffId,personId:a.personId};
   n.handoverHistory=(n.handoverHistory||[]).concat(b.handover?[copy(b.handover)]:[]);
   n.handover={version,status:'pending',recipientRole:'project-manager',managerId:manager.id,manager:manager.name,note:f.note.trim(),date:date(),submittedById:a.staffId,designVersion:flow.dispatch.version,drawingVersion:flow.packet.drawingVersion,basis:require('./project-records').basis(n),documents:packageRows(n)};
   for(const r of n.returns||[])if(r.view==='receive'&&r.status==='pending')r.status='resolved';
  }
  if(['packet-return','packet-receive'].includes(action)){
   X.assert(b.handover?.status==='pending'&&!b.acceptance&&same(b.handover.documents,packageRows(b)),'移交记录已变化或已办理，请刷新');
   if(action==='packet-return'){X.assert(String(f.note||'').trim(),'请填写资料退回原因');n.handover.status='returned';(n.returns||(n.returns=[])).push({view:'receive',reason:f.note.trim(),status:'pending',at,actorId:a.staffId});}
   else{X.assert(f.checked,'请核对本次图纸、签认依据和交底资料');n.handover.status='accepted';n.acceptance={managerId:a.staffId,manager:a.name,date:date(),at,handoverVersion:b.handover.version,drawingVersion:b.handover.drawingVersion,actorId:a.staffId,personId:a.personId};}
  }
 }
 flow.events=flow.events.concat({id:action+':'+(flow.events.length+1),action,at,actorId:a.staffId,personId:a.personId});
 flow.lastCommand={action,input:f,at,actorId:a.staffId};return n;
}
function validate(b,n,action){const cmd=n.businessFlow?.lastCommand;X.assert(cmd?.action===action&&cmd.actorId===X.context().staffId,'业务事件来源不一致');const expected=transition(b,action,cmd.input,cmd.at);X.assert(same(expected,n),'该动作不能改写其他业务字段或历史记录');}
function run(id,action,input,revision){return require('./business').write(id,n=>Object.assign(n,transition(n,action,input)),({ 'commission-submit':'提交设计委托','commission-feedback':'登记线下修改意见','design-complete':'线下设计完成','design-dispatch':'正式成果已下发','packet-submit':'提交成果与移交','packet-return':'移交资料退回','packet-receive':'业务资料接收完成'})[action],action,revision);}
function packageRows(c){const p=c.businessFlow?.packet;if(!p)return [];return [['design','正式设计成果',p.resultFile],['drawing','正式施工图',p.drawingFile],['review','线下专业审核依据',p.reviewFile],['confirmation','客户确认凭据',p.confirmationFile],['brief','电子交底',{name:'电子交底记录',text:p.note}]].map(([key,label,f])=>({key,label,version:p.drawingVersion,ready:file(f),file:copy(f),summary:key==='confirmation'?p.confirmationDate:'',view:''}));}
function tasks(a=X.context()){
 if(!a.active)return [];const rows=[];
 for(const c of (wx.getStorageSync(KEY)||{}).customers||[]){
  if(!is(c)){if(!legacy(c)&&c.budgets?.length&&allowed('commission-submit',c,a))rows.push({id:'commission:'+c.id,key:'commission:'+c.id,customerId:c.id,name:c.name,state:'方案预算已保存，待提交委托',action:'提交设计委托',actionable:true,url:url(c.id,'submit'),kind:'commission'});continue;}
  if(!X.customer(c,a)||c.acceptance)continue;const n=next(c),action={submit:'commission-submit',complete:'design-complete',dispatch:'design-dispatch',handover:'packet-submit',receive:'packet-receive'}[n.view],tail=require('./intercompany').condition(c,'tail'),followTail=!!c.businessFlow.completed&&!c.businessFlow.dispatch&&tail.paid<tail.expected&&allowed('commission-submit',c,a),actionable=!!action&&allowed(action,c,a)||followTail;
  if(a.role==='hq-design-admin'&&!['complete','dispatch'].includes(n.view))continue;
  if(a.role==='project-manager'&&n.view!=='receive')continue;
  const view=followTail?'progress':actionable?n.view:'progress';rows.push({id:'offline:'+c.id+':'+c.request.version,key:'offline:'+c.id+':'+c.request.version,customerId:c.id,name:c.name,state:followTail?'设计已完成 · 跟进尾款支付':n.state,owner:n.owner,action:followTail?'跟进尾款支付':actionable?n.label:'查看进度',actionable,url:url(c.id,view),kind:'commission'});
 }
 return rows;
}
module.exports={TYPE,is,legacy,legacyActions,entry,actions,url,policy,savePolicy,standardApproved,paymentAuthorization,allowed,feeApproved,next,nextRecord,transition,validate,run,packageRows,tasks,file,raw};
