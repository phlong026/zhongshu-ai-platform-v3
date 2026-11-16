const A = require('../../../utils/account');
const P = require('../../../utils/platform-performance'); // 阶段 3 API 态（任务机在线；无 token/失败一律错误态，不回落本地演示）
const http = require('../../../api/http');

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
    ready: false, error: '', view: 'mine', title: '绩效任务', myName: '', memberStaffId:'', period: '', periodEnd: '', tabs: [], rows: [], taskCount: 0, goalRows: [], staff: [], types: P.TYPES,
    form: {}, assigneeIndex: 0, typeIndex: 0, previous: null, reassignTask: null, evidenceTask: null,
    evidence: { note: '', photos: [] }, archive: null,
    canManage: false, submitting: false, taskOptionsOpen: false, taskOptions: [], taskOptionCount: 0, taskOptionsHeight: 0, taskQuery: '',
    reusableTasks: [], reuseBusy: false, reuseError: '', reusePeriod: '', reuseLoaded: false
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
  onUnload() { this.reuseGeneration = (this.reuseGeneration || 0) + 1; },
  resetTaskForm() {
    const period = this.requestedPeriod || this.data.period || A.localDate().slice(0, 7);
    if (this.commandKeys) delete this.commandKeys.create;
    this.setData({
      form: { title: '', assigneeId: '', due: monthEnd(period), type: 'manual', target: '', note: '' },
      assigneeIndex: 0, typeIndex: 0, previous: null, taskOptionsOpen: false, taskQuery: ''
    });
  },
  commandKey(action, payload) {
    const fingerprint = JSON.stringify([http.tenantId(), http.currentPostId(), http.sessionGeneration(),
      action, String(payload.id || ''), P.taskPayload(action, payload)]);
    const keys = this.commandKeys || (this.commandKeys = {});
    if (!keys[action] || keys[action].fingerprint !== fingerprint) {
      this.commandSequence = (this.commandSequence || 0) + 1;
      keys[action] = { fingerprint, key: 'performance-task-' + action + ':' + Date.now() + ':' + Math.random().toString(16).slice(2) + ':' + this.commandSequence };
    }
    return keys[action].key;
  },
  refresh() {
    const scope = JSON.stringify([http.tenantId(), http.currentPostId(), http.sessionGeneration()]);
    if (this.formScope && this.formScope !== scope) {
      this.reuseGeneration = (this.reuseGeneration || 0) + 1;
      this.setData({ reusableTasks: [], reuseBusy: false, reuseError: '', reuseLoaded: false, taskOptions: [] });
      this.resetTaskForm();
    }
    this.formScope = scope;
    if (P.online()) return this.refreshOnline();
    this.setData({ ready: true, error: '请先登录后端账号', rows: [], taskCount: 0, tabs: [], staff: [] });
  },
  writable() { if (this.data.view === 'member') { this.fail('成员绩效仅供查看'); return false; } return true; },
  manageTasks() { const post=P.apiPost();if(!post||!['boss','owner'].includes(post.roleCode)){this.fail('当前岗位无任务派发权限');return false;}return this.writable(); },
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
    const alive=http.requestGuard('performance-tasks');
    const post=P.apiPost(),canManage=!!post&&['boss','owner'].includes(post.roleCode);
    this.setData({canManage});
    try {
      const view = requested === 'archive' ? 'archive' : requested === 'team' ? 'team' : requested === 'member' ? 'member' : 'mine';
      const [raw, members] = await Promise.all([
        P.loadTasks(period, view, this.requestedMemberStaffId || undefined),
        P.loadMembers().catch(() => P.memberView([]))
      ]);
      if(!alive())return;
      const staff = members.length ? members : raw.map(r => ({ id: r.assigneeUserId, name: r.assigneeName, dept: r.assigneeDept }));
      this.apiStaff = staff;
      const archive = view === 'archive' ? P.archiveView(await P.api.tasks.archives(period)) : null;
      if(!alive())return;
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
        period, periodEnd: monthEnd(period), tabs: view === 'member' ? [] : tabs, rows, taskCount: rows.length,
        types: P.TYPES,
        goalRows: ['mine', 'member'].includes(view) ? rows : [],
        canArchive: view === 'team' && raw.length > 0,
        staff, archive: archive ? this.displayArchive(archive, staff) : null,
        platPerf: true
      });
      wx.setNavigationBarTitle({ title });
    } catch (error) {
      if(!alive())return;
      const msg = P.errorText(error);
      this.setData({ error: msg, ready: true });
      wx.showToast({ title: msg, icon: 'none' });
    }
  },
  fail(message) { this.setData({ error: message }); wx.showToast({ title: message, icon: 'none' }); },
  tab(event) {
    if (this.busy) return;
    this.setData({ taskOptionsOpen: false });
    this.requestedView = event.currentTarget.dataset.view;
    this.refresh();
  },
  month(event) {
    if (this.busy) return;
    this.reuseGeneration=(this.reuseGeneration||0)+1;
    this.requestedPeriod = event.detail.value;
    this.setData({ reusableTasks: [], reuseBusy: false, reuseError: '', reusePeriod: '', reuseLoaded: false, taskOptions: [] });
    this.resetTaskForm();
    this.refresh();
  },
  field(event) { this.setData({ ['form.' + event.currentTarget.dataset.key]: event.detail.value, error: '' }); },
  updateTaskOptions(query = this.data.taskQuery) {
    const keyword = String(query || '').trim();
    const reusable = this.data.reusableTasks.map(task => ({ id: 'reuse:' + task.id, kind: 'reuse', taskId: task.id,
      label: task.title, detail: '上月任务' + (task.assigneeName ? ' · ' + task.assigneeName : '') }));
    const metrics = this.data.types.filter(type => !type.manual).map(type => ({ id: 'type:' + type.id, kind: 'metric',
      type: type.id, label: type.label, detail: '业务指标 · ' + type.unit }));
    const options = reusable.concat(metrics).filter(option => !keyword || option.label.includes(keyword));
    this.setData({ taskQuery: keyword, taskOptions: options, taskOptionCount: options.length, taskOptionsHeight: Math.min(400, options.length * 112) });
  },
  async openTaskOptions() {
    if (!this.manageTasks() || this.data.submitting) return;
    this.setData({ taskOptionsOpen: true });
    this.updateTaskOptions('');
    await this.loadReusableTasks();
  },
  toggleTaskOptions() {
    if (this.data.taskOptionsOpen) this.setData({ taskOptionsOpen: false });
    else return this.openTaskOptions();
  },
  closeTaskOptions() { this.setData({ taskOptionsOpen: false }); },
  inputTask(event) {
    if (this.data.submitting) return;
    this.setData({ 'form.title': event.detail.value, 'form.type': 'manual', 'form.target': '', typeIndex: 0, previous: null, taskOptionsOpen: true, error: '' });
    this.updateTaskOptions(event.detail.value);
    return this.loadReusableTasks();
  },
  selectTaskOption(event) {
    if (!this.manageTasks() || this.data.submitting) return;
    const id = event.currentTarget.dataset.id;
    if (id === 'manual') {
      this.setData({ 'form.type': 'manual', 'form.target': '', typeIndex: 0, previous: null, taskOptionsOpen: false, error: '' });
      return;
    }
    const option = this.data.taskOptions.find(row => row.id === id);
    if (!option) return;
    if (option.kind === 'reuse') return this.useReusableTask({ currentTarget: { dataset: { id: option.taskId } } });
    const index = this.data.types.findIndex(type => type.id === option.type);
    this.setData({ typeIndex: index, 'form.type': option.type, 'form.title': option.label, 'form.target': '', taskOptionsOpen: false, error: '' });
    this.updatePrevious();
  },
  async loadReusableTasks() {
    if(!this.manageTasks()||this.data.reuseBusy)return;
    const period=P.previousPeriod(this.data.period);
    if (this.data.reuseLoaded && this.data.reusePeriod === period) return;
    const generation=this.reuseGeneration=(this.reuseGeneration||0)+1;
    const alive=http.sessionGuard();
    this.setData({reuseBusy:true,reuseError:'',reusePeriod:period,reusableTasks:[]});
    this.updateTaskOptions();
    try {
      const rows=await P.loadTasks(period,'team');
      if(!alive()||generation!==this.reuseGeneration)return;
      this.setData({reusableTasks:rows.filter(row=>row.type==='manual'),reuseLoaded:true});
      this.updateTaskOptions();
    } catch(error) {if(alive()&&generation===this.reuseGeneration)this.setData({reuseError:P.errorText(error)});}
    finally {if(alive()&&generation===this.reuseGeneration)this.setData({reuseBusy:false});}
  },
  useReusableTask(event) {
    if(!this.manageTasks())return;
    const task=this.data.reusableTasks.find(row=>String(row.id)===String(event.currentTarget.dataset.id));
    if(!task)return;
    const index=this.data.staff.findIndex(person=>String(person.id)===String(task.assigneeId));
    const last=Number(monthEnd(this.data.period).slice(-2)),day=Math.min(Number(String(task.due||'').slice(-2))||last,last);
    this.setData({typeIndex:0,assigneeIndex:Math.max(0,index),previous:null,
      form:{title:task.title,assigneeId:index>=0?this.data.staff[index].id:'',type:'manual',target:'',note:'',due:this.data.period+'-'+String(day).padStart(2,'0')},
      error:index<0?'请选择本月责任人':'',taskOptionsOpen:false});
    wx.showToast({title:'已填入，请确认后派发',icon:'none'});
  },
  selectAssignee(event) {
    const index = Number(event.detail.value), person = this.data.staff[index];
    if (!person) return;
    this.setData({ assigneeIndex: index, 'form.assigneeId': person.id, taskOptionsOpen: false });
    this.updatePrevious();
  },
  updatePrevious() { // 在线态：上月参考取任务行自带 suggestion（API 真源），不再查本地账簿
    const form = this.data.form;
    if (!form.assigneeId || !form.type) return;
    const row = this.data.rows.find(item => item.assigneeId === form.assigneeId && item.type === form.type && item.suggestion);
    this.setData({ previous: row ? { period: row.suggestion.period, value: P.valueText(form.type, row.suggestion.value), unit: P.typeMeta(form.type).unit } : null });
  },
  async submitTask(event) {
    if (!this.manageTasks() || this.busy) return;
    if (P.online()) {
      this.busy = true;
      this.setData({ submitting: true, taskOptionsOpen: false });
      const alive = http.sessionGuard();
      try {
        const form = { ...this.data.form, ...(event&&event.detail&&event.detail.value||{}), period: this.data.period };
        if(!String(form.title||'').trim()||String(form.title).trim().length>60)throw {message:'请输入1–60字的任务内容'};
        form.title = String(form.title).trim();
        if(!form.assigneeId)throw {message:'请选择责任人'};
        if(!/^\d{4}-\d{2}-\d{2}$/.test(form.due||'')||form.due<this.data.period+'-01'||form.due>monthEnd(this.data.period))throw {message:'截止日期须在任务月份内'};
        const person = this.data.staff.find(x => String(x.id) === String(form.assigneeId));
        if (!person) throw { message: '请选择有效责任人' };
        if (form.type !== 'manual' && P.toTargetCents(form.type, form.target) === null) throw { message: '请输入大于0的目标值' };
        const payload = { ...form, assigneeName: person.name, assigneeDept: person.dept || 'business' };
        await P.perform('create', { ...payload, idem: this.commandKey('create', payload) });
        if (!alive()) return;
        this.resetTaskForm();
        this.refresh();
        wx.showToast({ title: '任务已派发', icon: 'success' });
      } catch (error) { if (alive()) this.fail(P.errorText(error)); }
      finally { this.busy = false; this.setData({ submitting: false }); }
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
        const payload = { id: this.data.reassignTask.id, assigneeUserId: person.id, assigneeName: person.name };
        await P.perform('reassign', { ...payload, idem: this.commandKey('reassign', payload) });
        delete this.commandKeys.reassign;
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
        const payload = { id: this.data.evidenceTask.id, note: event.detail.value.note, photos: this.data.evidence.photos };
        await P.perform('manual', { ...payload, idem: this.commandKey('manual', payload) });
        delete this.commandKeys.manual;
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
        const payload = { period: this.data.period };
        await P.perform('archive', { ...payload, idem: this.commandKey('archive', payload) });
        this.requestedView = 'archive';
        this.refresh();
        delete this.commandKeys.archive;
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
        const payload = { period: this.data.period };
        await P.perform('revise', { ...payload, idem: this.commandKey('revise', payload) });
        this.refresh();
        delete this.commandKeys.revise;
        wx.showToast({ title: '修订版已归档', icon: 'success' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.busy = false; }
      return;
    }
    this.fail('请先登录后端账号');
  }
});
