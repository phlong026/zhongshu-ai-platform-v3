const http = require('../../../api/http');
const auth = require('../../../api/auth');
const { axes, readSnapshot, mapView } = require('./model');
const route = '/pages/account/store-map/index';
const initial = () => ({ markers: [], rows: [], total: 0, pinned: false, brands: [], pickers: [],
  selectedBrand: '', admin: false, brandName: '', includePoints: [], error: '', loading: true });

Page({
  data: initial(),
  onShow() { this.load(); },
  onHide() { this.generation++; this.snapshot = null; this.list = []; this.visibleStores = []; this.markerStores = []; this.fitPending = false; this.setData(initial()); },
  onUnload() { this.generation++; },
  async load() {
    const generation = this.generation = (this.generation || 0) + 1;
    const alive = http.sessionGuard();
    this.snapshot = null;
    this.setData(initial());
    if (!http.token()) {
      wx.redirectTo({ url: '/pages/account/index?view=login&return=' + encodeURIComponent(route) });
      return;
    }
    const post = auth.currentApiPost();
    try {
      if (!post || String(post.postId) !== String(http.currentPostId())) throw Error('请先选择工作身份');
      const response = await http.get('/franchise/store-map');
      if (!alive() || generation !== this.generation) return;
      this.snapshot = readSnapshot(response, post);
      this.filters = {};
      this.setData({ loading: false, admin: this.snapshot.admin, brandName: response.brandName || '' });
      this.renderMap();
    } catch (err) {
      if (alive() && generation === this.generation) this.setData({ loading: false,
        error: Number(err.code) === 404 ? '门店地图服务暂未开放' : err.message || '门店地图加载失败' });
    }
  },
  renderMap() {
    const view = mapView(this.snapshot, this.filters);
    this.visibleStores = view.rows;
    this.markerStores = view.markers.map(marker => marker.storeIds);
    const markers = view.markers.map(({ storeIds, ...marker }) => marker);
    this.fitPending = markers.length > 1;
    this.setData({ markers, pickers: view.pickers, brands: view.brands,
      selectedBrand: this.filters.brand || '', latitude: view.center.latitude, longitude: view.center.longitude,
      scale: markers.length ? 11 : 4, includePoints: markers.length > 1 ?
        markers.map(({ latitude, longitude }) => ({ latitude, longitude })) : [] });
    this.showStores(null);
  },
  fitMarkers() {
    if (!this.fitPending) return;
    this.fitPending = false;
    wx.createMapContext('stores', this).includePoints({ points: this.data.includePoints, padding: [40, 48, 30, 48] });
  },
  changeRegion(e) {
    if (!this.snapshot) return;
    const index = Number(e.currentTarget.dataset.index), picker = this.data.pickers[index];
    if (!picker || picker.disabled) return;
    const choice = picker.choices[Number(e.detail.value)];
    if (!choice) return;
    this.filters[picker.key] = choice.id;
    axes.slice(index + 1).forEach(([key]) => delete this.filters[key]);
    this.renderMap();
  },
  changeBrand(e) {
    if (!this.snapshot || !this.snapshot.admin) return;
    this.filters = { brand: e.currentTarget.dataset.id || '' };
    this.renderMap();
  },
  selectMarker(e) {
    const ids = this.markerStores && this.markerStores[Number(e.detail.markerId) - 1];
    if (this.snapshot && ids) this.showStores(new Set(ids));
  },
  showAll() { if (this.snapshot) this.showStores(null); },
  showStores(ids) {
    this.list = ids ? this.visibleStores.filter(row => ids.has(row.id)) : this.visibleStores;
    this.setData({ rows: this.list.slice(0, 30), total: this.list.length, pinned: !!ids });
  },
  more() { if (this.snapshot) this.setData({ rows: this.list.slice(0, this.data.rows.length + 30) }); }
});
