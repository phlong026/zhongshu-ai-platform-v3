// Closing a construction file and closing its financial account are separate facts.
const X=require('./access'),clone=require('./clone');
function snapshot(s,id){
 const Ops=require('./operations'),F=require('./finance-source'),p=Ops.project(id,s),ar=Ops.receivable(s,id),cost=Ops.cost(s,id),material=require('./material-finance'),blockers=[];
 const url=view=>'/pages/admin/operations/index?view='+view+'&id='+encodeURIComponent(id);
 const add=(key,text,view,amount=0)=>blockers.push({key,text,view,amount,url:url(view)});
 if(!p.engineering?.delivery)add('delivery','工程尚未完成交付','receivables');
 if(!ar.total)add('contract','有效建造合同尚未齐备','receivables');
 if(ar.remaining)add('receivable','合同款尚未收齐','receivables',ar.remaining);
 if(ar.advance)add('advance','合同实收超过约定，需核对款项归属','receivables',ar.advance);
 const qs=(s.payables||[]).filter(q=>q.projectId===id),unpaid=qs.reduce((n,q)=>n+Math.max(0,q.total-F.paid(s,q.id)),0);
 if(unpaid)add('payable','设计、材料或费用应付尚未付清','payables',unpaid);
 const pending=[...(s.receipts||[]).filter(r=>r.projectId===id&&!['已核算','已备案','已撤销'].includes(r.status)),...(s.payments||[]).filter(r=>r.projectId===id&&!['已核实','确认未付款','已撤销'].includes(r.status))];
 if(pending.length)add('pending','仍有到账、付款或补证待核对记录','payables');
 if(!cost.complete||cost.remaining!==0)add('cost','各类成本及剩余成本尚未核对完毕','costs',cost.remaining||0);
 const direct=material.direct(s).filter(r=>r.projectId===id),directRemaining=direct.reduce((n,r)=>n+Math.max(0,r.total-r.paid),0);
 if(directRemaining)add('direct','业主直付尚未完成事实核对','direct',directRemaining);
 const accounts=material.accounts(s).filter(r=>r.projectId===id),intercompany=accounts.reduce((n,r)=>n+r.remaining+r.unconfirmed,0);
 if(intercompany)add('intercompany','总部材料代垫尚未完成双方结清','intercompany',intercompany);
 const signature=JSON.stringify([ar.total,ar.received,qs.map(q=>[q.id,q.total,F.paid(s,q.id)]),pending.map(r=>[r.id,r.status,r.amount]),cost.signature,cost.remaining,cost.complete,direct.map(r=>[r.id,r.total,r.paid]),accounts.map(r=>[r.id,r.advanced,r.repaid,r.received]),p.engineering?.delivery?.id||'']);
 const history=(s.operations?.projectClosures||[]).filter(r=>r.projectId===id),record=history.at(-1),closed=!!record&&!blockers.length&&record.signature===signature,ready=!blockers.length;
 return {projectId:id,status:closed?'closed':ready?'ready':'pending',label:closed?'财务已结清':ready?'待确认财务结清':'财务待收尾',ready,closed,needsReclose:!!record&&!closed,blockers,record:closed?record:null,history,signature,url:url('settlement')};
}
function close(s,id,f){const a=X.context();X.assert(a.active&&a.companyId===s.companyId&&['finance-lead','boss'].includes(a.role),'请由本组织财务负责人核对项目结清');X.assert(require('./operations').canRead(id,s),'当前岗位无权关闭本项目账');const row=snapshot(s,id);X.assert(row.ready,row.blockers.map(b=>b.text).join('；'));X.assert(!row.closed,'本项目当前账目已确认结清');X.assert(f.checked&&f.file&&String(f.note||'').trim(),'请核对收付款、成本和往来，并保存结清说明及依据');const record={id:'project-close-'+Date.now(),projectId:id,signature:row.signature,note:String(f.note).trim(),file:clone(f.file),...X.actor()};s.operations.projectClosures.push(record);return {id};}
module.exports={snapshot,close};
