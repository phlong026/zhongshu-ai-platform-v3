const { canViewAllStores } = require('../../../utils/store-map-access');

const axes = [['province', '全部省份'], ['city', '全部城市'], ['district', '全部区县']];
const text = value => value == null ? '' : String(value).trim();
const unique = values => [...new Set(values.filter(Boolean))];
const color = value => /^#[0-9a-f]{6}$/i.test(value) ? value : '#89512a';
const hasPosition = row => Number.isFinite(row.latitude) && Number.isFinite(row.longitude)
  && Math.abs(row.latitude) <= 90 && Math.abs(row.longitude) <= 180;

// Only the backend decides the accessible brands. Fail closed on inconsistent snapshots.
function readSnapshot(payload, post) {
  if (!payload || !post) throw Error('地图数据格式异常');
  const admin = payload.scope === 'all' && canViewAllStores(post);
  const brandId = text(payload.brandId);
  if (!admin && !(payload.scope === 'brand' && post.orgType === 'store' && brandId
      && (!post.brandId || text(post.brandId) === brandId))) throw Error('当前岗位无权查看此地图');
  const version = Number(payload.schemaVersion) >= 2 ? 2 : 1;
  const publicProjection = version === 2 && Number(payload.publicProjectionVersion) === 1;
  const source = version === 2 ? payload.records : payload.stores;
  if (!Array.isArray(source) || version === 2 && (!Array.isArray(payload.points) || !Array.isArray(payload.brands))) {
    throw Error('地图数据格式异常');
  }
  const records = [...new Map(source.map(row => {
    if (!row.id || !row.brandId || !admin && text(row.brandId) !== brandId) throw Error('地图品牌范围异常，请重新加载');
    const recordType = version === 2 ? row.recordType : 'FRANCHISE';
    if (!['FRANCHISE', 'COVERAGE'].includes(recordType)) throw Error('地图记录类型异常');
    const regionCodes = unique((version === 2 ? row.regionCodes || [] : [row.districtCode || row.cityCode || row.provinceCode]).map(text));
    // Explicit public DTO projection also drops private fields returned by older cached clients/proxies.
    const record = { id:text(row.id), brandId:text(row.brandId), name:publicProjection ? text(row.name) : '',
      brandName:text(row.brandName), color:color(row.brandColor), recordType, regionCodes,
      provinceCode:text(row.provinceCode), cityCode:text(row.cityCode),
      matchStatus:version === 2 ? row.matchStatus : row.districtCode ? 'RESOLVED' : 'UNRESOLVED',
      publicAddress:publicProjection ? text(row.publicAddress) : '',
      wholeAreaCoverage:publicProjection && recordType === 'COVERAGE' && row.wholeAreaCoverage === true,
      districtCode:text(row.districtCode), provinceName:text(row.provinceName), cityName:text(row.cityName),
      districtName:text(row.districtName), latitude:row.latitude, longitude:row.longitude, locationPrecision:row.locationPrecision };
    return [record.id, record];
  })).values()];
  const byId = new Map(records.map(row => [row.id, row]));
  const brands = version === 2 ? payload.brands.map(brand => {
    if (!brand.id || !admin && text(brand.id) !== brandId) throw Error('地图品牌目录范围异常');
    return { id:text(brand.id), name:text(brand.name), color:color(brand.color) };
  }) : [...new Map(records.map(row => [row.brandId, {id:row.brandId,name:row.brandName,color:row.color}])).values()];
  const points = version === 2 ? payload.points.map(point => {
    const record = byId.get(text(point.recordId));
    if (!record || !record.regionCodes.includes(text(point.regionCode)) || point.locationPrecision !== 'district') {
      throw Error('地图落点与登记资料不一致');
    }
    return { id:text(point.id), recordId:record.id, regionCode:text(point.regionCode),
      latitude:point.latitude, longitude:point.longitude, locationPrecision:'district' };
  }) : records.filter(row => row.districtCode).map(row => ({
    id:row.id, recordId:row.id, regionCode:text(row.districtCode), longitude:row.longitude,
    latitude:row.latitude, locationPrecision:row.locationPrecision || 'unknown'
  }));
  return { admin, version, brandId, brandName:text(payload.brandName), records, stores:records, byId, brands, points };
}

function readRegions(rows) {
  if (!Array.isArray(rows)) throw Error('行政区目录格式异常');
  const regions = rows.map(row => ({ code:text(row.code), name:text(row.name),
    level:({1:'province',2:'city',3:'district'})[row.level] || text(row.level).toLowerCase(),
    parentCode:text(row.parentCode), provinceCode:text(row.provinceCode), cityCode:text(row.cityCode),
    districtCode:text(row.districtCode), provinceName:text(row.provinceName), cityName:text(row.cityName), districtName:text(row.districtName),
    latitude:row.latitude, longitude:row.longitude }));
  if (regions.some(row => !row.code || !row.name || !['province','city','district'].includes(row.level))) throw Error('行政区目录格式异常');
  return { rows:regions, byCode:new Map(regions.map(row => [row.code, row])) };
}

function legacyRegions(snapshot) {
  const rows = new Map();
  snapshot.records.forEach(row => axes.forEach(([level], index) => {
    const code = text(row[level + 'Code']);
    if (code && !rows.has(code)) rows.set(code, { ...row, code, name:row[level + 'Name'] || code, level,
      parentCode:index ? text(row[axes[index - 1][0] + 'Code']) : '' });
  }));
  return readRegions([...rows.values()]);
}

const regionTitle = region => region ? unique([region.provinceName, region.cityName, region.districtName || region.name]).join(' · ') : '全国已登记范围';
const selectedRegion = (filters, catalog) => catalog.byCode.get(filters.district || filters.city || filters.province);
const contains = (parent, region) => !parent || !!region && (parent.code === region.code
  || parent.level === 'province' && parent.code === region.provinceCode
  || parent.level === 'city' && parent.code === region.cityCode);
const districtCodes = (record, catalog) => record.regionCodes.filter(code => catalog.byCode.get(code)?.level === 'district');
const incomplete = (record, catalog) => record.matchStatus !== 'RESOLVED' || !districtCodes(record, catalog).length;

function mightCover(record, region, catalog) {
  if (!region) return true;
  const known = record.regionCodes.map(code => catalog.byCode.get(code)).filter(Boolean);
  const provinces = unique([record.provinceCode, ...known.map(row => row.provinceCode || (row.level === 'province' ? row.code : ''))]);
  const cities = unique([record.cityCode, ...known.map(row => row.cityCode || (row.level === 'city' ? row.code : ''))]);
  return (!provinces.length || provinces.includes(region.provinceCode || region.code))
    && (region.level === 'province' || !cities.length || cities.includes(region.level === 'city' ? region.code : region.cityCode)
      || known.some(row => row.code === region.code));
}

// A province/city anchor indicates where to find the draft coverage record, not
// verified coverage of its children. Never infer a county from a broad label.
function upperCoverageScope(record, catalog) {
  if (record.recordType !== 'COVERAGE' || districtCodes(record,catalog).length) return null;
  const parent = [record.cityCode, ...record.regionCodes, record.provinceCode]
    .map(code => catalog.byCode.get(code)).filter(Boolean);
  return parent.find(row => row.level === 'city') || parent.find(row => row.level === 'province') || null;
}

function buildPickers(filters, catalog) {
  return axes.map(([key, title], index) => {
    const rows = catalog.rows.filter(row => (index === 0 || !!filters.province) && row.level === key
      && (key === 'province' || !filters.province || row.provinceCode === filters.province)
      && (key !== 'district' || !filters.city || row.cityCode === filters.city));
    const choices = [{id:'',name:key === 'city' && filters.province && !rows.length ? '省直辖区县' : title},
      ...rows.map(row => ({id:row.code,name:key === 'district' && !filters.city && row.cityName ? row.cityName + ' · ' + row.name : row.name}))];
    return {key,title:['省份','城市','区县'][index],choices,index:Math.max(0,choices.findIndex(row => row.id === filters[key])),
      disabled:index > 0 && (!filters.province || !rows.length)};
  });
}

function searchRegions(catalog, keyword) {
  const normalize = value => text(value).replace(/[省市区县\s·/]/g, '').toLowerCase();
  const query = normalize(keyword);
  if (!query) return [];
  return catalog.rows.filter(row => normalize(regionTitle(row)).includes(query)).sort((a,b) =>
    Number(normalize(b.name) === query) - Number(normalize(a.name) === query)
    || a.code.length - b.code.length || a.code.localeCompare(b.code)).slice(0,8)
    .map(row => ({code:row.code,name:row.name,label:regionTitle(row),level:row.level}));
}

function inBounds(point, bounds) {
  if (!bounds || !hasPosition(bounds.southwest || {}) || !hasPosition(bounds.northeast || {})) return false;
  const { southwest:sw, northeast:ne } = bounds;
  return point.latitude >= sw.latitude && point.latitude <= ne.latitude
    && (sw.longitude <= ne.longitude ? point.longitude >= sw.longitude && point.longitude <= ne.longitude
      : point.longitude >= sw.longitude || point.longitude <= ne.longitude);
}

function counts(records) {
  return {franchiseCount:records.filter(row => row.recordType === 'FRANCHISE').length,
    coverageRecordCount:records.filter(row => row.recordType === 'COVERAGE').length};
}

function groupMarkers(points, snapshot, catalog, selectedRecordId, selectedPointKey) {
  const groups = new Map();
  for (const point of points) {
    if (!hasPosition(point)) continue;
    const key = point.latitude + ',' + point.longitude;
    if (!groups.has(key)) groups.set(key, {latitude:point.latitude,longitude:point.longitude,recordIds:new Set(),regionCodes:new Set()});
    const group = groups.get(key); group.recordIds.add(point.recordId); group.regionCodes.add(point.regionCode);
  }
  return [...groups].map(([key,group],index) => {
    const records = [...group.recordIds].map(id => snapshot.byId.get(id)), first = records[0];
    const mixed = records.some(row => row.brandId !== first.brandId), amount = counts(records);
    const heading = unique([...group.regionCodes].map(code => catalog.byCode.get(code)?.name)).join('、');
    const detail = [amount.franchiseCount ? amount.franchiseCount + '家加盟商' : '', amount.coverageRecordCount ? amount.coverageRecordCount + '条覆盖记录' : ''].filter(Boolean).join(' · ');
    const brands = [...new Set(records.map(row => row.brandId))].map(id => {
      const own = records.filter(row => row.brandId === id), first = own[0], amount = counts(own);
      return {id,name:first.brandName,color:first.color,...amount,
        detail:[amount.franchiseCount ? amount.franchiseCount + '家' : '', amount.coverageRecordCount ? amount.coverageRecordCount + '条覆盖记录' : ''].filter(Boolean).join(' · ')};
    });
    const selected = group.recordIds.has(selectedRecordId) || key === selectedPointKey;
    const pinText = amount.franchiseCount
      ? amount.franchiseCount + (snapshot.admin ? '' : '家')
      : amount.coverageRecordCount + '条';
    const pinColor = snapshot.admin && !mixed ? first.color : '#a56a2d';
    return {id:index + 1,key,latitude:group.latitude,longitude:group.longitude,storeIds:[...group.recordIds],
      heading,brands,selected,pinText,pinColor,iconPath:'/pages/account/store-map/pin.jpg',width:1,height:1,zIndex:selected ? 10 : 1,
      customCallout:{display:'ALWAYS',anchorY:0},
      callout:{content:(heading || (mixed ? '多品牌' : first.brandName)) + '\n' + detail,display:'ALWAYS',
        color:'#ffffff',bgColor:mixed ? '#89512a' : first.color,borderRadius:8,padding:7,fontSize:11}};
  });
}

function mapView(snapshot, filters = {}, catalog, mode = 'region', bounds = null) {
  const suppliedCatalog = !!catalog;
  catalog = catalog?.byCode ? catalog : catalog ? readRegions(catalog) : legacyRegions(snapshot);
  const target = selectedRegion(filters, catalog), viewport = mode === 'viewport';
  const allDistricts = unique(snapshot.records.flatMap(row => districtCodes(row, catalog)));
  const overview = {...counts(snapshot.records),districtCount:allDistricts.length};
  const candidates = snapshot.records.filter(row => !snapshot.admin || !filters.brand || row.brandId === filters.brand);
  const candidateIds = new Set(candidates.map(row => row.id));
  const pointSource = snapshot.version === 1 && suppliedCatalog ? snapshot.points.map(point => {
    const region = catalog.byCode.get(point.regionCode);
    return {...point,latitude:region?.latitude,longitude:region?.longitude,locationPrecision:'district'};
  }) : snapshot.points;
  const validPoints = pointSource.filter(point => candidateIds.has(point.recordId) && hasPosition(point) && point.locationPrecision === 'district'
    && catalog.byCode.get(point.regionCode)?.level === 'district');
  const visiblePoints = validPoints.filter(point => viewport ? inBounds(point,bounds) : contains(target,catalog.byCode.get(point.regionCode)));
  const visibleIds = new Set(visiblePoints.map(point => point.recordId));
  const confirmed = candidates.filter(row => viewport ? visibleIds.has(row.id)
    : districtCodes(row,catalog).some(code => contains(target,catalog.byCode.get(code))));
  const upperById = new Map(candidates.map(row => [row.id,upperCoverageScope(row,catalog)]));
  const whole = viewport ? [] : candidates.filter(row => {
    const upper = upperById.get(row.id);
    return row.wholeAreaCoverage && upper && (contains(target,upper) || target && contains(upper,target));
  });
  const pending = viewport ? [] : candidates.filter(row => !row.wholeAreaCoverage && incomplete(row,catalog) && mightCover(row,target,catalog));
  const areaLabels = new Map();
  for (const row of pending) {
    const upper = upperById.get(row.id);
    if (!upper) continue;
    const label = areaLabels.get(upper.code) || {code:upper.code,name:upper.name,count:0};
    label.count++;
    areaLabels.set(upper.code,label);
  }
  const focusedArea = target && areaLabels.size === 1 ? [...areaLabels.values()][0] : null;
  const pendingCoverageLabel = focusedArea ? focusedArea.name + ' · 区县待确认 ' + focusedArea.count + ' 条'
    : areaLabels.size ? '另有 ' + [...areaLabels.values()].reduce((sum,row) => sum+row.count,0) + ' 条省市覆盖资料待确认区县' : '';
  const wholeAreas = unique(whole.map(row => upperById.get(row.id).name));
  const wholeCoverageLabel = wholeAreas.length === 1 ? wholeAreas[0] + ' · 已确认全域覆盖 ' + whole.length + ' 条'
    : wholeAreas.length ? '已确认省市全域覆盖 ' + whole.length + ' 条' : '';
  const upperCoverageLabel = [wholeCoverageLabel,pendingCoverageLabel].filter(Boolean).join('；');
  const areaConfirmed = [...confirmed,...whole];
  const amounts = counts(areaConfirmed), pendingCount = pending.length;
  const status = viewport && !bounds ? 'loading' : amounts.franchiseCount || amounts.coverageRecordCount ? 'registered' : pendingCount ? 'pending' : 'empty';
  const summary = { ...amounts, pendingCount, brandCount:new Set(areaConfirmed.map(row => row.brandId)).size,
    districtCount:viewport ? new Set(visiblePoints.map(point => point.regionCode)).size
      : new Set(confirmed.flatMap(row => districtCodes(row,catalog).filter(code => contains(target,catalog.byCode.get(code))))).size,
    title:viewport ? '当前地图视野' : regionTitle(target),shortTitle:viewport ? '当前地图视野' : target?.name || '全国已登记范围',status,
    statusText:status === 'loading' ? '正在读取地图范围' : status === 'pending' ? '资料待确认'
      : status === 'empty' ? '暂无已登记加盟商' : amounts.franchiseCount ? '已有加盟商' : '已有覆盖记录',
    offMapPendingCount:viewport ? candidates.filter(row => !row.wholeAreaCoverage && incomplete(row,catalog)).length : 0 };
  const keyword = text(filters.keyword).toLowerCase();
  const regionText = row => unique(row.regionCodes.map(code => catalog.byCode.get(code)).filter(Boolean).map(regionTitle)).join('；')
    || unique([row.provinceCode,row.cityCode].map(code => catalog.byCode.get(code)).filter(Boolean).map(regionTitle)).join('；') || '地区待补充';
  const publicName = row => row.name || row.brandName + ' · ' + regionText(row) + (row.recordType === 'FRANCHISE' ? '门店' : '覆盖记录');
  const matchesName = row => !keyword || [publicName(row),row.publicAddress,regionText(row),row.brandName].join(' ').toLowerCase().includes(keyword);
  const resultRecords = keyword ? candidates : [...new Map([...areaConfirmed,...pending].map(row => [row.id,row])).values()];
  const formatRow = row => ({...row,
    name:publicName(row), typeLabel:row.recordType === 'FRANCHISE' ? '加盟商' : '覆盖记录',
    upperCoverageLabel:upperById.get(row.id)?.name || '',
    wholeAreaCoverage:row.wholeAreaCoverage && !!upperById.get(row.id),
    pending:!row.wholeAreaCoverage && incomplete(row,catalog),districtCount:districtCodes(row,catalog).length,
    districtNames:districtCodes(row,catalog).map(code => regionTitle(catalog.byCode.get(code))).join('；'),
    regionText:regionText(row) });
  const rows = resultRecords.filter(matchesName).map(formatRow);
  const mapRecordIds = new Set(candidates.filter(matchesName).map(row => row.id));
  const mapPoints = validPoints.filter(point => mapRecordIds.has(point.recordId)
    && (keyword || viewport || point.recordId === filters.record || contains(target,catalog.byCode.get(point.regionCode))));
  const markers = groupMarkers(mapPoints,snapshot,catalog,filters.record,filters.pointKey);
  const pinnedMarker = filters.pointKey && markers.find(marker => marker.key === filters.pointKey);
  // A point list is scoped to its pin, not to the camera bounds after moving the map.
  const pinnedRows = pinnedMarker ? pinnedMarker.storeIds.map(id => formatRow(snapshot.byId.get(id))) : null;
  const displayBrands = snapshot.brands.filter(brand => !filters.brand || brand.id === filters.brand);
  const brandCounts = displayBrands.map(brand => ({...brand,...counts(areaConfirmed.filter(row => row.brandId === brand.id)),
    pendingCount:pending.filter(row => row.brandId === brand.id).length}));
  const regionCenters = target ? catalog.rows.filter(row => row.level === 'district' && contains(target,row) && hasPosition(row)) : [];
  const fitPoints = (keyword && markers.length ? markers : regionCenters.length ? regionCenters : markers)
    .map(({latitude,longitude}) => ({latitude,longitude}));
  return { overview, summary, upperCoverageLabel, rows, pinnedRows, markers, points:validPoints, brandCounts, brands:[{id:'',name:'全部品牌'},...snapshot.brands],
    pickers:buildPickers(filters,catalog), fitPoints,
    center:keyword && markers.length ? markers[0] : hasPosition(target || {}) ? target : fitPoints[0] || {latitude:35.9,longitude:104.2} };
}

module.exports = { axes, readSnapshot, readRegions, mapView, searchRegions, hasPosition };
