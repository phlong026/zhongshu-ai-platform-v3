/** 组织充值订单 / 点数流水，各自分页、各自错误态；所有结果只属于发起时的身份。 */
const wallet = require('../../../api/wallet');
const view = require('../../../utils/wallet-view');
const PAGE_SIZE = 20;

Page({
  data: {
    tab: 'orders', loadState: 'ready',
    orders: [], ordersTotal: 0, ordersPageNo: 0, ordersLoading: false, ordersError: '',
    ledger: [], ledgerTotal: 0, ledgerPageNo: 0, ledgerLoading: false, ledgerError: ''
  },
  onLoad() {
    this._unloaded = false;
    this._seq = { orders: 0, ledger: 0 };
    this.reload();
  },
  onShow() {
    if (this._fp !== wallet.fingerprint()) this.reload();
  },
  onUnload() { this._unloaded = true; },
  reload() {
    this._fp = wallet.fingerprint();
    this.setData({ orders: [], ordersTotal: 0, ordersPageNo: 0, ledger: [], ledgerTotal: 0, ledgerPageNo: 0 });
    this.loadOrders(true);
    this.loadLedger(true);
  },
  onReachBottom() {
    if (this.data.tab === 'orders') this.loadOrders(false);
    else this.loadLedger(false);
  },
  switchTab(e) { this.setData({ tab: e.currentTarget.dataset.tab }); },
  retryCurrent() { this.onReachBottom(); },
  loadOrders(reset) { return this.loadList('orders', reset); },
  loadLedger(reset) { return this.loadList('ledger', reset); },
  loadList(kind, reset) {
    const data = this.data;
    if (!reset && (data[kind + 'Loading'] || (data[kind + 'PageNo'] > 0 && data[kind].length >= data[kind + 'Total']))) return;
    const seq = ++this._seq[kind];
    const fp = this._fp;
    const alive = () => !this._unloaded && seq === this._seq[kind] && fp === wallet.fingerprint();
    const nextNo = reset ? 1 : data[kind + 'PageNo'] + 1;
    const patch = { [kind + 'Loading']: true, [kind + 'Error']: '' };
    if (reset) Object.assign(patch, { [kind]: [], [kind + 'PageNo']: 0, [kind + 'Total']: 0 });
    this.setData(patch);
    const request = kind === 'orders' ? wallet.listOrders : wallet.pointLedger;
    return request(nextNo, PAGE_SIZE, fp).then((page) => {
      if (!alive()) return;
      const items = page.list.map(kind === 'orders' ? view.orderView : view.ledgerView);
      this.setData({
        [kind]: reset ? items : this.data[kind].concat(items),
        [kind + 'Total']: page.total,
        [kind + 'PageNo']: nextNo,
        [kind + 'Loading']: false
      });
    }).catch((err) => {
      if (!alive() || wallet.isStale(err)) return;
      console.warn('[wallet/records] 列表加载失败', kind, nextNo, err && err.code);
      this.setData({ [kind + 'Loading']: false, [kind + 'Error']: wallet.errorMessage(err) });
    });
  },
  goRecharge() { wx.navigateTo({ url: '/pages/wallet/recharge/index' }); }
});
