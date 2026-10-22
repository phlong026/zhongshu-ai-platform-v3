// Design payment and delivery follow the original commission independently of contract display.
const copy=x=>JSON.parse(JSON.stringify(x)),last=rows=>rows?.[rows.length-1]||null;
const first=c=>c.request?.flow==='design-first';
function basis(c){
 const b=last(c.budgets),p=b?.parameters;
 if(!p)return null;
 const width=Number(p.width),depth=Number(p.depth),floors=Number(p.floors),area=Number(b?.area||p.area);
 if(![width,depth,floors,area].every(n=>Number.isFinite(n)&&n>0))return null;
 return {kind:'budget',version:b.version,width,depth,floors,area};
}
function receipt(c){return (c.designReceipts||[]).find(r=>r.version===c.release?.version&&r.signature===c.release?.signature)||null;}
function confirmed(c){return !!(c.release&&c.confirmation&&c.confirmation.version===c.release.version&&(!first(c)||receipt(c)));}
function next(c){
 if(require('./sales-flow').is(c)){const s=require('./sales-flow').next(c);return {...s,view:'delivery',actionable:false};}
 const result=(state,owner,view,label,actionable=false)=>({state,owner,view,label,actionable});
 if(!c.request)return result('待发起设计委托','业务人员','request','发起设计委托',true);
 if(c.request.status==='returned')return result('设计资料待补充','业务人员','request','补齐资料并重新提交',true);
 const s=last(c.serviceOrders),T=require('./intercompany'),M=require('./commercial-approval');
 if(!s&&!c.paymentConditions||c.designFeeState==='missing'){const can=require('./access').workflow('service-prepare',c,true);return result('设计服务费待核定','业务负责人',can?'service-prepare':'service-order',can?'核定设计服务费':'查看服务费核定',can);}
 if(s&&!M.approved(c,'service')||c.designFeeState==='pending')return result('设计服务费待老板审批','老板','service-order','查看服务费审批');
 if(!T.condition(c,'first').ready){const p=c.paymentConditions?null:require('./design-payment-progress').progress(c,'first');return result(p?'设计首款 · '+p.status:'设计首款待核对',p?.owner||'门店／总部财务','payment-first','查看首款办理进度');}
 if(!c.assignment)return result('待总部设计负责人指定设计师','总部设计负责人','formal-progress','查看设计对接安排');
 if(!c.intake)return result('设计需求待核对','总部设计负责人','formal-progress','查看设计资料核对');
 if(!c.designStarted)return result('已派单 · 待开始设计',c.assignment.designer,'formal-progress','查看设计任务');
 const d=last(c.deliverables),r=last(c.deliverableReviews),feedback=last(c.designFeedbacks);
 if(!d||feedback&&d.feedbackId!==feedback.id)return result(feedback?'设计修改中':'正式设计中',c.assignment.designer,'formal-progress','查看设计进度');
 if(r?.version!==d.version||r.status!=='passed')return result(r?.version===d.version&&r.status==='returned'?'成果退回修改':'设计成果待审核','设计负责人','formal-progress','查看成果审核进度');
 if(!c.tailApplication&&!T.condition(c,'tail').ready)return result((c.designOrganizationId===c.companyId?'本店':'总部')+'成果审核通过 · 待业务申请尾款','业务人员','tail-apply','申请设计尾款',true);
 if(!T.condition(c,'tail').ready){const p=c.paymentConditions?null:require('./design-payment-progress').progress(c,'tail');return result(p?'设计尾款 · '+p.status:'设计尾款待核对',p?.owner||'门店／总部财务','payment-tail','查看尾款办理进度');}
 if(!c.release)return result('待正式下发','获授权设计人员','formal-progress','查看正式下发进度');
 if(first(c)&&!receipt(c))return result('已下发 · 待门店收图','业务人员','design-receipt','核查并确认收图',true);
 if(!confirmed(c))return result('待客户成果签认','业务人员','confirmation','登记客户成果签认',true);
 // Signing was completed before commission payment in the current business process.
 // Missing historical contract data is handled in engineering, not as a new design signing step.
 if(!c.contract)return result('设计办理已完成','业务人员','confirmation','查看客户成果签认');
 if(c.acceptance)return result('工程已接收 · 待开工','工程人员','accepted','查看工程接收回执');
 if(c.handover?.status==='returned')return result('工程移交待补充','业务人员','handover','补充资料并重新提交',true);
 if(c.handover)return result('工程待接收','指定工程人员','handover','查看移交进度');
 const drawing=last(c.drawings),review=last((c.reviews||[]).filter(r=>r.drawingVersion===drawing?.version));
 if(!drawing)return result('待补齐施工图','设计师','drawing-review','查看施工图进度');
 if(review?.status!=='passed')return result('施工图待审核','设计负责人','drawing-review','查看施工图审核进度');
 return result('待工程移交','业务人员','handover','准备工程移交',true);
}
module.exports={first,basis,receipt,confirmed,next};
