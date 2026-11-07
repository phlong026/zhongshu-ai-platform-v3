/**
 * 充值明细页：充值订单 / 点数流水 双 Tab，各自分页加载。
 * 订单状态文案来自服务端 paymentState/fulfillmentState（勿用渠道回调推断）。
 */
const wallet = require('../../../api/wallet');
const view = require('../../../utils/wallet-view');

const PAGE_SIZE = 20;

Page({
  data: {
    tab: 'orders', // orders | ledger
    loadState: 'loading', // loading | ready | unavailable
    orders: [],
    ordersTotal: 0,
    ledger: [],
    ledgerTotal: 0,
    ordersPageNo: 0,
    ledgerPageNo: 0,
    loadingMore: false
  },

  onLoad() {
    this._lifecycleToken = 0;
    wallet.ensureSession().then((token) => {
      if (!token) {
        this.setData({ loadState: 'unavailable' });
        return;
      }
      this.setData({ loadState: 'ready' });
      this.loadOrders(true);
      this.loadLedger(true);
    }).catch(() => {
      this.setData({ loadState: 'unavailable' });
    });
  },

  onUnload() {
    this._lifecycleToken += 1;
  },

  onReachBottom() {
    if (this.data.tab === 'orders') this.loadOrders(false);
    else this.loadLedger(false);
  },

  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab });
  },

  loadOrders(reset) {
    const token = this._lifecycleToken;
    const nextNo = reset ? 1 : this.data.ordersPageNo + 1;
    if (!reset && (this.data.loadingMore || this.data.orders.length >= this.data.ordersTotal)) return;
    this.setData({ loadingMore: true });
    wallet.listOrders(nextNo, PAGE_SIZE).then((page) => {
      if (token !== this._lifecycleToken) return;
      const items = (page && page.list ? page.list : []).map(view.orderView);
      this.setData({
        orders: reset ? items : this.data.orders.concat(items),
        ordersTotal: (page && page.total) || 0,
        ordersPageNo: nextNo,
        loadingMore: false
      });
    }).catch(() => {
      if (token !== this._lifecycleToken) return;
      this.setData({ loadingMore: false });
    });
  },

  loadLedger(reset) {
    const token = this._lifecycleToken;
    const nextNo = reset ? 1 : this.data.ledgerPageNo + 1;
    if (!reset && (this.data.loadingMore || this.data.ledger.length >= this.data.ledgerTotal)) return;
    this.setData({ loadingMore: true });
    wallet.pointLedger(nextNo, PAGE_SIZE).then((page) => {
      if (token !== this._lifecycleToken) return;
      const items = (page && page.list ? page.list : []).map(view.ledgerView);
      this.setData({
        ledger: reset ? items : this.data.ledger.concat(items),
        ledgerTotal: (page && page.total) || 0,
        ledgerPageNo: nextNo,
        loadingMore: false
      });
    }).catch(() => {
      if (token !== this._lifecycleToken) return;
      this.setData({ loadingMore: false });
    });
  },

  goRecharge() {
    wx.navigateTo({ url: '/pages/wallet/recharge/index' });
  }
});
