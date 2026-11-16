const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const budget = require('../../utils/budget-view');
const message = require('../../utils/message-view');
const format = require('../../utils/format');

protectedPage({
  data: { loading: false, error: '', quote: null, groups: [], statusText: '', publishedAt: '', withdrawnAt: '', exact: false },
  onLoad(options) {
    this._budgetId = message.id(options.budgetId); this._quoteId = message.id(options.quoteId);
    this._revisionId = options.revisionId == null ? null : message.id(options.revisionId);
    this._invalid = !this._budgetId || !this._quoteId || (options.revisionId != null && !this._revisionId);
  },
  onShow() { return this.load(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._session = null; },
  onPullDownRefresh() { return Promise.resolve(this.load()).finally(() => wx.stopPullDownRefresh()); },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._session && (http.isSameSession ? http.isSameSession(this._session) : this._session === http.getToken())) return true;
    this.setData({ loading: false, quote: null, groups: [], error: '登录状态已更新，请重新进入' }); return false;
  },
  load() {
    const seq = this._seq = (this._seq || 0) + 1;
    this._session = http.captureSession ? http.captureSession() : http.getToken();
    this.setData({ loading: false, error: '', quote: null, groups: [] });
    if (this._invalid) { this.setData({ error: '报价编号无效，请从消息或预算记录重新进入' }); return; }
    if (!this.current(seq)) return;
    this.setData({ loading: true });
    return api.getPublicBudgetQuote(this._budgetId, this._quoteId).then(response => {
      if (!this.current(seq)) return;
      const quote = budget.publicQuote(response);
      if (quote.quoteId !== this._quoteId || quote.budgetId !== this._budgetId || (this._revisionId && quote.revisionId !== this._revisionId)) throw Error('报价版本不匹配');
      this.setData({ loading: false, quote, statusText: quote.status === 'WITHDRAWN' ? '此报价已撤回，仅供历史核对' : quote.current ? '已发布 · 当前报价' : '已发布 · 历史报价',
        publishedAt: format.shortTime(quote.publishedAt), withdrawnAt: format.shortTime(quote.withdrawnAt),
        groups: quote.items.map(item => ({ ...item, amountText: item.amountCents == null ? '待补齐' : budget.yuan(item.amountCents) + '元',
          lines: item.lines.map(line => ({ ...line, amountText: line.amountCents == null ? '未计价' : budget.yuan(line.amountCents) + '元' })) })),
        ...budget.quoteView(quote, this.data.exact) });
    }).catch(error => { if (this.current(seq)) this.setData({ loading: false, error: budget.errorText(error) }); });
  },
  toggleExact() {
    if (!this.current(this._seq) || !this.data.quote) return;
    const exact = !this.data.exact; this.setData({ exact, ...budget.quoteView(this.data.quote, exact) });
  }
});
