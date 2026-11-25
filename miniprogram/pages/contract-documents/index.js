const http = require('../../api/http');
const auth = require('../../api/auth');
const scope = require('../../utils/api-scope');
const navigation = require('../../utils/page-navigation');
const { commandKey } = require('../../utils/platform-crm');
const { day } = require('../../utils/finance-date');
const model = require('./model');
const api = require('./api');
const errorText = e => String(e?.msg || e?.message || '操作未完成，请重试');
const revoked = e => e?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1113000004].includes(Number(e?.code));

Page({
  data: { loading: false, ready: false, context: null, form: null, today: '', editing: false, dirty: false,
    busy: false, uploading: false, uploadFailed: false, uploadRetryable: true, uploadRestartAllowed: false, uploadName: '', uncertain: false, conflict: false, error: '' },
  onLoad(options = {}) { this.contractId = model.id(options.contractId); this.setData({ today: day(Date.now()) }); },
  onShow() { this._visible = true; this.setData({ today: day(Date.now()) }); return this.load(); },
  onHide() { this._visible = false; this._read = null; this._preview = null; },
  onUnload() { this._disposed = true; this.onHide(); this._pending = null; this._pendingFile = null; this._localFile = null; },
  alive(savedScope = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && !!http.token() && !!savedScope && savedScope === scope() && post && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._preview = null; this._pending = null; this._pendingFile = null; this._localFile = null;
    this.setData({ loading: false, ready: false, context: null, form: null, editing: false, dirty: false, busy: false,
      uploading: false, uploadFailed: false, uploadRestartAllowed: false, uploadName: '', uncertain: false, conflict: false, error });
  },
  async load(options = {}) {
    if (!this._visible || this._disposed) return;
    const currentScope = scope(), post = auth.currentApiPost();
    if (!http.token() || !post || !currentScope || String(post.postId) !== String(http.currentPostId())) return this.clear('请先登录并选择工作身份');
    if (!this.contractId) return this.clear('合同链接无效，请从合同页重新进入');
    if (currentScope !== this._scope) { this.clear(); this._scope = currentScope; }
    if (this.data.busy || this.data.uploading) return;
    const request = this._read = { scope: currentScope };
    const current = () => this._visible && this._read === request && this.alive(request.scope);
    this.setData({ loading: true, error: '' });
    try {
      const result = await api.get(this.contractId); if (!current()) return;
      const context = model.detail(result, this.contractId, String(post.orgId), post);
      if (this.data.context && context.revision < this.data.context.revision) { this.setData({ loading: false }); return; }
      const keep = !options.replace && this.data.dirty && context.canWrite;
      if (!keep) { this._pendingFile = null; this._localFile = null; this._baseRevision = context.revision; }
      this.setData({ loading: false, ready: true, context, form: keep ? this.data.form : model.form(context),
        editing: context.canWrite && (keep ? this.data.editing : context.status === 'WAIT_SIGN'), dirty: keep,
        uploadFailed: keep && this.data.uploadFailed, uploadName: keep ? this.data.uploadName : '',
        conflict: keep && this._baseRevision !== context.revision });
    } catch (e) { if (current()) { if (revoked(e)) this.clear(errorText(e)); else this.setData({ loading: false, error: errorText(e) }); } }
  },
  editable() { return this.alive() && model.canWrite(auth.currentApiPost()) && this.data.context?.canWrite && this.data.editing
    && !this.data.busy && !this.data.uploading && !this.data.uncertain && !this.data.conflict; },
  startEdit() {
    if (!this.alive() || !this.data.context?.canWrite || !model.canWrite(auth.currentApiPost()) || this.data.busy || this.data.uncertain) return;
    this._baseRevision = this.data.context.revision;
    this.setData({ editing: true, form: model.form(this.data.context), dirty: false, conflict: false, error: '' });
  },
  input(e) {
    if (!this.editable() || !['signedDate', 'reason'].includes(e.currentTarget.dataset.key)) return;
    this.setData({ form: { ...this.data.form, [e.currentTarget.dataset.key]: String(e.detail.value || '') }, dirty: true, error: '' });
  },
  chooseFile() {
    if (!this.editable()) return; const savedScope = this._scope;
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'],
      success: result => { if (this.alive(savedScope) && this.editable() && result.tempFiles?.[0]) this.startUpload(result.tempFiles[0]); },
      fail: e => { if (this.alive(savedScope) && !/cancel/i.test(e.errMsg || '')) this.setData({ error: '文件选择失败，请重试' }); } });
  },
  choosePhoto() {
    if (!this.editable()) return; const savedScope = this._scope;
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'],
      success: result => { if (this.alive(savedScope) && this.editable() && result.tempFiles?.[0]) this.startUpload(result.tempFiles[0]); },
      fail: e => { if (this.alive(savedScope) && !/cancel/i.test(e.errMsg || '')) this.setData({ error: '照片选择失败，请重试' }); } });
  },
  startUpload(file) { if (!this.editable()) return; this._read = null; this._pendingFile = { file, scope: this._scope }; return this.retryUpload(); },
  async retryUpload() {
    const pending = this._pendingFile;
    if (!pending || !this.editable() || !this.alive(pending.scope) || pending.rejected) return;
    const alive = () => this.alive(pending.scope) && this._pendingFile === pending;
    this.setData({ uploading: true, uploadFailed: false, uploadRestartAllowed: false, uploadName: pending.file.name || '已签合同照片', error: '' });
    try {
      const file = await require('./upload').run(pending, alive); if (!alive()) return;
      this._localFile = { ...file, path: pending.file.path || pending.file.tempFilePath }; this._pendingFile = null;
      this.setData({ form: { ...this.data.form, assetId: file.assetId, fileName: file.fileName }, dirty: true,
        uploading: false, uploadFailed: false, uploadName: '' });
    } catch (e) {
      if (!alive()) return;
      if (revoked(e) || Number(e?.code) === 1099000002) return this.clear(errorText(e));
      this.setData({ uploading: false, uploadFailed: true, uploadRetryable: !pending.rejected, uploadRestartAllowed: !!pending.restartRequired,
        error: pending.restartRequired ? '文件尚未上传完成，可再次核对，或重新上传获取新凭据' : errorText(e) });
    }
  },
  restartUpload() {
    const pending = this._pendingFile;
    if (!this.editable() || !pending?.restartRequired || !this.alive(pending.scope)) return;
    this._pendingFile = { file: pending.file, scope: pending.scope, bytes: pending.bytes, mimeType: pending.mimeType };
    return this.retryUpload();
  },
  discardUpload() { if (!this.editable()) return; this._pendingFile = null; this.setData({ uploadFailed: false, uploadRestartAllowed: false, uploadName: '', error: '' }); },
  async previewFile() {
    if (!this.alive() || !this.data.form?.assetId) return;
    const assetId = this.data.form.assetId, request = this._preview = { scope: this._scope };
    const alive = () => this.alive(request.scope) && this._preview === request && this.data.form?.assetId === assetId;
    try {
      let path, mime;
      if (this._localFile?.assetId === assetId) { path = this._localFile.path; mime = this._localFile.mimeType; }
      else {
        const ticket = await api.download(this.contractId, assetId); if (!alive()) return;
        const file = await require('../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '合同文件' });
        if (!alive()) return; path = file.tempFilePath; mime = file.contentType;
      }
      if (/^image\//i.test(mime || '')) wx.previewImage({ urls: [path] });
      else wx.openDocument({ filePath: path, fileType: 'pdf', showMenu: true, fail: () => { if (alive()) this.setData({ error: '合同文件暂不能预览，请重新下载' }); } });
    } catch (e) {
      if (!alive()) return; this._preview = null;
      if (revoked(e)) return this.clear(errorText(e));
      if (Number(e?.code) === 1099000002) {
        const savedScope = this._scope;
        this.clear(errorText(e)); await this.load({ replace: true });
        if (this.alive(savedScope) && this.data.ready) {
          const replaced = this.data.form.assetId && this.data.form.assetId !== assetId;
          if (!replaced) this.invalidateAsset();
          this.setData({ error: replaced ? '合同文件已更新，请查看当前文件' : '当前合同文件不可用，请重新上传有效凭证' });
        }
        return;
      }
      this.setData({ error: errorText(e) });
    }
  },
  invalidateAsset() {
    this._localFile = null; this._preview = null;
    this.setData({ context: { ...this.data.context, assetId: '', hasEvidence: false }, form: { ...this.data.form, assetId: '', fileName: '' } });
  },
  async submit() {
    if (!this.editable()) return;
    if (this._pendingFile) return this.setData({ error: '请先完成文件上传，或取消本次选择' });
    try {
      const context = { ...this.data.context, revision: this._baseRevision }, body = model.payload(this.data.form, context, day(Date.now()));
      this._pending = { scope: this._scope, action: context.status === 'WAIT_SIGN' ? 'sign' : 'evidence', body, key: commandKey('contract-evidence') };
      return this.runPending();
    } catch (e) { this.setData({ error: errorText(e) }); }
  },
  async runPending() {
    const command = this._pending;
    if (!command || this.data.busy || !this.alive(command.scope)) return;
    this._read = null; this._preview = null; this.setData({ busy: true, uncertain: false, error: '' });
    try {
      const result = await api[command.action](this.contractId, command.body, command.key);
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (model.id(result?.contractId) !== this.contractId || !['SIGNED', 'VOIDED'].includes(result.status)
          || !Number.isSafeInteger(result.revision) || result.revision <= command.body.revision) throw Error('合同结果尚未确认');
      this._pending = null; this._pendingFile = null; this._localFile = null;
      this.setData({ busy: false, uncertain: false, dirty: false, editing: false, context: null, form: null, ready: false, conflict: false });
      wx.showToast({ title: result.status !== 'SIGNED' ? '合同状态已变化' : command.action === 'sign' ? '签约已登记' : '合同凭证已更新', icon: 'none' });
      return this.load({ replace: true });
    } catch (e) {
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (revoked(e)) return this.clear(errorText(e));
      const code = Number(e?.code), uncertain = !Number.isFinite(code) || code === -1 || code === 1099000000 || code >= 500 && code < 600;
      if (!uncertain) this._pending = null;
      if (code === 1099000002) this.invalidateAsset();
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '办理结果尚未确认，请用原请求重试核对' : errorText(e) });
    }
  },
  reloadLatest() {
    if (this.data.busy || this.data.uploading || this.data.uncertain) return;
    if (!this.data.dirty && !this._pendingFile) return this.load({ replace: true });
    const savedScope = this._scope;
    wx.showModal({ title: '载入最新合同', content: '当前未保存的日期、文件和说明将被最新记录替换。', success: r => { if (r.confirm && this.alive(savedScope)) this.load({ replace: true }); } });
  },
  openPlan() { if (this.alive() && !this.data.busy && !this.data.uploading) navigation.open('/pages/workflow/payment-plan/index?contractId=' + encodeURIComponent(this.contractId)); },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  navigationBack() {
    if (this.data.busy || this.data.uploading || this.data.uncertain) return wx.showToast({ title: this.data.uncertain ? '请先核对办理结果' : '请等待当前操作完成', icon: 'none' });
    const back = () => navigation.back({ skipLocal: true, fallback: '/pages/workflow/index?view=contract&id=' + encodeURIComponent(this.data.context?.customerId || '') });
    if (!this.data.dirty && !this._pendingFile) return back();
    wx.showModal({ title: '有未保存的签约资料', content: '离开将丢弃本页未保存的填写。', confirmText: '离开', success: r => { if (r.confirm) back(); } });
  }
});
