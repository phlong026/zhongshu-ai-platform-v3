const assert = require('node:assert/strict');

(async () => {
  await require('./role-page-runtime.cjs').connect();
  const Account = require('../miniprogram/utils/account');
  const Demo = require('../miniprogram/utils/demo-users');
  const Organization = require('../miniprogram/utils/organization');
  const key = 'zhongshu-management-v1';
  Account.mutate(state => Account.loginDemo(state, 'sales-dual'));
  assert.equal(Organization.defaultAssignee(wx.getStorageSync(key), 'engineering'), 'engineering-dual');

  for (const selected of ['engineering-lead', '']) {
    const book = wx.getStorageSync(key);
    book.managementSettings.assignments.engineering = selected;
    wx.setStorageSync(key, book);
    Demo.ensure();
    assert.equal(wx.getStorageSync(key).managementSettings.assignments.engineering, selected, '已有分工或明确清空不得覆盖');
  }
  const book = wx.getStorageSync(key);
  delete book.managementSettings;
  wx.setStorageSync(key, book);
  const account = wx.getStorageSync(Account.KEY);
  account.session.demo = false;
  wx.setStorageSync(Account.KEY, account);
  Demo.ensure();
  assert.equal(wx.getStorageSync(key).managementSettings, undefined, '真实会话不得自动补模拟岗位');
  console.log('PASS 体验组织补齐默认工程岗位，保留自定义与空配置，真实会话不改写');
})().catch(error => {
  console.error(error.stack);
  process.exitCode = 1;
});
