const api = require('../../../api/announcements');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('./scope');
const navigation = require('../../../utils/page-navigation');
const model = require('./model');
const route = '/pages/account/announcements/index';
const pageSize = 20;
const maxApiPageSize = 100;
const listChangedError = Object.freeze({ announcementPagingChanged: true });
const empty = () => ({ items: [], pageNo: 0, total: 0, hasMore: false, loading: false, loadingMore: false, error: '', refreshNotice: '' });

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
    const activeScope = scope();
    if (more && activeScope === this._scope && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return;
    const request = this._request = { scope: activeScope, keyword: this.data.keyword };
    if (request.scope !== this._scope) {
      more = false;
      request.keyword = '';
      this.setData({ searchText: '', keyword: '' });
    }
    this._scope = request.scope;
    const pageNo = more ? this.data.pageNo + 1 : 1;
    const previous = more ? this.data.items : [];
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
      // Offset pages shift when an announcement is withdrawn, published or pinned.
      // Replace the visible prefix from the server instead of appending a shifted suffix.
      const limit = pageNo * pageSize;
      const batchSize = Math.min(limit, maxApiPageSize);
      const rows = new Map();
      let total;
      for (let batch = 1; (batch - 1) * batchSize < Math.min(limit, total ?? limit); batch += 1) {
        const response = await api.page(batch, batchSize, request.keyword);
        if (!this.current(request)) return;
        const result = model.page(response);
        const offset = (batch - 1) * batchSize;
        if ((total !== undefined && total !== result.total)
            || result.rows.length !== Math.min(batchSize, Math.max(0, result.total - offset))) {
          throw listChangedError;
        }
        total = result.total;
        for (const item of result.rows) {
          if (rows.has(item.id)) throw listChangedError;
          rows.set(item.id, item);
        }
      }
      const items = [...rows.values()].slice(0, limit);
      const changed = previous.some((item, index) => item.id !== items[index]?.id);
      this.setData({ items, pageNo, total, loading: false, loadingMore: false, hasMore: items.length < total, error: '',
        refreshNotice: changed ? '公告列表已更新' : '' });
    } catch (error) {
      if (!this.current(request)) return;
      this.setData({ ...(model.denied(error) ? empty() : {}), loading: false, loadingMore: false, error: error === listChangedError ? '公告列表刚有变化，请重新载入' : model.errorText(error) });
    }
  },
  input(e) { this.setData({ searchText: String(e.detail.value || '').slice(0, 80) }); },
  search() { this.setData({ keyword: this.data.searchText.trim() }); return this.load(); },
  clearSearch() { this.setData({ searchText: '', keyword: '' }); return this.load(); },
  retry() { return this.load(this.data.items.length > 0); },
  loadMore() { return this.load(true); },
  onReachBottom() { if (!this.data.error) return this.loadMore(); },
  openAnnouncement(e) {
    if (this._scope !== scope()) return this.load();
    const id = model.id(e.currentTarget.dataset.id);
    if (this.visible && this.data.items.some(item => item.id === id)) navigation.open(route.replace('/index', '/detail') + '?id=' + encodeURIComponent(id));
  },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); }
});
