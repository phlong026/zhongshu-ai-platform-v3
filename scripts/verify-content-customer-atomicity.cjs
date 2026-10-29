const assert = require('node:assert/strict');

(async () => {
  await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const SalesDemo = require('../miniprogram/utils/sales-demo');
  const Growth = require('../miniprogram/utils/growth');
  const Business = require('../miniprogram/utils/business');
  const businessKey = require('../miniprogram/utils/storage-keys').business;
  const managementKey = require('../miniprogram/utils/storage-keys').management;

  Account.mutate(state => Account.loginDemo(state, 'zhang'));
  SalesDemo.ensure();

  const beforeGrowth = wx.getStorageSync(Growth.KEY);
  const beforeBusiness = wx.getStorageSync(businessKey);
  const beforeManagement = wx.getStorageSync(managementKey);
  const fromLead = Business.fromLead;
  Business.fromLead = () => { throw new Error('模拟客户建档失败'); };
  try {
    assert.throws(() => Growth.capture({
      name: '原子性测试客户',
      phone: '13998981234',
      need: '两层乡墅，需要平面、立面和施工图',
      source: '转介绍'
    }), /模拟客户建档失败/);
  } finally {
    Business.fromLead = fromLead;
  }

  assert.deepEqual(wx.getStorageSync(Growth.KEY), beforeGrowth, '客户建档失败时不得留下半截客资');
  assert.deepEqual(wx.getStorageSync(businessKey), beforeBusiness, '客户建档失败时不得改写客户簿');
  assert.deepEqual(wx.getStorageSync(managementKey), beforeManagement, '客户建档失败时不得改写责任工作簿');
  console.log('PASS 客户建档失败时，客资与客户保持原子一致');

  const setStorageSync = wx.setStorageSync;
  let growthWrites = 0;
  wx.setStorageSync = (key, value) => {
    if (key === Growth.KEY && ++growthWrites === 2) throw new Error('模拟客资最终落盘失败');
    return setStorageSync(key, value);
  };
  try {
    assert.throws(() => Growth.capture({
      name: '最终落盘测试客户',
      phone: '13998981235',
      need: '三层乡墅，需要完整设计与施工交底',
      source: '老客户转介绍'
    }), /模拟客资最终落盘失败/);
  } finally {
    wx.setStorageSync = setStorageSync;
  }

  assert.deepEqual(wx.getStorageSync(Growth.KEY), beforeGrowth, '最终客资落盘失败时须恢复客资簿');
  assert.deepEqual(wx.getStorageSync(businessKey), beforeBusiness, '最终客资落盘失败时须删除已创建客户');
  assert.deepEqual(wx.getStorageSync(managementKey), beforeManagement, '最终客资落盘失败时须删除孤儿客户责任项并恢复账簿revision');

  const retried = Growth.capture({
    name: '最终落盘测试客户',
    phone: '13998981235',
    need: '三层乡墅，需要完整设计与施工交底',
    source: '老客户转介绍'
  });
  const customers = wx.getStorageSync(businessKey).customers.filter(customer => customer.phone === '13998981235');
  const leads = wx.getStorageSync(Growth.KEY).leads.filter(lead => lead.phone === '13998981235');
  const works = wx.getStorageSync(managementKey).work.filter(work => work.customerId === retried.customer.id && work.projectId === retried.customer.projectId);
  assert.equal(customers.length, 1, '失败后重试只能创建一个客户');
  assert.equal(leads.length, 1, '失败后重试只能创建一条客资');
  assert.equal(works.length, 1, '失败后重试只能创建一个客户责任项');
  assert.equal(wx.getStorageSync(managementKey).revision, beforeManagement.revision + 1, '失败事务不得额外增加工作簿revision');
  assert.equal(leads[0].customerId, retried.customer.id);
  assert.equal(leads[0].status, '已转客户');
  console.log('PASS 最终客资落盘失败时完整回滚，重试不重复客户');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
