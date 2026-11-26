const P = require('../../../utils/platform-performance');
const http = require('../../../api/http');
const { id, statuses, versions } = require('./cycle-version');

const sourcePageSize = 30;
const empty = () => ({ sourcePage: 0, sourcePages: 0, members: [], current: null, snapshot: null, versionLabels: [], versionIndex: 0,
  canSubmit: false, canReview: false, note: '', saving: false });
function identity() {
  const post = P.apiPost();
  const user = id(http.currentUserId()), company = post && id(post.orgId);
  if (!user || !company || !id(post.postId) || id(post.postId) !== id(http.currentPostId())) return null;
  return { user, company, role: post.roleCode, key: JSON.stringify([http.tenantId(), post.postId, user, company, post.roleCode, http.sessionGeneration()]) };
}
function validRow(row, actor, period) {
  return row && id(row.companyId) === actor.company && id(row.userId) && row.period === period &&
    Object.prototype.hasOwnProperty.call(statuses, row.status) && Number.isSafeInteger(row.revision) && row.revision > 0;
}

Page({
  data: { ...empty(), period: '', team: false, loading: true, error: '', notice: '' },
  onLoad(options) {
    this.generation = 0;
    this.records = [];
    this.setData({ team: options.scope === 'team', period: P.validPeriod(options.period) ? options.period : P.currentPeriod() });
  },
  onShow() { this.visible = true; return this.refresh(); },
  onHide() {
    this.visible = false;
    this.generation++;
    this.records = [];
    this.versionRows = [];
    this.setData({ ...empty(), loading: true, error: '', notice: '' });
  },
  onUnload() { this.onHide(); this.command = null; },
  activeActor() {
    if (!this.visible) return null;
    const actor = identity();
    if (actor && actor.key === this.scope) return actor;
    this.refresh();
    return null;
  },
  guard(actor, generation) {
    if (!this.visible || generation !== this.generation) return false;
    if (actor.key === identity()?.key) return true;
    this.generation++;
    this.records = []; this.versionRows = []; this.command = null;
    this.setData({...empty(), loading:false, notice:'', error:'岗位已变化，请重新读取周期'});
    return false;
  },
  async refresh(notice = '') {
    const generation = ++this.generation, actor = identity();
    this.records = []; this.versionRows = [];
    this.setData({ ...empty(), loading: true, error: '', notice: typeof notice === 'string' ? notice : '' });
    if (!actor || (this.data.team && !['boss','owner'].includes(actor.role))) {
      this.scope = ''; this.command = null; this.selectedUser = '';
      this.setData({loading: false, error: actor ? '当前岗位无权查看团队周期' : '请先登录并选择有效岗位'});
      return;
    }
    if (actor.key !== this.scope) { this.command = null; this.selectedUser = ''; }
    this.scope = actor.key;
    try {
      const rows = await P.api.cycles.list(this.data.period);
      if (!this.guard(actor, generation)) return;
      if (!Array.isArray(rows)) throw Error('周期列表暂未返回，请重试');
      this.records = rows.filter(row => validRow(row, actor, this.data.period) &&
        (this.data.team ? id(row.userId) !== actor.user : id(row.userId) === actor.user));
      if (!this.data.team && !this.records.length) {
        this.records = [{ companyId: actor.company, userId: actor.user, period: this.data.period, status: '', revision: 0 }];
      }
      this.setData({loading: false}); this.showRows(actor);
      if (this.data.team && this.records.length) {
        const people = await P.loadMembers().catch(() => []);
        if (!this.guard(actor, generation)) return;
        const names = new Map(people.map(person => [id(person.id), person.name]));
        this.setData({members:this.data.members.map(member => ({...member, name:names.get(member.userId) || member.name}))});
      }
    } catch (error) {
      if (this.guard(actor, generation)) this.setData({loading: false, error: P.errorText(error)});
    }
  },
  showRows(actor) {
    const names = new Map(this.data.members.map(member => [member.userId, member.name]));
    const members = this.records.map(r => ({ userId: id(r.userId), name: names.get(id(r.userId)) || r.userName || '成员 ' + id(r.userId), statusLabel: statuses[r.status] || '未提交' }));
    this.setData({members});
    const row = this.records.find(r => id(r.userId) === this.selectedUser) || this.records[0];
    if (!row) return;
    this.selectedUser = id(row.userId);
    this.versionRows = row.revision ? versions(row, actor.role) : [];
    this.setData({current:{userId:this.selectedUser, revision:row.revision, status:row.status, statusLabel:statuses[row.status] || '未提交'},
      versionLabels:this.versionRows.map(v=>v.label), note:''});
    this.showVersion(0);
  },
  showVersion(index) {
    const row = this.records.find(r => id(r.userId) === this.selectedUser);
    const actor = identity();
    const active = index === 0 && actor && actor.key === this.scope && row;
    this.setData({versionIndex: index, note:'',
      canSubmit: !!(active && !this.data.team && id(row.userId) === actor.user && (!row.status || row.status === 'RETURNED' || row.status === 'PENDING_REVIEW')),
      canReview: !!(active && this.data.team && id(row.userId) !== actor.user && row.status === 'PENDING_REVIEW' && row.canReview === true)});
    this.showSources(0);
  },
  showSources(page) {
    const snapshot = this.versionRows[this.data.versionIndex];
    const pages = snapshot ? Math.ceil(snapshot.sources.length / sourcePageSize) : 0;
    if (page < 0 || page >= Math.max(pages, 1)) return;
    const start = page * sourcePageSize;
    this.setData({sourcePage:page, sourcePages:pages,
      snapshot:snapshot ? {...snapshot, sources:snapshot.sources.slice(start, start + sourcePageSize)} : null});
  },
  pageSources(event) {
    if (!this.activeActor()) return;
    const step = event.currentTarget.dataset.step;
    if (step === 'next' || step === 'previous') this.showSources(this.data.sourcePage + (step === 'next' ? 1 : -1));
  },
  selectMember(event) {
    const actor = this.activeActor(), target = id(event.currentTarget.dataset.user);
    if (!actor || this.data.saving || !this.records.some(r => id(r.userId) === target)) return;
    this.selectedUser = target; this.setData({error:'', notice:''}); this.showRows(actor);
  },
  selectVersion(event) {
    const index=Number(event.detail.value);
    if (this.activeActor() && !this.data.saving && this.versionRows[index]) this.showVersion(index);
  },
  month(event) {
    if (!this.activeActor() || this.data.saving || !P.validPeriod(event.detail.value)) return;
    this.command = null; this.selectedUser = '';
    this.setData({period:event.detail.value}); return this.refresh();
  },
  inputNote(event) { if (this.activeActor()) this.setData({note: event.detail.value, error:''}); },
  submit() { return this.write('submit'); },
  review(event) {
    const action = event.currentTarget.dataset.action;
    if (action === 'approve' || action === 'return') return this.write(action);
  },
  async write(action) {
    const actor = this.activeActor();
    if (!actor || this.data.saving || this.data.loading || this.data.versionIndex !== 0) return;
    if (action === 'submit' ? !this.data.canSubmit : !this.data.canReview) return;
    const row = this.records.find(r => id(r.userId) === this.selectedUser);
    if (!row) return;
    const note = this.data.note.trim();
    if (!note && action !== 'approve') { this.setData({error:action === 'submit' ? '请填写提交说明' : '请填写退回原因'}); return; }
    const user = id(row.userId), revision = row.revision, period = this.data.period;
    const fingerprint = JSON.stringify([actor.key, period, user, revision, action, note]);
    if (this.command?.fingerprint !== fingerprint) {
      this.command = {fingerprint, key: 'perf-cycle-' + Date.now() + '-' + Math.random().toString(36).slice(2)};
    }
    const key = this.command.key, generation = this.generation;
    this.setData({saving:true, error:'', notice:''});
    try {
      const result = action === 'submit' ? await P.api.cycles.submit(period,user,note,key,revision)
        : await P.api.cycles.review(period,user,action === 'approve',note,key,revision);
      if (!this.guard(actor, generation)) return;
      if (!validRow(result,actor,period) || id(result.userId) !== user) {
        await this.refresh('操作结果待核，请查看最新周期'); return;
      }
      this.command = null;
      this.records = this.records.map(r => id(r.userId) === user ? result : r);
      this.showRows(actor);
    } catch (error) {
      if (!this.guard(actor, generation)) return;
      const code = Number(error?.code);
      if (code === 409 || [401,403,1103000002,1116000000].includes(code)) {
        this.command = null;
        await this.refresh(code === 409 ? '周期版本已更新，请按最新记录重新确认' : '当前权限已变化，请以重新读取的记录为准');
      } else this.setData({error: P.errorText(error)});
    } finally {
      if (this.guard(actor, generation)) this.setData({saving:false});
    }
  },
  openSource(event) {
    const actor = this.activeActor(), generation = this.generation;
    if (!actor) return;
    const row = this.data.snapshot?.sources[Number(event.currentTarget.dataset.index)];
    if (row?.url) wx.navigateTo({url: row.url, fail: () => {
      if (this.guard(actor, generation)) this.setData({error:'原单暂无法打开，请核对当前岗位权限'});
    }});
  }
});
