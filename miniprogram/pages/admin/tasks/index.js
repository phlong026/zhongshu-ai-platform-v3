const A = require('../../../utils/account');
const P = require('../../../utils/platform-performance'); // 阶段 3 API 态（任务机在线；无 token/失败一律错误态，不回落本地演示）
const orgApi = require('../../../api/org');

const monthEnd = period => new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
const validPeriod = period => /^\d{4}-(0[1-9]|1[0-2])$/.test(String(period || ''));

function progress(task) {
  const raw = task.type === 'manual' ? (task.manualDone ? 100 : 0) : Number(task.target) > 0 ? Number(task.collected || 0) / Number(task.target) * 100 : 0;
  const percent = Math.round(raw * 10) / 10;
  const degrees = Math.max(0, Math.min(100, percent)) * 3.6;
  return { percent, progressRight: Math.min(180, degrees), progressLeft: Math.max(0, degrees - 180) };
}

function taskRows(rows, staff) {
  const names = Object.fromEntries(staff.map(person => [person.id, person.name]));
  const labels = Object.fromEntries(P.TYPES.map(type => [type.id, type]));
  return rows.map(task => ({
    ...task,
    ...progress(task),
    assigneeName: task.assigneeName || names[task.assigneeId] || '历史人员',
    typeLabel: labels[task.type]?.label || task.type,
    unit: labels[task.type]?.unit || '',
    targetText: task.type === 'manual' ? '提交说明（照片选填）' : task.target + (labels[task.type]?.unit || ''),
    collectedText: task.type === 'manual' ? (task.manualDone ? '已提交凭证' : '待提交凭证') : task.collected + (labels[task.type]?.unit || ''),
    suggestionText: task.suggestion ? '依据 ' + task.suggestion.period + ' 真实实绩 ' + task.suggestion.value + task.suggestion.unit : '',
    suggestionSources: task.suggestion?.sources || [],
    historyText: (task.history || []).slice(-1).map(row => row.action === 'reassign' ? '改派 ' + (names[row.from] || row.from) + ' → ' + (names[row.to] || row.to) : row.action === 'manual-complete' ? '已提交手动完成凭证' : '已派发给 ' + (names[row.to] || row.to)).join('')
  }));
}

Page({
  data: { 
    ready: false, error: '', view: 'mine', title: '绩效任务', myName: '', memberStaffId:'', period: '', periodEnd: '', tabs: [], rows: [], goalRows: [], staff: [], types: P.TYPES,
    form: {}, assigneeIndex: 0, typeIndex: 0, previous: null, reassignTask: null, evidenceTask: null,
    evidence: { note: '', photos: [] }, archive: null,
    canInitialize: false, initializationHint: ''
  },
  onLoad(options) {
    this.requestedView = options.view || '';
    this.initialMemberStaffId = options.view === 'member' ? String(options.person || '') : '';
    this.requestedMemberStaffId = this.initialMemberStaffId;
    this.initialPeriod = validPeriod(options.period) ? options.period : '';
    this.requestedPeriod = this.initialPeriod;
    this.resetTaskForm();
    this.refresh();
  },
  onShow() { if (this.data.ready) this.refresh(); 
  },
  resetTaskForm() {
    const period = this.requestedPeriod || this.data.period || A.localDate().slice(0, 7);
    this.setData({
      form: { title: '', assigneeId: '', due: monthEnd(period), type: 'manual', target: '', note: '', idempotencyKey: 'performance-task-create:' + Date.now() },
      assigneeIndex: 0, typeIndex: 0, previous: null
    });
  },
  refresh() {
    if (P.online()) return this.refreshOnline();
    this.setData({ ready: true, error: '请先登录后端账号', rows: [], tabs: [], staff: [] });
  },
  writable() { if (this.data.view === 'member') { this.fail('成员绩效仅供查看'); return false; } return true; },
  displayArchive(summary, staff) {
    const names = Object.fromEntries(staff.map(person => [person.id, person.name]));
    const latest = summary.latest && {
      ...summary.latest,
      completedCount: summary.latest.tasks.filter(task => task.status === '已完成').length,
      lines: summary.latest.tasks.map(task => ({
        id: task.id, name: task.assigneeName || names[task.assigneeId] || task.assigneeId, title: task.title, status: task.status
      }))
    };
    return { ...summary, latest };
  },
  async refreshOnline() { // API 态：范围列表/归档在线渲染；成员=org 真源（Codex P1-3）
    const period = this.requestedPeriod || this.data.period || A.localDate().slice(0, 7);
    const requested = this.requestedView || this.data.view;
    try {
      const view = requested === 'archive' ? 'archive' : requested === 'team' ? 'team' : requested === 'member' ? 'member' : 'mine';
      const [raw, members] = await Promise.all([
        P.loadTasks(period, view, this.requestedMemberStaffId || undefined),
        P.loadMembers().catch(() => P.memberView([]))
      ]);
      const staff = members.length ? members : raw.map(r => ({ id: r.assigneeUserId, name: r.assigneeName, dept: r.assigneeDept }));
      this.apiStaff = staff;
      const archive = view === 'archive' ? P.archiveView(await P.api.tasks.archives(period)) : null;
      const rows = taskRows(raw, staff);
      const myName = this.data.myName || '当前成员';
      const title = view === 'mine' ? myName + ' · 我的绩效' : view === 'member' ? myName + ' · 绩效目标'
        : view === 'archive' ? '绩效任务归档' : '团队绩效任务';
      this.requestedView = '';
      const tabs = [
        { id: 'team', label: '团队任务' },
        { id: 'mine', label: '我的任务' },
        { id: 'archive', label: '任务归档' }
      ];
      this.setData({
        ready: true, error: '', view, title, myName, memberStaffId: this.requestedMemberStaffId || '',
        period, periodEnd: monthEnd(period), tabs: view === 'member' ? [] : tabs, rows,
        types: P.TYPES,
        goalRows: ['mine', 'member'].includes(view) ? rows : [],
        canArchive: view === 'team' && raw.length > 0,
        canInitialize: view === 'team', staff, archive: archive ? this.displayArchive(archive, staff) : null,
        platPerf: true
      });
      wx.setNavigationBarTitle({ title });
    } catch (error) {
      const msg = P.errorText(error);
      this.setData({ error: msg, ready: true });
      wx.showToast({ title: msg, icon: 'none' });
    }
  },
  fail(message) { this.setData({ error: message }); wx.showToast({ title: message, icon: 'none' }); },
  tab(event) {
    this.requestedView = event.currentTarget.dataset.view;
    this.refresh();
  },
  month(event) {
    this.requestedPeriod = event.detail.value;
    this.setData({ initializationHint: '' });
    this.resetTaskForm();
    this.refresh();
  },
  async initializePrevious() {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        const result = await P.perform('initialize', { period: this.data.period,
          idem: 'performance-task-initialize:' + this.data.period });
        this.refresh();
        const message = result.created ? '已按上月真实实绩生成 ' + result.created + ' 项建议任务' : '上月暂无可用真实业绩，未生成 0 目标任务';
        this.setData({ initializationHint: message });
        wx.showToast({ title: result.created ? '建议任务已生成' : '上月暂无业绩', icon: 'none' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  },
  field(event) { this.setData({ ['form.' + event.currentTarget.dataset.key]: event.detail.value, error: '' }); },
  selectAssignee(event) {
    const index = Number(event.detail.value), person = this.data.staff[index];
    if (!person) return;
    this.setData({ assigneeIndex: index, 'form.assigneeId': person.id });
    this.updatePrevious();
  },
  selectType(event) {
    const index = Number(event.detail.value), type = this.data.types[index];
    this.setData({ typeIndex: index, 'form.type': type.id, 'form.target': type.manual ? '' : this.data.form.target });
    this.updatePrevious();
  },
  updatePrevious() { // 在线态：上月参考取任务行自带 suggestion（API 真源），不再查本地账簿
    const form = this.data.form;
    if (!form.assigneeId || !form.type) return;
    const row = this.data.rows.find(item => item.assigneeId === form.assigneeId && item.type === form.type && item.suggestion);
    this.setData({ previous: row ? { period: row.suggestion.period, value: P.valueText(form.type, row.suggestion.value), unit: P.typeMeta(form.type).unit } : null });
  },
  async submitTask(event) {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        const form = { ...this.data.form, ...event.detail.value, period: this.data.period };
        const person = this.data.staff.find(x => x.id === form.assigneeId);
        await P.perform('create', { ...form, assigneeName: person ? person.name : '',
          assigneeDept: (person && person.dept) || 'business', idem: form.idempotencyKey });
        this.resetTaskForm();
        this.refresh();
        wx.showToast({ title: '任务已派发', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  },
  beginReassign(event) {
    if (!this.writable()) return;
    const task = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    this.setData({ reassignTask: task, reassignIndex: Math.max(0, this.data.staff.findIndex(person => person.id === task.assigneeId)) });
  },
  cancelReassign() { this.setData({ reassignTask: null }); },
  async submitReassign(event) {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        const person = this.data.staff[Number(event.detail.value.assigneeIndex)];
        // R2 P1-4：每次独立改派生成新键（任务ID:人员:时间戳——B→A→B 不误判重放）；失败保留本键重试
        if (!this.reassignKey || this.reassignKeyFor !== this.data.reassignTask.id + ':' + person.id) {
          this.reassignKey = 'performance-task-reassign:' + this.data.reassignTask.id + ':' + person.id + ':' + Date.now();
          this.reassignKeyFor = this.data.reassignTask.id + ':' + person.id;
        }
        await P.perform('reassign', { id: this.data.reassignTask.id, assigneeUserId: person.id,
          assigneeName: person.name, idem: this.reassignKey });
        this.reassignKey = null; this.reassignKeyFor = null; // 成功废弃
        this.setData({ reassignTask: null });
        this.refresh();
        wx.showToast({ title: '改派已留痕', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  },
  beginManual(event) {
    if (!this.writable()) return;
    const task = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    // R3：键绑定任务 id 上下文（换任务换键）
    this.manualKey = 'performance-task-manual:' + task.id + ':' + Date.now(); // 本次操作键，失败保留
    this.setData({ evidenceTask: task, evidence: { note: (task.manualDone && task.manualDone.note) || '', photos: (task.manualDone && task.manualDone.photos) || [] } });
  },
  cancelManual() { this.setData({ evidenceTask: null, evidence: { note: '', photos: [] } }); },
  evidenceNote(event) { this.setData({ 'evidence.note': event.detail.value, error: '' }); },
  choosePhotos() {
    if (!this.writable()) return;
    const current = this.data.evidence.photos || [];
    if (current.length >= 6) return this.fail('最多上传 6 张照片');
    wx.chooseMedia({
      count: 6 - current.length, mediaType: ['image'], sourceType: ['album', 'camera'],
      success: result => {
        Promise.all(result.tempFiles.map(file => new Promise((resolve, reject) => wx.saveFile({ tempFilePath: file.tempFilePath, success: saved => resolve(saved.savedFilePath), fail: reject }))))
          .then(paths => this.setData({ 'evidence.photos': current.concat(paths), error: '' }))
          .catch(() => this.fail('照片保存失败，请重新选择'));
      },
      fail: () => this.fail('未选择照片，可重试')
    });
  },
  removePhoto(event) {
    const photos = (this.data.evidence.photos || []).filter((_, index) => index !== Number(event.currentTarget.dataset.index));
    this.setData({ 'evidence.photos': photos });
  },
  openSource(event) {
    const url = event.currentTarget.dataset.url;
    if (url) wx.navigateTo({ url, fail: () => this.fail('原单暂无法打开，请稍后重试') });
  },
  async completeManual(event) {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        const manualCtxOk = this.manualKeyCtx === String(this.data.evidenceTask.id);
        if (!this.manualKey || !manualCtxOk) {
          this.manualKey = 'performance-task-manual:' + this.data.evidenceTask.id + ':' + Date.now();
          this.manualKeyCtx = String(this.data.evidenceTask.id);
        }
        await P.perform('manual', { id: this.data.evidenceTask.id, note: event.detail.value.note,
          photos: this.data.evidence.photos, idem: this.manualKey });
        this.manualKey = null; this.manualKeyCtx = null; // 成功废弃
        this.cancelManual();
        this.refresh();
        wx.showToast({ title: '完成凭证已保存', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  },
  async archive() {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        // R3：键绑定命令上下文（period 变化换键——防跨月旧键死锁）
        if (!this.archiveKey || this.archiveKeyCtx !== this.data.period) {
          this.archiveKey = 'performance-task-archive:' + this.data.period + ':' + Date.now();
          this.archiveKeyCtx = this.data.period;
        }
        await P.perform('archive', { period: this.data.period, idem: this.archiveKey });
        this.requestedView = 'archive';
        this.refresh();
        this.archiveKey = null; this.archiveKeyCtx = null; // 成功废弃
        wx.showToast({ title: '本月任务已归档', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  },
  async revise() {
    if (!this.writable() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      try {
        if (!this.reviseKey || this.reviseKeyCtx !== this.data.period) {
          this.reviseKey = 'performance-task-archive:' + this.data.period + ':r' + Date.now();
          this.reviseKeyCtx = this.data.period;
        }
        await P.perform('revise', { period: this.data.period, idem: this.reviseKey });
        this.refresh();
        this.reviseKey = null; this.reviseKeyCtx = null; // 成功废弃
        wx.showToast({ title: '修订版已归档', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  }
});
