/** 公司积分卡：展示当前岗位所属公司的余额和员工本人当月使用量。 */
const http = require('../../api/http');
const points = require('../../api/company-points');
const denied = err => err && (err.postInvalid || [401,403].includes(Number(err.code)));
const loadError = '积分信息加载失败，请稍后重试';
const initial = () => ({ state: 'loading', balanceText: '--', usedText: '--', balanceLabel: '可用余额',
  emptyMessage: '公司积分池暂未开通', errorMessage: '', balanceError: false, usageError: false,
  refreshing: false });

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
          if (invalidValue) patch.balanceText = '--';
        } else if (invalidValue) patch.usedText = '--';
        update(patch);
      };
      // A return from another page must not join a read started before its operation.
      const balanceRead = points.summary({ fresh: true }).then(summary => {
        if (!alive()) return;
        if (summary === null) {
          update({ state: 'empty', balanceText: '--', balanceError: false });
          return;
        }
        if (!summary || summary.balance === undefined) throw { code: 'INVALID_CREDIT_VALUE' };
        const balanceText = points.formatCredits(summary.balance);
        if (summary.status === 'ACTIVE') {
          update({ balanceText, balanceError: false, state: 'ready', balanceLabel: '可用余额' });
          return;
        }
        if (summary.status === 'SUSPENDED') {
          update({ balanceText, balanceError: false, state: 'suspended', balanceLabel: '账户余额' });
          return;
        }
        throw { code: 'UNKNOWN_COMPANY_POINT_STATUS' };
      }).catch(err => failRead('balance', err));
      const usageRead = points.myUsage({ page: 1, size: 1, fresh: true }).then(usage => {
        if (!alive()) return;
        if (!usage || usage.used === undefined) throw { code: 'INVALID_CREDIT_VALUE' };
        update({ usedText: points.formatCredits(usage.used), usageError: false });
      }).catch(err => failRead('usage', err));
      return this.__reading = Promise.all([balanceRead, usageRead]).finally(() => {
        if (loadId === this.__loadId) { this.__reading = null; this.setData({ refreshing: false }); }
      });
    },
    retry() { return this.refresh(); }
  }
});
