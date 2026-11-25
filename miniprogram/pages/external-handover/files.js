const http = require('../../api/http');
const auth = require('../../api/auth');
const api = require('./api');
const model = require('./model');
const message = error => String(error?.msg || error?.message || '文件操作失败，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1110000000, 1110000001, 1112000000, 1099000002].includes(Number(error?.code));
module.exports = {
  chooseFile() { this.pickFile(false); },
  choosePhoto() { this.pickFile(true); },
  pickFile(photo) {
    if (!this.editable() || !this.data.context.canSubmit || this._pendingFile) return;
    const savedScope = this._scope, edit = this._edit;
    const options = { count: 1, success: result => {
      if (this.alive(savedScope) && this._edit === edit && this.editable() && result.tempFiles?.[0]) this.startUpload(result.tempFiles[0]);
    }, fail: error => { if (this.alive(savedScope) && this._edit === edit && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '文件选择失败，请重试' }); } };
    if (photo) wx.chooseMedia({ ...options, mediaType: ['image'], sourceType: ['album', 'camera'] });
    else wx.chooseMessageFile({ ...options, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'] });
  },
  startUpload(file) {
    if (!this.editable() || !this.data.context.canSubmit || this._pendingFile) return;
    this._read = null; this._preview = null; this._pendingFile = { file, scope: this._scope, edit: this._edit }; return this.retryUpload();
  },
  async retryUpload() {
    const pending = this._pendingFile;
    if (!pending || !this.editable() || pending.rejected || !this.alive(pending.scope) || this._edit !== pending.edit) return;
    const alive = () => this.alive(pending.scope) && this._pendingFile === pending && this._edit === pending.edit;
    this.setData({ uploading: true, uploadFailed: false, uploadRestartAllowed: false, uploadName: pending.file.name || '客户确认照片', error: '' });
    try {
      const file = await require('./upload').run(pending, alive); if (!alive()) return;
      this._localFile = { assetId: file.assetId, path: pending.file.path || pending.file.tempFilePath, mimeType: file.mimeType };
      this._pendingFile = null;
      this.setData({ form: { ...this.data.form, confirmationAssetId: file.assetId }, confirmationTitle: file.fileName,
        dirty: true, uploading: false, uploadFailed: false, uploadName: '' });
    } catch (error) {
      if (!alive()) return;
      if (revoked(error)) return this.recheckAccess(error);
      this.setData({ uploading: false, uploadFailed: true, uploadRetryable: !pending.rejected, uploadRestartAllowed: !!pending.restartRequired,
        error: pending.restartRequired ? '文件尚未上传完成，可再次核对，或重新上传获取新凭据' : message(error) });
    }
  },
  restartUpload() {
    const pending = this._pendingFile;
    if (!this.editable() || !pending?.restartRequired || !this.alive(pending.scope)) return;
    this._pendingFile = { file: pending.file, scope: pending.scope, edit: pending.edit, bytes: pending.bytes, mimeType: pending.mimeType };
    return this.retryUpload();
  },
  discardUpload() { if (!this.editable()) return; this._pendingFile = null; this.setData({ uploadFailed: false, uploadRestartAllowed: false, uploadName: '', error: '' }); },
  async previewFile(event) {
    const data = event.currentTarget.dataset, assetId = model.id(data.assetId), version = Number(data.version), kind = data.kind, slot = data.slot;
    if (!this.alive() || !assetId || !this.data.context) return;
    const local = this._localFile?.assetId === assetId ? this._localFile : null;
    const referenced = (context, source) => kind === 'results'
      ? context.canSubmit && source?.resultVersion === version && source.files.some(file => file.assetId === assetId && file.slot === slot)
      : context.packets.some(record => record.version === version && record.files.some(file => file.assetId === assetId && file.slot === slot));
    if (!local && !referenced(this.data.context, this.data.source)) return;
    const request = this._preview = { scope: this._scope }, edit = this._edit;
    const alive = () => this._visible && this.alive(request.scope) && this._preview === request && (!local || this._edit === edit);
    try {
      let path = local?.path, mime = local?.mimeType;
      if (!local) {
        const ticket = kind === 'results' ? await api.sourceDownload(this.data.context.customerId, version, assetId) : await api.download(this.commissionId, version, slot);
        if (!alive()) return;
        const file = await require('../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '工程移交资料' });
        if (!alive()) return; path = file.tempFilePath; mime = file.contentType;
      }
      const fresh = await api.get(this.commissionId); if (!alive()) return;
      const post = auth.currentApiPost(), context = model.detail(fresh, this.commissionId, model.id(post.orgId), post);
      let source = null;
      if (kind === 'results' && context.canSubmit) {
        const result = await api.source(context.customerId); if (!alive()) return; source = model.source(result, context, model.id(post.orgId));
      }
      if (local ? !context.canSubmit : !referenced(context, source)) throw { code: 1099000002, msg: '移交资料访问范围已变化' };
      if (/^image\//i.test(mime || '')) wx.previewImage({ urls: [path] });
      else wx.openDocument({ filePath: path, fileType: 'pdf', showMenu: true, fail: () => { if (alive()) this.setData({ error: '文件暂不能预览，请重试' }); } });
    } catch (error) {
      if (!alive()) return;
      if (revoked(error)) return this.recheckAccess(error);
      try { await api.get(this.commissionId); if (!alive()) return; }
      catch (accessError) { if (alive() && revoked(accessError)) return this.recheckAccess(accessError); }
      if (alive()) { this._preview = null; this.setData({ error: message(error) }); }
    }
  }
};
