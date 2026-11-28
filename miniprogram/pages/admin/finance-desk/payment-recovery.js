// One dispatched payment/refund per account and post. Persist the exact body before sending it.
const pending = new Map();
const kinds = {
  payment: { label: '付款', source: 'payableId', result: 'paymentId', call: 'registerPayment', path: 'payments' },
  'payroll-payment': { label: '薪酬付款', source: 'payrollRecordId', result: 'id' },
  'workbench-entry': { label: '收支', result: 'id', path: 'workbench/entries' },
  'stage-collection': { label: '合同收款', source: 'stageId', result: 'receiptId' },
  'receipt-correction': { label: '收款更正', source: 'receiptId', result: 'receiptId', call: 'correctContractReceipt' },
  refund: { label: '退款', source: 'receiptId', result: 'refundId', call: 'registerRefund', path: 'refunds' },
  'expense-refund': { label: '支出退回', source: 'paymentId', result: 'refundId', call: 'registerExpenseRefund', path: 'expense-refunds' }
};
const kind = entry => kinds[entry.type || 'payment']; // Existing saved payments have no type.
const payroll = entry => entry.type === 'payroll-payment';
const workbench = entry => entry.type === 'workbench-entry';
const stageCollection = entry => entry.type === 'stage-collection';
const workbenchForm = entry => payroll(entry) || workbench(entry) || stageCollection(entry);
const validSource = entry => workbench(entry)
  ? ['receipt', 'expense', 'payable'].includes(entry.body?.type) && /^(company|(project|book):[0-9]+)$/.test(entry.body?.scope || '')
  : !!entry.body?.[kind(entry).source];
const path = entry => entry.type === 'receipt-correction' ? '/franchise/sales/receipts/' + encodeURIComponent(entry.body.receiptId) + '/correction' :
  stageCollection(entry) ? '/franchise/sales/stages/' + encodeURIComponent(entry.body.stageId) + '/collect' : payroll(entry) ? '/franchise/finance/payroll/records/' + encodeURIComponent(entry.body.payrollRecordId) + '/payments' :
  '/franchise/finance/' + kind(entry).path;

module.exports = function attachRecovery(page, api, http, auth, wx, PF) {
  const scope = () => {
    const post = auth.currentApiPost() || {};
    return ['finance-payment-v1', http.tenantId?.() || '1', post.orgId, post.userId, http.currentPostId()].join(':');
  };
  const current = (key, entry) => scope() === key && pending.get(key) === entry &&
    entry.page === page && !page.__disposed && !page.__financeHidden;
  const clearRevokedPayroll = (key, entry, error) => {
    if (payroll(entry) && current(key, entry) && (error?.postInvalid || [401, 403, 404].includes(Number(error?.code)))) {
      page.setData({ wbForm: null, journeyResult: null });
    }
  };
  const remove = key => { wx.removeStorageSync?.(key); pending.delete(key); };
  const display = (key, entry) => {
    if (current(key, entry)) page.setData({ registerBusy: true, paymentPending: true,
      paymentRetry: !entry.flight, paymentNotice: entry.notice || '正在核验原' + kind(entry).label + '登记结果' });
  };
  const confirm = (key, entry, result) => {
    const id = result?.[kind(entry).result];
    if (!id || !current(key, entry)) return false;
    if (payroll(entry) && (String(result.payrollRecordId) !== entry.body.payrollRecordId || !require('../payroll/model').id(id))) return false;
    remove(key);
    page.registerIdem = null;
    page.setData({ registerType: '', registerForm: {}, registerBusy: false, paymentPending: false,
      paymentRetry: false, registerError: '', paymentNotice: kind(entry).label + '已登记，单号 ' + id });
    if (workbenchForm(entry)) page.setData({ wbForm: null, wbError: '' });
    if (entry.type === 'receipt-correction') page.setData({ revisionForm: null, revisionBusy: false });
    page.financeEntrySaved?.(workbench(entry) ? entry.body.type : stageCollection(entry) ? 'receipt' : payroll(entry) ? 'expense' : entry.type || 'payment', entry.form, result);
    page.loadPlatformFinance(true);
    page.loadWorkbench?.();
    page.loadDesignFees?.();
    page.refreshFinanceTasks?.();
    if ((workbench(entry) || stageCollection(entry) || entry.type === 'receipt-correction') && page.data.view === 'book') page.loadBookLedger?.();
    return true;
  };
  const reject = (key, entry) => {
    if (!current(key, entry)) return;
    remove(key);
    page.registerIdem = null;
    page.setData({ ...(workbenchForm(entry) ? { wbError: entry.rejection } : {}), registerBusy: false, paymentPending: false, paymentRetry: false,
      registerError: entry.rejection, paymentNotice: entry.rejection });
  };
  const send = (key, entry) => {
    if (entry.flight) return entry.flight;
    entry.notice = kind(entry).label + '请求已发送，正在确认登记结果';
    const { payrollRecordId, stageId, ...payment } = entry.body;
    const dispatch = payroll(entry) || stageCollection(entry) ? http.post(path(entry), payment, { 'Idempotency-Key': entry.idem })
      : workbench(entry) ? http.post(path(entry), entry.body, { 'Idempotency-Key': entry.idem }) : api[kind(entry).call](entry.body, entry.idem);
    entry.flight = dispatch.then(result => {
      entry.result = result;
    }, err => {
      entry.error = err;
      clearRevokedPayroll(key, entry, err);
      // Financial business errors are terminal for this frozen request. A lookup
      // serialized with registration must still establish whether it committed.
      const code = Number(err.code);
      if ((code >= 1000000000 && code < 2000000000) || code === 400 || code === 422 || payroll(entry) && code === 409) {
        entry.rejection = PF.errorMessage(err);
        wx.setStorageSync?.(key, { type: entry.type, body: entry.body, idem: entry.idem,
          form: entry.form, rejection: entry.rejection });
      }
    }).finally(() => {
      entry.flight = null;
      if (entry.rejection && pending.get(key) === entry && !entry.page?.__disposed && !entry.page?.__financeHidden) {
        return entry.page?.resumePayment();
      }
      entry.page?.showPaymentState(key, entry);
    });
    display(key, entry);
    return entry.flight;
  };
  page.showPaymentState = (key, entry) => {
    if (!current(key, entry) || confirm(key, entry, entry.result)) return;
    if (entry.error) {
      entry.uncertain = true;
      entry.notice = kind(entry).label + '结果待确认，请核验原单';
    }
    display(key, entry);
  };
  page.resumePayment = async function (retry = false) {
    const key = scope();
    let entry = pending.get(key);
    if (!entry) {
      const saved = wx.getStorageSync?.(key);
      if (!saved?.idem || !kind(saved) || !validSource(saved)) return;
      entry = { ...saved, uncertain: true };
      pending.set(key, entry);
    }
    entry.page = page;
    if (confirm(key, entry, entry.result)) return;
    display(key, entry);
    const check = entry.check = {};
    try {
      const result = await http.get(path(entry) + '/registration-result', { key: entry.idem });
      if (!current(key, entry) || entry.check !== check || confirm(key, entry, result)) return;
      if (entry.rejection && result?.state === 'NOT_REGISTERED' && !entry.flight) return reject(key, entry);
      if (entry.rejection) {
        entry.notice = kind(entry).label + '被拒绝，仍需核验原单';
        return display(key, entry);
      }
      if (retry === true && !entry.flight) {
        entry.uncertain = true;
        entry.error = null;
        return send(key, entry);
      }
      entry.notice = entry.flight ? kind(entry).label + '请求处理中，正在等待原单回执' : '尚未查到原单，可用原凭据和原请求重试';
    } catch (error) {
      clearRevokedPayroll(key, entry, error);
      entry.notice = '未能查询' + kind(entry).label + '结果，请重试核验';
    }
    display(key, entry);
  };
  page.retryPendingPayment = () => page.resumePayment(true);
  page.dispatchPreparedPayment = (body, idem, form, type = 'payment') => {
    const key = scope();
    if (pending.has(key) || wx.getStorageSync?.(key)?.idem) return page.resumePayment();
    const { amount, name, payableIdLabel, receiptIdLabel, paymentIdLabel, reimbursement, fundingType, scope: book, scopeLabel } = form;
    const saved = { type, body: JSON.parse(JSON.stringify(body)), idem,
      form: { amount, name, payableIdLabel, receiptIdLabel, paymentIdLabel, reimbursement, fundingType, scope: book, scopeLabel } };
    wx.setStorageSync?.(key, saved);
    const entry = { ...saved, page };
    pending.set(key, entry);
    return send(key, entry);
  };
};
