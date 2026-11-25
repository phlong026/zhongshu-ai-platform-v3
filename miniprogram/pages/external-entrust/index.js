const http = require('../../api/http');
const auth = require('../../api/auth');
const scope = require('../../utils/api-scope');
const navigation = require('../../utils/page-navigation');
const { commandKey } = require('../../utils/platform-crm');
const { day } = require('../../utils/finance-date');
const model = require('./model');
const api = require('./api');
const errorText = error => String(error?.msg || error?.message || '操作未完成，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1112000000].includes(Number(error?.code));

Page({
  ...require('./files'),
  data: { loading: false, ready: false, context: null, tab: 'request', form: null, editing: '', dirty: false, today: '',
    requestFields: model.requestFields, fileSlots: [], historyKey: '', busy: false, uploading: false, uploadFailed: false,
    uploadRetryable: false, uploadRestartAllowed: false, uploadName: '', uncertain: false, conflict: false, error: '' },
  onLoad(options = {}) {
    this._routeCustomerId = model.id(options.customerId); this._routeCommissionId = model.id(options.commissionId);
    this.customerId = this._routeCustomerId;
    this.setData({ today: day(Date.now()), tab: ['request', 'result', 'history'].includes(options.tab) ? options.tab : 'request' });
  },
  onShow() { this._visible = true; this.setData({ today: day(Date.now()) }); return this.load(); },
  onHide() { this._visible = false; this._read = null; this._preview = null; },
  onUnload() { this._disposed = true; this.onHide(); this.clear(); },
  alive(savedScope = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && !!http.token() && !!savedScope && savedScope === scope() && post && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._preview = null; this._pending = null; this._pendingFile = null; this._localFiles = {}; this._edit = null;
    this.setData({ loading: false, ready: false, context: null, form: null, editing: '', dirty: false, fileSlots: [], historyKey: '',
      busy: false, uploading: false, uploadFailed: false, uploadRetryable: false, uploadRestartAllowed: false, uploadName: '', uncertain: false, conflict: false, error });
  },
  apply(context) {
    this._read = null; this._preview = null; this._pendingFile = null; this._localFiles = {}; this._edit = null;
    this._baseRevision = context.revision; this._basePlanRef = model.copy(context.planRef);
    this.setData({ loading: false, ready: true, context, form: null, editing: '', dirty: false, conflict: false,
      uploadFailed: false, uploadName: '', uploadRestartAllowed: false, fileSlots: this.slotsFor(context.result?.data), error: '' });
    if (!context.commissionId && context.canRegister && this.data.tab === 'request') this.startEdit({ currentTarget: { dataset: { action: 'register' } } });
  },
  async load(options = {}) {
    if (!this._visible || this._disposed) return;
    const currentScope = scope(), post = auth.currentApiPost();
    if (!http.token() || !post || !currentScope || String(post.postId) !== String(http.currentPostId())) return this.clear('请先登录并选择工作身份');
    if (!this._routeCustomerId && !this._routeCommissionId) return this.clear('委托链接无效，请从客户页重新进入');
    if (currentScope !== this._scope) { this.clear(); this._scope = currentScope; this.customerId = this._routeCustomerId; }
    if (this.data.busy || this.data.uploading || this.data.uncertain) return;
    const request = this._read = { scope: currentScope };
    const current = () => this._visible && this._read === request && this.alive(request.scope);
    this.setData({ loading: true, error: '' });
    try {
      if (!this.customerId) {
        const previous = await api.resolve(this._routeCommissionId); if (!current()) return;
        if (model.id(previous?.commission?.id) !== this._routeCommissionId || !model.id(previous?.commission?.customerId)) throw Error('委托所属客户未确认');
        this.customerId = model.id(previous.commission.customerId);
      }
      const result = await api.get(this.customerId); if (!current()) return;
      const context = model.detail(result, this.customerId, model.id(post.orgId), post);
      if (this._routeCommissionId && context.commissionId !== this._routeCommissionId) throw Error('委托所属客户不匹配');
      if (this.data.context && context.revision < this.data.context.revision) return this.setData({ loading: false });
      const keep = !options.replace && this.data.editing && this.allowed(this.data.editing, context);
      if (!keep) this.apply(context);
      else this.setData({ loading: false, ready: true, context,
        conflict: this._baseRevision !== context.revision || this.data.editing !== 'results' && model.refKey(this._basePlanRef) !== model.refKey(context.planRef) });
    } catch (error) {
      if (!current()) return;
      if (revoked(error)) return this.clear(errorText(error));
      this.setData({ loading: false, error: errorText(error) });
    }
  },
  allowed(action, context = this.data.context) {
    return !!context && model.canWrite(auth.currentApiPost()) && ({ register: context.canRegister, convert: context.canConvert, results: context.canResults })[action] === true;
  },
  editable() { return this.alive() && this.allowed(this.data.editing) && !this.data.busy && !this.data.uploading && !this.data.uncertain && !this.data.conflict; },
  startEdit(event) {
    const action = event?.currentTarget?.dataset?.action;
    if (!this.alive() || !this.allowed(action) || this.data.busy || this.data.uploading || this.data.uncertain || this.data.dirty) return;
    const context = this.data.context; this._read = null; this._preview = null; this._pendingFile = null; this._localFiles = {}; this._edit = {};
    this._baseRevision = context.revision; this._basePlanRef = model.copy(context.planRef);
    const form = action === 'results' ? model.resultForm(context) : model.requestForm(context);
    this.setData({ form, editing: action, tab: action === 'results' ? 'result' : 'request', dirty: false, conflict: false, error: '',
      fileSlots: this.slotsFor(action === 'results' ? form : context.result?.data), uploadFailed: false, uploadName: '', uploadRestartAllowed: false });
  },
  input(event) {
    const key = event.currentTarget.dataset.key;
    const keys = this.data.editing === 'results' ? ['deliveredOn', 'drawingVersion', 'note'] : ['entrustedOn', 'reason', ...model.requestFields.map(field => field.key)];
    if (!this.editable() || !keys.includes(key)) return;
    this.setData({ form: { ...this.data.form, [key]: String(event.detail.value ?? '') }, dirty: true, error: '' });
  },
  changeTab(event) {
    const tab = event.currentTarget.dataset.tab;
    if (!['request', 'result', 'history'].includes(tab) || tab === this.data.tab || this.data.busy || this.data.uploading || this.data.uncertain) return;
    const change = () => { this._pendingFile = null; this._localFiles = {}; this._edit = null; this._preview = null;
      this.setData({ tab, form: null, editing: '', dirty: false, conflict: false, error: '', uploadFailed: false, uploadName: '',
        fileSlots: this.slotsFor(this.data.context?.result?.data) }); };
    if (!this.data.dirty && !this._pendingFile) return change();
    const savedScope = this._scope;
    wx.showModal({ title: '有未保存的填写', content: '切换将丢弃当前未保存的内容。', confirmText: '切换', success: result => { if (result.confirm && this.alive(savedScope)) change(); } });
  },
  toggleHistory(event) { const key = event.currentTarget.dataset.key; if (this.alive() && this.data.context?.history.some(item => item.key === key)) this.setData({ historyKey: this.data.historyKey === key ? '' : key }); },
  async submit() {
    if (!this.editable()) return;
    if (this._pendingFile) return this.setData({ error: '请先完成文件上传，或取消本次选择' });
    try {
      const context = { ...this.data.context, revision: this._baseRevision, planRef: this._basePlanRef };
      const body = model.payload(this.data.editing, this.data.form, context, day(Date.now()));
      this._pending = { scope: this._scope, customerId: this.customerId, action: this.data.editing, body: model.copy(body), key: commandKey('external-entrust') };
      return this.runPending();
    } catch (error) { this.setData({ error: errorText(error) }); }
  },
  async runPending() {
    const command = this._pending;
    if (!command || this.data.busy || !this.alive(command.scope) || command.customerId !== this.customerId) return;
    this._read = null; this._preview = null; this.setData({ busy: true, uncertain: false, error: '' });
    try {
      const result = await api[command.action](command.customerId, command.body, command.key);
      if (!this.alive(command.scope) || this._pending !== command) return;
      const context = model.detail(result, command.customerId, model.id(auth.currentApiPost().orgId), auth.currentApiPost());
      if (context.revision <= command.body.revision || !context.commissionId) throw Error('办理结果尚未确认');
      this._pending = null; this.setData({ busy: false, uncertain: false }); this.apply(context);
      wx.showToast({ title: command.action === 'results' ? '外部成果已登记' : command.action === 'convert' ? '已转入外部流程' : '委托需求已登记', icon: 'none' });
    } catch (error) {
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (revoked(error)) return this.clear(errorText(error));
      if (Number(error?.code) === 1099000002) return this.recheckAccess(error);
      const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code === 1099000000 || code >= 500 && code < 600;
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '办理结果尚未确认，请用原请求重试核对' : errorText(error) });
    }
  },
  async recheckAccess(error) {
    const savedScope = this._scope; this.clear(errorText(error)); await this.load({ replace: true });
    if (this.alive(savedScope) && this.data.ready) this.setData({ error: '委托或文件权限已变化，请核对最新记录后办理' });
  },
  reloadLatest() {
    if (this.data.busy || this.data.uploading || this.data.uncertain) return;
    if (!this.data.dirty && !this._pendingFile) return this.load({ replace: true });
    const savedScope = this._scope;
    wx.showModal({ title: '载入最新记录', content: '当前未保存的填写和文件选择将被最新记录替换。', success: result => { if (result.confirm && this.alive(savedScope)) this.load({ replace: true }); } });
  },
  leaveTo(url) {
    if (!this.alive() || this.data.busy || this.data.uploading || this.data.uncertain) return;
    const go = () => navigation.open(url), savedScope = this._scope;
    if (!this.data.dirty && !this._pendingFile) return go();
    wx.showModal({ title: '有未保存的填写', content: '离开将丢弃当前未保存的内容。', confirmText: '离开', success: result => { if (result.confirm && this.alive(savedScope)) { this.apply(this.data.context); go(); } } });
  },
  openPlan() {
    const context = this.data.context; if (!context) return;
    this.leaveTo('/pages/workflow/payment-plan/index?' + (context.currentPlan.source === 'CONTRACT'
      ? 'contractId=' + encodeURIComponent(context.currentPlan.contractId) : 'customerId=' + encodeURIComponent(this.customerId) + '&draft=1'));
  },
  openLegacy() { if (this.data.context?.commissionId) this.leaveTo('/pages/workflow/commission/index?view=progress&history=1&commissionId=' + encodeURIComponent(this.data.context.commissionId)); },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  navigationBack() {
    if (this.data.busy || this.data.uploading || this.data.uncertain) return wx.showToast({ title: this.data.uncertain ? '请先核对办理结果' : '请等待当前操作完成', icon: 'none' });
    const savedScope = this._scope, back = () => navigation.back({ skipLocal: true, fallback: '/pages/business/index' });
    if (!this.data.dirty && !this._pendingFile) return back();
    wx.showModal({ title: '有未保存的填写', content: '离开将丢弃当前未保存的内容。', confirmText: '离开', success: result => { if (result.confirm && this.alive(savedScope)) back(); } });
  }
});
