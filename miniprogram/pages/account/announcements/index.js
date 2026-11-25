const api = require('../../../api/announcements');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('./scope');
const navigation = require('../../../utils/page-navigation');
const model = require('./model');
const route = '/pages/account/announcements/index';
const pageSize = 20;
const empty = () => ({ items: [], pageNo: 0, total: 0, hasMore: false, loading: false, loadingMore: false, error: '' });

Page({
  data: { ...empty(), searchText: '', keyword: '' },
  onShow() {
    this.visible = true;
    if (this._scope !== scope()) this.setData({ searchText: '', keyword: '' });
    return this.load();
  },
  onHide() { this.visible = false; this._request = null; this.setData(empty()); },
  onUnload() { this.onHide(); },
  current(request) {
    if (!this.visible || this._request !== request) return false;
    if (request.scope === scope()) return true;
    this._request = null;
    this.setData({ ...empty(), error: http.token() ? '工作身份已变化，请重新载入' : '登录已过期，请重新登录' });
    return false;
  },
  async load(more = false) {
    if (!this.visible) return;
    if (more && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return;
    const request = this._request = { scope: scope() };
    if (more && request.scope !== this._scope) more = false;
    this._scope = request.scope;
    const pageNo = more ? this.data.pageNo + 1 : 1;
    this.setData(more ? { loadingMore: true, error: '' } : { ...empty(), loading: true });
    if (!http.token()) {
      this.setData({ loading: false, loadingMore: false });
      return wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(route) });
    }
    const post = auth.currentApiPost();
    if (!post || !http.currentPostId() || String(post.postId) !== String(http.currentPostId())) {
      this.setData({ ...empty(), error: '请先选择有效工作身份' });
      return;
    }
    try {
      const response = await api.page(pageNo, pageSize, this.data.keyword);
      if (!this.current(request)) return;
      const result = model.page(response);
      const rows = new Map((more ? this.data.items : []).map(item => [item.id, item]));
      result.rows.forEach(item => rows.set(item.id, item));
      this.setData({ items: [...rows.values()], pageNo, total: result.total, loading: false, loadingMore: false,
        hasMore: result.rows.length > 0 && pageNo * pageSize < result.total, error: '' });
    } catch (error) {
      if (!this.current(request)) return;
      this.setData({ ...(model.denied(error) ? empty() : {}), loading: false, loadingMore: false, error: model.errorText(error) });
    }
  },
  input(e) { this.setData({ searchText: String(e.detail.value || '').slice(0, 80) }); },
  search() { this.setData({ keyword: this.data.searchText.trim() }); return this.load(); },
  clearSearch() { this.setData({ searchText: '', keyword: '' }); return this.load(); },
  retry() { return this.load(this.data.items.length > 0); },
  loadMore() { return this.load(true); },
  onReachBottom() { return this.loadMore(); },
  openAnnouncement(e) {
    if (this._scope !== scope()) return this.load();
    const id = model.id(e.currentTarget.dataset.id);
    if (this.visible && this.data.items.some(item => item.id === id)) navigation.open(route.replace('/index', '/detail') + '?id=' + encodeURIComponent(id));
  },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); }
});
