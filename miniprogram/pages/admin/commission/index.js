const N = require('../../../utils/page-navigation');
const http = require('../../../api/http');
const finance = require('../../../api/finance');
const {selectVoucher} = require('../../../utils/finance-voucher');
const P = require('../../../utils/platform-performance'); // 阶段 3 API 态（提成在线；无 token/失败一律错误态，不回落本地演示）

const clone = require('../../../utils/clone');
const blankForm = () => ({ amount:'', signAfter:'',baseDone:'',capDone:'',assignee:'', note:'', file:null, checked:false, expectedVersion:0 });
const clean = () => ({ ready:false, error:'', busy:false, actor:{}, canEditPolicy:false, commissionRows:[], commissionProjects:[], commissionProjectIndex:0, commissionStaff:[], commissionStaffIndex:0, commissionForm:blankForm() });

Page({
  data:clean(),
  onLoad(options = {}) { this.options = options; this.selectedProjectId = options.id || ''; this.setData({returnLabel:options.from==='finance-profile'?'返回我的':'返回上一页'}); },
  returnToOrigin() { if(!this.data.busy) return N.back({fallback:['finance','finance-lead'].includes(P.apiPost()?.roleCode)?'/pages/admin/finance-desk/index?view=profile':undefined}); },
  onShow() { this.refresh(); 
  },
  refresh() {
    if (P.online()) return this.refreshOnline();
    this.setData({ ...clean(), error: '请先登录后端账号' });
  },
  async refreshOnline() { // API 态：身份=API 岗位（Codex P1-2）；项目=工程域∪已有规则（P1-5）；选人=org 真源（P1-3）
    const post = P.apiPost(), session=http.sessionGuard(), requested=http.currentPostId();
    const generation=this.__refresh=(this.__refresh||0)+1;
    const alive=()=>session()&&!this.__disposed&&http.currentPostId()===requested&&generation===this.__refresh;
    if (!post) return this.setData({ ...clean(), error: '请先登录后端账号', ready: true });
    try {
      const [view, members] = await Promise.all([
        P.loadCommission(),
        P.loadMembers().then(ms => ms.filter(m => ['boss', 'owner'].includes(m.roleCode)
          || ['finance', 'finance-lead', 'sales', 'sales-lead', 'engineer', 'project-manager'].includes(m.roleCode)))
          .catch(() => [])
      ]);
      if(!alive())return;
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
          signAfter:selected?.stages.find(s=>s.stage==='start')?.amountText.replace(/[¥,\s]/g,'')||'',baseDone:selected?.stages.find(s=>s.stage==='base')?.amountText.replace(/[¥,\s]/g,'')||'',capDone:selected?.stages.find(s=>s.stage==='topping')?.amountText.replace(/[¥,\s]/g,'')||'',assignee: selected ? selected.assigneeUserId : (staff[staffIndex] ? staff[staffIndex].id : '') },
        platPerf: true
      });
    } catch (error) {
      if(!alive())return;
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
  commissionInput(event) { this.setData({ [(this.data.commissionReview?'commissionReview.':'commissionForm.') + event.currentTarget.dataset.key]:event.detail.value, error:'' }); },
  cancelCommissionReview(){if(!this.data.busy){this.__pick=null;this.setData({commissionReview:null,evidenceUploading:false,error:''});}},
  commissionToggle() { this.setData({ 'commissionForm.checked':!this.data.commissionForm.checked, error:'' }); },
  onUnload(){this.__disposed=true;},
  async commissionUpload() {
    const session=http.sessionGuard(), post=http.currentPostId(), project=this.selectedProjectId;
    const review=this.data.commissionReview, pick=this.__pick={};
    const target=review?'commissionReview.file':'commissionForm.file';
    const alive=()=>!this.__disposed && session() && http.currentPostId()===post && this.__pick===pick &&
      this.selectedProjectId===project && !!this.data.commissionReview===!!review && (!review || (this.data.commissionReview.projectId===review.projectId && this.data.commissionReview.stage===review.stage));
    try {
      const file=await selectVoucher({alive});
      if(!file||!alive())return;
      this.setData({evidenceUploading:true,[target]:null});
      const assetId=await finance.uploadVoucher(file.path,file.name);
      if(alive())this.setData({[target]:{name:file.name,assetId:String(assetId)},error:''});
    } catch(e){if(alive())this.fail(e.message||'凭据上传失败');}
    finally{if(!this.__disposed&&this.__pick===pick)this.setData({evidenceUploading:false});}
  },
  async openCommissionEvidence(event){
    const id=event.currentTarget.dataset.id;
    if(!/^\d+$/.test(String(id)))return;
    const session=http.sessionGuard(),post=http.currentPostId(),alive=()=>!this.__disposed&&session()&&http.currentPostId()===post;
    try {
      const ticket=await http.post('/franchise/assets/'+id+'/download-tickets');
      if(!alive())return;
      const file=await require('../../../utils/b-asset-download').downloadTicketFile({http,downloadUrl:ticket.downloadUrl,alive,label:'核对凭据'});
      if(!alive())return;
      if(/^image\//.test(file.contentType))wx.previewImage({urls:[file.tempFilePath]});
      else wx.openDocument({filePath:file.tempFilePath,fileType:'pdf'});
    }catch(e){if(alive())this.fail(e.message||'凭据打开失败');}
  },
  async commissionAction(event) {
    if (this.data.busy||this.data.evidenceUploading) return;
    const data = event.currentTarget.dataset, action = data.action;
    if (action === 'commission-reconcile' && !data.confirmed) {
      const project = this.data.commissionRows.find(row => row.projectId === String(data.id));
      const stage = project?.stages.find(row => row.stage === data.stage);
      if (!stage?.canReconcile) return;
      this.__pick = null;
      this.setData({ commissionReview: {
        projectId: data.id, stage: data.stage, projectName: project.project, label: stage.label,
        received: stage.receivedText, outstanding: stage.outstandingText, eligible: stage.eligible,
        note: '', file: null
      }, error: '' });
      wx.pageScrollTo({scrollTop:0,duration:0});
      return;
    }
    const id = data.id || this.selectedProjectId;
    const form = clone(action==='commission-reconcile'?this.data.commissionReview:this.data.commissionForm);
    if (data.stage) form.stage = data.stage;
    if (data.version !== undefined) form.expectedVersion = Number(data.version);
    if (P.online() && ['commission-policy', 'commission-release', 'commission-reconcile'].includes(action)) {
      try {
        this.setData({ busy: true, error: '' });
        const person = this.data.commissionStaff[this.data.commissionStaffIndex] || {};
        if (action === 'commission-policy') {
          const fields=['amount','signAfter','baseDone','capDone'];
          if(fields.some(k=>!/^\d+(\.\d{1,2})?$/.test(String(form[k]).trim())))throw {msg:'请填写总额及三期金额，未约定期填0'};
          const cents=k=>Math.round(Number(form[k])*100);
          if(cents('amount')<=0||cents('signAfter')+cents('baseDone')+cents('capDone')!==cents('amount'))throw {msg:'三期提成之和须等于提成总额'};
          if(!form.checked||!form.note.trim()||!form.file)throw {msg:'请填写绩效调整依据、上传凭据并确认核对'};
          // R3：键绑定项目上下文（切项目换键——防跨项目旧键死锁）
          if (!this.policyKey || this.policyKeyCtx !== String(id)) {
            this.policyKey = 'perf-policy:' + id + ':' + Date.now();
            this.policyKeyCtx = String(id);
          }
          await P.perform('policy', { projectId: id, amount: form.amount, signAfter:form.signAfter,baseDone:form.baseDone,capDone:form.capDone,assigneeUserId: person.id,
            assigneeName: person.name, evidenceRef: form.file.assetId, note: form.note, idem: this.policyKey });
          this.policyKey = null; this.policyKeyCtx = null; // 成功废弃
        } else if (action === 'commission-release') {
          await P.perform('release', { projectId: id, stage: form.stage,
            idem: 'perf-release:' + id + ':' + form.stage });
        } else {
          if(!form?.note?.trim()||!form.file)throw {msg:'请上传核对依据并填写说明'};
          // R3：键绑定项目+阶段上下文
          const reconcileCtx = String(id) + ':' + form.stage;
          if (!this.reconcileKey || this.reconcileKeyCtx !== reconcileCtx) {
            this.reconcileKey = 'perf-reconcile:' + reconcileCtx + ':' + Date.now();
            this.reconcileKeyCtx = reconcileCtx;
          }
          await P.perform('reconcile', { projectId: id, stage: form.stage, evidenceRef: form.file.assetId, note: form.note,
            idem: this.reconcileKey });
          this.reconcileKey = null; this.reconcileKeyCtx = null; // 成功废弃
        }
        this.setData({commissionReview:null});this.refresh();
        wx.showToast({ title: action === 'commission-policy' ? '提成规则已保存' : action === 'commission-release' ? '提成应付已生成' : '人工核对已记录', icon: 'none' });
      } catch (error) { this.fail(P.errorText(error)); }
      finally { this.setData({ busy: false }); }
      return;
    }
    this.fail('请先登录后端账号');
  },
  commissionPay(event) { const id=String(event.currentTarget.dataset.payable||''); const stage=this.data.commissionRows.flatMap(r=>r.stages).find(s=>s.payableId===id);if(stage?.canPay) N.open('/pages/admin/finance-desk/index?view=flows&tab=payables&messageType=fin_payable&messageObject='+encodeURIComponent(id)+'&from=commission'); },
  fail(error) { this.setData({ error }); wx.showToast({ title:error, icon:'none' }); wx.pageScrollTo?.({ scrollTop:0, duration:150 }); }
});
