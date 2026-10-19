const X=require('./access');
// Actual makers remain disqualified after custody changes. Review/return and
// handoff authorization are deliberately not maker events: an independent
// reviewer may return a record and review its corrected version later.
function makers(row,companyId){
 if(!row)return [];
 const people=[],add=(id,person)=>{if(id||person)people.push(person||X.personIdFor(id,companyId));};
 for(const key of ['applicant','registrar','financialRegistrar','custodian'])add(row[key+'Id'],row[key+'PersonId']);
 for(const entry of row.supplements||[])add(entry.actorId,entry.personId);
 for(const entry of row.history||[])if(!entry.action&&entry.status==='退回补充'&&entry.file)add(entry.actorId,entry.personId);
 return [...new Set(people)];
}
function approvers(row,companyId){
 if(!row)return [];
 const rows=[row.approval,{actorId:row.approverId,personId:row.approverPersonId},...(row.events||[]).filter(e=>['approve-pay','approval-return'].includes(e.action)),...(row.history||[]).filter(e=>['refund-approve','refund-reject'].includes(e.action))];
 return [...new Set(rows.filter(Boolean).filter(e=>e.actorId||e.personId).map(e=>e.personId||X.personIdFor(e.actorId,companyId)))];
}
const independent=(row,companyId,a=X.context())=>!makers(row,companyId).includes(a.personId);
const separate=(row,companyId,a=X.context())=>independent(row,companyId,a)&&!approvers(row,companyId).includes(a.personId);
function bankReference(row){return row.financialRegistrarId?row.bankTransaction:(!row.source||row.source.module==='intercompany')&&row.status!=='待登记'?row.transaction:'';}
module.exports={makers,approvers,independent,separate,bankReference};
