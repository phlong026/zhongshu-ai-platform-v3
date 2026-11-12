/** 设计点资产卡：使用当前 ADMIN 岗位的组织钱包，失败不阻塞「我的」页。 */
const http = require('../../api/http');
const wallet = require('../../api/wallet');
const view = require('../../utils/wallet-view');

Component({
  data: { visible: false, balanceText: '' },
  lifetimes: {
    attached() {
      this._detached = false;
      this.refresh();
    },
    detached() {
      this._detached = true;
    }
  },
  pageLifetimes: {
    show() { this.refresh(); }
  },
  methods: {
    refresh() {
      const seq = this._requestSeq = (this._requestSeq || 0) + 1;
      const fp = wallet.fingerprint();
      const alive = () => !this._detached && seq === this._requestSeq && fp === wallet.fingerprint();
      this.setData({ visible: false, balanceText: '' });
      if (!http.token() || !http.currentPostId()) return;
      wallet.pointAccount(fp).then((account) => {
        if (!alive()) return;
        const visible = view.cardState(!!http.token(), http.currentPostId(), account, false) === 'ready';
        this.setData({ visible, balanceText: visible ? view.formatThousands(account.availablePoints) : '' });
      }).catch((err) => {
        if (!alive() || wallet.isStale(err)) return;
        console.warn('[point-asset-card] 余额查询失败', err && err.code);
        this.setData({ visible: false, balanceText: '' });
      });
    },
    goToRecharge() { wx.navigateTo({ url: '/pages/wallet/recharge/index' }); },
    goToRecords() { wx.navigateTo({ url: '/pages/wallet/records/index' }); }
  }
});
