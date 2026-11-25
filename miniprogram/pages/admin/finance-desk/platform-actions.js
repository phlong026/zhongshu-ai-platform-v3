/**
 * 平台账簿操作层（B2 板块 C / F2-F6）：登记表单校验与提交、到账确认。
 * 纯逻辑注入 page（零 wx 依赖的部分留在 utils/platform-finance.js）。
 */
const httpApi = require('../../../api/http');
const authApi = require('../../../api/auth');

module.exports = function attach(page, financeApi, PF) {
  require('./payment-recovery')(page, financeApi, httpApi, authApi, wx, PF);
  const beginBusy = target => {
    if (typeof target.beginFinanceBusy === 'function') return target.beginFinanceBusy();
    if (target.data.busy) return null;
    const token = {}; target.__financeBusyOwner = token; target.setData({ busy: true }); return token;
  };
  const ownsBusy = (target, token) => typeof target.ownsFinanceBusy === 'function'
    ? target.ownsFinanceBusy(token) : !!token && target.__financeBusyOwner === token;
  const finishBusy = (target, token, patch = {}) => {
    if (typeof target.finishFinanceBusy === 'function') return target.finishFinanceBusy(token, patch);
    if (!ownsBusy(target, token)) return false;
    target.__financeBusyOwner = null; target.setData({ busy: false, ...patch }); return true;
  };
  page.openRegister = function (e) {
    if (this.data.registerBusy || this.data.wbBusy || this.data.busy) return;
    const type = e.currentTarget.dataset.type;
    if (!['receipt', 'payment', 'refund', 'expense-refund', 'payable'].includes(type)) return;
    this.registerIdem = null; // M08-T04：新开表单换新键
    const form = {payDate:require('../../../utils/account').localDate(),receivedDate:require('../../../utils/account').localDate()};
    // M08-T05/R04：退款/支出退回均预填真实今日（日期必填——picker 显示实际值，用户可改；不冒充已填）
    if (type === 'expense-refund' || type === 'refund') form.date = require('../../../utils/account').localDate();
    this.wbGeneration = (this.wbGeneration || 0) + 1;
    this.setData({ wbForm: null, wbError: '', bookEntryDetail:null, registerType: type, registerForm: form, registerError: '' });
  };
  page.cancelRegister = function () {
    if (this.data.paymentPending) return;
    this.registerIdem = null; // M08-T04：放弃表单换新键
    this._registerSeq = (this._registerSeq || 0) + 1; // R3 P2-1：取消使挂起中的旧登记回调过期（不清新表单/不复位新操作）
    this._correctionCtx = null; // M08-T08 批次#34：放弃更正即清上下文
    this.setData({ registerType: '', registerError: '', registerBusy: false, revisionForm: null, // busy 一并解除：挂起请求放弃后不阻塞下次提交
      policyKey: null, policyKeyCtx: null, reconcileKey: null, reconcileKeyCtx: null });
  };
  page.registerInput = function (e) {
    const field = e.currentTarget.dataset.field;
    const value = field === 'method' ? ['bank', 'cash', 'other'][Number(e.detail.value)] : field === 'refundFundingType' ? [this.data.registerForm?.originalRefundFundingType,'COMPANY','EMPLOYEE'][Number(e.detail.value)] : field === 'fundingType' ? ['COMPANY','EMPLOYEE'][Number(e.detail.value)] : e.detail.value;
    this.setData({ ['registerForm.' + field]: value });
  };
  page.registerPick = function (e) {
    const field = e.currentTarget.dataset.field;
    const idx = Number(e.detail.value);
    if(field==='payerUserId'){const row=(this.data.workbenchStaff||[])[idx];if(row)this.setData({'registerForm.payerUserId':row.id,'registerForm.payerName':row.name});return;}
    const reg = this.data.platformRegister || {};
    const options = field === 'payableId' ? (reg.payables || [])
      : field === 'paymentId' ? (reg.expenseRefundable || [])
      : (reg.refundable || []);
    const choice = options[idx];
    if (!choice) return;
    this.setData({ ['registerForm.' + field]: choice.id, ['registerForm.' + field + 'Label']: choice.label,...(field==='payableId'?{'registerForm.allowEmployee':['design','manual'].includes(choice.sourceType),'registerForm.fundingType':'COMPANY'}:{}) });
  };
  page.registerUpload = function () {
    if (this.data.registerBusy || !this.data.registerType) return;
    const session = httpApi.sessionGuard(), generation = this.wbGeneration, sequence = this._registerSeq;
    const selection = this.__voucherSelection = (this.__voucherSelection || 0) + 1;
    const context = () => { const f = this.data.registerForm || {}; return JSON.stringify([this.data.registerType,f.payableId,f.receiptId,f.paymentId,f.scope]); };
    const original = context();
    const alive = () => session() && !this.__disposed && generation === this.wbGeneration && sequence === this._registerSeq && selection === this.__voucherSelection && context() === original;
    return require('../../../utils/finance-voucher').selectVoucher({ alive }).then(file => {
      if (file && alive()) this.setData({ 'registerForm.voucherPath': file.path, 'registerForm.voucherName': file.name, registerError: '' });
    }).catch(error => { if (alive()) this.setData({ registerError: error.message }); });
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
    const post = authApi.currentApiPost() || {};
    const scope = {
      session: httpApi.sessionGeneration(),
      orgId: String(post.orgId || ''),
      postId: String(post.postId || '')
    };
    const context = [scope.session, scope.orgId, scope.postId, String(flowId), String(receipt.id)].join('|');
    if (this.bankConfirmIdemCtx !== context) {
      this.bankConfirmIdem = financeApi.idemKey('confirm');
      this.bankConfirmIdemCtx = context;
    }
    const idem = this.bankConfirmIdem;
    const stillSameScope = () => {
      const current = authApi.currentApiPost() || {};
      return scope.session === httpApi.sessionGeneration()
        && scope.orgId === String(current.orgId || '')
        && scope.postId === String(current.postId || '');
    };
    const busyToken = beginBusy(this); if (!busyToken) return;
    financeApi.confirmBank(String(flowId), receipt.id, idem) // 同一业务上下文失败重试复用同键
      .then(() => {
        if (!ownsBusy(this, busyToken)) return;
        if (this.bankConfirmIdemCtx === context) { this.bankConfirmIdem = null; this.bankConfirmIdemCtx = null; }
        if (!stillSameScope()) return;
        wx.showToast({ title: '已确认入账' }); this.loadPlatformFinance(true);
      })
      .catch(err => { if (ownsBusy(this, busyToken) && stillSameScope()) wx.showToast({ title: PF.errorMessage(err), icon: 'none' }); })
      .finally(() => finishBusy(this, busyToken));
  };
  page.submitRegister = function (snapshot) {
    // R1 P2-1：更正作废挂起中（busy）同样不许发起新登记——双向互斥防表单互相覆盖
    if (this.data.registerBusy || this.data.busy) return;
    const type = snapshot?.form ? snapshot.type : this.data.registerType;
    const recoverable = ['payment', 'refund', 'expense-refund'].includes(type);
    const form = snapshot?.form || { ...this.data.registerForm };
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
        accountName:form.accountName,fundingType: form.fundingType || 'COMPANY', payerUserId: form.payerUserId, payerName: form.payerName, payDate: form.payDate || undefined, transactionNo: (form.transactionNo || '').trim() || undefined };
      call = financeApi.registerPayment;
    } else if (type === 'refund') {
      if (!form.receiptId) return fail('请选择关联收款');
      const reg = this.data.platformRegister || {};
      const src = (reg.refundable || []).find(r => String(r.id) === String(form.receiptId));
      if (src && amount > src.refundableCents) return fail('超过剩余可退金额，请刷新余额');
      // M08-T08 批次#34：更正链重登携 replacesId（CAS 替代原记录；雪花串传输）
      body = { receiptId: String(form.receiptId), amountCents: amount, reason: (form.reason || '').trim(),
        date: form.date || undefined, transactionNo: (form.transactionNo || '').trim() || undefined,
        replacesId: form.replacesId ? String(form.replacesId) : undefined };
      call = financeApi.registerRefund;
    } else if (type === 'expense-refund') {
      // M08-T08：支出退回——供应商/劳务退回，冲减已付现金（上限=该付款单剩余可退回额度，后端行锁内复核）
      if (!form.paymentId) return fail('请选择关联付款单');
      const reg = this.data.platformRegister || {};
      const src = (reg.expenseRefundable || []).find(r => String(r.id) === String(form.paymentId));
      if (src && amount > src.refundableCents) return fail('超过该付款单剩余可退回金额，请刷新余额');
      // R2 P1：日期兜底真实今日（picker 预填后仍防清空提交）；更正链携 replacesId
      body = { fundingType:form.refundFundingType, paymentId: String(form.paymentId), amountCents: amount, reason: (form.reason || '').trim(),
        date: form.date || require('../../../utils/account').localDate(),
        transactionNo: (form.transactionNo || '').trim() || undefined,
        replacesId: form.replacesId ? String(form.replacesId) : undefined };
      call = financeApi.registerExpenseRefund;
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
    const seq = this._registerSeq = (this._registerSeq || 0) + 1; // R1 P2-1：代次戳——旧登记回调不得覆盖后续更正表单
    const sessionAlive = httpApi.sessionGuard(), post = httpApi.currentPostId(), generation = this.wbGeneration;
    const alive = () => sessionAlive() && httpApi.currentPostId() === post && !this.__disposed &&
      seq === this._registerSeq && generation === this.wbGeneration;
    const withVoucher = (form.voucherPath
      ? financeApi.uploadVoucher(form.voucherPath, form.voucherName).then(assetId => { body.voucherFileId = assetId; })
      : Promise.resolve());
    return withVoucher
      .then(() => {
        if (!alive()) return;
        if (recoverable) return this.dispatchPreparedPayment(body, idem, form, type);
        return call(body, idem);
      })
      .then(result => {
        if (recoverable || !alive()) return; // 恢复模块持有原请求；身份/代次过期不得清理新表单
        if(this.financeEntrySaved)this.financeEntrySaved(type,form,result);
        wx.showToast({ title: type === 'payment' ? '付款登记完成' : ['refund', 'expense-refund'].includes(type) ? '退款登记完成' : '已登记' }); this.registerIdem = null; this.setData({ registerType: '', registerForm: {} }); this.loadPlatformFinance(true);if(this.loadWorkbench)this.loadWorkbench();
        if (this.loadDesignFees) this.loadDesignFees();
        if(this.refreshFinanceTasks)this.refreshFinanceTasks();
      })
      .catch(err => { if (alive()) this.setData({ registerError: PF.errorMessage(err) }); })
      .finally(() => { if (alive() && !this.data.paymentPending) this.setData({ registerBusy: false }); });
  };

  // ===================== M08-T08 批次#34：更正链双端（作废→重登携 replacesId 走 CAS 替代） =====================
  const correctionReason = '更正重登';
  const pendingCorrection = row => row && row.status === '已作废' &&
    row.voidReason === correctionReason && row.replacedById == null &&
    row.id != null && (row.refundDirection === 'expense' ? row.paymentId != null : row.receiptId != null);
  page.pendingRefundCorrections = (rows, role) => role === 'finance-lead' ? (rows || []).filter(pendingCorrection)
    .map(row => ({ id: String(row.id), label: row.refundDirection === 'expense' ? '支出退回' : '客户退款',
      amount: PF.yuan(row.amountCents), date: require('../../../utils/finance-date').day(row.date) || '日期待补' })) : [];
  const correctionGuard = target => {
    const session = httpApi.sessionGuard(), post = httpApi.currentPostId(), generation = target.wbGeneration;
    return () => session() && post === httpApi.currentPostId() && generation === target.wbGeneration &&
      !target.__disposed && !target.__financeHidden && target.data.refundVoidable === true;
  };
  const startCorrection = (target, token, row) => {
    if (target.data.journeyEnabled && target.rememberEntryOrigin) target.rememberEntryOrigin();
    const isExpense = row.refundDirection === 'expense', type = isExpense ? 'expense-refund' : 'refund';
    const sourceId = isExpense ? row.paymentId : row.receiptId;
    const source = (target.__journeyCash || []).find(r => r.sourceKind === (isExpense ? 'payment' : 'receipt') && String(r.id) === String(sourceId));
    const sourceLabel = source ? source.projectName + ' · ' + source.name : isExpense ? '原付款' : '原收款';
    const date = require('../../../utils/finance-date').day(row.date) || require('../../../utils/account').localDate();
    const originalRefundFundingType = ['COMPANY', 'EMPLOYEE'].includes(row.fundingType) ? row.fundingType : undefined;
    const form = { amount: row.amountCents ? (row.amountCents / 100).toFixed(2) : '',
      reason: row.reason || '', date, transactionNo: row.transactionNo || '', replacesId: row.id,
      ...(isExpense ? { paymentId: sourceId, paymentIdLabel: sourceLabel,
        refundFundingType: originalRefundFundingType, originalRefundFundingType } : { receiptId: sourceId, receiptIdLabel: sourceLabel }) };
    target.registerIdem = null;
    target._registerSeq = (target._registerSeq || 0) + 1;
    const route = target.data.view === 'desk' ? { view: 'flows', title: '资金流水', flowTab: 'ledger' } : {};
    if (!finishBusy(target, token, { ...route, registerType: type, registerForm: form, registerError: '',
      bookEntryDetail: null, journeyResult: null, wbForm: null })) return;
    if (target.syncJourneyForm) target.syncJourneyForm();
    target.loadPlatformFinance(true);
  };
  const canEnterCorrection = target => target.data.refundVoidable === true &&
    !target.data.busy && !target.data.registerBusy && !target.data.wbBusy && !target.data.paymentPending;
  page.openCorrection = function (e) {
    if (!canEnterCorrection(this)) return;
    const refundId = String(e.currentTarget.dataset.id || '').replace(/^f-/, '');
    const row = (this._refundRows || []).find(r => String(r.id) === refundId);
    if (!row || row.status !== '已核实') return this.setData({ registerError: '仅已核实的退款可更正重登' });
    const alive = correctionGuard(this);
    wx.showModal({
      title: row.refundDirection === 'expense' ? '作废并重登支出退回' : '作废并重登退款',
      content: '原记录 ¥' + PF.yuan(row.amountCents) + ' 将作废留痕。若中途退出，可从「退款待重登」继续。',
      success: res => {
        if (!res.confirm || !alive() || !canEnterCorrection(this)) return;
        const token = beginBusy(this); if (!token) return;
        financeApi.voidRefund(refundId, correctionReason, financeApi.idemKey('refund-void'))
          .then(() => alive() && ownsBusy(this, token) ? startCorrection(this, token, row) : finishBusy(this, token))
          .catch(err => {
            if (!alive()) return finishBusy(this, token);
            if (finishBusy(this, token, { ledgerError: PF.errorMessage(err) })) this.loadPlatformFinance(true);
          });
      }
    });
  };
  page.resumeRefundCorrection = function (e) {
    if (!canEnterCorrection(this) || this.data.registerType || this.data.wbForm) return;
    const refundId = String(e.currentTarget.dataset.id || '');
    if (!refundId || !(this._refundRows || []).some(row => String(row.id) === refundId && pendingCorrection(row))) return;
    const alive = correctionGuard(this), token = beginBusy(this);
    if (!token) return;
    return financeApi.refunds().then(rows => {
      if (!alive() || !ownsBusy(this, token)) return finishBusy(this, token);
      const row = (rows || []).find(r => String(r.id) === refundId);
      if (pendingCorrection(row)) return startCorrection(this, token, row);
      if (finishBusy(this, token)) {
        wx.showToast({ title: '这笔不再待重登', icon: 'none' });
        this.loadPlatformFinance(true);
      }
    }).catch(err => {
      if (alive() && finishBusy(this, token)) wx.showToast({ title: PF.errorMessage(err), icon: 'none' });
      else finishBusy(this, token);
    });
  };

  // ===================== P1 批6：付款单撤销（DELETE /franchise/finance/payments/{id}，1_102_000_012/037） =====================
  page.openPaymentVoid = function (e) {
    // 与挂起中的登记/更正互斥：旧回调会清表单，避免「已撤销但上下文被覆盖」
    if (this.data.busy || this.data.registerBusy) return;
    const rowId = String(e.currentTarget.dataset.id || '');
    const paymentId = rowId.replace(/^p-/, '');
    if (!paymentId) return;
    const row = (this.data.platformLedger || []).find(r => String(r.id) === rowId)
      || (this.ledgerSource || []).find(r => String(r.id) === rowId);
    // canVoid 由 index.js 按当前岗位 roleCode（仅 finance-lead 持 franchise:fin:payment-void）+ 行来源/状态预计算
    if (!row || row.canVoid !== true) {
      return this.setData({ ledgerError: '仅财务负责人可撤销已核实付款，请刷新列表后重试' });
    }
    const amountText = row.amountCents ? (row.amountCents / 100).toFixed(2) : '';
    wx.showModal({
      title: '撤销付款',
      content: '该笔付款' + (amountText ? ' ¥' + amountText : '') + '将作废留痕，对应应付未付余额恢复；请填写撤销原因。',
      editable: true,
      placeholderText: '撤销原因（必填）',
      success: res => {
        if (!res.confirm) return;
        const reason = String(res.content || '').trim();
        const reasonError = PF.voidReasonError(reason);
        if (reasonError) return this.setData({ ledgerError: reasonError });
        const busyToken = beginBusy(this); if (!busyToken) return;
        this.setData({ ledgerError: '', voidNotice: '' });
        // P2-3：发出时冻结岗位/会话/单据；回调写 UI/刷新前复核，换岗或会话变化后静默丢弃
        const seq = this._voidSeq = (this._voidSeq || 0) + 1;
        const context = {
          postKey: String((authApi.currentApiPost() || {}).postId || ''),
          session: httpApi.sessionGeneration(),
          paymentId
        };
        const stillSame = () => context.session === httpApi.sessionGeneration()
          && context.postKey === String((authApi.currentApiPost() || {}).postId || '');
        const drop = () => {
          if (seq !== this._voidSeq) return; // 已有更新的撤销接管，不动 busy
          finishBusy(this, busyToken);
        };
        financeApi.voidPayment(paymentId, reason, financeApi.idemKey('payment-void'))
          .then(() => {
            if (!stillSame()) return drop();
            // 作废释放额度：台账行已消失，联动提示说明应付余额恢复（刷新后不再出现该笔）
            if (!finishBusy(this, busyToken, { voidNotice: PF.voidNoticeText(row),bookEntryDetail:null })) return;
            wx.showToast({ title: '付款已撤销', icon: 'none' });
            this.loadPlatformFinance(true);
            if(this.loadWorkbench)this.loadWorkbench();
            if(this.data.view==='book'&&this.loadBookLedger)this.loadBookLedger();
          })
          .catch(err => {
            if (!stillSame()) return drop();
            finishBusy(this, busyToken, { ledgerError: PF.errorMessage(err) });
          });
      }
    });
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
      .then(() => { this.setData({ registerType: '', revisionForm: null, revisionKey: null }); this.loadPlatformFinance(true); wx.showToast({ title: '已修订留痕' }); })
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
