// Historical transition fixture for attribution tests; never loaded by a product page.
const X=require('../../miniprogram/utils/access'),Job=require('../../miniprogram/utils/organization'),Checkout=require('../../miniprogram/utils/design-checkout');
const copy=require('../../miniprogram/utils/clone'),isIsoDate=require('../../miniprogram/utils/iso-date');
const {is,allowed,policy,feeApproved,firstRegistered,packageRows,file}=require('../../miniprogram/utils/commission-history');
const TYPE='sales-offline-v1',same=(a,b)=>JSON.stringify(a)===JSON.stringify(b),last=c=>c.serviceOrders?.at(-1),date=()=>require('../../miniprogram/utils/account').localDate();
function cents(value){X.assert(/^\d+(\.\d{1,2})?$/.test(String(value))&&Number(value)>0&&Number(value)<=100000000,'请填写有效金额，最多两位小数');return require('../../miniprogram/utils/money').parseCents(value);}
function validDate(v){return isIsoDate(v)&&v<=date();}
function checkFileVersion(f,c,version){X.assert(f?.sample&&f.text&&f.meta?.customerId===c.id&&f.meta.projectId===c.projectId&&f.meta.version===version,'模拟文件须属于本客户、本项目及本版图纸，请重新生成');}
function transition(b,action,input,at=new Date().toISOString()){
 X.assert(allowed(action,b),'当前身份无本委托的办理权限');const a=X.context(),n=copy(b),f=copy(input||{});let flow=n.businessFlow;
 if(action==='commission-submit'){
  const approved=require('../../miniprogram/utils/commercial-approval');
  X.assert(!b.request||is(b)&&approved.latest(b,'service')?.status==='returned','已有委托请沿原流程继续办理');
  const ledger=X.rawBook(b.companyId);X.assert(!b.firstPayment&&!b.tailPayment&&!(b.designPayments||[]).length&&!(ledger?.payments||[]).some(p=>p.source?.id===b.id&&!['确认未付款','已撤销'].includes(p.status)),'已有付款记录，费用约定不能覆盖');
  const basis=require('../../miniprogram/utils/design-coordination').basis(b);X.assert(basis,'请先完成本项目参考预算，再提交设计委托');
  X.assert(f.__budgetVersion===undefined||f.__budgetVersion===basis.version,'参考预算已更新，请重新核对本版尺寸、需求和交付范围');
  X.assert(String(f.text||'').trim(),'请填写客户设计需求');
  X.assert(String(f.scope||'').trim(),'请填写本次交付范围');
  X.assert(f.checked,'请勾选确认已核对需求、尺寸来源、范围及费用约定');
  const full=f.paymentMode===Checkout.MODE;
  const checkout=full?Checkout.create(b,f,(b.request?.version||0)+1,(last(b)?.version||0)+1,at):null;
  const total=full?checkout.amountCents:cents(f.total),first=full?0:cents(f.first),recipient=full?checkout.recipient:String(f.recipient||'').trim();X.assert(first<total&&recipient,'请填写有效首尾款和实际总部收款方');
  const version=(b.request?.version||0)+1,serviceVersion=(b.serviceOrders?.at(-1)?.version||0)+1,p=policy(b.companyId),responsibleId=X.ownerId(b),responsiblePersonId=X.personIdFor(responsibleId,b.companyId);
  if(full&&f.attachment)checkFileVersion(f.attachment,b,'V'+version);
  if(b.request)(n.requestHistory||(n.requestHistory=[])).push(copy(b.request));
  n.request={version,flow:'design-first',commissionId:b.request?.commissionId||'design:'+b.projectId,text:f.text.trim(),scope:f.scope.trim(),basis,basisChecked:true,budgetVersion:b.budgets.at(-1).version,planVersion:b.plans?.at(-1)?.version||null,facadeVersion:b.facades?.at(-1)?.version||null,attachment:f.attachment||null,date:date()};
  n.designOrganizationId='headquarters';n.designTeamId='design';
  const order={id:'service-'+serviceVersion,version:serviceVersion,responsibleId,responsiblePersonId,submittedById:a.staffId,submittedAt:at,total:total/100,first:first/100,tail:full?0:(total-first)/100,recipient,basis:f.scope.trim(),verifier:a.name,date:date(),requestVersion:version,file:{name:'设计委托费用约定 V'+serviceVersion,text:full?'体验模拟一次性支付；面积 '+checkout.area+'㎡ × '+checkout.rateCents/100+'元/㎡，总额 '+total/100+' 元；收款方：'+recipient:'本次范围：'+f.scope+'；总额 '+total/100+' 元，首款 '+first/100+' 元，尾款 '+(total-first)/100+' 元；实际收款方：'+recipient}};
  (n.serviceOrders||(n.serviceOrders=[])).push(order);
  const match=p?.active&&same([order.total,order.first,order.tail,recipient,order.basis],[p.total,p.first,p.tail,p.recipient,p.scope]);
  flow=n.businessFlow={type:TYPE,...(full?{paymentMode:Checkout.MODE,paymentOrder:checkout}:{}),requestVersion:version,submittedAt:at,responsibleId,responsiblePersonId,submittedById:a.staffId,authorization:full?{kind:'area-pricing',pricingVersion:checkout.pricingVersion,serviceVersion}:match?{kind:'standard',policyId:p.id,policyVersion:p.version,serviceVersion}:{kind:'exception',serviceVersion},events:flow?.events||[]};
 }else{
  X.assert(is(b),'当前委托仍使用历史流程');
  if(action==='commission-pay'){
   X.assert(Checkout.is(b),'历史委托请沿原收付款流程办理');
   flow.paymentOrder=Checkout.pay(b,f,at);
  }
  if(action==='commission-feedback'){
   X.assert(flow.dispatch&&!b.handover&&!b.acceptance,'正式成果下发后、提交移交前可登记修改意见');
   X.assert(String(f.note||'').trim(),'请填写需要线下沟通的修改意见');
   const feedbacks=flow.feedbacks||(flow.feedbacks=[]);feedbacks.push({id:'feedback:'+(feedbacks.length+1),status:'pending',note:f.note.trim(),attachment:f.attachment||null,dispatchVersion:flow.dispatch.version,at,date:date(),actorId:a.staffId,personId:a.personId});
   (flow.dispatchHistory||(flow.dispatchHistory=[])).push(copy(flow.dispatch));delete flow.dispatch;
  }
  if(action==='design-complete'){
   const feedback=flow.feedbacks?.at(-1);X.assert(!flow.dispatch&&(!flow.completed||feedback?.status==='pending'),'设计完成已登记，请查看原记录');X.assert(feeApproved(b),'请等待设计费用授权');X.assert(firstRegistered(b),Checkout.is(b)?'请先完成本版设计费模拟支付，再开始设计':'请先由门店财务登记本版设计首款足额付款，再开始设计');
   X.assert(f.checked,'请确认本次线下设计及内部审核已完成');if(flow.completed)(flow.completedHistory||(flow.completedHistory=[])).push(copy(flow.completed));flow.completed={date:date(),at,by:a.name,actorId:a.staffId,personId:a.personId,note:String(f.note||'').trim(),requestVersion:b.request.version,feedbackId:feedback?.id||null};
   if(Checkout.is(b)&&['resultFile','drawingFile','reviewFile'].some(key=>f[key])){X.assert(String(f.drawingVersion||'').trim()&&['resultFile','drawingFile','reviewFile'].every(key=>file(f[key])),'请补齐本版设计成果');for(const key of ['resultFile','drawingFile','reviewFile'])checkFileVersion(f[key],b,f.drawingVersion.trim());flow.completed.files={resultFile:f.resultFile,drawingFile:f.drawingFile,reviewFile:f.reviewFile,drawingVersion:f.drawingVersion.trim()};}
   if(feedback?.status==='pending'){feedback.status='completed';feedback.completedAt=at;}
  }
  if(action==='design-dispatch'){
   X.assert(flow.completed&&!flow.dispatch&&flow.feedbacks?.at(-1)?.status!=='pending','请先完成设计；已下发的记录不能重复登记');X.assert(feeApproved(b),'请等待设计费用授权');
   const receipt=require('../../miniprogram/utils/design-receipts').release(b);X.assert(receipt.ready,Checkout.is(b)?'请先完成本版设计费模拟支付':'请等待总部财务确认本版首尾款足额到账');
   if(Checkout.is(b)){X.assert(file(f.resultFile)&&file(f.drawingFile)&&file(f.reviewFile)&&String(f.drawingVersion||'').trim(),'请补齐正式成果、施工图、审核依据文件及图纸版本');for(const key of ['resultFile','drawingFile','reviewFile'])checkFileVersion(f[key],b,f.drawingVersion.trim());}
   X.assert(String(f.recipient||'').trim()&&String(f.note||'').trim()&&f.checked,'请填写实际接收人、交付清单或渠道，并确认已经下发');
   flow.dispatch={...(Checkout.is(b)?{resultFile:f.resultFile,drawingFile:f.drawingFile,reviewFile:f.reviewFile,drawingVersion:f.drawingVersion.trim()}:{}),version:(flow.dispatchHistory?.length||0)+1,requestVersion:b.request.version,at,date:date(),recipient:f.recipient.trim(),note:f.note.trim(),actorId:a.staffId,personId:a.personId,financeConfirmation:{serviceVersion:last(b).version,requestVersion:b.request.version,confirmationVersion:receipt.confirmationVersion,confirmedAt:receipt.confirmedAt}};
  }
  if(action==='packet-submit'){
   X.assert(flow.dispatch&&!b.acceptance&&(!b.handover||b.handover.status==='returned'),'请等待正式成果下发；待接收或已接收资料不能覆盖');
   X.assert(f.dispatchVersion===flow.dispatch.version,'正式成果版本已更新，请重新收图并核对本版客户确认');
   if(Checkout.is(b))X.assert(['resultFile','drawingFile','reviewFile','drawingVersion'].every(key=>same(f[key],flow.dispatch[key])),'移交文件须与总部本版下发文件及图纸版本一致');
   X.assert(file(f.resultFile)&&file(f.drawingFile)&&file(f.reviewFile)&&file(f.confirmationFile),'请补齐正式成果、施工图、线下审核依据和客户确认凭据');
   X.assert(String(f.drawingVersion||'').trim()&&validDate(f.confirmationDate)&&f.received&&f.confirmed&&String(f.note||'').trim(),'请核对文件、图纸版本、客户确认日期及交底内容');
   if(Checkout.is(b))checkFileVersion(f.confirmationFile,b,f.drawingVersion.trim());
   const book=X.rawBook(b.companyId),engineers=(book?.staff||[]).filter(p=>p.active!==false&&Job.staffHas(p,'engineer')),manager=engineers.find(p=>p.id===b.handover?.managerId)||engineers.find(p=>p.id===Job.defaultAssignee(book,'engineering'));X.assert(manager,'请先完善本组织工程岗位分工，配置在岗工程负责人后再移交');
   const version=(b.handover?.version||0)+1;
   flow.packet={version,dispatchVersion:flow.dispatch.version,resultFile:f.resultFile,drawingFile:f.drawingFile,reviewFile:f.reviewFile,confirmationFile:f.confirmationFile,drawingVersion:f.drawingVersion.trim(),confirmationDate:f.confirmationDate,note:f.note.trim(),date:date(),actorId:a.staffId,personId:a.personId};
   n.handoverHistory=(n.handoverHistory||[]).concat(b.handover?[copy(b.handover)]:[]);
   n.handover={version,status:'pending',recipientRole:'project-manager',managerId:manager.id,manager:manager.name,note:f.note.trim(),date:date(),submittedById:a.staffId,designVersion:flow.dispatch.version,drawingVersion:flow.packet.drawingVersion,basis:require('../../miniprogram/utils/project-records').basis(n),documents:packageRows(n)};
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
module.exports={transition};
