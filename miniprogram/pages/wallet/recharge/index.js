/**
 * 设计点充值面板（后端契约：yudao-module-commerce AppRecharge*Controller）。
 *
 * 支付编排（与设计小程序已验证流程同源）：
 * 1. 确认支付 → wx.login 取一次性 code（虚拟支付签名必需，换取即弃）；
 * 2. createOrder(planId, Idempotency-Key, X-WX-Login-Code) → 按 payRoute 分发；
 * 3. wx.requestVirtualPayment（signData 服务端签名原文，原样透传禁止重序列化）；
 * 4. 成功回调只进查单轮询，到账以服务端 fulfillmentState=CREDITED 为准（红线 3：禁止前端加点）；
 * 5. 失败/取消 → 先查单对账（可能实际已支付），未支付则进入「继续支付」恢复流（pay-params 重领，不重复建单）。
 */
const wallet = require('../../../api/wallet');
const view = require('../../../utils/wallet-view');

const POLL_INTERVAL_MS = 2000;
const POLL_MAX_TIMES = 15;

Page({
  data: {
    loadState: 'loading', // loading | ready | unavailable
    balanceText: '',
    plans: [],
    selectedIdx: -1,
    payAmountText: '¥--',
    payPointsText: '--',
    agreement: true,
    paying: false,
    promoText: '',
    resultState: '', // '' | processing | done | failed | slow
    resultText: '',
    resultOrderId: '',
    canContinuePay: false
  },

  onLoad() {
    this._lifecycleToken = 0;
    this._pollTimer = null;
    this._pollLeft = 0;
    this.load();
  },

  onUnload() {
    this._lifecycleToken += 1;
    if (this._pollTimer) clearTimeout(this._pollTimer);
  },

  isCurrentLifecycle(token) {
    return token === this._lifecycleToken;
  },

  load() {
    wallet.ensureSession().then((token) => {
      if (!token) {
        // 桥接未部署/充值未开放：安静降级，不给「我的」页能力报错感
        this.setData({ loadState: 'unavailable' });
        return;
      }
      return Promise.all([wallet.listPlans(), wallet.pointAccount()]).then(([plans, account]) => {
        const planViews = view.plansView(plans || []);
        const pickedIdx = this.pickDefaultIdx(planViews);
        const bar = view.payBar(plans || [], pickedIdx);
        this.setData({
          loadState: 'ready',
          plans: planViews,
          selectedIdx: pickedIdx,
          payAmountText: bar.amountText,
          payPointsText: bar.pointsText,
          balanceText: view.formatThousands(account && account.availablePoints),
          promoText: this.buildPromoText(planViews)
        });
      });
    }).catch((err) => {
      console.warn('[wallet/recharge] 加载失败', err && (err.code || err.message));
      // 钱包会话失效等瞬时错误允许重试；桥接缺失保持 unavailable
      this.setData({ loadState: err && err.code === 'WALLET_UNAVAILABLE' ? 'unavailable' : 'ready-failed' });
      if (!err || err.code !== 'WALLET_UNAVAILABLE') {
        wx.showToast({ title: (err && err.message) || '加载失败，请重试', icon: 'none' });
      }
    });
  },

  /** 默认选中「推荐」，其次「最划算」，都没有则第一个 */
  pickDefaultIdx(planViews) {
    const byRibbon = (name) => planViews.findIndex((p) => p.ribbon === name);
    const idx = byRibbon('推荐') >= 0 ? byRibbon('推荐') : byRibbon('最划算');
    return idx >= 0 ? idx : (planViews.length ? 0 : -1);
  },

  buildPromoText(planViews) {
    const hero = planViews.find((p) => p.ribbon === '推荐') || planViews.find((p) => p.ribbon === '最划算');
    if (!hero || !hero.bonus) return '';
    return '充值专享：充 ' + hero.priceText + ' 到账 ' + hero.pointsText + ' 设计点（' + hero.bonus.label + '）';
  },

  tapPlan(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    const selected = this.data.plans[idx];
    if (!selected) return;
    this.setData({
      selectedIdx: idx,
      payAmountText: selected.priceText,
      payPointsText: selected.pointsText
    });
  },

  toggleAgreement() {
    this.setData({ agreement: !this.data.agreement });
  },

  makeIdemKey() {
    return 'recharge-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
  },

  capability() {
    return {
      virtualPayment: typeof wx.requestVirtualPayment === 'function',
      wechatPayment: typeof wx.requestPayment === 'function'
    };
  },

  confirmPay() {
    if (this.data.paying) return;
    const { agreement, selectedIdx, plans, resultState } = this.data;
    if (resultState === 'processing') return;
    if (!agreement) {
      wx.showToast({ title: '请先阅读并同意充值协议', icon: 'none' });
      return;
    }
    const picked = plans[selectedIdx];
    if (!picked) {
      wx.showToast({ title: '请选择充值套餐', icon: 'none' });
      return;
    }
    const token = this._lifecycleToken;
    this.setData({ paying: true });
    // 支付当刻的 session_key 才能通过签名校验：先 wx.login 换一次性 code 随建单上送
    wx.login({
      success: (res) => {
        if (!this.isCurrentLifecycle(token)) return;
        wallet.createOrder(picked.planId, this.makeIdemKey(), res && res.code).then((order) => {
          if (!this.isCurrentLifecycle(token)) return;
          this.setData({ paying: false, resultOrderId: String(order.orderId) });
          this.dispatchPay(order);
        }).catch((err) => {
          if (!this.isCurrentLifecycle(token)) return;
          this.setData({ paying: false });
          wx.showToast({ title: (err && err.message) || '创建订单失败', icon: 'none' });
        });
      },
      fail: () => {
        if (!this.isCurrentLifecycle(token)) return;
        this.setData({ paying: false });
        wx.showToast({ title: '微信登录失败，请重试', icon: 'none' });
      }
    });
  },

  dispatchPay(order) {
    const route = view.payRoute(order, this.capability());
    const params = order.payParams || {};
    if (route === 'session-code-required') {
      // 建单时缺一次性 code 无法签名：订单已保留，等用户回来经「继续支付」重签
      this.setData({ resultState: 'failed', resultText: '支付签名未就绪，请稍后点击「继续支付」', canContinuePay: true });
      return;
    }
    if (route === 'virtual') {
      wx.requestVirtualPayment({
        signData: params.signData, // 服务端签名原文，必须原样透传
        mode: params.mode,
        paySig: params.paySig,
        signature: params.signature,
        success: () => this.startPolling(String(order.orderId)),
        fail: (res) => this.reconcileAfterPayFail(String(order.orderId), res && res.errMsg)
      });
      return;
    }
    if (route === 'virtual-unsupported') {
      wx.showToast({ title: '当前微信版本不支持该支付方式，请升级后重试', icon: 'none' });
      return;
    }
    if (route === 'wechat') {
      wx.requestPayment({
        ...params,
        success: () => this.startPolling(String(order.orderId)),
        fail: (res) => this.reconcileAfterPayFail(String(order.orderId), res && res.errMsg)
      });
      return;
    }
    // stub/无支付参数：直接进查单轮询（本地开发路径）
    this.startPolling(String(order.orderId));
  },

  startPolling(orderId) {
    this._lifecycleToken += 1; // 作废在建的 wx.login 回调
    const token = this._lifecycleToken;
    this._pollLeft = POLL_MAX_TIMES;
    this.setData({ resultState: 'processing', resultText: '支付成功，等待设计点到账…', resultOrderId: orderId, canContinuePay: false });
    const tick = () => {
      if (!this.isCurrentLifecycle(token)) return;
      wallet.getOrder(orderId).then((order) => {
        if (!this.isCurrentLifecycle(token)) return;
        if (order.fulfillmentState === 'CREDITED') {
          this.setData({ resultState: 'done', resultText: '设计点已到账' });
          this.refreshAccount();
          return;
        }
        if (order.paymentState === 'FAILED' || order.paymentState === 'CLOSED') {
          this.setData({ resultState: 'failed', resultText: '订单未完成支付', canContinuePay: false });
          return;
        }
        this._pollLeft -= 1;
        if (this._pollLeft <= 0) {
          this.setData({ resultState: 'slow', resultText: '到账确认中，可稍后在充值明细中查看' });
          return;
        }
        this._pollTimer = setTimeout(tick, POLL_INTERVAL_MS);
      }).catch((err) => {
        if (!this.isCurrentLifecycle(token)) return;
        this._pollLeft -= 1;
        if (this._pollLeft <= 0) {
          this.setData({ resultState: 'slow', resultText: '到账确认中，可稍后在充值明细中查看' });
        } else {
          console.warn('[wallet/recharge] 查单失败将继续重试', err && err.message);
          this._pollTimer = setTimeout(tick, POLL_INTERVAL_MS);
        }
      });
    };
    tick();
  },

  /** 支付失败/取消：先对账——渠道可能实际已扣款；未支付则提供恢复入口 */
  reconcileAfterPayFail(orderId, errMsg) {
    wallet.getOrder(orderId).then((order) => {
      if (order.fulfillmentState === 'CREDITED' || order.paymentState === 'SUCCEEDED') {
        this.startPolling(orderId); // 实际已支付，继续等赠点到账
        return;
      }
      if (order.paymentState === 'CLOSED' || order.paymentState === 'FAILED') {
        this.setData({ resultState: 'failed', resultText: '订单已关闭，请重新下单', canContinuePay: false });
        return;
      }
      this.setData({
        resultState: 'failed',
        resultText: '支付未完成' + (errMsg ? '（' + errMsg + '）' : ''),
        canContinuePay: true
      });
    }).catch(() => {
      this.setData({ resultState: 'failed', resultText: '支付未完成', canContinuePay: true, resultOrderId: orderId });
    });
  },

  /** T13-24 恢复支付：pay-params 重领签名，禁止重复建单 */
  continuePay() {
    const orderId = this.data.resultOrderId;
    if (!orderId || this.data.paying) return;
    const token = this._lifecycleToken;
    this.setData({ paying: true });
    wx.login({
      success: (res) => {
        if (!this.isCurrentLifecycle(token)) return;
        wallet.getPayParams(orderId, res && res.code).then((params) => {
          if (!this.isCurrentLifecycle(token)) return;
          this.setData({ paying: false });
          this.dispatchPay({ orderId: orderId, payParams: params });
        }).catch((err) => {
          if (!this.isCurrentLifecycle(token)) return;
          this.setData({ paying: false });
          // 409=订单已不可支付（已付/已关），引导查记录
          wx.showToast({ title: (err && err.message) || '订单暂不可支付，请查看充值明细', icon: 'none' });
          this.setData({ canContinuePay: false });
        });
      },
      fail: () => {
        if (!this.isCurrentLifecycle(token)) return;
        this.setData({ paying: false });
        wx.showToast({ title: '微信登录失败，请重试', icon: 'none' });
      }
    });
  },

  refreshAccount() {
    wallet.pointAccount().then((account) => {
      this.setData({ balanceText: view.formatThousands(account && account.availablePoints) });
    }).catch(() => { /* 余额刷新失败不打扰，充值明细页可复核 */ });
  },

  dismissResult() {
    if (this.data.resultState === 'processing') return;
    const finished = this.data.resultState === 'done';
    this.setData({ resultState: '', resultText: '', canContinuePay: false });
    if (finished) wx.navigateBack();
  },

  goToRecords() {
    wx.navigateTo({ url: '/pages/wallet/records/index' });
  },

  /** 结果卡片自身拦截冒泡（空实现，阻止点卡片触发遮罩关闭） */
  noop() {}
});
