const http = require('../../../api/http');
const Money = require('../../../utils/money');
const today = () => require('../../../utils/account').localDate();
const yuan = cents => ((Number(cents) || 0) / 100).toFixed(2);

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
    const alive = http.requestGuard('finance-source-rows');
    this.setData({ platformNotice: '', error: '' });
    try {
      const source = await (tab === 'payables' ? api.payables() : api.receipts('待登记'));
      if (!alive() || this.__disposed || this.data.flowTab !== tab) return;
      const keyword = String(this.data.keyword || '').trim().toLowerCase();
      const rows = source.filter(row => !keyword || [row.id, row.name, row.projectId, row.sourceRef].join(' ').toLowerCase().includes(keyword)).map(row => ({
        id: String(row.id), name: row.name || ('原单 ' + row.id), status: row.status,
        amountText: '¥' + yuan(tab === 'payables' ? row.totalCents - row.paidCents : row.amountCents), subtitle: row.sourceRef || '',
        dateText: row.dueDate || row.receivedDate || '日期待核', detail: tab === 'payables' ? '累计应付 ¥' + yuan(row.totalCents) + ' · 已付 ¥' + yuan(row.paidCents) : '待登记原单',
        payableId: tab === 'payables' ? String(row.id) : '', canPay: tab === 'payables' && row.status === '进行中' && row.totalCents > row.paidCents }));
      this.setData({ rows, ready: true });
    } catch (e) { if (alive() && !this.__disposed) this.setData({ platformNotice: e.message || e.msg || '原单列表加载失败' }); }
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
    const alive = http.requestGuard('design-fees');
    const pageNo = append ? (this.designFeePage || 1) + 1 : 1;
    this.setData({ designFeeStatus: 'loading', designFeeError: '' });
    try {
      const result = await api.designFees(pageNo, 20);
      if (!alive() || this.__disposed) return;
      this.designFeePage = pageNo;
      const rows = result.list.map(row => ({ ...row, key: row.commissionId + ':' + row.submissionVersion + ':' + row.stage,
        expectedText: yuan(row.expectedCents), paidText: yuan(row.storePaidNetCents), receivedText: yuan(row.hqReceivedNetCents), exceptionText: row.financialException ? '财务异常：下发后发生退款/退回，当前净额不足' : '',
        canPay: row.allowedActions.includes('PAYMENT_REGISTER'),
        payments: row.payments.map(p => ({ ...p, receivableText: yuan(p.receivableCents), canReceive: row.allowedActions.includes('RECEIPT_REGISTER') && p.receivableCents > 0 })) }));
      this.setData({ designFeeStatus: 'ready', designFeeRows: append ? (this.data.designFeeRows || []).concat(rows) : rows, designFeeTotal: result.total });
    } catch (e) { if (alive() && !this.__disposed) this.setData({ designFeeStatus: 'error', designFeeError: e.message || e.msg || '设计费用加载失败' }); }
  };
  page.moreDesignFees = function () { if ((this.data.designFeeRows || []).length < this.data.designFeeTotal) this.loadDesignFees(true); };
  page.openDesignPayment = function (e) {
    const row = (this.data.designFeeRows || []).find(r => r.key === e.currentTarget.dataset.key);
    if (!row || !row.canPay) return;
    this.openRegister({ currentTarget: { dataset: { type: 'payment' } } });
    this.setData({ view: 'flows', flowTab: 'ledger', 'registerForm.payableId': String(row.payableId),
      'registerForm.payableIdLabel': '委托 ' + row.commissionId + ' V' + row.submissionVersion + ' ' + row.stage,
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
    const ctx = this.__designReceipt, alive = http.sessionGuard();
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'png', 'jpg', 'jpeg'], success: r => {
      if (!alive() || ctx !== this.__designReceipt || this.__disposed) return;
      const file = r.tempFiles[0];
      if (file.size > 20 * 1024 * 1024) return this.setData({ designReceiptError: '凭证不能超过20MB' });
      this.setData({ 'designReceiptForm.voucherPath': file.path, 'designReceiptForm.voucherName': file.name });
    } });
  };
  page.submitDesignReceipt = async function () {
    if (this.data.designReceiptBusy || !this.__designReceipt) return;
    const ctx = this.__designReceipt, session = http.sessionGuard();
    const alive = () => session() && ctx === this.__designReceipt && !this.__disposed;
    const form = { ...this.data.designReceiptForm };
    this.setData({ designReceiptBusy: true, designReceiptError: '' });
    try {
      const body = receiptBody(ctx.row, ctx.payment, form);
      if (form.voucherPath) body.voucherFileId = await api.uploadVoucher(form.voucherPath);
      if (!alive()) return;
      await api.registerReceipt(body, ctx.idem);
      if (!alive()) return;
      this.cancelDesignReceipt(); this.loadPlatformFinance(); this.loadDesignFees();
      wx.showToast({ title: '设计款已核收', icon: 'success' });
    } catch (e) { if (alive()) this.setData({ designReceiptBusy: false, designReceiptError: e.message || e.msg || '核收失败，请重试' }); }
  };
  page.loadProjectLedger = async function (append = false) {
    if (!this.id) return this.setData({ ledgerProjectError: '缺少工程项目编号' });
    const id = String(this.id), alive = http.requestGuard('project-ledger');
    const pageNo = append ? (this.projectLedgerPage || 1) + 1 : 1;
    this.setData({ ledgerProjectError: '', ledgerProjectLoading: true });
    this.setData({ id });
    try {
      const result = await api.projectLedger(id, pageNo, 20);
      if (!alive() || this.__disposed || String(this.id) !== id) return;
      this.projectLedgerPage = pageNo;
      const entries = result.entries.list.map(r => ({ ...r, key: r.type + ':' + r.id, amountText: yuan(r.amountCents) }));
      this.setData({ ledgerProjectLoading: false, projectLedgerSummary: Object.keys(result.summary).map(key => ({ key, label: { receivableCents: '合同应收', receivedCents: '实收', refundedCents: '退款', paidCents: '实付', expenseRefundedCents: '支出退回', payableCents: '应付' }[key] || key, value: yuan(result.summary[key]) })), projectLedgerEntries: append ? (this.data.projectLedgerEntries || []).concat(entries) : entries, projectLedgerTotal: result.entries.total });
    } catch (e) { if (alive() && !this.__disposed) this.setData({ ledgerProjectLoading: false, ledgerProjectError: e.message || e.msg || '项目账加载失败' }); }
  };
  page.moreProjectLedger = function () { if ((this.data.projectLedgerEntries || []).length < this.data.projectLedgerTotal) this.loadProjectLedger(true); };
}
module.exports = attach;
module.exports.receiptBody = receiptBody;
