/**
 * 平台账簿操作层（B2 板块 C / F2-F6）：登记表单校验与提交、到账确认。
 * 纯逻辑注入 page（零 wx 依赖的部分留在 utils/platform-finance.js）。
 */
module.exports = function attach(page, financeApi, PF) {
  page.openRegister = function (e) {
    const type = e.currentTarget.dataset.type;
    if (!['receipt', 'payment', 'refund', 'payable'].includes(type)) return;
    this.registerIdem = null; // M08-T04：新开表单换新键
    this.setData({ registerType: type, registerForm: {}, registerError: '' });
  };
  page.cancelRegister = function () {
    this.registerIdem = null; // M08-T04：放弃表单换新键
    this.setData({ registerType: '', registerError: '', registerBusy: false, revisionForm: null, // busy 一并解除：挂起请求放弃后不阻塞下次提交
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
    financeApi.confirmBank(String(flowId), receipt.id) // M08-T07
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
      body = { payableId: String(form.payableId), amountCents: amount, // M08-T07：雪花串逐字一致（Number 截断 >2^53）
        payDate: form.payDate || undefined, transactionNo: (form.transactionNo || '').trim() || undefined };
      call = financeApi.registerPayment;
    } else if (type === 'refund') {
      if (!form.receiptId) return fail('请选择关联收款');
      const reg = this.data.platformRegister || {};
      const src = (reg.refundable || []).find(r => String(r.id) === String(form.receiptId));
      if (src && amount > src.refundableCents) return fail('超过剩余可退金额，请刷新余额');
      body = { receiptId: String(form.receiptId), amountCents: amount, reason: (form.reason || '').trim(),
        date: form.date || undefined, transactionNo: (form.transactionNo || '').trim() || undefined };
      call = financeApi.registerRefund;
    } else {
      if (!(form.recipient || '').trim()) return fail('请输入收款方');
      body = { stage: (form.stage || '').trim() || undefined, totalCents: amount,
        recipient: form.recipient.trim(), projectId: form.projectId ? String(form.projectId) : undefined, // M08-T07
        agreementRef: (form.agreementRef || '').trim() || undefined };
      call = financeApi.registerPayable;
    }
    this.setData({ registerBusy: true });
    // M08-T04/I04：粘性幂等键——响应丢失（busy 复位但表单未关）后重试复用同键，后端去重只登记一笔；
    // 成功（表单关闭）/cancelRegister/openRegister 换新键
    if (!this.registerIdem) this.registerIdem = financeApi.idemKey(type);
    const idem = this.registerIdem;
    const withVoucher = (form.voucherPath
      ? financeApi.uploadVoucher(form.voucherPath).then(assetId => { body.voucherFileId = assetId; })
      : Promise.resolve());
    withVoucher
      .then(() => call(body, idem))
      .then(() => { wx.showToast({ title: '已登记' }); this.registerIdem = null; this.setData({ registerType: '', registerForm: {} }); this.loadPlatformFinance(); })
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
      originAmount: row.amountText || '', amount: '', note: '' }, registerError: '',
      revisionHistory: { status: 'loading', rows: [] } }); // R2 P2-8：打开即拉取/清空上次
    // R2 P2-8：请求代次防迟到（切单/关闭后旧响应丢弃）
    this.revisionHistGen = (this.revisionHistGen || 0) + 1;
    const gen = this.revisionHistGen;
    financeApi.revisions(rawId).then(rows => {
      if (gen !== this.revisionHistGen || this.data.registerType !== 'revision') return;
      const list = (rows || []).map(h => ({
        versionAfter: (h.baseRevisionVersion || 0) + 1, // 修订后版本=base+1（R2 纠正：历史行存 base）
        originalText: PF.yuan(h.originalAmountCents),
        newText: PF.yuan(h.newAmountCents),
        note: h.note || '',
        dateText: String(h.createTime || '').slice(0, 16).replace('T', ' ')
      }));
      this.setData({ 'revisionHistory.status': list.length ? 'ready' : 'empty', 'revisionHistory.rows': list });
    }).catch(() => {
      if (gen !== this.revisionHistGen || this.data.registerType !== 'revision') return;
      this.setData({ 'revisionHistory.status': 'failed' }); // 失败不禁用提交（D-3）
    });
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
        const raw = err && (err.data || err);
        // R2 P1-4：按错误码 1102000003 识别冲突（弃文案 indexOf）→ 回读当前收款+历史刷新基准与原金额（保留用户输入）
        if (raw && raw.code === 1102000003) {
          financeApi.receipts().then(receipts => {
            const cur = (receipts || []).find(r => String(r.id) === String(form.receiptId));
            if (!cur) return this.setData({ registerError: PF.errorMessage(err) });
            this.setData({
              registerError: '收款已被他人修订，已刷新最新版本与金额，请确认后重试',
              'revisionForm.baseRevisionVersion': cur.revisionVersion,
              'revisionForm.originAmount': PF.yuan(cur.amountCents)
            });
            this.revisionHistGen = (this.revisionHistGen || 0) + 1;
            const gen = this.revisionHistGen;
            financeApi.revisions(form.receiptId).then(rows => {
              if (gen !== this.revisionHistGen) return;
              const list = (rows || []).map(h => ({
                versionAfter: (h.baseRevisionVersion || 0) + 1,
                originalText: PF.yuan(h.originalAmountCents), newText: PF.yuan(h.newAmountCents),
                note: h.note || '', dateText: String(h.createTime || '').slice(0, 16).replace('T', ' ')
              }));
              this.setData({ 'revisionHistory.status': 'ready', 'revisionHistory.rows': list });
            }).catch(() => {});
          }).catch(e2 => this.setData({ registerError: PF.errorMessage(e2) }));
        } else this.setData({ registerError: PF.errorMessage(err) });
      })
      .finally(() => this.setData({ revisionBusy: false }));
  };
};
