const api = require('../../../api/company-points');
const auth = require('../../../api/auth');
const http = require('../../../api/http');
const { idemKey } = require('../../../api/finance');
const initial = () => ({ plans: [], pending: null, receipt: null, error: '', busy: false });
const money = fen => { const s = String(fen).padStart(3, '0'); return s.slice(0, -2) + '.' + s.slice(-2); };
function storageKey() {
  const post = auth.currentApiPost() || {};
  return ['company-package', http.tenantId(), post.orgId, post.userId, http.currentPostId()].join(':');
}

Page({
  data: initial(),
  onShow() { return this.refresh(); },
  onHide() { this.generation++; },
  onUnload() { this.generation++; },
  async refresh() {
    const generation = this.generation = (this.generation || 0) + 1;
    const session = api.fingerprint();
    if (!http.token() || !http.currentPostId()) return this.setData({ ...initial(), error: '请先登录并选择工作岗位' });
    const pending = wx.getStorageSync(storageKey()) || null;
    this.setData({ ...initial(), pending, busy: true });
    try {
      const plans = await api.packages();
      if (generation !== this.generation || session !== api.fingerprint()) return;
      this.setData({ plans: plans.map(p => ({ ...p, price: money(p.priceFen), base: api.formatCredits(p.basePoints), bonus: api.formatCredits(p.bonusPoints) })) });
    } catch (e) {
      if (generation === this.generation && !api.isStale(e)) this.setData({ error: e.message || '未能加载套餐' });
    } finally { if (generation === this.generation) this.setData({ busy: false }); }
  },
  choose(e) {
    if (this.data.busy || this.data.pending) return;
    const plan = this.data.plans.find(p => p.id === e.currentTarget.dataset.id);
    if (!plan) return;
    const generation = this.generation, session = api.fingerprint();
    this.setData({ busy: true, error: '' });
    wx.showModal({ title: '确认模拟支付', content: plan.name + '，模拟收款 ¥' + plan.price + '。测试积分仅供演练，将增加基础 ' + plan.base + ' 和赠送 ' + plan.bonus + '，微信不会扣款。',
      confirmText: '模拟支付',
      success: res => {
        if (generation !== this.generation || session !== api.fingerprint()) return;
        if (!res.confirm) return this.setData({ busy: false });
        const pending = { plan: { id: plan.id, version: plan.version, name: plan.name, price: plan.price }, key: idemKey('package') };
        try { wx.setStorageSync(storageKey(), pending); }
        catch (_) { return this.setData({ busy: false, error: '未能保存订单，请重新确认' }); }
        this.setData({ pending, receipt: null, busy: false });
        return this.confirmPending();
      }, fail: () => { if (generation === this.generation) this.setData({ busy: false }); }
    });
  },
  async confirmPending() {
    if (this.data.busy || !this.data.pending) return;
    const { plan, key } = this.data.pending;
    const generation = this.generation, session = api.fingerprint(), storage = storageKey();
    const active = () => generation === this.generation && session === api.fingerprint() && storage === storageKey();
    this.setData({ busy: true, error: '' });
    try {
      const result = await api.purchase(plan, key);
      if (!active()) return;
      wx.removeStorageSync(storage);
      this.setData({ pending: null, receipt: { ...result, price: money(result.priceFen), credited: api.formatCredits(result.points) } });
    } catch (e) {
      if (!active() || api.isStale(e)) return;
      if ([400, 403, 409].includes(e.code)) {
        wx.removeStorageSync(storage);
        this.setData({pending:null, plans:[]});
      }
      this.setData({ error: e.message || '付款结果待确认，请用原单重试' });
    } finally { if (active()) this.setData({ busy: false }); }
  }
});
