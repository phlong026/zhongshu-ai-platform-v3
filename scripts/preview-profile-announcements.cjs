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

const course = { id: 'management', title: '门店经营与团队管理', category: '经营管理', completed: 2, lessons: 6, percent: 33 };
const replies = new Map([
  ['/platform/profile/mine', { id: '701', nickname: '陈明', avatar: '/assets/account-avatar.jpg' }],
  ['/platform/messages/center/summary', { count: 3 }],
  ['/franchise/company-points/summary', { balance: 9300, status: 'ACTIVE' }],
  ['/franchise/company-points/my-usage', { used: 0, list: [], total: 0 }],
  ['/franchise/company-points/recharge/catalog', { enabled: false }],
  ['/training/app/courses', [course]],
  ['/training/app/materials', []]
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
