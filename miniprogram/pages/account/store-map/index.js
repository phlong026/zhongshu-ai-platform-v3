const http = require('../../../api/http');
const auth = require('../../../api/auth');
const { axes, readSnapshot, readRegions, mapView, searchRegions, hasPosition } = require('./model');
const route = '/pages/account/store-map/index';
const initial = () => ({markers:[],callouts:[],rows:[],total:0,pinned:false,brands:[],brandCounts:[],pickers:[],
  selectedBrand:'',brandIndex:0,admin:false,brandName:'',error:'',loading:true,
  query:'',keyword:'',suggestions:[],searchOpen:false,mode:'region',viewportError:'',viewportLoading:false,
  overview:{franchiseCount:0,coverageRecordCount:0,districtCount:0},summary:null,expandedId:'',focusNote:'',
  latitude:35.9,longitude:104.2,scale:4});

Page({
  data:initial(),
  onShow() { this.visible = true; return this.load(); },
  onHide() {
    this.visible = false; this.generation = (this.generation || 0) + 1;
    this.snapshot = null; this.catalog = null; this.bounds = null; this.filters = {};
    this.list = []; this.visibleStores = []; this.markerStores = []; this.mapPoints = []; this.fitPending = null; this.fitInFlight = false;
    this.setData(initial());
  },
  onUnload() { this.onHide(); },
  async load() {
    const generation = this.generation = (this.generation || 0) + 1, alive = http.sessionGuard();
    this.snapshot = null; this.catalog = null; this.bounds = null; this.filters = {}; this.fitPending = null; this.fitInFlight = false;
    this.setData(initial());
    if (!http.token()) return wx.redirectTo({url:'/pages/account/index?view=login&return=' + encodeURIComponent(route)});
    const post = auth.currentApiPost();
    try {
      if (!post || String(post.postId) !== String(http.currentPostId())) throw Error('请先选择工作身份');
      const [response, regions] = await Promise.all([http.get('/franchise/store-map'), http.get('/franchise/store-map/regions')]);
      if (!alive() || generation !== this.generation) return;
      this.snapshot = readSnapshot(response, post);
      this.catalog = readRegions(regions);
      this.setData({loading:false,admin:this.snapshot.admin,brandName:this.snapshot.brandName});
      this.renderMap(true);
    } catch (err) {
      if (alive() && generation === this.generation) this.setData({loading:false,
        error:Number(err.code) === 404 ? '地图或行政区目录尚未开放，请稍后重试' : err.message || '门店地图加载失败'});
    }
  },
  renderMap(fit = false) {
    if (!this.snapshot || !this.catalog) return;
    const view = mapView(this.snapshot,this.filters,this.catalog,this.data.mode,this.bounds);
    this.mapPoints = view.points;
    this.visibleStores = view.rows;
    this.markerStores = view.markers.map(marker => marker.storeIds);
    const callouts = view.markers.map(({id,heading,brands,selected}) => ({id,heading,brands,selected}));
    const markers = view.markers.map(({storeIds,heading,brands,selected,...marker}) => marker);
    const patch = {markers,callouts,pickers:view.pickers,brands:view.brands,brandCounts:view.brandCounts,
      overview:view.overview,summary:view.summary,selectedBrand:this.filters.brand || '',
      keyword:this.filters.keyword || '',brandIndex:Math.max(0,view.brands.findIndex(brand => brand.id === this.filters.brand)),
      expandedId:this.filters.record || ''};
    if (fit) {
      const points = view.fitPoints;
      const target = this.catalog.byCode.get(this.filters.district || this.filters.city || this.filters.province);
      Object.assign(patch,{latitude:view.center.latitude,longitude:view.center.longitude,
        scale:target?.level === 'district' ? 10 : target?.level === 'city' ? 9 : markers.length ? 7 : 4});
      this.fitPending = points.length > 1 ? points : null;
      this.fitInFlight = false;
    }
    this.setData(patch,() => { if (fit) this.fitMarkers(); });
    this.showStores(null);
  },
  mapContext() { return typeof wx.createMapContext === 'function' ? wx.createMapContext('stores',this) : null; },
  fitMarkers() {
    const points = this.fitPending, context = this.mapContext();
    if (!points || this.fitInFlight || !context?.includePoints) return;
    const generation = this.generation;
    this.fitInFlight = true;
    context.includePoints({points,padding:[80,40,60,40],
      success:() => { if (generation === this.generation && this.fitPending === points) { this.fitPending = null; this.fitInFlight = false; } },
      fail:() => { if (generation === this.generation && this.fitPending === points) this.fitInFlight = false; }
    });
  },
  changeRegion(e) {
    if (!this.snapshot || !this.catalog) return;
    const index = Number(e.currentTarget.dataset.index), picker = this.data.pickers[index];
    if (!picker || picker.disabled) return;
    const choice = picker.choices[Number(e.detail.value)];
    if (!choice) return;
    this.filters[picker.key] = choice.id; delete this.filters.record;
    axes.slice(index + 1).forEach(([key]) => delete this.filters[key]);
    if (picker.key === 'district' && choice.id) this.applyRegion(choice.id);
    this.viewportRequest = (this.viewportRequest || 0) + 1;
    this.setData({mode:'region',viewportLoading:false,viewportError:'',suggestions:[],searchOpen:false,focusNote:''});
    this.renderMap(true);
  },
  applyRegion(code) {
    const region = this.catalog?.byCode.get(String(code));
    if (!region) return;
    this.filters = {...this.filters,province:region.provinceCode || (region.level === 'province' ? region.code : ''),
      city:region.level === 'province' ? '' : region.cityCode || '',district:region.level === 'district' ? region.code : ''};
    if (region.level === 'city') this.filters.city = region.code;
  },
  changeBrand(e) {
    if (!this.snapshot?.admin) return;
    const id = e.currentTarget.dataset.id ?? this.data.brands[Number(e.detail.value)]?.id;
    if (id === undefined || !this.data.brands.some(brand => brand.id === id)) return;
    this.filters.brand = id; delete this.filters.record;
    this.setData({focusNote:''});
    this.renderMap(this.data.mode === 'region');
  },
  searchInput(e) {
    const query = String(e.detail.value || '').slice(0,80);
    this.setData({query,searchOpen:!!query.trim(),suggestions:this.catalog ? searchRegions(this.catalog,query) : []});
    if (!query.trim() && this.filters?.keyword) { delete this.filters.keyword; this.renderMap(false); }
  },
  chooseSearchRegion(e) {
    if (!this.catalog) return;
    const region = this.catalog.byCode.get(String(e.currentTarget.dataset.code));
    if (!region) return;
    this.applyRegion(region.code); delete this.filters.keyword; delete this.filters.record;
    this.viewportRequest = (this.viewportRequest || 0) + 1;
    this.setData({query:'',suggestions:[],searchOpen:false,mode:'region',viewportLoading:false,viewportError:'',focusNote:''});
    this.renderMap(true);
  },
  confirmSearch() {
    if (!this.snapshot || !this.catalog) return;
    const query = this.data.query.trim(), suggestions = searchRegions(this.catalog,query);
    const exact = suggestions.filter(row => row.name === query);
    if (exact.length === 1 || suggestions.length === 1) {
      return this.chooseSearchRegion({currentTarget:{dataset:{code:(exact[0] || suggestions[0]).code}}});
    }
    if (suggestions.length) return this.setData({searchOpen:true,suggestions});
    this.searchName();
  },
  searchName() {
    if (!this.snapshot) return;
    this.filters.keyword = this.data.query.trim(); delete this.filters.record;
    this.setData({suggestions:[],searchOpen:false,focusNote:''});
    this.renderMap(true);
  },
  clearSearch() {
    if (this.filters) { delete this.filters.keyword; delete this.filters.record; }
    this.setData({query:'',keyword:'',suggestions:[],searchOpen:false,focusNote:''});
    this.renderMap(false);
  },
  changeMode(e) {
    const mode = e.currentTarget.dataset.mode;
    if (!this.snapshot || !['region','viewport'].includes(mode)) return;
    this.viewportRequest = (this.viewportRequest || 0) + 1;
    this.setData({mode,viewportError:'',viewportLoading:false});
    this.renderMap(false);
    if (mode === 'viewport') this.refreshViewport();
  },
  onRegionChange(e) {
    const type = e.detail?.type || e.type, cause = e.detail?.causedBy || e.causedBy;
    if (type === 'begin' && cause === 'gesture') { this.fitPending = null; this.fitInFlight = false; }
    if (type === 'end') this.refreshViewport();
  },
  refreshViewport() {
    if (!this.snapshot) return;
    const context = this.mapContext(), generation = this.generation, request = this.viewportRequest = (this.viewportRequest || 0) + 1;
    if (!context?.getRegion) {
      if (this.data.mode === 'viewport') this.setData({viewportLoading:false,viewportError:'当前环境无法读取地图范围，请使用行政区域统计'});
      return;
    }
    const alive = http.sessionGuard();
    if (this.data.mode === 'viewport') this.setData({viewportLoading:true,viewportError:''});
    context.getRegion({
      success:bounds => {
        if (!alive() || generation !== this.generation || request !== this.viewportRequest) return;
        if (!hasPosition(bounds.southwest || {}) || !hasPosition(bounds.northeast || {})) {
          this.setData({viewportLoading:false,viewportError:'地图范围未能读取，请重试'}); return;
        }
        const previous = JSON.stringify(this.bounds);
        this.bounds = {southwest:bounds.southwest,northeast:bounds.northeast};
        this.setData({viewportLoading:false,viewportError:''});
        if (this.data.mode === 'viewport' && (previous !== JSON.stringify(this.bounds) || this.data.summary?.status === 'loading')) this.renderMap(false);
      },
      fail:() => {
        if (alive() && generation === this.generation && request === this.viewportRequest) {
          this.setData({viewportLoading:false,viewportError:'地图范围未能读取，请重试'});
        }
      }
    });
  },
  zoom(e) {
    const delta = Number(e.currentTarget.dataset.delta);
    if (!this.snapshot || ![-1,1].includes(delta)) return;
    const generation = this.generation, context = this.mapContext();
    const update = scale => { if (generation === this.generation) this.setData({scale:Math.min(18,Math.max(3,Number(scale) + delta))}); };
    if (context?.getScale) context.getScale({success:r => update(r.scale)});
    else update(this.data.scale);
  },
  selectMarker(e) {
    const ids = this.markerStores?.[Number(e.detail?.markerId ?? e.currentTarget?.dataset.markerId) - 1];
    if (this.snapshot && ids) this.showStores(new Set(ids));
  },
  showAll() { if (this.snapshot) this.showStores(null); },
  showStores(ids) {
    this.list = ids ? this.visibleStores.filter(row => ids.has(row.id)) : this.visibleStores;
    this.setData({rows:this.list.slice(0,30),total:this.list.length,pinned:!!ids,expandedId:this.filters.record || ''});
  },
  more() { if (this.snapshot) this.setData({rows:this.list.slice(0,this.data.rows.length + 30)}); },
  toggleRecord(e) {
    const id = String(e.currentTarget.dataset.id);
    const record = this.list?.find(row => row.id === id);
    if (!record) return;
    if (this.filters.record === id) {
      delete this.filters.record; this.setData({focusNote:''}); return this.renderMap(false);
    }
    this.filters.record = id;
    const points = [...new Map(this.mapPoints.filter(point => point.recordId === id && hasPosition(point)
      && this.catalog.byCode.get(point.regionCode)?.level === 'district').map(point => [point.regionCode,point])).values()];
    this.renderMap(false);
    if (!points.length) return this.setData({focusNote:'这条资料的区县定位待确认，暂未生成地图点位'});
    this.fitPending = points.length > 1 ? points.map(({latitude,longitude}) => ({latitude,longitude})) : null;
    this.fitInFlight = false;
    this.setData({latitude:points[0].latitude,longitude:points[0].longitude,scale:10,
      focusNote:points.length > 1 ? '已定位该记录的 ' + points.length + ' 个覆盖区县（区县中心）' : '已定位至' + this.catalog.byCode.get(points[0].regionCode).name + '中心'},() => this.fitMarkers());
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({selector:'#stores',duration:250});
  }
});
