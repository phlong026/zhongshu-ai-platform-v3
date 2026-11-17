const test=require('node:test'),assert=require('node:assert/strict'),PF=require('../miniprogram/utils/platform-finance'),V=require('../miniprogram/utils/finance-workbench-view'),{day}=require('../miniprogram/utils/finance-date');
test('actual HTTP date formats share the Shanghai accounting day across month boundaries',()=>{
 for(const date of ['2026-11-17',[2026,11,17],1794844800000])assert.equal(day(date),'2026-11-17');
 assert.equal(day(Date.UTC(2026,9,31,16)),'2026-11-01');assert.equal(day(null),'');assert.equal(day([2026,2,30]),'');
 const rows=[{id:'r',status:'已登记',receivedDate:1794844800000,amountCents:10000000}];const paid=[{id:'p',status:'已核实',payDate:[2026,11,17],amountCents:1100000}];
 assert.equal(PF.metrics(rows,paid,[],'2026-11').net,'89000.00');assert.equal(PF.metrics(rows,paid,[],'2026-10').received,'0.00');assert.equal(PF.ledgerRows(rows,paid,[],'2026-11').rows.length,2);
});
test('ledger money uses separators and never exposes technical source fields as titles',()=>{
 assert.equal(V.money(60000000),'600,000.00');assert.equal(V.money(-123456),'-1,234.56');
 const rows=V.bookRows([{id:'1',type:'PAYMENT',date:[2026,11,17],amount_cents:600000,name:'engineering',source_ref:'1118:1:FULL',status:'已核实'},{id:'2',type:'PAYABLE',amount_cents:240000,name:'FULL',source_type:'design'},{id:'3',type:'PAYABLE',amount_cents:240000,name:'设计费报销',source_type:'reimbursement',category:'员工往来'},{id:'4',type:'ADVANCE',amount_cents:240000,name:'FULL'}]);
 assert.equal(rows[0].name,'材料采购');assert.equal(rows[0].date,'2026-11-17');assert.equal(V.filteredRows(rows,'cost').length,1);assert.equal(V.filteredRows(rows,'cash').length,1);assert.equal(V.filteredRows(rows,'employee').length,2);
});
test('book search and scope filters keep company/project/early books distinct',()=>{
 const books=[{scope:'company',name:'公司日常账',status:'公司日常'},{scope:'project:1',name:'张先生乡墅',status:'PENDING_START'},{scope:'book:2',name:'李先生前期',status:'前期账本'}];
 assert.equal(V.books(books,'张','all')[0].statusText,'待开工');assert.equal(V.books(books,'张','company').length,0);assert.equal(V.books(books,'','book').length,1);
});
