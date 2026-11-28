// 设计委托页（API 态）：数据源 /franchise/commissions、/franchise/design-policies。
// 实体设计费在外部付款，由财务登记实际流水和凭证；旧模拟记录仅供历史查阅。
const Nav = require('../../../utils/page-navigation');
const Money = require('../../../utils/money');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const API = require('../../../api/franchise/commission');
const Presentation = require('./presentation');
const Brief=require('./design-brief');
const { makeFormHandlers } = require('../../../utils/view-container');

const paymentView = order => order?{...order,amountText:order.amountCents==null?'':Money.money(order.amountCents),timeText:require('../../../utils/platform-messages').formatTime(order.paidAt)}:null;
// 写命令幂等键：cm-{动作}-{16位随机hex}（对齐 pages/workflow/index.js 的 __cmdKeys 先例，域前缀区分）
const commandKey = action => {
  let hex = '';
  for (let i = 0; i < 16; i++) hex += Math.floor(Math.random() * 16).toString(16);
  return 'cm-' + action + '-' + hex;
};

Page({
  ...makeFormHandlers(),
  data: {"exporting":false,"orderSheet":null,"hasOrderSheet":false,"designBrief":{},"briefSummary":"","orderCustomer":{},"designBriefEnabled":false,"title":"设计委托","view":"progress","customer":null,"fields":[],"form":{},"rows":[],"error":"","busy":false,"primary":"","canReturn":false,"budgetBasis":null,"pricingQuote":null,"isComplete":false,"platformStatus":"loading","preview":null,"retiredEntry":false},
  onLoad(options = {}) {
    this.__settlementCommand = null;
    this.disposed = false; this.options = options || {}; this.customerId = options.customerId || ''; this.id = options.commissionId || options.id || '';
    if (!this.customerId && options.view === 'submit' && !options.commissionId) { this.customerId = this.id; this.id = ''; }
    this.__byCustomer = !!this.customerId;
    let view = options.view || 'progress';
    const retiredDesign = ['feedback','complete','dispatch'].includes(view);
    if (view==='paid') view='receipt';
    if (view==='created'||view==='pay'||retiredDesign) view='progress';
    this.historyOnly = options.history === '1' || retiredDesign;
    if (this.historyOnly) view = 'progress';
    this.view = view; this.initialized = false; // 加载统一由 onShow→refresh 触发，避免冷启动重复请求
  },
  onShow() { if (require('./external-entry')(this)) return; return this.refresh(false); },
  onUnload() { this.disposed = true; },
  /** 在线岗位：无 token 或无后端岗位时页面整体进入登录引导错误态 */
  post() { try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; } },
  actor() { const post = this.__post; return post ? require('../../../utils/organization').apiContext(post) : null; },
  cmdKey(action) { this.__cmdKeys = this.__cmdKeys || {}; if (!this.__cmdKeys[action]) this.__cmdKeys[action] = commandKey(action); return this.__cmdKeys[action]; },
  dropCmdKey(action) { if (this.__cmdKeys) delete this.__cmdKeys[action]; },
  failState(message) {
    this.__settlementCommand = null;
    this.dropCmdKey('historical-settlement');
    this.setData({settlementNote:'',settlementPending:false,busy:false});
    this.action = ''; this.nextView = ''; this.initialized = false;
    this.setData({ orderSheet:null,hasOrderSheet:false,exporting:false,designBriefEnabled:false,designBrief:{},briefSummary:'',orderCustomer:{},paymentOrder:null,managerOptions: [] });
    this.setData({ platformStatus: 'degraded', error: message, needsLogin:!this.post(), customer: null, rows: [], form: {}, fields: [], preview: null, primary: '', canReturn: false, isComplete: false, budgetBasis: null, pricingQuote: null });
  },
  refresh(reset = false) {
    const post = this.post();
    if (!post) return this.failState('请先登录后端账号');
    const identity = String(post.postId) + ':' + http.sessionGeneration();
    if (this.__identity && this.__identity !== identity) {
      this.__settlementCommand = null;
      this.setData({settlementNote:'',settlementPending:false});
      this.action = ''; this.nextView = ''; this.initialized = false; this.__context = null; this.__cmdKeys = {}; this.setData({ form: {}, fields: [], rows: [], busy: false, primary: '', canReturn: false, managerOptions: [] });
    }
    this.__identity = identity;
    this.__post = post;
    if (['admin', 'projects'].includes(this.view)) {
      this.setData({title:'历史委托入口',view:'progress',retiredEntry:true});
      return this.failState('旧设计工作台已停用，请返回当前工作台继续办理');
    }
    if (this.view === 'submit') {
      if (require('./external-entry')(this)) return;
      this.view = 'progress';
    }
    if (!this.id && !this.customerId && !['policy', 'pricing'].includes(this.view)) {
      return this.failState('缺少委托或客户编号，请从客户或项目列表进入');
    }
    const alive = http.requestGuard('commission');
    if (this.view === 'receipt') return this.refreshReceipt(alive);
    if (this.view === 'policy' || this.view === 'pricing') return this.refreshRules(alive);
    return this.refreshCommission(alive, reset);
  },
  async refreshReceipt(alive){
    if(this.__settlementCommand&&this.settlementCurrent(this.__settlementCommand))return;
    this.__settlementCommand=null;
    const previous=this.data.paymentOrder;
    this.setData({title:'历史模拟记录',view:'receipt',platformStatus:'loading',error:'',primary:'',fields:[],rows:[],designBriefEnabled:false,customer:null,paymentOrder:null,busy:false});
    try{const order=await API.commissions.simulatedReceipt(this.id);if(!alive()||this.disposed)return;
      if(String(order.commissionId)!==String(this.id))throw Error('历史记录与当前委托不一致，请重新进入');
      const same=previous&&String(previous.commissionId)===String(order.commissionId)&&previous.submissionVersion===order.submissionVersion;
      this.setData({platformStatus:'ready',paymentOrder:paymentView(order),customer:{name:order.customerName},settlementNote:same?this.data.settlementNote||'':'',settlementPending:false,
        canViewHistoricalPayments:this.__post?.orgType==='store'&&['finance','finance-lead'].includes(this.__post.roleCode)});}
    catch(e){if(alive()&&!this.disposed){
      if(e?.postInvalid||[401,403,1111000000,1111000001].includes(Number(e?.code)))return this.failState(Presentation.errorMessage(e));
      this.setData({platformStatus:'degraded',error:Presentation.errorMessage(e)});
    }}
  },
  settlementInput(e){
    if(this.data.busy||this.__settlementCommand)return;
    this.setData({settlementNote:e.detail.value,error:''});
  },
  settlementCurrent(command){
    const post=this.post();
    return !this.disposed&&this.view==='receipt'&&this.__settlementCommand===command&&String(this.id)===command.id&&post
      &&command.identity===String(post.postId)+':'+http.sessionGeneration();
  },
  async prepareHistoricalSettlement(){
    if(this.data.busy||this.view!=='receipt')return;
    let command=this.__settlementCommand;
    if(!command){
      const order=this.data.paymentOrder,post=this.post(),note=String(this.data.settlementNote||'').trim();
      if(this.data.platformStatus!=='ready'||!order?.canPrepareHistoricalSettlement||!post
          ||this.__identity!==String(post.postId)+':'+http.sessionGeneration())return;
      if(!note||note.length>500)return this.setData({error:'请填写历史费用核对说明（500字以内）'});
      command={id:String(this.id),identity:this.__identity,key:this.cmdKey('historical-settlement'),
        body:{submissionVersion:order.submissionVersion,reviewNote:note}};
      this.__settlementCommand=command;
    }
    if(!this.settlementCurrent(command))return this.refresh(false);
    this.setData({busy:true,error:''});
    try{
      await API.commissions.historicalSettlement(command.id,command.body,command.key);
      if(!this.settlementCurrent(command))return;
      this.__settlementCommand=null;this.dropCmdKey('historical-settlement');
      this.setData({busy:false,settlementPending:false,settlementNote:''});
      wx.showToast({title:'历史费用已复核',icon:'success'});
      return this.refresh(false);
    }catch(e){
      if(!this.settlementCurrent(command))return;
      if(e?.postInvalid||[401,403,1111000000,1111000001].includes(Number(e?.code)))return this.failState(Presentation.errorMessage(e));
      const uncertain=e&&(e.code===-1||e.code>=500&&e.code<600);
      if(!uncertain){this.__settlementCommand=null;this.dropCmdKey('historical-settlement');}
      this.setData({busy:false,settlementPending:!!uncertain,error:uncertain?'未收到复核确认，请重试原提交':Presentation.errorMessage(e)});
    }
  },
  openHistoricalPayments(){
    const post=this.post();
    const order=this.data.paymentOrder, id=String(order?.commissionId??''), version=String(order?.submissionVersion??'');
    if(!this.data.busy&&this.view==='receipt'&&id===String(this.id)&&/^[1-9]\d{0,18}$/.test(id)&&/^[1-9]\d{0,9}$/.test(version)
        &&post?.orgType==='store'&&['finance','finance-lead'].includes(post.roleCode)
        &&this.__identity===String(post.postId)+':'+http.sessionGeneration())
      Nav.open('/pages/admin/finance-desk/index?view=design-fees&commissionId='+encodeURIComponent(id)+'&submissionVersion='+encodeURIComponent(version));
  },
  // 原费用规则只读；历史单金额继续使用各自冻结的费用版本。
  async refreshRules(alive) {
    const actor = this.actor(), isPricing = this.view === 'pricing';
    if (isPricing && (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters')) return this.failState('当前岗位无权查看历史设计计价规则');
    if (!isPricing && !(actor.orgType === 'store' && ['boss', 'owner'].includes(actor.role))) return this.failState('历史标准费用仅本门店老板可查看');
    this.action = ''; this.nextView = '';
    this.setData({ platformStatus: 'loading', error: '', primary: '', fields: [], form: {}, rows: [], customer: null, canReturn: false });
    try {
      const res = await (isPricing ? API.pricing : API.policies).current();
      if (!alive() || this.disposed) return;
      const p = res || {};
      const amount = key => p[key] == null ? '未记录' : Money.money(p[key]);
      this.setData({
        title: isPricing ? '历史设计计价规则' : '历史设计费用规则', view: this.view, platformStatus: 'ready', error: '',
        rows: [{ label: '使用说明', value: '原规则仅供查阅；旧委托按原版本费用结算，实际收付款在财务工作台办理。' },
          { label: '规则版本', value: p.version ? 'V' + p.version : '未记录' },
          ...(isPricing ? [{ label: '设计费单价', value: amount('rateCents') + '/㎡' }]
            : [{ label: '服务总额', value: amount('totalCents') }, { label: '首款', value: amount('firstCents') }]),
          { label: '交付范围', value: p.scope || '未记录' }, { label: '收款主体', value: p.recipient || '未记录' }]
      });
    } catch (e) { if (!alive() || this.disposed) return; this.failState(Presentation.errorMessage(e)); }
  },
  goHome() {
    if (!this.post()) {
      const query=Object.entries(this.options||{}).filter(([,value])=>value!==undefined&&value!=='').map(([key,value])=>encodeURIComponent(key)+'='+encodeURIComponent(value)).join('&');
      return Nav.open('/pages/account/index?view=login&return='+encodeURIComponent('/pages/workflow/commission/index'+(query?'?'+query:'')),{replace:true});
    }
    Nav.home();
  },
  // ---- 客户委托视图：commissions.get → rows/fields/form ----
  async refreshCommission(alive, reset) {
    this.setData({ platformStatus: 'loading', error: '', retiredEntry:false });
    let res = null;
    try {
      if (this.__byCustomer) {
        const context = await API.commissions.context(this.customerId);
        if (!alive() || this.disposed) return;
        this.__context = context;
        this.id = context.commissionId ? String(context.commissionId) : '';
        res = context.commission;
        if (!res) {
          this.setData({retiredEntry:true});
          return this.failState('暂无可查看的历史委托，请从客户跟进继续办理');
        }
      } else {
        res = await API.commissions.get(this.id);
      }
    } catch (err) {
      if (!alive() || this.disposed) return;
      return this.setData({ platformStatus: 'degraded', error: Presentation.errorMessage(err) });
    }
    if (!alive() || this.disposed) return;
    if (!this.historyOnly && (res?.commission || res)?.workflowMode === 'EXTERNAL' && ['handover', 'receive', 'progress'].includes(this.view)) {
      this.failState('正在打开外部成果移交');
      Nav.open('/pages/external-handover/index?commissionId=' + encodeURIComponent(this.id), { replace: true });
      return;
    }
    const c = Presentation.normalize(res || {});
    if(this.__context){c.name=c.name||this.__context.customerName||'';c.city=c.city||this.__context.city||'';}
    this.customerId = c.id || this.customerId;
    this.__commission = c;
    const view = this.view;
    const n = Presentation.stateOf(c);
    const action = Presentation.actionFor(view);
    const can = !this.historyOnly && !!action && Presentation.canDo(c, view);
    const canReturn = !this.historyOnly && view === 'receive' && c.allowedActions.includes('packet-return');
    const basis = c.request && c.request.basis;
    let form = (reset || !this.initialized) ? Presentation.formInit(c, view) : this.data.form;
    this.initialized = true;
    const fields = Presentation.fieldsFor(c, view, can, canReturn);
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
    const labels = { handover: c.handover ? '补充后移交给工程' : '移交给工程', receive: '确认接收' };
    this.action = can ? action : '';
    this.nextView = !this.historyOnly && !can && n.actionable && n.view !== view && Presentation.actionFor(n.view) ? n.view : '';
    this.setData({
      title: ({ progress: '委托与交付', handover: '成果与移交', receive: '业务资料接收' })[view] || '委托与交付',
      view, platformStatus: 'ready', error: '',
      customer: c.id || c.name ? { id: c.id, projectId: c.projectId, name: c.name, city: c.city, revision: c.revision } : null,
      form, fields, rows,
      paymentOrder:paymentView(c.simulatedPayment),
      budgetBasis: basis,
      pricingQuote: Presentation.quoteOf(c),
      primary: can ? labels[view] : this.nextView ? ('继续办理') : '',
      canReturn,
      isComplete: !!c.acceptance
    });
    this.prepareBrief(c);
    this.refreshOrderSheet();
  },
  prepareBrief(c){
    const brief=Brief.snapshot(c.request);
    this.setData({orderCustomer:brief&&brief.customer||this.__context?.customerInfo||{},designBriefEnabled:!!brief,designBrief:brief||{},
      briefSummary:brief?Brief.summary(brief):'',showCustomerPhone:this.__post.roleCode!=='hq-design-admin'});
  },
  refreshOrderSheet(){
    const c=this.__commission||{};
    this.setData({hasOrderSheet:!!(this.data.designBriefEnabled||c.request),orderSheet:require('./order-sheet').model(this.data,c,Presentation.stateOf(c).state)});
  },
  exportOrderImage(){return require('./order-sheet').exportImage(this);},
  togglePaymentDetails(){this.setData({paymentDetails:!this.data.paymentDetails});},
  check(e) {
    if (this.data.busy) return;
    const key = e.currentTarget.dataset.key, checked = e.detail.value.length > 0;
    this.setData({ ['form.' + key]: checked, error: '' });
  },
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
          const uploaded = await require('../../../utils/commission-assets').upload(f, 'CONTRACT_ATTACHMENT', alive);
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
  commandPayload(action) {
    const f = this.data.form;
    const assetId = file => file && file.assetId ? String(file.assetId) : undefined;
    if (action === 'commission-submit') throw Error('旧委托新增已停用，请从客户跟进登记外部委托');
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
    throw Error('旧设计办理已停用，请从客户跟进继续办理');
  },
  async submit() {
    if (this.historyOnly) return;
    if (this.data.busy) return;
    if (this.nextView) return Nav.open(Presentation.url(this.id, this.nextView));
    this.setData({ busy: true, error: '' });
    const session = http.sessionGuard(), alive = () => session() && !this.disposed;
    try {
      const action = this.action;
      if (!action) throw Error('当前没有可提交的办理动作');
      const payload = this.commandPayload(action);
      // 幂等键：失败时保留原键以便安全重试，成功后作废取新键
      await API.commissions.command(this.id, action, payload, this.cmdKey(action));
      if (!alive()) return;
      this.dropCmdKey(action);
      this.action = ''; this.nextView = ''; this.view = 'progress'; this.initialized = false;
      this.setData({ busy: false });
      this.refresh(true);
      wx.pageScrollTo({ scrollTop: 0, duration: 0 });
      wx.showToast({ title: '已保存并更新待办', icon: 'success' });
    } catch (e) {
      if (!alive()) return;
      this.setData({ error:Presentation.errorMessage(e), busy:false });
    }
  },
  async returnPacket() {
    if (this.data.busy || this.historyOnly || this.view !== 'receive' || !this.data.canReturn) return;
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
