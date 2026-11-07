/**
 * 设计点资产卡（「我的」页第 2 位）：品牌渐变卡展示点数余额，去充值/充值明细双入口。
 *
 * 自取数组件：挂载与所在页每次 onShow 时刷新（pageLifetimes.show），充值回来余额即时更新；
 * 平台未登录、钱包会话不可用（桥接未部署/充值未开放）或查询失败时整体隐藏——
 * 资产卡属增量能力，任何异常都不阻塞「我的」页原有功能。
 */
const http = require('../../api/http');
const wallet = require('../../api/wallet');
const view = require('../../utils/wallet-view');

Component({
  data: {
    visible: false,
    balanceText: ''
  },

  lifetimes: {
    attached() {
      this.refresh();
    }
  },

  pageLifetimes: {
    show() {
      this.refresh();
    }
  },

  methods: {
    refresh() {
      const platformLoggedIn = !!(wx.getStorageSync(http.TOKEN_KEY) || '');
      if (!platformLoggedIn) {
        this.setData({ visible: false });
        return;
      }
      wallet.ensureSession().then((token) => {
        const state = view.cardState(platformLoggedIn, token, null, false);
        if (state !== 'ready') {
          this.setData({ visible: false });
          return null;
        }
        return wallet.pointAccount().then((account) => {
          if (view.cardState(platformLoggedIn, token, account, false) !== 'ready') {
            this.setData({ visible: false });
            return;
          }
          this.setData({
            visible: true,
            balanceText: view.formatThousands(account.availablePoints)
          });
        });
      }).catch((err) => {
        // 网络失败等一律静默隐藏；带上下文留 warn 便于排查
        console.warn('[point-asset-card] 余额查询失败', err && (err.code || err.message));
        this.setData({ visible: false });
      });
    },

    goToRecharge() {
      wx.navigateTo({ url: '/pages/wallet/recharge/index' });
    },

    goToRecords() {
      wx.navigateTo({ url: '/pages/wallet/records/index' });
    }
  }
});
