const BookView=require('../../../utils/finance-workbench-view');
const {day:financeDay}=require('../../../utils/finance-date');
const http = require('../../../api/http');
const Money = require('../../../utils/money');
const Finance = require('../../../utils/platform-finance');
const LedgerValue = require('../accounting/model');
const today = () => require('../../../utils/account').localDate();
const yuan = cents => ((Number(cents) || 0) / 100).toFixed(2);
const ledgerLabels = { PAYABLE: '费用', CONFIRMED_ENGINEERING_COST: '已确认工程费用（未挂应付）', PAYMENT: '付款',
  ADVANCE: '员工垫付', RECEIPT: '收款', REFUND: '客户退款', EXPENSE_REFUND: '支出退回公司',
  EMPLOYEE_REFUND: '垫付退回员工', RECEIVABLE: '合同应收', PENDING_RECEIPT: '待核实收款' };
const ledgerNames = { FULL: '设计费', FIRST: '设计首款', TAIL: '设计尾款', engineering: '材料采购', engineering_expense: '工程费用' };
function projectLedgerRow(row, index) {
  const originalExpenseId = row.type === 'CONFIRMED_ENGINEERING_COST' ? LedgerValue.id(row.originalExpenseId) : '';
  const id = LedgerValue.id(row.recordId || originalExpenseId || row.id), details = row.details || {};
  const label = ledgerLabels[row.type] || '账目记录', amount = LedgerValue.money(row.amountCents), date = financeDay(row.occurredOn);
  const income = ['RECEIPT', 'EXPENSE_REFUND', 'EMPLOYEE_REFUND'].includes(row.type);
  const sign = income ? '+' : ['PAYMENT', 'REFUND', 'ADVANCE'].includes(row.type) ? '−' : '';
  return { ...row, id, originalExpenseId, key: row.type + ':' + (id || 'unresolved-' + index), label, typeLabel: label,
    name: ledgerNames[details.name] || details.name || label, amount, amountText: amount, date, dateText: date,
    tone: income ? 'income' : 'expense', sign, counterparty: details.counterparty || '',
    account_name: details.accountName || '', transaction_no: details.transactionNo || '',
    voucher_file_id: LedgerValue.id(details.voucherFileId), note: details.note || '',
    sourceLabel: ({ LABOR: '人工', TRAVEL: '差旅', OTHER: '其他' })[details.category] || details.category || label,
    statusText: ({ PENDING: '待收款', OPEN: '待收款', PARTIAL: '部分到账', SETTLED: '已收齐' })[row.status] || row.status };
}

function receiptBody(row, payment, form) {
  const amountCents = Money.parseCents(form.amount);
  if (amountCents <= 0 || amountCents > payment.receivableCents) throw Error('核收金额须大于0且不超过原付款剩余可核收额');
  if (!form.receivedDate || form.receivedDate > today()) throw Error('请填写有效实际到账日期');
  if (!String(form.transactionNo || '').trim()) throw Error('请填写实际银行流水号');
  return { sourceType: 'design', designCommissionId: String(row.commissionId), designStage: row.stage,
    originPaymentId: String(payment.paymentId), amountCents, receivedDate: form.receivedDate,
    method: 'bank', transactionNo: form.transactionNo.trim(), name: '设计费核收 ' + row.commissionId + ' ' + row.stage };
}

function attach(page, api) {
  page.loadFinanceSourceRows = async function () {
    const tab = this.data.flowTab;
    if (!['payables', 'receivables'].includes(tab)) return;
    const alive = (this.financeReadGuard?this.financeReadGuard('finance-source-rows'):http.requestGuard('finance-source-rows'));
    this.setData({ platformNotice: '', error: '' });
    try {
      const [source, payments, refunds] = tab === 'payables'
        ? await Promise.all([api.payables(), api.payments(), api.refunds()])
        : [await api.receipts('待登记'), [], []];
      if (!alive() || this.__disposed || this.data.flowTab !== tab) return;
      const keyword = String(this.data.keyword || '').trim().toLowerCase();
      const progress = tab === 'payables' ? Finance.payableProgress(source, payments, refunds) : source;
      const rows = progress.filter(row => !keyword || [row.id, row.name, row.projectId, row.sourceRef].join(' ').toLowerCase().includes(keyword)).map(row => ({
        id: String(row.id), name: row.name || ('原单 ' + row.id), status: row.status,
        amountText: '¥' + yuan(tab === 'payables' ? row.remainingCents : row.amountCents), subtitle: row.sourceRef || '',
        dateText: row.dueDate || row.businessDate || row.receivedDate || '日期待核', detail: tab === 'payables' ? '累计应付 ¥' + yuan(row.totalCents) + ' · 已付 ¥' + yuan(row.paidCents) : '待登记原单',
        sourceType:row.sourceType,projectId:row.projectId,bookId:row.bookId,deleted:row.deleted,
        canAssign:tab==='payables'&&row.canAssign,
        canAdjust:!['design','reimbursement'].includes(row.sourceType),payableId: tab === 'payables' ? String(row.id) : '', canPay: tab === 'payables' && row.canPay }));
      this.setData({ rows, ready: true });
    } catch (e) { if (alive() && !this.__disposed){const patch={platformNotice:e.message||e.msg||'原单列表加载失败'};if(this.financeReadError)this.financeReadError(e,patch);else this.setData(patch);} }
  };
  page.paySourceRow = function (e) {
    const row = (this.data.rows || []).find(r => r.id === String(e.currentTarget.dataset.id));
    if (!row || !row.canPay) return;
    this.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
    this.setData({ flowTab: 'ledger', 'registerForm.payableId': row.payableId, 'registerForm.payableIdLabel': row.name, 'registerForm.payDate': today() });
  };
  page.loadFinanceProjects = async function (append = false) {
    const alive = http.requestGuard('finance-projects'), pageNo = append ? (this.financeProjectPage || 1) + 1 : 1;
    this.setData({ financeProjectError: '', financeProjectLoading: true });
    try {
      const result = await api.projects(pageNo, 20, this.data.keyword || '');
      if (!alive() || this.__disposed) return;
      this.financeProjectPage = pageNo;
      const rows = result.list.map(row => ({ ...row, id: String(row.projectId), url: '/pages/admin/finance-desk/index?view=project&id=' + encodeURIComponent(row.projectId) }));
      this.setData({ financeProjectLoading: false, financeProjects: append ? (this.data.financeProjects || []).concat(rows) : rows, financeProjectTotal: result.total });
    } catch (e) { if (alive() && !this.__disposed) this.setData({ financeProjectLoading: false, financeProjectError: e.message || e.msg || '项目列表加载失败' }); }
  };
  page.moreFinanceProjects = function () { if ((this.data.financeProjects || []).length < this.data.financeProjectTotal) this.loadFinanceProjects(true); };
  page.loadDesignFees = async function (append = false) {
    append = append === true;
    const alive = (this.financeReadGuard?this.financeReadGuard('design-fees'):http.requestGuard('design-fees'));
    const pageNo = append ? (this.designFeePage || 1) + 1 : 1;
    this.setData({ designFeeStatus: 'loading', designFeeError: '' });
    try {
      let result = await api.designFees(pageNo, 20);
      const messageId=this.options?.messageType==='design_fee'?String(this.options.messageObject||''):'';
      if(messageId&&!append){
        let batch=result,n=pageNo,found=batch.list.find(r=>String(r.payableId)===messageId);
        while(!found&&n*20<batch.total){batch=await api.designFees(++n,20);if(!alive()||this.__disposed)return;found=batch.list.find(r=>String(r.payableId)===messageId);}
        result={list:found?[found]:[],total:found?1:0};
        if(!found)this.setData({designFeeError:'该设计款项已变更或当前岗位不可查看，请返回消息中心刷新'});
      }
      if (!alive() || this.__disposed) return;
      this.designFeePage = pageNo;
      const rows = result.list.map(row => ({ ...row, key: row.commissionId + ':' + row.submissionVersion + ':' + row.stage,
        stageLabel: ({FULL:'全额',FIRST:'首款',TAIL:'尾款'})[row.stage] || '设计费', expectedText: yuan(row.expectedCents), paidText: yuan(row.storePaidNetCents), receivedText: yuan(row.hqReceivedNetCents), exceptionText: row.originPaymentOverReceivedCents > 0 ? '原付款与平台核收金额不一致，请先核对来源并更正' : row.financialException ? '下发后发生退款或退回，当前净额不足，请核对' : '',
        canPay: row.allowedActions.includes('PAYMENT_REGISTER'),
        payments: row.payments.map(p => ({ ...p, receivableText: yuan(p.receivableCents), canReceive: row.allowedActions.includes('RECEIPT_REGISTER') && p.receivableCents > 0 })) }));
      this.setData({ designFeeStatus: 'ready', designFeeRows: append ? (this.data.designFeeRows || []).concat(rows) : rows, designFeeTotal: result.total });
    } catch (e) { if (alive() && !this.__disposed){const patch={designFeeStatus:'error',designFeeError:e.message||e.msg||'设计费用加载失败'};if(this.financeReadError)this.financeReadError(e,patch);else this.setData(patch);} }
  };
  page.moreDesignFees = function () { if ((this.data.designFeeRows || []).length < this.data.designFeeTotal) this.loadDesignFees(true); };
  page.openDesignPayment = function (e) {
    const row = (this.data.designFeeRows || []).find(r => r.key === e.currentTarget.dataset.key);
    if (!row || !row.canPay) return;
    this.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
    this.setData({ view: 'flows', flowTab: 'ledger', 'registerForm.payableId': String(row.payableId),
      'registerForm.allowEmployee':true,'registerForm.payableIdLabel': '委托 ' + row.commissionId + ' V' + row.submissionVersion + ' ' + row.stage,
      'registerForm.amount': yuan(Math.max(0, row.expectedCents - row.storePaidNetCents)), 'registerForm.payDate': today() });
  };
  page.openDesignReceipt = function (e) {
    const row = (this.data.designFeeRows || []).find(r => r.key === e.currentTarget.dataset.key);
    const payment = row && row.payments.find(p => String(p.paymentId) === String(e.currentTarget.dataset.paymentId));
    if (!payment || !payment.canReceive) return;
    this.__designReceipt = { row, payment, idem: api.idemKey('design-receipt') };
    this.setData({ designReceiptForm: { amount: '', receivedDate: today(), transactionNo: '', voucherName: '', voucherPath: '' }, designReceiptError: '', designReceiptPaymentId: String(payment.paymentId) });
  };
  page.designReceiptInput = function (e) { this.setData({ ['designReceiptForm.' + e.currentTarget.dataset.field]: e.detail.value }); };
  page.cancelDesignReceipt = function () { this.__designReceipt = null; this.setData({ designReceiptForm: null, designReceiptBusy: false }); };
  page.designReceiptVoucher = function () {
    if (this.data.designReceiptBusy || !this.__designReceipt) return;
    const ctx = this.__designReceipt, session = http.sessionGuard();
    const selection = this.__voucherSelection = (this.__voucherSelection || 0) + 1;
    const alive = () => session() && ctx === this.__designReceipt && !this.__disposed && selection === this.__voucherSelection;
    return require('../../../utils/finance-voucher').selectVoucher({ alive }).then(file => {
      if (file && alive()) this.setData({ 'designReceiptForm.voucherPath': file.path, 'designReceiptForm.voucherName': file.name, designReceiptError: '' });
    }).catch(error => { if (alive()) this.setData({ designReceiptError: error.message }); });
  };
  page.submitDesignReceipt = async function () {
    if (this.data.designReceiptBusy || !this.__designReceipt) return;
    const ctx = this.__designReceipt, session = http.sessionGuard();
    const alive = () => session() && ctx === this.__designReceipt && !this.__disposed;
    const form = { ...this.data.designReceiptForm };
    this.setData({ designReceiptBusy: true, designReceiptError: '' });
    try {
      const body = receiptBody(ctx.row, ctx.payment, form);
      if (form.voucherPath) body.voucherFileId = await api.uploadVoucher(form.voucherPath, form.voucherName);
      if (!alive()) return;
      await api.registerReceipt(body, ctx.idem);
      if (!alive()) return;
      this.cancelDesignReceipt(); this.loadPlatformFinance(true); this.loadDesignFees();
      wx.showToast({ title: '设计款已核收', icon: 'success' });
    } catch (e) { if (alive()) this.setData({ designReceiptBusy: false, designReceiptError: e.message || e.msg || '核收失败，请重试' }); }
  };
  page.loadProjectLedger = async function (append = false) {
    if (this.canLoadProjectLedger && !this.canLoadProjectLedger()) return;
    if (!this.id) return this.setData({ ledgerProjectError: '缺少工程项目编号' });
    const id = String(this.id), alive = http.requestGuard('project-ledger');
    const pageNo = append ? (this.projectLedgerPage || 1) + 1 : 1;
    this.setData({ ledgerProjectError: '', ledgerProjectLoading: true, ...(!append ? { bookEntryDetail: null } : {}) });
    this.setData({ id });
    try {
      const result = await api.projectLedger(id, pageNo, 20);
      if (!alive() || this.__disposed || String(this.id) !== id || (this.canLoadProjectLedger && !this.canLoadProjectLedger())) return;
      this.projectLedgerPage = pageNo;
      const s=result.summary;
      const entries = result.entries.list.map((row, index) => projectLedgerRow(row, (pageNo - 1) * 20 + index));
      const metrics = [
        {key:'received',label:'累计收款',value:BookView.money(s.receivedCents)},
        {key:'paid',label:'累计付款',value:BookView.money(s.paidCents)}
      ];
      if (s.refundedCents > 0 || s.expenseRefundedCents > 0) {
        metrics.push({key:'refunded',label:'收款退款',value:BookView.money(s.refundedCents)},
          {key:'returned',label:'支出退回',value:BookView.money(s.expenseRefundedCents)});
      }
      metrics.push({key:'contract',label:'合同金额',value:BookView.money(s.receivableCents)},
        {key:'cost',label:'项目费用',value:BookView.money(s.payableCents)});
      this.setData({
        ledgerProjectLoading:false, projectBookName:result.projectName||'项目账本',
        projectBookNet:BookView.money(Number(s.receivedCents)+Number(s.expenseRefundedCents)-Number(s.paidCents)-Number(s.refundedCents)),
        projectLedgerSummary:metrics, projectLedgerEntries:append?(this.data.projectLedgerEntries||[]).concat(entries):entries,
        projectLedgerTotal:result.entries.total
      });
    } catch (e) { if (alive() && !this.__disposed && (!this.canLoadProjectLedger || this.canLoadProjectLedger())) this.setData({ ledgerProjectLoading: false, ledgerProjectError: e.message || e.msg || '项目账加载失败' }); }
  };
  page.moreProjectLedger = function () { if ((this.data.projectLedgerEntries || []).length < this.data.projectLedgerTotal) this.loadProjectLedger(true); };
  page.openProjectLedgerEntry = function (event) {
    if (!this.data.projectLedgerReadOnly || this.__financeHidden || this.data.ledgerProjectError || !this.canLoadProjectLedger?.()) return;
    const row = (this.data.projectLedgerEntries || []).find(entry => entry.key === event.currentTarget.dataset.key);
    if (row?.id) this.setData({ bookEntryDetail: row });
  };
  // Boss pages attach only these read actions; finance workbench installs its own handlers later.
  page.closeBookEntry = function () { this.setData({ bookEntryDetail: null }); };
  page.openBookVoucher = async function () {
    const entry = this.data.bookEntryDetail;
    const alive = () => this.data.projectLedgerReadOnly && !this.__financeHidden && !this.data.ledgerProjectError
      && this.canLoadProjectLedger?.() && this.data.bookEntryDetail === entry
      && (this.data.projectLedgerEntries || []).some(row => row.key === entry?.key && row.voucher_file_id === entry.voucher_file_id);
    if (!entry?.voucher_file_id || !alive()) return;
    try {
      const ticket = await http.post('/franchise/assets/' + encodeURIComponent(entry.voucher_file_id) + '/download-tickets');
      if (!alive()) return;
      const file = await require('../../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '凭据' });
      if (!alive()) return;
      if (/^image\//i.test(file.contentType)) wx.previewImage({ urls: [file.tempFilePath] });
      else wx.openDocument({ filePath: file.tempFilePath, fileType: 'pdf', showMenu: true, fail: () => { if (alive()) wx.showToast({ title: '凭据打开失败', icon: 'none' }); } });
    } catch (error) { if (alive()) wx.showToast({ title: error.message || '凭据加载失败', icon: 'none' }); }
  };
}
module.exports = attach;
module.exports.receiptBody = receiptBody;
