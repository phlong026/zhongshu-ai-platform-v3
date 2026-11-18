const J=require('../../../utils/finance-journey-view');
const V=require('../../../utils/finance-workbench-view');
const PF=require('../../../utils/platform-finance');
const {day}=require('../../../utils/finance-date');
const auth=require('../../../api/auth');
const http=require('../../../api/http');
const today=()=>require('../../../utils/account').localDate();
const event=(type,extra={})=>({currentTarget:{dataset:{type,...extra}}});
module.exports=function(page,api){
 page.openMessageSource=function(){
  const o=this.options||{},id=String(o.messageObject||''),type=o.messageType;if(!/^\d+$/.test(id)||!type||!this.data.journeyEnabled||!this.__financeFacts||!this.__messageWorkbenchReady||this.data.platformFinance?.status!=='ready'||this.__messageSourceOpened)return;
  const event={currentTarget:{dataset:{id}}};
  if(type==='sale_collection_declaration'){
   const d=(this.data.journeyDeclarations||[]).find(r=>String(r.id)===id);
   this.__messageSourceOpened=true;
   if(d)return this.openJourneyDeclaration(event);
   this.setData({workbenchError:'这笔申报已处理或当前岗位不可查看，请从关联合同核对最新收款记录'});return;
  }
  if(type==='fin_payable'){
   this.__messageSourceOpened=true;
   const employee=(this.data.journeyReimbursements||[]).find(r=>String(r.id)===id);
   if(employee&&employee.remaining!==0)return employee.remaining<0?this.recoverEmployee(event):this.reimburseEmployee(event);
   const payable=(this.data.journeyPayables||[]).find(r=>String(r.id)===id);
   if(payable?.canPay)return this.payJourneyRow(event);
   this.setData({flowTab:'payables',keyword:payable?.name||'',workbenchError:payable?'该应付单当前无需付款，可在列表核对最新状态':'原单已变更或当前岗位不可查看'});return;
  }
  const kind={fin_receipt:'receipt',fin_payment:'payment',fin_refund:'refund'}[type];
  if(kind){this.__messageSourceOpened=true;const cash=(this.__journeyCash||[]).find(r=>r.id===id&&r.sourceKind===kind);if(cash)return this.openJourneyCash({currentTarget:{dataset:{key:cash.key}}});this.setData({workbenchError:'未找到可查看的原始流水，请核对当前工作身份'});}
 };
 page.financeTaskTarget=function(key){
  const match=String(key||'').match(/^(fr-design-fin:|fr-fin-workbench:payable:|payable:|fr-fin-workbench:reimburse:|employee:|fr-sale-finance:|declaration:)(\d+)$/);if(!match)return null;
  const kind=/reimburse|employee/.test(match[1])?'employee':/fr-sale-finance|declaration/.test(match[1])?'declaration':'payable';
  const rows=kind==='employee'?this.data.journeyReimbursements:kind==='declaration'?this.data.journeyDeclarations:this.data.journeyPayables;
  const row=(rows||[]).find(r=>String(r.id)===match[2]);return row?{kind,row}:null;
 };
 page.presentFinanceTask=function(task){
  if(!this.data.journeyEnabled)return null;const target=this.financeTaskTarget(task.key);if(!target)return null;const {kind,row}=target;
  return {name:kind==='employee'?row.payer_name+' · '+row.stage:row.projectName+' · '+row.name,amount:'¥'+(kind==='declaration'?row.amountText:row.remainingText),status:kind==='declaration'?'核实到账':kind==='employee'?row.state:row.paidCents>0?'部分付款':'待付款'};
 };
 page.openFinanceTask=function(e){
  if(!this.data.journeyEnabled)return this.go(e);const target=this.financeTaskTarget(e.currentTarget.dataset.key||e.currentTarget.dataset.id);if(!target)return this.go(e);
  if(this.data.wbBusy||this.data.registerBusy||this.data.busy)return;
  if(target.kind==='payable'&&!target.row.canPay||target.kind==='employee'&&target.row.remaining===0){wx.showToast({title:'该事项已办结',icon:'none'});this.loadWorkbench();this.loadPlatformFinance();return;}
  this.rememberEntryOrigin();if(this.data.view==='desk')this.setData({view:'flows',flowTab:target.kind==='declaration'?'receivables':'payables'});
  const event={currentTarget:{dataset:{id:target.row.id}}};
  if(target.kind==='employee')return target.row.remaining<0?this.recoverEmployee(event):this.reimburseEmployee(event);
  if(target.kind==='declaration')return this.openJourneyDeclaration(event);
  return this.payJourneyRow(event);
 };
 page.setData({journeyEntryTitles:{receipt:'登记收款',payment:'登记付款',expense:'补记其他支出',payable:'登记待付费用',refund:'登记退款','expense-refund':'登记退款（支出退回）',revision:'修订收款金额',book:'新建账本',contractchange:'合同金额变更',payablechange:'结算调整',allocation:'抵扣定金',linkbook:'关联工程',assignbook:'账本归属',supplement:'补充资料',recovery:'登记员工归还'}});
 page.rememberEntryOrigin=function(){if(!this.__entryOrigin){this.__entryOptions={...this.options};this.__entryScroll=this.__navigationScrollTop||0;this.__entryOrigin={view:this.data.view,title:this.data.title,flowTab:this.data.flowTab,bookScope:this.data.bookScope,bookFlowTab:this.data.bookFlowTab,keyword:this.data.keyword};this.setData({journeyReturnLabel:this.data.view==='book'?'返回项目账本':this.data.view==='desk'?'返回财务首页':this.data.view==='reimbursements'?'返回报销列表':this.data.view==='tasks'?'返回待办列表':'返回上一页'});}};
 page.refreshJourney=function(){
  if(!this.data.journeyEnabled)return;
  const books=this.data.workbenchBooks||[],stages=(this.data.financialStages||[]).map(s=>({...s,project_id:s.project_id||books.find(b=>String(b.sale_contract_id)===String(s.contract_id))?.project_id})),facts=this.__financeFacts;
  const payables=facts?J.payableRows(facts,books,stages):[];
  const cash=facts?J.cashRows(facts,books,stages,auth.currentApiPost()?.roleCode):[];
  this.__journeyCash=cash;
  const month=this.data.flowScope==='all'?'':this.data.selectedMonth||this.data.platformFinance?.month||today().slice(0,7);
  const filtered=J.filterCash(cash,{month,keyword:this.data.keyword||'',direction:this.data.journeyDirection||'all'});
  const period=J.filterCash(cash,{month});
  const incoming=period.filter(r=>r.sign==='+').reduce((n,r)=>n+Number(r.amount_cents),0),outgoing=period.filter(r=>r.sign!=='+').reduce((n,r)=>n+Number(r.amount_cents),0);
  const reimbursements=(this.data.reimbursementRows||[]).map(r=>({...r,...J.context(r,books,stages),eligibleText:V.money(r.eligible_cents),paidText:V.money(r.reimbursed_cents),remainingText:V.money(Math.abs(r.remaining)),recoveredText:V.money(r.recovered_cents),state:r.remaining>0?'待报销':r.remaining<0?'待归还':'已结清'}));
  const declarations=(this.data.collectionDeclarations||[]).map(r=>({...r,...J.context({project_id:r.project_id,sourceRef:'sale:'+r.contract_id},books,stages),name:(r.customer_name||'业主')+' · '+J.stageName(r.stage),amountText:V.money(r.amount_cents),date:day(r.received_date)}));
  const progress=[...reimbursements.filter(r=>r.remaining!==0).map(r=>({id:'employee:'+r.id,name:r.payer_name+' · '+r.stage,projectName:r.projectName,amount:'¥'+r.remainingText,status:r.remaining<0?'待员工归还':Number(r.reimbursed_cents)>0?'部分报销':'待报销',url:'/pages/admin/finance-desk/index?view=reimbursements'})),...payables.filter(r=>r.canPay&&r.sourceType!=='reimbursement'&&r.paidCents>0).map(r=>({id:'payable:'+r.id,name:r.name,projectName:r.projectName,amount:'¥'+r.remainingText,status:'部分付款',url:'/pages/admin/finance-desk/index?view=flows&tab=payables'})),...declarations.map(r=>({id:'declaration:'+r.id,name:r.name,projectName:r.projectName,amount:'¥'+r.amountText,status:'待核实到账',url:'/pages/admin/finance-desk/index?view=flows&tab=receivables'}))];
  const accounts=[...new Set([...(facts?.payments||[]),...(facts?.receipts||[])].map(r=>r.accountName).filter(Boolean))];
  this.setData({journeyCashRows:filtered,journeyCashTotal:filtered.length,journeyTotals:{incoming:V.money(incoming),outgoing:V.money(outgoing),net:V.money(incoming-outgoing)},journeyPeriod:month||'全部月份',journeyPayables:payables,journeyPayableCount:payables.filter(r=>r.canPay).length,journeyReimbursements:reimbursements,journeyDeclarations:declarations,journeyProgress:progress,journeyAccounts:[...new Set([...accounts,'公司银行账户','公司微信','现金'])],journeyCategories:['材料','人工','运输','设计','差旅','办公','其他费用'],financialStages:stages.map(s=>({...s,stageName:J.stageName(s.stage),remainingText:V.money(Math.max(0,Number(s.amount_cents)-Number(s.collected_cents)))})),'deskHome.focus':progress.slice(0,3),'deskHome.focusUrl':'/pages/admin/finance-desk/index?view=progress'});
  // Existing correction/void handlers operate on the same original IDs and permission flags.
  this.ledgerSource=cash.map(r=>({...r,id:r.key,amountText:r.sign+'¥'+r.amount}));
  this.syncJourneyForm();
  if(this.syncPlatformTasks)this.syncPlatformTasks();
  this.openMessageSource();
 };
 page.syncJourneyForm=function(){
  if(!this.data.journeyEnabled)return;
  const f=this.data.wbForm,books=this.data.workbenchBooks||[],stages=this.data.financialStages||[];
  if(f&&['receipt','expense','payable'].includes(f.type)){
   const contract=f.type==='receipt'&&f.receiptMode!=='other';
   const scopes=contract?books.filter(b=>b.scope.startsWith('project:')):[...books];
   if(contract)for(const s of stages)if(!s.project_id&&!scopes.some(b=>b.scope==='contract:'+s.contract_id))scopes.push({scope:'contract:'+s.contract_id,name:s.customer_name+' · 合同款'});
   const choices=stages.filter(s=>f.scope==='project:'+s.project_id||f.scope==='contract:'+s.contract_id);
   const stage=stages.find(s=>String(s.id)===String(f.stageId));
   const pending=(this.data.journeyDeclarations||[]).filter(d=>String(d.stage_id)===String(f.stageId));
   this.setData({journeyScopes:scopes,journeyStages:choices,journeyStage:stage?{...stage,remainingText:V.money(Math.max(0,Number(stage.amount_cents)-Number(stage.collected_cents)))}:null,journeyReceiptPending:pending,journeyFormTitle:f.type==='receipt'?'登记收款':f.type==='payable'?'登记待付费用':'补记其他支出'});
  }
  const form=this.data.registerForm||{},q=(this.data.journeyPayables||[]).find(r=>r.id===String(form.payableId));
  const scope=this.__entryOrigin?.view==='book'?this.data.bookScope:'';
  const options=(this.data.journeyPayables||[]).filter(r=>r.canPay&&r.sourceType!=='reimbursement'&&(!scope||r.scope===scope));
  const raw=this.data.platformRegister||{},refund=this.data.registerType==='expense-refund';
  const refunds=(refund?raw.expenseRefundable:raw.refundable)||[];
  const refundOptions=refunds.filter(r=>r.refundableCents>0).map(r=>{const cash=(this.__journeyCash||[]).find(c=>c.sourceKind===(refund?'payment':'receipt')&&c.id===String(r.id));return {...r,label:cash?cash.projectName+' · '+cash.name+' · 可退 ¥'+V.money(r.refundableCents):r.label};});
  const selected=refundOptions.find(r=>String(r.id)===String(refund?form.paymentId:form.receiptId));
  this.setData({journeyPaymentOptions:options,journeyPayment:q||null,journeyRefundOptions:refundOptions,journeyRefund:selected?{...selected,originalText:V.money(selected.amountCents),remainingText:V.money(selected.refundableCents)}:null,journeyRegisterTitle:form.reimbursement?'办理报销':this.data.registerType==='payment'?'登记付款':refund?'登记退款（支出退回）':'登记退款'});
 };
 const open=page.openRegister;
 page.openRegister=function(e){
  if(!this.data.journeyEnabled)return open.call(this,e);
  if(this.data.registerBusy||this.data.wbBusy||this.data.busy)return;
  this.rememberEntryOrigin();this.setData({journeyResult:null,journeyDeclaration:null,journeyErrorField:''});
  const result=open.call(this,e);this.syncJourneyForm();wx.pageScrollTo({scrollTop:0,duration:0});return result;
 };
 const entry=page.openWorkbenchEntry;
 page.openWorkbenchEntry=function(e){
  if(!this.data.journeyEnabled)return entry.call(this,e);
  if(this.data.registerBusy||this.data.wbBusy||this.data.busy)return;
  this.rememberEntryOrigin();this.setData({journeyResult:null,journeyDeclaration:null,journeyErrorField:''});entry.call(this,e);
  if(e.currentTarget.dataset.type==='reimbursement'){this.__entryOrigin=null;this.__entryOptions=null;return;}
  const f=this.data.wbForm;if(f&&f.type==='receipt')this.setData({'wbForm.receiptMode':f.scope&& !f.scope.startsWith('project:')?'other':'contract'});
  this.syncJourneyForm();wx.pageScrollTo({scrollTop:0,duration:0});
 };
 const cancelRegister=page.cancelRegister,cancelWorkbench=page.cancelWorkbench;
 page.returnFinanceEntry=function(){if(this.data.designReceiptBusy||this.data.revisionBusy)return;if(this.data.designReceiptForm)return this.cancelDesignReceipt();return this.closeFinanceEntry();};
 page.closeFinanceEntry=function(){
  if(this.data.registerBusy||this.data.wbBusy||this.data.revisionBusy||this.data.busy)return;
  require('../../../utils/finance-keyboard').dismiss(this,wx);
  cancelRegister.call(this);cancelWorkbench.call(this);
  const origin=this.__entryOrigin,scroll=this.__entryScroll||0;if(this.__entryOptions)this.options={...this.__entryOptions};this.__entryOrigin=null;this.__entryOptions=null;
  this.setData({...(origin||{}),journeyResult:null,journeyDeclaration:null,bookEntryDetail:null,journeyErrorField:''});
  this.refresh();if(this.data.view==='desk')this.syncPlatformTasks();wx.pageScrollTo({scrollTop:scroll,duration:0});
 };
 page.changeReceiptMode=function(e){const mode=e.currentTarget.dataset.mode;if(this.data.wbBusy)return;const f=this.data.wbForm||{},keep=mode==='other'?!String(f.scope||'').startsWith('contract:'):String(f.scope||'').startsWith('project:')||String(f.scope||'').startsWith('contract:');this.setData({'wbForm.receiptMode':mode,'wbForm.stageId':'','wbForm.stageLabel':'','wbForm.name':'','wbForm.scope':keep?f.scope:'','wbForm.scopeLabel':keep?f.scopeLabel:'',wbError:'',journeyErrorField:''});this.syncJourneyForm();};
 page.journeyPick=function(e){
  const field=e.currentTarget.dataset.field,i=Number(e.detail.value),f=this.data.wbForm||{};
  if(field==='scope'){const r=this.data.journeyScopes[i];if(r)this.setData({'wbForm.scope':r.scope,'wbForm.scopeLabel':r.name,'wbForm.stageId':'','wbForm.stageLabel':''});}
  if(field==='stageId'){const r=this.data.journeyStages[i];if(r)this.setData({'wbForm.stageId':String(r.id),'wbForm.stageLabel':J.stageName(r.stage),'wbForm.name':J.stageName(r.stage),'wbForm.counterparty':r.customer_name,'wbForm.category':'合同回款'});}
  if(field==='category')this.setData({'wbForm.category':this.data.journeyCategories[i],...(i===0?{'wbForm.fundingType':'COMPANY'}:{})});
  if(field==='account')this.setData({[f.type?'wbForm.accountName':'registerForm.accountName']:this.data.journeyAccounts[i]});
  this.setData({journeyErrorField:'',wbError:'',registerError:''});this.syncJourneyForm();
 };
 page.journeyChoosePayable=function(e){const q=this.data.journeyPaymentOptions[Number(e.detail.value)];if(q)this.selectJourneyPayable(q);};
 page.selectJourneyPayable=function(q){this.setData({'registerForm.payableId':q.id,'registerForm.payableIdLabel':q.name,'registerForm.amount':(q.remainingCents/100).toFixed(2),'registerForm.allowEmployee':q.allowEmployee,'registerForm.fundingType':'COMPANY','registerForm.reimbursement':q.sourceType==='reimbursement','registerForm.scope':q.scope,'registerForm.scopeLabel':q.projectName,'registerForm.name':q.name,journeyErrorField:'',registerError:''});this.syncJourneyForm();};
 page.payJourneyRow=function(e){const q=(this.data.journeyPayables||[]).find(r=>r.id===String(e.currentTarget.dataset.id));if(!q||!q.canPay)return;if(q.sourceType==='reimbursement')return this.reimburseEmployee(e);this.openRegister(event('payment'));this.selectJourneyPayable(q);};
 const reimburse=page.reimburseEmployee;
 page.reimburseEmployee=function(e){
  if(!this.data.journeyEnabled)return reimburse.call(this,e);
  const r=(this.data.journeyReimbursements||[]).find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(!r||r.remaining<=0)return;
  this.openRegister(event('payment'));const q=(this.data.journeyPayables||[]).find(q=>q.id===String(r.id));
  if(q)this.selectJourneyPayable({...q,remainingCents:r.remaining});
  this.setData({'registerForm.reimbursement':true,'registerForm.allowEmployee':false,'registerForm.payableId':String(r.id),'registerForm.payableIdLabel':r.payer_name+' · '+r.stage,'registerForm.amount':(r.remaining/100).toFixed(2),'registerForm.scope':r.scope,'registerForm.scopeLabel':r.projectName,'registerForm.name':r.stage,journeyReimbursement:r});this.syncJourneyForm();
 };
 const paySource=page.paySourceRow;
 page.openEmployeeAdvance=async function(e){
  const employee=(this.data.journeyReimbursements||[]).find(r=>String(r.id)===String(e.currentTarget.dataset.id));
  const payment=(this.__financeFacts?.payments||[]).find(r=>String(r.id)===String(employee?.origin_payment_id));
  if(!employee||!payment)return;
  const refundable=(this.data.platformRegister?.expenseRefundable||[]).find(r=>String(r.id)===String(payment.id));
  const row=V.bookRows([{id:String(payment.id),type:'ADVANCE',name:employee.stage,amount_cents:payment.amountCents,date:payment.payDate,status:payment.status,account_name:payment.accountName,transaction_no:payment.transactionNo,counterparty:employee.payer_name,source_type:'design',funding_type:'EMPLOYEE'}])[0];
  const detail={...row,scope:employee.scope,projectName:employee.projectName,canRefund:Number(refundable?.refundableCents)>0};
  this.setData({bookEntryDetail:detail});
  const alive=http.requestGuard('journey-entry-detail');
  try{const result=await http.get('/franchise/finance/workbench/ledger',{scope:employee.scope});if(!alive()||this.__disposed||this.data.bookEntryDetail?.key!==detail.key)return;const original=(result.entries||[]).find(r=>r.type==='ADVANCE'&&String(r.id)===String(payment.id));if(original)this.setData({bookEntryDetail:{...detail,...V.bookRows([original])[0]}});}catch(error){if(alive()&&this.data.bookEntryDetail?.key===detail.key)wx.showToast({title:'补充资料暂未加载，可稍后重试',icon:'none'});}
 };
 page.paySourceRow=function(e){return this.data.journeyEnabled?this.payJourneyRow(e):paySource.call(this,e);};
 page.journeyChooseRefund=function(e){const r=this.data.journeyRefundOptions[Number(e.detail.value)];if(!r)return;const field=this.data.registerType==='expense-refund'?'paymentId':'receiptId';this.setData({['registerForm.'+field]:String(r.id),['registerForm.'+field+'Label']:r.label});this.syncJourneyForm();};
 page.changeCashDirection=function(e){this.setData({journeyDirection:e.currentTarget.dataset.direction});this.refreshJourney();};
 page.openJourneyCash=async function(e){const r=(this.__journeyCash||[]).find(r=>r.key===e.currentTarget.dataset.key);if(!r)return;this.setData({bookEntryDetail:r});if(r.scope.startsWith('contract:'))return;const alive=http.requestGuard('journey-entry-detail');try{const result=await http.get('/franchise/finance/workbench/ledger',{scope:r.scope});if(!alive()||this.__disposed||this.data.bookEntryDetail?.key!==r.key)return;const detail=(result.entries||[]).find(x=>String(x.id)===r.id&&x.type===r.type);if(detail)this.setData({bookEntryDetail:{...r,note:detail.note,voucher_file_id:detail.voucher_file_id||r.voucher_file_id}});}catch(e){if(alive()&&this.data.bookEntryDetail?.key===r.key)wx.showToast({title:'补充资料暂未加载，可稍后重试',icon:'none'});}};
 const bookEntry=page.openBookEntry;
 page.openBookEntry=function(e){bookEntry.call(this,e);const r=this.data.bookEntryDetail;if(!r||!this.data.journeyEnabled)return;const cash=(this.__journeyCash||[]).find(c=>c.id===String(r.id)&&c.type===r.type);this.setData({bookEntryDetail:{...r,...cash,scope:this.data.bookScope,projectName:this.data.bookName}});};
 page.refundBookEntry=function(){const r=this.data.bookEntryDetail;if(!r)return;const type=r.type==='RECEIPT'?'refund':'expense-refund',id=String(r.id);this.openRegister(event(type));this.setData({['registerForm.'+(type==='refund'?'receiptId':'paymentId')]:id,['registerForm.'+(type==='refund'?'receiptIdLabel':'paymentIdLabel')]:r.name,'registerForm.scope':r.scope||this.data.bookScope,'registerForm.scopeLabel':r.projectName||this.data.bookName});this.syncJourneyForm();};
 page.openJourneyDeclaration=function(e){const row=(this.data.journeyDeclarations||[]).find(r=>String(r.id)===String(e.currentTarget.dataset.id));if(!row)return;this.rememberEntryOrigin();this.setData({journeyDeclaration:row,registerType:'',wbForm:null,journeyResult:null});wx.pageScrollTo({scrollTop:0,duration:0});};
 page.collectJourneyStage=function(e){const r=(this.data.financialStages||[]).find(s=>String(s.id)===String(e.currentTarget.dataset.id));if(!r)return;this.openWorkbenchEntry(event('receipt'));const scope=r.project_id?'project:'+r.project_id:'contract:'+r.contract_id;const book=(this.data.workbenchBooks||[]).find(b=>b.scope===scope);this.setData({'wbForm.scope':scope,'wbForm.scopeLabel':book?.name||r.customer_name+' · 合同款','wbForm.stageId':String(r.id),'wbForm.stageLabel':J.stageName(r.stage),'wbForm.name':J.stageName(r.stage),'wbForm.counterparty':r.customer_name,'wbForm.category':'合同回款'});this.syncJourneyForm();};
 page.openJourneyVoucher=async function(){const row=this.data.journeyDeclaration;if(row?.voucher_file_id)await this.openBookVoucher({voucher_file_id:row.voucher_file_id});};
 page.financeEntrySaved=function(type,form,result){
  if(!this.data.journeyEnabled)return;
  const id=typeof result==='object'&&result?(result.receiptId||result.paymentId||result.refundId||result.id):result;
  const labels={receipt:'收款已登记',payment:form.reimbursement?'报销已登记':'付款登记完成',expense:'付款登记完成',refund:'退款登记完成','expense-refund':'退款登记完成',recovery:'归还已登记'};
  if(!labels[type]){this.__entryOrigin=null;this.__entryOptions=null;return;}
  this.setData({journeyDeclaration:null,journeyResult:{title:labels[type],amount:V.money(PF.toCents(form.amount)),name:form.name||form.payableIdLabel||form.receiptIdLabel||form.paymentIdLabel||'',scope:form.scope||'',scopeLabel:form.scopeLabel||'公司收支流水',id:id?String(id):'',key:(type==='receipt'||type==='recovery'?'r-':type==='payment'||type==='expense'?'p-':'f-')+id,employee:form.fundingType==='EMPLOYEE'}});
 };
 page.viewJourneyResult=function(){const r=this.data.journeyResult;if(!r)return;const row=(this.__journeyCash||[]).find(x=>x.key===r.key);if(row){this.setData({bookEntryDetail:row});return;}if(r.scope&&!r.scope.startsWith('contract:')){this.options={...this.options,bookScope:r.scope};this.__entryOrigin=null;this.setData({journeyResult:null,view:'book',bookScope:r.scope,title:'项目账本'});return this.loadBookLedger();}this.setData({journeyResult:null,view:'flows',flowTab:'ledger',title:'收支流水'});this.refreshJourney();};
 page.showBookCalculation=function(){wx.showModal({title:'账目口径',content:'收支结余＝公司累计收款＋支出退回－累计付款－客户退款。项目费用包括已登记的待付费用和员工垫付；报销只结清员工往来，不重复计入项目费用。',showCancel:false});};
 const submit=page.submitRegister,submitEntry=page.submitWorkbench;
 page.journeyInvalid=function(field,message,manual){this.setData({journeyErrorField:field,[manual?'wbError':'registerError']:message});wx.pageScrollTo({selector:'#jf-'+field,duration:200});};
 page.submitJourneyRegister=function(){
  const f=this.data.registerForm||{},type=this.data.registerType;
  if(type==='payment'&&!f.payableId)return this.journeyInvalid('source','请选择待付款事项');
  const amount=PF.toCents(f.amount);if(!amount)return this.journeyInvalid('amount','请输入大于0的金额，最多两位小数');
  const remaining=f.reimbursement?this.data.journeyReimbursement?.remaining:this.data.journeyPayment?.remainingCents;
  if(type==='payment'&&remaining!=null&&amount>remaining)return this.journeyInvalid('amount','本次金额超过待付余额');
  if(type==='payment'&&!String(f.accountName||'').trim())return this.journeyInvalid('account','请选择或填写实际付款账户');
  const date=type==='payment'?f.payDate:f.date;if(!date||date>today())return this.journeyInvalid('date','请选择实际发生日期，不能晚于今天');
  if(type!=='payment'&&!String(f.reason||'').trim())return this.journeyInvalid('reason','请填写退回原因');
  this.setData({journeyErrorField:'',registerError:''});return submit.call(this);
 };
 page.submitJourneyEntry=function(){
  const f=this.data.wbForm||{},contract=f.type==='receipt'&&f.receiptMode!=='other';
  if(!f.scope)return this.journeyInvalid('scope','请选择项目或账本',true);
  if(contract&&!f.stageId)return this.journeyInvalid('stage','请选择本次收到的合同款项',true);
  if(!contract&&!String(f.name||'').trim())return this.journeyInvalid('name','请填写这笔收支的用途',true);
  const amount=PF.toCents(f.amount);if(!amount)return this.journeyInvalid('amount','请输入大于0的金额，最多两位小数',true);
  if(contract&&(this.data.journeyReceiptPending||[]).some(r=>Number(r.amount_cents)===amount&&r.date===f.date))return this.journeyInvalid('amount','已有同日同金额的到账申报，请先核实该笔收款',true);
  if(f.type!=='receipt'&&!String(f.counterparty||'').trim())return this.journeyInvalid('counterparty','请填写收款方',true);
  if(f.type!=='payable'&&!String(f.accountName||'').trim())return this.journeyInvalid('account','请选择或填写实际收付账户',true);
  if(!f.date||f.date>today())return this.journeyInvalid('date','请选择实际发生日期，不能晚于今天',true);
  this.setData({journeyErrorField:'',wbError:''});return submitEntry.call(this);
 };
 // Every secondary form keeps the same return target as the primary entry points.
 for(const name of ['createFinanceBook','linkFinanceBook','openReceiptAllocation','openFinanceSupplement','recoverEmployee','assignPayableBook','openContractChange','openPayableAdjustment','openRevision']){
  const original=page[name];page[name]=function(e){if(this.data.journeyEnabled){if(this.data.wbBusy||this.data.registerBusy||this.data.busy)return;this.rememberEntryOrigin();this.setData({journeyResult:null,journeyDeclaration:null,registerType:'',wbForm:null,bookEntryDetail:null});}const result=original.call(this,e);if(this.data.journeyEnabled){this.syncJourneyForm();wx.pageScrollTo({scrollTop:0,duration:0});}return result;};
 }
 page.cancelRegister=function(){return this.data.journeyEnabled?this.closeFinanceEntry():cancelRegister.call(this);};
 page.cancelWorkbench=function(){return this.data.journeyEnabled?this.closeFinanceEntry():cancelWorkbench.call(this);};
};
