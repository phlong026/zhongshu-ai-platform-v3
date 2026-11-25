const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('../../../utils/api-scope');
const navigation = require('../../../utils/page-navigation');
const model = require('./model');
const organization = require('../../../utils/organization');
const base = '/franchise/finance/payroll';
const clone = value => JSON.parse(JSON.stringify(value));
const message = error => String(error?.msg || error?.message || '操作未完成，请重试');
const revoked = error => error?.postInvalid || [401, 403, 404].includes(Number(error?.code));
const readable = post => post?.orgType === 'store' && ['finance', 'finance-lead', 'boss', 'owner'].includes(post.roleCode);
const writable = post => readable(post) && ['finance', 'finance-lead'].includes(post.roleCode);
Page({
  data: { ready: false, loading: false, error: '', headquarters: false, companyName: '', month: '', families: model.FAMILIES, familyIndex: 0, kind: 'records',
    context: null, activeRule: null, staff: [], rows: [], listBusy: false, listError: '', hasMore: false,
    form: null, formLoading: false, ruleForm: null, totalText: '—', busy: false, uncertain: false, conflict: false,
    detail: null, versions: [], versionsMore: false, versionsLoading: false, versionsError: '' },
  onLoad(options = {}) { this.headquarters = options.headquarters === '1'; this.companyId = model.id(options.companyId); this.setData({ headquarters: this.headquarters, month: require('../../../utils/finance-date').day(Date.now()).slice(0, 7) }); this._lineNo = 0; },
  endpoint(path) { return (this.headquarters ? '/platform/headquarters/companies/' + encodeURIComponent(this.companyId) + '/payroll' : base) + path; },
  actor(post) { return (this.headquarters ? organization.headquartersReader(post) : readable(post)) && String(post?.userId) === http.currentUserId() && String(post?.tenantId) === String(http.tenantId()); },
  onShow() { this._visible = true; return this.load(); },
  onHide() {
    this._visible = false; this._read = null; this._listRead = null; this._formRead = null; this._versionsRead = null;
    this.setData({ ready: false, loading: false, context: null, activeRule: null, staff: [], rows: [], detail: null, versions: [],
      form: null, ruleForm: null, formLoading: false, busy: false, listBusy: false, versionsLoading: false, uncertain: !!this._pending });
  },
  onUnload() { this._disposed = true; this.onHide(); this._pending = null; },
  alive(saved = this._scope) {
    const post = auth.currentApiPost();
    return !this._disposed && this._visible && !!http.token() && !!saved && saved === scope() && this.actor(post)
      && String(post.postId) === String(http.currentPostId());
  },
  clear(error = '') {
    this._read = null; this._listRead = null; this._formRead = null; this._versionsRead = null; this._pending = null;
    this.setData({ ready: false, loading: false, context: null, activeRule: null, staff: [], rows: [], form: null, ruleForm: null, detail: null,
      versions: [], formLoading: false, busy: false, listBusy: false, versionsLoading: false, uncertain: false, conflict: false, error });
  },
  family() { return model.FAMILIES[this.data.familyIndex].key; },
  idle() { return this.alive() && !this.data.busy && !this.data.uncertain && !this.data.loading; },
  canEdit() { return !this.headquarters && this.idle() && this.data.context?.canWrite && writable(auth.currentApiPost()) && !this.data.conflict && !this.data.formLoading; },
  async load() {
    if (!this._visible || this._disposed) return;
    const post = auth.currentApiPost(), saved = scope();
    if (!http.token() || !saved || !this.actor(post) || String(post.postId) !== String(http.currentPostId())) return this.clear(this.headquarters ? '请使用总部管理员或总部财务身份查看' : '请使用本公司财务或老板身份查看薪酬核算');
    if (this.headquarters && !this.companyId) return this.clear('公司链接无效，请从总部工作台重新进入');
    if (saved !== this._scope) { this.clear(); this._scope = saved; }
    if (this.data.busy) return;
    const request = this._read = {}, month = this.data.month; this._listRead = null; this._formRead = null; this._versionsRead = null;
    this.setData({ loading: true, listBusy: false, formLoading: false, versionsLoading: false, form: null, ruleForm: null, detail: null, versions: [], error: '' });
    try {
      let ctx,companyName='';
      if (this.headquarters) {
        const response=await http.get('/platform/headquarters/context');
        if(this._read!==request || !this.alive(saved) || month!==this.data.month)return;
        if(!response || response.readOnly!==true || String(response.tenantId)!==String(http.tenantId()) || String(response.headquartersOrgId)!==String(post.orgId)
            || String(response.postId)!==String(post.postId) || response.roleCode!==post.roleCode || !Array.isArray(response.companies)) throw {code:403,message:'总部工作身份校验未通过'};
        const company=response.companies.find(c=>model.id(c.id)===this.companyId);if(!company)throw {code:403,message:'公司不属于当前总部可查看范围'};
        companyName=String(company.name||'所选公司');ctx={companyId:this.companyId,month,canWrite:false,rules:[],staff:[],projects:[]};
      } else {
        const response=await http.get(base+'/context',{month});
        if(this._read!==request || !this.alive(saved) || month!==this.data.month)return;
        ctx=model.context(response,model.id(post.orgId),month);ctx.canWrite=ctx.canWrite&&writable(auth.currentApiPost());
      }
      this.setData({ context: ctx, companyName, activeRule: ctx.rules.find(r => r.family === this.family()), staff: ctx.staff.filter(p => p.family === this.family()),
        ready: true, loading: false, rows: [], hasMore: true, conflict: false, uncertain: !!this._pending,
        error: this._pending ? '上次保存结果尚未核实，请用原请求重试。' : '' });
      this._pageNo = 0; return this.more();
    } catch (error) {
      if (this._read !== request || !this.alive(saved)) return;
      if (revoked(error)) this.clear(message(error)); else this.setData({ loading: false, error: message(error) });
    }
  },
  async more() {
    if (!this.alive() || !this.data.ready || this.data.loading || this.data.listBusy || !this.data.hasMore) return;
    const request = this._listRead = {}, saved = this._scope, pageNo = this._pageNo + 1, kind = this.data.kind, family = this.family();
    this.setData({ listBusy: true, listError: '' });
    try {
      const result = await http.get(this.endpoint('/' + kind), { family, ...(kind === 'records' ? { month: this.data.month } : {}), pageNo, pageSize: 20 });
      if (this._listRead !== request || !this.alive(saved) || kind !== this.data.kind || family !== this.family()) return;
      const parsed = model.page(result, pageNo, this.data.context.companyId, kind), rows = [...this.data.rows, ...parsed.rows];
      if (parsed.rows.some(row => row.family !== family || kind === 'records' && row.month !== this.data.month)) throw Error('薪酬列表范围不匹配');
      this._pageNo = pageNo; this.setData({ rows, listBusy: false, hasMore: rows.length < parsed.total });
    } catch (error) {
      if (this._listRead !== request || !this.alive(saved)) return;
      if (revoked(error)) this.clear(message(error)); else this.setData({ listBusy: false, listError: message(error) });
    }
  },
  changeMonth(event) { const month = model.month(event.detail.value); if (month && this.idle()) { this.setData({ month }); return this.load(); } },
  changeFamily(event) { const index = Number(event.currentTarget.dataset.index); if (model.FAMILIES[index] && this.idle()) { this.setData({ familyIndex: index }); return this.load(); } },
  changeKind(event) { const kind = event.currentTarget.dataset.kind; if (['records', 'rules'].includes(kind) && this.idle()) { this.setData({ kind }); return this.load(); } },
  newRecord() { if (this.canEdit()) this.setData({ detail: null, ruleForm: null, form: this.blank(), totalText: '—', error: '' }); },
  blank() { return { userId: '', userName: '', expectedVersion: 0, base: '', adjustment: '0.00', commissions: [], note: '', confirmed: false }; },
  async chooseStaff(event) {
    if (!this.canEdit() || !this.data.form) return;
    const person = this.data.staff[Number(event.detail.value)]; if (!person) return;
    const request = this._formRead = {}, saved = this._scope, family = this.family(), month = this.data.month;
    this.setData({ form: { ...this.blank(), userId: person.id, userName: person.name }, formLoading: true, totalText: '—', error: '' });
    try {
      const result = await http.get(base + '/latest', { userId: person.id, family, month });
      if (this._formRead !== request || !this.alive(saved) || !this.data.form || this.data.form.userId !== person.id) return;
      if (!result || !Object.prototype.hasOwnProperty.call(result, 'record')) throw Error('员工核算版本尚未确认，请重新选择');
      if (result.record) {
        const record = model.record(result.record, this.data.context.companyId);
        if (record.userId !== person.id || record.family !== family || record.month !== month) throw Error('员工核算范围不匹配');
        const form = { ...this.blank(), userId: person.id, userName: person.name, expectedVersion: record.version, base: record.base,
          adjustment: record.adjustment, commissions: record.commissions.map(line => ({ ...line, key: String(++this._lineNo), stages: [], stagesLoading: false, stageError: '' })) };
        this.setData({ form, totalText: model.total(form) });
        for (const line of form.commissions) this.loadStages(line.key, line.projectId, line.stageId);
      }
      this.setData({ formLoading: false });
    } catch (error) {
      if (this._formRead !== request || !this.alive(saved)) return;
      if (revoked(error)) this.clear(message(error)); else this.setData({ formLoading: false, form: null, error: message(error) });
    }
  },
  newRule() {
    if (!this.canEdit()) return;
    const old = this.data.activeRule.effectiveRule;
    this.setData({ form: null, detail: null, ruleForm: { effectiveMonth: this.data.month, baseSalaryNote: old?.baseSalaryNote || '',
      assessmentNote: old?.assessmentNote || '', commissionNote: old?.commissionNote || '', evidenceNote: '', confirmed: false }, error: '' });
  },
  input(event) {
    if (!this.canEdit()) return;
    const key = event.currentTarget.dataset.key, value = String(event.detail.value ?? '');
    if (this.data.ruleForm && ['effectiveMonth', 'baseSalaryNote', 'assessmentNote', 'commissionNote', 'evidenceNote'].includes(key)) return this.setData({ ['ruleForm.' + key]: value, error: '' });
    if (this.data.form && ['base', 'adjustment', 'note'].includes(key)) {
      const form = { ...this.data.form, [key]: value }; this.setData({ form, totalText: model.total(form), error: '' });
    }
  },
  check(event) { if (this.canEdit()) this.setData({ [(this.data.ruleForm ? 'ruleForm' : 'form') + '.confirmed']: (event.detail.value || []).includes('confirmed') }); },
  closeForm() { if (this.idle()) { this._formRead = null; this.setData({ form: null, ruleForm: null, formLoading: false, error: '' }); } },
  addLine() { if (this.canEdit() && this.data.form && this.data.form.commissions.length < 30) { const form = clone(this.data.form); form.commissions.push({ key: String(++this._lineNo), projectId: '', projectName: '', stageId: '', stages: [], amount: '', basis: '' }); this.setData({ form, totalText: model.total(form) }); } },
  removeLine(event) { if (this.canEdit() && this.data.form) { const form = clone(this.data.form); form.commissions = form.commissions.filter(row => row.key !== event.currentTarget.dataset.key); this.setData({ form, totalText: model.total(form) }); } },
  lineInput(event) {
    if (!this.canEdit() || !this.data.form || !['amount', 'basis'].includes(event.currentTarget.dataset.field)) return;
    const form = clone(this.data.form), line = form.commissions.find(r => r.key === event.currentTarget.dataset.key); if (!line) return;
    line[event.currentTarget.dataset.field] = String(event.detail.value || ''); this.setData({ form, totalText: model.total(form) });
  },
  chooseProject(event) {
    if (!this.canEdit() || !this.data.form) return;
    const project = this.data.context.projects[Number(event.detail.value)], form = clone(this.data.form), line = form.commissions.find(r => r.key === event.currentTarget.dataset.key);
    if (!project || !line) return;
    Object.assign(line, { projectId: project.id, projectName: project.name, stageId: '', stageName: '不关联付款节点', netText: '', stages: [], stageError: '' });
    this.setData({ form }); return this.loadStages(line.key, project.id, '');
  },
  async loadStages(key, project, selected) {
    if(this.headquarters)return;
    const saved = this._scope, form = this.data.form, line = form?.commissions.find(r => r.key === key); if (!line || !this.alive()) return;
    const request = {}; this._stages = this._stages || {}; this._stages[key] = request;
    const update = patch => { if (!this.alive(saved) || this._stages[key] !== request) return false; const current = clone(this.data.form); const row = current?.commissions.find(r => r.key === key); if (!row || row.projectId !== project) return false; Object.assign(row, patch); this.setData({ form: current }); return true; };
    update({ stagesLoading: true, stageError: '' });
    try {
      const response = await http.get(base + '/projects/' + encodeURIComponent(project) + '/stages'), stages = model.stages(response, project), choice = stages.find(s => s.stageId === selected);
      update({ stages, stagesLoading: false, stageName: choice?.label || '原付款节点已变更，请重新选择', netText: choice?.netText || '', stageError: choice ? '' : '原付款节点已变更，请重新核对' });
    } catch (error) { if (!this.alive(saved) || this._stages[key] !== request) return; if (revoked(error)) this.clear(message(error)); else update({ stages: [], stagesLoading: false, stageError: message(error) }); }
  },
  chooseStage(event) {
    if (!this.canEdit() || !this.data.form) return;
    const form = clone(this.data.form), line = form.commissions.find(r => r.key === event.currentTarget.dataset.key), stage = line?.stages?.[Number(event.detail.value)]; if (!stage) return;
    Object.assign(line, { stageId: stage.stageId, stageName: stage.label, netText: stage.netText, stageError: '' }); this.setData({ form });
  },
  submit() {
    if (!this.canEdit()) return;
    try {
      const kind = this.data.ruleForm ? 'rules' : 'records', body = kind === 'rules' ? model.rulePayload(this.data.ruleForm, this.data.context, this.family()) : model.recordPayload(this.data.form, this.data.context, this.family());
      this._pending = { scope: this._scope, company: this.data.context.companyId, kind, body, key: 'payroll-' + Date.now() + '-' + Math.random().toString(36).slice(2) };
      return this.retry();
    } catch (error) { this.setData({ error: message(error) }); }
  },
  async retry() {
    const command = this._pending; if (this.headquarters || !command || !this.alive(command.scope) || !writable(auth.currentApiPost()) || this.data.busy) return;
    this._read = null; this._listRead = null; this._versionsRead = null;
    this.setData({ busy: true, uncertain: false, listBusy: false, versionsLoading: false, error: '' });
    try {
      const result = await http.post(base + '/' + command.kind, command.body, { 'Idempotency-Key': command.key });
      if (!this.alive(command.scope) || this._pending !== command) return;
      const value = command.kind === 'rules' ? model.rule(result, command.company) : model.record(result, command.company);
      if (value.version !== command.body.expectedVersion + 1 || value.family !== command.body.family
          || command.kind === 'records' && (value.userId !== command.body.userId || value.month !== command.body.month)) throw Error('保存结果尚未核实，请用原请求重试');
      this._pending = null; this.setData({ busy: false, uncertain: false, form: null, ruleForm: null });
      wx.showToast({ title: command.kind === 'rules' ? '规则版本已保存' : '人工核算已记录', icon: 'none' }); return this.load();
    } catch (error) {
      if (!this.alive(command.scope) || this._pending !== command) return;
      if (revoked(error)) return this.clear(message(error));
      const code = Number(error?.code), uncertain = !Number.isFinite(code) || code === -1 || code >= 500 && code < 600 || code === 1099000000;
      if (!uncertain) this._pending = null;
      this.setData({ busy: false, uncertain, conflict: code === 409 || code === 1099000001, error: uncertain ? '保存结果尚未核实，请用原请求重试。' : message(error) });
    }
  },
  openRecord(event) {
    if (!this.idle()) return;
    const row = this.data.rows.find(r => r.id === String(event.currentTarget.dataset.id)); if (!row || this.data.kind !== 'records') return;
    this._versionsRead = null; this._versionPage = 0;
    this.setData({ form: null, ruleForm: null, detail: row, versions: [], versionsMore: true, versionsLoading: false, versionsError: '' }); return this.moreVersions();
  },
  closeDetail() { if (this.idle()) { this._versionsRead = null; this.setData({ detail: null, versions: [], versionsLoading: false }); } },
  viewVersion(event) { if (this.idle()) { const row = this.data.versions.find(r => r.id === String(event.currentTarget.dataset.id)); if (row) { this._versionsRead = null; this.setData({ detail: row, versionsLoading: false }); } } },
  async moreVersions() {
    if (!this.alive() || this.data.loading || !this.data.detail || this.data.versionsLoading || !this.data.versionsMore) return;
    const request = this._versionsRead = {}, saved = this._scope, record = this.data.detail, pageNo = this._versionPage + 1;
    this.setData({ versionsLoading: true, versionsError: '' });
    try {
      const response = await http.get(this.endpoint('/records/' + record.id + (this.headquarters ? '/history' : '/versions')), { pageNo, pageSize: 20 });
      if (this._versionsRead !== request || !this.alive(saved) || this.data.detail?.id !== record.id) return;
      const result = model.page(response, pageNo, this.data.context.companyId, 'records');
      if (result.rows.some(row => row.userId !== record.userId || row.month !== record.month || row.family !== record.family)) throw Error('薪酬历史范围不匹配');
      const rows = [...this.data.versions, ...result.rows]; this._versionPage = pageNo; this.setData({ versions: rows, versionsLoading: false, versionsMore: rows.length < result.total });
    } catch (error) { if (this._versionsRead !== request || !this.alive(saved)) return; if (revoked(error)) this.clear(message(error)); else this.setData({ versionsLoading: false, versionsError: message(error) }); }
  },
  correct() { if (!this.canEdit() || !this.data.detail) return; const index = this.data.staff.findIndex(p => p.id === this.data.detail.userId); if (index < 0) return this.setData({ error: '该员工已不在本公司当前岗位，历史记录只读保留' }); this.newRecord(); return this.chooseStaff({ detail: { value: index } }); },
  login() { navigation.open('/pages/account/index'); }
});
