/** 公司积分卡：展示当前岗位所属公司的余额和员工本人当月使用量。 */
const http = require('../../api/http');
const points = require('../../api/company-points');

Component({
  data: {
    visible: true,
    state: 'loading',
    balanceText: '--',
    usedText: '--',
    balanceLabel: '可用余额',
    emptyMessage: '公司积分池暂未开通',
    errorMessage: ''
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
    openPackages() { wx.navigateTo({url:'/pages/wallet/packages/index'}); },
    refresh() {
      this.setData({purchaseAvailable:false});
      const loadId = this.__loadId = (this.__loadId || 0) + 1;
      const platformLoggedIn = !!http.token();
      if (!platformLoggedIn) {
        this.setData({ visible: false, hasPost: false });
        return;
      }
      if (!http.currentPostId()) {
        this.setData({
          visible: true,
          hasPost: false,
          state: 'empty',
          balanceText: '--',
          usedText: '--',
          balanceLabel: '可用余额',
          emptyMessage: '请选择工作身份',
          errorMessage: ''
        });
        return;
      }
      this.setData({
        visible: true,
        hasPost: true,
        state: 'loading',
        balanceText: '--',
        usedText: '--',
        balanceLabel: '可用余额',
        emptyMessage: '公司积分池暂未开通',
        errorMessage: ''
      });
      Promise.all([points.summary(), points.myUsage({ page: 1, size: 1 })]).then(([summary, usage]) => {
        if (loadId !== this.__loadId) return;
        if (!summary || summary.balance === undefined || !usage || usage.used === undefined) {
          this.setData({ state: 'empty', balanceText: '--', usedText: '--' });
          return;
        }
        const values = {
          balanceText: points.formatCredits(summary.balance),
          usedText: points.formatCredits(usage.used),
          errorMessage: ''
        };
        if (summary.status === 'ACTIVE') {
          this.setData(Object.assign(values, { state: 'ready', balanceLabel: '可用余额' }));
          if (points.packages) points.packages().then(plans => {
            if (loadId === this.__loadId && plans.length) this.setData({ purchaseAvailable:true });
          }).catch(() => {});
          return;
        }
        if (summary.status === 'SUSPENDED') {
          this.setData(Object.assign(values, { state: 'suspended', balanceLabel: '账户余额' }));
          return;
        }
        const error = new Error('UNKNOWN_COMPANY_POINT_STATUS');
        error.code = 'UNKNOWN_COMPANY_POINT_STATUS';
        throw error;
      }).catch((err) => {
        if (loadId !== this.__loadId) return;
        if (points.isStale(err)) {
          this.refresh();
          return;
        }
        console.warn('[point-asset-card] 公司积分查询失败', err && (err.code || err.message));
        this.setData({
          state: 'error',
          balanceText: '--',
          usedText: '--',
          balanceLabel: '可用余额',
          errorMessage: '积分信息加载失败，请稍后重试'
        });
      });
    },

    openRecharge() {
      if (http.token() && http.currentPostId()) wx.navigateTo({ url: '/pages/wallet/company-recharge/index' });
    },

    retry() {
      this.refresh();
    }
  }
});
