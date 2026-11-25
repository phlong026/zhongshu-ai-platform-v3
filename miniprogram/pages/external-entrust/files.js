const http = require('../../api/http');
const auth = require('../../api/auth');
const api = require('./api');
const model = require('./model');
const errorText = error => String(error?.msg || error?.message || '文件操作失败，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1099000002].includes(Number(error?.code));
module.exports = {
  slotsFor(data) { return model.slots.map(slot => ({ ...slot, assetId: model.id(data?.[slot.key]), title: this._localFiles?.[data?.[slot.key]]?.title || slot.label })); },
  chooseFile(event) { this.pickFile(event, false); },
  choosePhoto(event) { this.pickFile(event, true); },
  pickFile(event, photo) {
    if (!this.editable() || this._pendingFile) return;
    const slot = event.currentTarget.dataset.slot || 'attachments', savedScope = this._scope, edit = this._edit;
    if (slot === 'attachments' ? this.data.editing === 'results' || this.data.form.attachments.length >= 20 : this.data.editing !== 'results' || !model.slots.some(item => item.key === slot)) return;
    const options = { count: 1, success: result => { if (this.alive(savedScope) && this._edit === edit && this.editable() && result.tempFiles?.[0]) this.startUpload(result.tempFiles[0], slot); },
      fail: error => { if (this.alive(savedScope) && this._edit === edit && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '文件选择失败，请重试' }); } };
    if (photo) wx.chooseMedia({ ...options, mediaType: ['image'], sourceType: ['album', 'camera'] });
    else wx.chooseMessageFile({ ...options, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'] });
  },
  startUpload(file, slot = 'attachments') {
    if (!this.editable() || this._pendingFile) return;
    if (slot === 'attachments' ? this.data.editing === 'results' || this.data.form.attachments.length >= 20 : this.data.editing !== 'results' || !model.slots.some(item => item.key === slot)) return;
    this._read = null; this._pendingFile = { file, slot, scope: this._scope, edit: this._edit }; return this.retryUpload();
  },
  async retryUpload() {
    const pending = this._pendingFile;
    if (!pending || !this.editable() || pending.rejected || !this.alive(pending.scope) || this._edit !== pending.edit) return;
    const alive = () => this.alive(pending.scope) && this._pendingFile === pending && this._edit === pending.edit;
    this.setData({ uploading: true, uploadFailed: false, uploadRestartAllowed: false, uploadName: pending.file.name || '现场照片', error: '' });
    try {
      const file = await require('./upload').run(pending, alive); if (!alive()) return;
      const attachment = { assetId: file.assetId, title: file.fileName, kind: '', version: 0 };
      this._localFiles = { ...this._localFiles, [file.assetId]: { ...attachment, path: pending.file.path || pending.file.tempFilePath, mimeType: file.mimeType } };
      const form = { ...this.data.form };
      if (pending.slot === 'attachments') form.attachments = [...form.attachments.filter(item => item.assetId !== file.assetId), attachment];
      else form[pending.slot] = file.assetId;
      this._pendingFile = null;
      this.setData({ form, fileSlots: this.data.editing === 'results' ? this.slotsFor(form) : this.data.fileSlots,
        dirty: true, uploading: false, uploadFailed: false, uploadName: '' });
    } catch (error) {
      if (!alive()) return;
      if (revoked(error)) return this.recheckAccess(error);
      this.setData({ uploading: false, uploadFailed: true, uploadRetryable: !pending.rejected, uploadRestartAllowed: !!pending.restartRequired,
        error: pending.restartRequired ? '文件尚未上传完成，可再次核对，或重新上传获取新凭据' : errorText(error) });
    }
  },
  restartUpload() {
    const pending = this._pendingFile;
    if (!this.editable() || !pending?.restartRequired || !this.alive(pending.scope)) return;
    this._pendingFile = { file: pending.file, slot: pending.slot, scope: pending.scope, edit: pending.edit, bytes: pending.bytes, mimeType: pending.mimeType };
    return this.retryUpload();
  },
  discardUpload() { if (!this.editable()) return; this._pendingFile = null; this.setData({ uploadFailed: false, uploadRestartAllowed: false, uploadName: '', error: '' }); },
  removeFile(event) {
    if (!this.editable() || this._pendingFile || this.data.editing === 'results') return;
    const assetId = model.id(event.currentTarget.dataset.assetId), attachments = this.data.form.attachments.filter(file => file.assetId !== assetId);
    this._preview = null; if (this._localFiles) delete this._localFiles[assetId]; this.setData({ form: { ...this.data.form, attachments }, dirty: true });
  },
  async previewFile(event) {
    const data = event.currentTarget.dataset, assetId = model.id(data.assetId);
    if (!this.alive() || !assetId || !this.data.context) return;
    const local = this._localFiles?.[assetId], kind = data.kind, version = Number(data.version);
    const referenced = context => context?.history.some(record => record.kind === kind && record.version === version && record.attachments.some(file => file.assetId === assetId));
    if (!local && !referenced(this.data.context)) return;
    const request = this._preview = { scope: this._scope };
    const alive = () => this.alive(request.scope) && this._preview === request;
    try {
      let path = local?.path, mime = local?.mimeType;
      if (!local) {
        const ticket = await api.download(this.customerId, kind, version, assetId); if (!alive()) return;
        const file = await require('../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '委托资料' });
        if (!alive()) return; path = file.tempFilePath; mime = file.contentType;
      }
      const fresh = await api.get(this.customerId); if (!alive()) return;
      const context = model.detail(fresh, this.customerId, model.id(auth.currentApiPost().orgId), auth.currentApiPost());
      if (!local && !referenced(context) || local && !this.allowed(this.data.editing, context)) throw { code: 1099000002, msg: '文件访问范围已变化' };
      if (/^image\//i.test(mime || '')) wx.previewImage({ urls: [path] });
      else wx.openDocument({ filePath: path, fileType: 'pdf', showMenu: true, fail: () => { if (alive()) this.setData({ error: '文件暂不能预览，请重试' }); } });
    } catch (error) {
      if (!alive()) return;
      if (revoked(error)) return this.recheckAccess(error);
      // Proxy download failures may be envelopes without a code; check customer access again before retaining the view.
      try { await api.get(this.customerId); if (!alive()) return; }
      catch (accessError) { if (alive() && revoked(accessError)) return this.recheckAccess(accessError); }
      if (alive()) { this._preview = null; this.setData({ error: errorText(error) }); }
    }
  }
};
