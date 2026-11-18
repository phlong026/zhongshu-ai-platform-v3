const { canViewAllStores } = require('../../../utils/store-map-access');

const axes = [
  ['province', '全部省份'], ['city', '全部城市'], ['district', '全部区县']
];
const all = name => ({ id: '', name });

// The server must scope the entire snapshot before sending it. Reject inconsistent responses.
function readSnapshot(payload, post) {
  if (!payload || !Array.isArray(payload.stores)) throw Error('地图数据格式异常');
  const admin = payload.scope === 'all' && canViewAllStores(post);
  if (!admin && !(payload.scope === 'brand' && post.orgType === 'store' && payload.brandId
    && (!post.brandId || String(post.brandId) === String(payload.brandId)))) throw Error('当前岗位无权查看此地图');
  return { admin, stores: payload.stores.map(store => {
    if (!store.id || !store.brandId || (!admin && String(store.brandId) !== String(payload.brandId))) {
      throw Error('地图品牌范围异常，请重新加载');
    }
    return { ...store, id: String(store.id), brandId: String(store.brandId),
      color: /^#[0-9a-f]{6}$/i.test(store.brandColor) ? store.brandColor : '#89512a' };
  }) };
}

function options(rows, key, idKey, nameKey, title) {
  const choices = new Map();
  rows.forEach(row => {
    const id = String(row[idKey] || '');
    if (id && !choices.has(id)) choices.set(id, { id, name: row[nameKey], color: row.color });
  });
  return { key, choices: [all(title), ...choices.values()] };
}

function groupMarkers(rows) {
  const pins = new Map();
  for (const row of rows) {
    const { latitude, longitude } = row;
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
        Math.abs(latitude) > 90 || Math.abs(longitude) > 180) continue;
    const key = latitude + ',' + longitude;
    if (!pins.has(key)) pins.set(key, { latitude, longitude, stores: [] });
    pins.get(key).stores.push(row);
  }
  return Array.from(pins.values(), (pin, index) => {
    const first = pin.stores[0], mixed = pin.stores.some(row => row.brandId !== first.brandId);
    return {
      id: index + 1, latitude: pin.latitude, longitude: pin.longitude,
      iconPath: '/pages/account/store-map/pin.jpg', width: 17, height: 21,
      callout: { content: (mixed ? '多品牌' : first.brandName) + ' · ' + pin.stores.length,
        display: 'ALWAYS', color: '#ffffff', bgColor: mixed ? '#564c44' : first.color,
        borderRadius: 6, padding: 5, fontSize: 11 },
      storeIds: pin.stores.map(row => row.id)
    };
  });
}

function mapView(snapshot, filters) {
  let rows = snapshot.stores;
  const brands = options(rows, 'brand', 'brandId', 'brandName', '全部品牌').choices;
  if (snapshot.admin && filters.brand) rows = rows.filter(row => row.brandId === filters.brand);
  const pickers = axes.map(([key, title], index) => {
    const field = key + 'Code';
    const picker = options(rows, key, field, key + 'Name', title);
    const selected = picker.choices.findIndex(item => item.id === filters[key]);
    if (selected > 0) rows = rows.filter(row => String(row[field]) === filters[key]);
    return { ...picker, index: Math.max(selected, 0), disabled: index > 0 && !filters[axes[index - 1][0]] };
  });
  const markers = groupMarkers(rows);
  return { rows, pickers, brands, markers, center: markers[0] || { latitude: 35.9, longitude: 104.2 } };
}

module.exports = { axes, readSnapshot, mapView };
