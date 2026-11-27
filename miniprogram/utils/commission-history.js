// Historical commission reads shared by receipts, finance, messages and engineering archives.
// Current commands use the API; this module never creates or changes a commission.
const X=require('./access'),Job=require('./organization'),Checkout=require('./design-checkout'),Status=require('./status-labels').commission;
const TYPE='sales-offline-v1',KEY=require('./storage-keys').business,copy=require('./clone'),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
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
const actions=['commission-pay','commission-submit','commission-feedback','design-complete','design-dispatch','packet-submit','packet-return','packet-receive'];
const url=(id,view='progress')=>'/pages/workflow/commission/index?view='+view+'&id='+encodeURIComponent(id);
const last=c=>c.serviceOrders?.at(-1);
function file(f){return !!(f&&String(f.name||'').trim()&&(f.path||f.tempFilePath||f.fileID||f.url||f.text||f.sample&&X.context().demo));}
function raw(c){return ((wx.getStorageSync(KEY)||{}).customers||[]).find(r=>r.id===c.id&&(r.companyId||'quanzhou')===(c.companyId||'quanzhou'))||c;}
function policy(companyId){return X.rawBook(companyId)?.businessDesignPolicies?.at(-1)||null;}
function standardApproved(c){
 if(Checkout.is(c))return Checkout.authorized(c);
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
 if(['commission-pay','packet-submit','commission-feedback'].includes(action))return allowed('commission-submit',c,a);
 return false;
}
function feeApproved(c){return standardApproved(c)||require('./commercial-approval').latest(c,'service')?.status==='approved'&&require('./commercial-approval').latest(c,'service')?.approverRole==='boss';}
function firstRegistered(c){if(Checkout.is(c))return Checkout.paid(c);const p=require('./intercompany').condition(c,'first');return p.expected>0&&p.paid>=p.expected;}
function paymentAuthorization(c,signature){
 if(!is(c)||!feeApproved(c)||Checkout.is(c))return null;
 const f=c.businessFlow,a=standardApproved(c)?X.rawBook(c.companyId).businessDesignPolicies.find(p=>p.id===f.authorization.policyId):require('./commercial-approval').latest(c,'service');
 return {status:'approved',role:'boss',signature,kind:standardApproved(c)?'standing-design-policy':'commission-service-authorization',actorId:a.approvedById||a.approverId,personId:a.approvedPersonId||a.approverPersonId,at:a.approvedAt||a.reviewedAt,sourceId:a.id,serviceVersion:last(c).version};
}
function next(c){
 return nextRecord(raw(c));
}
function receiptWaiting(c){
 if(Checkout.is(c))return {ready:Checkout.paid(c),state:Status.pay,roleState:'设计费待一次性支付',paymentStage:'一次性',owner:'经办业务'};
 // Completion opens the tail stage even when finance is still settling the first payment.
 const release=require('./design-receipts').release(c),pair=!release.tail.ready?['尾款',release.tail]:['首款',release.first],stage=pair[1];
 const storePending=stage.paid<stage.expected,gap=Math.max(0,stage.expected-(storePending?stage.paid:stage.received));
 const M=require('./money'),money=v=>M.money(Math.max(0,Math.round(Number(v)||0)));
 const roleState=(pair[0]==='首款'?'设计尾款已到账 · 首款':'设计尾款')+(storePending?'待门店付款':'待总部核收');
 return {ready:release.ready,state:storePending?Status.pay:Status.receiptWaiting,roleState,paymentStage:pair[0],detail:'应收 '+money(stage.expected)+' · 门店已付 '+money(stage.paid)+' · 总部实收 '+money(stage.received)+' · 差额 '+money(gap)+(pair[0]==='尾款'&&!release.first.ready?'；首款已登记付款，待总部确认到账':''),owner:storePending?'门店财务':'总部财务'};
}
// Workbench batches already have current records; avoid reading all customers once per row.
function nextRecord(c){
 const f=c.businessFlow||{},r=(state,owner,view,actionable=false,detail='',meta={})=>({state,owner,view,actionable,label:view==='pay'?'登记付款':view==='submit'?'提交设计委托':view==='handover'?'移交给工程':view==='receive'?'核对接收':view==='complete'?'登记设计完成':view==='dispatch'?'下发设计成果':'查看进度',...(detail?{detail}:{}),...meta});
 if(!is(c))return r(Status.submit,'经办业务','submit',true);
 if(c.acceptance)return r('业务移交完成','已完成','progress');
 if(c.handover?.status==='returned')return r(Status.handover,'经办业务','handover',true,'',{roleState:'移交资料待补充'});
 if(c.handover)return r(Status.receive,c.handover.manager,'receive',true,'',{roleState:'资料待接收'});
 if(require('./commercial-approval').latest(c,'service')?.status==='returned')return r('委托费用待修改','经办业务','submit',true);
 if(!feeApproved(c))return r('费用待老板审批','加盟商老板','progress');
 if(Checkout.is(c)&&!Checkout.paid(c))return r(Status.pay,'经办业务','pay',true,'体验模拟订单，不产生真实扣款；成功后自动抄送双方财务并通知总部设计。',{roleState:'设计费待一次性支付',paymentStage:'一次性'});
 if(!firstRegistered(c)){const roleState=f.completed?'首款未登记 · 待补齐后办理尾款':'设计首款待门店登记';return {...r(Status.pay,'门店财务','progress',false,'请在线下支付设计首款后登记付款，登记足额后触发总部设计待办。',{roleState,paymentStage:'首款'}),waitingFirst:true};}
 if(!f.completed||f.feedbacks?.at(-1)?.status==='pending'){const modifying=f.feedbacks?.at(-1)?.status==='pending';return r(modifying?Status.modifying:Status.designing,'设计行政','complete',true,'',{roleState:modifying?'线下修改中':Checkout.is(c)?Status.designing:'线下设计中'});}
 if(!f.dispatch){const waiting=receiptWaiting(c);if(!waiting.ready)return r(waiting.state,waiting.owner,'progress',false,waiting.detail,{roleState:waiting.roleState,paymentStage:waiting.paymentStage});}
 if(!f.dispatch)return r(Status.dispatch,'设计行政','dispatch',true,'',{roleState:'待正式成果下发'});
 return r(Status.handover,'经办业务','handover',true,'',{roleState:'已下发 · 待收图并移交'});
}
function packageRows(c){const p=c.businessFlow?.packet;if(!p)return [];return [['design','正式设计成果',p.resultFile],['drawing','正式施工图',p.drawingFile],['review','线下专业审核依据',p.reviewFile],['confirmation','客户确认凭据',p.confirmationFile],['brief','电子交底',{name:'电子交底记录',text:p.note}]].map(([key,label,f])=>({key,label,version:p.drawingVersion,ready:file(f),file:copy(f),summary:key==='confirmation'?p.confirmationDate:'',view:''}));}
// Each next step inherits the event that made it actionable, not the customer's creation order.
function triggeredAt(c, view) {
 const Order=require('./task-order'),flow=c.businessFlow||{};
 const event=action=>Order.latest(...(flow.events||[]).filter(row=>row.action===action).map(row=>row.at));
 const stamp=row=>row?.at||row?.registeredAt||row?.date||'';
 const submitted=flow.submittedAt||stamp(c.serviceOrders?.at(-1))||stamp(c.budgets?.at(-1))||c.createdAt||'';
 let at=submitted;
 if(view==='submit')at=require('./commercial-approval').latest(c,'service')?.reviewedAt||stamp(c.budgets?.at(-1))||submitted;
 else if(view==='pay')at=Order.latest(submitted,require('./commercial-approval').latest(c,'service')?.reviewedAt);
 else if(view==='complete'){
  const feedback=flow.feedbacks?.at(-1);
  if(feedback?.status==='pending')at=feedback.at||feedback.date;
  else if(Checkout.is(c))at=flow.paymentOrder?.paidAt||submitted;
  else if(c.serviceOrders?.at(-1)?.first>0)at=Order.latest(submitted,...require('./intercompany').stagePayments(c,'first').map(stamp));
 }else if(view==='dispatch'){
  const confirmed=!Checkout.is(c)&&c.serviceOrders?.at(-1)?.total>0?require('./design-receipts').release(c).confirmedAt:'';
  at=Order.latest(flow.completed?.at,confirmed)||submitted;
 }else if(view==='handover')at=c.handover?.status==='returned'?Order.latest(event('packet-return'),...(c.returns||[]).filter(row=>row.view==='receive'&&row.status==='pending').map(stamp)):flow.dispatch?.at;
 else if(view==='receive')at=event('packet-submit')||stamp(c.handover);
 else at=flow.completed?.at||submitted;
 return Order.latest(at,...(c.responsibilityHistory||[]).map(stamp));
}
function tasks(a=X.context()){
 if(!a.active)return [];const rows=[];
 for(const c of (wx.getStorageSync(KEY)||{}).customers||[]){
  if(!is(c)){if(!legacy(c)&&c.budgets?.length&&allowed('commission-submit',c,a))rows.push({id:'commission:'+c.id,key:'commission:'+c.id,customerId:c.id,name:c.name,state:'方案预算已保存，待提交委托',action:'提交设计委托',command:'commission-submit',triggeredAt:triggeredAt(c,'submit'),actionable:true,url:url(c.id,'submit'),kind:'commission'});continue;}
  if(!X.customer(c,a)||c.acceptance)continue;const n=next(c),action={pay:'commission-pay',submit:'commission-submit',complete:'design-complete',dispatch:'design-dispatch',handover:'packet-submit',receive:'packet-receive'}[n.view],tail=require('./intercompany').condition(c,'tail'),followTail=!Checkout.is(c)&&!!c.businessFlow.completed&&!c.businessFlow.dispatch&&!n.waitingFirst&&tail.paid<tail.expected&&allowed('commission-submit',c,a),actionable=!!action&&allowed(action,c,a)||followTail;
  if(a.role==='hq-design-admin'&&!['complete','dispatch'].includes(n.view))continue;
  if(a.role==='project-manager'&&n.view!=='receive')continue;
  const view=followTail?'progress':actionable?n.view:'progress';rows.push({id:'offline:'+c.id+':'+c.request.version,key:'offline:'+c.id+':'+c.request.version,customerId:c.id,name:c.name,state:followTail?'设计已完成 · 跟进尾款支付':n.state,owner:n.owner,action:followTail?'跟进尾款支付':actionable?n.label:'查看进度',command:actionable&&!followTail?action:'',triggeredAt:triggeredAt(c,view),actionable,url:url(c.id,view),kind:'commission'});
 }
 return require('./task-order').newest(rows);
}
module.exports={is,legacy,legacyActions,entry,actions,url,policy,standardApproved,paymentAuthorization,allowed,feeApproved,firstRegistered,next,nextRecord,packageRows,tasks,triggeredAt,file,raw};
