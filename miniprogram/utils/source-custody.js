const copy=require('./clone');
// Follow-up custody changes; original applicants, receivers and evidence stay.
function transfer(book,module,source,t,to){
 const assign=row=>{row.transferHistory=(row.transferHistory||[]).concat(copy(t));row.custodianId=to.id;row.custodianPersonId=to.personId||to.id;};
 for(const r of source[module==='business'?'receipts':'collections']||[])if(!r.financialRegistrarId){r.registrarId=r.registrarId||t.from;assign(r);}
 if(module==='business'){
  const order=source.serviceOrders?.at(-1);if(order){order.financeStates=order.financeStates||{};for(const stage of ['first','tail']){const application=source[stage==='first'?'request':'tailApplication'];if(application)application.applicantId=application.applicantId||t.from;const state=order.financeStates[stage]||{};assign(state);order.financeStates[stage]=state;}}
 }else for(const supply of source.supplies||[]){supply.applicantId=supply.applicantId||t.from;assign(supply);}
 for(const row of book.payables||[])if(row.source?.module===module&&row.source.id===source.id){row.applicantId=row.applicantId||t.from;assign(row);}
 for(const row of book.receipts||[])if(row.source?.module===module&&row.source.id===source.id&&!row.financialRegistrarId){row.registrarId=row.registrarId||t.from;assign(row);}
}
module.exports={transfer};
