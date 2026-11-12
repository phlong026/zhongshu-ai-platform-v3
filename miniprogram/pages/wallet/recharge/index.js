/** B 组织钱包充值。建单键持久保存；每次支付尝试独立取 code，到账只认服务端 creditState。 */
const http = require('../../../api/http');
const wallet = require('../../../api/wallet');
const view = require('../../../utils/wallet-view');
const POLL_INTERVAL_MS = 2000;
const POLL_MAX_TIMES = 15;

Page({
  data: {
    loadState: 'loading', errorText: '', balanceText: '', plans: [], selectedIdx: -1,
    payAmountText: '¥--', payPointsText: '--', agreement: true, paying: false, promoText: '',
    resultState: '', resultText: '', resultOrderId: '', canContinuePay: false, hasPendingOrder: false
  },
  onLoad() {
    this._unloaded = false;
    this._loadSeq = 0;
    this._paySeq = 0;
    this._pollSeq = 0;
    this.load();
  },
  onShow() {
    if (this._fp !== wallet.fingerprint()) this.load();
  },
  onUnload() {
    this._unloaded = true;
    this.stopPolling();
  },
  stopPolling() {
    this._pollSeq += 1;
    if (this._pollTimer) clearTimeout(this._pollTimer);
  },
  payIntentAlive(seq) {
    return !this._unloaded && this._payFp === wallet.fingerprint() && (seq === undefined || seq === this._paySeq);
  },
  load() {
    const fp = wallet.fingerprint();
    if (fp !== this._fp) {
      this.stopPolling();
      this._paySeq += 1;
      this._payFp = null;
      this._fp = fp;
      this._intent = null;
      this._intentError = '';
      // postId 是任职资源身份；只存订单恢复信息，不存 token、code 或签名。
      this._intentKey = 'zs-wallet-intent-v2:' + http.tenantId() + ':' + http.currentPostId();
      try {
        const saved = wx.getStorageSync(this._intentKey);
        if (saved) {
          if (typeof saved !== 'object' || !saved.planId || !saved.idempotencyKey) throw new Error('invalid intent');
          this._intent = saved;
        }
      } catch (err) {
        console.warn('[wallet/recharge] 读取原订单恢复信息失败');
        this._intentError = '无法读取原订单，请先查看充值记录，暂不创建新订单';
      }
      this.setData({ paying: false, resultState: '', resultText: '', canContinuePay: false,
        resultOrderId: this._intent && this._intent.orderId || '', hasPendingOrder: !!this._intent });
    }
    const seq = ++this._loadSeq;
    const alive = () => !this._unloaded && seq === this._loadSeq && fp === wallet.fingerprint();
    this.setData({ loadState: 'loading', errorText: '', balanceText: '', plans: [], selectedIdx: -1 });
    const intent = this._intent;
    const originalOrder = intent && intent.orderId ? wallet.getOrder(intent.orderId, fp) : Promise.resolve(null);
    return Promise.all([wallet.listPlans(fp), wallet.pointAccount(fp), originalOrder]).then(([plans, account, order]) => {
      if (!alive()) return;
      const planViews = view.plansView(plans);
      const pickedIdx = intent ? planViews.findIndex((plan) => plan.planId === intent.planId) : this.pickDefaultIdx(planViews);
      // 原单可能使用旧报价；未知建单结果不借用当前套餐价格，查到原单才显示其快照。
      const bar = order ? view.orderView(order) : intent ? { amountText: '待确认', pointsText: '待确认' } : view.payBar(plans, pickedIdx);
      this.setData({ loadState: 'ready', plans: planViews, selectedIdx: pickedIdx,
        payAmountText: bar.amountText, payPointsText: bar.pointsText,
        balanceText: view.formatThousands(account.availablePoints), promoText: this.buildPromoText(planViews) });
    }).catch((err) => {
      if (!alive() || wallet.isStale(err)) return;
      console.warn('[wallet/recharge] 加载失败', err && err.code);
      this.setData({ loadState: 'failed', errorText: wallet.errorMessage(err) });
    });
  },
  pickDefaultIdx(plans) {
    const recommended = plans.findIndex((p) => p.ribbon === '推荐');
    const best = plans.findIndex((p) => p.ribbon === '最划算');
    return recommended >= 0 ? recommended : best >= 0 ? best : plans.length ? 0 : -1;
  },
  buildPromoText(plans) {
    const hero = plans.find((p) => p.ribbon === '推荐') || plans.find((p) => p.ribbon === '最划算');
    return hero && hero.bonus ? '充值专享：充 ' + hero.priceText + ' 到账 ' + hero.pointsText + ' 设计点（' + hero.bonus.label + '）' : '';
  },
  tapPlan(e) {
    if (this.data.paying || this._intent) return;
    const idx = Number(e.currentTarget.dataset.idx);
    const selected = this.data.plans[idx];
    if (selected) this.setData({ selectedIdx: idx, payAmountText: selected.priceText, payPointsText: selected.pointsText });
  },
  toggleAgreement() { this.setData({ agreement: !this.data.agreement }); },
  makeIdemKey(kind) { return kind + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12); },
  saveIntent(intent) {
    // 写入失败必须阻止建单，否则超时后会丢失幂等键。
    try {
      wx.setStorageSync(this._intentKey, intent);
      this._intent = intent;
      this.setData({ hasPendingOrder: true });
    } catch (err) {
      console.warn('[wallet/recharge] 保存原订单恢复信息失败');
      throw { code: 'WALLET_STORAGE', message: '无法保存原订单信息，请稍后重试' };
    }
  },
  clearIntent() {
    try {
      wx.removeStorageSync(this._intentKey);
      this._intent = null;
      this.setData({ hasPendingOrder: false });
    } catch (err) {
      console.warn('[wallet/recharge] 清理已结束订单恢复信息失败');
      // 保留原订单，下次先查其终态，不冒险重建。
    }
  },
  confirmPay() {
    if (this._unloaded || this._fp !== wallet.fingerprint() || this.data.paying || this.data.resultState === 'processing') return;
    if (this._intentError) return wx.showToast({ title: this._intentError, icon: 'none' });
    if (!this.data.agreement) return wx.showToast({ title: '请先阅读并同意充值协议', icon: 'none' });
    if (this._intent && this._intent.orderId) return this.continuePay();
    const picked = this.data.plans[this.data.selectedIdx];
    if (!this._intent && !picked) return wx.showToast({ title: '请选择充值套餐', icon: 'none' });
    this._payFp = this._fp;
    const seq = ++this._paySeq;
    this.setData({ paying: true });
    try {
      if (!this._intent) this.saveIntent({ planId: picked.planId, idempotencyKey: this.makeIdemKey('order') });
    } catch (err) {
      this.paymentError(err, seq);
      return;
    }
    return wallet.createOrder(this._intent.planId, this._intent.idempotencyKey, this._payFp).catch((err) => {
      // B 的 006 专指套餐不可用且未落单；通用参数错误、超时不能证明原单不存在。
      if (this.payIntentAlive(seq) && err && err.code === 1120000006) this.clearIntent();
      throw err;
    }).then((order) => {
      if (!this.payIntentAlive(seq)) return;
      this.saveIntent({ ...this._intent, orderId: order.id });
      this.setData({ resultOrderId: order.id });
      if (!this.showFinalOrder(order)) this.requestPaymentParams(order.id, seq);
    }).catch((err) => this.paymentError(err, seq));
  },
  paymentError(err, seq) {
    if (!this.payIntentAlive(seq) || wallet.isStale(err)) return;
    console.warn('[wallet/recharge] 支付步骤未完成', err && err.code);
    this.setData({ paying: false, resultState: 'failed', resultText: wallet.errorMessage(err),
      canContinuePay: !!(this._intent && this._intent.orderId) });
  },
  requestPaymentParams(orderId, seq) {
    if (!this.payIntentAlive(seq)) return;
    if (typeof wx.login !== 'function') return this.paymentError({ message: '微信登录不可用，请在微信内重试' }, seq);
    const attemptKey = this.makeIdemKey('attempt');
    wx.login({
      timeout: 15000,
      success: (res) => {
        if (!this.payIntentAlive(seq)) return;
        if (!res || !res.code) return this.paymentError({ message: '微信登录未返回有效凭据，请重试' }, seq);
        wallet.getPayParams(orderId, res.code, attemptKey, this._payFp).then((params) => {
          if (!this.payIntentAlive(seq)) return;
          this.dispatchPay({ id: orderId, payParams: params }, seq);
        }).catch((err) => this.paymentError(err, seq));
      },
      fail: () => this.paymentError({ message: '微信登录失败，请重试' }, seq)
    });
  },
  dispatchPay(order, seq) {
    if (!this.payIntentAlive(seq)) return;
    const route = view.payRoute(order, { virtualPayment: typeof wx.requestVirtualPayment === 'function' });
    if (route !== 'virtual') {
      return this.paymentError({ message: route === 'virtual-unsupported' ? '当前微信版本不支持该支付方式，请升级后重试' : '支付参数不可用，请稍后查询原订单' }, seq);
    }
    const params = order.payParams;
    let settled = false;
    const accept = () => {
      if (settled || !this.payIntentAlive(seq)) return false;
      settled = true;
      return true;
    };
    try {
      wx.requestVirtualPayment({
        signData: params.signData, mode: params.mode, paySig: params.paySig, signature: params.signature,
        success: () => { if (accept()) this.startPolling(order.id); },
        fail: () => { if (accept()) this.reconcileAfterPayFail(order.id, seq); }
      });
    } catch (err) {
      if (accept()) this.reconcileAfterPayFail(order.id, seq);
    }
  },
  /** 返回 true 表示已处理终态/冻结/待入账，不能再签发支付参数。 */
  showFinalOrder(order) {
    const snapshot = view.orderView(order);
    this.setData({ payAmountText: snapshot.amountText, payPointsText: snapshot.pointsText });
    if (order.creditState === 'BLOCKED') {
      this.setData({ paying: false, resultState: 'failed', resultText: '入账已冻结，请联系管理员', canContinuePay: false });
      return true;
    }
    if (order.creditState === 'CREDITED') {
      this.clearIntent();
      this.setData({ paying: false, resultState: 'done', resultText: '设计点已到账', canContinuePay: false });
      this.refreshAccount();
      return true;
    }
    if (order.status === 'FAILED' || order.status === 'CLOSED') {
      this.clearIntent();
      this.setData({ paying: false, resultState: 'failed', resultText: view.orderStatusText(order), canContinuePay: false });
      return true;
    }
    if (order.status === 'SUCCEEDED') {
      this.startPolling(order.id);
      return true;
    }
    if (!['CREATED', 'PENDING', 'UNKNOWN'].includes(order.status)) {
      this.setData({ paying: false, resultState: 'slow', resultText: '订单状态待确认，请查看充值明细', canContinuePay: false });
      return true;
    }
    return false;
  },
  continuePay() {
    if (this.data.paying || this._fp !== wallet.fingerprint() || this._unloaded || !this._intent || !this._intent.orderId) return;
    this._payFp = this._fp;
    const seq = ++this._paySeq;
    const orderId = this._intent.orderId;
    this.stopPolling();
    this.setData({ paying: true });
    // 用户重试先查原单。查询失败只展示错误，绝不创建替代订单。
    return wallet.getOrder(orderId, this._payFp).then((order) => {
      if (!this.payIntentAlive(seq)) return;
      if (!this.showFinalOrder(order)) this.requestPaymentParams(orderId, seq);
    }).catch((err) => this.paymentError(err, seq));
  },
  reconcileAfterPayFail(orderId, seq) {
    return wallet.getOrder(orderId, this._payFp).then((order) => {
      if (!this.payIntentAlive(seq)) return;
      if (this.showFinalOrder(order)) return;
      this.setData({ paying: false, resultState: 'failed', resultText: '支付尚未确认，可继续处理原订单', canContinuePay: true });
    }).catch((err) => this.paymentError(err, seq));
  },
  startPolling(orderId) {
    if (!this.payIntentAlive()) return;
    this.stopPolling();
    const pollSeq = this._pollSeq;
    let remaining = POLL_MAX_TIMES;
    const alive = () => this.payIntentAlive() && pollSeq === this._pollSeq;
    this.setData({ paying: false, resultState: 'processing', resultText: '到账确认中，请稍候…', resultOrderId: orderId, canContinuePay: false });
    const next = () => {
      remaining -= 1;
      if (remaining <= 0) {
        this.setData({ resultState: 'slow', resultText: '结果仍在确认，可稍后查看原订单', canContinuePay: true });
      } else this._pollTimer = setTimeout(tick, POLL_INTERVAL_MS);
    };
    const tick = () => {
      if (!alive()) return;
      wallet.getOrder(orderId, this._payFp).then((order) => {
        if (!alive()) return;
        if (order.creditState === 'CREDITED' || order.creditState === 'BLOCKED' || order.status === 'FAILED' || order.status === 'CLOSED') {
          this.showFinalOrder(order);
          return;
        }
        this.setData({ resultText: order.status === 'SUCCEEDED' ? '支付已确认，待入账' : '支付结果确认中，请稍候…' });
        next();
      }).catch((err) => {
        if (!alive() || wallet.isStale(err)) return;
        console.warn('[wallet/recharge] 查单失败', err && err.code);
        this.setData({ resultState: 'slow', resultText: wallet.errorMessage(err), canContinuePay: true });
      });
    };
    tick();
  },
  refreshAccount() {
    const fp = this._fp;
    return wallet.pointAccount(fp).then((account) => {
      if (!this._unloaded && fp === wallet.fingerprint()) this.setData({ balanceText: view.formatThousands(account.availablePoints) });
    }).catch((err) => {
      if (!this._unloaded && fp === wallet.fingerprint() && !wallet.isStale(err)) console.warn('[wallet/recharge] 余额刷新失败', err && err.code);
    });
  },
  dismissResult() {
    if (this.data.resultState === 'processing' || this.data.paying) return;
    const finished = this.data.resultState === 'done';
    this.setData({ resultState: '', resultText: '', canContinuePay: false });
    if (finished) wx.navigateBack();
  },
  goToRecords() { wx.navigateTo({ url: '/pages/wallet/records/index' }); },
  noop() {}
});
