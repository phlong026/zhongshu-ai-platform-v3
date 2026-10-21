const X=require('./access'),R=require('./same-person-routing'),P=require('./finance-participation'),F=require('./finance-source');
const copy=x=>JSON.parse(JSON.stringify(x));
function finish(s,row,kind){const a=X.context(),proof=R.audit('finance',row,a,R.binding(row));if(!proof)return false;
 X.assert(s.companyId===a.companyId&&(!row.companyId||row.companyId===a.companyId),'不能自动核对其他组织记录');
 const maker=row.custodianId||row.financialRegistrarId||row.registrarId;X.assert(maker===a.staffId,'仅当前实际登记或补证人可触发自动复核');
 X.assert(row.file||row.bankFile,'请先补齐原笔到账或实付凭证');
 if(kind==='receipt'){
  X.assert(row.status==='待核算'&&(!row.source||row.source.module==='intercompany'||row.financialRegistrarId),'请先完成本方财务实际到账登记');
  const E=require('./cross-evidence');if((s.crossQueries||[]).some(q=>q.receiptId===row.id&&q.status!=='已关闭'&&(!E.reply(q)||!row.crossEvidenceVersions?.some(v=>v.queryId===q.id&&v.requestVersion===q.requestVersion&&v.replyVersion===E.reply(q).version))))return false;E.review(s,row,{...a,automatic:true});row.status='已核算';
 }else{
  X.assert(row.status==='待复核','自动复核仅适用于已查实登记或补证的原笔');
  if(kind==='payment'){const q=s.payables.find(q=>q.id===row.payableId);X.assert(q&&q.approvalValid&&q.approval?.signature===F.signature(q)&&row.sourceSignature===F.signature(q),'原申请或审批版本已变化');X.assert(P.separate(q,s.companyId,a),'业务申请和批准仍须与实际付款办理分离');X.assert(F.paid(s,q.id)+row.amount<=q.total,'累计付款超出原审批金额');row.applicantId=q.applicantId;row.applicantPersonId=X.personIdFor(q.applicantId,s.companyId);row.approverId=q.approval.actorId;row.approverPersonId=q.approval.personId;row.participantPersonIds=[...new Set([...P.makers(row,s.companyId),...P.makers(q,s.companyId),...P.approvers(q,s.companyId)])];}
  if(kind==='refund'){const q=s.refundRequests?.find(q=>q.id===row.refundRequestId);X.assert(q&&q.status==='已登记待复核'&&R.refundMaker(q,s.companyId,a),'原退款须经有效业务批准，财务办理人不能兼任业务批准人');q.status='已核对';row.participantPersonIds=[...new Set([...P.makers(row,s.companyId),...P.makers(q,s.companyId),...P.approvers(q,s.companyId)])];}
  row.status='已核实';row.reviewFile=copy(row.file);row.reviewOpinion=proof.label;
 }
 row.reviewerId=proof.leadId;row.reviewerPersonId=proof.leadPersonId;row.reviewedAt=proof.at;row.autoReview=proof;
 row.events=(row.events||[]).concat({action:'system-auto-'+kind,companyId:s.companyId,objectId:row.id,autoReview:copy(proof),at:proof.at});
 s.events=(s.events||[]).concat({action:'system-auto-'+kind,id:row.id,companyId:s.companyId,autoReview:copy(proof),at:proof.at});return true;
}
module.exports={finish};
