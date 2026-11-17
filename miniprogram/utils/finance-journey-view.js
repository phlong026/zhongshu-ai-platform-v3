const V=require('./finance-workbench-view');
const {day}=require('./finance-date');
const PF=require('./platform-finance');
const stageName=s=>({SIGN_AFTER:'签约款',BASE_DONE:'基础完成款',CAP_DONE:'封顶款',tail:'交付尾款',FULL:'设计费',FIRST:'设计首款',TAIL:'设计尾款',engineering:'材料采购',engineering_expense:'工程费用'}[s]||s||'其他费用');
const scopeOf=r=>r.projectId||r.project_id?'project:'+String(r.projectId||r.project_id):r.bookId||r.book_id?'book:'+String(r.bookId||r.book_id):'company';
function context(row,books,stages=[]){
 let scope=scopeOf(row);
 const contract=String(row.sourceRef||'').match(/^sale(?:-allocation)?:([^:]+)/);
 const stage=contract&&stages.find(s=>String(s.contract_id)===contract[1]);
 if(contract){const matched=books.find(b=>String(b.sale_contract_id)===contract[1]);scope=matched?matched.scope:stage?.project_id?'project:'+stage.project_id:'contract:'+contract[1];}
 const book=books.find(b=>b.scope===scope);
 return {scope,projectName:book?book.name:stage?stage.customer_name+' · 合同款':scope.startsWith('contract:')?'客户合同款':scope==='company'?'公司日常账':'待补充归属'};
}
function payableRows(facts,books,stages){return PF.payableProgress(facts.payables||[],facts.payments||[],facts.refunds||[]).map(q=>({
 ...q,id:String(q.id),...context(q,books,stages),name:q.name||stageName(q.stage),counterparty:q.recipient||'收款方待补充',
 totalText:V.money(q.totalCents),paidText:V.money(q.paidCents),remainingText:V.money(q.remainingCents),
 label:(q.name||stageName(q.stage))+' · '+(q.recipient||'')+' · 待付 ¥'+V.money(q.remainingCents),
 canAdjust:!['design','reimbursement'].includes(q.sourceType),allowEmployee:q.sourceType==='design',date:day(q.businessDate||q.dueDate)
}));}
function cashRows(facts,books,stages,role){
 const qs=new Map((facts.payables||[]).map(q=>[String(q.id),q]));
 const rs=new Map((facts.receipts||[]).map(r=>[String(r.id),r]));
 const ps=new Map((facts.payments||[]).map(p=>[String(p.id),p]));
 const rows=[];
 const add=(raw,type,sourceKind,date,sign,name,source)=>{
  const q=source||{},key=({receipt:'r-',payment:'p-',refund:'f-'})[sourceKind]+raw.id;
  const refundDir=sourceKind==='refund'?(raw.refundDirection==='expense'?'expense':'client'):'';
  const returned=(facts.refunds||[]).filter(f=>f.status==='已核实'&&String(sourceKind==='receipt'?f.receiptId:f.paymentId)===String(raw.id)&&(sourceKind==='receipt'?f.refundDirection!=='expense':f.refundDirection==='expense')).reduce((n,f)=>n+Number(f.amountCents),0);
  rows.push({...raw,...context(q,books,stages),id:String(raw.id),key,type,sourceKind,label:type==='RECEIPT'?'收款':type==='PAYMENT'?'付款':refundDir==='expense'?'支出退回':'客户退款',
   date:day(date),sign,tone:sign==='+'?'income':'expense',name,amount:V.money(raw.amountCents),amount_cents:raw.amountCents,
   counterparty:q.recipient||q.counterparty||'',source_type:q.sourceType,sourceLabel:q.category||stageName(q.stage)||(q.sourceType==='business'?'合同回款':'其他'),
   account_name:raw.accountName||'',transaction_no:raw.transactionNo||'',voucher_file_id:raw.voucherFileId,statusText:'已登记',funding_type:raw.fundingType||'COMPANY',
   canRefund:['receipt','payment'].includes(sourceKind)&&Number(raw.amountCents)>returned,refundableCents:Math.max(0,Number(raw.amountCents)-returned),
   canRevise:sourceKind==='receipt'&&q.sourceType==='manual',canVoid:PF.canVoidPayment(role,sourceKind,raw.status),canCorrect:sourceKind==='refund'&&role==='finance-lead',category:type==='RECEIPT'?'收款':type==='PAYMENT'?'付款':refundDir==='expense'?'支出退回':'退款'});
 };
 for(const r of facts.receipts||[])if(r.status==='已登记')add(r,'RECEIPT','receipt',r.receivedDate,'+',r.name||'收款',r);
 for(const p of facts.payments||[])if(p.status==='已核实'&&p.fundingType!=='EMPLOYEE'){
  const q=qs.get(String(p.payableId))||p;add(p,'PAYMENT','payment',p.payDate,'−',q.name||stageName(q.stage)||'付款',q);
 }
 for(const f of facts.refunds||[])if(f.status==='已核实'&&!(f.refundDirection==='expense'&&f.fundingType==='EMPLOYEE')){
  const expense=f.refundDirection==='expense',origin=expense?ps.get(String(f.paymentId)):rs.get(String(f.receiptId));
  const q=expense?(qs.get(String(origin&&origin.payableId))||origin):origin;
  add(f,expense?'EXPENSE_REFUND':'REFUND','refund',f.date,expense?'+':'−',(expense?'支出退回 · ':'客户退款 · ')+(q&&q.name||q&&stageName(q.stage)||origin&&origin.name||'原收付款'),q||f);
 }
 return rows.sort((a,b)=>b.date.localeCompare(a.date)||b.id.localeCompare(a.id,undefined,{numeric:true}));
}
function filterCash(rows,{month='',keyword='',direction='all',scope=''}={}){const word=keyword.trim().toLowerCase();return rows.filter(r=>(!month||r.date.slice(0,7)===month)&&(!scope||r.scope===scope)&&(direction==='all'||(direction==='in'?r.sign==='+':r.sign!=='+' ))&&(!word||[r.name,r.projectName,r.counterparty,r.transaction_no].join(' ').toLowerCase().includes(word)));}
module.exports={stageName,scopeOf,context,payableRows,cashRows,filterCash};
