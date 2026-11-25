const http = require('../../api/http');
const auth = require('../../api/auth');
const scope = require('../../utils/api-scope');
const navigation = require('../../utils/page-navigation');
const { commandKey } = require('../../utils/platform-crm');
const { day } = require('../../utils/finance-date');
const model = require('./model');
const api = require('./api');
const message = error => String(error?.msg || error?.message || '操作未完成，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1112000000].includes(Number(error?.code));

Page({
  ...require('./files'),
  data: { ready: false, loading: false, context: null, source: null, form: null, editing: false, dirty: false, tab: 'current',
    today: '', receivers: [], receiverIndex: -1, receiverError: '', historyVersion: 0, busy: false, uncertain: false, conflict: false,
    uploading: false, uploadFailed: false, uploadRetryable: false, uploadRestartAllowed: false, uploadName: '', confirmationTitle: '', error: '' },
  onLoad(options = {}) { this.commissionId = model.id(options.commissionId); this.setData({ today: day(Date.now()) }); },
  onShow() { this._visible = true; this.setData({ today: day(Date.now()) }); return this.load(); },
  onHide() { this._visible = false; this._read = null; this._preview = null; },
  onUnload() { this._disposed = true; this.onHide(); this.clear(); },
  alive(savedScope = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && !!http.token() && !!savedScope && savedScope === scope() && !!post && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._preview = null; this._pending = null; this._pendingFile = null; this._localFile = null; this._edit = null;
    this.setData({ ready: false, loading: false, context: null, source: null, form: null, editing: false, dirty: false,
      receivers: [], receiverIndex: -1, receiverError: '', historyVersion: 0, busy: false, uncertain: false, conflict: false,
      uploading: false, uploadFailed: false, uploadRetryable: false, uploadRestartAllowed: false, uploadName: '', confirmationTitle: '', error });
  },
  apply(context, source = null, receivers = [], receiverError = '') {
    this._read = null; this._preview = null; this._pendingFile = null; this._localFile = null; this._edit = {};
    this._baseRevision = context.revision;
    this.setData({ ready: true, loading: false, context, source, receivers, receiverError, receiverIndex: -1,
      editing: context.canSubmit || context.canReceive || context.canReturn, form: model.form(context), dirty: false,
      confirmationTitle: '', conflict: false, uploadFailed: false, uploadName: '', uploadRestartAllowed: false, error: '' });
  },
  async load(options = {}) {
    if (!this._visible || this._disposed) return;
    const currentScope = scope(), post = auth.currentApiPost();
    if (!http.token() || !post || !currentScope || String(post.postId) !== String(http.currentPostId())) return this.clear('请先登录并选择工作身份');
    if (!this.commissionId) return this.clear('移交链接无效，请从客户或工程待办重新进入');
    if (currentScope !== this._scope) { this.clear(); this._scope = currentScope; }
    if (this.data.busy || this.data.uploading || this.data.uncertain) return;
    const request = this._read = { scope: currentScope };
    const current = () => this._visible && this._read === request && this.alive(request.scope);
    this._preview = null; this.setData({ loading: true, error: '' });
    try {
      const result = await api.get(this.commissionId); if (!current()) return;
      const context = model.detail(result, this.commissionId, model.id(post.orgId), post);
      if (this.data.context && context.revision < this.data.context.revision) return this.setData({ loading: false });
      let source = null, receivers = [], receiverError = '';
      if (context.canSubmit) {
        const values = await Promise.all([api.source(context.customerId), api.receivers(this.commissionId).then(rows => ({ rows })).catch(error => ({ error }))]);
        if (!current()) return;
        if (values[1].error && revoked(values[1].error)) throw values[1].error;
        source = model.source(values[0], context, model.id(post.orgId));
        if (values[1].error) receiverError = message(values[1].error);
        else receivers = model.receivers(values[1].rows, post);
      }
      const keep = !options.replace && this.data.editing
        && (context.canSubmit && this.data.context?.canSubmit || (context.canReceive || context.canReturn) && (this.data.context?.canReceive || this.data.context?.canReturn));
      if (!keep) this.apply(context, source, receivers, receiverError);
      else this.setData({ ready: true, loading: false, context, source, receivers, receiverError,
        receiverIndex: receivers.findIndex(person => person.userId === this.data.form.managerUserId), conflict: context.revision !== this._baseRevision });
    } catch (error) {
      if (!current()) return;
      if (revoked(error)) return this.clear(message(error));
      if (Number(error?.code) === 1099000002) return this.clear(message(error));
      this.setData({ loading: false, conflict: !!this.data.ready, error: message(error) });
    }
  },
  editable() {
    const context = this.data.context, post = auth.currentApiPost();
    return this.alive() && this.data.editing && !!context
      && (context.canSubmit && model.canSubmit(post) || (context.canReceive || context.canReturn) && model.canDecide(post))
      && !this.data.busy && !this.data.uploading && !this.data.uncertain && !this.data.conflict;
  },
  input(event) {
    const key = event.currentTarget.dataset.key;
    if (!this.editable() || !(this.data.context.canSubmit ? ['confirmationDate', 'note'] : ['reason']).includes(key)) return;
    this.setData({ form: { ...this.data.form, [key]: String(event.detail.value ?? '') }, dirty: true, error: '' });
  },
  toggleConfirmed() { if (this.editable()) this.setData({ form: { ...this.data.form, confirmed: !this.data.form.confirmed }, dirty: true, error: '' }); },
  pickReceiver(event) {
    if (!this.editable() || !this.data.context.canSubmit) return;
    const index = Number(event.detail.value), person = this.data.receivers[index]; if (!person) return;
    this.setData({ form: { ...this.data.form, managerUserId: person.userId }, receiverIndex: index, dirty: true, error: '' });
  },
  changeTab(event) {
    const tab = event.currentTarget.dataset.tab;
    if (this.alive() && ['current', 'history'].includes(tab) && !this.data.busy && !this.data.uploading && !this.data.uncertain) this.setData({ tab });
  },
  toggleHistory(event) {
    const version = Number(event.currentTarget.dataset.version);
    if (this.alive() && this.data.context?.packets.some(item => item.version === version)) this.setData({ historyVersion: this.data.historyVersion === version ? 0 : version });
  },
  async submit(event) {
    if (!this.editable()) return;
    const action = event?.currentTarget?.dataset?.action;
    if (!['submit', 'return', 'receive'].includes(action)) return;
    if (this._pendingFile) return this.setData({ error: '请先完成客户确认凭据上传，或取消本次选择' });
    try {
      if (action === 'submit' && (!this.data.source || this.data.source.resultVersion !== this.data.context.resultVersion)) throw Error('请先核对当前外部成果');
      const context = { ...this.data.context, revision: this._baseRevision };
      const body = model.payload(action, this.data.form, context, this.data.receivers, day(Date.now()));
      this._pending = { scope: this._scope, commissionId: this.commissionId, action, body: model.copy(body), key: commandKey('external-handover') };
      return this.runPending();
    } catch (error) { this.setData({ error: message(error) }); }
  },
  async runPending() {
    const command = this._pending;
    if (!command || this.data.busy || !this.alive(command.scope) || command.commissionId !== this.commissionId) return;
    this._read = null; this._preview = null; this.setData({ busy: true, uncertain: false, error: '' });
    try {
      const result = await api[command.action](command.commissionId, command.body, command.key);
      if (!this.alive(command.scope) || this._pending !== command) return;
      const context = model.detail(result, this.commissionId, model.id(auth.currentApiPost().orgId), auth.currentApiPost());
      if (context.revision <= command.body.revision) throw Error('办理结果尚未确认');
      this._pending = null; this.setData({ busy: false, uncertain: false }); this.apply(context);
      wx.showToast({ title: { submit: '已提交工程接收', return: '已退回业务补充', receive: '已接收并建立工程档案' }[command.action], icon: 'none' });
    } catch (error) {
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (revoked(error)) return this.clear(message(error));
      if (Number(error?.code) === 1099000002) return this.recheckAccess(error);
      const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code === 1099000000 || code >= 500 && code < 600;
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '办理结果尚未确认，请用原请求重试核对' : message(error) });
    }
  },
  async recheckAccess(error) {
    const savedScope = this._scope; this.clear(message(error)); await this.load({ replace: true });
    if (this.alive(savedScope) && this.data.ready) this.setData({ error: '移交或文件权限已变化，请核对最新记录后办理' });
  },
  reloadLatest() {
    if (!this.alive() || this.data.busy || this.data.uploading || this.data.uncertain) return;
    if (!this.data.dirty && !this._pendingFile) return this.load({ replace: true });
    const savedScope = this._scope;
    wx.showModal({ title: '载入最新移交', content: '当前未保存的填写和文件选择将被最新记录替换。', success: result => {
      if (result.confirm && this.alive(savedScope) && !this.data.busy && !this.data.uploading && !this.data.uncertain) this.load({ replace: true });
    } });
  },
  openProject() {
    const context = this.data.context;
    if (this.alive() && context?.canOpenProject && context.projectId && context.currentPacket?.status === 'ACCEPTED' && !this.data.busy && !this.data.uncertain)
      navigation.open('/pages/build/record/index?id=' + encodeURIComponent(context.projectId) + '&view=overview');
  },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  navigationBack() {
    if (this.data.busy || this.data.uploading || this.data.uncertain) return wx.showToast({ title: this.data.uncertain ? '请先核对办理结果' : '请等待当前操作完成', icon: 'none' });
    const savedScope = this._scope, back = () => { if (this.alive(savedScope) && !this.data.busy && !this.data.uploading && !this.data.uncertain) navigation.back({ skipLocal: true, fallback: '/pages/business/index' }); };
    if (!this.data.dirty && !this._pendingFile) return back();
    wx.showModal({ title: '有未保存的填写', content: '离开将丢弃当前未保存的内容。', confirmText: '离开', success: result => { if (result.confirm) back(); } });
  }
});
