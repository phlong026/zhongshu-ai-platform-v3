// Fixtures for the simulator layout audit, built from the new commission protocol only:
// the last engineering-records scene (a sales-offline-v1 customer with project, payments and
// engineering records) with the account swapped per demo identity.
const fs = require('fs'), path = require('path');
const copy = x => JSON.parse(JSON.stringify(x));
const ACCOUNT = 'zhongshu-account-v1';
// role label used in reports -> demo login id
const IDENTITIES = {
  'sales-lead': 'sales-dual',
  boss: 'boss',
  'finance-lead': 'finance-dual',
  'project-manager': 'engineering-dual',
  'hq-design-admin': 'hq-design-admin',
  'hq-finance': 'hq-finance-dual',
  'hq-boss': 'hq-boss',
  owner: 'store-owner'
};
module.exports = async function build() {
  await require('./role-page-runtime.cjs').connect();
  const A = require('../miniprogram/utils/account'), Demo = require('../miniprogram/utils/demo-users');
  const scenes = JSON.parse(fs.readFileSync(path.resolve('qa/engineering-records-20261021/page-scenes.json'), 'utf8'));
  const base = copy(scenes[scenes.length - 1].after || scenes[scenes.length - 1].before);
  const install = rows => {
    wx.getStorageInfoSync().keys.forEach(k => wx.removeStorageSync(k));
    for (const [k, v] of copy(rows)) wx.setStorageSync(k, v);
  };
  const rowsByRole = { guest: [] };
  for (const [label, id] of Object.entries(IDENTITIES)) {
    install(base);
    const a = A.load();
    Demo.login(a, id);
    wx.setStorageSync(A.KEY, a);
    rowsByRole[label] = wx.getStorageInfoSync().keys.map(k => [k, wx.getStorageSync(k)]);
  }
  const rows = rowsByRole['sales-lead'];
  const biz = rows.find(r => r[0] === 'zhongshu-business-v3')[1];
  const eng = rows.find(r => r[0] === 'zhongshu-engineering-v1')[1];
  const mgmt = rows.find(r => r[0] === 'zhongshu-management-v1')[1];
  const customer = biz.customers.find(c => c.businessFlow?.type === 'sales-offline-v1') || biz.customers[0];
  const project = eng.projects.find(p => p.sourceCustomerId === customer.id) || eng.projects[0];
  const payable = (mgmt.payables || []).find(p => String(p.id).includes(customer.id)) || (mgmt.payables || [])[0];
  const ids = [customer.id, project && project.id, 'business-design:' + customer.id + ':1:first', payable && payable.id].filter(Boolean);
  return { rowsByRole, customerId: customer.id, projectId: project && project.id, ids };
};
