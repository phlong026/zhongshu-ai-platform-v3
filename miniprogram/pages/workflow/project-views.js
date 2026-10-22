const X=require('../../utils/access'),Job=require('../../utils/organization'),R=require('../../utils/project-records'),D=require('../../utils/design-coordination');
const date=value=>String(value||'').replace('T',' ').replace(/\.\d+Z$/,'').slice(0,16);
const short=id=>String(id||'').length>24?String(id).slice(0,8)+'…'+String(id).slice(-8):id;
// Project header facts shared by the customer workspace and the approval / authorization pages.
const compactViews=['money-review','task-access'];
function decorate(view,c,f,out){
 out.primaryView='';const a=X.context(),basis=R.basis(c);
 out.projectSummary=[c.city?.replace(/市$/,''),basis.floors?basis.floors+'层乡墅':'层数待补齐',basis.area?basis.area+'㎡':'面积待补齐'].filter(Boolean).join(' · ');
 out.projectNumber=short(c.projectId);out.projectBasis=basis;
 const financeBook=X.rawBook(a.companyId);out.canProjectFinance=c.companyId===a.companyId&&(Job.has(a.role,'sales')||Job.has(a.role,'engineer')||Job.has(a.role,'finance')||X.isBoss(a))&&!!financeBook&&require('../../utils/operations').canRead(c.projectId,{...financeBook,companyId:a.companyId});
 out.compactProject=compactViews.includes(view);
 if(out.compactProject){const n=D.next(c);out.taskSummary={state:n.state,owner:n.owner};}else out.taskSummary=null;
 for(const s of out.sections||[])for(const i of s.items||[]){
  if(i.type==='input'&&i.label.length>6)i.long=true;
  if(i.type==='timeline')for(const p of i.values)p.date=date(p.date);
 }
}
module.exports={decorate};
