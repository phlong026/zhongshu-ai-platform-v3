const api = require('./api');
const M = require('./model');
const S = require('./session');
const Files = require('./files');
const O = require('../../utils/organization');
const navigation = require('../../utils/page-navigation');
const empty = () => ({ ready: false, loading: false, error: '', orgName: '', roleName: '', brands: [], companies: [], companyChoices: [],
  brandIndex: 0, companyIndex: 0, brandName: '全部品牌', companyName: '全部公司', rows: [], books: [], total: 0, pageNo: 0,
  summary: null, keyword: '', status: '', dateFrom: '', dateTo: '', hasMore: false, exportReady: false, fileBusy: false, exportError: '', canOpenPayroll: false });

Page({
  data: { ...empty(), sections: M.SECTIONS, financeKinds: M.FINANCE, section: 'overview', financeKind: 'receipts', activeIndex: 21 },
  onLoad(options = {}) {
    this.options = options;
    this._section = M.SECTIONS.some(item => item.key === options.section) ? options.section : '';
    this._filters = {}; this._listSequence = 0;
  },
  clearPrivate() {
    this._fileEpoch = (this._fileEpoch || 0) + 1;
    this._listSequence += 1; this._rows = []; this._export = null; this._catalog = null;
    this.setData(empty());
  },
  onShow() { return this.reload(); },
  onHide() { Files.cancel(this); S.hide(this); },
  onUnload() { this.onHide(); Files.cleanup(this); },
  async reload() {
    Files.cancel(this); Files.cleanup(this); this.clearPrivate();
    const ticket = S.start(this), post = S.current();
    if (!post) return this.setData({ error: '请使用总部管理员或总部财务的有效工作身份进入' });
    if (this._filterScope !== ticket.scope) this._filters = {};
    this._filterScope = ticket.scope;
    this._section = this._section || (['finance', 'finance-lead'].includes(post.roleCode) ? 'finance' : 'overview');
    this.setData({ loading: true, section: this._section, activeIndex: this._section === 'books' ? 22 : 21 });
    try {
      const context = await api.context();
      if (!S.context(this, ticket, context)) return;
      this._catalog = M.context(context);
      this.setData({ ready: true, orgName: post.orgName || '总部', roleName: O.roles[post.roleCode], error: '' });
      this.syncChoices();
      await this.loadContent();
    } catch (error) { S.fail(this, ticket, error); }
  },
  syncChoices() {
    const catalog = this._catalog;
    if (!catalog) return;
    if (!catalog.brands.some(row => row.id === this._filters.brandId)) this._filters.brandId = '';
    const companies = catalog.companies.filter(row => !this._filters.brandId || row.brandState === 'RESOLVED' && row.brandId === this._filters.brandId);
    if (!companies.some(row => row.id === this._filters.companyId)) this._filters.companyId = '';
    const brands = [{ id: '', name: '全部品牌' }, ...catalog.brands], choices = [{ id: '', name: '全部公司' }, ...companies];
    const brandIndex = brands.findIndex(row => row.id === this._filters.brandId), companyIndex = choices.findIndex(row => row.id === this._filters.companyId);
    this.setData({ brands, companies, companyChoices: choices, brandIndex, companyIndex, brandName: brands[brandIndex].name, companyName: choices[companyIndex].name,
      canOpenPayroll: choices[companyIndex].orgType === 'store',
      keyword: this._filters.keyword || '', status: this._filters.status || '', dateFrom: this._filters.dateFrom || '', dateTo: this._filters.dateTo || '' });
  },
  dataset() { return this._section === 'finance' ? this.data.financeKind : this._section; },
  async loadContent(more = false) {
    const ticket = S.ticket(this);
    if (!this._hqContext || !S.alive(this, ticket) || more && (this.data.loading || !this.data.hasMore)) return;
    const sequence = ++this._listSequence, pageNo = more ? this.data.pageNo + 1 : 1, section = this._section;
    const filters = { ...this._filters };
    const current = () => S.alive(this, ticket) && this._listSequence === sequence;
    this._fileEpoch += 1; this._export = null; Files.cancel(this); Files.cleanup(this);
    this.setData({ loading: true, error: '', exportReady: false, exportError: '', fileBusy: false,
      ...(!more ? { rows: [], books: [], summary: null, total: 0, pageNo: 0, hasMore: false } : {}) });
    if (!more) this._rows = [];
    try {
      if (section === 'overview') {
        const summary = await api.summary(filters);
        if (!current()) return;
        if (summary?.readOnly !== true || !Number.isSafeInteger(summary.customers) || !Number.isSafeInteger(summary.projects)) throw Error('概览数据不完整');
        this.setData({ summary: { customers: summary.customers, projects: summary.projects }, loading: false });
      } else if (section === 'books') {
        const books = filters.companyId ? await api.books(filters.companyId, filters.keyword) : [];
        if (!current()) return;
        this.setData({ books: M.books(books), loading: false });
      } else {
        const kind = this.dataset(), result = M.page(await api.list(kind, { ...filters, pageNo, pageSize: 20 }), pageNo);
        if (!current()) return;
        const rows = result.list.map(row => M.card(kind, row));
        const merged = more ? this._rows.concat(rows) : rows;
        this._rows = Array.from(new Map(merged.map(row => [row.id, row])).values());
        this.setData({ rows: this._rows, total: result.total, pageNo, hasMore: pageNo * 20 < result.total, loading: false });
      }
    } catch (error) { if (current()) S.fail(this, ticket, error, { loading: false }); }
  },
  selectSection(event) {
    const section = event.currentTarget.dataset.section;
    if (!M.SECTIONS.some(item => item.key === section) || !this._hqContext) return;
    this._section = section; this.options.section = section;
    this._filters = { brandId: this._filters.brandId, companyId: this._filters.companyId };
    this.setData({ section, activeIndex: section === 'books' ? 22 : 21 }); this.syncChoices();
    return this.loadContent();
  },
  selectFinance(event) {
    const kind = event.currentTarget.dataset.kind;
    if (!M.FINANCE.some(item => item.key === kind)) return;
    this._filters.status = ''; this.setData({ financeKind: kind, status: '' }); return this.loadContent();
  },
  selectBrand(event) {
    const row = this.data.brands[Number(event.detail.value)]; if (!row) return;
    this._filters.brandId = row.id; this._filters.companyId = ''; this.syncChoices(); return this.loadContent();
  },
  selectCompany(event) {
    const row = this.data.companyChoices[Number(event.detail.value)]; if (!row) return;
    this._filters.companyId = row.id; this.syncChoices(); return this.loadContent();
  },
  chooseBookCompany(event) {
    const row = this.data.companies[Number(event.currentTarget.dataset.index)]; if (!row) return;
    this._filters.companyId = row.id; this.syncChoices(); return this.loadContent();
  },
  input(event) {
    const key = event.currentTarget.dataset.field;
    if (!['keyword', 'status', 'dateFrom', 'dateTo'].includes(key)) return;
    this.setData({ [key]: String(event.detail.value || '') });
    this._fileEpoch += 1; Files.cancel(this); Files.cleanup(this);
    this._export = null; this.setData({ exportReady: false, fileBusy: false });
  },
  applyFilters() {
    try {
      const filters = api.filters({ ...this._filters, keyword: this.data.keyword, status: this.data.status, dateFrom: this.data.dateFrom, dateTo: this.data.dateTo });
      this._filters = filters; return this.loadContent();
    } catch (error) { this.setData({ error: error.message }); }
  },
  clearFilters() {
    this._filters = { brandId: this._filters.brandId, companyId: this._filters.companyId };
    this.syncChoices(); return this.loadContent();
  },
  more() { return this.loadContent(true); },
  retry() { return this._hqContext ? this.loadContent(this.data.pageNo > 0) : this.reload(); },
  openRow(event) {
    if (!S.alive(this, S.ticket(this)) || !this._hqContext) return;
    const row = this._rows[Number(event.currentTarget.dataset.index)];
    if (row) navigation.open('/pages/headquarters/detail?kind=' + this.dataset() + '&id=' + row.id);
  },
  openBook(event) {
    if (!S.alive(this, S.ticket(this)) || !this._hqContext) return;
    const row = this.data.books[Number(event.currentTarget.dataset.index)];
    if (row && this._filters.companyId) navigation.open('/pages/headquarters/detail?kind=ledger&companyId=' + this._filters.companyId + '&scope=' + encodeURIComponent(row.scope));
  },
  openPayroll() {
    if (!S.alive(this, S.ticket(this)) || !this._hqContext || this._section !== 'finance') return;
    const company = this._catalog?.companies.find(row => row.id === this._filters.companyId && row.orgType === 'store');
    if (company) navigation.open('/pages/admin/payroll/index?headquarters=1&companyId=' + api.id(company.id));
  },
  async exportRows() {
    const ticket = { ...S.ticket(this), fileEpoch: this._fileEpoch + 1 };
    if (!this._hqContext || !S.alive(this, ticket) || this.data.fileBusy || this.data.loading || !api.DATASETS.includes(this.dataset())) return;
    this._fileEpoch = ticket.fileEpoch;
    const sequence = this._listSequence, kind = this.dataset(), filters = { ...this._filters };
    this._export = null; Files.cleanup(this);
    this.setData({ fileBusy: true, exportReady: false, exportError: '' });
    try {
      const file = await Files.exportCsv(this, kind, filters, ticket);
      if (!S.alive(this, ticket) || sequence !== this._listSequence || ticket.fileEpoch !== this._fileEpoch) return;
      this._export = file; this.setData({ exportReady: true });
    } catch (error) {
      if (S.alive(this, ticket) && sequence === this._listSequence && ticket.fileEpoch === this._fileEpoch) {
        if ([401, 403].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, error);
        else if (!error.silent) this.setData({ exportError: error.message || '导出失败，请重试' });
      }
    } finally { if (S.alive(this, ticket) && sequence === this._listSequence && ticket.fileEpoch === this._fileEpoch) this.setData({ fileBusy: false }); }
  },
  async shareExport() {
    const ticket = { ...S.ticket(this), fileEpoch: this._fileEpoch };
    if (this.data.fileBusy || !this._export || !this._hqContext) return;
    this.setData({ fileBusy: true, exportError: '' });
    try { await Files.share(this, this._export, ticket); }
    catch (error) {
      if (S.alive(this, ticket) && ticket.fileEpoch === this._fileEpoch && !error.silent) {
        if ([401, 403].includes(Number(error.code)) || error.postInvalid) S.fail(this, ticket, error);
        else this.setData({ exportError: error.message });
      }
    } finally { if (S.alive(this, ticket) && ticket.fileEpoch === this._fileEpoch) this.setData({ fileBusy: false }); }
  },
  switchWorkspaceTab(tab) {
    if (!['headquarters-home', 'headquarters-books'].includes(tab.key)) return false;
    const section = tab.key === 'headquarters-books' ? 'books' : S.current()?.roleCode === 'hq-admin' ? 'overview' : 'finance';
    this.selectSection({ currentTarget: { dataset: { section } } }); return true;
  },
  hasWorkspaceSubpage() { return !['overview', 'books', 'finance'].includes(this.data.section); },
  identity() { navigation.open('/pages/account/index?view=identity'); }
});
