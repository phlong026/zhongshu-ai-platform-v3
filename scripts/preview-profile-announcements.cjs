const { createServer } = require('node:http');

const notices = [
  {
    id: 'bf1c9d6b-fdab-4c3a-92ed-ba741ae764a1',
    title: '门店运营培训安排',
    summary: '11 月 30 日线上培训，演示客户跟进、正式报价和工程移交。',
    content: '【本地模拟公告】\n\n11 月 30 日 14:00 开展线上门店运营培训，重点讲解客户八步跟进、正式报价资料和工程移交。请各门店负责人组织业务、工程及财务同事参加。\n\n培训后可在「我的 → 学习培训」查看回放及配套资料。',
    pinned: true,
    publishedAt: '2026-11-26T01:30:00Z'
  },
  {
    id: 'cbfe076a-fa9b-4e86-a2f6-63f69abe112b',
    title: '12 月门店资料核对提醒',
    summary: '请于 12 月 2 日前核对门店对外地址、联系电话和营业时间。',
    content: '【本地模拟公告】\n\n请门店老板打开「我的 → 门店管理 → 加盟商资料」，核对门店对外地址、联系电话和营业时间。资料核对完成后，可在「门店地图」确认展示信息。',
    pinned: false,
    publishedAt: '2026-11-25T08:00:00Z'
  },
  {
    id: 'e5bebfca-04ea-4c59-9fc8-a813b2b464cc',
    title: '项目账本月末核对提醒',
    summary: '请在月末核对已登记的到款和支出，补齐关联凭据。',
    content: '【本地模拟公告】\n\n请工程和财务同事在 11 月 30 日前核对项目账本，重点确认当期到款、实际支出、退款及相关凭据。发现差异时请从对应业务原单发起核实。',
    pinned: false,
    publishedAt: '2026-11-24T03:00:00Z'
  }
].map(notice => ({ ...notice, status: 'PUBLISHED', revision: 1 }));

const trainingByRole = {
  boss: ['management', '门店经营与团队管理', '经营管理', '门店经营月度复盘表', 2],
  owner: ['store-owner', '门店日常经营与人员协作', '经营管理', '门店交接检查表', 1],
  sales: ['sales', '客户跟进与正式报价', '业务销售', '客户跟进记录表', 1],
  'sales-lead': ['sales-lead', '销售团队管理与客户交接', '业务销售', '销售团队交接清单', 2],
  engineer: ['engineering', '乡墅开工与现场交底', '工程施工', '开工交底检查表', 1],
  'project-manager': ['project-manager', '项目排期与现场质量复验', '工程施工', '施工节点复验清单', 2],
  finance: ['finance', '财务收支与项目对账', '财务对账', '项目对账核对表', 1],
  'finance-lead': ['finance-lead', '财务复核与月度结账', '财务对账', '月度结账复核清单', 2]
};
const brand = { id: 'preview-brand', name: '合家美宅', color: '#985523' };
const province = { provinceCode: '360000', provinceName: '江西省' };
const city = { ...province, cityCode: '360100', cityName: '南昌市' };
const districts = [
  ['360102', '东湖区', 28.686, 115.899],
  ['360103', '西湖区', 28.657, 115.877],
  ['360121', '南昌县', 28.545, 115.945]
].map(([code, name, latitude, longitude]) => ({
  ...city, code, name, level: 'district', parentCode: city.cityCode, districtCode: code, districtName: name, latitude, longitude
}));
const regions = [
  { ...province, code: province.provinceCode, name: province.provinceName, level: 'province' },
  { ...city, code: city.cityCode, name: city.cityName, level: 'city', parentCode: province.provinceCode },
  ...districts
];
const stores = districts.map(district => ({
  ...district, id: 'preview-store-' + district.code, brandId: brand.id, brandName: brand.name, brandColor: brand.color,
  recordType: 'FRANCHISE', matchStatus: 'RESOLVED', regionCodes: [district.code],
  name: district.name + '示例门店', publicAddress: city.cityName + district.name + '（演示资料）'
}));
const storeMap = {
  scope: 'brand', schemaVersion: 2, publicProjectionVersion: 1, brandId: brand.id, brandName: brand.name, brands: [brand], records: stores,
  points: stores.map(({ id, districtCode, latitude, longitude }) => ({
    id: id + '-point', recordId: id, regionCode: districtCode, latitude, longitude, locationPrecision: 'district'
  }))
};
const replies = new Map([
  ['/platform/profile/mine', { id: '701', nickname: '陈明', avatar: '/assets/account-avatar.jpg' }],
  ['/platform/messages/center/summary', { count: 3 }],
  ['/franchise/company-points/summary', { balance: 9300, status: 'ACTIVE' }],
  ['/franchise/company-points/my-usage', { used: 0, list: [], total: 0 }],
  ['/franchise/company-points/recharge/catalog', { enabled: false }]
]);

if (require.main === module) {
  createServer((req, res) => {
    const send = (data, code = 0, msg = '') => {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ code, msg, data }));
    };
    if (req.method !== 'GET') return send(null, 403, '本地模拟环境只提供读取');
    const url = new URL(req.url, 'http://127.0.0.1');
    const path = url.pathname.replace(/^\/admin-api/, '');
    if (path === '/health') return send({ ready: true });
    if (path === '/franchise/store-map' || path === '/franchise/store-map/regions') {
      const role = String(req.headers['x-post-id'] || '').replace(/^preview-/, '');
      if (!Object.hasOwn(trainingByRole, role)) return send(null, 403, '请选择本地加盟商演示身份');
      if (path === '/franchise/store-map') return send(storeMap);
      const parent = url.searchParams.get('parentCode');
      return send(parent ? regions.filter(row => row.parentCode === parent) : regions);
    }
    if (path.startsWith('/training/app/')) {
      const role = String(req.headers['x-post-id'] || '').replace(/^preview-/, '');
      const seed = Object.hasOwn(trainingByRole, role) ? trainingByRole[role] : null;
      if (!seed) return send([]);
      const [id, title, category, name, completed] = seed;
      const course = { id, title, category, completed, lessons: 6, percent: Math.round(completed / 6 * 100), summary: '本地模拟岗位课程' };
      if (path === '/training/app/courses') return send([course]);
      if (path === '/training/app/materials') {
        return send([{ courseId: id, category, courseTitle: title, versionId: id + '-fixture', name, fileName: name + '.pdf', extension: 'pdf', version: 1 }]);
      }
      if (path === '/training/app/courses/' + id) {
        return send({ ...course, lessons: ['岗位职责', '办理流程', '常见问题', '资料核对', '实际演练', '复盘总结']
          .map((lesson, index) => ({ id: 'lesson-' + index, title: lesson, chapter: '岗位实操', completed: index < completed })) });
      }
      return send(null, 403, '当前模拟岗位无此课程权限');
    }
    const endpoint = '/platform/announcements/app';
    if (path === endpoint) {
      const keyword = (url.searchParams.get('keyword') || '').trim();
      const rows = notices.filter(row => (row.title + row.summary + row.content).includes(keyword));
      const size = Number(url.searchParams.get('pageSize')) || 20;
      const offset = ((Number(url.searchParams.get('pageNo')) || 1) - 1) * size;
      return send({ list: rows.slice(offset, offset + size), total: rows.length });
    }
    if (path.startsWith(endpoint + '/')) {
      const row = notices.find(item => item.id === path.slice(endpoint.length + 1));
      return row ? send(row) : send(null, 404, '模拟公告不存在');
    }
    return replies.has(path) ? send(replies.get(path)) : send(null, 404, '本地示例未配置此接口');
  }).listen(39776, '127.0.0.1');
}

module.exports = notices;
module.exports.trainingByRole = trainingByRole;
