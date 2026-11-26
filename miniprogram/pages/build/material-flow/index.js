const api = require('../../../api/franchise/engineering');
const auth = require('../../../api/auth');
const http = require('../../../api/http');
const { day } = require('../../../utils/finance-date');

const kinds = [
  { code: 'CONSUME', name: '领用耗用' }, { code: 'DAMAGE', name: '报损' },
  { code: 'RETURN', name: '退回供应商' }, { code: 'SURPLUS_TRANSFER', name: '余料结转' }
];
const kindNames = Object.fromEntries(kinds.map(k => [k.code, k.name]));
const qualityOptions = ['合格', '不合格'];
function id(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const str = String(value ?? '');
  return /^[1-9]\d{0,18}$/.test(str) && (str.length < 19 || str <= '9223372036854775807') ? str : '';
}
function thousandths(value) {
  const parts = /^(\d{1,8})(?:\.(\d{1,3}))?$/.exec(String(value ?? '').trim());
  if (!parts) return -1;
  const result = Number(parts[1]) * 1000 + Number((parts[2] || '').padEnd(3, '0'));
  return result <= 10000000000 ? result : -1;
}
const initial = { line: null, editable: false, history: [], qualityRows: [], reships: [], receivers: [],
  quantity: '', note: '', kindIndex: 0, receiverIndex: 0, selectedReship: '', arrivalQuantity: '',
  transaction: '', arrivalNote: '', arrivalDate: '', qualityIndex: 0, photo: '', error: '', saving: false, uncertain: false };
const pendingKey = (post, page) => ['zs-material-flow-v1', post.tenantId, post.orgId, post.userId, post.postId,
  page.project, page.material].join(':');

Page({
  data: { ...initial, loading: true, kinds, qualityOptions },
  onLoad(options) {
    this.project = id(options.project); this.plan = id(options.plan); this.material = id(options.material);
  },
  onShow() { this.active = true; return this.load(); },
  onHide() { this.active = false; },
  onUnload() { this.active = false; this.closed = true; },
  async load() {
    const post = auth.currentApiPost() || {};
    const key = pendingKey(post, this);
    if (this.cacheKey !== key) {
      this.cacheKey = key; this.ticket = null; this.setData({ ...initial });
    }
    const valid = this.scopeValid = http.sessionGuard(), version = this.version = (this.version || 0) + 1;
    const alive = () => this.active && !this.closed && valid() && this.version === version;
    this.setData({ loading: true, line: null, editable: false, history: [], qualityRows: [], reships: [], error: '' });
    try {
      if (!this.project || !this.plan || !this.material || !post.postId) throw Error('请选择工作身份并从材料计划打开原单');
      const [project, summary] = await Promise.all([api.projects.get(this.project), api.materials.summary(this.project, this.plan)]);
      if (!alive()) return;
      const plan = summary?.plans?.find(item => id(item.materialPlanId) === this.plan);
      const line = plan?.lines?.find(item => id(item.materialId) === this.material);
      if (id(project?.id) !== this.project || id(summary?.projectId) !== this.project || !id(line?.supplyId)
          || !Array.isArray(line.movements) || !Array.isArray(line.pendingReships) || !Array.isArray(line.receipts)
          || thousandths(line.availableQuantity) < 0 || thousandths(line.consumedQuantity) < 0) {
        throw Error('当前岗位无法读取这份材料原单');
      }
      const editable = post.orgType === 'store' && ['engineer','project-manager'].includes(post.roleCode)
        && [id(project.managerUserId), id(project.assignedEngineerUserId)].includes(id(post.userId))
        && !!id(post.userId) && !line.cancelled && ['PENDING_START','IN_PROGRESS','READY_DELIVER'].includes(project.status);
      const receivers = [{ id: '', name: '未指定' }];
      for (const [userId, name] of [[project.managerUserId, project.managerName || '项目负责人'],
        [project.assignedEngineerUserId, project.assignedEngineerName || '现场工程师']]) {
        if (id(userId) && !receivers.some(r => r.id === id(userId))) receivers.push({ id: id(userId), name });
      }
      const stored = wx.getStorageSync(key);
      const targetMatches = stored?.action === 'movement' ? id(stored.body?.materialId) === this.material
        : stored?.action === 'reship' && !!id(stored.body?.parentReceiptId)
          && [...line.pendingReships, ...line.receipts].some(r => id(r.id) === id(stored.body.parentReceiptId));
      this.ticket = stored?.key && stored.material === this.material && stored.supply === id(line.supplyId) && targetMatches ? stored : null;
      const person = raw => receivers.find(r => r.id && r.id === id(raw))?.name || (id(raw) ? '人员编号 ' + id(raw) : '');
      const history = line.movements.map(m => ({ id: id(m.id), kind: kindNames[m.kind] || m.kind, quantity: m.quantity,
        date: day(m.occurredAt), note: m.note || '', author: person(m.authorUserId), receiver: person(m.receiverUserId),
        settlement: m.settlementStatus === 'PENDING_SETTLE' ? '待财务结算' : '已登记' }));
      const qualityRows = line.receipts.filter(r => r.quality === 'REJECTED' && !r.qualified && id(r.id)).map(r =>
        ({ id: id(r.id), quantity: r.actualQuantity, url: '/pages/build/issues/index?project=' + this.project + '&supply=' + id(line.supplyId) + '&receipt=' + id(r.id) }));
      this.setData({ line, history, receivers, qualityRows, editable, reships: line.pendingReships.filter(r => id(r.id)),
        loading: false, uncertain: !!this.ticket, receiverIndex: this.data.receiverIndex < receivers.length ? this.data.receiverIndex : 0 });
    } catch (e) { if (alive()) this.setData({ loading: false, error: e.message || '读取材料原单失败' }); }
  },
  canWrite() {
    return this.active && this.data.editable && this.scopeValid?.() && this.cacheKey === pendingKey(auth.currentApiPost() || {}, this);
  },
  input(e) {
    const key = e.currentTarget.dataset.key;
    if (!this.data.saving && !this.data.uncertain && ['quantity','note','arrivalQuantity','transaction','arrivalNote','arrivalDate'].includes(key)) {
      this.setData({ [key]: e.detail.value, error: '' });
    }
  },
  pickKind(e) { if (!this.data.saving && !this.ticket && kinds[e.detail.value]) this.setData({ kindIndex: Number(e.detail.value) }); },
  pickReceiver(e) { if (!this.data.saving && !this.ticket && this.data.receivers[e.detail.value]) this.setData({ receiverIndex: Number(e.detail.value) }); },
  pickQuality(e) { if (!this.data.saving && !this.ticket && qualityOptions[e.detail.value]) this.setData({ qualityIndex: Number(e.detail.value) }); },
  pickReship(e) {
    const selected = id(e.currentTarget.dataset.id);
    if (this.canWrite() && !this.data.saving && !this.ticket && this.data.reships.some(r => id(r.id) === selected)) {
      this.setData({ selectedReship: selected, arrivalQuantity: '', transaction: '', arrivalNote: '', arrivalDate: day(Date.now()), qualityIndex: 0, photo: '', error: '' });
    }
  },
  choosePhoto() {
    if (!this.canWrite() || this.data.saving || this.ticket) return;
    const valid = http.sessionGuard(), selected = this.data.selectedReship;
    wx.chooseMedia({ count: 1, mediaType: ['image'], success: result => {
      if (this.active && valid() && this.data.selectedReship === selected) this.setData({ photo: result.tempFiles[0].tempFilePath });
    } });
  },
  openQuality(e) {
    const row = this.data.qualityRows.find(r => r.id === id(e.currentTarget.dataset.id));
    if (row && this.active && this.scopeValid?.() && !this.data.saving) wx.navigateTo({ url: row.url });
  },
  submitMovement() {
    if (!this.canWrite() || this.data.saving || this.ticket) return;
    const { line, quantity, note, kindIndex, receiverIndex, receivers } = this.data;
    const amount = thousandths(quantity);
    if (amount <= 0 || amount > thousandths(line.availableQuantity)) return this.setData({ error: '数量须大于零、最多三位小数，并在可分配量内' });
    if (!note.trim() || note.trim().length > 500) return this.setData({ error: '请填写500字以内的用途或原因' });
    return this.storeAndSend('movement', { materialId: this.material, kind: kinds[kindIndex].code, quantity: String(quantity).trim(),
      note: note.trim(), receiverUserId: receivers[receiverIndex]?.id || null });
  },
  async submitReship() {
    if (!this.canWrite() || this.data.saving || this.ticket) return;
    const { selectedReship, arrivalQuantity, transaction, qualityIndex, arrivalNote, arrivalDate, photo } = this.data;
    if (!this.data.reships.some(r => id(r.id) === selectedReship) || thousandths(arrivalQuantity) <= 0 || !transaction.trim() || transaction.trim().length > 128 || !photo
        || !day(arrivalDate) || arrivalDate > day(Date.now())) {
      return this.setData({ error: '请选择待补原单，填写实际数量、收货流水并上传现场照片' });
    }
    const valid = http.sessionGuard();
    this.setData({ saving: true, error: '' });
    try {
      const fileAssetId = await api.uploadVoucher(photo, 'ENG_SITE_PHOTO');
      if (!this.active || !valid() || this.data.selectedReship !== selectedReship) return;
      await this.storeAndSend('reship', { parentReceiptId: selectedReship, actual: String(arrivalQuantity).trim(),
        transactionNo: transaction.trim(), quality: qualityIndex === 1 ? 'REJECTED' : 'OK', fileAssetId, occurredAt: arrivalDate, note: arrivalNote.trim() });
    } catch (e) { if (this.active && valid()) this.setData({ error: e.message || e.msg || '上传现场照片失败' }); }
    finally { if (valid() && !this.closed) this.setData({ saving: false }); }
  },
  storeAndSend(action, body) {
    const ticket = { key: 'material-' + Date.now() + '-' + Math.random().toString(36).slice(2), action,
      material: this.material, supply: id(this.data.line.supplyId), body };
    try { wx.setStorageSync(this.cacheKey, ticket); } catch (_) { return this.setData({ error: '未能保存本次原请求，请重试' }); }
    this.ticket = ticket;
    return this.send(ticket);
  },
  retry() { if (this.canWrite() && !this.data.saving && this.ticket) return this.send(this.ticket); },
  async send(ticket) {
    const valid = http.sessionGuard(), key = this.cacheKey;
    const clear = () => { if (wx.getStorageSync(key)?.key === ticket.key) wx.removeStorageSync(key); };
    this.setData({ saving: true, error: '' });
    try {
      if (ticket.action === 'movement') await api.materials.movement(this.project, ticket.body, ticket.key);
      else await api.materials.arrival(ticket.supply, ticket.body, ticket.key);
      clear();
      if (!valid() || this.closed || key !== this.cacheKey) return;
      this.ticket = null;
      this.setData({ ...initial, loading: true });
      if (this.active) { wx.showToast({ title: '已登记' }); await this.load(); }
    } catch (e) {
      if (!valid() || this.closed || key !== this.cacheKey) return;
      const code = Number(e.code), uncertain = !Number.isFinite(code) || code <= 0 || code === 1099000000 || code >= 500 && code < 600;
      if (!uncertain) { clear(); this.ticket = null; }
      this.setData({ uncertain, error: uncertain ? '提交结果待核对，可重试原请求' : e.message || e.msg || '登记失败' });
    } finally { if (valid() && !this.closed && key === this.cacheKey) this.setData({ saving: false }); }
  }
});
