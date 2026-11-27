const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm'),{createRequire}=require('module'),path=require('path');
const J=require('../miniprogram/utils/finance-journey-view'),V=require('../miniprogram/utils/finance-workbench-view'),PF=require('../miniprogram/utils/platform-finance');
test('contract receipts and their refunds show their source as contract collections',()=>{
 const source={id:'raw',sourceType:'business',status:'已登记',amountCents:5000};
 const refund={id:'ret',receiptId:source.id,status:'已核实',refundDirection:'client',amountCents:5000};
 const rows=J.cashRows({receipts:[source],refunds:[refund]},[],[],'finance');
 assert.equal(rows.length,2);assert.ok(rows.every(row=>row.sourceLabel==='合同回款'));
 assert.equal(rows.find(row=>row.id==='raw').statusText,'全额已退 ¥50.00');
 const partial=J.cashRows({receipts:[source],refunds:[{...refund,amountCents:1000}]},[],[],'finance');
 assert.equal(partial.find(row=>row.id==='raw').statusText,'已退 ¥10.00');
});
test('same-day identical refund choices distinguish original transactions and support missing or colliding references',()=>{
 const sources=['101','102','103','104','105'].map(id=>({id,refundableCents:id==='105'?0:5000}));
 const transactions=['BANK-111111-1','BANK-111111-2','','ALT-111111-2',''];
 const cash=sources.map(({id},i)=>({id,sourceKind:'receipt',projectName:'测试乡墅',name:'签约款',transaction_no:transactions[i]}));
 const options=J.refundOptions(sources,cash,'receipt');
 assert.equal(options.length,4);
 assert.equal(new Set(options.map(row=>row.label)).size,4);
 assert.match(options[0].label,/^流水尾号 111111-1/);
 assert.match(options[1].label,/^原单 102/);
 assert.equal(options[1].reference,'BANK-111111-2');
 assert.deepEqual([options[2].referenceLabel,options[2].reference],['原单号','103']);
 assert.match(options[3].label,/^原单 104/);
});
const facts={receipts:[{id:'r',status:'已登记',amountCents:100000,sourceType:'business',sourceRef:'sale:c:SIGN_AFTER:r',name:'业主首款',receivedDate:'2026-11-17'}],payables:[{id:'q',projectId:'9007199254740999',stage:'engineering',totalCents:50000,recipient:'材料商',status:'进行中'},{id:'d',projectId:'9007199254740999',stage:'FULL',sourceType:'design',totalCents:240000,status:'进行中'}],payments:[{id:'p',payableId:'q',status:'已核实',amountCents:20000,payDate:'2026-11-17',fundingType:'COMPANY'},{id:'personal',payableId:'d',status:'已核实',amountCents:240000,payDate:'2026-11-17',fundingType:'EMPLOYEE'}],refunds:[{id:'return',status:'已核实',paymentId:'p',refundDirection:'expense',fundingType:'COMPANY',amountCents:5000,date:'2026-11-17'},{id:'personal-return',status:'已核实',paymentId:'personal',refundDirection:'expense',fundingType:'EMPLOYEE',amountCents:10000,date:'2026-11-17'}]};
const books=[{scope:'company',name:'公司日常账'},{scope:'project:9007199254740999',name:'张先生乡墅'}],stages=[{id:'s',contract_id:'c',project_id:'9007199254740999',customer_name:'张先生',stage:'SIGN_AFTER',amount_cents:200000,collected_cents:100000}];
function harness(){const dir=path.resolve('miniprogram/pages/admin/finance-desk'),http={currentPostId:()=>123,get:async()=>({entries:[]}),requestGuard:()=>()=>true,sessionGuard:()=>()=>true,post:async()=>({id:'new-record'})},auth={currentApiPost:()=>({roleCode:'finance',orgType:'store'})};let calls=[];
 const wx={pageScrollTo(){},showToast(){},showModal(){}};
 function attach(name){const file=path.join(dir,name+'.js'),real=createRequire(file),mod={exports:{}};vm.runInNewContext(fs.readFileSync(file,'utf8'),{module:mod,require:n=>n.endsWith('/api/http')?http:n.endsWith('/api/auth')?auth:n.endsWith('/account')?{localDate:()=> '2026-11-17'}:real(n),wx,console});return mod.exports;}
 const api={revisions:async()=>[],idemKey:()=> 'sticky-key',registerPayment:async(body,key)=>{calls.push({body,key});return {paymentId:'p-new'}},uploadVoucher:async()=> 'voucher'};
 const page={data:{view:'book',title:'项目账本',flowTab:'ledger',journeyEnabled:true,bookScope:'project:9007199254740999',bookName:'张先生乡墅',bookFlowTab:'employee',workbenchBooks:books,financialStages:stages,collectionDeclarations:[],reimbursementRows:[{id:'reim',payable_id:'reim',project_id:'9007199254740999',payer_name:'业务甲',stage:'设计费报销',remaining:140000,eligible_cents:240000,reimbursed_cents:100000,pay_date:'2026-11-17'}],platformRegister:{payables:[],refundable:[],expenseRefundable:[]}},options:{view:'book',bookScope:'project:9007199254740999'},__financeFacts:facts,setData(patch){for(const[k,v]of Object.entries(patch)){let d=this.data;const bits=k.replace(/\[(\d+)\]/g,'.$1').split('.');for(const f of bits.slice(0,-1))d=d[f]||(d[f]={});d[bits.at(-1)]=v}},loadPlatformFinance(){},refresh(){},syncPlatformTasks(){},loadDesignFees(){},paySourceRow(){},openBookEntry(){}};
 attach('platform-actions')(page,api,PF);attach('workbench')(page,api);page.installWorkbenchActions();page.loadWorkbench=()=>{};attach('journey')(page,api);page.refreshJourney();return {page,calls,http,api,wx,auth};}
const event=(type,extra={})=>({currentTarget:{dataset:{type,...extra}}});
function openDeclaration(h, overrides={}) {
 const row={id:'9007199254741999',contract_id:'c',stage_id:'s',stage:'SIGN_AFTER',customer_name:'张先生',
  amount_cents:15000,received_date:'2026-11-17',transaction_no:'actual-bank-reference',voucher_file_id:'9007199254742000',
  method:'bank',account_name:null,status:'PENDING',...overrides};
 h.page.data.collectionDeclarations=[row];h.page.refreshJourney();
 const click=event('',{id:row.id});h.page.openJourneyDeclaration(click);return click;
}
test('原到账核实表单带入已有账户与方式，并只把实际补齐资料提交到原申报',async()=>{
 const h=harness(),click=openDeclaration(h,{account_name:'已申报账户',method:'other'}),writes=[];
 h.http.post=async(...args)=>{writes.push(JSON.parse(JSON.stringify(args)));return {receiptId:'9007199254742999'};};
 assert.deepEqual(JSON.parse(JSON.stringify(h.page.data.declarationForm)),{accountName:'已申报账户',method:'other'});
 const {render}=require('./role-page-runtime.cjs'),flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
 let nodes=flatten(render(path.resolve('miniprogram/pages/admin/finance-desk/journey.wxml'),h.page.data));
 assert.ok(nodes.some(n=>n.tag==='input'&&n.attrs?.['data-field']==='accountName'&&n.attrs.bindinput==='declarationInput'));
 assert.ok(nodes.some(n=>n.tag==='picker'&&n.attrs?.['data-field']==='method'&&n.attrs.bindchange==='declarationInput'));
 h.page.declarationInput({currentTarget:{dataset:{field:'accountName'}},detail:{value:' '}});
 await h.page.confirmCollectionDeclaration(click);assert.equal(writes.length,0);assert.match(h.page.data.workbenchError,/实际收款账户/);
 h.page.declarationInput({currentTarget:{dataset:{field:'method'}},detail:{value:'1'}});
 h.page.journeyPick({currentTarget:{dataset:{field:'account'}},detail:{value:String(h.page.data.journeyAccounts.indexOf('现金'))}});
 await h.page.confirmCollectionDeclaration(click);
 assert.deepEqual(writes,[['/franchise/sales/collection-declarations/9007199254741999/confirm',{accountName:'现金',method:'cash'},{'Idempotency-Key':'sticky-key'}]]);
 assert.equal(h.page.data.journeyResult.id,'9007199254742999');assert.equal(h.page.data.journeyResult.amount,'150.00');
 assert.equal(h.page.data.declarationPending,false);assert.equal(h.page.__declarationPending,null);
});
test('到账核实回执丢失后冻结原申报账户方式和键，只能原样重试',async()=>{
 const h=harness(),click=openDeclaration(h,{account_name:'实际银行账户'}),writes=[];
 h.http.post=async(...args)=>{writes.push(JSON.parse(JSON.stringify(args)));if(writes.length===1)throw {code:-1};return {receiptId:'9007199254742999'};};
 await h.page.confirmCollectionDeclaration(click);assert.equal(h.page.data.declarationPending,true);assert.equal(h.page.data.wbBusy,false);
 const {render}=require('./role-page-runtime.cjs'),flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
 const nodes=flatten(render(path.resolve('miniprogram/pages/admin/finance-desk/journey.wxml'),h.page.data));
 assert.ok(nodes.filter(n=>['input','picker'].includes(n.tag)).every(n=>n.attrs.disabled));
 assert.ok(nodes.find(n=>n.attrs?.bindtap==='returnCollectionDeclaration').attrs.disabled);
 h.page.declarationInput({currentTarget:{dataset:{field:'accountName'}},detail:{value:'另一账户'}});
 h.page.journeyPick({currentTarget:{dataset:{field:'account'}},detail:{value:'0'}});
 h.page.closeFinanceEntry();h.page.openWorkbenchEntry(event('receipt'));
 assert.equal(h.page.data.journeyDeclaration.id,'9007199254741999');assert.equal(h.page.data.declarationForm.accountName,'实际银行账户');
 await h.page.confirmCollectionDeclaration(event('',{id:'9007199254741888'}));assert.equal(writes.length,1);
 h.page.data.declarationForm={accountName:'绕过输入处理器的更改',method:'other'};
 await h.page.confirmCollectionDeclaration(click);
 assert.deepEqual(writes[1],writes[0]);assert.equal(h.page.data.journeyResult.id,'9007199254742999');assert.equal(h.page.data.declarationPending,false);
});
test('到账核实被明确拒绝后可修改；切岗清空冻结命令并拒绝旧回执',async()=>{
 const h=harness(),click=openDeclaration(h,{account_name:'公司银行'}),writes=[];let sequence=0;
 h.api.idemKey=()=> 'confirm-'+(++sequence);
 h.http.post=async(...args)=>{writes.push(JSON.parse(JSON.stringify(args)));if(writes.length===1)throw {code:409,message:'原申报待核对'};return {receiptId:'9007199254742999'};};
 await h.page.confirmCollectionDeclaration(click);assert.equal(h.page.data.declarationPending,false);assert.equal(h.page.__declarationPending,null);
 h.page.declarationInput({currentTarget:{dataset:{field:'accountName'}},detail:{value:'核对后的实际账户'}});
 await h.page.confirmCollectionDeclaration(click);
 assert.notEqual(writes[0][2]['Idempotency-Key'],writes[1][2]['Idempotency-Key']);assert.equal(writes[1][1].accountName,'核对后的实际账户');
 const switched=harness();let generation=1,resolve;
 switched.http.sessionGuard=()=>{const original=generation;return()=>generation===original;};
 const old=openDeclaration(switched,{account_name:'旧岗位账户'});
 switched.http.post=()=>new Promise(done=>{resolve=done;});
 const pending=switched.page.confirmCollectionDeclaration(old);
 assert.equal(switched.page.data.wbBusy,true);
 let definition;vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/index.js','utf8'),{Page:p=>definition=p,require:()=>({})});
 switched.page.clearFinanceSnapshot=definition.clearFinanceSnapshot;generation++;
 definition.syncFinanceIdentity.call(switched.page,'another-finance-post');
 assert.equal(switched.page.__declarationPending,null);assert.equal(switched.page.data.declarationForm,null);
 assert.equal(switched.page.data.journeyDeclaration,null);assert.equal(switched.page.data.declarationPending,false);
 resolve({receiptId:'9007199254742999'});await pending;
 assert.equal(switched.page.data.journeyResult,null);assert.equal(switched.page.data.journeyDeclaration,null);assert.equal(switched.page.data.wbBusy,false);
});
test('旧待核实列表打开同一补齐表单，非财务和过期岗位不能确认',async()=>{
 const h=harness(),click=openDeclaration(h,{account_name:'原账户'});let writes=0;
 h.http.post=async()=>{writes++;return {receiptId:'9007199254742999'};};
 h.auth.currentApiPost=()=>({roleCode:'engineer'});await h.page.confirmCollectionDeclaration(click);assert.equal(writes,0);
 h.auth.currentApiPost=()=>({roleCode:'finance'});h.page.__declarationFormAlive=()=>false;
 await h.page.confirmCollectionDeclaration(click);assert.equal(writes,0);
 h.page.data.journeyEnabled=false;h.page.data.journeyDeclarations=[];h.page.data.journeyDeclaration=null;h.page.openJourneyDeclaration(click);
 assert.equal(h.page.data.journeyDeclaration.id,'9007199254741999');assert.equal(h.page.data.declarationForm.accountName,'原账户');
 const {render}=require('./role-page-runtime.cjs'),flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children||[])]);
 let definition;vm.runInNewContext(fs.readFileSync('miniprogram/pages/admin/finance-desk/index.js','utf8'),{Page:p=>definition=p,require:()=>({})});
 const data={...definition.data,...h.page.data,ready:true,view:'flows',platformApiMode:true,designReceiptForm:null,bookEntryDetail:null,projectLedgerReadOnly:false};
 const nodes=flatten(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),data));
 assert.equal(nodes.filter(n=>n.tag==='button'&&n.attrs?.bindtap==='confirmCollectionDeclaration').length,1);
 assert.ok(nodes.some(n=>n.tag==='input'&&n.attrs?.bindinput==='declarationInput'));
 const old=flatten(render(path.resolve('miniprogram/pages/admin/finance-desk/index.wxml'),{...data,journeyDeclaration:null}));
 assert.ok(old.some(n=>n.attrs?.bindtap==='openJourneyDeclaration'));
 assert.equal(old.filter(n=>n.attrs?.bindtap==='confirmCollectionDeclaration').length,0);
});
test('旧列表共享核实表单退回成功回原列表，迟到回执不关闭另一张原单',async()=>{
 for(const openAnother of [false,true]){
  const h=harness();h.page.data.journeyEnabled=false;h.page.data.view='reimbursements';h.page.options={view:'reimbursements'};
  const click=openDeclaration(h);let respond,resolve,refreshes=0;const writes=[];
  h.wx.showModal=options=>{respond=options.success;};h.page.loadWorkbench=()=>{refreshes++;};
  h.http.post=(...args)=>{writes.push(JSON.parse(JSON.stringify(args)));return new Promise(done=>{resolve=done;});};
  h.page.returnCollectionDeclaration(click);const pending=respond({confirm:true,content:' 请补齐原到账凭据 '});
  if(openAnother)openDeclaration(h,{id:'9007199254741888',account_name:'另一张原单账户'});
  resolve(true);await pending;
  assert.deepEqual(writes,[['/franchise/sales/collection-declarations/9007199254741999/return',{reason:'请补齐原到账凭据'},{'Idempotency-Key':'sticky-key'}]]);
  assert.equal(refreshes,1);assert.equal(h.page.data.view,'reimbursements');assert.equal(h.page.options.view,'reimbursements');
  if(openAnother){assert.equal(h.page.data.journeyDeclaration.id,'9007199254741888');assert.equal(h.page.data.declarationForm.accountName,'另一张原单账户');}
  else{assert.equal(h.page.data.journeyDeclaration,null);assert.equal(h.page.data.declarationForm,null);assert.equal(h.page.__entryOrigin,null);}
 }
});
test('task targets retain original records and display remaining payment instead of full agreement',()=>{const {page}=harness();page.data.journeyPayables=[{id:'123',canPay:true,projectName:'张先生乡墅',name:'设计费',remainingText:'600.00',paidCents:180000}];assert.equal(page.financeTaskTarget('fr-design-fin:123').row.id,'123');const card=page.presentFinanceTask({key:'fr-design-fin:123'});assert.equal(card.name,'张先生乡墅 · 设计费');assert.equal(card.amount,'¥600.00');assert.equal(card.status,'部分付款');assert.equal(page.financeTaskTarget('fr-design-fin:123:other'),null);});
test('home task opens original payment and returns to home; completed task never opens an empty form',()=>{const {page}=harness();page.data.view='desk';page.data.journeyPayables=[{id:'123',canPay:true,remainingCents:60000,totalCents:240000,paidCents:180000,label:'设计费',scope:'company',projectName:'公司日常账'}];page.openFinanceTask(event('',{key:'fr-design-fin:123'}));assert.equal(page.data.registerForm.payableId,'123');assert.equal(page.data.journeyReturnLabel,'返回财务首页');page.closeFinanceEntry();assert.equal(page.data.view,'desk');page.data.journeyPayables[0].canPay=false;page.openFinanceTask(event('',{key:'fr-design-fin:123'}));assert.equal(page.data.registerType,'');assert.equal(page.data.view,'desk');});
test('cash listing matches company totals and excludes employee advances and personal refunds',()=>{const rows=J.cashRows(facts,books,stages,'finance');assert.equal(rows.length,3);assert.equal(rows.reduce((n,r)=>n+(r.sign==='+'?1:-1)*r.amount_cents,0),85000);assert.equal(PF.metrics(facts.receipts,facts.payments,facts.refunds,'2026-11').net,'850.00');assert.equal(rows.find(r=>r.type==='PAYMENT').name,'材料采购');assert.equal(rows.find(r=>r.type==='RECEIPT').projectName,'张先生乡墅');});
test('cash search, month and direction preserve record identity',()=>{const rows=J.cashRows(facts,books,stages,'finance');assert.equal(J.filterCash(rows,{month:'2026-10'}).length,0);assert.equal(J.filterCash(rows,{month:'2026-11',keyword:'材料商',direction:'out'})[0].id,'p');assert.equal(J.filterCash(rows,{scope:'project:9007199254740999'}).length,3);});
test('engineering uses company payment and snowflake identifiers survive selection',()=>{const q=J.payableRows(facts,books,stages)[0];assert.equal(q.allowEmployee,false);assert.equal(q.remainingCents,35000);assert.equal(q.scope,'project:9007199254740999');const {page}=harness();page.openRegister(event('payment'));page.selectJourneyPayable(q);assert.equal(page.data.registerForm.payableId,'q');assert.equal(page.data.registerForm.amount,'350.00');assert.equal(page.data.registerForm.fundingType,'COMPANY');});
test('switching an existing payment form to manual creates exactly one active form and returns to the original book tab',()=>{const {page}=harness();page.openRegister(event('payment'));page.openWorkbenchEntry(event('expense'));assert.equal(page.data.registerType,'');assert.equal(page.data.wbForm.type,'expense');assert.equal(page.data.wbForm.scope,'project:9007199254740999');page.closeFinanceEntry();assert.equal(page.data.view,'book');assert.equal(page.data.bookFlowTab,'employee');assert.equal(page.options.bookScope,'project:9007199254740999');assert.equal(page.data.wbForm,null);});
test('receipt cancel and successful result preserve the project return destination',()=>{const {page}=harness();page.openWorkbenchEntry(event('receipt'));page.financeEntrySaved('receipt',{amount:'500',scope:page.data.bookScope,scopeLabel:'张先生乡墅',name:'首款'},{receiptId:'1234567890123456789'});assert.equal(page.data.journeyResult.key,'r-1234567890123456789');page.closeFinanceEntry();assert.equal(page.data.view,'book');assert.equal(page.data.bookScope,'project:9007199254740999');});
test('home and reimbursement entry returns do not inherit an earlier form origin',()=>{const {page}=harness();page.data.view='desk';page.data.title='财务工作台';page.openRegister(event('reimbursement'));assert.equal(page.data.view,'reimbursements');assert.equal(page.__entryOrigin,null);page.reimburseEmployee(event('',{id:'reim'}));assert.equal(page.data.registerForm.reimbursement,true);assert.equal(page.data.registerForm.amount,'1400.00');assert.equal(page.data.registerForm.allowEmployee,false);page.closeFinanceEntry();assert.equal(page.data.view,'reimbursements');});
test('supplement and allocation forms restore the originating view',()=>{for(const name of ['openFinanceSupplement','openReceiptAllocation','openPayableAdjustment','createFinanceBook']){const {page}=harness();page[name](event('',{id:'s',kind:'receipt'}));assert.ok(page.data.wbForm,name);page.closeFinanceEntry();assert.equal(page.data.view,'book',name);assert.equal(page.data.bookFlowTab,'employee',name);}});
test('validation blocks missing source/account, excess payment and future dates without posting',()=>{const {page,calls}=harness();page.openRegister(event('payment'));page.submitJourneyRegister();assert.equal(page.data.journeyErrorField,'source');page.selectJourneyPayable(J.payableRows(facts,books,stages)[0]);page.data.registerForm.amount='351';page.submitJourneyRegister();assert.equal(page.data.journeyErrorField,'amount');page.data.registerForm.amount='200';page.submitJourneyRegister();assert.equal(page.data.journeyErrorField,'account');page.data.registerForm.accountName='公司银行';page.data.registerForm.payDate='2026-12-01';page.submitJourneyRegister();assert.equal(page.data.journeyErrorField,'date');assert.equal(calls.length,0);});
test('pending owner declaration blocks a duplicate manual collection but leaves the form open',()=>{const {page}=harness();page.openWorkbenchEntry(event('receipt'));page.data.wbForm={...page.data.wbForm,scope:'project:9007199254740999',stageId:'s',name:'首款',amount:'1000',date:'2026-11-17',accountName:'公司银行'};page.data.journeyReceiptPending=[{amount_cents:100000,date:'2026-11-17'}];page.submitJourneyEntry();assert.equal(page.data.journeyErrorField,'amount');assert.match(page.data.wbError,/先核实/);assert.ok(page.data.wbForm);});
test('payment submission retains source and saves a useful result without creating another expense',async()=>{const {page,calls}=harness();page.openRegister(event('payment'));page.selectJourneyPayable(J.payableRows(facts,books,stages)[0]);page.data.registerForm.amount='200';page.data.registerForm.accountName='公司银行';page.submitJourneyRegister();await new Promise(r=>setImmediate(r));assert.equal(calls.length,1);assert.equal(calls[0].body.payableId,'q');assert.equal(calls[0].body.fundingType,'COMPANY');assert.equal(page.data.journeyResult.key,'p-p-new');assert.equal(page.data.journeyResult.scope,'project:9007199254740999');assert.equal(page.data.registerType,'');});

test('employee advance retains its original payment for a refund and returns to employee tab',async()=>{const {page}=harness();page.data.reimbursementRows[0].origin_payment_id='personal';page.data.platformRegister.expenseRefundable=[{id:'personal',amountCents:240000,refundableCents:230000,label:'设计费垫付'}];page.refreshJourney();await page.openEmployeeAdvance(event('',{id:'reim'}));assert.equal(page.data.bookEntryDetail.type,'ADVANCE');assert.equal(page.data.bookEntryDetail.canRefund,true);page.refundBookEntry();assert.equal(page.data.registerType,'expense-refund');assert.equal(page.data.registerForm.paymentId,'personal');assert.equal(page.data.journeyRefund.remainingText,'2,300.00');assert.equal(page.data.registerForm.refundFundingType,undefined);page.closeFinanceEntry();assert.equal(page.data.bookFlowTab,'employee');assert.equal(page.data.bookScope,'project:9007199254740999');});

test('receipt revision opens from a book and keeps the original tab, with return blocked while saving',async()=>{const {page}=harness();page.openRevision(event('',{id:'r-r'}));await new Promise(r=>setImmediate(r));assert.equal(page.data.registerType,'revision');assert.equal(page.data.revisionHistory.status,'empty');const runtime=require('./role-page-runtime.cjs');const flat=nodes=>nodes.flatMap(n=>[n,...flat(n.children||[])]);const nodes=flat(runtime.render(path.resolve('miniprogram/pages/admin/finance-desk/journey-forms.wxml'),{...page.data,wbForm:null}));assert.ok(nodes.some(n=>n.tag==='input'&&n.attrs?.['data-field']==='amount'));assert.ok(nodes.some(n=>n.tag==='button'&&n.attrs?.bindtap==='submitRevision'));page.data.revisionBusy=true;page.returnFinanceEntry();assert.equal(page.data.registerType,'revision');page.data.revisionBusy=false;page.returnFinanceEntry();assert.equal(page.data.view,'book');assert.equal(page.data.bookFlowTab,'employee');assert.equal(page.data.registerType,'');});

test('refund review blocks commission payment both in list and a stale open form',async()=>{const {page,calls,http}=harness();page.__financeFacts={receipts:[],payments:[],refunds:[],payables:[{id:'123',projectId:'9007199254740999',sourceType:null,stage:'performance',agreementRef:'perf-commission:policy:v2',totalCents:15000,status:'进行中'}]};page.__commissionStages=[{payableId:'123',eligible:true,reviewDue:false}];page.refreshJourney();const q=page.data.journeyPayables[0];assert.equal(q.canPay,true);page.openRegister(event('payment'));page.selectJourneyPayable(q);page.data.registerForm.accountName='公司银行';http.get=async()=>[{stages:[{payableId:'123',eligible:false,reviewDue:true}]}];await page.submitJourneyRegister();assert.equal(calls.length,0);assert.match(page.data.registerError,/由老板核对/);page.__commissionStages=[{payableId:'123',eligible:false,reviewDue:true}];page.refreshJourney();assert.equal(page.data.journeyPayables[0].canPay,false);assert.equal(page.data.journeyPayables[0].commissionHold,true);});
test('应付列表按原单权限显示操作，按钮保留单据长 ID',()=>{
 const {render}=require('./role-page-runtime.cjs');
 const resolve=require('node:path').resolve;
 const flatten=nodes=>nodes.flatMap(n=>[n,...flatten(n.children)]);
 const rows=[{id:'9007199254740993',name:'水泥',canPay:true,canAdjust:true,canAssign:false},{id:'9007199254740995',name:'中砂',canPay:false,canAdjust:false,canAssign:true,commissionHold:true}];
  const data={ready:true,view:'flows',flowTab:'payables',journeyEnabled:false,registerType:'',wbForm:null,designReceiptForm:null,platformApiMode:true,platformFinance:{status:'ready'},rows};
  const nodes=flatten(render(resolve('miniprogram/pages/admin/finance-desk/index.wxml'),data));
  const cards=nodes.filter(n=>String(n.attrs?.class||'').split(' ').includes('fin-flow-row'));assert.equal(cards.length,2);
  for(let i=0;i<2;i++){
   const buttons=flatten(cards[i].children).filter(n=>n.tag==='button'&&n.attrs['data-id']);
   assert.deepEqual(buttons.map(n=>n.attrs['data-id']),Array(buttons.length).fill(rows[i].id));
   assert.equal(buttons.some(n=>n.attrs.bindtap==='assignPayableBook'),rows[i].canAssign);
   assert.equal(buttons.some(n=>n.attrs.bindtap==='openPayableAdjustment'),rows[i].canAdjust);
   assert.equal(buttons.some(n=>n.attrs.bindtap==='paySourceRow'),rows[i].canPay);
  }
});

test('late commission eligibility cannot register an edited, cancelled or switched entry',async()=>{
 for(const change of ['cancel','edit','post','hide','unload']){
  const {page,http,calls}=harness();
  let release;
  http.get=()=>new Promise(resolve=>{release=resolve;});
  page.openRegister(event('payment'));
  page.data.journeyPayment={sourceType:'performance',remainingCents:10000};
  Object.assign(page.data.registerForm,{payableId:'A',amount:'10',accountName:'公司',payDate:'2026-11-17'});
  const request=page.submitJourneyRegister();
  if(change==='cancel'){page.cancelRegister();page.openRegister(event('payment'));Object.assign(page.data.registerForm,{payableId:'B',amount:'20'});}
  if(change==='edit') page.data.registerForm.amount='11';
  if(change==='post') http.currentPostId=()=>456;
  if(change==='hide') page.__registerValidation=null;
  if(change==='unload') page.__disposed=true;
  release([{stages:[{payableId:'A',eligible:true}]}]);await request;
  assert.equal(calls.length,0,change);
  if(change==='cancel') assert.equal(page.data.registerForm.payableId,'B');
 }
});

test('commission task distinguishes payable, partly paid, and on hold',()=>{
 const {page}=harness();
 page.__financeFacts={...facts,payables:[{id:'321',stage:'performance',sourceType:'performance',totalCents:50000,status:'进行中',recipient:'收款人'}],payments:[],refunds:[]};
 page.__commissionStages=[{payableId:'321',eligible:true}];
 page.refreshJourney();
 const status=()=>page.presentFinanceTask({key:'payable:321'}).status;
 assert.equal(status(),'待付款');
 page.__financeFacts.payments=[{id:'222',payableId:'321',amountCents:100,status:'已核实',fundingType:'COMPANY'}];
 page.refreshJourney();assert.equal(status(),'部分付款');
 page.__commissionStages[0].reviewDue=true;page.refreshJourney();assert.match(status(),/核对/);
});

test('payment accepted before hide is confirmed by its original ID even before the delayed response',async()=>{
 const {page,api,http}=harness();
 let release, uploads=0, writes=0, lookups=0;
 api.uploadVoucher=async()=>{uploads++;return '667788';};
 api.registerPayment=(body,key)=>{writes++;assert.equal(key,'sticky-key');assert.equal(body.voucherFileId,'667788');return new Promise(resolve=>{release=resolve;});};
 page.openRegister(event('payment'));
 Object.assign(page.data.registerForm,{amount:'10',accountName:'公司',payDate:'2026-11-17',payableId:'A',voucherPath:'local-a'});
 const first=page.submitJourneyRegister();
 await new Promise(setImmediate);
 page.__financeHidden=true;page.__registerValidation=null;page._registerSeq++;
 assert.equal(page.data.paymentPending,true);
 page.__financeHidden=false;
 http.get=async(path,args)=>{assert.equal(path,'/franchise/finance/payments/registration-result');assert.equal(args.key,'sticky-key');lookups++;return {paymentId:'725'};};
 await page.resumePayment();
 assert.equal(page.data.journeyResult.id,'725');
 release({paymentId:'725'});await first;
 assert.deepEqual([uploads,writes,lookups],[1,1,1]);
 assert.equal(page.data.registerBusy,false);
});

test('unknown payment outcome retries the frozen body and key, including its uploaded voucher',async()=>{
 const {page,api,http}=harness(); const bodies=[];let uploads=0;
 api.uploadVoucher=async()=>String(++uploads);
 api.registerPayment=async(body,key)=>{bodies.push(JSON.stringify({body,key}));if(bodies.length===1)throw {code:-1};return {paymentId:'808'};};
 http.get=async()=>({});
 page.openRegister(event('payment'));
 Object.assign(page.data.registerForm,{amount:'12',accountName:'公司',payDate:'2026-11-17',payableId:'A',voucherPath:'local'});
 await page.submitJourneyRegister();assert.equal(page.data.paymentPending,true);
 page.data.registerForm.amount='25';await page.retryPendingPayment();
 assert.equal(uploads,1);assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);
 assert.equal(page.data.journeyResult.id,'808');
});

test('definitive refusal after timeout releases only after the server confirms absence',async()=>{
 const {page,api,http}=harness(); let writes=0,offline=false;
 api.registerPayment=async()=>{writes++;if(writes===1)throw {code:-1};offline=true;throw {code:1102000050,message:'应付已作废'};};
 http.get=async()=>{if(offline)throw Error('offline');return {state:'NOT_REGISTERED'};};
 page.openRegister(event('payment'));
 Object.assign(page.data.registerForm,{amount:'12',accountName:'公司',payDate:'2026-11-17',payableId:'A'});
 await page.submitJourneyRegister();await page.retryPendingPayment();
 assert.equal(writes,2);assert.equal(page.data.paymentPending,true);
 offline=false;await page.retryPendingPayment();
 assert.equal(page.data.paymentPending,false);assert.equal(page.data.registerBusy,false);
 assert.match(page.data.paymentNotice,/作废/);assert.equal(writes,2);
 page.openRegister(event('payment'));assert.equal(page.data.registerType,'payment');
});

for(const type of ['refund','expense-refund']) {
 const method=type==='refund'?'registerRefund':'registerExpenseRefund';
 const source=type==='refund'?'receiptId':'paymentId';
 const endpoint=type==='refund'?'refunds':'expense-refunds';
 test(type+' timeout retries the original uploaded voucher, correction, amount and idempotency key',async()=>{
  const {page,api,http}=harness(), bodies=[];let uploads=0;
  http.currentPostId=()=>type+'-retry';
  api.uploadVoucher=async()=>String(++uploads);
  api[method]=async(body,key)=>{bodies.push(JSON.stringify({body,key}));if(bodies.length===1)throw {code:-1};return {refundId:'9223372036854775000'};};
  http.get=async(path,args)=>{assert.equal(path,'/franchise/finance/'+endpoint+'/registration-result');assert.equal(args.key,'sticky-key');return {state:'NOT_REGISTERED'};};
  page.openRegister(event(type));
  Object.assign(page.data.registerForm,{[source]:'9007199254740999',amount:'12',date:'2026-11-17',voucherPath:'local',replacesId:'9007199254741001',reason:'更正'});
  await page.submitRegister();assert.equal(page.data.paymentPending,true);
  page.data.registerForm.amount='25';page.data.registerForm.voucherPath='different-local';
  page.cancelRegister();assert.equal(page.data.registerType,type);
  await page.retryPendingPayment();
  assert.equal(uploads,1);assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);
  assert.equal(JSON.parse(bodies[1]).body.replacesId,'9007199254741001');
  assert.equal(page.data.journeyResult.id,'9223372036854775000');
  assert.equal(page.data.journeyResult.amount,'12.00');assert.equal(page.data.registerBusy,false);
 });
 test(type+' survives process reload and keeps an accepted response isolated from another account',async()=>{
  const first=harness(), storage=new Map();let release,writes=0,lookups=0;
  const storageApi={setStorageSync:(k,v)=>storage.set(k,JSON.parse(JSON.stringify(v))),getStorageSync:k=>storage.get(k),removeStorageSync:k=>storage.delete(k)};
  Object.assign(first.wx,storageApi);first.http.currentPostId=()=>type+'-persist';
  first.api.uploadVoucher=async()=> '9007199254742001';
  first.api[method]=(body)=>{writes++;assert.equal(body.voucherFileId,'9007199254742001');return new Promise(resolve=>{release=resolve;});};
  first.page.openRegister(event(type));Object.assign(first.page.data.registerForm,{[source]:'9007199254742003',amount:'10',date:'2026-11-17',voucherPath:'local'});
  const flight=first.page.submitRegister();await new Promise(setImmediate);first.page.__disposed=true;
  delete require.cache[require.resolve('../miniprogram/pages/admin/finance-desk/payment-recovery')];
  const second=harness();Object.assign(second.wx,storageApi);second.http.currentPostId=()=> 'another-post';
  second.http.get=async(path)=>{lookups++;assert.equal(path,'/franchise/finance/'+endpoint+'/registration-result');return {refundId:'9007199254742005'};};
  await second.page.resumePayment();assert.equal(lookups,0);assert.equal(second.page.data.journeyResult,undefined);
  second.http.currentPostId=()=>type+'-persist';await second.page.resumePayment();
  assert.equal(second.page.data.journeyResult.id,'9007199254742005');assert.equal(storage.size,0);
  release({refundId:'9007199254742005'});await flight;
  assert.equal(writes,1);assert.equal(first.page.data.journeyResult,null);
 });
 test(type+' releases a rejected frozen request only after confirmed absence',async()=>{
  const {page,api,http}=harness();let writes=0,offline=false;
  http.currentPostId=()=>type+'-reject';
  api[method]=async()=>{writes++;if(writes===1)throw {code:-1};offline=true;throw {code:1102000050,message:'原单已作废'};};
  http.get=async()=>{if(offline)throw Error('offline');return {state:'NOT_REGISTERED'};};
  page.openRegister(event(type));Object.assign(page.data.registerForm,{[source]:'123',amount:'12',date:'2026-11-17'});
  await page.submitRegister();await page.retryPendingPayment();assert.equal(page.data.paymentPending,true);
  offline=false;await page.retryPendingPayment();assert.equal(page.data.paymentPending,false);assert.equal(writes,2);
  assert.match(page.data.registerError,/作废/);
 });
 for(const originalFirst of [true,false])test(type+' keeps one frozen request when '+(originalFirst?'registration arrives before confirmation':'confirmation arrives before registration'),async()=>{
  const {page,api,http,wx}=harness(),sent=[],events=[],storage=new Map();
  let originalArrived=false,finishRetry,uploads=0,keys=0;
  const key='arrival-'+type+'-'+originalFirst,result={refundId:'9223372036854775000'};
  http.currentPostId=()=>key;
  Object.assign(wx,{
   setStorageSync:(scope,value)=>storage.set(scope,JSON.parse(JSON.stringify(value))),
   getStorageSync:scope=>storage.get(scope),removeStorageSync:scope=>storage.delete(scope)
  });
  api.idemKey=()=>{keys++;return key;};
  api.uploadVoucher=async()=>{uploads++;return '9007199254742001';};
  // Model transport order and confirmation visibility only; database uniqueness is covered by backend integration tests.
  api[method]=(body,idem)=>{
   sent.push(JSON.parse(JSON.stringify({body,key:idem})));
   if(sent.length===1)return Promise.reject({code:-1,message:'request:fail timeout'});
   return new Promise(resolve=>{finishRetry=resolve;});
  };
  http.get=async(path,args)=>{
   assert.equal(path,'/franchise/finance/'+endpoint+'/registration-result');assert.equal(args.key,key);
   events.push('confirmation');return originalArrived?result:{state:'NOT_REGISTERED'};
  };
  const deliverOriginal=()=>{originalArrived=true;events.push('registration');};
  page.openRegister(event(type));
  Object.assign(page.data.registerForm,{[source]:'9007199254740999',amount:'12.34',date:'2026-11-17',
   voucherPath:'original-voucher',voucherName:'原凭据.jpg',replacesId:'9007199254741001',
   reason:'原始退款原因',transactionNo:'ORIGINAL-'+type,refundFundingType:'COMPANY'});
  await page.submitRegister();
  const frozen=JSON.stringify([...storage.values()][0]);
  assert.equal(page.data.paymentPending,true);assert.equal(page.data.registerBusy,true);
  if(originalFirst)deliverOriginal();
  await page.resumePayment();
  if(!originalFirst){
   assert.equal(page.data.paymentPending,true);assert.equal(page.data.registerBusy,true);
   assert.equal(page.registerIdem,key);assert.equal(JSON.stringify([...storage.values()][0]),frozen);
   assert.match(page.data.paymentNotice,/尚未查到原单/);
   page.cancelRegister();page.openRegister(event('receipt'));await page.submitRegister();
   assert.equal(page.data.registerType,type);assert.equal(page.data.registerForm.amount,'12.34');
   assert.equal(page.data.registerForm.voucherPath,'original-voucher');assert.equal(sent.length,1);
   page.data.registerForm.amount='99';page.data.registerForm.voucherPath='different-voucher';
   const retry=page.retryPendingPayment();await new Promise(setImmediate);
   assert.equal(sent.length,2);assert.deepEqual(sent[1],sent[0]);
   assert.equal(JSON.stringify([...storage.values()][0]),frozen);
   assert.equal(page.data.paymentPending,true);assert.equal(page.data.registerBusy,true);
   deliverOriginal();finishRetry(result);await retry;
  }
  assert.equal(events[0],originalFirst?'registration':'confirmation');
  assert.equal(sent.length,originalFirst?1:2);assert.equal(keys,1);assert.equal(uploads,1);
  assert.equal(sent[0].body.amountCents,1234);assert.equal(sent[0].body.voucherFileId,'9007199254742001');
  assert.equal(sent[0].body.replacesId,'9007199254741001');
  assert.equal(page.data.journeyResult.id,result.refundId);assert.equal(page.data.journeyResult.amount,'12.34');
  assert.equal(page.data.paymentPending,false);assert.equal(page.data.registerBusy,false);assert.equal(storage.size,0);
 });
}


test('only unassigned manual expenses expose book assignment and the original handler refuses stale source rows',()=>{
 const originals=[
  {id:'9007199254740999',sourceType:'manual',status:'进行中',totalCents:100},
  {id:'engineer',stage:'engineering',sourceType:null,status:'进行中',projectId:'91',totalCents:100},
  {id:'linked',sourceType:'manual',status:'进行中',projectId:'91',totalCents:100},
  {id:'book',sourceType:'manual',status:'进行中',bookId:'92',totalCents:100},
  {id:'design',sourceType:'design',status:'进行中',totalCents:100},
  {id:'void',sourceType:'manual',status:'已作废',totalCents:100}
 ];
 assert.deepEqual(J.payableRows({payables:originals},[],[]).map(q=>q.canAssign),[true,false,false,false,false,false]);
 const page={__financeFacts:{payables:originals},data:{wbForm:null},setData(patch){Object.assign(this.data,patch);}};
 require('../miniprogram/pages/admin/finance-desk/workbench')(page,{idemKey:()=> 'original-key'});
 const tap=id=>({currentTarget:{dataset:{id}}});
 for(const q of originals.slice(1)){page.assignPayableBook(tap(q.id));assert.equal(page.data.wbForm,null);}
 page.assignPayableBook(tap(originals[0].id));assert.equal(page.data.wbForm.payableId,originals[0].id);
 page.data.wbForm=null;originals[0].projectId='91';page.assignPayableBook(tap(originals[0].id));assert.equal(page.data.wbForm,null);
});
