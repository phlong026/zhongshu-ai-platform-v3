const Job=require('./organization');
const X=require('./access'),C=require('./task-access');
const controls=new Set(['input','textarea','upload','check','pick','picker','choice','field','metric-inputs','photos','upload-field','media','material-mode']);
function readonly(sections,key='items',build=false){return (sections||[]).map(s=>({...s,[key]:(s[key]||[]).filter(i=>!controls.has(i.type)&&(!build||i.type!=='file'||i.savedFile))})).filter(s=>s[key].length);}
function engineering(p,action,a=X.context()){
 if(!p||!a.active)return false;
 if(action==='delivery-review')return false;
 if(action==='inspection-review')return a.role==='project-manager'&&X.sameCompany(p,a)&&X.engineeringTeam(p,a)&&p.inspections.concat(p.rectifications).some(r=>r.reviewStatus==='pending'&&r.reviewerId===a.staffId&&r.submittedByPersonId!==a.personId);
 if(action==='review')return false;
 if(!Job.has(a.role,'engineer'))return false;
 if(a.role==='project-manager'&&X.sameCompany(p,a)&&X.engineeringTeam(p,a))return true;
 if(X.engineeringExecutor(p,a))return true;
 const resource=p.sourceCustomerId?require('./business').customer(p.sourceCustomerId)||p:p;
 return C.allows(resource,'engineering',action,a);
}
module.exports={readonly,engineering,controls};
