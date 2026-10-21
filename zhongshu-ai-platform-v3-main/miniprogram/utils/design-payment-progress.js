const X=require('./access'),T=require('./intercompany'),F=require('./finance-source');
function progress(c,stage){
 const order=c.serviceOrders?.at(-1),condition=T.condition(c,stage),id='business-design:'+c.id+':'+(order?.version||1)+':'+stage;
 const raw=F.book(c.companyId),book=raw?F.merge(raw):{payables:[],payments:[]},payable=book.payables.find(r=>r.id===id);
 const rows=book.payments.filter(r=>r.payableId===id&&!['已撤销','确认未付款'].includes(r.status));
 const expected=condition.expected||Math.round(Number(order?.[stage]||0)*100),paid=condition.paid||0,registered=rows.reduce((n,r)=>n+r.amount,0),pending=rows.find(r=>['待复核','需补充','待核实'].includes(r.status));
 const received=condition.received||0,external=!!c.designOrganizationId&&c.designOrganizationId!==c.companyId;
 const auto=rows.some(r=>r.autoReview&&require('./same-person-routing').valid(r));
 let status=condition.ready?'已完成':pending?.status==='需补充'?'付款凭证待补充':pending?'待付款复核':paid>0&&paid<expected?'部分支付':paid>=expected&&external?'待总部到账核对':payable?.needsSupplement?'付款申请待补充':payable&&!payable.approvalValid?'待老板审批':'待财务付款登记';
 const owner=condition.ready?'获授权设计人员':pending?.status==='需补充'?'门店财务':pending?'门店财务负责人':paid>=expected&&external?'总部财务／财务负责人':payable?.needsSupplement?'原业务申请人':payable&&!payable.approvalValid?'加盟商老板':'门店财务';
 return {id,condition,expected,paid,registered,received,remaining:Math.max(0,expected-paid),pending,payable,external,auto,status,owner};
}
module.exports={progress};
