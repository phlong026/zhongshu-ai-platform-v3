// 设计委托页（API 态）：数据源 /franchise/commissions、/franchise/design-policies。
// 模拟支付、sample-files 假文件与本地动作机已下线；委托费用按授权规则执行，到账由财务实收登记。
const Nav = require('../../../utils/page-navigation');
const Money = require('../../../utils/money');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const API = require('../../../api/franchise/commission');
const Presentation = require('./presentation');
const { makeFormHandlers } = require('../../../utils/view-container');

const copy = v => JSON.parse(JSON.stringify(v === undefined ? null : v));
// 写命令幂等键：cm-{动作}-{16位随机hex}（对齐 pages/workflow/index.js 的 __cmdKeys 先例，域前缀区分）
const commandKey = action => {
  let hex = '';
  for (let i = 0; i < 16; i++) hex += Math.floor(Math.random() * 16).toString(16);
  return 'cm-' + action + '-' + hex;
};

Page({ ...makeFormHandlers(),
  data: { title: '设计委托', view: 'progress', customer: null, fields: [], form: {}, rows: [], tasks: [], taskCount: 0, metrics: [], recent: [], projectRows: [], projectFilters: [], projectCounts: {}, projectTotal: 0, projectHasMore: false, keyword: '', projectFilter: '全部', error: '', busy: false, primary: '', canReturn: false, budgetCard: false, budgetReady: false, budgetBasis: null, budgetNotice: '', pricingQuote: null, isComplete: false, platformStatus: 'loading', adminName: '', adminDate: '', preview: null },
  onLoad(options) {
    this.disposed = false; this.options = options || {}; this.id = options.id || '';
    let view = options.view || 'progress';
    // 模拟支付/体验收据视图随支付链路下线，历史深链一律回落进度态
    if (['pay', 'paid', 'created'].includes(view)) view = 'progress';
    this.view = view; this.initialized = false; // 加载统一由 onShow→refresh 触发，避免冷启动重复请求
  },
  onShow() { this.refresh(false); },
  onUnload() { this.disposed = true; },
  /** 在线岗位：无 token 或无后端岗位时页面整体进入登录引导错误态 */
  post() { try { return http.token() ? auth.currentApiPost() : null; } catch (e) { return null; } },
  actor() { const post = this.__post; return post ? require('../../../utils/organization').apiContext(post) : null; },
  cmdKey(action) { this.__cmdKeys = this.__cmdKeys || {}; if (!this.__cmdKeys[action]) this.__cmdKeys[action] = commandKey(action); return this.__cmdKeys[action]; },
  dropCmdKey(action) { if (this.__cmdKeys) delete this.__cmdKeys[action]; },
  failState(message) {
    this.action = ''; this.nextView = ''; this.initialized = false; this.__list = null;
    this.setData({ platformStatus: 'degraded', error: message, customer: null, rows: [], tasks: [], taskCount: 0, metrics: [], recent: [], projectRows: [], projectFilters: [], projectCounts: {}, projectTotal: 0, projectHasMore: false, form: {}, fields: [], preview: null, primary: '', canReturn: false, budgetCard: false, isComplete: false, budgetReady: false, budgetBasis: null, budgetNotice: '', pricingQuote: null });
  },
  refresh(reset = false) {
    const post = this.post();
    if (!post) return this.failState('请先登录后端账号');
    this.__post = post;
    const alive = http.requestGuard('commission');
    if (this.view === 'admin' || this.view === 'projects') return this.refreshWorkbench(alive);
    if (this.view === 'policy' || this.view === 'pricing') return this.refreshRules(alive, reset);
    return this.refreshCommission(alive, reset);
  },
  // ---- 设计行政工作台（首页/项目）：commissions.page → 工作台视图 ----
  async refreshWorkbench(alive) {
    const actor = this.actor();
    if (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters') return this.failState('当前岗位无设计行政权限');
    this.setData({ platformStatus: 'loading', error: '' });
    try {
      const res = await API.commissions.page(1, 100);
      if (!alive() || this.disposed) return;
      this.__list = ((res && res.list) || []).map(Presentation.normalize);
      const wb = Presentation.workbench(this.__list);
      if (this.view === 'admin') {
        this.setData({ title: '首页', view: 'admin', platformStatus: 'ready', error: '', primary: '', tasks: wb.tasks, taskCount: wb.taskCount, metrics: wb.metrics, recent: wb.recent, projectFilters: wb.filters, adminName: actor.name || this.__post.name || '', adminDate: Presentation.dateLabel() });
      } else {
        this.setData({ title: '项目', view: 'projects', platformStatus: 'ready', error: '', primary: '', projectFilters: wb.filters });
        this.renderAdminProjects(true);
      }
    } catch (e) { if (!alive() || this.disposed) return; this.failState(Presentation.errorMessage(e)); }
  },
  // ---- 费用规则（policy=老板标准费用 / pricing=总部计价）：policies.current → 表单 ----
  async refreshRules(alive, reset) {
    const actor = this.actor(), isPricing = this.view === 'pricing';
    if (isPricing && (actor.role !== 'hq-design-admin' || actor.orgType !== 'headquarters')) return this.failState('设计费计价由总部设计人员配置');
    if (!isPricing && !(actor.orgType === 'store' && ['boss', 'owner'].includes(actor.role))) return this.failState('标准费用由加盟商老板配置');
    this.setData({ platformStatus: 'loading', error: '' });
    try {
      const res = await API.policies.current();
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
    this.setData({ platformStatus: 'loading', error: '' });
    let res = null;
    try {
      res = await API.commissions.get(this.id);
    } catch (err) {
      if (!alive() || this.disposed) return;
      // 尚未提交委托的新客户：详情 404 视为空委托，允许进入提交视图
      if (err && Number(err.code) === 404) res = null;
      else return this.failState(Presentation.errorMessage(err));
    }
    if (!alive() || this.disposed) return;
    const c = Presentation.normalize(res || {});
    const view = this.view, actor = this.actor();
    const n = Presentation.stateOf(c);
    const action = Presentation.actionFor(view);
    const can = !!action && Presentation.canDo(c, view, actor);
    const basis = c.request && c.request.basis;
    const budgetChanged = view === 'submit' && this.initialized && basis && this.data.form.__budgetVersion !== undefined && this.data.form.__budgetVersion !== basis.version;
    let form = (reset || !this.initialized) ? Presentation.formInit(c, view) : this.data.form;
    // 参考预算更新后保留已填内容，但确认勾选必须对本版重做
    if (budgetChanged && basis) form = { ...form, __budgetVersion: basis.version, checked: false };
    this.initialized = true;
    const fields = Presentation.fieldsFor(c, view, can);
    const rows = Presentation.rowsFor(c, n);
    const labels = { submit: '提交设计委托', feedback: '提交修改意见', complete: '设计完成', dispatch: '上传并下发成果', handover: c.handover ? '补充后移交给工程' : '移交给工程', receive: '确认接收' };
    this.action = can ? action : '';
    this.nextView = !can && n.actionable && n.view !== view && Presentation.actionFor(n.view) ? n.view : '';
    this.setData({
      title: ({ submit: '设计委托', progress: '委托与交付', feedback: '登记修改意见', handover: '成果与移交', complete: '设计办理', dispatch: '下发设计成果', receive: '业务资料接收' })[view] || '委托与交付',
      view, platformStatus: 'ready', error: '',
      customer: c.id || c.name ? { id: c.id, projectId: c.projectId, name: c.name, city: c.city, revision: c.revision } : null,
      form, fields, rows,
      budgetReady: !!basis, budgetBasis: basis,
      budgetNotice: budgetChanged ? '参考预算已更新，请核对本版尺寸、需求和范围后重新勾选确认。' : view === 'submit' ? this.data.budgetNotice : '',
      pricingQuote: Presentation.quoteOf(c),
      primary: can ? (view === 'submit' && !basis ? '先完成参考预算' : labels[view]) : this.nextView ? '继续办理' : '',
      budgetCard: view === 'submit' && can,
      canReturn: can && view === 'receive',
      isComplete: !!c.acceptance
    });
  },
  renderAdminProjects(reset = false) {
    if (reset) this.projectLimit = 20;
    const page = Presentation.paginate(this.__list || [], this.data.keyword, this.data.projectFilter, this.projectLimit || 20);
    this.setData({ projectRows: page.rows, projectTotal: page.total, projectHasMore: page.hasMore });
  },
  searchProjects(e) { this.setData({ keyword: e.detail.value }); this.renderAdminProjects(true); },
  selectProjectFilter(e) { this.setData({ projectFilter: e.currentTarget.dataset.filter }); this.renderAdminProjects(true); },
  loadMoreProjects() { if (!this.data.projectHasMore) return; this.projectLimit = (this.projectLimit || 20) + 20; this.renderAdminProjects(false); },
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
    if (next === 'projects') this.renderAdminProjects(true); else this.refresh(true);
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    return true;
  },
  openTask(e) { Nav.open(e.currentTarget.dataset.url); },
  check(e) { this.setData({ ['form.' + e.currentTarget.dataset.key]: e.detail.value.length > 0, error: '' }); },
  chooseFile(e) {
    const key = e.currentTarget.dataset.key;
    wx.chooseMessageFile({
      count: 1, type: 'file',
      success: async r => {
        try {
          const f = r.tempFiles[0], saved = await require('../../../utils/creation').persistMedia(f.path);
          this.setData({ ['form.' + key]: { name: f.name, path: saved, size: f.size }, error: '' });
        } catch (err) { this.setData({ error: err.message }); }
      },
      fail: err => { if (!/cancel/i.test(err.errMsg || '')) this.setData({ error: '文件选择失败，请重新选择' }); }
    });
  },
  preview(e) {
    const f = this.data.form[e.currentTarget.dataset.key] || (this.data.rows[Number(e.currentTarget.dataset.index)] || {}).file;
    if (!f) return;
    if (f.text) return this.setData({ preview: { name: f.name, text: f.text } });
    if (f.path) return wx.openDocument({ filePath: f.path, showMenu: true, fail: () => this.setData({ error: '此格式无法直接预览，请核对原文件后继续' }) });
    if (f.url) return wx.downloadFile({ url: f.url, success: r => wx.openDocument({ filePath: r.tempFilePath, showMenu: true, fail: () => this.setData({ error: '此格式无法直接预览，请核对原文件后继续' }) }), fail: () => this.setData({ error: '附件下载失败，请稍后重试' }) });
    this.setData({ error: '该附件暂无可预览的文件' });
  },
  closePreview() { this.setData({ preview: null }); },
  completeBudget() { if (this.data.busy || this.view !== 'submit' || this.action !== 'commission-submit') return; Nav.open('/pages/workflow/index?view=budget&id=' + encodeURIComponent(this.id)); },
  /** 命令 payload：表单 → 后端字段（金额统一转分，内部键剔除） */
  commandPayload(action) {
    const f = this.data.form;
    const fileRef = file => file ? { name: file.name, size: file.size, path: file.path, url: file.url } : null;
    if (action === 'commission-submit') {
      const text = String(f.text || '').trim(), scope = String(f.scope || '').trim();
      if (!text) throw Error('请填写客户设计需求');
      if (!scope) throw Error('请填写本次交付范围');
      if (!f.checked) throw Error('请勾选确认已核对需求、尺寸来源、交付范围及费用约定');
      const payload = { text, scope, checked: true, budgetVersion: f.__budgetVersion, attachment: fileRef(f.attachment) };
      if (f.__pricingVersion) payload.pricingVersion = f.__pricingVersion;
      if (f.total) { // 分笔费用约定（历史委托沿用）
        payload.totalCents = Money.parseCents(f.total);
        payload.firstCents = Money.parseCents(f.first);
        payload.recipient = String(f.recipient || '').trim();
        if (!payload.recipient) throw Error('请填写总部实际收款方');
        if (payload.firstCents >= payload.totalCents) throw Error('首款须小于总额');
      }
      return payload;
    }
    if (action === 'commission-feedback') {
      const note = String(f.note || '').trim();
      if (!note) throw Error('请填写需要线下沟通的修改意见');
      return { note, attachment: fileRef(f.attachment) };
    }
    if (action === 'design-complete') {
      if (!f.checked) throw Error('请确认本次线下设计及内部审核已完成');
      return { note: String(f.note || '').trim(), drawingVersion: String(f.drawingVersion || '').trim(), checked: true };
    }
    if (action === 'design-dispatch') {
      if (!String(f.recipient || '').trim() || !String(f.note || '').trim() || !f.checked) throw Error('请填写实际接收人、交付清单或渠道，并确认已经下发');
      return { recipient: String(f.recipient || '').trim(), drawingVersion: String(f.drawingVersion || '').trim(), note: String(f.note || '').trim(), checked: true };
    }
    if (action === 'packet-submit') {
      if (!String(f.drawingVersion || '').trim()) throw Error('请填写本次施工图版本');
      if (!f.confirmationDate || String(f.confirmationDate) > require('../../../utils/account').localDate()) throw Error('请填写有效的客户确认日期');
      if (!f.received || !f.confirmed || !String(f.note || '').trim()) throw Error('请核对文件、图纸版本、客户确认日期及交底内容');
      return { resultFile: fileRef(f.resultFile), drawingFile: fileRef(f.drawingFile), reviewFile: fileRef(f.reviewFile), confirmationFile: fileRef(f.confirmationFile), drawingVersion: String(f.drawingVersion || '').trim(), confirmationDate: f.confirmationDate, note: String(f.note || '').trim(), received: true, confirmed: true, dispatchVersion: f.dispatchVersion };
    }
    if (action === 'packet-return') {
      const note = String(f.note || '').trim();
      if (!note) throw Error('请填写资料退回原因');
      return { note };
    }
    if (action === 'packet-receive') {
      if (!f.checked) throw Error('请核对本次图纸、签认依据和交底资料');
      return { checked: true };
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
      await API.policies.save({ scope, recipient, rateCents, active: f.active !== false });
      return;
    }
    if (!scope || !recipient) throw Error('请填写交付范围、总部实际收款方');
    const totalCents = Money.parseCents(f.total), firstCents = Money.parseCents(f.first);
    if (firstCents >= totalCents) throw Error('首款须小于总额');
    await API.policies.save({ scope, recipient, totalCents, firstCents, active: f.active !== false });
  },
  async submit() {
    if (this.data.busy) return;
    if (this.nextView) return Nav.open(Presentation.url(this.id, this.nextView));
    this.setData({ busy: true, error: '' });
    try {
      if (this.view === 'policy') { await this.saveRules(false); wx.showToast({ title: '历史规则已保存', icon: 'success' }); }
      else if (this.view === 'pricing') { await this.saveRules(true); wx.showToast({ title: '计价规则已保存', icon: 'success' }); }
      else {
        const action = this.action;
        if (!action) throw Error('当前没有可提交的办理动作');
        const payload = this.commandPayload(action);
        // 幂等键：失败时保留原键以便安全重试，成功后作废取新键
        if (action === 'commission-submit') await API.commissions.submit(this.id, payload, this.cmdKey(action));
        else await API.commissions.command(this.id, action, payload, this.cmdKey(action));
        this.dropCmdKey(action);
        this.action = ''; this.nextView = ''; this.view = 'progress'; this.initialized = false;
        this.setData({ busy: false });
        this.refresh(true);
        wx.pageScrollTo({ scrollTop: 0, duration: 0 });
        wx.showToast({ title: '已保存并更新待办', icon: 'success' });
        return;
      }
      this.initialized = false;
      this.setData({ busy: false });
      this.refresh(true);
    } catch (e) {
      this.setData({ error: Presentation.errorMessage(e), busy: false });
    }
  },
  returnPacket() {
    if (this.data.busy) return;
    const action = 'packet-return';
    this.setData({ busy: true, error: '' });
    API.commissions.command(this.id, action, this.commandPayload(action), this.cmdKey(action)).then(() => {
      this.dropCmdKey(action);
      this.action = ''; this.nextView = ''; this.view = 'progress'; this.initialized = false;
      this.setData({ busy: false });
      this.refresh(true);
    }).catch(e => this.setData({ error: Presentation.errorMessage(e), busy: false }));
  }
});
