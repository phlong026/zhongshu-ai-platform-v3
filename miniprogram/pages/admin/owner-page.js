const W = require('./owner-model');
const H = require('./owner-home');
const B = require('./owner-business');
const P = require('./owner-performance');
const Settings = require('./owner-settings');
const A = require('../../utils/account');
const M = require('./model');
const { routeValue } = require('../../utils/view-container');
const titles = { home:'首页', finance:'经营', performance:'绩效', profile:'我的', stat:'经营原单明细', metric:'经营指标明细', appointments:'预约到店', acquisition:'内容获客', todos:'全部待办', approvals:'待我审批', 'finance-progress':'财务办理进度', receivables:'应收账款', payables:'应付账款', receivable:'应收原单', payable:'应付原单', business:'业务概览', engineering:'工程概览', targets:'月度目标设置', team:'团队绩效', reviews:'结果复核', activity:'操作记录', settings:'审批与分工', rules:'我的规矩', company:'门店资料', handover:'工程负责人交接' };

module.exports = function(kind) {
  return {
    data:{ ready:false, desk:null, title:titles[kind] || '', keyword:'', todoTab:'all', homeTodos:[], homeTodoTotal:0, rows:[], total:0, pageLimit:20, record:null, error:'', form:{}, saving:false, selectedDepartment:'all', expandedPerson:'', companyForm:{}, assignmentForm:{}, managementSettings:null, performanceSettings:null },
    openDesignPolicy(){ require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=policy'); },
    logout(){ return require('../../utils/session-actions').logout(); },
    onLoad(options = {}) {
      this.options = { ...options };
      for (const key of ['id','person','department','tab','metric']) this.options[key] = routeValue(options[key]);
      this.kind = kind === 'detail' ? titles[options.view] ? options.view : 'business' : kind;
      this.setData({ title:titles[this.kind] });
    },
    onShow() {
      if (!W.allowed()) { this.setData({ready:false,desk:null,rows:[]}); wx.switchTab({url:'/pages/profile/index'}); return; }
      this.refresh();
    },
    refresh() {
      try {
        const desk = W.snapshot(), view = this.kind;
        this.ownerSnapshot = desk;
        desk.home = H.home(desk);
        Object.assign(desk, B.forView(view, desk));
        if (view === 'performance') desk.performance = P.build(desk, this.data.selectedDepartment, this.data.expandedPerson);
        const keyword = this.data.keyword.trim();
        const homeRows = H.rows(view, desk, this.options);
        let rows = homeRows !== null ? homeRows : view === 'todos' ? H.todoRows(desk, this.options.tab || 'all') : ['receivables','payables','stat'].includes(view) ? B.rows(view, desk, this.options.id) : W.rowsFor(view, desk, this.options.person, this.options.department, this.options.tab || 'all');
        if (keyword) rows = rows.filter(row => [row.name,row.status,row.detail,row.meta,row.tag,row.key].filter(Boolean).join(' ').includes(keyword));
        const total = rows.length;
        rows = rows.slice(0, this.data.pageLimit);
        const form = view === 'targets' ? this.dirty ? this.data.form : P.formFor(desk) : this.data.form;
        const performanceSettings = view === 'targets' ? P.settingsFor({ ...desk, goals:{ ...desk.goals, ...form, version:form.expectedVersion } }) : null;
        const settings = ['company','settings','rules'].includes(view) ? Settings.load(desk.state) : null;
        if (settings) settings.assignmentFields = settings.assignmentFields.map(field=>({ ...field, selectedName:field.options.find(option=>option.id===(this.assignmentsDirty?this.data.assignmentForm.assignments[field.id]:field.value))?.name || '请选择' }));
        const companyForm = view === 'company' ? this.companyDirty ? this.data.companyForm : { ...settings.company, expectedVersion:settings.version } : {};
        const assignmentForm = view === 'settings' ? this.assignmentsDirty ? this.data.assignmentForm : { assignments:Object.fromEntries(settings.assignmentFields.filter(field=>field.value).map(field=>[field.id,field.value])), expectedVersion:settings.version } : {};
        const rulesForm = view === 'rules' ? (this.rulesDirty ? this.data.rulesForm : { expenseLine: String(settings.approvals.expenseLine) }) : {};
        const display = { name:desk.name, company:desk.company, date:desk.date, sample:desk.sample, period:desk.period, periodLabel:desk.periodLabel, actor:{ role:desk.actor.role, companyId:desk.actor.companyId } };
        if (view === 'company') display.staff = desk.staff.map(person=>({id:person.id}));
        if (view === 'home') { display.home = desk.home; display.ledger = desk.ledger; display.approvalCount = desk.approvalCount; display.income = desk.income; display.paid = desk.paid; }
        if (view === 'finance') display.business = desk.business;
        if (view === 'performance') display.performance = desk.performance;
        const homeTodos = view === 'home' ? H.visibleTodos(desk, this.data.todoTab) : [];
        this.setData({ ready:true, desk:display, rows, total, projects:[], form, performanceSettings, managementSettings:settings, companyForm, assignmentForm, rulesForm, view, record:B.recordFor(view,desk,this.options.id), error:'', homeTodos, homeTodoTotal:view==='home'?H.todoRows(desk,this.data.todoTab).length:0 });
      } catch(error) { this.setData({error:error.message,ready:false,desk:null,rows:[]}); }
    },
    search(e) { this.setData({keyword:e.detail.value,pageLimit:20}); this.refresh(); },
    clear() { this.setData({keyword:'',pageLimit:20}); this.refresh(); },
    loadMore() { this.setData({pageLimit:this.data.pageLimit + 20}); this.refresh(); },
    todoTab(e) {
      const todoTab=e.currentTarget.dataset.tab, snapshot=this.ownerSnapshot;
      if(snapshot) this.setData({todoTab,homeTodos:H.visibleTodos(snapshot,todoTab),homeTodoTotal:H.todoRows(snapshot,todoTab).length});
    },
    departmentFilter(e) { this.setData({selectedDepartment:e.currentTarget.dataset.department,expandedPerson:''}); this.refresh(); },
    togglePerson(e) { const id=e.currentTarget.dataset.person; this.setData({expandedPerson:this.data.expandedPerson===id?'':id}); this.refresh(); },
    openSource(e) {
      const url=e.currentTarget.dataset.url;
      const allowed=this.data.desk?.performance?.groups.flatMap(group=>group.rows).flatMap(person=>person.recentRecords).some(row=>row.url===url);
      if (allowed) require('../../utils/page-navigation').open(url);
    },
    openRow(e) { this.go(e); },
    month(e) {
      if (!W.allowed()) return;
      const period=e.detail.value;
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) return;
      M.mutate(state=>{state.period=period;});
      this.dirty=false; this.setData({pageLimit:20}); this.refresh();
    },
    go(e) {
      if (!W.allowed()) return this.onShow();
      const d=e.currentTarget.dataset, s=W.snapshot(), home=H.home(s);
      const actions = {
        payroll:'/pages/admin/operations/index?view=payroll', commissions:'/pages/admin/commission/index', receivables:W.detail('receivables'),payables:W.detail('payables'),costs:'/pages/admin/operations/index?view=costs',funds:'/pages/admin/operations/index?view=funds',operating:'/pages/admin/operations/index',business:W.detail('business'),engineering:W.detail('engineering'),approvals:W.detail('approvals'),todos:W.detail('todos',{tab:this.data.todoTab}),reports:'/pages/admin/finance/index',performance:'/pages/admin/tasks/index?view=team',tasks:'/pages/admin/tasks/index?view=team',
        promotion:'/pages/admin/operations/index?view=expenses&id='+encodeURIComponent('company:'+s.actor.companyId)+'&purpose='+encodeURIComponent('推广获客'),
        goals:W.detail('targets'),team:W.detail('team'),reviews:W.detail('reviews'),activity:W.detail('activity'),receipts:W.admin('finance')+'&tab='+encodeURIComponent('收款'),payments:W.detail('payables'),financeProgress:W.detail('finance-progress'),staff:W.admin('staff'),settings:W.detail('settings'),handover:W.detail('handover'),company:W.detail('company'),
        profile:'/pages/account/index?view=profile',messages:'/pages/account/index?view=messages',learning:'/pages/account/index?view=learning',services:'/pages/account/index?view=services',selection:'/pages/account/index?view=selection',help:'/pages/account/index?view=help',privacy:'/pages/account/index?view=privacy',rules:W.admin('rules'),engineeringTeam:W.admin('engineering-team'),businessTeam:W.admin('business-team'),designTeam:W.admin('design-team'),financeTeam:W.admin('finance-team')
      };
      let url=d.url || actions[d.action];
      if(d.key) url=[...this.data.rows,...s.approvals,...s.queue,...s.focus,...s.projectRows,...s.team,...s.events,...H.todoRows(s),...home.six,...home.business.notes,home.business.appointments,home.business.acquisition,...home.engineering.sites,...B.rows(this.kind,s,this.options.id)].find(row=>row?.key===d.key)?.url;
      if(url) require('../../utils/page-navigation').open(url);
    },
    input(e) {
      this.dirty=true;
      const d=e.currentTarget.dataset, form=A.clone(this.data.form);
      if(d.person) form.staff[d.person]=e.detail.value; else form[d.field]=e.detail.value;
      this.setData({form,error:''});
    },
    goalFieldInput(e) {
      const d=e.currentTarget.dataset, form=A.clone(this.data.form), value=e.detail.value;
      if(d.scope==='company') form[d.field]=value;
      else if(d.scope==='department') (form.departmentGoals[d.department]||(form.departmentGoals[d.department]={}))[d.metric]=value;
      else if(d.scope==='staff') {
        const person=form.staffDepartmentGoals[d.person]||(form.staffDepartmentGoals[d.person]={});
        (person[d.department]||(person[d.department]={}))[d.metric]=value;
      } else return;
      this.dirty=true; this.setData({form,error:''}); this.refresh();
    },
    savePerformanceGoals(){ if(this.kind==='targets') return this.fail('旧目标页只读，请使用绩效任务'); return this.save(); },
    save() {
      if(this.kind==='targets') return this.fail('旧目标页只读，请使用绩效任务');
      if(this.data.saving) return;
      try { this.setData({saving:true}); W.saveGoals(this.data.desk.period,this.data.form); this.dirty=false; this.refresh(); wx.showToast({title:'目标已保存',icon:'success'}); }
      catch(error) { this.fail(error.message); }
      finally { this.setData({saving:false}); }
    },
    companyInput(e) { this.companyDirty=true; this.setData({['companyForm.'+e.currentTarget.dataset.field]:e.detail.value,error:''}); },
    assignmentChange(e) {
      const field=this.data.managementSettings.assignmentFields.find(row=>row.id===e.currentTarget.dataset.field);
      const selected=field?.options[Number(e.detail.value)];
      if(selected) { this.assignmentsDirty=true; this.setData({['assignmentForm.assignments.'+field.id]:selected.id,error:''}); }
    },
    saveCompany(){ this.saveConfiguration('management-company',this.data.companyForm); },
    saveAssignments(){ this.saveConfiguration('management-assignments',this.data.assignmentForm); },
    rulesInput(e) { this.rulesDirty=true; this.setData({['rulesForm.'+e.currentTarget.dataset.field]:e.detail.value,error:''}); },
    saveRules() {
      if(this.data.saving) return;
      try { this.setData({saving:true}); M.mutate(state=>M.apply(state,'approval-settings',state.companyId,{expenseLine:this.data.rulesForm.expenseLine,expectedVersion:this.data.managementSettings.version})); this.rulesDirty=false; this.refresh(); wx.showToast({title:'规矩已保存',icon:'success'}); }
      catch(error) { this.fail(error.message); }
      finally { this.setData({saving:false}); }
    },
    saveConfiguration(action,form) {
      if(this.data.saving) return;
      try { this.setData({saving:true}); M.mutate(state=>M.apply(state,action,state.companyId,form)); this.companyDirty=false; this.assignmentsDirty=false; this.refresh(); wx.showToast({title:'配置已保存',icon:'success'}); }
      catch(error) { this.fail(error.message); }
      finally { this.setData({saving:false}); }
    },
    markHandover(e) {
      const id=e.currentTarget.dataset.id;
      if(!this.data.rows.some(row=>row.workId===id&&!row.marked)) return;
      wx.showModal({title:'标记需交接',editable:true,placeholderText:'填写需转交责任的原因',success:result=>{
        if(!result.confirm) return;
        try { M.mutate(state=>M.apply(state,'handover-mark',id,{checked:true,reason:String(result.content||'').trim()})); this.refresh(); wx.showToast({title:'已加入交接待办',icon:'success'}); }
        catch(error) { this.fail(error.message); }
      }});
    },
    archiveOperating() {
      const snapshot=this.ownerSnapshot, info=this.data.desk.business.snapshotInfo;
      if(!info.canArchive) return;
      const expectedSignature=require('../../utils/operating-report').sourceSignature(snapshot.state,snapshot.period);
      wx.showModal({title:'确认月度结账',content:'',editable:true,placeholderText:'填写本月结账说明',success:result=>{
        if(!result.confirm) return;
        try { M.mutate(state=>M.apply(state,'operating-snapshot',snapshot.period,{checked:true,note:String(result.content||'').trim(),version:info.version,expectedSignature})); this.refresh(); wx.showToast({title:'结账已保存',icon:'success'}); }
        catch(error) { this.fail(error.message); }
      }});
    },
    copyReminder() {
      const reminder=this.data.record?.reminder;
      if(!reminder?.ready) return this.fail(reminder?.reason||'请从实时应收原单生成话术');
      wx.setClipboardData({data:reminder.text,fail:()=>this.fail('复制失败，请重试')});
    },
    openReminderFile() {
      const file=this.data.record?.reminder?.file, path=file?.path||file?.url;
      if(!path) return this.fail('暂无可查看的实际现场资料');
      if(/\.(jpg|jpeg|png|webp)$/i.test(file.name||path)) wx.previewImage({urls:[path],fail:()=>this.fail('现场照片无法打开，请回原项目核对')});
      else wx.openDocument({filePath:path,fail:()=>this.fail('现场资料无法打开，请回原项目核对')});
    },
    fail(error) { this.setData({error}); wx.showToast({title:error,icon:'none'}); }
  };
};
