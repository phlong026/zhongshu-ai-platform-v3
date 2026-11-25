const api = require('../../../api/announcements');
const http = require('../../../api/http');
const auth = require('../../../api/auth');
const scope = require('./scope');
const navigation = require('../../../utils/page-navigation');
const model = require('./model');
const route = '/pages/account/announcements/detail';

Page({
  data: { announcement: null, loading: false, error: '' },
  onLoad(options = {}) { this.id = model.id(options.id); },
  onShow() { this.visible = true; return this.load(); },
  onHide() { this.visible = false; this._request = null; this.setData({ announcement: null, loading: false, error: '' }); },
  onUnload() { this.onHide(); },
  current(request) {
    if (!this.visible || this._request !== request) return false;
    if (request.scope === scope()) return true;
    this._request = null;
    this.setData({ announcement: null, loading: false, error: http.token() ? '工作身份已变化，请重新载入' : '登录已过期，请重新登录' });
    return false;
  },
  async load() {
    if (!this.visible) return;
    const request = this._request = { scope: scope() };
    this.setData({ announcement: null, loading: true, error: '' });
    if (!http.token()) {
      this.setData({ loading: false });
      return wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(route + '?id=' + encodeURIComponent(this.id || '')) });
    }
    const post = auth.currentApiPost();
    if (!post || !http.currentPostId() || String(post.postId) !== String(http.currentPostId())) {
      this.setData({ loading: false, error: '请先选择有效工作身份' });
      return;
    }
    if (!this.id) { this.setData({ loading: false, error: '公告链接无效，请返回公告列表' }); return; }
    try {
      const response = await api.get(this.id);
      if (!this.current(request)) return;
      const announcement = model.row(response, true);
      if (announcement.id !== this.id) throw Error('公告响应不匹配');
      this.setData({ announcement, loading: false, error: '' });
    } catch (error) {
      if (this.current(request)) this.setData({ announcement: null, loading: false, error: model.errorText(error, true) });
    }
  },
  chooseIdentity() { navigation.open('/pages/account/index?view=' + (http.token() ? 'identity' : 'login')); },
  backToList() { navigation.open('/pages/account/announcements/index', { replace: true }); }
});
