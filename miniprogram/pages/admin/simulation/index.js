const { createClient } = require('./client');

const client = createClient();
const clone = value => JSON.parse(JSON.stringify(value));
const emptyGoals = () => ({ sales: '', collections: '', effective: '', customers: '', nodes: '', staff: {}, departmentGoals: {}, staffDepartmentGoals: {}, expectedVersion: 0 });
const emptyCommission = () => ({ amount: '', assignee: '', note: '', checked: false, file: { name: '模拟跨端提成确认依据.pdf', sample: true }, expectedVersion: 0 });
const money = cents => '¥ ' + (Number(cents || 0) / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function commissionState(commission, selectedProjectId) {
  const projects = commission?.projects || [], staff = commission?.staff || [];
  let projectIndex = projects.findIndex(row => row.id === selectedProjectId);
  if (projectIndex < 0) projectIndex = 0;
  const project = projects[projectIndex];
  const policy = (commission?.rows || []).find(row => row.projectId === project?.id);
  let staffIndex = staff.findIndex(row => row.id === policy?.assigneeId);
  if (staffIndex < 0) staffIndex = 0;
  return {
    projectId: project?.id || '', projectIndex, staffIndex,
    form: {
      ...emptyCommission(), amount: policy ? String(policy.total / 100) : '',
      assignee: policy?.assigneeId || staff[staffIndex]?.id || '', expectedVersion: policy?.version || 0
    }
  };
}

function commissionView(commission) {
  return {
    ...(commission || { canEdit: false, projects: [], staff: [], rows: [] }),
    rows: (commission?.rows || []).map(row => ({
      ...row, totalText: money(row.total),
      stages: (row.stages || []).map(stage => ({ ...stage, amountText: money(stage.amount), receivedText: money(stage.received), requiredText: money(stage.required) }))
    }))
  };
}

Page({
  data: {
    modeLabel: '管理数据联调', baseUrl: '', inviteCode: '', connected: false, busy: false,
    error: '', revision: 0, actor: null, management: null, goalForm: emptyGoals(),
    companyForm: { displayName: '', contact: '', phone: '', address: '', expectedVersion: 0 },
    commission: { canEdit: false, projects: [], staff: [], rows: [] }, commissionProjectIndex: 0,
    commissionStaffIndex: 0, commissionForm: emptyCommission()
  },
  onLoad() {
    this.goalDirty = false;
    this.companyDirty = false;
    this.commissionDirty = false;
    this.selectedProjectId = '';
    this.setData({ baseUrl: client.baseUrl() });
    if (client.connected() && client.snapshot()) this.accept(client.snapshot());
  },
  onShow() {
    if (client.connected()) client.start(update => update.error ? this.setData({ error: update.error }) : this.accept(update.snapshot));
  },
  onHide() { client.stop(); },
  onUnload() { client.stop(); },
  input(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }); },
  goalInput(event) { this.goalDirty = true; this.setData({ ['goalForm.' + event.currentTarget.dataset.field]: event.detail.value }); },
  companyInput(event) { this.companyDirty = true; this.setData({ ['companyForm.' + event.currentTarget.dataset.field]: event.detail.value }); },
  accept(snapshot) {
    const management = snapshot.management;
    if (!management) return this.setData({ connected: true, revision: snapshot.revision, actor: snapshot.actor, management: null, error: '当前岗位只能验证隔离，不能查看管理数据' });
    const goals = management.goals || {};
    const company = management.settings?.company || {};
    const commission = commissionView(management.commission);
    const patch = {
      connected: true, revision: snapshot.revision, actor: snapshot.actor, management, error: '',
      commission
    };
    if (!this.goalDirty) patch.goalForm = {
      sales: goals.sales ?? '', collections: goals.collections ?? '', effective: goals.effective ?? '', customers: goals.customers ?? '', nodes: goals.nodes ?? '',
      staff: clone(goals.staff || {}), departmentGoals: clone(goals.departmentGoals || {}), staffDepartmentGoals: clone(goals.staffDepartmentGoals || {}), expectedVersion: goals.version || 0
    };
    if (!this.companyDirty) patch.companyForm = { displayName: company.displayName || '', contact: company.contact || '', phone: company.phone || '', address: company.address || '', expectedVersion: management.settings?.version || 0 };
    if (!this.commissionDirty) {
      const selected = commissionState(commission, this.selectedProjectId);
      this.selectedProjectId = selected.projectId;
      patch.commissionProjectIndex = selected.projectIndex;
      patch.commissionStaffIndex = selected.staffIndex;
      patch.commissionForm = selected.form;
    }
    this.setData(patch);
  },
  async connect() {
    this.setData({ busy: true, error: '' });
    try {
      const snapshot = await client.connect(this.data.baseUrl, this.data.inviteCode);
      this.accept(snapshot);
      client.start(update => update.error ? this.setData({ error: update.error }) : this.accept(update.snapshot));
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  },
  async refresh() { await this.run(() => client.refresh()); },
  async retry() { await this.run(() => client.retryPending()); },
  async saveGoals() {
    await this.run(() => client.command('/commands', { kind: 'goals', period: this.data.management.period, form: clone(this.data.goalForm) }), () => { this.goalDirty = false; });
  },
  async saveCompany() {
    await this.run(() => client.command('/commands', { kind: 'company', companyId: this.data.actor.companyId, form: clone(this.data.companyForm) }), () => { this.companyDirty = false; });
  },
  commissionProject(event) {
    const index = Number(event.detail.value), project = this.data.commission.projects[index];
    if (!project) return;
    this.selectedProjectId = project.id;
    this.commissionDirty = false;
    const selected = commissionState(this.data.commission, project.id);
    this.setData({ commissionProjectIndex: selected.projectIndex, commissionStaffIndex: selected.staffIndex, commissionForm: selected.form, error: '' });
  },
  commissionStaff(event) {
    const index = Number(event.detail.value), member = this.data.commission.staff[index];
    if (!member) return;
    this.commissionDirty = true;
    this.setData({ commissionStaffIndex: index, 'commissionForm.assignee': member.id, error: '' });
  },
  commissionInput(event) { this.commissionDirty = true; this.setData({ ['commissionForm.' + event.currentTarget.dataset.field]: event.detail.value }); },
  commissionToggle() { this.commissionDirty = true; this.setData({ 'commissionForm.checked': !this.data.commissionForm.checked }); },
  async saveCommission() {
    const form = clone(this.data.commissionForm);
    await this.run(() => client.command('/commands', { kind: 'commission-policy', id: this.selectedProjectId, form }), () => { this.commissionDirty = false; });
  },
  async run(operation, onSaved) {
    this.setData({ busy: true, error: '' });
    try {
      const result = await operation();
      onSaved?.();
      if (result?.revision) await client.refresh();
      this.accept(client.snapshot());
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  },
  disconnect() {
    client.disconnect();
    this.goalDirty = this.companyDirty = this.commissionDirty = false;
    this.selectedProjectId = '';
    this.setData({ connected: false, actor: null, management: null, revision: 0, inviteCode: '', error: '', goalForm: emptyGoals(), commission: { canEdit: false, projects: [], staff: [], rows: [] }, commissionForm: emptyCommission() });
  }
});
