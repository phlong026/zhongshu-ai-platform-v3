const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('../../../utils/api-scope');
const navigation = require('../../../utils/page-navigation');
const model = require('./model');
const base = project => '/franchise/finance/workbench/projects/' + encodeURIComponent(project) + '/accounting';
const text = error => String(error?.msg || error?.message || '加载失败，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404, 1102000018].includes(Number(error?.code));
const canRead = post => post?.orgType === 'store' && ['boss', 'owner', 'finance', 'finance-lead'].includes(post.roleCode);
const canWrite = post => canRead(post) && ['finance','finance-lead'].includes(post.roleCode);
Page({
  data: { ready: false, loading: false, row: null, error: '', history: [], historyError: '', historyLoading: false,
    historyMore: false, note: '', checked: false, busy: false, uncertain: false, conflict: false },
  onLoad(options) { this.projectId = model.id(options.projectId); },
  onShow() { this._visible = true; return this.load(); },
  onHide() {
    this._visible = false; this._read = null; this._historyRead = null;
    this.setData({ ready: false, loading: false, row: null, history: [], historyMore: false, historyLoading: false,
      busy: false, uncertain: !!this._pending });
  },
  onUnload() { this._disposed = true; this.onHide(); this._pending = null; },
  alive(saved = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && this._visible && !!http.token() && !!saved && saved === scope() && canRead(post)
      && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._historyRead = null; this._pending = null;
    this.setData({ ready: false, loading: false, row: null, history: [], historyMore: false, historyLoading: false,
      note: '', checked: false, busy: false, uncertain: false, conflict: false, error });
  },
  async load() {
    if (!this._visible || this._disposed) return;
    const saved = scope(), post = auth.currentApiPost();
    if (!this.projectId) return this.clear('项目链接无效，请从项目账本重新进入');
    if (!http.token() || !saved || !canRead(post) || String(post.postId) !== String(http.currentPostId())) return this.clear('请使用本公司老板或财务身份查看项目核算');
    if (saved !== this._scope) { this.clear(); this._scope = saved; }
    if (this.data.busy) return;
    const request = this._read = {}; this._historyRead = null;
    this.setData({ loading: true, historyLoading: false, error: '' });
    try {
      const response = await http.get(base(this.projectId));
      if (this._read !== request || !this.alive(saved)) return;
      const row = model.view(response, this.projectId); row.canConfirm = row.canConfirm && canWrite(auth.currentApiPost());
      this.setData({ row, ready: true, loading: false, conflict: false, checked: false, uncertain: !!this._pending, error: this._pending ? '上一次核算确认结果尚未核实，请使用原请求重试。' : '' });
      this._pageNo = 0; this.setData({ history: [], historyMore: true });
      return this.moreHistory();
    } catch (error) {
      if (this._read !== request || !this.alive(saved)) return;
      if (revoked(error)) this.clear(text(error));
      else this.setData({ loading: false, error: text(error) });
    }
  },
  async moreHistory() {
    if (!this.alive() || !this.data.ready || this.data.loading || this.data.historyLoading || !this.data.historyMore) return;
    const request = this._historyRead = {}, saved = this._scope, pageNo = this._pageNo + 1;
    this.setData({ historyLoading: true, historyError: '' });
    try {
      const response = await http.get(base(this.projectId) + '/snapshots', { pageNo, pageSize: 20 });
      if (this._historyRead !== request || !this.alive(saved)) return;
      const result = model.history(response, this.projectId, pageNo), rows = [...this.data.history, ...result.rows];
      this._pageNo = pageNo; this.setData({ history: rows, historyMore: rows.length < result.total, historyLoading: false });
    } catch (error) {
      if (this._historyRead !== request || !this.alive(saved)) return;
      if (revoked(error)) this.clear(text(error));
      else this.setData({ historyLoading: false, historyError: text(error) });
    }
  },
  editable() { return this.alive() && this.data.row?.canConfirm && this.data.row.state !== 'CURRENT' && !this.data.busy && !this.data.uncertain && !this.data.conflict; },
  input(event) { if (this.editable()) this.setData({ note: String(event.detail.value || ''), error: '' }); },
  check(event) { if (this.editable()) this.setData({ checked: (event.detail.value || []).includes('complete'), error: '' }); },
  submit() {
    if (!this.editable()) return;
    const note = this.data.note.trim();
    if (!this.data.checked || !note || note.length > 1000) return this.setData({ error: '请确认费用已完整归集，并填写核算说明。' });
    this._pending = { scope: this._scope, body: { sourceHash: this.data.row.sourceHash, expectedVersion: this.data.row.version, costsComplete: true, note },
      key: 'project-accounting-' + Date.now() + '-' + Math.random().toString(36).slice(2) };
    return this.retry();
  },
  async retry() {
    const command = this._pending;
    if (!command || !this.alive(command.scope) || !canWrite(auth.currentApiPost()) || this.data.busy) return;
    this._read = null; this._historyRead = null;
    this.setData({ busy: true, uncertain: false, error: '', historyLoading: false });
    try {
      const result = await http.post(base(this.projectId) + '/confirm', command.body, { 'Idempotency-Key': command.key });
      if (this._pending !== command || !this.alive(command.scope)) return;
      const record = model.snapshot(result, this.projectId);
      if (record.version !== command.body.expectedVersion + 1 || record.sourceHash !== command.body.sourceHash) throw Error('确认结果尚未核实，请用原请求重试');
      this._pending = null; this.setData({ busy: false, uncertain: false, note: '', checked: false });
      wx.showToast({ title: '核算版本已保存', icon: 'none' }); return this.load();
    } catch (error) {
      if (this._pending !== command || !this.alive(command.scope)) return;
      if (revoked(error)) return this.clear(text(error));
      const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600 || code === 1099000000;
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001,
        error: uncertain ? '确认结果尚未核实，请使用原请求重试核对。' : text(error) });
    }
  },
  back() { navigation.back(); },
  login() { navigation.open('/pages/account/index'); },
  ledger() {
    if (!this.alive()) return;
    const route = canWrite(auth.currentApiPost())
      ? 'view=book&bookScope=' + encodeURIComponent('project:' + this.projectId)
      : 'view=project&id=' + encodeURIComponent(this.projectId);
    navigation.open('/pages/admin/finance-desk/index?' + route);
  }
});
