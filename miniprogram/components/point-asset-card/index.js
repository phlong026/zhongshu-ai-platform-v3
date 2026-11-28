/** 公司积分卡：展示当前岗位所属公司的余额和员工本人当月使用量。 */
const http = require('../../api/http');
const points = require('../../api/company-points');
const denied = err => err && (err.postInvalid || [401,403].includes(Number(err.code)));
const sessionInvalid = err => err && (err.postInvalid || Number(err.code) === 401);
const loadError = '积分信息加载失败，请稍后重试';
const initial = () => ({ state: 'loading', balanceText: '--', usedText: '--', balanceLabel: '可用余额',
  emptyMessage: '公司积分池暂未开通', errorMessage: '', balanceError: false, usageError: false,
  purchaseAvailable: false, hasRechargeOrder: false, rechargeOrderError: '', refreshing: false });

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
    refresh() {
      if (this.__hidden) return;
      const key = points.fingerprint(), sameScope = key === this.__pointKey;
      if (sameScope && this.__reading) return this.__reading;
      this.__pointKey = key;
      this.__reading = null;
      const loadId = this.__loadId = (this.__loadId || 0) + 1;
      if (!http.token() || !http.currentPostId()) {
        this.setData({ ...initial(), visible: !!http.token(), state: 'empty', emptyMessage: '请选择工作身份' });
        return;
      }
      this.setData({ ...(sameScope ? {} : initial()), visible: true, refreshing: true, errorMessage: '' });
      let blocked = false;
      let balanceAllowsPurchase = sameScope && this.data.purchaseAvailable;
      let catalogAllowsPurchase = balanceAllowsPurchase;
      const purchaseAvailable = () => !!(balanceAllowsPurchase && catalogAllowsPurchase);
      const alive = () => {
        if (blocked || this.__hidden || loadId !== this.__loadId) return false;
        if (key !== points.fingerprint()) { this.__reading = null; this.refresh(); return false; }
        return true;
      };
      const update = patch => {
        const next = { ...this.data, ...patch };
        this.setData({ ...patch, errorMessage: next.balanceError || next.usageError ? loadError : '' });
      };
      const invalidate = () => {
        // A revoked session must also block the other metric's still-pending response.
        blocked = true;
        this.__reading = null;
        this.setData({ ...initial(), state: 'error', errorMessage: loadError });
      };
      const failRead = (metric, err) => {
        if (!alive() || points.isStale(err)) return;
        if (denied(err)) { invalidate(); return; }
        const invalidValue = ['UNKNOWN_COMPANY_POINT_STATUS','INVALID_CREDIT_VALUE'].includes(err && err.code);
        const patch = { [metric + 'Error']: true };
        if (metric === 'balance') {
          patch.state = !invalidValue && ['ready','suspended'].includes(this.data.state) ? this.data.state : 'error';
          balanceAllowsPurchase = false;
          patch.purchaseAvailable = false;
          if (invalidValue) patch.balanceText = '--';
        } else if (invalidValue) patch.usedText = '--';
        update(patch);
      };
      // A return from another page must not join a read started before its operation.
      const balanceRead = points.summary({ fresh: true }).then(summary => {
        if (!alive()) return;
        if (summary === null) {
          balanceAllowsPurchase = false;
          update({ state: 'empty', balanceText: '--', balanceError: false, purchaseAvailable: false });
          return;
        }
        if (!summary || summary.balance === undefined) throw { code: 'INVALID_CREDIT_VALUE' };
        const balanceText = points.formatCredits(summary.balance);
        const active = summary.status === 'ACTIVE';
        if (!active && summary.status !== 'SUSPENDED') throw { code: 'UNKNOWN_COMPANY_POINT_STATUS' };
        balanceAllowsPurchase = active;
        update({ balanceText, balanceError: false, state: active ? 'ready' : 'suspended',
          balanceLabel: active ? '可用余额' : '账户余额', purchaseAvailable: purchaseAvailable() });
      }).catch(err => failRead('balance', err));
      // Existing orders are readable even when the balance summary or point pool is unavailable.
      const catalogRead = points.rechargeCatalog().then(catalog => {
        if (!alive()) return;
        if (!catalog || !Array.isArray(catalog.plans)) throw Error('Invalid recharge catalog');
        catalogAllowsPurchase = catalog.enabled === true && catalog.plans.length > 0;
        this.setData({ purchaseAvailable: purchaseAvailable(), hasRechargeOrder: !!catalog.currentOrder?.id,
          rechargeOrderError: '' });
      }).catch(err => {
        if (!alive() || points.isStale(err)) return;
        if (sessionInvalid(err)) { invalidate(); return; }
        catalogAllowsPurchase = false;
        const forbidden = Number(err?.code) === 403, unavailable = Number(err?.code) === 404;
        this.setData({ purchaseAvailable: false, ...(forbidden || unavailable ? { hasRechargeOrder: false } : {}),
          rechargeOrderError: forbidden ? '' : unavailable ? '当前无法查看充值订单，重试' : '充值订单加载失败，重试' });
      });
      const usageRead = points.myUsage({ page: 1, size: 1, fresh: true }).then(usage => {
        if (!alive()) return;
        if (!usage || usage.used === undefined) throw { code: 'INVALID_CREDIT_VALUE' };
        update({ usedText: points.formatCredits(usage.used), usageError: false });
      }).catch(err => failRead('usage', err));
      return this.__reading = Promise.all([balanceRead, usageRead, catalogRead]).finally(() => {
        if (loadId === this.__loadId) { this.__reading = null; this.setData({ refreshing: false }); }
      });
    },
    openRecharge() {
      if (this.__pointKey === points.fingerprint() && (this.data.purchaseAvailable || this.data.hasRechargeOrder) && http.token() && http.currentPostId()) {
        wx.navigateTo({ url: '/pages/wallet/company-recharge/index' + (this.data.purchaseAvailable ? '' : '?history=1') });
      }
    },
    retry() { return this.refresh(); }
  }
});
