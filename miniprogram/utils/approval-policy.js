// Confirmed business policy. Identity is an actual person, never a switched role.
const X=require('./access'),O=require('./organization');
const deferred=view=>/^(refund|correction)(-|$)/.test(view)||['verify','correct','request-cancel'].includes(view);
const moneyBoss=(a=X.context())=>a.active&&a.role==='boss';
function designReview(c,authors=[],a=X.context()){
 if(!a.active||a.role!=='hq-design-lead'||c.designOrganizationId!==a.companyId||!X.designTeam(c,a))return false;
 if(!authors.includes(a.personId))return true;
 return a.orgType==='store'&&a.roles.includes('hq-designer')&&a.roles.includes('hq-design-lead');
}
function inspectionLead(p){
 const book=X.rawBook(p.companyId||'quanzhou'),engineer=book?.staff.find(r=>r.id===X.managerId(p)),team=p.engineeringTeamId||engineer?.teamIds?.engineering||'engineering';
 const leads=(book?.staff||[]).filter(r=>r.active!==false&&(r.roles||[]).includes('project-manager')&&(r.managedTeams?.engineering||r.managedTeamIds||['engineering']).includes(team));
 const assigned=p.inspectionLeadId?leads.find(r=>r.id===p.inspectionLeadId):leads.find(r=>(r.personId||r.id)===(engineer?.personId||engineer?.id))||leads[0];
 return assigned?{id:assigned.id,personId:assigned.personId||assigned.id,name:assigned.name,samePerson:(assigned.personId||assigned.id)===(engineer?.personId||engineer?.id)}:null;
}
function inspectionSubmission(p){const lead=inspectionLead(p),a=X.context();X.assert(lead,'请在后台配置本工程团队的工程负责人');return {reviewMode:lead.samePerson?'same-person':'independent',reviewStatus:lead.samePerson?'passed':'pending',reviewerId:lead.id,reviewerPersonId:lead.personId,submittedById:a.staffId,submittedByPersonId:a.personId,...(lead.samePerson?{review:{mode:'same-person',...X.actor()}}:{})};}
module.exports={deferred,moneyBoss,designReview,inspectionLead,inspectionSubmission};
