const test = require('node:test');
const assert = require('node:assert/strict');
const { readSnapshot, readRegions, mapView, searchRegions } = require('../miniprogram/pages/account/store-map/model');

// Synthetic records only. District coordinates are the checked-in pf_map_region centers.
const regions = [
  { code:'420000', name:'湖北省', level:'province', parentCode:'', provinceCode:'420000', provinceName:'湖北省' },
  { code:'420100', name:'武汉市', level:'city', parentCode:'420000', provinceCode:'420000', provinceName:'湖北省', cityCode:'420100', cityName:'武汉市' },
  { code:'420115', name:'江夏区', level:'district', parentCode:'420100', provinceCode:'420000', provinceName:'湖北省', cityCode:'420100', cityName:'武汉市', districtCode:'420115', districtName:'江夏区', longitude:114.313961, latitude:30.349045 },
  { code:'420116', name:'黄陂区', level:'district', parentCode:'420100', provinceCode:'420000', provinceName:'湖北省', cityCode:'420100', cityName:'武汉市', districtCode:'420116', districtName:'黄陂区', longitude:114.374025, latitude:30.874155 },
  { code:'420117', name:'新洲区', level:'district', parentCode:'420100', provinceCode:'420000', provinceName:'湖北省', cityCode:'420100', cityName:'武汉市', districtCode:'420117', districtName:'新洲区', longitude:114.802108, latitude:30.842149 },
  { code:'110000', name:'北京市', level:'province', parentCode:'', provinceCode:'110000', provinceName:'北京市' },
  { code:'110101', name:'东城区', level:'district', parentCode:'110000', provinceCode:'110000', provinceName:'北京市', cityCode:'', cityName:'', districtCode:'110101', districtName:'东城区', longitude:116.418757, latitude:39.917544 }
];
const brands = [{id:'a',name:'测试甲',color:'#965528'},{id:'b',name:'测试乙',color:'#197262'},{id:'c',name:'测试丙',color:'#4467A0'}];
const record = (id,brandId='a',codes=['420115'],type='FRANCHISE') => ({ id, name:'验收记录'+id, brandId, brandName:brands.find(b=>b.id===brandId).name, brandColor:brands.find(b=>b.id===brandId).color, recordType:type, originalRegionText:codes.join('、'), matchStatus:'RESOLVED', regionCodes:codes, provinceCode:'420000', cityCode:'420100', linkedOrgId:'' });
const fixture = (extra=[]) => {
  const records=[record('one','a',['420115','420116']),record('two','b'),record('coverage','b',['420115'],'COVERAGE'),...extra];
  return {schemaVersion:2,scope:'all',brandId:'',brandName:'',brands,records,points:records.flatMap(r=>r.regionCodes.map(code=>{
    const region=regions.find(n=>n.code===code);
    return region?.level==='district'?{id:r.id+':'+code,recordId:r.id,regionCode:code,longitude:region.longitude,latitude:region.latitude,locationPrecision:'district'}:null;
  }).filter(Boolean))};
};
const hq={orgType:'headquarters',roleCode:'finance'};
const county={province:'420000',city:'420100',district:'420115'};

test('所有总部有效岗位接收全品牌快照；加盟商拒绝跨品牌记录、点位和品牌目录',()=>{
  const source=fixture();
  assert.equal(readSnapshot(source,hq).admin,true);
  assert.throws(()=>readSnapshot(source,{orgType:'store',brandId:'a'}));
  const own={...source,scope:'brand',brandId:'a',brandName:'测试甲',brands:[brands[0]],records:[source.records[0]],points:source.points.filter(p=>p.recordId==='one')};
  assert.equal(readSnapshot(own,{orgType:'store',brandId:'a'}).records.length,1);
  assert.throws(()=>readSnapshot({...own,records:source.records},{orgType:'store',brandId:'a'}));
  assert.throws(()=>readSnapshot({...own,brands},{orgType:'store',brandId:'a'}));
  assert.throws(()=>readSnapshot({...own,points:source.points},{orgType:'store',brandId:'a'}));
});

test('多县同加盟商只算一家，覆盖记录只算条，覆盖县按行政区去重',()=>{
  const snapshot=readSnapshot(fixture(),hq),catalog=readRegions(regions);
  const view=mapView(snapshot,{},catalog);
  assert.deepEqual(view.overview,{franchiseCount:2,coverageRecordCount:1,districtCount:2});
  assert.equal(view.summary.franchiseCount,2);
  assert.equal(view.summary.coverageRecordCount,1);
  assert.equal(view.summary.districtCount,2);
  assert.equal(view.rows.length,3);
});

test('同县所有品牌和覆盖记录保留、同坐标合并不移动中心，零家品牌仍可见',()=>{
  const view=mapView(readSnapshot(fixture(),hq),county,readRegions(regions));
  assert.equal(view.summary.franchiseCount,2);
  assert.equal(view.summary.coverageRecordCount,1);
  assert.equal(view.summary.brandCount,2);
  assert.equal(view.markers.length,1);
  assert.equal(view.markers[0].longitude,114.313961);
  assert.equal(view.markers[0].latitude,30.349045);
  assert.deepEqual(new Set(view.markers[0].storeIds),new Set(['one','two','coverage']));
  assert.equal(view.brandCounts.find(b=>b.id==='c').franchiseCount,0);
  assert.equal(new Set(view.rows.map(r=>r.color)).size,2);
  assert.equal(view.markers[0].customCallout.display,'ALWAYS');
  assert.deepEqual(view.markers[0].brands.map(({id,color,franchiseCount,coverageRecordCount})=>({id,color,franchiseCount,coverageRecordCount})),[
    {id:'a',color:brands[0].color,franchiseCount:1,coverageRecordCount:0},
    {id:'b',color:brands[1].color,franchiseCount:1,coverageRecordCount:1}
  ]);
});

test('完整目录让零记录县可选且状态只说暂无已登记；名称筛选不改区域存在结论',()=>{
  const snapshot=readSnapshot(fixture(),hq),catalog=readRegions(regions);
  const empty=mapView(snapshot,{...county,district:'420117'},catalog);
  assert(empty.pickers[2].choices.some(c=>c.id==='420117'));
  assert.equal(empty.summary.status,'empty');
  assert.equal(empty.summary.statusText,'暂无已登记加盟商');
  const search=mapView(snapshot,{...county,keyword:'不存在的验收名称'},catalog);
  assert.equal(search.summary.status,'registered');
  assert.equal(search.summary.franchiseCount,2);
  assert.equal(search.rows.length,0);
});

test('明确上级但县待确认的记录不能变成假县落点或空白招商结论',()=>{
  const uncertain={...record('uncertain','a',['420100']),matchStatus:'PARTIAL',originalRegionText:'武汉片区'};
  const view=mapView(readSnapshot(fixture([uncertain]),hq),{...county,district:'420117'},readRegions(regions));
  assert.equal(view.summary.status,'pending');
  assert.equal(view.summary.franchiseCount,0);
  assert.equal(view.summary.pendingCount,1);
  assert.equal(view.markers.length,0);
  assert.equal(view.rows[0].originalRegionText,'武汉片区');
  const direct=readRegions([...regions,{code:'429004',name:'仙桃市',level:'district',parentCode:'420000',provinceCode:'420000',provinceName:'湖北省',cityCode:'',districtCode:'429004',districtName:'仙桃市'}]);
  const outside=mapView(readSnapshot(fixture([uncertain]),hq),{province:'420000',district:'429004'},direct);
  assert.equal(outside.summary.status,'empty','明确武汉市的待确认资料不扩张到同省直辖县');
});

test('覆盖记录不能标成加盟商一家；省下直辖县可直接选择',()=>{
  const source=fixture();source.records=source.records.filter(r=>r.recordType==='COVERAGE');source.points=source.points.filter(p=>p.recordId==='coverage');
  const view=mapView(readSnapshot(source,hq),county,readRegions(regions));
  assert.equal(view.summary.statusText,'已有覆盖记录');
  assert.equal(view.summary.franchiseCount,0);
  const direct=mapView(readSnapshot(fixture(),hq),{province:'110000'},readRegions(regions));
  assert.equal(direct.pickers[1].disabled,true);
  assert.equal(direct.pickers[2].disabled,false);
  assert(direct.pickers[2].choices.some(n=>n.id==='110101'));
  assert.equal(searchRegions(readRegions(regions),'武汉江夏')[0].code,'420115');
});

test('行政区统计不随视野改变；视野统计按真实点去重并区分家与条',()=>{
  const snapshot=readSnapshot(fixture(),hq),catalog=readRegions(regions);
  const bounds={southwest:{longitude:114.36,latitude:30.86},northeast:{longitude:114.39,latitude:30.89}};
  const region=mapView(snapshot,county,catalog,'region',bounds);
  assert.equal(region.summary.franchiseCount,2);
  const viewport=mapView(snapshot,county,catalog,'viewport',bounds);
  assert.equal(viewport.summary.franchiseCount,1);
  assert.equal(viewport.summary.coverageRecordCount,0);
  assert.deepEqual(viewport.rows.map(r=>r.id),['one']);
  assert.equal(mapView(snapshot,county,catalog,'viewport').summary.status,'loading');
});

test('无效坐标不会制造落点，资料原文仍保留；V2不回落旧stores虚构数据',()=>{
  const source=fixture();source.points=source.points.map(p=>({...p,latitude:999}));source.stores=[{id:'not-used',brandId:'a'}];
  const view=mapView(readSnapshot(source,hq),county,readRegions(regions));
  assert.equal(view.markers.length,0);
  assert.equal(view.rows.length,3);
  assert(view.rows.every(r=>r.id!=='not-used'));
});

test('旧版精确地址坐标只按独立区划目录转换为县中心，没有可信县中心时不落点',()=>{
  const old={scope:'all',stores:[{id:'legacy',name:'旧版测试',brandId:'a',brandName:'测试甲',brandColor:brands[0].color,
    provinceCode:'420000',cityCode:'420100',districtCode:'420116',provinceName:'湖北省',cityName:'武汉市',districtName:'黄陂区',
    longitude:114.123456,latitude:30.123456,locationPrecision:'exact'}]};
  const snapshot=readSnapshot(old,hq),view=mapView(snapshot,{},readRegions(regions));
  assert.equal(view.markers[0].longitude,114.374025);assert.equal(view.markers[0].latitude,30.874155);
  assert.equal(view.points[0].locationPrecision,'district');
  assert.equal(mapView(snapshot,{}).markers.length,0);
});

test('省市无中心且零记录时，使用下属真实县中心定位视野，不生成门店点',()=>{
  const view=mapView(readSnapshot({...fixture(),records:[],points:[]},hq),{province:'420000',city:'420100'},readRegions(regions));
  assert.equal(view.markers.length,0);assert.equal(view.summary.status,'empty');
  assert.equal(view.fitPoints.length,3);
  assert.deepEqual(view.fitPoints[0],{longitude:114.313961,latitude:30.349045});
  assert.deepEqual(view.center,view.fitPoints[0]);
});

const flush=async()=>{for(let i=0;i<12;i++)await new Promise(resolve=>setImmediate(resolve));};
async function openMap({manual=false,source=fixture()}={}) {
  const {connect}=require('./role-page-runtime.cjs');
  const runtime=await connect(),auth=require('../miniprogram/api/auth');
  auth.saveLogin({accessToken:'map-test-session',userId:7});
  auth.savePosts([{postId:'hq-map',userId:7,orgId:'hq',orgType:'headquarters',roleCode:'finance'}]);
  auth.switchPost('hq-map');
  const network=[],viewports=[],fits=[];
  wx.request=options=>{
    const url=new URL(options.url).pathname.replace('/admin-api','');
    network.push({url,options});
    if(!manual)setImmediate(()=>options.success({statusCode:200,data:{code:0,data:url.endsWith('/regions')?regions:source}}));
  };
  wx.createMapContext=()=>({includePoints:options=>{fits.push(options);options.success?.();},getRegion:options=>viewports.push(options),getScale:options=>options.success({scale:9})});
  await runtime.callWxMethod('navigateTo',{url:'/pages/account/store-map/index'});await flush();
  const nativeMap=await (await runtime.currentPage()).$('map');if(nativeMap)await nativeMap.trigger('updated',{});
  return {runtime,auth,page:getCurrentPages().at(-1),network,viewports,fits};
}

test('真实Page地区优先搜索和品牌切换保留区县；setData不包含全国目录；图钉名单可恢复',async()=>{
  const {page,runtime}=await openMap();
  assert.equal(page.data.error,'');
  assert.equal(page.data.pickers[2].choices.length,1);
  page.searchInput({detail:{value:'武汉江夏'}});
  const screen=await runtime.currentPage();
  await (await screen.$('.search-result')).tap();
  assert.equal(page.filters.district,'420115');
  page.changeBrand({detail:{value:2},currentTarget:{dataset:{}}});
  assert.equal(page.filters.district,'420115');
  assert.equal(page.data.summary.franchiseCount,1);
  assert.equal(page.data.summary.coverageRecordCount,1);
  assert(!JSON.stringify(page.data).includes('东城区'));
  page.changeBrand({currentTarget:{dataset:{id:''}}});
  page.selectMarker({detail:{markerId:1}});
  assert.equal(page.data.pinned,true);assert.equal(page.data.total,3);
  page.showAll();assert.equal(page.data.pinned,false);
  page.searchInput({detail:{value:'验收记录two'}});page.searchName();
  assert.equal(page.data.rows.length,1);
  assert.equal(page.data.summary.franchiseCount,2,'名称搜索不能把区域真实登记数改小');
});

test('真实Page切视野后读取MapContext，乱序及隐藏后的旧范围不能覆盖当前结果',async()=>{
  const {page,viewports}=await openMap();
  page.changeMode({currentTarget:{dataset:{mode:'viewport'}}});
  assert.equal(page.data.viewportLoading,true);
  const north={southwest:{longitude:114.36,latitude:30.86},northeast:{longitude:114.39,latitude:30.89}};
  const south={southwest:{longitude:114.30,latitude:30.33},northeast:{longitude:114.33,latitude:30.37}};
  viewports[0].success(north);assert.equal(page.data.summary.franchiseCount,1);
  page.onRegionChange({type:'end'});page.onRegionChange({type:'end'});
  viewports[2].success(south);viewports[1].success(north);
  assert.equal(page.data.summary.franchiseCount,2);
  page.onRegionChange({type:'end'});page.onHide();viewports[3].success(north);
  assert.equal(page.data.rows.length,0);assert.equal(page.data.summary,null);
});

test('地图和地区目录加载失败可重试，隐藏/重开后迟到的旧快照不回写',async()=>{
  const {page,network}=await openMap({manual:true});
  page.onHide();page.onShow();
  const reply=(entry,data)=>entry.options.success({statusCode:200,data:{code:0,data}});
  reply(network[2],fixture());reply(network[3],regions);await flush();
  assert.equal(page.data.overview.franchiseCount,2);
  reply(network[0],{...fixture(),records:[]});reply(network[1],[]);await flush();
  assert.equal(page.data.overview.franchiseCount,2);
  const retry=page.load();network[5].options.fail({errMsg:'目录请求失败'});reply(network[4],fixture());await retry;
  assert.match(page.data.error,/目录请求失败/);assert.equal(page.data.rows.length,0);
});

test('同县气泡渲染每个品牌名称与各自家/条数；点气泡保留全部品牌名单',async()=>{
  const {page,runtime}=await openMap();
  page.applyRegion('420115');page.renderMap(true);
  const screen=await runtime.currentPage(),callout=await screen.$('.county-callout'),caption=await callout.text();
  assert.match(caption,/测试甲/);assert.match(caption,/测试乙/);assert.match(caption,/1条覆盖记录/);
  assert.equal(page.data.callouts[0].brands.length,2);
  await callout.tap();assert.equal(page.data.pinned,true);assert.equal(page.data.rows.length,3);
  assert.match(await (await screen.$('.area-quick-status')).text(),/江夏区.*已有加盟商/);
});

test('名称搜索跨所选县找出全范围记录，点击跨县加盟商定位所有真实县中心并高亮',async()=>{
  const {page,runtime,fits}=await openMap();
  page.applyRegion('420117');page.renderMap(true);
  assert.equal(page.data.summary.status,'empty');
  page.searchInput({detail:{value:'验收记录one'}});page.searchName();
  assert.deepEqual(page.data.rows.map(row=>row.id),['one']);
  assert.equal(page.data.markers.length,2);
  assert.equal(page.data.summary.status,'empty','行政区结论仍属于明确选中的新洲区');
  page.toggleRecord({currentTarget:{dataset:{id:'one'}}});
  await (await (await runtime.currentPage()).$('map')).trigger('updated',{});
  assert.equal(page.data.expandedId,'one');assert.equal(page.data.callouts.filter(row=>row.selected).length,2);
  assert.equal(page.data.rows[0].districtCount,2);assert.match(page.data.focusNote,/2 个覆盖区县/);
  assert.deepEqual(fits.at(-1).points,[{longitude:114.313961,latitude:30.349045},{longitude:114.374025,latitude:30.874155}]);
  assert.equal(page.data.latitude,30.349045);
});

test('点击只有宽泛地区的待确认资料不生成坐标；单县记录定位到真实县中心',async()=>{
  const pending={...record('pending','a',['420100']),matchStatus:'PARTIAL',originalRegionText:'武汉片区'};
  const {page,fits}=await openMap({source:fixture([pending])});
  page.searchInput({detail:{value:'验收记录pending'}});page.searchName();
  const before=fits.length,center=[page.data.longitude,page.data.latitude];
  page.toggleRecord({currentTarget:{dataset:{id:'pending'}}});
  assert.equal(fits.length,before);assert.deepEqual([page.data.longitude,page.data.latitude],center);
  assert.equal(page.data.markers.length,0);assert.match(page.data.focusNote,/待确认/);
  page.searchInput({detail:{value:'验收记录two'}});page.searchName();page.toggleRecord({currentTarget:{dataset:{id:'two'}}});
  assert.equal(page.data.longitude,114.313961);assert.equal(page.data.latitude,30.349045);
  assert.match(page.data.focusNote,/江夏区中心/);assert.equal(page.data.callouts[0].selected,true);
});

test('视野加载/失败不展示旧名单和旧品牌数，切回行政区立即恢复；拖图不再次fit',async()=>{
  const {page,runtime,viewports,fits}=await openMap();
  const bounds={southwest:{longitude:114.30,latitude:30.33},northeast:{longitude:114.33,latitude:30.37}},fitCount=fits.length;
  page.changeMode({currentTarget:{dataset:{mode:'viewport'}}});
  assert.equal(await (await runtime.currentPage()).$('.brand-list'),null);
  viewports[0].success(bounds);
  assert.equal(page.data.summary.franchiseCount,2);assert.equal(fits.length,fitCount);
  page.onRegionChange({detail:{type:'begin',causedBy:'gesture'}});page.onRegionChange({detail:{type:'end',causedBy:'drag'}});
  viewports[1].fail({errMsg:'native map unavailable'});
  const screen=await runtime.currentPage();
  assert.equal(await screen.$('.brand-list'),null);assert.equal(await screen.$('.stores'),null);
  assert.match(await (await screen.$('.area-quick-status')).text(),/范围读取失败/);
  page.refreshViewport();page.changeMode({currentTarget:{dataset:{mode:'region'}}});
  assert.equal(page.data.viewportLoading,false);assert(await screen.$('.summary-metrics'));assert(await screen.$('.stores'));
  viewports[2].success(bounds);assert.equal(fits.length,fitCount);assert.equal(page.data.mode,'region');
});

test('首次视野状态仍为loading时，相同bounds的成功回调仍能完成统计',async()=>{
  const {page,viewports}=await openMap();
  page.changeMode({currentTarget:{dataset:{mode:'viewport'}}});
  const bounds={southwest:{longitude:114.30,latitude:30.33},northeast:{longitude:114.33,latitude:30.37}};
  page.bounds=bounds;
  assert.equal(page.data.summary.status,'loading');
  viewports[0].success(bounds);
  assert.equal(page.data.summary.status,'registered');assert.equal(page.data.summary.franchiseCount,2);
});

test('点县气泡后展开唯一门店，视野刷新时列表和已展开项不丢失',async()=>{
  const source=fixture(Array.from({length:31},(_,i)=>record('first-'+i)).concat(record('only','a',['420117'])));
  source.records=source.records.slice(3);
  const ids=new Set(source.records.map(r=>r.id));
  source.points=source.points.filter(p=>ids.has(p.recordId));
  const {page,runtime,viewports}=await openMap({source});
  const bounds={southwest:{longitude:114,latitude:30},northeast:{longitude:115,latitude:31}};
  page.changeMode({currentTarget:{dataset:{mode:'viewport'}}});
  viewports[0].success(bounds);
  const screen=await runtime.currentPage();
  await (await screen.$('.county-callout[data-marker-id="2"]')).tap();
  assert.deepEqual(page.data.rows.map(row=>row.id),['only']);
  const scrolls=[]; wx.pageScrollTo=options=>scrolls.push(options);
  await (await screen.$('.store')).tap();
  assert.deepEqual(scrolls,[],'只移动地图镜头，页面不从展开的资料跳走');
  assert.equal(page.data.expandedId,'only');assert(page.data.pinned);
  assert.deepEqual(page.data.rows.map(row=>row.id),['only']);
  await (await screen.$('map')).trigger('regionchange',{type:'end'});
  assert(await screen.$('.record-detail'),'定位更新期间也应保留已展开的选定点资料');
  viewports[1].success({southwest:{longitude:114.78,latitude:30.82},northeast:{longitude:114.82,latitude:30.87}});
  assert.equal(page.data.summary.franchiseCount,1);
  assert.deepEqual(page.data.rows.map(row=>row.id),['only']);
  assert.equal(page.data.expandedId,'only');
  await (await screen.$('map')).trigger('regionchange',{type:'end',causedBy:'drag'});
  viewports[2].success({southwest:{longitude:114.30,latitude:30.33},northeast:{longitude:114.33,latitude:30.37}});
  assert.equal(page.data.summary.franchiseCount,31);
  assert.deepEqual(page.data.rows.map(row=>row.id),['only'],'移动镜头不等于关闭点位资料');
  assert.equal(page.data.expandedId,'only');
  await (await screen.$('.list-head button')).tap();
  assert.equal(page.data.pinned,false);
  assert.equal(page.data.expandedId,'');
  assert.equal(page.data.total,31);
});

test('加载第31条后可展开和收起；换品牌时清除旧点与旧分页',async()=>{
  const {page,runtime}=await openMap({source:fixture(Array.from({length:31},(_,i)=>record('new-'+i)))});
  const screen=await runtime.currentPage();
  await (await screen.$('.county-callout')).tap();
  assert.equal(page.data.rows.length,30);
  await (await screen.$('.more')).tap();
  const id=page.data.rows[30].id;
  await (await screen.$('.store[data-id="'+id+'"]')).tap();
  assert.equal(page.data.rows[30].id,id);
  assert.equal(page.data.expandedId,id);
  await (await screen.$('.store[data-id="'+id+'"]')).tap();
  assert.equal(page.data.expandedId,'');assert.equal(page.data.rows[30].id,id);
  page.changeBrand({currentTarget:{dataset:{id:'b'}}});
  assert.equal(page.data.pinned,false);
  assert.equal(page.data.expandedId,'');
  assert(page.data.rows.every(row=>row.brandId==='b'));
});

test('视野总数和跨地区名称匹配数分别显示；清空查询后只保留视野统计',async()=>{
  const {page,runtime,viewports}=await openMap({source:fixture(Array.from({length:30},(_,i)=>record('extra-'+i)))});
  const screen=await runtime.currentPage();
  page.changeMode({currentTarget:{dataset:{mode:'viewport'}}});
  viewports[0].success({southwest:{longitude:114.30,latitude:30.33},northeast:{longitude:114.33,latitude:30.37}});
  page.searchInput({detail:{value:'验收记录two'}});page.searchName();
  assert.equal(page.data.summary.franchiseCount,32);
  assert.equal(page.data.summary.coverageRecordCount,1);
  assert.equal(page.data.searchMatchCount,1);
  assert.equal(page.data.markers.length,1);
  assert.match(await (await screen.$('.summary-note')).text(),/视野统计.*不随名称搜索变化/);
  assert.match(await (await screen.$('.query-note')).text(),/跨地区.*匹配 1 条资料/);
  page.searchInput({detail:{value:'没有这条记录'}});page.searchName();
  assert.match(await (await screen.$('.query-note')).text(),/匹配 0 条资料/);
  page.clearSearch();
  assert.equal(await screen.$('.query-note'),null);
  assert.equal(page.data.summary.franchiseCount,32);
});
