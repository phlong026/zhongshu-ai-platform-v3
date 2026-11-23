// One dispatched payment/refund per account and post. Persist the exact body before sending it.
const pending = new Map();
const kinds = {
  payment: { label: '付款', source: 'payableId', result: 'paymentId', call: 'registerPayment', path: 'payments' },
  refund: { label: '退款', source: 'receiptId', result: 'refundId', call: 'registerRefund', path: 'refunds' },
  'expense-refund': { label: '支出退回', source: 'paymentId', result: 'refundId', call: 'registerExpenseRefund', path: 'expense-refunds' }
};
const kind = entry => kinds[entry.type || 'payment']; // Existing saved payments have no type.

module.exports = function attachRecovery(page, api, http, auth, wx, PF) {
  const scope = () => {
    const post = auth.currentApiPost() || {};
    return ['finance-payment-v1', http.tenantId?.() || '1', post.orgId, post.userId, http.currentPostId()].join(':');
  };
  const current = (key, entry) => scope() === key && pending.get(key) === entry &&
    entry.page === page && !page.__disposed && !page.__financeHidden;
  const remove = key => { wx.removeStorageSync?.(key); pending.delete(key); };
  const display = (key, entry) => {
    if (current(key, entry)) page.setData({ registerBusy: true, paymentPending: true,
      paymentRetry: !entry.flight, paymentNotice: entry.notice || '正在核验原' + kind(entry).label + '登记结果' });
  };
  const confirm = (key, entry, result) => {
    const id = result?.[kind(entry).result];
    if (!id || !current(key, entry)) return false;
    remove(key);
    page.registerIdem = null;
    page.setData({ registerType: '', registerForm: {}, registerBusy: false, paymentPending: false,
      paymentRetry: false, registerError: '', paymentNotice: kind(entry).label + '已登记，单号 ' + id });
    page.financeEntrySaved?.(entry.type || 'payment', entry.form, result);
    page.loadPlatformFinance(true);
    page.loadWorkbench?.();
    page.loadDesignFees?.();
    page.refreshFinanceTasks?.();
    return true;
  };
  const reject = (key, entry) => {
    if (!current(key, entry)) return;
    remove(key);
    page.registerIdem = null;
    page.setData({ registerBusy: false, paymentPending: false, paymentRetry: false,
      registerError: entry.rejection, paymentNotice: entry.rejection });
  };
  const send = (key, entry) => {
    if (entry.flight) return entry.flight;
    entry.notice = kind(entry).label + '请求已发送，正在确认登记结果';
    entry.flight = api[kind(entry).call](entry.body, entry.idem).then(result => {
      entry.result = result;
    }, err => {
      entry.error = err;
      // Financial business errors are terminal for this frozen request. A lookup
      // serialized with registration must still establish whether it committed.
      const code = Number(err.code);
      if ((code >= 1000000000 && code < 2000000000) || code === 400 || code === 422) {
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
      if (!saved?.idem || !kind(saved) || !saved?.body?.[kind(saved).source]) return;
      entry = { ...saved, uncertain: true };
      pending.set(key, entry);
    }
    entry.page = page;
    if (confirm(key, entry, entry.result)) return;
    display(key, entry);
    const check = entry.check = {};
    try {
      const result = await http.get('/franchise/finance/' + kind(entry).path + '/registration-result', { key: entry.idem });
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
    } catch (_) {
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
