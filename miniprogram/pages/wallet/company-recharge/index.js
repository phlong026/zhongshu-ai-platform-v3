const http = require('../../../api/http');
const points = require('../../../api/company-points');
const auth = require('../../../api/auth');
const { formatYuan } = require('../../../utils/wallet-view');
const route = '/pages/wallet/company-recharge/index';
const base = '/franchise/company-points/recharge';
const refreshMs = 10000;
const statusText = { CREATED: '待支付', PROCESSING: '支付确认中', PAID: '额度到账中',
  CREDITED: '额度已到账', CLOSED: '订单已关闭', FAILED: '支付失败', REFUNDING:'退款核对中', REFUNDED:'已退款', REFUND_REVIEW:'退款已完成，额度待核对' };
const initial = () => ({ loading: true, error: '', note: '', company: '', enabled: false, reason: '',
  plans: [], hasPlans: false, selected: null, busy: false, order: null, browseOnly: false });

async function readCatalog(actor) {
  try {
    return await http.get(base + '/catalog');
  } catch (err) {
    if (Number(err.code) !== 404 || actor !== points.fingerprint()) throw err;
    // The existing platform endpoint already returns the administrator's active offers in display order.
    const offers = await http.get('/platform/wallet/recharge-plans');
    const post = auth.currentApiPost();
    if (!Array.isArray(offers)) throw Error('套餐数据异常，请重新加载');
    return { companyName: post && post.orgName, enabled: false, browseOnly: true,
      reason: '公司积分支付暂未开放', plans: offers.map(plan => ({
        id: plan.id, version: '', name: plan.name, amountCents: Number(plan.priceFen),
        credits: Number(plan.basePoints) + Number(plan.bonusPoints)
      })) };
  }
}

function planView(plan) {
  if (!plan.id || plan.version == null || !Number.isSafeInteger(plan.amountCents) || plan.amountCents <= 0)
    throw Error('套餐价格异常，请刷新');
  return { id: String(plan.id), version: String(plan.version), amount: formatYuan(plan.amountCents),
    name: plan.name, credits: points.formatCredits(plan.credits), amountCents: plan.amountCents };
}

function samePlan(a, b) {
  return a && b && a.id === b.id && a.version === b.version &&
    a.amountCents === b.amountCents && a.credits === b.credits;
}

function orderView(order) {
  if (!order) return null;
  if (!order.id || !statusText[order.status]) throw Error('订单状态异常，请刷新');
  return { id: String(order.id), status: order.status, label: statusText[order.status],
    canPay: order.status === 'CREATED' || (order.status === 'PROCESSING' && order.quoteCurrent === true),
    quoteExpired: order.status === 'PROCESSING' && order.quoteCurrent === false,
    amount: formatYuan(order.amountCents), credits: points.formatCredits(order.credits) };
}

Page({
  data: initial(),
  onShow() {
    this.foreground = true;
    if (this.actor !== points.fingerprint()) {
      this.actor = points.fingerprint();
      this.operation = null;
      this.intent = null;
      this.lastSeenOrder = '';
      this.setData(initial());
    }
    if (!http.token()) {
      wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(route) });
      return;
    }
    if (!http.currentPostId()) {
      this.setData({ loading: false, error: '请先选择工作身份' });
      return;
    }
    if (!this.operation) this.refresh();
    clearInterval(this.timer);
    this.timer = setInterval(() => {
      if (!this.operation && !this.reading) this.refresh();
    }, refreshMs);
  },
  onHide() { this.foreground = false; clearInterval(this.timer); },
  onUnload() { this.closed = true; this.onHide(); },
  current(actor) { return !this.closed && actor === points.fingerprint(); },
  async refresh() {
    const actor = this.actor, sequence = this.reading = (this.sequence || 0) + 1;
    this.sequence = sequence;
    try {
      const catalog = await readCatalog(actor);
      if (!this.current(actor) || sequence !== this.sequence) return false;
      if (!catalog || !Array.isArray(catalog.plans)) throw Error('套餐数据异常，请重新加载');
      const enabled = catalog.enabled === true, browseOnly = catalog.browseOnly === true;
      const plans = enabled || browseOnly ? catalog.plans.map(planView) : [];
      const old = this.data.selected, selected = old && plans.find(plan => samePlan(plan, old)) || null;
      const currentOrder = orderView(catalog.currentOrder);
      const order = currentOrder && currentOrder.id + '/' + currentOrder.status !== this.lastSeenOrder ? currentOrder : null;
      const changed = !!old && !selected;
      if (changed || order) this.intent = null;
      let note = this.data.note;
      if (!plans.length && !order) note = '';
      else if (changed) note = plans.some(plan => plan.id === old.id)
        ? '套餐已调整，请按最新价格重新选择。' : '原套餐已下架，请选择其他套餐。';
      this.setData({ loading: false, error: '', company: catalog.companyName, enabled, browseOnly, plans,
        hasPlans: plans.length > 0, selected, order,
        reason: catalog.reason || '当前公司暂未开放充值', note });
      return true;
    } catch (err) {
      if (this.current(actor) && sequence === this.sequence) this.setData({ loading: false, error:
        Number(err.code) === 404 ? '公司积分充值服务暂未开放' : err.message || '套餐加载失败，请重试' });
      return false;
    } finally {
      if (this.reading === sequence) this.reading = 0;
    }
  },
  retry() { if (!this.data.busy) this.refresh(); },
  choose(e) {
    if (this.data.busy || this.data.error || this.data.order || !this.data.enabled) return;
    const selected = this.data.plans.find(item => item.id === e.currentTarget.dataset.id);
    if (selected) {
      if (!samePlan(selected, this.data.selected)) this.intent = null;
      this.setData({ selected, note: '' });
    }
  },
  async checkout() {
    if (this.operation || this.data.error || !this.current(this.actor)) return;
    const selected = this.data.selected, previousOrder = this.data.order, actor = this.actor;
    if (!selected && !previousOrder?.canPay) return;
    const operation = this.operation = {};
    this.setData({ busy: true, note: '' });
    try {
      if (!await this.refresh() || !this.current(actor) || !this.foreground || !this.data.enabled) return;
      let order = this.data.order;
      if (previousOrder) {
        if (!order || order.id !== previousOrder.id || !order.canPay) return;
      } else {
        if (order || !samePlan(selected, this.data.selected)) return;
        this.intent = this.intent || ('cp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2));
        const result = await http.post(base + '/orders', { planId: selected.id, planVersion: selected.version },
          { 'Idempotency-Key': this.intent });
        if (!this.current(actor)) return;
        order = orderView(result);
        if (!order) throw Error('未取得充值订单，请刷新');
        this.intent = null;
        this.setData({ order, selected: null });
        if (order.status !== 'CREATED' || order.amount !== selected.amount || order.credits !== selected.credits)
          throw Error('订单信息已更新，请核对后再支付');
      }
      if (!this.foreground) return;
      if (typeof wx.requestVirtualPayment !== 'function') throw Error('当前微信版本不支持此支付方式，请升级后重试');
      const login = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: reject }));
      if (!this.current(actor) || !this.foreground) return;
      if (!login.code) throw Error('获取支付凭证失败，请重试');
      const params = await http.post(base + '/orders/' + encodeURIComponent(order.id) + '/pay-params', { code: login.code });
      if (!this.current(actor) || !this.foreground) return;
      if (!params || !params.mode || !params.signData || !params.paySig || !params.signature)
        throw Error('支付参数不完整，请刷新订单');
      await new Promise((resolve, reject) => wx.requestVirtualPayment({ mode: params.mode, signData: params.signData,
        paySig: params.paySig, signature: params.signature, success: resolve, fail: reject }));
      if (this.current(actor)) this.setData({ note: '已提交支付，正在核对到账状态。' });
    } catch (err) {
      if (this.current(actor)) this.setData({ note: /cancel/i.test(err.errMsg || '') ? '已取消支付。' :
        err.message || '支付未完成，请刷新订单状态。' });
    } finally {
      if (this.operation === operation) {
        this.operation = null;
        if (this.current(actor)) {
          this.setData({ busy: false });
          if (this.foreground) await this.refresh();
        }
      }
    }
  },
  dismissOrder() {
    const order = this.data.order;
    if (!order || (!order.quoteExpired && !['CREDITED','CLOSED','FAILED','REFUNDED','REFUND_REVIEW'].includes(order.status))) return;
    this.lastSeenOrder = order.id + '/' + order.status;
    this.setData({ order: null, note: '' });
  }
});
