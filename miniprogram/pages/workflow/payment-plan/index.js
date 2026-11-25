const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('../../../utils/api-scope');
const navigation = require('../../../utils/page-navigation');
const { commandKey } = require('../../../utils/platform-crm');
const api = require('./api');
const model = require('./model');
const clone = value => JSON.parse(JSON.stringify(value));
const errorText = error => String(error?.msg || error?.message || '操作未完成，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1113000004].includes(Number(error?.code));

Page({
  data: { loading: false, refreshing: false, ready: false, context: null, customerName: '', form: null, summary: null,
    editing: false, creating: false, dirty: false, busy: false, uncertain: false, conflict: false, error: '',
    triggers: model.TRIGGERS, today: '', showHistory: false, history: [], historyLoading: false, historyError: '', selectedVersion: null },
  onLoad(options = {}) {
    this.contractId = model.id(options.contractId); this.customerId = model.id(options.customerId);
    this._invalid = !!options.contractId && !this.contractId;
    this._openHistory = options.history === '1'; this._rowNo = 1;
    this.setData({ today: require('../../../utils/finance-date').day(Date.now()) });
  },
  onShow() { this._visible = true; return this.load(); },
  onHide() { this._visible = false; this._read = null; this._historyRead = null; this.setData({ historyLoading: false }); },
  onUnload() { this._disposed = true; this.onHide(); this._pending = null; },
  alive(savedScope = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && !!http.token() && !!savedScope && savedScope === scope()
      && post && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._historyRead = null; this._pending = null;
    this.setData({ ready: false, loading: false, refreshing: false, context: null, customerName: '', form: null, summary: null,
      editing: false, creating: false, dirty: false, busy: false, uncertain: false, conflict: false, error,
      showHistory: false, history: [], historyLoading: false, historyError: '', selectedVersion: null });
  },
  async load(options = {}) {
    if (!this._visible || this._disposed) return;
    const currentScope = scope(), post = auth.currentApiPost();
    if (!http.token() || !currentScope || !post || String(post.postId) !== String(http.currentPostId())) return this.clear('请先登录并选择工作身份');
    if (this._invalid || !this.contractId && !this.customerId) return this.clear('合同或客户链接无效，请从业务入口重新进入');
    if (this._scope !== currentScope) { this.clear(); this._scope = currentScope; }
    if (this.data.busy) return;
    const request = this._read = { scope: currentScope, contractId: this.contractId };
    const current = () => this._visible && this._read === request && this.alive(request.scope);
    this.setData({ loading: !this.data.ready, refreshing: this.data.ready, error: '' });
    try {
      if (this.contractId) {
        const result = await api.get(this.contractId);
        if (!current()) return;
        const context = model.detail(result, this.contractId, String(post.orgId));
        if (this.data.context && context.revision < this.data.context.revision) { this.setData({ loading: false, refreshing: false }); return; }
        this.customerId = context.customerId;
        const keep = !options.replace && this.data.dirty && context.canEdit;
        const form = keep ? this.data.form : clone(context.form);
        if (!keep) this._baseRevision = context.revision;
        this.setData({ ready: true, loading: false, refreshing: false, context, customerName: context.customerName, creating: false,
          editing: keep && this.data.editing, form, summary: model.preview(form), dirty: keep,
          conflict: keep && this._baseRevision !== context.revision });
      } else {
        if (!['sales', 'sales-lead', 'boss'].includes(post.roleCode) || post.orgType !== 'store') return this.clear('当前岗位不能创建合同');
        const result = await api.prepare(this.customerId);
        if (!current()) return;
        const active = result.contracts.find(row => row.contract?.status !== 'VOIDED');
        if (active) {
          if (!model.id(active.contract?.id) || model.id(active.contract.customerId) !== this.customerId) throw Error('客户合同响应不匹配');
          this.contractId = model.id(active.contract.id); return this.load({ replace: true });
        }
        const latest = result.requirements[0]; this.requirementId = latest ? model.id(latest.id) : '';
        const quote = result.quotes[0], keep = !options.replace && this.data.dirty;
        const form = keep ? this.data.form : model.initial(quote?.totalCents);
        this.setData({ ready: true, loading: false, refreshing: false, context: null, customerName: String(quote?.customerName || ''),
          creating: true, editing: true, form, summary: model.preview(form), dirty: keep, conflict: false });
      }
      if ((this._openHistory || this.data.showHistory) && this.contractId) { this._openHistory = false; this.setData({ showHistory: true }); this.loadHistory(); }
    } catch (error) {
      if (!current()) return;
      if (revoked(error)) this.clear(errorText(error));
      else this.setData({ loading: false, refreshing: false, error: errorText(error) });
    }
  },
  editable() { return this.alive() && this.data.editing && (this.data.creating || this.data.context?.canEdit)
    && !this.data.busy && !this.data.uncertain && !this.data.conflict; },
  setForm(form) { this.setData({ form, summary: model.preview(form), dirty: true, error: '' }); },
  startEdit() {
    if (!this.alive() || !this.data.context?.canEdit || this.data.busy || this.data.uncertain) return;
    this._baseRevision = this.data.context.revision;
    const form = clone(this.data.context.form);
    this.setData({ editing: true, showHistory: false, dirty: false, conflict: false, form, summary: model.preview(form), error: '' });
  },
  input(event) {
    if (!this.editable()) return;
    const key = event.currentTarget.dataset.key, value = String(event.detail.value ?? '');
    const form = clone(this.data.form), rowKey = event.currentTarget.dataset.row;
    if (rowKey) {
      const row = form.rows.find(r => r.key === rowKey);
      if (!row || !['label', 'amount', 'ratio', 'dueDate', 'nodeIndex'].includes(key)) return;
      row[key] = value;
    } else if (['total', 'signedDate', 'reason'].includes(key)) form[key] = value;
    else return;
    this.setForm(form);
  },
  clearDate(event) { this.input({ currentTarget: event.currentTarget, detail: { value: '' } }); },
  chooseMode(event) {
    if (!this.editable()) return;
    const mode = event.currentTarget.dataset.mode;
    if (!['AMOUNT', 'RATIO'].includes(mode) || this.data.form.mode === mode) return;
    const form = clone(this.data.form);
    if (mode === 'AMOUNT' && this.data.summary.valid) form.rows.forEach((row, i) => row.amount = this.data.summary.rows[i].amountText);
    form.mode = mode; this.setForm(form);
  },
  trigger(event) {
    if (!this.editable()) return;
    const selected = this.data.summary.rows.find(r => r.key === event.currentTarget.dataset.row)?.triggerChoices[Number(event.detail.value)], form = clone(this.data.form);
    const row = form.rows.find(r => r.key === event.currentTarget.dataset.row);
    if (!row || !selected) return;
    row.trigger = selected.value; if (row.trigger !== 'NODE_COMPLETED') row.nodeIndex = '';
    this.setForm(form);
  },
  addRow() {
    if (!this.editable() || this.data.form.rows.length >= 20) return;
    const form = clone(this.data.form); form.rows.push({ ...model.emptyRow('new-' + ++this._rowNo), label: '第' + (form.rows.length + 1) + '期' }); this.setForm(form);
  },
  removeRow(event) {
    if (!this.editable()) return;
    const key = event.currentTarget.dataset.row;
    if (!this.data.summary.rows.find(r => r.key === key)?.removable) return;
    const form = clone(this.data.form); form.rows = form.rows.filter(r => r.key !== key); this.setForm(form);
  },
  moveRow(event) {
    if (!this.editable()) return;
    const form = clone(this.data.form), index = form.rows.findIndex(r => r.key === event.currentTarget.dataset.row);
    const direction = Number(event.currentTarget.dataset.direction), target = index + direction;
    if (![-1, 1].includes(direction) || index < 0 || target < 0 || target >= form.rows.length) return;
    [form.rows[index], form.rows[target]] = [form.rows[target], form.rows[index]]; this.setForm(form);
  },
  async submit() {
    if (!this.editable()) return;
    try {
      const body = model.payload(this.data.form, { contractId: this.contractId, customerId: this.customerId,
        revision: this._baseRevision, requirementId: this.requirementId });
      this._pending = { scope: this._scope, contractId: this.contractId, body, key: commandKey('payment-plan') };
      return this.runPending();
    } catch (error) { this.setData({ error: errorText(error) }); }
  },
  async runPending() {
    const command = this._pending;
    if (!command || this.data.busy || !this.alive(command.scope)) return;
    this._read = null; this._historyRead = null;
    this.setData({ busy: true, uncertain: false, error: '', refreshing: false, historyLoading: false });
    try {
      const result = command.contractId ? await api.save(command.contractId, command.body, command.key) : await api.create(command.body, command.key);
      if (!this.alive(command.scope) || this._pending !== command) return;
      const contractId = model.id(result?.contract?.id || result?.contractId);
      if (!contractId || command.contractId && contractId !== command.contractId) throw Error('保存响应未确认，请重试核对');
      this.contractId = contractId; this._pending = null;
      this.setData({ busy: false, uncertain: false, dirty: false, editing: false, creating: false, form: null, summary: null,
        context: null, ready: false, conflict: false, showHistory: false });
      wx.showToast({ title: command.contractId ? '付款计划已保存' : '待签合同已创建', icon: 'none' });
      return this.load({ replace: true });
    } catch (error) {
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (revoked(error)) return this.clear(errorText(error));
      const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600;
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '保存结果尚未确认，请用原请求重试核对' : errorText(error) });
    }
  },
  reloadLatest() {
    if (this.data.busy || this.data.uncertain) return;
    if (!this.data.dirty) return this.load({ replace: true });
    const savedScope = this._scope;
    wx.showModal({ title: '载入最新付款计划', content: '未保存的调整将被最新版本替换。', success: r => {
      if (r.confirm && this.alive(savedScope)) this.load({ replace: true });
    } });
  },
  toggleHistory() {
    if (!this.contractId || !this.alive() || this.data.busy) return;
    this.setData({ showHistory: !this.data.showHistory, selectedVersion: null });
    if (this.data.showHistory) return this.loadHistory();
  },
  async loadHistory() {
    if (!this.contractId || !this.alive()) return;
    const request = this._historyRead = { scope: this._scope };
    this.setData({ historyLoading: true, historyError: '', history: [], selectedVersion: null });
    try {
      const result = await api.versions(this.contractId);
      if (this._historyRead !== request || !this.alive(request.scope)) return;
      this.setData({ history: model.versions(result), historyLoading: false });
    } catch (error) {
      if (this._historyRead !== request || !this.alive(request.scope)) return;
      if (revoked(error)) return this.clear(errorText(error));
      this.setData({ historyLoading: false, historyError: errorText(error) });
    }
  },
  viewVersion(event) {
    if (!this.alive()) return;
    this.setData({ selectedVersion: this.data.history.find(v => String(v.version) === String(event.currentTarget.dataset.version)) || null });
  },
  closeVersion() { this.setData({ selectedVersion: null }); },
  noop() {},
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  navigationBack() {
    if (this.data.selectedVersion) return this.closeVersion();
    if (this.data.busy || this.data.uncertain) return wx.showToast({ title: this.data.uncertain ? '请先核对保存结果' : '请等待保存完成', icon: 'none' });
    const post = auth.currentApiPost();
    const fallback = ['finance', 'finance-lead'].includes(post?.roleCode) ? '/pages/admin/finance-desk/index'
      : '/pages/workflow/index?view=contract&id=' + encodeURIComponent(this.customerId || '');
    const back = () => navigation.back({ skipLocal: true, fallback });
    if (!this.data.dirty) return back();
    wx.showModal({ title: '有未保存的调整', content: '离开将丢弃当前付款计划的修改。', confirmText: '离开', success: r => { if (r.confirm) back(); } });
  }
});
