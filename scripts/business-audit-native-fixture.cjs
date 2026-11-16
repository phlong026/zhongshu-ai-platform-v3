// Controlled native UI data only. This is not backend acceptance evidence.
// All wx.request calls are intercepted, and unconfigured/write calls fail closed.
function mockRequest(options) {
  const app = getApp();
  const state = app.globalData.__businessFixture || (app.globalData.__businessFixture = { requests: [], rows: null });
  const day = new Date();
  if (!state.rows) state.rows = Array.from({ length: 60 }, (_, i) => ({
    id: String(i + 1), name: i === 57 ? '跨页目标' : '受控客户' + (i + 1), phone: String(13800000000 + i),
    province: '湖北省', city: i === 57 ? '宜昌市' : '武汉市', status: i === 57 ? 'FOLLOWING' : 'REGISTERED',
    nextVisitDate: i === 57 ? [day.getFullYear(), day.getMonth() + 1, day.getDate()] : null,
    lastFollowAt: i === 57 ? Date.now() - 86400000 : null, createTime: Date.now() - i * 1000, revision: 1
  }));
  const route = String(options.url).split('/admin-api')[1].split('?')[0];
  const query = options.data || {}, method = String(options.method || 'GET').toUpperCase();
  state.requests.push({ method, route, pageNo: query.pageNo, pageSize: query.pageSize, keyword: query.keyword || '', fixture: true, at: Date.now() });
  let data, code = 0;
  if (method !== 'GET') code = 590001;
  else if (route === '/platform/profile/mine') data = { nickname: '受控交互样例', mobile: '13800000000' };
  else if (route === '/platform/posts/mine') data = [{ postId: '991', userId: '992', orgId: '993', roleCode: 'sales', orgName: '受控测试组织' }];
  else if (route === '/platform/todos') data = { items: [], failedDomains: [], actionableCount: 0 };
  else if (route === '/franchise/customers') {
    const term = String(query.keyword || '').trim();
    const rows = state.rows.filter(row => [row.name, row.phone, row.province, row.city].some(value => String(value || '').includes(term)));
    const size = Number(query.pageSize || 50), start = (Number(query.pageNo || 1) - 1) * size;
    data = { list: rows.slice(start, start + size), total: rows.length };
  } else if (route === '/franchise/commissions') data = { list: [], total: 0 };
  else if (route === '/franchise/content/growth/summary') data = {};
  else if (route === '/franchise/content/drafts' || route === '/franchise/content/course-progress') data = [];
  else if (route === '/franchise/company-points/summary') data = { balance: 1000, inFlight: 0, status: 'ACTIVE' };
  else if (route === '/franchise/company-points/my-usage') data = { used: 0, list: [], total: 0 };
  else if (route === '/platform/messages/unread-count') data = { count: 0 };
  else if (route === '/franchise/design/cases') data = { list: [], total: 0, nextCursor: null };
  else code = 590001;
  return { statusCode: 200, data: { code, data, msg: code ? '受控样例未配置此接口；未调用真实后端' : '' }, header: {} };
}
module.exports = { mockRequest };
