// Record actual money once. Commercial authorization remains on the original payable.
const O=require('../../utils/organization'),X=require('../../utils/access'),F=require('../../utils/finance-source');
const copy=x=>JSON.parse(JSON.stringify(x));let sequence=0;
function amount(v){X.assert(/^\d+(\.\d{1,2})?$/.test(String(v))&&Number(v)>0&&Number(v)<=100000000,'金额须大于0且最多两位小数');return Math.round(Number(v)*100);}
function date(v){return /^\d{4}-\d{2}-\d{2}$/.test(v||'')&&!Number.isNaN(Date.parse(v))&&new Date(v).toISOString().slice(0,10)===v;}
function apply(s,action,id,f={}){
 if(!['incoming-register','approve-pay','approval-return','pay','pay-supplement'].includes(action))return null;
 const a=X.context(),actor=X.actor();X.assert(a.active&&s.companyId===a.companyId,'当前公司或人员没有授权');let result=id;
 const q=s.payables.find(r=>r.id===id),r=s.receipts.find(r=>r.id===id);
 if(action==='incoming-register'){
  X.assert(a.orgType==='store'&&O.has(a.role,'finance'),'仅加盟商财务办理收款登记');
  X.assert(r&&X.rowAccess(r)&&r.source?.module!=='intercompany'&&r.status==='待核算'&&!r.financialRegistrarId,'请从原收款事项登记，已登记的收款不能重复办理');
  X.assert(amount(f.amount)===r.amount&&date(f.date)&&f.date<=require('../../utils/account').localDate()&&f.file&&f.checked&&String(f.transaction||'').trim(),'请核对原收款金额并补齐实际日期、流水和凭据');
  X.assert(!s.receipts.some(t=>t.id!==id&&(t.bankTransaction||t.transaction)===f.transaction.trim()),'银行到账流水已登记');
  Object.assign(r,{financialRegistrarId:a.staffId,financialRegistrarPersonId:a.personId,custodianId:a.staffId,bankFile:copy(f.file),bankTransaction:f.transaction.trim(),bankDate:f.date,bankNote:String(f.note||'').trim(),status:'已核算',registrationMode:'record-only-v1',registeredAt:actor.at,version:(r.version||1)+1});
 }else{
  X.assert(q&&X.rowAccess(q),'付款来源不存在或无权访问');
  if(action==='approve-pay'||action==='approval-return'){
   X.assert(a.role==='boss','金额约定由本组织老板确认');X.assert(require('../../utils/finance-participation').independent(q,s.companyId),'不能批准本人申请');
   X.assert(!s.payments.some(p=>p.payableId===id&&['待复核','待核实','需补充'].includes(p.status)),'原笔历史款项需先在线下处理');
   X.assert(String(f.note||'').trim()&&(action!=='approve-pay'||f.checked),'请核对原单并填写意见');
   if(action==='approve-pay'){
    X.assert(q.serviceApprovalValid!==false&&!q.needsSupplement,'请先完善本版费用约定');if(f.due){X.assert(date(f.due),'付款到期日须有效');q.due=f.due;}
    q.approval={id:'approval-'+Date.now()+'-'+(++sequence),status:'approved',signature:F.signature(q),...actor};q.approvalValid=true;q.status='待办理';
   }else{q.needsSupplement=true;q.returnReason=f.note.trim();q.approval=null;q.approvalValid=false;q.status='需补充';}
  }else if(action==='pay-supplement'){
   X.assert(q.needsSupplement&&X.supplement(q),'请由原事项经办人补齐付款依据');X.assert(f.file&&String(f.note||'').trim(),'请补充依据及说明');
   q.supplements=(q.supplements||[]).concat({file:copy(f.file),note:f.note,...actor});q.agreementHistory=(q.agreementHistory||[]).concat({file:copy(q.agreement),...actor});q.agreement=copy(f.file);q.needsSupplement=false;q.approval=null;q.approvalValid=false;q.status='待审批';
  }else{
   X.assert(O.has(a.role,'finance'),'请由财务登记实际付款');X.assert(q.approvalValid&&q.approval.signature===F.signature(q),'请先确认当前版本的费用约定');
   X.assert(!f.expectedSignature||f.expectedSignature===F.signature(q),'原付款依据已变化，请刷新');X.assert(['待办理','部分已付'].includes(q.status),'原事项暂不能新增付款登记');
   const n=amount(f.amount);X.assert(n<=q.total-F.reserved(s,q.id),'付款金额超过剩余可登记金额');
   X.assert(date(f.date)&&f.date<=require('../../utils/account').localDate()&&f.file&&f.checked&&String(f.transaction||'').trim(),'请补齐实际日期、流水、凭据并核对');
   const transaction=f.transaction.trim();X.assert(![...s.payments,...s.refunds].some(p=>p.transaction===transaction),'付款流水号重复');
   result='payment-'+Date.now()+'-'+(++sequence);s.payments.push({id:result,payableId:q.id,projectId:q.projectId,customer:q.customer,name:q.name,amount:n,date:f.date,file:copy(f.file),transaction,status:'已核实',registrationMode:'record-only-v1',registeredAt:actor.at,registrarId:a.staffId,registrarPersonId:a.personId,registrar:a.name,approvalId:q.approval.id,sourceSignature:F.signature(q),...actor});
   q.status=F.paid(s,q.id)>=q.total?'已付清':'部分已付';
  }
 }
 s.events=(s.events||[]).concat({action,id:result,...actor});return {id:result};
}
module.exports={apply};
