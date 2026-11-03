/**
 * 平台账簿操作层（B2 板块 C / F2-F6）：登记表单校验与提交、到账确认。
 * 纯逻辑注入 page（零 wx 依赖的部分留在 utils/platform-finance.js）。
 */
module.exports = function attach(page, financeApi, PF) {
  page.openRegister = function (e) {
    const type = e.currentTarget.dataset.type;
    if (!['receipt', 'payment', 'refund', 'payable'].includes(type)) return;
    this.setData({ registerType: type, registerForm: {}, registerError: '' });
  };
  page.cancelRegister = function () {
    this.setData({ registerType: '', registerError: '', revisionForm: null,
      policyKey: null, policyKeyCtx: null, reconcileKey: null, reconcileKeyCtx: null });
  };
  page.registerInput = function (e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ ['registerForm.' + field]: e.detail.value });
  };
  page.registerPick = function (e) {
    const field = e.currentTarget.dataset.field;
    const idx = Number(e.detail.value);
    const reg = this.data.platformRegister || {};
    const options = field === 'payableId' ? (reg.payables || []) : (reg.refundable || []);
    const choice = options[idx];
    if (!choice) return;
    this.setData({ ['registerForm.' + field]: choice.id, ['registerForm.' + field + 'Label']: choice.label });
  };
  page.registerUpload = function () {
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'png', 'jpg', 'jpeg'],
      success: result => {
        const f = result.tempFiles[0];
        if (f.size > 20 * 1024 * 1024) return this.setData({ registerError: '凭证超过 20MB' });
        wx.saveFile({ tempFilePath: f.path,
          success: saved => this.setData({ 'registerForm.voucherPath': saved.savedFilePath,
            'registerForm.voucherName': f.name, registerError: '' }),
          fail: () => this.setData({ registerError: '凭证保存失败，请重试' }) });
      } });
  };
  page.bankPick = function (e) {
    const flowId = e.currentTarget.dataset.flowId;
    this.setData({ ['bankPick' + flowId]: Number(e.detail.value) });
  };
  page.confirmBankSubmitInner = function (e) {
    const flowId = e.currentTarget.dataset.flowId;
    if (!flowId || this.data.busy) return;
    const reg = this.data.platformRegister || {};
    const options = reg.pendingReceipts || [];
    const pick = this.data['bankPick' + flowId];
    const receipt = options[pick === undefined ? -1 : pick];
    if (!receipt) {
      wx.showToast({ title: '请先选择对应待登记应收', icon: 'none' });
      return;
    }
    this.setData({ busy: true });
    financeApi.confirmBank(Number(flowId), receipt.id)
      .then(() => { wx.showToast({ title: '已确认入账' }); this.loadPlatformFinance(); })
      .catch(err => { wx.showToast({ title: PF.errorMessage(err), icon: 'none' }); })
      .finally(() => this.setData({ busy: false }));
  };
  page.submitRegister = function () {
    if (this.data.registerBusy) return;
    const type = this.data.registerType;
    const form = this.data.registerForm || {};
    const fail = msg => this.setData({ registerError: msg });
    const amount = PF.toCents(form.amount);
    if (!amount) return fail('请输入正确的金额（最多两位小数）');
    let body; let call;
    if (type === 'receipt') {
      if (!form.name) return fail('请输入款项名称');
      if ((form.method || 'bank') === 'bank' && !(form.transactionNo || '').trim()) return fail('银行收款须填流水号');
      body = { sourceType: 'manual', name: form.name.trim(), amountCents: amount,
        method: form.method || 'bank', transactionNo: (form.transactionNo || '').trim(),
        receivedDate: form.receivedDate || undefined };
      call = financeApi.registerReceipt;
    } else if (type === 'payment') {
      if (!form.payableId) return fail('请选择关联应付');
      body = { payableId: Number(form.payableId), amountCents: amount,
        payDate: form.payDate || undefined, transactionNo: (form.transactionNo || '').trim() || undefined };
      call = financeApi.registerPayment;
    } else if (type === 'refund') {
      if (!form.receiptId) return fail('请选择关联收款');
      const reg = this.data.platformRegister || {};
      const src = (reg.refundable || []).find(r => String(r.id) === String(form.receiptId));
      if (src && amount > src.refundableCents) return fail('超过剩余可退金额，请刷新余额');
      body = { receiptId: Number(form.receiptId), amountCents: amount, reason: (form.reason || '').trim(),
        date: form.date || undefined, transactionNo: (form.transactionNo || '').trim() || undefined };
      call = financeApi.registerRefund;
    } else {
      if (!(form.recipient || '').trim()) return fail('请输入收款方');
      body = { stage: (form.stage || '').trim() || undefined, totalCents: amount,
        recipient: form.recipient.trim(), projectId: form.projectId ? Number(form.projectId) : undefined,
        agreementRef: (form.agreementRef || '').trim() || undefined };
      call = financeApi.registerPayable;
    }
    this.setData({ registerBusy: true });
    const idem = financeApi.idemKey(type);
    const withVoucher = (form.voucherPath
      ? financeApi.uploadVoucher(form.voucherPath).then(assetId => { body.voucherFileId = assetId; })
      : Promise.resolve());
    withVoucher
      .then(() => call(body, idem))
      .then(() => { wx.showToast({ title: '已登记' }); this.setData({ registerType: '', registerForm: {} }); this.loadPlatformFinance(); })
      .catch(err => { this.setData({ registerError: PF.errorMessage(err) }); })
      .finally(() => this.setData({ registerBusy: false }));
  };

  // ===================== M5 F2：收款修订（business 来源后端拒绝/退款下限后端拒绝；失败保留输入） =====================
  page.openRevision = function (e) {
    const rawId = String(e.currentTarget.dataset.id || '').replace(/^r-/, ''); // ledger 行 id=key（r-123）→ receiptId
    const row = (this.data.platformLedger || []).find(r => String(r.id) === String(e.currentTarget.dataset.id))
      || (this.ledgerSource || []).find(r => String(r.id) === String(e.currentTarget.dataset.id));
    if (!rawId || !row || row.category !== '收款') return this.setData({ registerError: '仅收款可修订' });
    this.setData({ registerType: 'revision', revisionForm: { receiptId: rawId, baseRevisionVersion: row.revisionVersion || 1,
      originAmount: row.amountText || '', amount: '', note: '' }, registerError: '' });
  };
  page.revisionInput = function (e) {
    this.setData({ ['revisionForm.' + e.currentTarget.dataset.field]: e.detail.value });
  };
  page.submitRevision = function () {
    if (this.data.revisionBusy) return;
    const form = this.data.revisionForm || {};
    const amountCents = PF.toCents(form.amount);
    if (!amountCents) return this.setData({ registerError: '请输入正确的新金额' });
    if (!String(form.note || '').trim()) return this.setData({ registerError: '请填写修订说明（留痕）' });
    this.setData({ revisionBusy: true });
    financeApi.reviseReceipt(form.receiptId, form.baseRevisionVersion, amountCents, form.note)
      .then(() => { this.setData({ registerType: '', revisionForm: null, revisionKey: null }); this.loadPlatformFinance(); wx.showToast({ title: '已修订留痕' }); })
      .catch(err => {
        const msg = PF.errorMessage(err);
        if (msg.indexOf('版本') >= 0 && form.baseRevisionVersion != null) {
          // 冲突后刷新版本重填（D-3 UX）
          this.setData({ registerError: msg + '（已刷新版本，请重试）',
            'revisionForm.baseRevisionVersion': Number(form.baseRevisionVersion) + 1 });
        } else this.setData({ registerError: msg });
      })
      .finally(() => this.setData({ revisionBusy: false }));
  };
};
