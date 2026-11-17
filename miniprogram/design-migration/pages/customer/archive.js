const { protectedPage } = require('../../utils/access');
const session = require('../../utils/request');
const crm = require('../../../api/franchise/crm');
const design = require('../../../api/franchise/design');
const http = require('../../../api/http');
const { id } = require('../../utils/record-view');
const sha = require('../../utils/sha256');

protectedPage({
  data: { rows: [], rowCount: 0, keyword: '', loading: false, busy: false, error: '', hasMore: false,
    selected: '', saved: false, forCommission: false },
  onLoad(q) {
    this._token = session.captureSession();
    this._project = id(q.projectId); this._version = id(q.resultVersionId);
    this._budget = id(q.budgetId); this._revision = id(q.revisionId);
    this._kind = q.kind === 'ELEVATION' ? 'FACADE' : 'PLAN';
    this.setData({ forCommission: q.next === 'commission' && !!this._budget });
    if (!this._budget && (!this._project || !this._version)) {
      this.setData({ error: '方案编号无效，请返回结果页重新进入' }); return;
    }
    if (this.data.forCommission && id(q.customerId)) this.inheritCustomer(q.customerId);
    else this.load(true);
  },
  async inheritCustomer(customerId) {
    this.setData({loading:true,error:''});
    try {
      const detail = await crm.customers.get(customerId);
      if (!this.current()) return;
      const customer = detail && detail.customer;
      if (!customer || String(customer.id) !== customerId) throw Error('客户信息未确认');
      this.setData({rows:[customer],rowCount:1,selected:customerId,loading:false});
      // Carry the verified customer, but archive only after the user confirms this screen.
    } catch(e) {
      if (!this.current()) return;
      this.setData({loading:false,error:'原客户暂不可用，请重新选择委托客户'});
      return this.load(true);
    }
  },
  onUnload() { this._token = null; },
  current() { return session.isSameSession(this._token); },
  input(e) { this.setData({ keyword: e.detail.value }); },
  search() { return this.load(true); },
  more() { return this.load(false); },
  async load(reset) {
    if (!this.current() || this.data.loading) return;
    const page = reset ? 1 : (this._page || 1) + 1;
    this.setData({ loading: true, error: '' });
    try {
      const result = await crm.customers.page(page, 20, this.data.keyword.trim());
      if (!this.current()) return;
      const rows = reset ? result.list || [] : this.data.rows.concat(result.list || []);
      this._page = page;
      this.setData({ rows, rowCount: rows.length, hasMore: page * 20 < Number(result.total), loading: false });
    } catch (e) {
      if (this.current()) this.setData({ loading: false, error: e.msg || e.message || '客户读取失败，请重试' });
    }
  },
  choose(e) {
    if (!this.data.busy) {
      this._savedCustomer = null;
      this.setData({ selected: String(e.currentTarget.dataset.id), saved: false, error: '' });
    }
  },
  async save() {
    if (!this.current() || this.data.busy || !id(this.data.selected)) return;
    if (this.data.saved) { if (this.data.forCommission) this.openCommission(); return; }
    const customer = this.data.selected;
    const body = this._budget ? { customerId: customer, budgetId: this._budget, revisionId: this._revision }
      : { customerId: customer, kind: this._kind, resultVersionId: this._version };
    const scope = require('../../utils/context').scope() + ':' + JSON.stringify(body);
    const storage = 'design-customer-' + sha.sha256Hex(new Uint8Array(Array.from(unescape(encodeURIComponent(scope)), c => c.charCodeAt(0))));
    this.setData({ busy: true, error: '' });
    try {
      let key = wx.getStorageSync(storage);
      if (!key) { key = 'archive-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12); wx.setStorageSync(storage, key); }
      const result = this._budget ? await http.post('/franchise/design/flow-budget-archive', body, { 'Idempotency-Key': key })
        : await design.projects.saveToCustomer(this._project, body, key);
      if (!this.current()) return;
      if (this.data.forCommission && (!result || !id(String(result.schemeId)) || !Number.isInteger(result.version) || result.version < 1))
        throw Error('预算归档结果未确认，请重试');
      this._savedCustomer = customer;
      this.setData({ busy: false, saved: true });
      if (this.data.forCommission) this.openCommission();
      else wx.showToast({ title: '已发送至客户', icon: 'success' });
    } catch (e) {
      if (this.current()) this.setData({ busy: false, error: e.msg || e.message || '发送失败，可重试同一请求' });
    }
  },
  openCommission() {
    if (!this.current() || this.data.busy || !this.data.saved || this._savedCustomer !== this.data.selected) return;
    this.setData({ busy: true, error: '' });
    const customer = this.data.rows.find(row => String(row.id) === this._savedCustomer);
    wx.navigateTo({ url: '/pages/workflow/commission/index?view=submit&customerId=' + encodeURIComponent(this._savedCustomer)
      + (customer && customer.name ? '&customerName=' + encodeURIComponent(customer.name) : ''),
      success: () => { if (this.current()) this.setData({ busy: false }); },
      fail: () => { if (this.current()) this.setData({ busy: false, error: '委托页面未打开，请重试；预算已发送至所选客户' }); }
    });
  },
  back() { wx.navigateBack(); }
});
