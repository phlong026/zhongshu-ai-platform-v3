const { createClient } = require('./client');
const copy = require('../../utils/clone');
const localDate = () => require('../../utils/account').localDate();
const money = amount => '¥ ' + (amount / 100).toLocaleString('en-US', { maximumFractionDigits: 2 });
const dateTime = value => {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return '';
  const pad = number => String(number).padStart(2, '0');
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
};

Page({
  data: {
    title: '跨设备联调', connected: false, busy: false, error: '', endpoint: 'http://127.0.0.1:8787', inviteCode: '',
    screen: 'workspace', tab: 'home', actor: null, metrics: [], tasks: [], projects: [], projectRows: [], messages: [], deliveries: [], jobs: [], recent: [],
    unread: 0, keyword: '', synchronizedAt: '', form: {}, activeTask: null, activeProject: null, consent: 'none', pending: false, syncError: false
  },
  onLoad(options) {
    this.client = createClient();
    this.requestedCustomer = options?.customerId || '';
    this.setData({ endpoint: options?.server || this.client.baseUrl() || this.data.endpoint });
  },
  onShow() {
    if (!this.client?.connected()) return;
    const saved = this.client.snapshot();
    if (saved) this.apply(saved);
    this.follow();
  },
  onHide() { this.client?.stop(); },
  onUnload() { this.client?.stop(); },
  onPullDownRefresh() { this.refresh().finally(() => wx.stopPullDownRefresh()); },
  input(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ [e.currentTarget.dataset.form ? 'form.' + key : key]: e.detail.value });
  },
  check(e) { this.setData({ 'form.checked': e.detail.value.length > 0 }); },
  sampleProof() { this.setData({ 'form.proofRef': 'mock-bank-receipt' }); },
  follow() {
    this.client.start(event => {
      if (event.snapshot) this.apply(event.snapshot);
      if (event.error) this.setData({ error: event.error, syncError: true });
      else if (this.data.syncError) this.setData({ error: '', syncError: false });
      this.setData({ pending: this.client.pending?.() || false });
    });
  },
  async connect() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '', syncError: false });
    try {
      const value = await this.client.connect(this.data.endpoint, this.data.inviteCode);
      this.setData({ inviteCode: '', tab: 'home', screen: 'workspace' });
      this.apply(value);
      this.follow();
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  },
  apply(snapshot) {
    const finance = ['finance', 'finance-lead'].includes(snapshot.actor.role);
    const projects = snapshot.projects || [], tasks = require('../../utils/task-order').newest(snapshot.tasks || []), messages = snapshot.messages || [];
    const metrics = finance ? [
      { label: '待确认到账', value: tasks.length, unit: '笔' },
      { label: '待确认金额', value: money(tasks.reduce((sum, row) => sum + (row.amount || 0), 0)), unit: '' }
    ] : [
      { label: '设计与修改', value: projects.filter(row => row.action === 'complete').length, unit: '项' },
      { label: '待到账确认', value: projects.filter(row => /待.*到账|待.*财务/.test(row.state)).length, unit: '项' },
      { label: '待下发', value: tasks.filter(row => row.action === 'dispatch').length, unit: '项' }
    ];
    const updates = {
      connected: true, actor: snapshot.actor, finance, metrics,
      tasks: tasks.map(row => ({ ...row, amountText: finance ? money(row.amount || 0) : '', actionLabel: row.kind === 'receipt' ? '确认到账' : row.action === 'complete' ? '设计完成' : '登记下发' })),
      projects, projectRows: this.filter(projects), messages, unread: messages.filter(row => !row.read).length,
      deliveries: (snapshot.wechat?.deliveries || []).map(row => ({ ...row, sentAtText: dateTime(row.sentAt) })),
      jobs: (snapshot.wechat?.jobs || []).map(row => ({ ...row, statusText: ({ 'waiting-consent': '等待授权', pending: '等待发送', sending: '正在发送', delivered: '测试网关已送达', failed: '发送失败', cancelled: '已取消', 'recipient-unavailable': '接收人暂不可用' })[row.status] || '等待更新' })),
      consent: snapshot.wechat?.consent || 'none', recent: (snapshot.recent || []).map(row => ({ ...row, atText: dateTime(row.at) })),
      synchronizedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }), pending: this.client.pending?.() || false
    };
    if (this.data.screen === 'project') {
      const project = projects.find(row => row.id === this.data.activeProject?.id);
      updates.activeProject = project || null;
      if (project?.action !== this.data.activeProject?.action) updates.form = this.projectForm(project);
    }
    if (this.data.screen === 'receipt' && !tasks.some(row => row.id === this.data.activeTask?.id) && !this.data.busy) {
      updates.screen = 'workspace';
      updates.activeTask = null;
    }
    this.setData(updates);
    if (this.requestedCustomer) {
      const id = this.requestedCustomer;
      this.requestedCustomer = '';
      this.project(id);
    }
  },
  filter(projects = this.data.projects) {
    const keyword = (this.data.keyword || '').trim().toLowerCase();
    return projects.filter(row => !keyword || [row.name, row.city, row.requirement].some(text => String(text || '').toLowerCase().includes(keyword)));
  },
  search(e) { this.setData({ keyword: e.detail.value }); this.setData({ projectRows: this.filter() }); },
  async refresh() {
    if (!this.client?.connected()) return;
    try { this.apply(await this.client.refresh()); this.setData({ error: '' }); }
    catch (error) { this.setData({ error: error.message }); }
  },
  tab(e) { this.setData({ tab: e.currentTarget.dataset.tab, screen: 'workspace', error: '', activeTask: null, activeProject: null }); },
  back() { this.setData({ screen: 'workspace', activeTask: null, activeProject: null, error: '' }); },
  openTask(e) {
    const task = this.data.tasks.find(row => row.id === e.currentTarget.dataset.id);
    if (!task) return this.setData({ error: '该事项已更新，请查看最新待办' });
    if (task.kind === 'receipt') return this.setData({ screen: 'receipt', activeTask: task, form: { amount: String(task.amount / 100), date: localDate(), transaction: '', proofRef: '', checked: false, note: '' }, error: '' });
    this.project(task.customerId);
  },
  openProject(e) { this.project(e.currentTarget.dataset.id); },
  project(id) {
    const project = this.data.projects.find(row => row.id === id);
    if (!project) return this.setData({ error: '项目不存在或当前身份无权查看' });
    this.setData({ screen: 'project', activeProject: project, form: this.projectForm(project), error: '' });
  },
  projectForm(project) { return { recipient: project?.recipient || '', note: '', checked: false }; },
  openDelivery(e) {
    const delivery = this.data.deliveries.find(row => row.id === e.currentTarget.dataset.id);
    if (!delivery || !delivery.page.startsWith('/pages/collaboration/index?customerId=')) return this.setData({ error: '通知入口已变化，请刷新后重试' });
    wx.navigateTo({ url: delivery.page, fail: () => this.setData({ error: '暂未打开通知，请重试或从项目中办理' }) });
  },
  async openMessage(e) {
    if (this.data.busy) return;
    const message = this.data.messages.find(row => row.id === e.currentTarget.dataset.id);
    if (!message) return;
    if (message.read) return this.project(message.customerId);
    await this.execute('/messages/' + encodeURIComponent(message.id) + '/read', {}, () => this.project(message.customerId));
  },
  async subscribe() { await this.execute('/subscriptions', { accepted: true }); },
  async submit() {
    const { screen, activeTask, activeProject, form } = this.data;
    if (screen === 'receipt' && activeTask) return this.execute('/receipts/' + encodeURIComponent(activeTask.id) + '/confirm', { ...copy(form), taskVersion: activeTask.taskVersion }, () => this.back());
    if (screen === 'project' && activeProject?.action) {
      const input = activeProject.action === 'complete' ? { checked: form.checked, note: form.note } : { recipient: form.recipient, note: form.note, checked: form.checked };
      return this.execute('/commissions/' + encodeURIComponent(activeProject.id) + '/' + activeProject.action, input, () => this.back());
    }
  },
  async execute(path, input, done) {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '', syncError: false });
    try {
      const result = await this.client.command(path, input);
      if (result.synchronized) {
        this.apply(this.client.snapshot());
        done?.();
        wx.showToast({ title: '服务端已保存', icon: 'success' });
      } else this.setData({ error: result.refreshError, syncError: true });
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false, pending: this.client.pending?.() || false }); }
  },
  async retry() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '', syncError: false });
    try { const result = await this.client.retryPending(); if (result?.synchronized) { this.apply(this.client.snapshot()); this.back(); } else this.setData({ error: result?.refreshError || '', syncError: true }); }
    catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false, pending: this.client.pending?.() || false }); }
  },
  disconnect() {
    this.client.disconnect();
    this.setData({ connected: false, actor: null, projects: [], projectRows: [], tasks: [], messages: [], deliveries: [], jobs: [], metrics: [], recent: [], unread: 0, form: {}, screen: 'workspace', error: '', pending: false });
  }
});
