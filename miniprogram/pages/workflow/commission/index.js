// 设计委托页（API 态）：数据源 /franchise/commissions、/franchise/design-policies。
// 面积计价与历史首尾款默认走正式财务流程，测试组织可显式选择模拟付款。
const Nav = require('../../../utils/page-navigation');
const Money = require('../../../utils/money');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const API = require('../../../api/franchise/commission');
const Presentation = require('./presentation');
const Brief=require('./design-brief');
const { makeFormHandlers } = require('../../../utils/view-container');

const copy = v => JSON.parse(JSON.stringify(v === undefined ? null : v));
const paymentView = order => order?{...order,amountText:order.amountCents==null?'':Money.money(order.amountCents),timeText:require('../../../utils/platform-messages').formatTime(order.paidAt)}:null;
// 写命令幂等键：cm-{动作}-{16位随机hex}（对齐 pages/workflow/index.js 的 __cmdKeys 先例，域前缀区分）
const commandKey = action => {
  let hex = '';
  for (let i = 0; i < 16; i++) hex += Math.floor(Math.random() * 16).toString(16);
  return 'cm-' + action + '-' + hex;
};

Page({
  ...require('./payment-flow')({http,commissions:API.commissions,paymentView}), ...makeFormHandlers(),
  data: { projectLoading:false,projectReady:false,projectError:'',countsLoading:false,countsError:'',recentLoading:false,recentError:'',exporting:false,orderSheet:null,hasOrderSheet:false, designBrief:{},briefSections:[],briefSummary:'',orderCustomer:{},designBriefEnabled:false,submitPreview:false,briefStorageError:'',title: '设计委托', view: 'progress', customer: null, fields: [], form: {}, rows: [], tasks: [], taskCount: 0, metrics: [], recent: [], projectRows: [], projectFilters: [], projectCounts: {}, projectTotal: 0, projectHasMore: false, keyword: '', projectFilter: '全部', error: '', busy: false, primary: '', canReturn: false, canPrepareBudget:false,submitBlockedReason:'',budgetCard: false, budgetReady: false, budgetBasis: null, budgetNotice: '', pricingQuote: null, isComplete: false, platformStatus: 'loading', adminName: '', adminDate: '', preview: null, simulationPhase:'',simulationUncertain:false,simulationError:'',paymentNotice:'',paymentChoiceRequired:false,simulationAllowed:false,paymentMerchant:'' },
  onLoad(options) {
    this.disposed = false; this.options = options || {}; this.customerId = options.customerId || ''; this.id = options.commissionId || options.id || '';
    if (!this.customerId && options.view === 'submit' && !options.commissionId) { this.customerId = this.id; this.id = ''; }
    this.__byCustomer = !!this.customerId;
    let view = options.view || 'progress';
    if (view==='paid') view='receipt';
    if (view==='created') view='progress';
    this.view = view; this.initialized = false; // 加载统一由 onShow→refresh 触发，避免冷启动重复请求
  },
  onShow() { return this.refresh(false); },
  onUnload() { this.disposed = true; },
  /** 在线岗位：无 token 或无后端岗位时页面整体进入登录引导错误态 */
  post() { try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; } },
  actor() { const post = this.__post; return post ? require('../../../utils/organization').apiContext(post) : null; },
  cmdKey(action) { this.__cmdKeys = this.__cmdKeys || {}; if (!this.__cmdKeys[action]) this.__cmdKeys[action] = commandKey(action); return this.__cmdKeys[action]; },
  dropCmdKey(action) { if (this.__cmdKeys) delete this.__cmdKeys[action]; },
  failState(message) {
    this.__recentRequest = null; this.setData({recentLoading:false,recentError:''});
    this.__projectRequest = null; this.__countsRequest = null;
    this.setData({projectLoading:false,projectReady:false,projectError:'',countsLoading:false,countsError:''});
    this.action = ''; this.nextView = ''; this.initialized = false; this.__list = null;
    this.__briefScope='';this.setData({ orderSheet:null,hasOrderSheet:false,exporting:false,designBriefEnabled:false,designBrief:{},briefSummary:'',orderCustomer:{},paymentOrder:null,simulationPhase:'',simulationUncertain:false,simulationError:'',paymentNotice:'',paymentChoiceRequired:false,simulationAllowed:false,canFeeDecide: false, canFeedback: false, managerOptions: [] });
    this.setData({ platformStatus: 'degraded', error: message, customer: null, rows: [], tasks: [], taskCount: 0, metrics: this.view==='admin'&&this.post()&&this.__post&&this.__post.roleCode==='hq-design-admin'?Presentation.workbench([]).metrics.map(m=>({...m,value:'—'})):[], recent: [], projectRows: [], projectFilters: [], projectCounts: {}, projectTotal: 0, projectHasMore: false, form: {}, fields: [], preview: null, primary: '', canReturn: false, budgetCard: false, isComplete: false, budgetReady: false, budgetBasis: null, budgetNotice: '', pricingQuote: null });
  },
  refresh(reset = false) {
    const post = this.post();
    if (!post) return this.failState('请先登录后端账号');
    const identity = String(post.postId) + ':' + http.sessionGeneration();
    if (this.__identity && this.__identity !== identity) {
      this.__projectRequest = null; this.__list = []; this.__projectPage = 0; this.__projectLoaded = 0;
      this.__countsRequest = null;
      this.setData({projectRows:[],projectTotal:0,projectHasMore:false,projectLoading:false,projectReady:false,projectError:'',countsLoading:false,countsError:'',projectFilters:[],tasks:[],metrics:[],recent:[]});
      this.action = ''; this.nextView = ''; this.initialized = false; this.__context = null; this.__cmdKeys = {}; this.setData({ form: {}, fields: [], rows: [], busy: false, simulationPhase:'',simulationUncertain:false,simulationError:'',paymentNotice:'',paymentChoiceRequired:false,simulationAllowed:false,primary: '', canReturn: false, canFeeDecide: false, canFeedback: false, managerOptions: [] });
    }
    if(this.__identity!==identity)this.homeProfileName='';
    this.__identity = identity;
    this.__post = post;
    if (!this.id && !this.customerId && !['admin', 'projects', 'policy', 'pricing'].includes(this.view)) {
      return this.failState('缺少委托或客户编号，请从客户或项目列表进入');
    }
    const alive = http.requestGuard('commission');
    if (this.view === 'receipt') return this.refreshReceipt(alive);
    if (this.view === 'admin' || this.view === 'projects') return this.refreshWorkbench(alive);
    if (this.view === 'policy' || this.view === 'pricing') return this.refreshRules(alive, reset);
    return this.refreshCommission(alive, reset);
  },
  async refreshReceipt(alive){
    this.setData({title:'模拟付款登记凭据',view:'receipt',platformStatus:'loading',error:'',primary:'',fields:[],rows:[],designBriefEnabled:false,customer:null,paymentOrder:null});
    try{const order=await API.commissions.simulatedReceipt(this.id);if(!alive()||this.disposed)return;this.setData({platformStatus:'ready',paymentOrder:paymentView(order),customer:{name:order.customerName}});}
    catch(e){if(alive()&&!this.disposed)this.setData({platformStatus:'degraded',error:Presentation.errorMessage(e)});}
  },
  async refreshRecent(parentAlive = () => true) {
    const alive = http.requestGuard('commission-recent'), request = this.__recentRequest = {};
    const same = () => parentAlive() && alive() && this.__recentRequest === request && !this.disposed && this.view === 'admin';
    this.setData({recent:[],recentLoading:true,recentError:''});
    try {
      const recent = Presentation.recentRows(await API.commissions.workbenchRecent());
      if (same()) this.setData({recent});
    } catch (error) {
      if (same()) this.setData({recentError:'办理记录加载失败'});
    } finally {
      if (same()) this.setData({recentLoading:false});
    }
  },
  retryRecent() { return this.refreshRecent(); },
  async refreshWorkbenchCounts(parentAlive = () => true) {
    const alive = http.requestGuard('commission-counts'), request = this.__countsRequest = {};
    const identity = this.__identity, view = this.view;
    const same = () => parentAlive() && alive() && this.__countsRequest === request && this.__identity === identity && !this.disposed && this.view === view;
    this.setData({countsLoading:true,countsError:''});
    try {
      const counts = await API.commissions.workbenchCounts();
      if (!same()) return;
      if (!counts || typeof counts !== 'object' || Array.isArray(counts) || Object.values(counts).some(value => !Number.isSafeInteger(value) || value < 0)) throw Error('项目统计暂未加载');
      this.setData({projectFilters:Presentation.workbench([], counts).filters});
    } catch (error) {
      if (same()) this.setData({countsError:'项目统计暂时没加载，请重试'});
    } finally {
      if (same()) this.setData({countsLoading:false});
    }
  },
  retryWorkbenchCounts() { return this.refreshWorkbenchCounts(); },
  // ---- 设计行政工作台：全量指标、待办与最近办理分别读取 ----
  async refreshWorkbench(alive) {
    const actor = this.actor();
    if (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters') return this.failState('当前岗位无设计行政权限');
    if(this.view==='admin')this.setData({view:'admin',tasks:[],taskCount:0,recent:[],adminName:this.homeProfileName||actor.name||this.__post.name||'设计同事',adminDate:Presentation.dateLabel(),metrics:Presentation.workbench([]).metrics.map(m=>({...m,value:'—'}))});
    this.setData({ platformStatus: 'loading', error: '' });
    http.get('/platform/profile/mine').then(profile=>{if(alive()&&!this.disposed&&profile&&profile.nickname){this.homeProfileName=profile.nickname;this.setData({adminName:profile.nickname});}}).catch(()=>{});
    if (this.view === 'projects') {
      this.setData({title:'项目',view:'projects',platformStatus:'ready',primary:'',projectFilters:Presentation.workbench([]).filters.map(item=>({...item,count:'—'}))});
      return Promise.all([this.refreshWorkbenchCounts(alive),this.renderAdminProjects(true)]);
    }
    const recent = this.view === 'admin' ? this.refreshRecent(alive) : Promise.resolve();
    try {
      const [counts, todos] = await Promise.all([API.commissions.workbenchCounts(),
        this.view === 'admin' ? require('../../../api/todos').list() : Promise.resolve(null)]);
      if (!alive() || this.disposed) return;
      const wb = Presentation.workbench([], counts);
      if (this.view === 'admin') {
        if (!Array.isArray(todos?.items) || todos.failedDomains?.includes('franchise')) throw Error('设计待办暂未加载，请稍后刷新');
        const tasks = todos.items.filter(t => t.actionable && /^franchise:design-(complete|dispatch):/.test(t.key))
          .map(t => ({ key:t.key, title:t.title, url:t.appPath, clickable:true, status:t.status === 'DESIGN_COMPLETED' ? '待下发' : '设计中' }));
        this.setData({ title: '首页', view: 'admin', platformStatus: 'ready', error: '', primary: '', tasks, taskCount: tasks.length, metrics: wb.metrics, projectFilters: wb.filters, adminName: this.homeProfileName || actor.name || this.__post.name || '设计同事', adminDate: Presentation.dateLabel() });
      }
    } catch (e) { if (!alive() || this.disposed) return; this.failState(Presentation.errorMessage(e)); }
    await recent;
  },
  // ---- 费用规则（policy=老板标准费用 / pricing=总部计价）：policies.current → 表单 ----
  async refreshRules(alive, reset) {
    const actor = this.actor(), isPricing = this.view === 'pricing';
    if (isPricing && (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters')) return this.failState('设计费计价由总部设计人员配置');
    if (!isPricing && !(actor.orgType === 'store' && ['boss', 'owner'].includes(actor.role))) return this.failState('标准费用由加盟商老板配置');
    this.setData({ platformStatus: 'loading', error: '' });
    try {
      const res = await (isPricing ? API.pricing : API.policies).current();
      if (!alive() || this.disposed) return;
      const p = res || {};
      const form = reset || !this.initialized ? (isPricing
        ? { rate: Presentation.formMoney(p, 'rate'), scope: p.scope || '', recipient: p.recipient || p.payee || '', active: p ? p.active !== false : true }
        : { scope: p.scope || '', total: Presentation.formMoney(p, 'total', 'totalAmount'), first: Presentation.formMoney(p, 'first', 'firstAmount'), recipient: p.recipient || p.payee || '', active: p ? p.active !== false : true })
        : this.data.form;
      this.initialized = true;
      this.setData({
        title: isPricing ? '设计费计价设置' : '历史委托费用授权', view: this.view, platformStatus: 'ready', error: '',
        form, primary: isPricing ? '保存计价规则' : '保存历史授权规则',
        fields: isPricing
          ? [{ key: 'rate', label: '设计费单价（元/㎡）', kind: 'money' }, { key: 'scope', label: '默认交付范围', kind: 'textarea' }, { key: 'recipient', label: '总部收款主体', kind: 'input' }, { key: 'active', label: '启用本版计价规则', kind: 'check' }]
          : [{ key: 'scope', label: '适用交付范围', kind: 'textarea' }, { key: 'total', label: '服务总额（元）', kind: 'money' }, { key: 'first', label: '首款（元，尾款自动计算）', kind: 'money' }, { key: 'recipient', label: '总部实际收款方', kind: 'input' }, { key: 'active', label: '启用本版历史规则', kind: 'check' }],
        rows: isPricing
          ? [{ label: '计算规则', value: '设计费 = 已确认预算建筑面积 × 本版单价；委托提交时锁定面积、单价和规则版本。' }, { label: '费用执行', value: '费用按授权规则执行；到账登记由财务在财务工作台办理。' }]
          : [{ label: '适用范围', value: '仅用于继续办理已有委托的费用约定。新委托提交后费用按授权规则执行；到账登记由财务在财务工作台办理。' }, { label: '授权说明', value: '范围、金额、首尾款和收款方全部匹配时费用直通执行；不匹配则交老板审批。' }],
        customer: null
      });
    } catch (e) { if (!alive() || this.disposed) return; this.failState(Presentation.errorMessage(e)); }
  },
  // ---- 客户委托视图：commissions.get → rows/fields/form ----
  async refreshCommission(alive, reset) {
    const preservedError = this.__submitFailure || ''; this.__submitFailure = '';
    this.setData({ platformStatus: 'loading', error: preservedError });
    let res = null;
    try {
      if (this.__byCustomer) {
        const context = await API.commissions.context(this.customerId);
        if (!alive() || this.disposed) return;
        this.__context = context;
        if(this.view==='submit'&&context.commissionId&&!(context.allowedActions||[]).includes('commission-submit')){
          this.id=String(context.commissionId);this.__byCustomer=false;this.initialized=false;
          this.view=context.commission&&context.commission.simulatedPayment&&context.commission.simulatedPayment.status==='PENDING'?'pay':'progress';
        }
        if(!context.customerName&&!(context.allowedActions||[]).includes('commission-submit')){
          const detail=await require('../../../api/franchise/crm').customers.get(this.customerId);
          if(!alive()||this.disposed)return;
          const customer=detail&&detail.customer||{};
          context.customerName=customer.name;context.city=customer.city;context.customerInfo={name:customer.name,phone:customer.phone,address:[customer.province,customer.city,customer.district,customer.town,customer.village,customer.addressDetail].filter(Boolean).join(' ')};
          if(context.canPrepareBudget===undefined)context.canPrepareBudget=String(customer.ownerUserId)===String(this.__post.userId)&&['sales','sales-lead'].includes(this.__post.roleCode);
        }
        this.id = context.commissionId ? String(context.commissionId) : '';
        res = context.commission;
        if (!res) res = { customerId: context.customerId, allowedActions: context.allowedActions || [], request: context.latestBudget ? { basis: context.latestBudget } : null,
          fee: context.feeOffer ? { ...context.feeOffer, quote: context.feeOffer.mode === 'AREA_FULL' ? { ...context.feeOffer, pricingVersion: context.feeOffer.ruleVersion } : null } : null };
      } else {
        res = await API.commissions.get(this.id);
        if (this.view === 'submit' && res && res.commission) {
          this.__context = await API.commissions.context(String(res.commission.customerId));
        }
      }
    } catch (err) {
      if (!alive() || this.disposed) return;
      return this.setData({ platformStatus: 'degraded', error: Presentation.errorMessage(err) });
    }
    if (!alive() || this.disposed) return;
    const c = Presentation.normalize(res || {});
    if(this.__context){c.name=c.name||this.__context.customerName||'';c.city=c.city||this.__context.city||'';}
    if (!c.name && this.view === 'submit' && this.__byCustomer && this.options.customerName) {
      let name = String(this.options.customerName);
      try { name = decodeURIComponent(name); } catch (e) { /* A plain display name may contain a literal percent sign. */ }
      c.name = name;
    }
    if (this.view === 'submit' && this.__context) {
      c.request = { ...(c.request || {}), basis: this.__context.latestBudget || null };
      const offer = this.__context.feeOffer;
      if (offer) c.fee = { ...offer, quote: offer.mode === 'AREA_FULL' ? { ...offer, amountCents: offer.totalCents, pricingVersion: offer.ruleVersion } : null };
      c.allowedActions = this.__context.allowedActions || [];
    }
    this.customerId = c.id || this.customerId;
    this.__commission = c;
    const view = this.view, actor = this.actor();
    const n = view === 'submit' && this.__context && !this.__context.commissionId
      ? { state: '待发起委托', owner: '经办业务', view: 'submit', actionable: true }
      : Presentation.stateOf(c);
    const action = Presentation.actionFor(view);
    const can = !!action && Presentation.canDo(c, view, actor);
    const basis = c.request && c.request.basis;
    const currentQuote = c.fee && c.fee.quote;
    const budgetChanged = view === 'submit' && this.initialized && ((basis && this.data.form.__budgetVersion !== basis.version) || (currentQuote && this.data.form.__pricingVersion !== currentQuote.pricingVersion));
    let form = (reset || !this.initialized) ? Presentation.formInit(c, view) : this.data.form;
    // 参考预算更新后保留已填内容，但确认勾选必须对本版重做
    if (budgetChanged) { form = { ...form, __budgetVersion: basis && basis.version, __pricingVersion: currentQuote && currentQuote.pricingVersion, __mode: c.fee && c.fee.mode, scope: currentQuote ? currentQuote.scope : form.scope, checked: false }; this.dropCmdKey('commission-submit'); }
    const simulationAllowed = view === 'submit' && can && this.__context?.feeOffer?.simulationPaymentMode === 'TEST_SIMULATION';
    const simulationWithdrawn = view === 'submit' && this.data.form.testPayment === true && !simulationAllowed;
    const paymentChoiceRequired = view === 'submit' && (simulationWithdrawn || this.data.paymentChoiceRequired);
    if (paymentChoiceRequired) form = { ...form, testPayment: false, checked: false };
    this.initialized = true;
    const fields = Presentation.fieldsFor(c, view, can);
    const rows = Presentation.rowsFor(c, n).filter(row=>!(Brief.snapshot(c.request)&&row.label==='设计需求'));
    if (view === 'handover' && can) {
      try {
        const staff = await API.commissions.receivers(this.id);
        if (!alive() || this.disposed) return;
        this.__receivers = staff.filter(p => p.active !== false && ['engineer', 'project-manager'].includes(p.roleCode) && String(p.userId) !== String(this.__post.userId));
        this.setData({ managerOptions: this.__receivers.map(p => ({ id: String(p.userId), label: p.name || String(p.userId) })) });
        const selected = this.data.managerOptions.find(p => p.id === String(form.managerUserId || ''));
        form = { ...form, managerUserId: selected ? selected.id : '', managerName: selected ? selected.label : '' };
      } catch (e) { if (!alive() || this.disposed) return; this.setData({ error: '工程接收人加载失败：' + Presentation.errorMessage(e) }); }
    }
    const labels = { pay:'登记付款',submit: '提交设计委托', feedback: '提交修改意见', complete: '设计完成', dispatch: '上传并下发成果', handover: c.handover ? '补充后移交给工程' : '移交给工程', receive: '确认接收' };
    this.action = can ? action : '';
    this.nextView = !can && n.actionable && n.view !== view && Presentation.actionFor(n.view) ? n.view : '';
    this.setData({
      title: ({ pay:'设计费付款登记',submit: '设计委托', progress: '委托与交付', feedback: '登记修改意见', handover: '成果与移交', complete: '设计办理', dispatch: '下发设计成果', receive: '业务资料接收' })[view] || '委托与交付',
      view, platformStatus: 'ready', error: preservedError,
      customer: c.id || c.name ? { id: c.id, projectId: c.projectId, name: c.name, city: c.city, revision: c.revision } : null,
      form, fields:view==='pay'?[]:fields, rows,
      simulationAllowed, paymentChoiceRequired,
      paymentOrder:paymentView(c.simulatedPayment),
      paymentChannel:this.data.paymentChannel||'WECHAT',
      canFeeDecide: c.allowedActions.includes('fee-decide'),
      canFeedback: view !== 'feedback' && c.allowedActions.includes('commission-feedback'),
      canPrepareBudget:view==='submit'&&!!this.__context&&this.__context.canPrepareBudget===true,
      submitBlockedReason:view==='submit'&&!can?( !basis?'请先完成参考预算，再发起设计委托':!currentQuote?'当前设计费报价未就绪，请联系管理员':'当前账号暂不能提交本次委托，请联系经办业务或管理员'):'',
      budgetReady: !!basis, budgetBasis: basis,
      budgetNotice: budgetChanged ? '参考预算已更新，请核对本版尺寸、需求和范围后重新勾选确认。' : view === 'submit' ? this.data.budgetNotice : '',
      pricingQuote: Presentation.quoteOf(c),
      primary: can ? (view === 'submit' && !basis ? '先完成参考预算' : labels[view]) : this.nextView ? (this.nextView==='pay'?'登记付款':'继续办理') : '',
      budgetCard:view==='submit',
      canReturn: can && view === 'receive',
      isComplete: !!c.acceptance
    });
    this.prepareBrief(c,view,can);
    this.refreshOrderSheet();
  },
  prepareBrief(c,view,can){
    const frozen=Brief.snapshot(c.request),context=this.__context||{},client=view==='submit'?(context.customerInfo||frozen&&frozen.customer||{}):(frozen&&frozen.customer||context.customerInfo||{});
    const enabled=view==='submit'?(can&&!!context.customerInfo):!!frozen;
    const key=[this.__post?.userId,this.__post?.orgId,this.customerId].join(':');
    this.setData({orderCustomer:client,designBriefEnabled:enabled});
    if(!enabled)return;
    if(view!=='submit'||this.__briefScope!==key){
      this.__briefScope=key;
      let local;try{local=wx.getStorageSync('zs-commission-brief:'+key);}catch(e){}
      const inherited=Brief.inherit({...context,customerName:context.customerName||c.name},local&&local.schemaVersion===1?local:frozen);
      const brief=view==='submit'?inherited.brief:frozen;
      this.setData({designBrief:brief,briefSections:Brief.sections(brief),briefSummary:Brief.summary(brief),submitPreview:false,expandedBrief:0,showCustomerPhone:this.__post.roleCode!=='hq-design-admin'});
    }
  },
  refreshOrderSheet(){
    const c=this.__commission||{};
    this.setData({hasOrderSheet:!!(this.data.designBriefEnabled||c.request),orderSheet:require('./order-sheet').model(this.data,c,Presentation.stateOf(c).state)});
  },
  exportOrderImage(){return require('./order-sheet').exportImage(this);},
  updateBrief(key,value,toggle){
    if(this.data.busy||this.view!=='submit')return;
    const brief=Brief.edit(this.data.designBrief,key,value,toggle);
    this.setData({designBrief:brief,briefSections:Brief.sections(brief),briefSummary:Brief.summary(brief),submitPreview:false,'form.checked':false,briefStorageError:''});
    this.dropCmdKey('commission-submit');
    try{wx.setStorageSync('zs-commission-brief:'+this.__briefScope,brief);}catch(e){this.setData({briefStorageError:'需求未能暂存，请在离开前完成提交'});}
  },
  briefInput(e){this.updateBrief(e.currentTarget.dataset.key,e.detail.value,false);},
  briefChoice(e){this.updateBrief(e.currentTarget.dataset.key,e.currentTarget.dataset.value,true);},
  toggleBrief(e){const index=Number(e.currentTarget.dataset.index);this.setData({expandedBrief:this.data.expandedBrief===index?-1:index});},
  togglePaymentDetails(){this.setData({paymentDetails:!this.data.paymentDetails});},
  editBrief(){this.setData({submitPreview:false,'form.checked':false});},
  async renderAdminProjects(reset = false) {
    if (this.view !== 'projects' || (this.data.projectLoading && !reset)) return;
    const alive = http.requestGuard('commission-projects'), request = this.__projectRequest = {};
    const identity = this.__identity, keyword = this.data.keyword.trim(), filter = this.data.projectFilter;
    const same = () => alive() && !this.disposed && this.__projectRequest === request && this.__identity === identity && this.view === 'projects';
    if (reset) { this.__list = []; this.__projectPage = 0; this.__projectLoaded = 0; }
    const number = (this.__projectPage || 0) + 1;
    this.setData({ projectLoading:true, projectError:'', error:'', ...(reset ? { projectRows:[], projectTotal:0, projectHasMore:false, projectReady:false } : {}) });
    try {
      const res = await API.commissions.page(number, 20, keyword, Presentation.FILTER_STATUSES[filter]);
      if (!same()) return;
      if (!Array.isArray(res?.list) || !Number.isSafeInteger(res.total) || res.total < 0) throw Error('项目列表暂未加载，请重试');
      const rows = new Map((this.__list || []).map(c => [c.commissionId,c]));
      res.list.map(Presentation.normalize).forEach(c => rows.set(c.commissionId,c));
      this.__list = [...rows.values()]; this.__projectPage = number;
      this.__projectLoaded = (this.__projectLoaded || 0) + res.list.length;
      const page = Presentation.paginate(this.__list, '', '全部', this.__list.length);
      this.setData({ platformStatus:'ready', projectReady:true, projectRows:page.rows, projectTotal:res.total,
        projectHasMore:res.list.length > 0 && this.__projectLoaded < res.total });
    } catch (error) { if (same()) this.setData({ projectError:reset?'项目暂时没加载，请重试':'更多项目暂时没加载，请重试' }); }
    finally { if (same()) this.setData({ projectLoading:false }); }
  },
  searchProjects(e) { this.setData({ keyword: e.detail.value }); return this.renderAdminProjects(true); },
  selectProjectFilter(e) { this.setData({ projectFilter: e.currentTarget.dataset.filter }); return this.renderAdminProjects(true); },
  retryProjects() { return this.renderAdminProjects(!(this.__projectPage > 0)); },
  loadMoreProjects() { if (!this.data.projectHasMore) return; return this.renderAdminProjects(false); },
  onReachBottom() { if (this.view === 'projects') this.loadMoreProjects(); },
  openMetric(e) { this.tabStates = this.tabStates || {}; this.tabStates.admin = { keyword: this.data.keyword, projectFilter: this.data.projectFilter }; this.view = 'projects'; this.setData({ title: '项目', view: 'projects', keyword: '', projectFilter: e.currentTarget.dataset.filter || '全部' }); this.renderAdminProjects(true); wx.pageScrollTo({ scrollTop: 0, duration: 0 }); },
  switchWorkspaceTab(tab) {
    const home = tab.key === 'business' && tab.index === 19, projects = tab.key === 'design-admin-projects' && tab.index === 20;
    if (!home && !projects) return false;
    this.tabStates = this.tabStates || {};
    this.tabStates[this.view] = { keyword: this.data.keyword, projectFilter: this.data.projectFilter };
    const next = projects ? 'projects' : 'admin';
    this.view = next;
    this.setData({ title: next === 'projects' ? '项目' : '首页', view: next, keyword: '', projectFilter: '全部', ...(this.tabStates[next] || {}) });
    this.__projectRequest = null; this.setData({projectLoading:false}); this.refresh(true);
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    return true;
  },
  openTask(e) { const item=e.detail&&e.detail.item||e.currentTarget.dataset; Nav.open(item.actionUrl||item.url); },
  check(e) {
    if (this.data.busy) return;
    const key = e.currentTarget.dataset.key, checked = e.detail.value.length > 0;
    if (key === 'testPayment') return this.chooseCommissionPayment(checked);
    if (key === 'checked' && this.data.paymentChoiceRequired) return;
    this.setData({ ['form.' + key]: checked, error: '' });
  },
  chooseCommissionPayment(testPayment) {
    if (this.view !== 'submit' || this.data.busy || this.data.platformStatus !== 'ready'
        || (testPayment && !this.data.simulationAllowed)) return;
    this.dropCmdKey('commission-submit');
    this.setData({ 'form.testPayment': testPayment, 'form.checked': false, paymentChoiceRequired: false, error: '' });
  },
  chooseFormalCommission() { this.chooseCommissionPayment(false); },
  retryCommissionSimulation() { return this.refresh(false); },
  chooseManager(e) { const p = (this.data.managerOptions || [])[Number(e.detail.value)]; if (p) this.setData({ 'form.managerUserId': p.id, 'form.managerName': p.label }); },
  chooseFile(e) {
    if (this.data.busy) return;
    const key = e.currentTarget.dataset.key;
    const session = http.sessionGuard(), view = this.view, alive = () => session() && !this.disposed && this.view === view;
    wx.chooseMessageFile({
      count: 1, type: 'file',
      success: async r => {
        if (!alive()) return;
        try {
          this.setData({ busy: true, error: '' });
          const f = r.tempFiles[0];
          const uploaded = await require('../../../utils/commission-assets').upload(f, this.view === 'dispatch' ? 'DESIGN_DELIVERABLE' : 'CONTRACT_ATTACHMENT', alive);
          if (!alive()) return;
          this.setData({ ['form.' + key]: uploaded, busy: false, error: '' });
        } catch (err) { if (alive()) this.setData({ busy: false, error: err.message }); }
      },
      fail: err => { if (alive() && !/cancel/i.test(err.errMsg || '')) this.setData({ error: '文件选择失败，请重新选择' }); }
    });
  },
  async preview(e) {
    const f = this.data.form[e.currentTarget.dataset.key] || (this.data.rows[Number(e.currentTarget.dataset.index)] || {}).file;
    if (!f) return;
    if (f.text) return this.setData({ preview: { name: f.name, text: f.text } });
    if (f.path) return wx.openDocument({ filePath: f.path, showMenu: true, fail: () => this.setData({ error: '此格式无法直接预览，请核对原文件后继续' }) });
    if (f.slot || f.assetId) {
      const session = http.sessionGuard(), alive = () => session() && !this.disposed;
      try {
        const ticket = f.slot ? await API.commissions.downloadTicket(this.id, f.dispatchSeq, f.slot) : await http.post('/franchise/assets/' + f.assetId + '/download-tickets');
        if (!alive()) return;
        const file = await require('../../../utils/b-asset-download').downloadTicketFile({ http, downloadUrl: ticket.downloadUrl, alive, label: '委托附件' });
        if (!alive()) return;
        if (/^image\//.test(file.contentType || f.mimeType || '')) return wx.previewImage({ urls: [file.tempFilePath] });
        return wx.openDocument({ filePath: file.tempFilePath, showMenu: true, fail: () => this.setData({ error: '此格式无法直接预览，请核对原文件' }) });
      } catch (err) { if (alive()) this.setData({ error: Presentation.errorMessage(err) }); return; }
    }
    this.setData({ error: '该附件暂无可预览的文件' });
  },
  closePreview() { this.setData({ preview: null }); },
  openFeedback() { if (!this.data.busy && this.data.canFeedback) Nav.open(Presentation.url(this.id, 'feedback')); },
  async decideFee(e) {
    if (this.data.busy || !this.data.canFeeDecide) return;
    const approve = e.currentTarget.dataset.approve === true || e.currentTarget.dataset.approve === 'true';
    const note = String(this.data.form.feeNote || '').trim();
    if (!approve && !note) return this.setData({ error: '请填写退回原因' });
    const session = http.sessionGuard(), alive = () => session() && !this.disposed;
    this.setData({ busy: true, error: '' });
    try {
      await API.commissions.feeDecide(this.id, { approve, note }, this.cmdKey('fee-decide-' + approve));
      if (!alive()) return;
      this.dropCmdKey('fee-decide-' + approve); this.setData({ busy: false }); this.refresh(true);
    } catch(e) { if (alive()) this.setData({ busy: false, error: Presentation.errorMessage(e) }); }
  },
  completeBudget() { if (this.data.busy || this.view !== 'submit' || !this.data.canPrepareBudget) return; Nav.open('/pages/workflow/index?view=budget&id=' + encodeURIComponent(this.customerId)); },
  /** 命令 payload：表单 → 后端字段（金额统一转分，内部键剔除） */
  commandPayload(action) {
    const f = this.data.form;
    const assetId = file => file && file.assetId ? String(file.assetId) : undefined;
    if (action === 'commission-submit') {
      const briefError=this.data.designBriefEnabled?Brief.error(this.data.designBrief):'';if(briefError)throw Error(briefError);
      const summary=this.data.designBriefEnabled?Brief.summary(this.data.designBrief):String(f.text || '').trim();
      const text=summary.length>2000&&this.data.designBriefEnabled?summary.slice(0,1950)+'\n完整需求见结构化委托单':summary,scope = String(f.scope || '').trim();
      if (!text) throw Error('请填写客户设计需求');
      if (!scope) throw Error('请填写本次交付范围');
      if (!f.checked) throw Error('请勾选确认已核对需求、尺寸来源、交付范围及费用约定');
      if (!f.__budgetVersion) throw Error('请先完成有效参考预算');
      if (this.data.paymentChoiceRequired || (f.testPayment === true
          && (f.__mode !== 'AREA_FULL' || this.__context?.feeOffer?.simulationPaymentMode !== 'TEST_SIMULATION'))) {
        throw Error('模拟付款资格已变化，请重新选择付款方式');
      }
      const payload = { ...(f.testPayment === true ? {paymentMode:'TEST_SIMULATION'} : {}),...(this.data.designBriefEnabled?{designBrief:this.data.designBrief}:{}), mode: f.__mode, text, scope, confirmed: true, budgetVersion: f.__budgetVersion, attachmentAssetId: assetId(f.attachment) };
      if (f.__mode === 'AREA_FULL') payload.feeRuleVersion = f.__pricingVersion;
      else if (f.__mode === 'LEGACY_SPLIT') {
        payload.totalCents = Money.parseCents(f.total);
        payload.firstCents = Money.parseCents(f.first);
        payload.recipient = String(f.recipient || '').trim();
        if (!payload.recipient) throw Error('请填写总部实际收款方');
        if (payload.firstCents >= payload.totalCents) throw Error('首款须小于总额');
      } else throw Error('当前暂无有效设计费规则，请联系平台设计配置');
      return payload;
    }
    if (action === 'commission-feedback') {
      const note = String(f.note || '').trim();
      if (!note) throw Error('请填写需要线下沟通的修改意见');
      return { note };
    }
    if (action === 'design-complete') {
      if (!f.checked) throw Error('请确认本次线下设计及内部审核已完成');
      return { note: String(f.note || '').trim(), confirmed: true };
    }
    if (action === 'design-dispatch') {
      if (!String(f.recipient || '').trim() || !String(f.note || '').trim() || !f.checked) throw Error('请填写实际接收人、交付清单或渠道，并确认已经下发');
      if (!String(f.drawingVersion || '').trim() || !assetId(f.resultFile) || !assetId(f.drawingFile) || !assetId(f.reviewFile)) throw Error('请上传三份正式成果并填写图纸版本');
      return { recipient: String(f.recipient || '').trim(), drawingVersion: String(f.drawingVersion || '').trim(), note: String(f.note || '').trim(), resultAssetId: assetId(f.resultFile), drawingAssetId: assetId(f.drawingFile), reviewAssetId: assetId(f.reviewFile), confirmed: true };
    }
    if (action === 'packet-submit') {
      if (!String(f.drawingVersion || '').trim()) throw Error('请填写本次施工图版本');
      if (!f.confirmationDate || String(f.confirmationDate) > require('../../../utils/account').localDate()) throw Error('请填写有效的客户确认日期');
      if (!f.received || !f.confirmed || !String(f.note || '').trim()) throw Error('请核对文件、图纸版本、客户确认日期及交底内容');
      if (!assetId(f.confirmationFile) || !/^\d+$/.test(String(f.managerUserId || ''))) throw Error('请上传客户确认凭据并选择有效工程接收人');
      return { dispatchSeq: f.dispatchVersion, confirmationAssetId: assetId(f.confirmationFile), confirmationDate: f.confirmationDate, note: String(f.note || '').trim(), managerUserId: String(f.managerUserId), confirmed: true };
    }
    if (action === 'packet-return') {
      const note = String(f.note || '').trim();
      if (!note) throw Error('请填写资料退回原因');
      return { reason: note, confirmed: true };
    }
    if (action === 'packet-receive') {
      if (!f.checked) throw Error('请核对本次图纸、签认依据和交底资料');
      return { confirmed: true };
    }
    return copy(f);
  },
  /** 费用规则保存（policy=标准费用 / pricing=面积计价），每次保存一个新版本 */
  async saveRules(isPricing) {
    const f = this.data.form;
    const scope = String(f.scope || '').trim(), recipient = String(f.recipient || '').trim();
    if (isPricing) {
      const rateCents = Money.parseCents(f.rate);
      if (!scope) throw Error('请填写默认交付范围');
      if (!recipient) throw Error('请填写总部收款主体');
      await API.pricing.save({ scope, recipient, rateCents, active: f.active !== false }, this.cmdKey('pricing-save'));
      this.dropCmdKey('pricing-save');
      return;
    }
    if (!scope || !recipient) throw Error('请填写交付范围、总部实际收款方');
    const totalCents = Money.parseCents(f.total), firstCents = Money.parseCents(f.first);
    if (firstCents >= totalCents) throw Error('首款须小于总额');
    await API.policies.save({ scope, recipient, totalCents, firstCents, active: f.active !== false });
  },
  async submit() {
    if(this.view==='pay'&&this.action==='commission-simulate-pay')return this.simulatePayment();
    if (this.action === 'commission-submit') {
      if (this.data.busy || this.data.platformStatus !== 'ready' || this.data.paymentChoiceRequired) return;
      if (this.data.form.testPayment && this.__context?.feeOffer?.simulationPaymentMode !== 'TEST_SIMULATION') {
        this.setData({ 'form.checked': false, 'form.testPayment': false, paymentChoiceRequired: true, simulationAllowed: false });
        return;
      }
    }
    if(this.action==='commission-submit'&&this.data.designBriefEnabled&&!this.data.submitPreview){
      try{const message=Brief.error(this.data.designBrief);if(message)throw Error(message);this.setData({submitPreview:true,error:''});this.refreshOrderSheet();wx.pageScrollTo({scrollTop:0,duration:0});}catch(e){const message=e.message;const index=/项目|朝向|入口/.test(message)?0:/常住人口/.test(message)?1:2;this.setData({error:message,expandedBrief:index});}return;
    }
    if (this.data.busy) return;
    if (this.nextView) return Nav.open(Presentation.url(this.id, this.nextView));
    this.setData({ busy: true, error: '' });
    const session = http.sessionGuard(), alive = () => session() && !this.disposed;
    try {
      if (this.view === 'policy') { await this.saveRules(false); wx.showToast({ title: '历史规则已保存', icon: 'success' }); }
      else if (this.view === 'pricing') { await this.saveRules(true); wx.showToast({ title: '计价规则已保存', icon: 'success' }); }
      else {
        const action = this.action;
        if (!action) throw Error('当前没有可提交的办理动作');
        const payload = this.commandPayload(action);
        // 幂等键：失败时保留原键以便安全重试，成功后作废取新键
        const result = action === 'commission-submit'
          ? await API.commissions.submit(this.customerId, payload, this.cmdKey(action))
          : await API.commissions.command(this.id, action, payload, this.cmdKey(action));
        if (!alive()) return;
        if (action === 'commission-submit') {
          const id = result && (result.commissionId || (result.commission && result.commission.id));
          if (!id) throw Error('委托响应缺少真实编号，请刷新客户委托核对后再办理');
          this.id = String(id);
          try{wx.removeStorageSync('zs-commission-brief:'+this.__briefScope);}catch(e){}
          this.__briefScope='';
        }
        this.dropCmdKey(action);
        this.action = ''; this.nextView = ''; this.view = result&&result.simulatedPayment&&result.simulatedPayment.status==='PENDING'?'pay':'progress'; this.initialized = false;
        this.setData({ busy: false });
        this.refresh(true);
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        wx.showToast({ title: '已保存并更新待办', icon: 'success' });
        return;
      }
      if (!alive()) return;
      this.initialized = false;
      this.setData({ busy: false });
      this.refresh(true);
    } catch (e) {
      if (!alive()) return;
      const simulationRejected = this.view === 'submit' && this.data.form.testPayment && Number(e?.code) === 1111000022;
      const error = simulationRejected ? '' : Presentation.errorMessage(e);
      this.setData({ error, busy: false, ...(simulationRejected
        ? { 'form.checked': false, 'form.testPayment': false, paymentChoiceRequired: true, simulationAllowed: false } : {}) });
      if (this.view === 'submit') { this.__submitFailure = error; return this.refresh(false); }
    }
  },
  showProgress(){if(this.data.busy)return;this.view='progress';this.refresh(true);wx.pageScrollTo({scrollTop:0,duration:0});},
  choosePayment(e){if(this.data.busy||this.data.simulationPhase||this.data.simulationUncertain)return;const value=e.currentTarget.dataset.channel;if(['WECHAT','ALIPAY'].includes(value)&&value!==this.data.paymentChannel){this.dropCmdKey('simulate-pay');this.setData({paymentChannel:value});}},
  simulatePayment(){return this.openSimulationPayment();},
  async returnPacket() {
    if (this.data.busy) return;
    const action = 'packet-return';
    this.setData({ busy: true, error: '' });
    const session = http.sessionGuard(), alive = () => session() && !this.disposed;
    try {
      await API.commissions.command(this.id, action, this.commandPayload(action), this.cmdKey(action));
      if (!alive()) return;
      this.dropCmdKey(action);
      this.action = ''; this.nextView = ''; this.view = 'progress'; this.initialized = false;
      this.setData({ busy: false });
      this.refresh(true);
    } catch(e) { if (alive()) this.setData({ error: Presentation.errorMessage(e), busy: false }); }
  }
});
