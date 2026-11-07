const W = require('./owner-model');
const PP = require('../../utils/platform-performance'); // 阶段 3 API 态（绩效 Tab 平台汇总）
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
    data:{ ready:false, desk:null, title:titles[kind] || '', keyword:'', platformTodos:null, todoTab:'all', homeTodos:[], homeTodoTotal:0, rows:[], total:0, pageLimit:20, record:null, error:'', form:{}, saving:false, selectedDepartment:'all', expandedPerson:'', companyForm:{}, assignmentForm:{}, managementSettings:null, performanceSettings:null },
    openDesignPolicy(){ require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=policy'); },
    logout(){ return require('../../utils/session-actions').logout(); },
    retryPlatformTodos(){ require('../../utils/platform-todos-page').retry(this); },
    openPlatformTodo(e){ require('../../utils/platform-todos-page').open(this, e); },
    onLoad(options = {}) {
      this.options = { ...options };
      for (const key of ['id','person','department','tab','metric']) this.options[key] = routeValue(options[key]);
      this.kind = kind === 'detail' ? titles[options.view] ? options.view : 'business' : kind;
      this.setData({ title:titles[this.kind] });
      require('../../utils/platform-todos-page').init(this);
    },
    onShow() {
      // M+：在线态全部视图走平台链路——不进 W.snapshot（本地账簿加载会写 demo Storage；非 performance/finance 视图自然空态）
      if (PP.online()) {
        if (!this.platPeriod) this.platPeriod = PP.currentPeriod();
        this.refreshPlatView();
        this.refreshPlatSummary();
        return;
      }
      if (!W.allowed()) { this.setData({ready:false,desk:null,rows:[]}); wx.switchTab({url:'/pages/profile/index'}); return; }
      this.refresh();
      this.refreshPlatSummary();
    },
    archivePlatSnapshot() { // M10-T03：快照写服务端（operating.snapshot；period=选中月——历史月归档提交选中月）
      if (!PP.online()) return;
      const period = this.platPeriod || PP.currentPeriod();
      const requestGen = (this.platReqGen = (this.platReqGen || 0) + 1);
      PP.api.operating.snapshot(period, '老板端月度归档', 'snap-' + period + '-' + requestGen)
        .then(() => {
          if (requestGen !== this.platReqGen) return;
          wx.showToast({ title: '快照已归档', icon: 'success' });
          this.refreshPlatSummary();
        })
        .catch((e) => {
          if (requestGen !== this.platReqGen) return;
          // R2 P2：002 签名未变化=数据无修订（提示非错误）；HTTP 层字段为 message
          const msg = (e && (e.message || e.msg)) || '归档失败';
          wx.showToast({ title: msg.indexOf('002') >= 0 || msg.indexOf('锁定') >= 0 ? '数据无修订，已有快照' : msg, icon: 'none' });
        });
    },
    refreshPlatView() { // 平台绩效视图（在线独占；零 demo 读写）
      // M10-T03/R2 P1-7：渲染前核对缓存归属岗位——换岗后旧岗位汇总不进首屏（串台防护）
      const currentPost = PP.apiPost();
      if (this.platSummary && this.platSummaryPost
          && currentPost && String(this.platSummaryPost) !== String(currentPost.postId)) {
        this.platSummary = null; this.platSummaryKey = '';
      }
      const view = this.kind;
      this.setData({
        ready: true, view, title: titles[view] || '', rows: [], total: 0, error: this.platDegraded
          ? '绩效数据加载失败，请下拉重试' : '',
        platDegraded: !!this.platDegraded,
        operatingDegraded: !!this.platOperatingDegraded, // R2 P2-9：经营接口独立降级可见
        desk: {
          name: '', company: '', date: '', sample: false,
          period: this.platPeriod, periodLabel: this.platPeriod,
          // M10-T03/R4 P1：finance 在线视图 desk.operating 直供模板（business.historical 等本地键不动）
          operating: this.platSummary && this.platSummary.operating || null,
          business: Object.assign({}, (this.data.desk && this.data.desk.business) || {}, {
            // R5 P1：historical=选中月早于当前月（历史快照态）；保留模板消费的 headline/levels/analytics 等本地字段
            historical: this.platPeriod && this.platPeriod < PP.currentPeriod(),
            snapshotInfo: { available: false, note: '在线快照经「归档月度快照」写服务端' },
            operating: this.platSummary && this.platSummary.operating || null }),
          performance: PP.platPerformance(this.platSummary, this.platPeriod, this.data.selectedDepartment),
          actor: { role: (PP.apiPost() || {}).roleCode || '', companyId: (PP.apiPost() || {}).orgId || '' }
        },
        form: {}, performanceSettings: null, managementSettings: null, companyForm: {}, assignmentForm: {}, rulesForm: {}
      });
    },
    refreshPlatSummary() { // API 态：三组指标+目标对照（平台真源；缓存绑定 月份+岗位+请求代次——R2 P2-6/P1-3）
      if (!PP.online()) return;
      const post = PP.apiPost();
      const period = this.platPeriod || PP.currentPeriod();
      const cacheKey = period + ':' + (post ? post.postId : '');
      const requestGen = (this.platReqGen = (this.platReqGen || 0) + 1);
      // M10-T03：在线余额三源并行——任务/目标/经营 analytics（与财务一致；迟到响应丢弃防串台）
      Promise.all([PP.api.tasks.summary(period), PP.api.goals.get(period),
        PP.api.operating.analytics(period).catch(() => null)])
        .then(([summary, goals, operating]) => {
          if (requestGen !== this.platReqGen) return; // 迟到响应丢弃（退出/换岗不串台）
          this.platSummary = { summary, goals, operating };
          this.platSummaryKey = cacheKey;
          this.platSummaryPost = post.postId; // M10-T03：缓存归属岗位（换岗不串台）
          this.platDegraded = false;
          this.platOperatingDegraded = operating == null; // R2 P2：经营接口失败独立降级标记
          if (this.kind === 'performance' || this.kind === 'finance') this.refreshPlatView(); // R5 P1：finance 响应后刷新（desk.operating 同步选中月）
          // M10-T03 P1：home/finance 视图在线余额转页面（operating 真源——不再读本地账）
          if ((this.kind === 'home' || this.kind === 'finance') && operating) {
            this.setData({ operating: {
              incomeText: ((operating.trend || []).slice(-1).pop() || {}).incomeCents,
              paidText: ((operating.trend || []).slice(-1).pop() || {}).paidCents,
              netText: ((operating.trend || []).slice(-1).pop() || {}).netCents,
              receivableCents: (operating.totals || {}).receivableOutstandingCents,
              payableOutstandingCents: (operating.totals || {}).payableOutstandingCents,
              period
            }});
          }
        })
        .catch(() => {
          if (requestGen !== this.platReqGen) return;
          this.platSummary = null; this.platSummaryKey = ''; this.platDegraded = true;
          this.platOperatingDegraded = false;
          if (this.kind === 'performance') this.refreshPlatView(); // R2 P2-6：失败同步可见状态
        });
    },
    refresh() {
      if (PP.online() && this.kind === 'performance') { this.refreshPlatView(); return; } // R2 P1-2
      try {
        const desk = W.snapshot(), view = this.kind;
        this.ownerSnapshot = desk;
        desk.home = H.home(desk);
        Object.assign(desk, B.forView(view, desk));
        // M+（D1）：删除本地绩效回落 P.build——无条件渲染平台绩效结构（无汇总时为空态，依赖 platDegraded 错误横幅提示重试）
        if (view === 'performance') desk.performance = PP.platPerformance(this.platSummary || null, this.platPeriod || desk.period || PP.currentPeriod(), this.data.selectedDepartment);
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
        if (view === 'home') {
          // M+（D3）：本地数值兜底（ledger/income/paid，出自离线 W.snapshot 账簿）已删除——经营数值只来自平台 operating
          display.home = desk.home; display.approvalCount = desk.approvalCount;
        }
        if (view === 'finance') {
          display.business = desk.business;
          // M+（D3）：仅保留在线 operating 注入；拉取失败仅置 operatingDegraded 错误卡，不回落本地账簿
          if (PP.online()) {
            if (this.platSummary && this.platSummary.operating) {
              display.operating = this.platSummary.operating;
            } else {
              display.operatingDegraded = true;
            }
          }
        }
        if (view === 'performance') display.performance = desk.performance;
        // M+（D6）：在线态不渲染本地待办行——platformTodos 优先，本地行仅非 API 态显示
        const homeTodos = view === 'home' && !PP.online() ? H.visibleTodos(desk, this.data.todoTab) : [];
        this.setData({ ready:true, desk:display, rows, total, projects:[], form, performanceSettings, managementSettings:settings, companyForm, assignmentForm, rulesForm, view, record:B.recordFor(view,desk,this.options.id), error:'', homeTodos, homeTodoTotal:view==='home'&&!PP.online()?H.todoRows(desk,this.data.todoTab).length:0 });
        if (view === 'home') require('../../utils/platform-todos-page').refresh(this, false);
      } catch(error) { this.setData({error:error.message,ready:false,desk:null,rows:[]}); }
    },
    search(e) { this.setData({keyword:e.detail.value,pageLimit:20}); this.refresh(); },
    clear() { this.setData({keyword:'',pageLimit:20}); this.refresh(); },
    loadMore() { this.setData({pageLimit:this.data.pageLimit + 20}); this.refresh(); },
    todoTab(e) {
      const todoTab=e.currentTarget.dataset.tab, snapshot=this.ownerSnapshot;
      if (PP.online()) return this.setData({todoTab}); // M+（D6）：在线态只切 tab，不渲染本地待办行
      if(snapshot) this.setData({todoTab,homeTodos:H.visibleTodos(snapshot,todoTab),homeTodoTotal:H.todoRows(snapshot,todoTab).length});
    },
    departmentFilter(e) { this.setData({selectedDepartment:e.currentTarget.dataset.department,expandedPerson:''});
      if (PP.online() && this.kind === 'performance') return this.refreshPlatView(); // R2 P1-2
      this.refresh(); },
    togglePerson(e) { const id=e.currentTarget.dataset.person; this.setData({expandedPerson:this.data.expandedPerson===id?'':id}); this.refresh(); },
    openSource(e) {
      const url=e.currentTarget.dataset.url;
      const allowed=this.data.desk?.performance?.groups.flatMap(group=>group.rows).flatMap(person=>person.recentRecords).some(row=>row.url===url);
      if (allowed) require('../../utils/page-navigation').open(url);
    },
    openRow(e) { this.go(e); },
    month(e) {
      const period=e.detail.value;
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) return;
      // R2 P1-3：performance 在线态换月独立 platPeriod（查询/缓存键/展示统一），不写本地账簿
      if (PP.online() && this.kind === 'performance') {
        this.platPeriod = period;
        this.refreshPlatView();
        this.refreshPlatSummary();
        return;
      }
      // M10-T03/R3 P1：finance 在线态换月同步 platPeriod 并刷新（historical/结账按钮随选中月更新）
      if (PP.online() && this.kind === 'finance') {
        this.platPeriod = period;
        this.refreshPlatSummary();
        this.refreshPlatView();
        return;
      }
      if (!W.allowed()) return;
      M.mutate(state=>{state.period=period;});
      this.dirty=false; this.setData({pageLimit:20}); this.refresh();
    },
    go(e) {
      if (!W.allowed()) return this.onShow();
      const d=e.currentTarget.dataset, s=W.snapshot(), home=H.home(s);
      // M+（D5）：本地 admin/detail 链接改指平台页——财务→finance-desk flows，组织→api-members，绩效→绩效任务
      const flows = tab => '/pages/admin/finance-desk/index?view=flows&tab=' + tab;
      const members = '/pages/account/index?view=api-members';
      const actions = {
        commissions:'/pages/admin/commission/index', receivables:flows('receivables'),payables:flows('payables'),costs:'/pages/admin/operations/index?view=costs',funds:'/pages/admin/operations/index?view=funds',operating:'/pages/admin/operations/index',business:W.detail('business'),engineering:W.detail('engineering'),approvals:flows('payables'),todos:flows('receivables'),reports:'/pages/admin/finance/index',performance:'/pages/admin/tasks/index?view=team',tasks:'/pages/admin/tasks/index?view=team',
        promotion:'/pages/admin/operations/index?view=expenses&id='+encodeURIComponent('company:'+s.actor.companyId)+'&purpose='+encodeURIComponent('推广获客'),
        goals:W.detail('targets'),team:W.detail('team'),reviews:W.detail('reviews'),activity:W.detail('activity'),receipts:flows('receivables'),payments:flows('payables'),financeProgress:flows('receivables'),staff:members,settings:W.detail('settings'),handover:W.detail('handover'),company:members,
        profile:'/pages/account/index?view=profile',messages:'/pages/account/index?view=messages',learning:'/pages/account/index?view=learning',services:'/pages/account/index?view=services',selection:'/pages/account/index?view=selection',help:'/pages/account/index?view=help',privacy:'/pages/account/index?view=privacy',rules:'/pages/admin/tasks/index',engineeringTeam:members,businessTeam:members,designTeam:members,financeTeam:members
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
      // M+（D4）：仅保留在线分流到服务端快照（operating.snapshot）；本地 M.mutate 结账分支已删除
      if (PP.online()) return this.archivePlatSnapshot();
      return this.fail('请登录平台岗位后再归档月度快照');
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
