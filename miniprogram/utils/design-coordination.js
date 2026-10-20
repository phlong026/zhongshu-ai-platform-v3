// A design commission belongs to the customer project before a construction contract exists.
const copy=x=>JSON.parse(JSON.stringify(x)),last=rows=>rows?.[rows.length-1]||null;
const first=c=>c.request?.flow==='design-first';
function basis(c){
 const m=last(c.measurements),b=last(c.budgets),p=m||b?.parameters;
 if(!p)return null;
 const width=Number(p.width),depth=Number(p.depth),floors=Number(p.floors),area=Number(p.area||b?.area);
 if(![width,depth,floors,area].every(n=>Number.isFinite(n)&&n>0))return null;
 return {kind:m?'measurement':'budget',version:m?.version||b.version,width,depth,floors,area,file:m?.file?copy(m.file):null};
}
function request(c,f,date){
 const source=basis(c),flow=c.request?.flow||(c.contract?'legacy':'design-first');
 if(!source||!String(f.requestText||'').trim())throw Error('请先保存有效预算或现场测量资料，并填写设计需求。');
 if(flow==='design-first'&&(!String(f.requestScope||'').trim()||!f.designBasisChecked))throw Error('请填写本次设计交付范围，并核对需求、尺寸及资料来源。');
 return {version:(c.request?.version||0)+1,commissionId:c.request?.commissionId||'design:'+c.projectId,flow,text:f.requestText.trim(),scope:String(f.requestScope||c.request?.scope||'按本次需求及设计服务约定交付').trim(),date,basis:source,basisChecked:!!f.designBasisChecked,budgetVersion:last(c.budgets)?.version||null,planVersion:last(c.plans)?.version||null,facadeVersion:last(c.facades)?.version||null,contractVersion:c.contract?.version||null,measurementVersion:last(c.measurements)?.version||null,attachment:f.requestAttachment?copy(f.requestAttachment):null};
}
function receipt(c){return (c.designReceipts||[]).find(r=>r.version===c.release?.version&&r.signature===c.release?.signature)||null;}
function confirmed(c){return !!(c.release&&c.confirmation&&c.confirmation.version===c.release.version&&(!first(c)||receipt(c)));}
function quoteCurrent(c){const q=last(c.quotes);return !!(q&&(!first(c)||q.designVersion===c.release?.version&&q.designSignature===c.release?.signature));}
function next(c){
 const result=(state,owner,view,label,actionable=false)=>({state,owner,view,label,actionable});
 if(!c.request)return result('待发起设计委托','业务人员','request','发起设计委托',true);
 if(c.request.status==='returned')return result('设计资料待补充','业务人员','request','补齐资料并重新提交',true);
 const s=last(c.serviceOrders),T=require('./intercompany'),M=require('./commercial-approval');
 if(!s&&!c.paymentConditions||c.designFeeState==='missing'){const can=require('./access').workflow('service-prepare',c,true);return result('设计服务费待核定','业务负责人',can?'service-prepare':'service-order',can?'核定设计服务费':'查看服务费核定',can);}
 if(s&&!M.approved(c,'service')||c.designFeeState==='pending')return result('设计服务费待老板审批','老板','service-order','查看服务费审批');
 if(!T.condition(c,'first').ready)return result('设计首款待核对','门店／总部财务','payment-first','查看首款办理进度');
 if(!c.intake)return result('总部待受理','总部设计负责人','formal-progress','查看总部受理进度');
 if(!c.assignment)return result('总部待派单','总部设计负责人','formal-progress','查看派单进度');
 if(!c.designStarted)return result('已派单 · 待开始设计',c.assignment.designer,'formal-progress','查看设计任务');
 const d=last(c.deliverables),r=last(c.deliverableReviews),feedback=last(c.designFeedbacks);
 if(!d||feedback&&d.feedbackId!==feedback.id)return result(feedback?'设计修改中':'正式设计中',c.assignment.designer,'formal-progress','查看设计进度');
 if(r?.version!==d.version||r.status!=='passed')return result(r?.version===d.version&&r.status==='returned'?'成果退回修改':'设计成果待审核','设计负责人','formal-progress','查看成果审核进度');
 if(!c.tailApplication&&!T.condition(c,'tail').ready)return result('待申请设计尾款','业务人员','tail-apply','申请设计尾款',true);
 if(!T.condition(c,'tail').ready)return result('设计尾款待核对','门店／总部财务','payment-tail','查看尾款办理进度');
 if(!c.release)return result('待正式下发','获授权总部人员','formal-progress','查看正式下发进度');
 if(first(c)&&!receipt(c))return result('已下发 · 待门店收图','业务人员','design-receipt','核查并确认收图',true);
 if(!confirmed(c))return result('待客户成果签认','业务人员','confirmation','登记客户成果签认',true);
 if(!c.contract){if(!quoteCurrent(c))return result('设计已确认 · 待建造报价','业务人员','measurement','继续实测与建造报价',true);if(!M.approved(c,'quote'))return result('建造报价待老板审批','老板','quote','查看建造报价审批');return result('待签建造合同','业务人员','contract-prepare','登记建造合同签署',true);}
 if(c.acceptance)return result('工程已接收 · 待开工','工程人员','accepted','查看工程接收回执');
 if(c.handover)return result('工程待接收','指定工程人员','handover','查看移交进度');
 const drawing=last(c.drawings),review=last((c.reviews||[]).filter(r=>r.drawingVersion===drawing?.version));
 if(!drawing)return result('待补齐施工图','设计师','drawing-review','查看施工图进度');
 if(review?.status!=='passed')return result('施工图待审核','设计负责人','drawing-review','查看施工图审核进度');
 return result('待工程移交','业务人员','handover','准备工程移交',true);
}
function receive(c,f,date){
 if(!c.release)throw Error('正式下发后才能确认收图。');
 if(receipt(c))return;
 if(!f.designReceiveChecked)throw Error('请先核对本版正式下发文件完整且可读取。');
 const a=require('./access').context();
 (c.designReceipts||(c.designReceipts=[])).push({version:c.release.version,signature:c.release.signature,requestVersion:c.request?.version,date,checked:true,files:copy(c.release.files),note:String(f.designReceiveNote||'').trim(),receivedById:a.staffId,receivedByPersonId:a.personId,companyId:a.companyId});
}
function feedback(c,f,date){
 if(!c.release||c.confirmation||c.handover)throw Error('仅当前已下发且尚未签认的成果可提交修改意见。');
 if(!String(f.designFeedbackNote||'').trim())throw Error('请填写本版需要修改的具体内容。');
 const a=require('./access').context(),row={id:'design-feedback-'+Date.now()+'-'+(c.designFeedbacks||[]).length,version:c.release.version,requestVersion:c.request.version,note:f.designFeedbackNote.trim(),file:f.designFeedbackFile?copy(f.designFeedbackFile):null,date,authorId:a.staffId,authorPersonId:a.personId};
 (c.designFeedbacks||(c.designFeedbacks=[])).push(row);
 (c.designHistory||(c.designHistory=[])).push({release:copy(c.release),receipt:receipt(c)?copy(receipt(c)):null,feedbackId:row.id});
 c.release=null;
}
module.exports={first,basis,request,receipt,confirmed,quoteCurrent,next,receive,feedback};
