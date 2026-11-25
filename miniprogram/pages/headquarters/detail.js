const api = require('./api');
const M = require('./model');
const External = require('./external');
const S = require('./session');
const Files = require('./files');
const navigation = require('../../utils/page-navigation');
const { routeValue } = require('../../utils/view-container');
const empty = () => ({ ready: false, loading: false, error: '', detail: null, ledger: null, companyName: '', history: [], historyOpen: false,
  historyPage: 0, historyTotal: 0, historyMore: false, historyLoading: false, historyError: '', documents: [], documentsPage: 0, documentsMore: false,
  documentsLoading: false, documentsError: '', siteRecords: [], sitePage: 0, siteMore: false, siteLoading: false, siteError: '', expanded: '', fileBusy: false, fileError: '',
  externalOpen: false, externalKind: 'requests', externalRows: [], externalPage: 0, externalMore: false, externalLoading: false, externalError: '' });

Page({
  data: { ...empty(), kind: '', title: '总部详情', ledgerTitle: '' },
  onLoad(options = {}) { this.options = options; this._routeScope = require('../../utils/api-scope')(); },
  clearPrivate() { this._externalRead = (this._externalRead || 0) + 1; this._fileActions = new Map(); this.setData(empty()); },
  onHide() { Files.cancel(this); S.hide(this); },
  onUnload() { this.onHide(); Files.cleanup(this); },
  onShow() { return this.reload(); },
  registerFile(action, label) {
    api.attachmentPath(action);
    const key = 'file-' + this._fileActions.size;
    this._fileActions.set(key, action); return { key, label: M.text(label) };
  },
  async reload() {
    Files.cancel(this); Files.cleanup(this); this.clearPrivate();
    const ticket = S.start(this), post = S.current();
    if (!post || this._routeScope !== ticket.scope) return this.setData({ error: '工作身份已变化，请从当前总部工作台重新打开记录' });
    this.setData({ loading: true });
    try {
      const kind = this.options.kind;
      if (kind !== 'ledger') api.dataset(kind);
      const recordId = kind === 'ledger' ? api.id(this.options.companyId) : api.id(this.options.id);
      const context = await api.context();
      if (!S.context(this, ticket, context)) return;
      const catalog = M.context(context), register = this.registerFile.bind(this);
      this.setData({ kind, title: kind === 'customers' ? '客户八步跟进' : kind === 'projects' ? '工程详情' : kind === 'ledger' ? '账本明细' : M.title(kind) + '详情' });
      if (kind === 'ledger') {
        const company = catalog.companies.find(row => row.id === recordId);
        if (!company) throw { code: 403, message: '公司不属于当前可读范围' };
        const scope = api.bookScope(routeValue(this.options.scope)), result = await api.ledger(recordId, scope);
        if (!S.alive(this, ticket)) return;
        this.setData({ ledger: M.ledger(result,scope), companyName: company.name, ledgerTitle: scope === 'company' ? '公司日常账' : scope.startsWith('project:') ? '项目账本' : '前期账本', ready: true, loading: false });
      } else {
        const row = await api.detail(kind, recordId);
        if (!S.alive(this, ticket)) return;
        if (api.id(row.id) !== recordId || !catalog.companies.some(company => company.id === api.id(row.companyId))) throw { code: 403, message: '记录不属于当前可读范围' };
        const detail = kind === 'customers' ? M.customer(row, register) : kind === 'projects' ? M.project(row, register) : M.finance(kind, row, register);
        const extra = {};
        if (kind === 'projects') {
          const docs = M.page(row.documents, 1), sites = M.page(row.siteRecords, 1);
          Object.assign(extra, { documents: M.documents(docs.list, recordId, register), documentsPage: 1, documentsMore: docs.total > docs.pageSize,
            siteRecords: M.siteRecords(sites.list, recordId, register), sitePage: 1, siteMore: sites.total > sites.pageSize });
        }
        this.setData({ detail, ready: true, loading: false, ...extra });
      }
    } catch (error) { S.fail(this, ticket, error); }
  },
  expandStep(event) { const code = event.currentTarget.dataset.code; if (api.STEPS.includes(code)) this.setData({ expanded: this.data.expanded === code ? '' : code }); },
  toggleExternal() {
    if (!this._hqContext || !S.alive(this, S.ticket(this)) || this.data.kind !== 'customers') return;
    const open = !this.data.externalOpen;
    this._externalRead += 1; this.setData({ externalOpen: open, externalLoading: false });
    if (open && !this.data.externalPage) return this.loadExternal();
  },
  selectExternal(event) {
    const kind = event.currentTarget.dataset.kind;
    if (!['requests', 'results'].includes(kind) || kind === this.data.externalKind || !this.data.externalOpen) return;
    this._externalRead += 1;
    this.setData({ externalKind: kind, externalRows: [], externalPage: 0, externalMore: false, externalLoading: false, externalError: '' });
    return this.loadExternal();
  },
  async loadExternal(options = {}) {
    const ticket = S.ticket(this), more = options.more === true;
    if (!this._hqContext || !S.alive(this, ticket) || this.data.kind !== 'customers' || !this.data.externalOpen || this.data.externalLoading
        || more && !this.data.externalMore) return;
    const sequence = ++this._externalRead, kind = this.data.externalKind, pageNo = more ? this.data.externalPage + 1 : 1;
    const current = () => S.alive(this, ticket) && this._externalRead === sequence && this.data.externalOpen;
    this.setData({ externalLoading: true, externalError: '', ...(!more ? { externalRows: [], externalPage: 0, externalMore: false } : {}) });
    try {
      const result = M.page(await api.externalVersions(this.options.id, kind, pageNo), pageNo);
      if (!current()) return;
      const incoming = External.versions(result.list, this.options.id, kind, this.registerFile.bind(this));
      const rows = more ? this.data.externalRows.concat(incoming) : incoming;
      this.setData({ externalRows: Array.from(new Map(rows.map(row => [row.key, row])).values()), externalPage: pageNo, externalMore: pageNo * 20 < result.total });
    } catch (error) {
      if (current()) {
        if ([401, 403, 404].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, { ...error, code: 403 });
        else this.setData({ externalError: error.message || error.msg || '委托资料加载失败，请重试' });
      }
    } finally { if (current()) this.setData({ externalLoading: false }); }
  },
  moreExternal() { return this.loadExternal({ more: this.data.externalPage > 0 }); },
  async toggleHistory() {
    const open = !this.data.historyOpen; this.setData({ historyOpen: open });
    if (open && !this.data.historyPage) await this.loadHistory();
  },
  async loadHistory() {
    const ticket = S.ticket(this);
    if (!this._hqContext || !S.alive(this, ticket) || this.data.historyLoading || this.data.kind !== 'customers') return;
    const pageNo = this.data.historyPage + 1;
    this.setData({ historyLoading: true, historyError: '' });
    try {
      const result = M.page(await api.records(this.options.id, pageNo), pageNo, false);
      if (!S.alive(this, ticket)) return;
      const rows = this.data.history.concat(M.records(result.list, api.id(this.options.id), this.registerFile.bind(this)));
      this.setData({ history: Array.from(new Map(rows.map(row => [row.id, row])).values()), historyPage: pageNo, historyTotal: result.total, historyMore: pageNo * 20 < result.total });
    } catch (error) {
      if (S.alive(this, ticket)) {
        if ([401, 403].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, error);
        else this.setData({ historyError: error.message || '历史记录加载失败，请重试' });
      }
    } finally { if (S.alive(this, ticket)) this.setData({ historyLoading: false }); }
  },
  async moreProjectItems(event) {
    const kind = event.currentTarget.dataset.kind;
    if (!['documents', 'site-records'].includes(kind)) return;
    const docs = kind === 'documents', pageField = docs ? 'documentsPage' : 'sitePage', loading = docs ? 'documentsLoading' : 'siteLoading',
      errorField = docs ? 'documentsError' : 'siteError', rowsField = docs ? 'documents' : 'siteRecords', moreField = docs ? 'documentsMore' : 'siteMore';
    const ticket = S.ticket(this);
    if (!this._hqContext || !S.alive(this, ticket) || this.data[loading] || !this.data[moreField]) return;
    const pageNo = this.data[pageField] + 1;
    this.setData({ [loading]: true, [errorField]: '' });
    try {
      const result = M.page(await api.projectItems(this.options.id, kind, pageNo), pageNo);
      if (!S.alive(this, ticket)) return;
      const incoming = (docs ? M.documents : M.siteRecords)(result.list, api.id(this.options.id), this.registerFile.bind(this));
      const rows = this.data[rowsField].concat(incoming);
      this.setData({ [rowsField]: Array.from(new Map(rows.map(row => [row.id, row])).values()), [pageField]: pageNo, [moreField]: pageNo * 20 < result.total });
    } catch (error) {
      if (S.alive(this, ticket)) {
        if ([401, 403].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, error);
        else this.setData({ [errorField]: error.message || '工程资料加载失败，请重试' });
      }
    } finally { if (S.alive(this, ticket)) this.setData({ [loading]: false }); }
  },
  async openFile(event) {
    const ticket = S.ticket(this), action = this._fileActions.get(event.currentTarget.dataset.key);
    if (!action || !this._hqContext || this.data.fileBusy || !S.alive(this, ticket)) return;
    this.setData({ fileBusy: true, fileError: '' });
    try { await Files.preview(this, action, ticket); }
    catch (error) {
      if (S.alive(this, ticket) && !error.silent) {
        if ([401, 403].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, error);
        else this.setData({ fileError: error.message || '附件读取失败，请重试' });
      }
    } finally { if (S.alive(this, ticket)) this.setData({ fileBusy: false }); }
  },
  openLedger() {
    if (!this._hqContext || !S.alive(this, S.ticket(this)) || this.data.kind !== 'projects') return;
    navigation.open('/pages/headquarters/detail?kind=ledger&companyId=' + this.data.detail.companyId + '&scope=project%3A' + this.data.detail.id);
  },
  openEntry(event) {
    if (!this._hqContext || !S.alive(this, S.ticket(this))) return;
    const entry = this.data.ledger?.entries[Number(event.currentTarget.dataset.index)];
    if (entry?.kind) navigation.open('/pages/headquarters/detail?kind=' + entry.kind + '&id=' + entry.id);
  },
  navigationBack() {
    const section = this.options.kind === 'ledger' ? 'books' : ['customers', 'projects'].includes(this.options.kind) ? this.options.kind : 'finance';
    return navigation.back({ skipLocal: true, fallback: '/pages/headquarters/index?section=' + section });
  },
  identity() { navigation.open('/pages/account/index?view=identity'); }
});
