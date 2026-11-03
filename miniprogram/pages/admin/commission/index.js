const M = require('../model');
const C = require('../../../utils/collection-commission');
const X = require('../../../utils/access');
const O = require('../../../utils/organization');
const N = require('../../../utils/page-navigation');
const Files = require('../../../utils/sample-files');
const P = require('../../../utils/platform-performance'); // 阶段 3 API 态（提成在线；失败降级本地演示）

const clone = require('../../../utils/clone');
const money = require('../../../utils/money').money;
const blankForm = () => ({ amount:'', assignee:'', note:'', file:null, checked:false, expectedVersion:0 });
const clean = () => ({ ready:false, error:'', busy:false, actor:{}, canEditPolicy:false, commissionRows:[], commissionProjects:[], commissionProjectIndex:0, commissionStaff:[], commissionStaffIndex:0, commissionForm:blankForm() });

Page({
  data:clean(),
  onLoad(options = {}) { this.options = options; this.selectedProjectId = options.id || ''; },
  onShow() { this.refresh(); 
  this.setData && this.setData({ showDemoBadge: require('../../../utils/demo-badge').isDemoMode(this) }); // M11-T01：pages/admin/commission 深度三级，原少退一级 MODULE_NOT_FOUND
  },
  refresh() {
    if (P.online()) return this.refreshOnline();
    try {
      const actor = X.context();
      if (!actor.active || !['boss','owner','finance','finance-lead'].includes(actor.role)) throw Error('当前身份无本组织提成查看权限');// API 态准入在 refreshOnline（apiPost.roleCode 矩阵）
      const state = M.load();
      const projects = (wx.getStorageSync(require('../../../utils/storage-keys').engineering)?.projects || []).filter(row => (row.companyId || 'quanzhou') === actor.companyId && row.recordFlow).map(row => ({ id:row.projectId || row.id, name:row.name || row.customer || row.projectId || row.id }));
      const staff = (state.staff || []).filter(row => row.active !== false && row.personId).map(row => ({ id:row.id, name:row.name }));
      if (!projects.some(row => row.id === this.selectedProjectId)) this.selectedProjectId = projects[0]?.id || '';
      const selectedPolicy = C.policyFor(state, this.selectedProjectId);
      const projectIndex = Math.max(0, projects.findIndex(row => row.id === this.selectedProjectId));
      const staffIndex = Math.max(0, staff.findIndex(row => row.id === selectedPolicy?.assigneeId));
      const finance = O.has(actor.role, 'finance'), actualBoss = actor.role === 'boss';
      const rows = C.rows(state).map(row => ({
        id:row.id, projectId:row.projectId, project:row.project, totalText:money(row.total), assignee:row.assignee, version:row.version,
        stages:row.stages.map(stage => ({
          stage:stage.stage, label:stage.label, status:stage.status, payableId:stage.payableId, reviewDue:stage.reviewDue,
          requiredText:money(stage.required), receivedText:money(stage.received), amountText:money(stage.amount), paidText:money(stage.paid), outstandingText:money(stage.outstanding),
          canRelease:finance && stage.eligible && !stage.payableId,
          canPay:finance && !!stage.payableId && !stage.reviewDue && stage.outstanding > 0,
          canReconcile:actualBoss && stage.reviewDue && stage.eligible,
          reason:stage.reviewDue?'原收款已变化，未付部分暂停；已付差额只待人工核对，不代表已追回。':!stage.eligible?'阶段款尚未收齐，暂不能发放。':stage.payableId?'应付已生成，可登记付款。':'阶段款已足额，可由财务生成本期应付。'
        }))
      }));
      const form = { ...blankForm(), amount:selectedPolicy ? String(selectedPolicy.total / 100) : '', assignee:selectedPolicy?.assigneeId || staff[staffIndex]?.id || '', expectedVersion:selectedPolicy?.version || 0 };
      this.setData({ ready:true, error:'', actor:{ role:actor.role, companyId:actor.companyId, demo:actor.demo }, canEditPolicy:actualBoss, commissionRows:rows, commissionProjects:projects, commissionProjectIndex:projectIndex, commissionStaff:staff, commissionStaffIndex:staffIndex, commissionForm:form });
    } catch (error) {
      this.setData({ ...clean(), error:error.message });
    }
  },
  async refreshOnline() { // API 态：身份=API 岗位（Codex P1-2）；项目=工程域∪已有规则（P1-5）；选人=org 真源（P1-3）
    const post = P.apiPost();
    if (!post) return this.setData({ ...clean(), error: '请先登录后端账号', ready: true });
    try {
      const [view, members] = await Promise.all([
        P.loadCommission(),
        P.loadMembers().then(ms => ms.filter(m => ['boss', 'owner'].includes(m.roleCode)
          || ['finance', 'finance-lead', 'sales', 'sales-lead', 'engineer', 'project-manager'].includes(m.roleCode)))
          .catch(() => [])
      ]);
      const projects = view.map(row => ({ id: row.projectId, name: row.project }));
      if (!projects.some(row => row.id === this.selectedProjectId)) this.selectedProjectId = projects[0]?.id || '';
      const selected = view.find(row => row.projectId === this.selectedProjectId);
      const staff = members.length ? members : [];
      const staffIndex = Math.max(0, staff.findIndex(x => x.id === selected?.assigneeUserId));
      const finance = ['finance', 'finance-lead'].includes(post.roleCode), actualBoss = post.roleCode === 'boss';
      this.setData({
        ready: true, error: '', actor: { role: post.roleCode, companyId: post.orgId, demo: false },
        canEditPolicy: actualBoss, commissionRows: view, commissionProjects: projects,
        commissionProjectIndex: Math.max(0, projects.findIndex(x => x.id === this.selectedProjectId)),
        commissionStaff: staff, commissionStaffIndex: staffIndex,
        commissionForm: { ...blankForm(), amount: selected && selected.totalText !== '—'
          ? selected.totalText.replace(/[¥,]/g, '') : '',
          assignee: selected ? selected.assigneeUserId : (staff[staffIndex] ? staff[staffIndex].id : '') },
        platPerf: true
      });
    } catch (error) {
      const msg = P.errorText(error);
      this.setData({ ...clean(), error: msg, ready: true });
      wx.showToast({ title: msg, icon: 'none' });
    }
  },
  commissionProject(event) {
    const index = Number(event.detail.value), project = this.data.commissionProjects[index];
    if (!project) return;
    this.selectedProjectId = project.id;
    this.refresh();
  },
  commissionStaff(event) {
    const index = Number(event.detail.value), member = this.data.commissionStaff[index];
    if (member) this.setData({ commissionStaffIndex:index, 'commissionForm.assignee':member.id, error:'' });
  },
  commissionInput(event) { this.setData({ ['commissionForm.' + event.currentTarget.dataset.key]:event.detail.value, error:'' }); },
  commissionToggle() { this.setData({ 'commissionForm.checked':!this.data.commissionForm.checked, error:'' }); },
  commissionUpload() {
    wx.showActionSheet({ itemList:Files.items(['选择实际凭据','使用参考凭据']), success:result => {
      if (result.tapIndex === 1) {
        if (!this.data.actor.demo) return this.fail('当前账号请上传实际凭据');
        return this.setData({ 'commissionForm.file':{ name:'项目提成确认参考凭据.pdf', sample:true }, error:'' });
      }
      wx.chooseMessageFile({ count:1, type:'file', success:result => { const file=result.tempFiles[0]; wx.saveFile({ tempFilePath:file.path, success:saved => this.setData({ 'commissionForm.file':{ name:file.name, path:saved.savedFilePath }, error:'' }), fail:() => this.fail('凭据未保存，请重新上传') }); } });
    } });
  },
  async commissionAction(event) {
    if (this.data.busy) return;
    const data = event.currentTarget.dataset, action = data.action;
    const id = data.id || this.selectedProjectId;
    const form = clone(this.data.commissionForm);
    if (data.stage) form.stage = data.stage;
    if (data.version !== undefined) form.expectedVersion = Number(data.version);
    if (P.online() && ['commission-policy', 'commission-release', 'commission-reconcile'].includes(action)) {
      try {
        this.setData({ busy: true, error: '' });
        const person = this.data.commissionStaff[this.data.commissionStaffIndex] || {};
        if (action === 'commission-policy') {
          // R3：键绑定项目上下文（切项目换键——防跨项目旧键死锁）
          if (!this.policyKey || this.policyKeyCtx !== String(id)) {
            this.policyKey = 'perf-policy:' + id + ':' + Date.now();
            this.policyKeyCtx = String(id);
          }
          await P.perform('policy', { projectId: id, amount: form.amount, assigneeUserId: person.id,
            assigneeName: person.name, evidenceRef: form.file, note: form.note, idem: this.policyKey });
          this.policyKey = null; this.policyKeyCtx = null; // 成功废弃
        } else if (action === 'commission-release') {
          await P.perform('release', { projectId: id, stage: form.stage,
            idem: 'perf-release:' + id + ':' + form.stage });
        } else {
          // R3：键绑定项目+阶段上下文
          const reconcileCtx = String(id) + ':' + form.stage;
          if (!this.reconcileKey || this.reconcileKeyCtx !== reconcileCtx) {
            this.reconcileKey = 'perf-reconcile:' + reconcileCtx + ':' + Date.now();
            this.reconcileKeyCtx = reconcileCtx;
          }
          await P.perform('reconcile', { projectId: id, stage: form.stage, evidenceRef: form.file, note: form.note,
            idem: this.reconcileKey });
          this.reconcileKey = null; this.reconcileKeyCtx = null; // 成功废弃
        }
        this.refresh();
        wx.showToast({ title: action === 'commission-policy' ? '提成规则已保存' : action === 'commission-release' ? '提成应付已生成' : '人工核对已记录', icon: 'none' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.setData({ busy: false }); }
      return;
    }
    try {
      this.setData({ busy:true, error:'' });
      M.mutate(state => M.apply(state, action, id, form));
      this.refresh();
      wx.showToast({ title:action === 'commission-policy' ? '提成规则已保存' : action === 'commission-release' ? '提成应付已生成' : '人工核对已记录', icon:'none' });
    } catch (error) { this.fail(error.message); }
    finally { this.setData({ busy:false }); }
  },
  commissionPay(event) { const id=event.currentTarget.dataset.payable; if (id) N.open('/pages/admin/index?view=pay&id=' + encodeURIComponent(id)); },
  fail(error) { this.setData({ error }); wx.showToast({ title:error, icon:'none' }); wx.pageScrollTo?.({ scrollTop:0, duration:150 }); }
});
