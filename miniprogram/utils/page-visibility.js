const Job=require('./organization');
const X=require('./access'),C=require('./task-access');
const controls=new Set(['input','textarea','upload','check','pick','picker','choice','field','metric-inputs','photos','upload-field','media','material-mode']);
function readonly(sections,key='items',build=false){return (sections||[]).map(s=>({...s,[key]:(s[key]||[]).filter(i=>!controls.has(i.type)&&(!build||i.type!=='file'||i.savedFile))})).filter(s=>s[key].length);}
function engineering(p,action,a=X.context()){
 if(!p||!a.active)return false;
 if(action==='delivery-review')return a.role==='project-manager'&&X.engineeringTeam(p,a)&&p.delivery?.status==='待核验'&&!X.isSelf(p.delivery.actorId,p.companyId,p.delivery.personId);
 if(action==='review')return a.role==='hq-design-lead'&&p.reviewerId===a.staffId&&p.review?.status==='pending'&&!X.isSelf(p.review.requestedById,p.companyId,p.review.personId);
 if(!Job.has(a.role,'engineer'))return false;
 if(X.managerId(p)===a.staffId)return true;
 const resource=p.sourceCustomerId?require('./business').customer(p.sourceCustomerId)||p:p;
 return C.allows(resource,'engineering',action,a);
}
module.exports={readonly,engineering,controls};
