const BookView=require('../../../utils/finance-workbench-view');
const {day:financeDay}=require('../../../utils/finance-date');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const Money = require('../../../utils/platform-finance');
const date = () => require('../../../utils/account').localDate();
const yuan = n => (Number(n || 0) / 100).toFixed(2);
const base = '/franchise/finance/workbench';
const Accounting = require('../accounting/model');
module.exports = function (page, api) {
  page.loadWorkbench = async function () {
    this.__messageWorkbenchReady=false;
    const alive = this.financeReadGuard?this.financeReadGuard('fin-workbench'):http.requestGuard('fin-workbench');
    this.setData({ workbenchError: '' });
    try {
      const [books, reimbursements, staff, declarations, stages, receipts,commission] = await Promise.all([http.get(base + '/books'), http.get(base + '/reimbursements'), http.get(base + '/staff'),http.get('/franchise/sales/collection-declarations'),http.get('/franchise/sales/financial-stages'),api.receipts(),http.get('/franchise/performance/commission/rows').catch(()=>null)]);
      if (!alive() || this.__disposed) return;
      const rows = reimbursements.map(r => ({ ...r, pay_date:financeDay(r.pay_date), id: r.payable_id, remaining: Number(r.eligible_cents) - Number(r.reimbursed_cents) + Number(r.recovered_cents||0),
        absoluteAmount:yuan(Math.abs(Number(r.eligible_cents)-Number(r.reimbursed_cents)+Number(r.recovered_cents||0))),amount: yuan(Number(r.eligible_cents) - Number(r.reimbursed_cents) + Number(r.recovered_cents||0)), paidText: yuan(r.reimbursed_cents) }));
      this.setData({ allocationReceipts:receipts.filter(r=>r.status==='已登记'&&r.sourceType==='manual'&&!r.sourceRef).map(r=>({...r,label:r.name+' · ¥'+yuan(r.amountCents)})),financialStages: stages.map(r=>({...r,stageLabel:r.label||require("../../../utils/finance-journey-view").stageName(r.stage),label: (r.customer_name||"合同收款")+" · "+(r.label||require("../../../utils/finance-journey-view").stageName(r.stage))+" · 待收 ¥"+BookView.money(Math.max(0,Number(r.amount_cents)-Number(r.collected_cents)))})),workbenchBooks: books, reimbursementRows: rows, workbenchStaff: staff, collectionDeclarations: declarations.map(r=>({...r,amount:yuan(r.amount_cents)})),
        'deskHome.focus': rows.filter(r => r.remaining !== 0).map(r => ({ id: r.id, name: r.payer_name + ' · '+r.stage,
          amount: '¥' + BookView.money(r.remaining), status: r.remaining < 0 ? '员工应归还公司' : Number(r.reimbursed_cents) > 0 ? '部分报销' : '待报销', url: '/pages/admin/finance-desk/index?view=reimbursements' })) });
      this.__commissionStages=commission===null?null:commission.flatMap(r=>r.stages||[]);
      this.__messageWorkbenchReady=true;
      if(this.refreshJourney)this.refreshJourney();if (this.data.view === 'projects') this.loadFinanceProjects();if(this.data.view==='book'&&this.data.bookEntryRows)this.updateBookEmployees();
    } catch (e) { if (alive() && !this.__disposed){const patch={workbenchError:e.message||e.msg||'账本及报销加载失败'};if(this.financeReadError)this.financeReadError(e,patch);else this.setData(patch);} }
  };
  page.returnCollectionDeclaration=function(e){
    const id=String(e.currentTarget.dataset.id),alive=http.sessionGuard();
    wx.showModal({title:'退回补充到账资料',editable:true,placeholderText:'填写需补充或更正的内容',success:async result=>{
      if(!result.confirm||!alive()||this.__disposed)return;
      if(!(result.content||'').trim())return this.setData({workbenchError:'请填写退回原因'});
      try{await http.post('/franchise/sales/collection-declarations/'+encodeURIComponent(id)+'/return',{reason:result.content.trim()},{'Idempotency-Key':api.idemKey('collection-return')});if(alive()&&!this.__disposed){if(this.data.journeyEnabled&&this.closeFinanceEntry)this.closeFinanceEntry();this.loadWorkbench();if(this.refreshFinanceTasks)this.refreshFinanceTasks();wx.showToast({title:'已退回补充'});}}
      catch(error){if(alive()&&!this.__disposed)this.setData({workbenchError:error.message||'退回失败'});}
    }});
  };
  page.confirmCollectionDeclaration = async function(e){
    const id=String(e.currentTarget.dataset.id);if(this.data.wbBusy)return;
    this.declarationKeys=this.declarationKeys||{};if(!this.declarationKeys[id])this.declarationKeys[id]=api.idemKey('collect-confirm');
    const alive=http.sessionGuard();this.setData({wbBusy:true,workbenchError:''});
    try{const result=await http.post('/franchise/sales/collection-declarations/'+encodeURIComponent(id)+'/confirm',{}, {'Idempotency-Key':this.declarationKeys[id]});if(!alive()||this.__disposed)return;delete this.declarationKeys[id];if(this.financeEntrySaved&&this.data.journeyDeclaration){const d=this.data.journeyDeclaration;this.financeEntrySaved('receipt',{name:d.name,amount:(d.amount_cents/100).toFixed(2),scope:d.scope,scopeLabel:d.projectName},result);}this.loadWorkbench();this.loadPlatformFinance(true);if(this.refreshFinanceTasks)this.refreshFinanceTasks();wx.showToast({title:'已核实到账'});}
    catch(e){if(alive()&&!this.__disposed)this.setData({workbenchError:e.message||e.msg||'核实失败'});}finally{if(alive()&&!this.__disposed)this.setData({wbBusy:false});}
  };
  page.loadFinanceProjects = async function () {
    const alive=this.financeReadGuard?this.financeReadGuard('finance-projects'):http.requestGuard('finance-projects');this.setData({financeProjectLoading:true,financeProjectError:''});
    try{const books=await http.get(base+'/books');if(!alive()||this.__disposed)return;
      this.setData({financeProjectLoading:false,workbenchBooks:books});this.filterFinanceBooks();if(this.refreshJourney)this.refreshJourney();
    }catch(e){if(alive()&&!this.__disposed){const patch={financeProjectLoading:false,financeProjectError:e.message||'账本加载失败'};if(this.financeReadError)this.financeReadError(e,patch);else this.setData(patch);}}
  };
  page.filterFinanceBooks=function(){const rows=BookView.books(this.data.workbenchBooks,this.data.keyword||'',this.data.bookListTab||'all');this.setData({financeProjects:rows,financeProjectTotal:rows.length});};
  page.changeBookListTab=function(e){this.setData({bookListTab:e.currentTarget.dataset.tab});this.filterFinanceBooks();};
  page.loadBookLedger = async function () {
    const scope=this.options.bookScope,alive=this.financeReadGuard?this.financeReadGuard('finance-book-ledger'):http.requestGuard('finance-book-ledger');
    if(scope!==this.data.bookScope)this.setData({bookResult:null,bookEntryRows:[],bookEntryDetail:null,bookEntries:[],bookSummary:[],bookEmployeeRows:[],bookName:'',bookNet:'—',bookCost:'—',bookAccounting:null,bookAccountingUrl:'',bookCollectionUrl:'',bookMaterialsUrl:'',bookExpensesUrl:''});
    this.setData({bookLoading:true,bookError:'',bookScope:scope,canLinkBook:String(scope||'').startsWith('book:')});
    try{
      const [result,books]=await Promise.all([http.get(base+'/ledger',{scope}),http.get(base+'/books')]);
      if(!alive()||this.__disposed||scope!==this.options.bookScope)return;
      const s=result.summary,entryRows=BookView.bookRows(result.entries),book=books.find(b=>b.scope===scope);
      const projectId = String(scope).startsWith('project:') ? Accounting.id(String(scope).slice(8)) : '';
      const accounting = result.accounting && projectId ? Accounting.view(result.accounting,projectId) : null;
      this.setData({bookLoading:false,bookResult:result,workbenchBooks:books,bookName:book?book.name:'项目账本',bookEntryRows:entryRows,
        bookFlowTab:this.data.bookFlowTab||'cash',bookSummary:BookView.summary(s),
        bookAccounting:accounting,bookAccountingUrl:projectId?'/pages/admin/accounting/index?projectId='+encodeURIComponent(projectId):'',
        bookNet:BookView.money(Number(s.received_cents)+Number(s.expense_refunded_cents)-Number(s.paid_cents)-Number(s.refunded_cents)),bookCost:BookView.money(s.committed_cents),bookEntryDetail:null,bookCollectionUrl:'/pages/build/record/index?id='+encodeURIComponent(String(scope).slice(8))+'&view=collection',bookMaterialsUrl:'/pages/build/record/index?id='+encodeURIComponent(String(scope).slice(8))+'&view=materials',bookExpensesUrl:'/pages/build/record/index?id='+encodeURIComponent(String(scope).slice(8))+'&view=expenses'});this.filterBookEntries();this.updateBookEmployees();
    }catch(e){if(alive()&&!this.__disposed){const patch={bookLoading:false,bookError:e.message||'账本加载失败'};if(this.financeReadError)this.financeReadError(e,patch);else this.setData(patch);}}
  };
  page.filterBookEntries=function(){this.setData({bookEntries:BookView.filteredRows(this.data.bookEntryRows,this.data.bookFlowTab||'cash')});};
  page.updateBookEmployees=function(){
    const ids=new Set((this.data.bookResult?.reimbursementIds||[]).map(String));
    this.setData({bookEmployeeRows:(this.data.reimbursementRows||[]).filter(r=>ids.has(String(r.id))).map(r=>({...r,eligibleText:BookView.money(r.eligible_cents),paidText:BookView.money(r.reimbursed_cents),remainingText:BookView.money(Math.abs(r.remaining))}))});
  };
  page.changeBookFlowTab=function(e){this.setData({bookFlowTab:e.currentTarget.dataset.tab});this.filterBookEntries();};
  page.openBookEntry=function(e){const key=e.currentTarget.dataset.key;const entry=(this.data.bookEntryRows||[]).find(r=>r.key===key);if(entry)this.setData({bookEntryDetail:entry});};
  page.closeBookEntry=function(){this.setData({bookEntryDetail:null});};
  page.openBookVoucher=async function(e){
    const entry=e&&e.voucher_file_id?e:this.data.bookEntryDetail,alive=http.sessionGuard();if(!entry||!entry.voucher_file_id)return;
    try{const ticket=await http.post('/franchise/assets/'+encodeURIComponent(entry.voucher_file_id)+'/download-tickets');
      const file=await require('../../../utils/b-asset-download').downloadTicketFile({http,downloadUrl:ticket.downloadUrl,alive:()=>alive()&&!this.__disposed,label:'凭据'});if(!alive()||this.__disposed)return;
      if(/^image\//i.test(file.contentType))wx.previewImage({urls:[file.tempFilePath]});else wx.openDocument({filePath:file.tempFilePath,fileType:'pdf',showMenu:true,fail:()=>wx.showToast({title:'凭据打开失败',icon:'none'})});
    }catch(e){if(alive()&&!this.__disposed)wx.showToast({title:e.message||'凭据加载失败',icon:'none'});}
  };
  page.openReceiptAllocation=function(e){this.wbKey=api.idemKey('allocate');this.setData({wbForm:{type:'allocation',name:'前期定金抵合同款',stageId:String(e.currentTarget.dataset.id)},wbError:''});};
  page.openFinanceSupplement=function(e){this.setData({bookEntryDetail:null});this.wbKey=api.idemKey('supplement');this.setData({wbForm:{type:'supplement',sourceType:e.currentTarget.dataset.kind,sourceId:String(e.currentTarget.dataset.id).replace(/^[rpf]-/,''),name:'补充原收付资料'},wbError:''});};
  page.recoverEmployee=function(e){const row=(this.data.reimbursementRows||[]).find(r=>r.id===String(e.currentTarget.dataset.id));if(!row||row.remaining>=0)return;this.wbKey=api.idemKey('employee-recover');this.setData({wbForm:{type:'recovery',payableId:row.id,name:'员工归还 · '+row.payer_name,amount:yuan(-row.remaining),date:date(),accountName:''},wbError:''});};
  page.assignPayableBook=function(e){this.wbKey=api.idemKey('assign-book');this.setData({wbForm:{type:'assignbook',payableId:String(e.currentTarget.dataset.id),name:'补充账本归属'},wbError:''});};
  page.openContractChange=function(e){
    if(this.data.wbBusy||this.data.busy||this.__disposed)return;
    const id=String(e.currentTarget.dataset.id);
    if(!(this.data.financialStages||[]).some(r=>String(r.contract_id)===id))return this.setData({wbError:'合同数据已变化，请刷新后重试'});
    require('../../../utils/page-navigation').open('/pages/workflow/payment-plan/index?contractId='+encodeURIComponent(id));
  };
  page.openPayableAdjustment=function(e){this.wbKey=api.idemKey('payable-adjust');this.setData({wbForm:{type:'payablechange',payableId:String(e.currentTarget.dataset.id),name:'采购/费用结算调整'},wbError:''});};
  page.linkFinanceBook=function(){this.wbKey=api.idemKey('book-link');this.setData({wbForm:{type:'linkbook',bookId:String(this.data.bookScope).slice(5),name:'关联已有工程'},wbError:''});};
  page.createFinanceBook = function () {
    this.setData({ wbForm: { type: 'book', name: '' }, wbError: '' }); this.wbKey = api.idemKey('book');
  };
  page.openWorkbenchEntry = function (e) {
    if(this.data.wbBusy||this.data.registerBusy||this.data.busy)return;
    const type = e.currentTarget.dataset.type;
    if (type === 'reimbursement') { this.setData({ view: 'reimbursements', title: '报销办理' }); return this.loadWorkbench(); }
    this.wbKey = api.idemKey(type); this.wbGeneration = (this.wbGeneration || 0) + 1;
    this._registerSeq=(this._registerSeq||0)+1;this.registerIdem=null;
    this.setData({ registerType:'',registerForm:{},registerError:'',bookEntryDetail:null, wbForm: { type, scope: this.data.view === 'book' ? (this.data.bookScope || '') : '',scopeLabel:this.data.view==='book'?this.data.bookName:'', date: date(), category: type === 'receipt' ? '其他收款' : '其他费用', accountName: '' }, wbError: '' });
  };
  page.wbInput = function (e) { const field=e.currentTarget.dataset.field;this.setData({ ['wbForm.' + field]: field==='fundingType'?['COMPANY','EMPLOYEE'][Number(e.detail.value)]:e.detail.value }); };
  page.wbPick = function (e) {
    const field = e.currentTarget.dataset.field;if(field==='receiptId'){const row=(this.data.allocationReceipts||[])[Number(e.detail.value)];if(row)this.setData({'wbForm.receiptId':row.id,'wbForm.receiptLabel':row.label});return;}if(field==='stageId'){const row=(this.data.financialStages||[])[Number(e.detail.value)];if(row)this.setData({'wbForm.stageId':row.id,'wbForm.stageLabel':row.label});return;}const rows = field === 'scope' ? this.data.workbenchBooks : this.data.workbenchStaff;
    const row = (rows || [])[Number(e.detail.value)]; if (!row) return;
    this.setData({ ['wbForm.' + field]: field === 'scope' ? row.scope : row.id, ['wbForm.' + field + 'Label']: row.name });
  };
  page.cancelWorkbench = function () { if (this.data.wbBusy) return; this.wbGeneration = (this.wbGeneration || 0) + 1; this.setData({ wbForm: null, wbError: '' }); };
  page.wbVoucher = function () {
    if (this.data.wbBusy || !this.data.wbForm) return;
    const session = http.sessionGuard(), generation = this.wbGeneration;
    const selection = this.__voucherSelection = (this.__voucherSelection || 0) + 1;
    const context = () => { const f = this.data.wbForm || {}; return JSON.stringify([f.type,f.scope,f.id,f.recordId,f.sourceType,f.sourceId,f.payableId,f.stageId,f.receiptId]); };
    const original = context();
    const alive = () => session() && !this.__disposed && generation === this.wbGeneration && selection === this.__voucherSelection && context() === original;
    return require('../../../utils/finance-voucher').selectVoucher({ alive }).then(file => {
      if (file && alive()) this.setData({ 'wbForm.voucherPath': file.path, 'wbForm.voucherName': file.name, 'wbForm.voucherFileId': null, wbError: '' });
    }).catch(error => { if (alive()) this.setData({ wbError: error.message }); });
  };
  page.submitWorkbench = async function () {
    if (this.data.wbBusy || !this.data.wbForm) return;
    const f = { ...this.data.wbForm }, aliveSession = http.sessionGuard(), generation = this.wbGeneration;
    if (f.payrollRecordId) {
      const post = auth.currentApiPost(), route = this.options || {};
      if (String(post?.orgId) !== route.payrollCompany || String(post?.postId) !== route.payrollPost ||
          !['finance', 'finance-lead'].includes(post?.roleCode) || require('../../../utils/api-scope')() !== this.__ledgerRouteIdentity) {
        return this.setData({ wbForm: null, paymentNotice: '当前工作身份已变化，请重新核对薪酬原单' });
      }
    }
    const alive = () => aliveSession() && !this.__disposed && generation === this.wbGeneration;
    try {
      if (!f.name || !f.name.trim()) throw Error('请填写名称或事项说明');
      let body = { name: f.name.trim() }, endpoint = '/books';
      if(['contractchange','payablechange','linkbook','assignbook','recovery','supplement','allocation'].includes(f.type)){
        if(f.type==='contractchange'){
          return this.openContractChange({currentTarget:{dataset:{id:f.contractId}}});
        }else if(f.type==='payablechange'){
          const total=String(f.amount)==='0'?0:Money.toCents(f.amount);if(total===null||!(f.reason||'').trim())throw Error('请填写调整后结算总额和依据');
          body={totalCents:total,reason:f.reason,dueDate:f.dueDate||undefined};endpoint=base+'/payables/'+encodeURIComponent(f.payableId)+'/adjust';
        }else if(f.type==='allocation'){if(!f.receiptId)throw Error('请选择原实际收款');body={};endpoint='/franchise/sales/stages/'+encodeURIComponent(f.stageId)+'/allocate-receipt/'+encodeURIComponent(f.receiptId);
        }else if(f.type==='supplement'){
          if(!f.voucherPath&&!(f.reason||'').trim())throw Error('请填写补充说明或凭据');body={type:f.sourceType,note:f.reason};endpoint=base+'/entries/'+encodeURIComponent(f.sourceId)+'/supplement';
        }else if(f.type==='recovery'){
          const amount=Money.toCents(f.amount);if(!amount||!f.accountName||!f.date)throw Error('请填写归还金额、账户及实际日期');
          body={amountCents:amount,date:f.date,accountName:f.accountName,transactionNo:f.transactionNo||undefined};endpoint=base+'/reimbursements/'+encodeURIComponent(f.payableId)+'/recover';
        }else if(f.type==='assignbook'){
          if(!f.scope||!f.reason)throw Error('请选择账本并填写依据');body={scope:f.scope,reason:f.reason};endpoint=base+'/payables/'+encodeURIComponent(f.payableId)+'/assign';
        }else{
          if(!(f.scope||'').startsWith('project:'))throw Error('请选择已有工程项目');
          body={};endpoint=base+'/books/'+encodeURIComponent(f.bookId)+'/project/'+encodeURIComponent(f.scope.slice(8));
        }
      }else if (f.type !== 'book') {
        const amountCents = Money.toCents(f.amount);
        if (!amountCents || (!f.scope&&!f.stageId) || !f.date || f.date > date() || (f.type!=='payable'&&!f.accountName.trim())) throw Error('请填写归属、金额、实际日期及收付账户');
        if (f.type !== 'receipt' && !(f.counterparty || '').trim()) throw Error('请填写实际收款方');
        body = { scope: f.scope, type: f.type, name: f.name.trim(), amountCents, category: f.category, date: f.date,
          fundingType:f.fundingType||'COMPANY',payerUserId:f.payerUserId,payerName:f.payerUserIdLabel,counterparty: f.counterparty, accountName: f.accountName, transactionNo: f.transactionNo || undefined };
        endpoint = '/entries';
      }
      this.setData({ wbBusy: true, wbError: '' });
      if (f.voucherPath) {
        body.voucherFileId=f.voucherFileId||await api.uploadVoucher(f.voucherPath, f.voucherName);
        if(alive())this.setData({'wbForm.voucherFileId':body.voucherFileId});
      }
      if (!alive()) return;
      if (f.payrollRecordId) {
        return await this.dispatchPreparedPayment({ payrollRecordId: f.payrollRecordId, expectedRegistrationCount: f.payrollRegistrationCount,
          amountCents: body.amountCents, date: body.date, accountName: body.accountName, transactionNo: body.transactionNo,
          voucherFileId: body.voucherFileId }, this.wbKey, f, 'payroll-payment');
      }
      const result=f.type==='receipt'&&f.stageId?await http.post('/franchise/sales/stages/'+encodeURIComponent(f.stageId)+'/collect',{amountCents:body.amountCents,method:'other',transactionNo:body.transactionNo,receivedDate:body.date,voucherFileId:body.voucherFileId,accountName:body.accountName},{'Idempotency-Key':this.wbKey}):await http.post(endpoint.startsWith('/franchise/')?endpoint:base + endpoint, body, { 'Idempotency-Key': this.wbKey });
      if (!alive()) return;
      if(f.type==='linkbook'){this.options={...this.options,bookScope:f.scope};this.setData({bookScope:f.scope,canLinkBook:false});}
      if(this.financeEntrySaved)this.financeEntrySaved(f.type,f,result);
      this.setData({ wbForm: null }); this.loadWorkbench(); this.loadPlatformFinance(true);
      if (this.data.view === 'book') this.loadBookLedger();if(this.data.view==='projects')this.loadFinanceProjects();
      wx.showToast({ title: ['payment','expense'].includes(f.type) ? '付款登记完成' : '已登记' });wx.pageScrollTo({scrollTop:0,duration:0});if(this.refreshFinanceTasks)this.refreshFinanceTasks();
    } catch (e) { if (alive()) this.setData({ wbError: e.message || e.msg || '登记失败，请重试' }); }
    finally { if (alive()) this.setData({ wbBusy: false }); }
  };
  page.reimburseEmployee = function (e) {
    const row = (this.data.reimbursementRows || []).find(r => r.id === String(e.currentTarget.dataset.id));
    if (!row || row.remaining <= 0) return;
    this.setData({ view: 'flows', flowTab: 'ledger' });
    this.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
    this.setData({ 'registerForm.payableId': row.id, 'registerForm.payableIdLabel': row.payer_name + ' · '+row.stage, 'registerForm.amount': row.amount, 'registerForm.payDate': date(),'registerForm.allowEmployee':false });wx.pageScrollTo({scrollTop:0,duration:0});
  };
  page.installWorkbenchActions = function () {
    if (this.__wbActionsInstalled || !this.openRegister) return;
    const original = this.openRegister;
    this.openRegister = function (e) {
      const type = e.currentTarget.dataset.type;
      if (auth.currentApiPost()?.orgType !== 'headquarters' && ['receipt','payable','reimbursement'].includes(type)) return this.openWorkbenchEntry(e);
      return original.call(this, e);
    };
    this.__wbActionsInstalled = true;
  };
};
