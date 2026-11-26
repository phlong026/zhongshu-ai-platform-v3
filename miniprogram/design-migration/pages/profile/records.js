const navigation = require('../../utils/navigation');
const getDesignApp = require('../../utils/context').getApp;
'use strict';
const { protectedPage } = require('../../utils/access');
const api = require('../../utils/api');
const http = require('../../utils/request');
const view = require('../../utils/record-view');
const assets = require('../../utils/assets');

protectedPage({
  data: { title: '我的记录', rows: [], loading: false, error: '', hasMore: false, emptyText: '' },
  onLoad(options) {
    this._type = ['projects','favorites'].includes(options.type) ? options.type : null;
    if (!this._type) { this.setData({ error: '记录类型无效，请从「我的」重新进入' }); return; }
    this.setData({ title: options.from==='tasks'&&this._type==='projects'?'设计任务':view.titles[this._type],
      emptyText: ({ projects: options.from === 'tasks' ? '暂无设计任务' : '暂无方案', submissions: '还没有投稿，可从已完成的方案提交审核', favorites: '暂无收藏户型', orders: '还没有充值订单', points: '还没有点数变动，充值或生成后会记录在这里' })[this._type] });
  },
  onShow() { if (this._type) this.reload(); },
  onUnload() { this._seq = (this._seq || 0) + 1; this._token = null; },
  onReachBottom() { this.more(); },
  // 下拉刷新（UX 2026-10：对标同行，去掉页内刷新按钮）
  onPullDownRefresh() {
    Promise.resolve(this.reload()).catch(() => {}).then(() => wx.stopPullDownRefresh());
  },
  current(seq) {
    if (seq !== this._seq) return false;
    if (this._token && (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken())) return true;
    this.setData({ rows: [], loading: false, hasMore: false, error: '登录状态已更新，请重新进入' });
    return false;
  },
  reload() {
    this._seq = (this._seq || 0) + 1; this._token = http.captureSession ? http.captureSession() : http.getToken(); this._cursor = null; this._page = 1;
    this.setData({ rows: [], error: '', loading: false, hasMore: false });
    return this.load();
  },
  load() {
    if (!this._type || this.data.loading) return;
    if (!this.current(this._seq)) { this.setData({ rows: [], hasMore: false, error: '登录状态已更新，请重新进入' }); return; }
    const seq = this._seq, cursor = this._cursor, pageNo = this._page, type = this._type;
    this.setData({ loading: true, error: '' });
    const request = type === 'orders' ? api.listRechargeOrders(pageNo, 20)
      : type === 'points' ? api.listPointLedger(pageNo, 20)
      : ({ projects: api.listProjects, submissions: api.listSubmissions, favorites: api.listFavorites })[type](cursor, 20);
    return request.then(response => {
      if (!this.current(seq)) return;
      if (!response || !Array.isArray(response.list)) throw Error('记录数据不完整，请重试');
      if (type !== 'orders' && response.nextCursor != null && (!view.id(response.nextCursor) || response.nextCursor === cursor)) throw Error('分页数据异常，请刷新');
      if ((type === 'orders' || type === 'points') && (!Number.isSafeInteger(response.total) || response.total < 0)) throw Error('分页数据异常，请重试');
      const incoming = response.list.map(item => view.row(type, item));
      const seen = new Set(this.data.rows.map(item => item.id));
      const rows = this.data.rows.concat(incoming.filter(item => { if (seen.has(item.id)) return false; seen.add(item.id); return true; }));
      const more = (type === 'orders' || type === 'points') ? pageNo * 20 < response.total : !!response.nextCursor;
      if (more && rows.length === this.data.rows.length) throw Error('分页未返回新的记录，请刷新');
      this._cursor = response.nextCursor || null; this._page = pageNo + 1;
      this.setData({ rows, hasMore: more, loading: false });
      if (type === 'projects') this.loadCovers(rows);
    }).catch(error => {
      if (!this.current(seq)) return;
      const code = String(error && error.code);
      const message = code === '401' ? '请重新登录' : code === '403' ? '当前岗位没有查看权限' : `${this.data.title}暂时无法加载`;
      this.setData({ loading: false, error: message });
    });
  },
  // 封面缩略图：逐行经下载票据换 data URL；行序以 id 定位，避免并发回写错位
  loadCovers(rows) {
    const seq = this._seq;
    rows.forEach((row, index) => {
      if (!row.coverAssetId || row.coverUrl) return;
      assets.fetchAssetDataUrl(row.coverAssetId).then(url => {
        if (seq !== this._seq) return;
        const target = this.data.rows.find(item => item.id === row.id);
        if (target && target.coverAssetId === row.coverAssetId && !target.coverUrl) {
          this.setData({ rows: this.data.rows.map(item => item === target ? Object.assign({}, item, { coverUrl: url }) : item) });
        }
      }).catch(function () { /* 封面失败不阻断列表，行保持无图布局 */ });
    });
  },
  more() { if (this.data.hasMore) return this.load(); },
  retry() { return (http.isSameSession ? http.isSameSession(this._token) : this._token === http.getToken()) ? this.load() : this.reload(); },
  open(event) {
    const id = event.currentTarget.dataset.id;
    if (!this.current(this._seq)) { this.setData({ rows: [], hasMore: false, error: '登录状态已更新，请重新进入' }); return; }
    const row = this.data.rows.find(item => item.id === id);
    if (row && row.url) wx.navigateTo({ url: row.url, fail: () => wx.showToast({ title: '打开失败，请重试', icon: 'none' }) });
  },
  // 封面缩略图点击放大（catchtap 阻断卡片打开）
  previewCover(event) {
    const url = event.currentTarget.dataset.url;
    if (url) assets.previewImages([url], url);
  }
});
