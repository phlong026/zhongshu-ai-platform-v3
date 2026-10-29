const A = require('../../../utils/account');
const X = require('../../../utils/access');
const O = require('../../../utils/organization');
const F = require('../../../utils/finance-source');
const Model = require('../model');
const Facts = require('../owner-performance').facts;
const Tasks = require('../../../utils/performance-tasks').withFacts(Facts);

const monthEnd = period => new Date(Date.UTC(Number(period.slice(0, 4)), Number(period.slice(5, 7)), 0)).toISOString().slice(0, 10);
const yuan = cents => (Number(cents || 0) / 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 });

function taskRows(rows, staff) {
  const names = Object.fromEntries(staff.map(person => [person.id, person.name]));
  const labels = Object.fromEntries(Tasks.TYPES.map(type => [type.id, type]));
  return rows.map(task => ({
    ...task,
    assigneeName: task.assigneeName || names[task.assigneeId] || '历史人员',
    typeLabel: labels[task.type]?.label || task.type,
    unit: labels[task.type]?.unit || '',
    targetText: task.type === 'manual' ? '提交说明（照片选填）' : task.target + (labels[task.type]?.unit || ''),
    collectedText: task.type === 'manual' ? (task.manualDone ? '已提交凭证' : '待提交凭证') : task.collected + (labels[task.type]?.unit || ''),
    bonusText: yuan(task.bonus),
    suggestionText: task.suggestion ? '依据 ' + task.suggestion.period + ' 真实实绩 ' + task.suggestion.value + task.suggestion.unit : '',
    suggestionSources: task.suggestion?.sources || [],
    historyText: (task.history || []).slice(-1).map(row => row.action === 'reassign' ? '改派 ' + (names[row.from] || row.from) + ' → ' + (names[row.to] || row.to) : row.action === 'manual-complete' ? '已提交手动完成凭证' : '已派发给 ' + (names[row.to] || row.to)).join('')
  }));
}

function payoutRows(state, bonus, actor) {
  return (state.payables || []).filter(row => row.performanceArchive?.kind === 'performance-task-bonus-v1' && row.performanceArchive.period === bonus?.period).map(row => {
    const paid = F.paid(state, row.id), reserved = F.reserved(state, row.id), current = row.performanceArchive.archiveId === bonus.latest?.id;
    const remaining = Math.max(0, row.total - paid), blocked = !current || bonus.needsRevision;
    return {
      id:row.id, name:row.recipient, total:yuan(row.total), paid:yuan(paid), remaining:yuan(remaining), current,
      status:blocked ? current ? '原单已变化，暂停付款' : '旧归档应付，需人工对账' : reserved >= row.total ? '已登记付款' : reserved ? '部分已登记' : row.status,
      url:O.has(actor.role, 'finance') && !blocked && remaining > 0 ? '/pages/admin/index?view=pay&id=' + encodeURIComponent(row.id) : ''
    };
  }).sort((left, right) => Number(right.current) - Number(left.current) || left.name.localeCompare(right.name));
}

Page({
  data: {
    ready: false, error: '', view: 'mine', period: '', periodEnd: '', tabs: [], rows: [], staff: [], types: Tasks.TYPES,
    form: {}, assigneeIndex: 0, typeIndex: 0, previous: null, reassignTask: null, evidenceTask: null,
    evidence: { note: '', photos: [] }, bonus: null, payoutDraft: null, payableRows: [], canGeneratePayables: false,
    canInitialize: false, initializationHint: ''
  },
  onLoad(options) {
    this.requestedView = options.view || '';
    this.requestedPeriod = options.period || '';
    this.resetTaskForm();
    this.refresh();
  },
  onShow() { if (this.data.ready) this.refresh(); },
  resetTaskForm() {
    const period = this.requestedPeriod || this.data.period || A.localDate().slice(0, 7);
    this.setData({
      form: { title: '', assigneeId: '', due: monthEnd(period), type: 'manual', target: '', bonus: '', note: '', idempotencyKey: 'performance-task-create:' + Date.now() },
      assigneeIndex: 0, typeIndex: 0, previous: null
    });
  },
  refresh() {
    try {
      const state = Model.load(), actor = X.context(), period = this.requestedPeriod || this.data.period || state.period || A.localDate().slice(0, 7);
      this.state = state;
      const boss = X.isBoss(actor), finance = O.has(actor.role, 'finance');
      const tabs = [
        ...(boss ? [{ id: 'team', label: '团队任务' }] : []),
        { id: 'mine', label: '我的任务' },
        ...(boss || finance ? [{ id: 'bonus', label: '奖金归档' }] : [])
      ];
      const allowed = new Set(tabs.map(tab => tab.id));
      const requested = this.requestedView || this.data.view;
      const view = allowed.has(requested) ? requested : boss ? 'team' : 'mine';
      const raw = view === 'team' ? Tasks.list(state, { scope: 'company', period }) : view === 'mine' ? Tasks.list(state, { scope: 'mine', period }) : [];
      const staff = (state.staff || []).filter(person => person.active !== false).map(person => ({ id: person.id, name: person.name }));
      const bonus = view === 'bonus' ? Tasks.bonusSummary(state, period) : null;
      const payoutDraft = bonus?.latest && !bonus.needsRevision ? Tasks.payoutDraft(state, bonus.latest.id) : null;
      const payableRows = bonus ? payoutRows(state, bonus, actor) : [];
      this.requestedView = '';
      this.setData({
        ready: true, error: '', view, period, periodEnd: monthEnd(period), tabs, rows: taskRows(raw, staff), canArchive: view === 'team' && raw.length > 0 && !raw[0].frozen,
        canInitialize: boss && view === 'team' && !raw.some(task => task.frozen),
        staff: boss ? staff : [], bonus: bonus ? this.displayBonus(bonus, staff) : null, payoutDraft, payableRows,
        canGeneratePayables:!!payoutDraft && (!!payoutDraft.lines.length || payableRows.length > 0) && !payableRows.some(row => row.current),
        payableActionLabel:payoutDraft && !payoutDraft.lines.length ? '核销旧版未付应付' : '按归档生成奖金应付'
      });
      if (!this.data.form.assigneeId && boss && staff.length) this.selectAssignee({ detail: { value: 0 } });
    } catch (error) {
      this.setData({ ready: true, error: error.message || '绩效任务加载失败' });
    }
  },
  displayBonus(summary, staff) {
    const names = Object.fromEntries(staff.map(person => [person.id, person.name]));
    const latest = summary.latest && {
      ...summary.latest,
      bonusText: yuan(summary.latest.bonusTotal),
      completedCount: summary.latest.tasks.filter(task => task.status === '已完成').length,
      lines: summary.latest.tasks.filter(task => task.bonusAwarded > 0).map(task => ({
        id: task.id, name: task.assigneeName || names[task.assigneeId] || task.assigneeId, title: task.title, amount: yuan(task.bonusAwarded)
      }))
    };
    return { ...summary, latest };
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
  initializePrevious() {
    try {
      const result = Tasks.initializePrevious(this.state, {
        period:this.data.period,
        idempotencyKey:['performance-task-initialize', this.state.companyId, this.data.period, Date.now()].join(':')
      });
      this.refresh();
      const message = result.created.length ? '已按上月真实实绩生成 ' + result.created.length + ' 项建议任务' : '上月暂无可用真实业绩，未生成 0 目标任务';
      this.setData({ initializationHint:message });
      wx.showToast({ title:result.created.length ? '建议任务已生成' : '上月暂无业绩', icon:'none' });
    } catch (error) { this.fail(error.message); }
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
  updatePrevious() {
    const form = this.data.form;
    if (!this.state || !form.assigneeId || !form.type) return;
    try { this.setData({ previous: Tasks.previousActual(this.state, { period: this.data.period, assigneeId: form.assigneeId, type: form.type }) }); }
    catch (_) { this.setData({ previous: null }); }
  },
  submitTask(event) {
    try {
      Tasks.create(this.state, { ...this.data.form, ...event.detail.value, period: this.data.period });
      this.resetTaskForm();
      this.refresh();
      wx.showToast({ title: '任务已派发', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  beginReassign(event) {
    const task = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    this.setData({ reassignTask: task, reassignIndex: Math.max(0, this.data.staff.findIndex(person => person.id === task.assigneeId)) });
  },
  cancelReassign() { this.setData({ reassignTask: null }); },
  submitReassign(event) {
    try {
      const person = this.data.staff[Number(event.detail.value.assigneeIndex)];
      Tasks.reassign(this.state, this.data.reassignTask.id, {
        assigneeId: person.id, note: event.detail.value.note,
        idempotencyKey: 'performance-task-reassign:' + this.data.reassignTask.id + ':' + person.id
      });
      this.setData({ reassignTask: null });
      this.refresh();
      wx.showToast({ title: '改派已留痕', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  beginManual(event) {
    const task = this.data.rows.find(row => row.id === event.currentTarget.dataset.id);
    this.setData({ evidenceTask: task, evidence: { note: task.manualDone?.note || '', photos: task.manualDone?.photos || [] } });
  },
  cancelManual() { this.setData({ evidenceTask: null, evidence: { note: '', photos: [] } }); },
  evidenceNote(event) { this.setData({ 'evidence.note': event.detail.value, error: '' }); },
  choosePhotos() {
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
  completeManual(event) {
    try {
      Tasks.completeManual(this.state, this.data.evidenceTask.id, {
        note: event.detail.value.note, photos: this.data.evidence.photos,
        idempotencyKey: 'performance-task-manual:' + this.data.evidenceTask.id + ':' + Date.now()
      });
      this.cancelManual();
      this.refresh();
      wx.showToast({ title: '完成凭证已保存', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  archive() {
    try {
      Tasks.archive(this.state, this.data.period, { note: '月度绩效任务归档', idempotencyKey: 'performance-task-archive:' + this.state.companyId + ':' + this.data.period });
      this.requestedView = 'bonus';
      this.refresh();
      wx.showToast({ title: '本月任务已归档', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  revise() {
    try {
      const revision = (this.data.bonus?.latest?.revision || 1) + 1;
      Tasks.reviseArchive(this.state, this.data.period, { note: '原业务事实变化后重新冻结', idempotencyKey: 'performance-task-archive:' + this.state.companyId + ':' + this.data.period + ':r' + revision });
      this.refresh();
      wx.showToast({ title: '修订版已归档', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  generatePayables() {
    try {
      Model.mutate(state => Model.apply(state, 'performance-bonus-register', this.data.bonus.latest.id, { idempotencyKey:this.data.payoutDraft.idempotencyKey }));
      this.refresh();
      wx.showToast({ title: '奖金应付已生成', icon: 'success' });
    } catch (error) { this.fail(error.message); }
  },
  openPay(event) {
    const url = event.currentTarget.dataset.url;
    if (url && this.data.payableRows.some(row => row.url === url)) wx.navigateTo({ url });
  }
});
