const assert = require('node:assert/strict');

const clone = value => JSON.parse(JSON.stringify(value));

async function main() {
  await require('./role-page-runtime.cjs').connect();
  const { W } = await require('./verify-owner-workbench.cjs').fixture();
  const H = require('../miniprogram/pages/admin/owner-home');
  const B = require('../miniprogram/pages/admin/owner-business');
  const snapshot = W.snapshot();
  const originalGrowth = wx.getStorageSync('zhongshu-growth-v1');
  const checks = [];
  const pass = name => { checks.push(name); console.log('PASS ' + name); };

  try {
    const sample = clone(snapshot);
    const customer = sample.customers.find(row => row.id === 'customer-chen') || sample.customers[0];
    customer.follows = (customer.follows || []).concat({
      method: '电话',
      next: '到店沟通',
      appointment: sample.today + ' 10:00',
      date: sample.today,
      author: '李业务',
      content: '已确认到店时间并继续核对建房需求'
    });
    wx.setStorageSync('zhongshu-growth-v1', {
      version: 1,
      contents: [{
        id: 'content-owner-p0',
        companyId: sample.actor.companyId,
        authorId: 'sales-dual',
        status: '演示已发布',
        publishedAt: sample.today + 'T09:00:00.000Z',
        pack: { title: '武汉乡墅采光设计' }
      }],
      leads: [{
        id: 'lead-owner-p0',
        companyId: sample.actor.companyId,
        ownerId: 'sales-dual',
        name: '周女士',
        source: '内容咨询',
        contentId: 'content-owner-p0',
        status: '已转客户',
        at: sample.today + 'T10:00:00.000Z'
      }],
      events: []
    });

    const home = H.home(sample);
    const handover = home.groups.find(group => group.key === 'handover');
    const inactiveOwners = new Set(sample.state.staff.filter(person => person.active === false).map(person => person.id));
    const expectedHandover = sample.state.work.filter(work => inactiveOwners.has(work.owner));
    assert(handover, '首页提供交接待办分组');
    assert.equal(handover.count, expectedHandover.length);
    assert(H.todoRows(sample, 'handover').every(row => row.url.includes('view=transfer')));
    assert(!H.todoRows(sample, 'handover').some(row => row.key.includes('engineering-work:biz-customer')),
      '在岗项目经理的业务转工程记录不能制造重复交接审批');
    pass('交接分组只收纳老板需办理的失效责任人内部交接');

    assert(home.business.notes.some(row => row.text.includes('预约到店 1 人')));
    assert(home.business.notes.some(row => row.text.includes('本月发布内容 1 条') && row.text.includes('内容获客 1 条')));
    assert(home.business.notes.every(row => row.key && row.url && row.url.includes('view=stat')));
    assert.equal(B.rows('stat', sample, 'appointment-followups').length, 1);
    assert.equal(B.rows('stat', sample, 'content-acquisition').length, 2);
    pass('业务概况同时汇总真实预约到店与内容获客记录');

    assert.equal(home.six.length, 6);
    assert(home.six.every(row => row.key && row.url && row.url.includes('view=stat')));
    for (const metric of home.six.map(row => row.metric)) {
      const rows = B.rows('stat', sample, metric);
      assert(Array.isArray(rows), metric + ' 返回原单列表');
      assert(rows.every(row => row.key && row.name && row.status && row.url), metric + ' 原单字段完整');
    }
    sample.state.receipts.push(
      { id:'receipt-owner-p0-cents', customer:'周女士', name:'工程预收款', amount:8000000, status:'已核算', bankDate:sample.today, registrar:'王财务', revisions:[] },
      { id:'receipt-owner-p0-zero', customer:'吴先生', name:'更正为零的重复收款', amount:50000, status:'已核算', bankDate:sample.today, registrar:'王财务', revisions:[{ amount:0 }] }
    );
    const incomeRows = B.rows('stat', sample, 'income');
    assert(incomeRows.some(row => row.name.includes('陈先生')));
    assert.equal(incomeRows.find(row => row.key === 'income:receipt-owner-p0-cents').amount, '¥ 80,000');
    assert.equal(incomeRows.find(row => row.key === 'income:receipt-owner-p0-zero').amount, '¥ 0');
    assert(B.rows('stat', sample, 'new-leads').some(row => row.name.includes('周女士')));
    pass('六项经营统计逐项映射本组织当月原单');

    const page = wx.reLaunch({ url: '/pages/admin/home/index' }) && getCurrentPages().at(-1);
    assert(page && !page.data.error, page?.data?.error || '老板首页未打开');
    const income = page.data.desk.home.six.find(row => row.metric === 'income');
    page.go({ currentTarget: { dataset: { key: income.key } } });
    const detail = getCurrentPages().at(-1);
    assert.equal(detail.data.view, 'stat');
    assert.equal(detail.options.id, 'income');
    assert(detail.data.total > 0);
    detail.search({ detail: { value: '陈先生' } });
    assert(detail.data.rows.every(row => (row.name + ' ' + row.status).includes('陈先生')));
    pass('经营统计可点击进入复用分页搜索的原单明细');

    console.log(JSON.stringify({ result: 'passed', checks: checks.length }));
  } finally {
    if (originalGrowth === undefined || originalGrowth === null || originalGrowth === '') wx.removeStorageSync('zhongshu-growth-v1');
    else wx.setStorageSync('zhongshu-growth-v1', originalGrowth);
  }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
