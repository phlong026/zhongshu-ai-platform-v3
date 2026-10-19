const O=require('./organization'),X=require('./access'),copy=x=>JSON.parse(JSON.stringify(x)),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
function validate(old,row,c,kind){
 const a=X.context(),e=row.confirmationEvidence,n=row.confirmation;
 X.assert(!old.confirmation,'已签认版本只读，请办理变更');
 X.assert(O.has(a.role,'sales')&&e?.status==='confirmed'&&e.file&&e.date&&e.registrarId===a.staffId&&e.registrarPersonId===a.personId,'请由获授权业务人员登记客户真实签认');
 X.assert(n?.status==='confirmed'&&same(n.file,e.file)&&n.date===e.date&&n.version===e.version&&n.registrarId===e.registrarId,'客户签认须绑定同一份材料、日期及版本');
 X.assert(kind!=='confirmation'||c.release&&e.version===c.release.version,'签认必须绑定当前正式下发版本');
}
function process(c,change,f){
 const a=X.context(),row=change||c,e=row.confirmationEvidence;
 X.assert(!row.confirmation&&O.has(a.role,'sales'),'请由获授权业务人员登记客户真实签认，已签认版本只读');
 const file=change?f.changeConfirmationFile:f.confirmationFile,date=change?f.changeConfirmationDate:f.confirmationDate,checked=change?f.changeConfirmationChecked:f.confirmationChecked;
 X.assert(file&&checked&&/^\d{4}-\d{2}-\d{2}$/.test(date||'')&&!Number.isNaN(Date.parse(date))&&new Date(date).toISOString().slice(0,10)===date,'请上传客户真实签认依据并核对日期和版本');
 X.assert(!change||f.changeResultFile,'请关联本次变更成果');
 const evidence={file:copy(file),date,version:change?change.version:c.release?.version,status:'confirmed',registrarId:a.staffId,registrarPersonId:a.personId,registeredAt:new Date().toISOString(),...(change?{result:copy(f.changeResultFile)}:{})};
 row.confirmationHistory=(row.confirmationHistory||[]).concat(e?[copy(e)]:[]);row.confirmationEvidence=evidence;row.confirmation=copy(evidence);
 if(change){row.result=copy(evidence.result);row.status='confirmed';}
 return '客户成果签认已登记';
}
module.exports={validate,process};
