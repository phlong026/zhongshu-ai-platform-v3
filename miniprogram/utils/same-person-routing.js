// Only content approval and actual bank registration use this exception.
// Professional design, delivery, business approval and corrections stay independent.
const O=require('./organization');
const LEADS={sales:'sales-lead',finance:'finance-lead'};
function lead(profession,c,teamId){const X=require('./access');if(!c?.active||!O.has(c.role,profession))return null;const book=X.rawBook(c.companyId),team=teamId||c.teamId;return book?.staff?.find(p=>p.active!==false&&(p.personId||p.id)===c.personId&&(p.roles||[p.roleId]).includes(LEADS[profession])&&(p.managedTeams?.[profession]||p.managedTeamIds||[profession]).includes(team));}
function audit(profession,row,c,version){const p=lead(profession,c,profession==='finance'?row.financeTeamId:row.teamId);if(!p)return null;return {mode:'same-person-auto',label:'系统自动审核／复核（同人兼岗）',rule:'same-person-registration-v1',profession,companyId:c.companyId,objectId:row.id,version,makerId:c.staffId,makerPersonId:c.personId,leadId:p.id,leadPersonId:p.personId||p.id,leadRole:LEADS[profession],at:new Date().toISOString()};}
function refundMaker(q,companyId,c){const X=require('./access'),P=require('./finance-participation');return !!(c?.active&&companyId===c.companyId&&(!q.companyId||q.companyId===companyId)&&(P.separate(q,companyId,c)||lead('finance',c,q.financeTeamId)&&!P.approvers(q,companyId).includes(c.personId)));}
function binding(r){return JSON.stringify([r.id,r.amount,r.transaction,r.bankTransaction,r.bankDate||r.date,r.bankFile||r.file,r.sourceSignature||null,r.supplements?.length||0]);}
function valid(row,profession='finance'){const q=row?.autoReview;return !!(q&&q.mode==='same-person-auto'&&q.rule==='same-person-registration-v1'&&q.profession===profession&&q.objectId===row.id&&(!row.companyId||q.companyId===row.companyId)&&q.makerPersonId===q.leadPersonId&&q.leadId===row.reviewerId&&q.leadPersonId===row.reviewerPersonId&&q.version===binding(row));}
module.exports={lead,audit,binding,valid,refundMaker};
