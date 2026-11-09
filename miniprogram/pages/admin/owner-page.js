const PP = require('../../utils/platform-performance'); // 阶段 3 API 态（绩效 Tab 平台汇总）
const A = require('../../utils/account');
const OwnerHome = require('../../utils/platform-owner-home');
const Engineering = require('../../utils/platform-engineering');
const http = require('../../api/http');
const { routeValue } = require('../../utils/view-container');
// 切生产收口：owner-model/owner-home/owner-business/owner-performance/owner-settings/model（本地账簿链）引用已下线
const titles = { home:'首页', finance:'经营', performance:'绩效', profile:'我的', stat:'经营原单明细', metric:'经营指标明细', appointments:'预约到店', acquisition:'内容获客', todos:'全部待办', approvals:'待我审批', 'finance-progress':'财务办理进度', receivables:'应收账款', payables:'应付账款', receivable:'应收原单', payable:'应付原单', business:'业务概览', engineering:'工程概览', targets:'月度目标设置', team:'团队绩效', reviews:'结果复核', activity:'操作记录', settings:'审批与分工', rules:'我的规矩', company:'门店资料', handover:'工程负责人交接' };

module.exports = function(kind) {
  return {
    data:{ ready:false, desk:null, title:titles[kind] || '', keyword:'', platformTodos:null, todoTab:'all', homeTodos:[], homeTodoTotal:0, rows:[], total:0, pageLimit:20, record:null, error:'', form:{}, saving:false, selectedDepartment:'all', expandedPerson:'', companyForm:{}, assignmentForm:{}, managementSettings:null, performanceSettings:null },
    openDesignPolicy(){ require('../../utils/page-navigation').open('/pages/workflow/commission/index?view=policy'); },
    logout(){ return require('../../utils/session-actions').logout(); },
    retryPlatformTodos(){ require('../../utils/platform-todos-page').retry(this); },
    retryHome(){ this.refresh(); },
    openPlatformTodo(e){ require('../../utils/platform-todos-page').open(this, e); },
    onLoad(options = {}) {
      this.options = { ...options };
      for (const key of ['id','person','department','tab','metric']) this.options[key] = routeValue(options[key]);
      this.kind = kind === 'detail' ? titles[options.view] ? options.view : 'business' : kind;
      this.setData({ title:titles[this.kind] });
      require('../../utils/platform-todos-page').init(this);
    },
    onShow() {
      // 切生产收口：owner 族页面纯在线——无平台会话不再走本地账簿（W.snapshot 双轨下线），统一回个人页登录
      if (!PP.online()) { this.setData({ready:false,desk:null,rows:[]}); wx.switchTab({url:'/pages/profile/index'}); return; }
      if (!this.platPeriod) this.platPeriod = PP.currentPeriod();
      this.refresh();
      // 姓名真源（2026-11-09）：切生产收口后 desk.name 不再有本地账簿填充——
      // 经 profile/mine 同步后端 nickname 到「你好，」问候（后台改名即生效）
      if (http.token()) {
        const alive = http.requestGuard('profile');
        http.get('/platform/profile/mine').then(remote => {
          if (!alive()) return;
          this.platProfileName = (remote && remote.nickname) || '';
          this.setData({ 'desk.name': this.platProfileName });
        }).catch(() => {});
      }
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
      const ownerHome = OwnerHome.view({
        period: this.platPeriod,
        post: currentPost,
        name: this.platProfileName,
        summary: this.platSummary && this.platSummary.summary,
        operating: this.platSummary && this.platSummary.operating,
        projectsStatus: this.ownerProjectsStatus,
        projects: this.ownerProjects
      });
      this.setData({
        ready: true, view, title: titles[view] || '', rows: [], total: 0, error: this.platDegraded
          ? '绩效数据加载失败，请重试' : this.platOperatingDegraded
            ? '经营数据加载失败，其他指标已保留' : '',
        platDegraded: !!this.platDegraded,
        operatingDegraded: !!this.platOperatingDegraded, // R2 P2-9：经营接口独立降级可见
        desk: {
          ...ownerHome,
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
      const requestSession = http.sessionGeneration();
      const requestPostId = String((post && post.postId) || '');
      const alive = () => requestGen === this.platReqGen
        && requestSession === http.sessionGeneration()
        && requestPostId === String((PP.apiPost() || {}).postId || '');
      // M10-T03：在线余额三源并行——任务/目标/经营 analytics（与财务一致；迟到响应丢弃防串台）
      const projects = this.kind === 'home'
        ? Engineering.api.projects.page().then(rows => ({ status:'ready', rows })).catch(() => ({ status:'error', rows:null }))
        : Promise.resolve({ status:this.ownerProjectsStatus, rows:this.ownerProjects });
      Promise.all([PP.api.tasks.summary(period), PP.api.goals.get(period),
        PP.api.operating.analytics(period).catch(() => null), projects])
        .then(([summary, goals, operating, projectResult]) => {
          if (!alive()) return; // 迟到响应丢弃（换月/退出/换岗不串台）
          this.ownerProjectsStatus = projectResult.status;
          this.ownerProjects = projectResult.rows;
          this.platSummary = { summary, goals, operating };
          this.platSummaryKey = cacheKey;
          this.platSummaryPost = requestPostId; // M10-T03：缓存归属岗位（换岗不串台）
          this.platDegraded = false;
          this.platOperatingDegraded = operating == null; // R2 P2：经营接口失败独立降级标记
          this.refreshPlatView();
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
          if (!alive()) return;
          this.platSummary = null; this.platSummaryKey = ''; this.platDegraded = true;
          this.platOperatingDegraded = false;
          if (this.kind === 'home') { this.ownerProjectsStatus = 'error'; this.ownerProjects = null; }
          this.refreshPlatView();
        });
    },
    refresh() {
      // 切生产收口：本地账簿渲染体（W.snapshot/H/B/Settings 本地链）已下线——
      // 非在线态错误态；在线态统一平台视图（home/finance/performance 平台直供，本地账簿明细 view 自然空态）
      if (!PP.online()) { this.setData({ready:false,desk:null,rows:[],error:'请先登录后端账号'}); return; }
      if (this.kind === 'home') {
        require('../../utils/platform-todos-page').refresh(this, false);
        this.ownerProjectsStatus = 'loading';
        this.ownerProjects = null;
      }
      this.refreshPlatView();
      this.refreshPlatSummary();
    },
    search(e) { this.setData({keyword:e.detail.value,pageLimit:20}); this.refresh(); },
    clear() { this.setData({keyword:'',pageLimit:20}); this.refresh(); },
    loadMore() { this.setData({pageLimit:this.data.pageLimit + 20}); this.refresh(); },
    todoTab(e) { this.setData({todoTab:e.currentTarget.dataset.tab}); }, // 切生产收口：本地待办行渲染下线，仅切 tab
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
      if (PP.online() && ['home','performance','finance'].includes(this.kind)) {
        this.platPeriod = period;
        this.platSummary = null;
        this.platSummaryKey = '';
        this.platDegraded = false;
        this.platOperatingDegraded = false;
        this.refreshPlatView();
        this.refreshPlatSummary();
        return;
      }
      // 切生产收口：本地账簿 period 写入（M.mutate）下线——非在线态提示登录
      return this.fail('请先登录后端账号');
    },
    go(e) {
      if (!PP.online()) return this.fail('请先登录后端账号');
      // 切生产收口：本地账簿行 key 查找链（W.snapshot/H/B）下线；全部动作映射平台页
      const d=e.currentTarget.dataset, post=PP.apiPost()||{};
      const flows = tab => '/pages/admin/finance-desk/index?view=flows&tab=' + tab;
      const members = '/pages/account/index?view=api-members';
      const actions = {
        commissions:'/pages/admin/commission/index', receivables:flows('receivables'),payables:flows('payables'),costs:'/pages/admin/operations/index?view=costs',funds:'/pages/admin/operations/index?view=funds',operating:'/pages/admin/operations/index',business:'/pages/business/index',engineering:'/pages/engineering/index',approvals:flows('payables'),todos:flows('receivables'),reports:'/pages/admin/finance/index',performance:'/pages/admin/tasks/index?view=team',tasks:'/pages/admin/tasks/index?view=team',
        promotion:'/pages/admin/operations/index?view=expenses&id='+encodeURIComponent('company:'+(post.orgId||''))+'&purpose='+encodeURIComponent('推广获客'),
        goals:'/pages/admin/tasks/index?view=team',team:members,reviews:'/pages/admin/tasks/index?view=team',activity:members,receipts:flows('receivables'),payments:flows('payables'),financeProgress:flows('receivables'),staff:members,settings:members,handover:members,company:members,
        profile:'/pages/account/index?view=profile',messages:'/pages/account/index?view=messages',learning:'/pages/account/index?view=learning',services:'/pages/account/index?view=services',selection:'/pages/account/index?view=selection',help:'/pages/account/index?view=help',privacy:'/pages/account/index?view=privacy',rules:'/pages/admin/tasks/index',engineeringTeam:members,businessTeam:members,designTeam:members,financeTeam:members
      };
      const url=d.url || actions[d.action || d.key];
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
      // 切生产收口：本地目标保存（W.saveGoals 写本地 storage）下线——目标统一走绩效任务
      return this.fail('目标请通过「绩效任务」下发与调整');
    },
    companyInput(e) { this.companyDirty=true; this.setData({['companyForm.'+e.currentTarget.dataset.field]:e.detail.value,error:''}); },
    assignmentChange() {
      // 切生产收口：本地职责分工表单（Settings.load 渲染）下线——配置入口改到成员与权限
      return this.fail('职责分工暂未接入平台，请到「成员与权限」办理岗位指派');
    },
    saveCompany(){ this.saveConfiguration('management-company',this.data.companyForm); },
    saveAssignments(){ this.saveConfiguration('management-assignments',this.data.assignmentForm); },
    rulesInput(e) { this.rulesDirty=true; this.setData({['rulesForm.'+e.currentTarget.dataset.field]:e.detail.value,error:''}); },
    saveRules() {
      // 切生产收口：本地规矩保存（M.apply approval-settings）下线——费用线等规则配置暂未接入平台
      return this.fail('该规则配置暂未接入平台，请联系总部办理');
    },
    saveConfiguration() {
      // 切生产收口：本地配置保存（M.apply management-* 命令）下线——门店资料/职责分工暂未接入平台
      return this.fail('该配置暂未接入平台，请联系总部办理');
    },
    markHandover() {
      // 切生产收口：本地交接标记（M.apply handover-mark）下线——交接统一走「成员与权限」离职交接
      return require('../../utils/page-navigation').open('/pages/account/index?view=api-members');
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
