const PT = require('../../../utils/platform-todos-page');
const OwnerTodos = require('../../../utils/owner-todo-view');
const auth = require('../../../api/auth');
const http = require('../../../api/http');
const { postKeyOf } = require('../../../utils/platform-todos');
const ownerPost = () => {
  const post = http.token() && auth.currentApiPost();
  return post && post.orgType === 'store' && ['boss', 'owner'].includes(post.roleCode) ? post : null;
};

Page({
  data: { platformTodos: null, rows: [], sources: OwnerTodos.sources, source: 'all', decisionsOnly: false, allowed: false },
  onLoad(options = {}) {
    this.setData({ decisionsOnly: options.decisions === '1' });
    PT.init(this);
  },
  onShow() { this.hidden = false; this.refresh(false); },
  onHide() { this.hidden = true; this.__ptLoadGen = (this.__ptLoadGen || 0) + 1; },
  onUnload() { this.onHide(); },
  onPullDownRefresh() { Promise.resolve(this.refresh(true)).finally(() => wx.stopPullDownRefresh()); },
  refresh(manual = true) {
    const allowed = !!ownerPost();
    this.setData({ allowed });
    if (!allowed) {
      const generation = this.__ptLoadGen || 0;
      PT.init(this);
      this.__ptLoadGen = generation + 1;
      this.setData({ rows: [] });
      return;
    }
    if (this.data.platformTodos.refreshing || this.data.platformTodos.status === 'loading') manual = true;
    const request = PT.refresh(this, manual, OwnerTodos.load);
    this.applyPlatformTodoUpdate();
    return Promise.resolve(request).then(() => {
      if (!this.hidden) this.applyPlatformTodoUpdate();
    });
  },
  applyPlatformTodoUpdate() {
    const { platformTodos, source, decisionsOnly, allowed } = this.data;
    const post = ownerPost();
    const valid = allowed && post && platformTodos && platformTodos.postKey === postKeyOf(post) &&
      platformTodos.sessionGen === http.sessionGeneration();
    const rows = valid ? OwnerTodos.board(platformTodos.rows).rows : [];
    this.setData({ rows: OwnerTodos.filter(rows, source, decisionsOnly) });
  },
  selectSource(e) {
    const source = e.currentTarget.dataset.source;
    if (!OwnerTodos.sources.some(item => item.key === source)) return;
    this.setData({ source });
    this.applyPlatformTodoUpdate();
  },
  toggleDecisions(e) {
    this.setData({ decisionsOnly: e.detail.value === true });
    this.applyPlatformTodoUpdate();
  },
  open(e) {
    const selected = this.data.rows.find(item => item.key === e.currentTarget.dataset.key);
    const post = ownerPost(), state = this.data.platformTodos;
    if (selected && post && state?.postKey === postKeyOf(post) && state.sessionGen === http.sessionGeneration()) {
      PT.open(this, { detail: { item: selected } });
    }
  }
});
