const http = require('../../api/http');
const auth = require('../../api/auth');
const apiScope = require('../../utils/api-scope');
const navigation = require('../../utils/page-navigation');
const api = require('./api');
const model = require('./model');
const visitEntry = require('./visit-entry');
const commandKey = require('../../utils/platform-crm').commandKey;
const scope = () => http.token() ? JSON.stringify([apiScope(), auth.currentApiPost()?.brandId || '']) : '';
const clone = value => JSON.parse(JSON.stringify(value));
const accessRevoked = error => error.postInvalid || [401, 403, 404, 1110000000, 1110000001].includes(Number(error.code));

Page({
  ...visitEntry.actions,
  data: { workflow: null, step: null, form: null, fields: [], facts: [], loading: false, refreshing: false,
    error: '', busy: false, dirty: false, conflict: false, uncertain: false, uploading: false, uploadFailed: false, uploadRetryable: true, uploadRestartAllowed: false,
    uploadName: '', journeyOpen: false, nextStepLabel: '', showHistory: false, records: [], historyLoading: false, historyError: '', historyMore: false, activeRecord: null },
  onLoad(options = {}) { this.customerId = model.id(options.customerId); this.selectedCode = model.definition(options.step)?.code || ''; this._drafts = {}; this._localFiles = {}; },
  onShow() { this._visible = true; return this.load(); },
  onHide() { this._visible = false; this._request = null; this._historyRequest = null; },
  onUnload() { this._disposed = true; this.onHide(); this._drafts = {}; this._pendingFile = null; this._localFiles = {}; },
  clear(error = '') {
    this._visitEntry = null;
    this._request = null; this._historyRequest = null; this._previewRequest = null; this._pending = null; this._pendingFile = null; this._drafts = {}; this._localFiles = {};
    this.setData({ workflow: null, step: null, form: null, fields: [], facts: [], loading: false, refreshing: false,
      visitSites: [], visitTimes: [], visitFeedback: [], visitSiteMore: false, visitSiteLoading: false, visitSiteError: '',
      busy: false, dirty: false, conflict: false, uncertain: false, uploading: false, uploadFailed: false, uploadRetryable: true, uploadRestartAllowed: false, uploadName: '',
      journeyOpen: false, nextStepLabel: '', showHistory: false, records: [], historyLoading: false, historyError: '', historyMore: false, activeRecord: null, error });
  },
  validPost() {
    const post = auth.currentApiPost();
    return !!(http.token() && post && http.currentPostId() && String(post.postId) === String(http.currentPostId()));
  },
  alive(savedScope = this._scope) { return !this._disposed && !!savedScope && savedScope === scope() && this.validPost(); },
  current(request) { return this._visible && this._request === request && this.alive(request.scope); },
  async load(options = {}) {
    if (!this._visible || this._disposed) return;
    const currentScope = scope();
    if (!this.validPost()) { this._scope = ''; this.clear(http.token() ? '请先选择有效工作身份' : '请先登录后查看客户'); return; }
    if (!this.customerId) { this.clear('客户链接无效，请返回客户列表'); return; }
    if (this._scope !== currentScope) { this.clear(); this._scope = currentScope; }
    if (this.data.busy) return;
    const request = this._request = { scope: currentScope };
    this.setData({ loading: !this.data.workflow, refreshing: !!this.data.workflow, error: '' });
    try {
      const result = await api.get(this.customerId);
      if (!this.current(request)) return;
      if (this.data.workflow && result.revision < this.data.workflow.revision) { this.setData({ loading: false, refreshing: false }); return; }
      const showHistory = this.data.showHistory;
      this.applyWorkflow(result, { replace: options.replace === true });
      if (showHistory) { this.setData({ showHistory: true }); this.loadHistory(true); }
    } catch (error) {
      if (!this.current(request)) return;
      if (accessRevoked(error)) this.clear(model.errorText(error));
      else this.setData({ loading: false, refreshing: false, error: model.errorText(error) });
    }
  },
  applyWorkflow(response, options = {}) {
    const workflow = model.workflow(response, this.customerId);
    const code = model.definition(this.selectedCode)?.code || workflow.currentStepCode || (workflow.completedCount === 8 ? 'START' : 'INVITATION');
    if (options.replace) delete this._drafts[code];
    this.setData({ workflow, loading: false, refreshing: false, error: '' });
    this.select(code, options.replace);
  },
  select(code, replace = false) {
    const step = this.data.workflow?.steps.find(s => s.code === code);
    if (!step) return;
    const draft = replace ? null : this._drafts[code];
    this.selectedCode = code;
    const form = draft ? clone(draft.form) : model.form(step);
    if (!draft) visitEntry.defaultDate(step, form);
    this._formRevision = draft ? draft.revision : this.data.workflow.revision;
    const workflow = this.data.workflow;
    const nextCode = workflow.currentStepCode === code ? workflow.nextStepCode : workflow.currentStepCode;
    const nextStep = model.definition(nextCode) || workflow.steps.find(item => item.code !== code && item.status !== 'COMPLETED');
    this.setData({ step: { ...step, canReadEngineering: this.canReadEngineering() }, form, fields: model.fieldRows(code, form), facts: model.facts(step), dirty: !!draft,
      nextStepLabel: nextStep?.name || '', conflict: !!draft && draft.revision !== workflow.revision, showHistory: false, activeRecord: null, error: '' });
    visitEntry.prepare(this, step, draft, auth.currentApiPost(), http.currentUserId?.(), api);
  },
  journeyLocked() { return this.data.busy || this.data.uploading || this.data.uploadFailed || this.data.uncertain; },
  showJourney() {
    if (this.journeyLocked()) return;
    if (!this.alive()) return this.load();
    this.setData({ journeyOpen: true });
  },
  hideJourney() { this.setData({ journeyOpen: false }); },
  selectStep(event) {
    if (this.journeyLocked()) return;
    if (!this.alive()) return this.load();
    this.select(event.currentTarget.dataset.code);
    this.hideJourney();
  },
  editable() { return this.alive() && this.data.step?.canWrite && !this.data.busy && !this.data.uploading && !this.data.uncertain && !this.data.conflict; },
  change(form) {
    this._drafts[this.selectedCode] = { form: clone(form), revision: this._formRevision };
    this.setData({ form, fields: model.fieldRows(this.selectedCode, form), dirty: true, error: '' });
  },
  input(event) {
    if (!this.editable()) return;
    const key = event.currentTarget.dataset.key;
    const value = String(event.detail.value == null ? '' : event.detail.value);
    const form = clone(this.data.form);
    if (['occurredOn', 'note', 'appointmentDate', 'appointmentTime'].includes(key)) form[key] = value;
    else if (model.definition(this.selectedCode).fields.some(f => f.key === key && !['hidden', 'reference', 'boolean'].includes(f.type))) form.fields[key] = value;
    else return;
    this.change(form);
  },
  pick(event) {
    if (!this.editable()) return;
    const field = model.definition(this.selectedCode).fields.find(f => f.key === event.currentTarget.dataset.key);
    const selected = field?.options?.[Number(event.detail.value)];
    if (selected) { const form = clone(this.data.form); form.fields[field.key] = selected.value; this.change(form); }
  },
  toggle(event) {
    if (!this.editable()) return;
    const key = event.currentTarget.dataset.key;
    if (!model.definition(this.selectedCode).fields.some(f => f.key === key && f.type === 'boolean')) return;
    const form = clone(this.data.form); form.fields[key] = event.detail.value === true; this.change(form);
  },
  async submit(event) {
    if (!this.editable() || this.data.uploadFailed) return;
    const action = event?.currentTarget?.dataset?.action === 'complete' ? 'complete' : 'save';
    try {
      const body = model.payload(this.selectedCode, this.data.form, this._formRevision);
      this._pending = { scope: this._scope, code: this.selectedCode, action, stage: 'save', body, key: commandKey('workflow-save') };
      return this.runPending();
    } catch (error) { this.setData({ error: model.errorText(error) }); }
  },
  async runPending() {
    const pending = this._pending;
    if (!pending || this.data.busy || !this.alive(pending.scope)) return;
    this._request = null; this._historyRequest = null;
    this.setData({ busy: true, uncertain: false, refreshing: false, historyLoading: false, error: '' });
    try {
      let result;
      if (pending.stage === 'save') {
        result = await api.save(this.customerId, pending.code, pending.body, pending.key);
        if (!this.alive(pending.scope) || this._pending !== pending) return;
        const saved = model.workflow(result, this.customerId);
        delete this._drafts[pending.code];
        this.applyWorkflow(result, { replace: true });
        if (pending.action === 'complete') {
          pending.stage = 'complete'; pending.body = { revision: saved.revision }; pending.key = commandKey('workflow-complete');
        }
      }
      if (pending.stage === 'complete') result = await api.complete(this.customerId, pending.code, pending.body, pending.key);
      if (pending.stage === 'reopen') result = await api.reopen(this.customerId, pending.code, pending.body, pending.key);
      if (!this.alive(pending.scope) || this._pending !== pending) return;
      this._pending = null;
      delete this._drafts[pending.code];
      this.applyWorkflow(result, { replace: true });
      this.setData({ busy: false, uncertain: false });
      wx.showToast({ title: pending.stage === 'complete' ? '本步已完成' : pending.stage === 'reopen' ? '已重新办理' : '记录已保存', icon: 'none' });
    } catch (error) {
      if (!this.alive(pending.scope) || this._pending !== pending) return;
      const code = Number(error.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600;
      if (accessRevoked(error)) return this.clear(model.errorText(error));
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '保存结果尚未确认，请重试核对' : model.errorText(error) });
    }
  },
  reopen() {
    if (!this.alive() || !this.data.step?.canReopen || this.data.busy || this.data.uncertain) return;
    const savedScope = this._scope, code = this.selectedCode, revision = this.data.workflow.revision;
    wx.showModal({ title: '重新办理本步', editable: true, placeholderText: '填写更正原因',
      success: result => {
        if (!result.confirm || !this.alive(savedScope) || code !== this.selectedCode) return;
        const reason = String(result.content || '').trim();
        if (!reason) return this.setData({ error: '请填写更正原因' });
        this._pending = { scope: savedScope, code, action: 'reopen', stage: 'reopen', body: { revision, reason }, key: commandKey('workflow-reopen') };
        return this.runPending();
      } });
  },
  reloadLatest() {
    if (this.data.busy || this.data.uncertain) return;
    const savedScope = this._scope;
    if (!this.data.dirty) return this.load({ replace: true });
    wx.showModal({ title: '载入最新记录', content: '当前未保存的填写将被最新记录替换。', success: r => {
      if (r.confirm && this.alive(savedScope)) this.load({ replace: true });
    } });
  },
  chooseFile() {
    if (!this.editable() || this.data.form.attachments.length >= 10) return;
    const savedScope = this._scope, code = this.selectedCode;
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'],
      success: response => { if (this.alive(savedScope) && code === this.selectedCode && response.tempFiles?.[0]) this.startUpload(response.tempFiles[0]); },
      fail: error => { if (this.alive(savedScope) && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '文件选择失败，请重试' }); } });
  },
  choosePhoto() {
    if (!this.editable() || this.data.form.attachments.length >= 10) return;
    const savedScope = this._scope, code = this.selectedCode;
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'],
      success: response => { if (this.alive(savedScope) && code === this.selectedCode && response.tempFiles?.[0]) this.startUpload(response.tempFiles[0]); },
      fail: error => { if (this.alive(savedScope) && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '照片选择失败，请重试' }); } });
  },
  startUpload(file) { this._pendingFile = { file, scope: this._scope, code: this.selectedCode }; return this.retryUpload(); },
  async retryUpload() {
    const pending = this._pendingFile;
    if (!pending || !this.alive(pending.scope) || pending.code !== this.selectedCode || this.data.uploading) return;
    this.setData({ uploading: true, uploadFailed: false, uploadRestartAllowed: false, uploadName: pending.file.name || '现场照片', error: '' });
    const alive = () => this.alive(pending.scope) && this._pendingFile === pending && this.selectedCode === pending.code;
    try {
      const file = await require('./upload').run(pending, alive);
      if (!alive()) return;
      this._localFiles[file.assetId] = pending.file.path || pending.file.tempFilePath;
      const form = clone(this.data.form);
      if (!form.attachments.some(item => item.assetId === file.assetId)) form.attachments.push(file);
      this._pendingFile = null;
      this.setData({ uploading: false, uploadFailed: false, uploadRetryable: true, uploadRestartAllowed: false, uploadName: '' });
      this.change(form);
    } catch (error) { if (alive()) this.setData({ uploading: false, uploadFailed: true, uploadRetryable: !pending.rejected, uploadRestartAllowed: !!pending.restartRequired, error: model.errorText(error) }); }
  },
  restartUpload() {
    const pending = this._pendingFile;
    if (!this.editable() || !pending?.restartRequired || !this.alive(pending.scope) || pending.code !== this.selectedCode) return;
    this._pendingFile = { file: pending.file, bytes: pending.bytes, mimeType: pending.mimeType, scope: pending.scope, code: pending.code };
    return this.retryUpload();
  },
  discardUpload() { if (!this.data.uploading) { this._pendingFile = null; this.setData({ uploadFailed: false, uploadRestartAllowed: false, uploadName: '', error: '' }); } },
  removeFile(event) {
    if (!this.editable()) return;
    const form = clone(this.data.form); form.attachments = form.attachments.filter(f => f.assetId !== String(event.currentTarget.dataset.id)); this.change(form);
  },
  async previewFile(event) {
    if (!this.alive()) return;
    const assetId = String(event.currentTarget.dataset.id), record = this.data.activeRecord;
    const source = record || { stepCode: this.selectedCode, attachments: this.data.form?.attachments || [] };
    const attachment = source.attachments.find(f => f.assetId === assetId);
    if (!attachment) return;
    const preview = this._previewRequest = { scope: this._scope };
    const alive = () => this.alive(preview.scope) && this._previewRequest === preview;
    try {
      let localPath = this._localFiles[assetId], contentType = attachment.mimeType;
      if (!localPath) {
        const ticket = await api.download(this.customerId, source.stepCode, assetId);
        if (!alive()) return;
        const file = await require('../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '节点资料' });
        if (!alive()) return;
        localPath = file.tempFilePath; contentType = file.contentType || contentType;
      }
      if (/^image\//.test(contentType || '') || /\.(jpe?g|png)$/i.test(attachment.title)) wx.previewImage({ urls: [localPath] });
      else wx.openDocument({ filePath: localPath, showMenu: true, fail: () => { if (alive()) this.setData({ error: '文件暂不能预览，请重新下载' }); } });
    } catch (error) {
      if (!alive()) return;
      if (accessRevoked(error)) return this.clear(model.errorText(error));
      this.setData({ error: model.errorText(error) });
    }
  },
  chooseReference() {
    if (!this.editable() || this.selectedCode !== 'HOUSE_SELECTION') return;
    const savedScope = this._scope;
    wx.navigateTo({ url: '/design-migration/pages/library/index?pick=workflow&customerId=' + encodeURIComponent(this.customerId),
      events: { workflowReference: value => {
        if (!this.alive(savedScope) || !this.editable() || this.selectedCode !== 'HOUSE_SELECTION' || !model.id(value?.caseId)) return;
        const form = clone(this.data.form); form.fields.referenceCaseId = model.id(value.caseId); form.fields.referenceCaseName = String(value.title || '参考户型').slice(0, 200); this.change(form);
      } }, fail: () => { if (this.alive(savedScope)) this.setData({ error: '灵感库打开失败，请重试' }); } });
  },
  clearReference() {
    if (!this.editable()) return;
    const form = clone(this.data.form); form.fields.referenceCaseId = ''; form.fields.referenceCaseName = ''; this.change(form);
  },
  openBudget() { if (this.alive()) navigation.open('/design-migration/pages/budget/quick?customerId=' + encodeURIComponent(this.customerId)); },
  openSalesQuote() { if (this.alive()) navigation.open('/pages/workflow/index?view=budget&id=' + encodeURIComponent(this.customerId)); },
  openEntrust() {
    if (['MEASUREMENT', 'QUOTATION', 'START'].includes(this.selectedCode)) this.openRelated('/pages/external-entrust/index?customerId=' + encodeURIComponent(this.customerId));
  },
  openPaymentDraft() {
    if (this.selectedCode === 'QUOTATION') this.openRelated('/pages/workflow/payment-plan/index?customerId=' + encodeURIComponent(this.customerId) + '&draft=1');
  },
  openRelated(url) {
    if (!this.alive() || this.data.busy || this.data.uploading || this.data.uncertain) return;
    const savedScope = this._scope, go = () => {
      if (this.alive(savedScope) && !this.data.busy && !this.data.uploading && !this.data.uncertain) navigation.open(url);
    };
    if (!Object.keys(this._drafts || {}).length && !this._pendingFile) return go();
    wx.showModal({ title: '本步尚未保存', content: '继续办理不会保存或完成本步，请核对未保存的填写。', confirmText: '继续办理', success: result => { if (result.confirm && this.alive(savedScope)) go(); } });
  },
  openCustomer() { if (this.alive()) navigation.open('/pages/workflow/index?view=customer&id=' + encodeURIComponent(this.customerId)); },
  canReadEngineering() {
    const post = auth.currentApiPost();
    return post?.orgType === 'store' && ['engineer', 'project-manager', 'boss', 'owner', 'finance', 'finance-lead'].includes(post.roleCode)
      && (!Array.isArray(post.permissions) || post.permissions.includes('franchise:engineering:query'));
  },
  openSource() {
    if (!this.alive() || this.data.busy || this.data.uploading || this.data.uncertain) return;
    if (this.selectedCode === 'CONTRACT') return navigation.open('/pages/workflow/index?view=contract&id=' + encodeURIComponent(this.customerId));
    const projectId = model.id(this.data.step?.fields?.projectId);
    if (projectId && this.canReadEngineering()) navigation.open('/pages/build/index?view=overview&id=' + encodeURIComponent(projectId));
    else this.openEntrust();
  },
  toggleHistory() { if (!this.alive() || this.data.busy) return; this.setData({ showHistory: !this.data.showHistory, activeRecord: null }); if (this.data.showHistory) return this.loadHistory(true); },
  async loadHistory(reset = false) {
    if (!this.alive() || this.data.historyLoading && !reset) return;
    const request = this._historyRequest = { scope: this._scope }, pageNo = reset ? 1 : (this._historyPage || 0) + 1;
    this.setData({ historyLoading: true, historyError: '', ...(reset ? { records: [] } : {}) });
    try {
      const response = await api.records(this.customerId, pageNo);
      if (this._historyRequest !== request || !this.alive(request.scope)) return;
      const rows = model.records(response.list), merged = reset ? rows : this.data.records.concat(rows);
      const records = [...new Map(merged.map(row => [row.id, row])).values()];
      this._historyPage = pageNo;
      this.setData({ records, historyLoading: false, historyMore: records.length < Number(response.total || 0) && rows.length > 0 });
    } catch (error) {
      if (this._historyRequest !== request || !this.alive(request.scope)) return;
      if (accessRevoked(error)) return this.clear(model.errorText(error));
      this.setData({ historyLoading: false, historyError: model.errorText(error) });
    }
  },
  moreHistory() { if (this.data.historyMore) return this.loadHistory(false); },
  retryHistory() { return this.loadHistory(!this.data.records.length); },
  viewRecord(event) {
    const record = this.data.records.find(r => r.id === String(event.currentTarget.dataset.id));
    if (!record || !this.alive()) return;
    const value = model.form({ ...record, code: record.stepCode });
    const details = model.fieldRows(record.stepCode, value).map(f => ({ label: f.label,
      value: f.type === 'reference' ? value.fields.referenceCaseName || '未关联' : f.type === 'boolean' ? (f.value ? '是' : '否')
        : f.type === 'pick' ? f.optionLabel : String(f.value || '—') }));
    this.setData({ activeRecord: { ...record, details } });
  },
  closeRecord() { this.setData({ activeRecord: null }); },
  noop() {},
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  navigationBack() {
    if (this.data.activeRecord) return this.closeRecord();
    if (this.data.busy || this.data.uploading || this.data.uncertain) return wx.showToast({ title: this.data.uncertain ? '请先核对保存结果' : '请等待当前操作完成', icon: 'none' });
    const back = () => navigation.back({ skipLocal: true, fallback: '/pages/workflow/index?view=customer&id=' + encodeURIComponent(this.customerId || '') });
    if (!Object.keys(this._drafts).length && !this._pendingFile) return back();
    wx.showModal({ title: '有未保存的记录', content: '离开后将丢弃本页未保存的填写。', confirmText: '离开', success: r => { if (r.confirm) back(); } });
  }
});
