/** 公司积分卡：展示当前岗位所属公司的余额和员工本人当月使用量。 */
const http = require('../../api/http');
const points = require('../../api/company-points');
const denied = err => err && (err.postInvalid || [401,403].includes(Number(err.code)));
const initial = () => ({ state: 'loading', balanceText: '--', usedText: '--', balanceLabel: '可用余额',
  emptyMessage: '公司积分池暂未开通', errorMessage: '', purchaseAvailable: false, refreshing: false });

Component({
  data: { visible: true, ...initial() },
  lifetimes: {
    attached() { this.__hidden = false; return this.refresh(); },
    detached() { this.stop(); }
  },
  pageLifetimes: {
    show() { this.__hidden = false; return this.refresh(); },
    hide() { this.stop(); }
  },
  methods: {
    stop() { this.__hidden = true; this.__loadId = (this.__loadId || 0) + 1; this.__reading = null; },
    openPackages() { wx.navigateTo({url:'/pages/wallet/packages/index'}); },
    refresh() {
      if (this.__hidden) return;
      const key = points.fingerprint(), sameScope = key === this.__pointKey;
      if (sameScope && this.__reading) return this.__reading;
      this.__pointKey = key;
      this.__reading = null;
      const loadId = this.__loadId = (this.__loadId || 0) + 1;
      if (!http.token() || !http.currentPostId()) {
        this.setData({ ...initial(), visible: !!http.token(), hasPost: false, state: 'empty', emptyMessage: '请选择工作身份' });
        return;
      }
      this.setData({ ...(sameScope ? {} : initial()), visible: true, hasPost: true, refreshing: true, errorMessage: '' });
      const alive = () => {
        if (this.__hidden || loadId !== this.__loadId) return false;
        if (key !== points.fingerprint()) { this.__reading = null; this.refresh(); return false; }
        return true;
      };
      // A return from another page must not join a read started before its operation.
      return this.__reading = Promise.all([points.summary({ fresh: true }), points.myUsage({ page: 1, size: 1, fresh: true })]).then(([summary, usage]) => {
        if (!alive()) return;
        if (!summary || summary.balance === undefined || !usage || usage.used === undefined) {
          this.setData({ ...initial(), state: 'empty' });
          return;
        }
        const values = { balanceText: points.formatCredits(summary.balance), usedText: points.formatCredits(usage.used), errorMessage: '' };
        if (summary.status === 'ACTIVE') {
          this.setData({ ...values, state: 'ready', balanceLabel: '可用余额' });
          if (points.packages) points.packages().then(plans => {
            if (alive()) this.setData({ purchaseAvailable: !!plans.length });
          }).catch(err => { if (alive() && denied(err)) this.setData({ ...initial(), state: 'error', errorMessage: '积分信息加载失败，请稍后重试' }); });
          return;
        }
        if (summary.status === 'SUSPENDED') {
          this.setData({ ...values, state: 'suspended', balanceLabel: '账户余额', purchaseAvailable: false });
          return;
        }
        throw { code: 'UNKNOWN_COMPANY_POINT_STATUS' };
      }).catch(err => {
        if (!alive() || points.isStale(err)) return;
        const retain = ['ready','suspended'].includes(this.data.state) && !denied(err) &&
          !['UNKNOWN_COMPANY_POINT_STATUS','INVALID_CREDIT_VALUE'].includes(err && err.code);
        this.setData({ ...(retain ? {} : { ...initial(), state: 'error' }), errorMessage: '积分信息加载失败，请稍后重试' });
      }).finally(() => {
        if (loadId === this.__loadId) { this.__reading = null; this.setData({ refreshing: false }); }
      });
    },
    openRecharge() {
      if (http.token() && http.currentPostId()) wx.navigateTo({ url: '/pages/wallet/company-recharge/index' });
    },
    retry() { return this.refresh(); }
  }
});
