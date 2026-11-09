const assert = require('node:assert/strict');
const storage = new Map();
let definition;
let requests = [];
let toasts = [];
global.Page = page => { definition = page; };
global.getApp = () => ({ globalData: {} });
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  request: request => { requests.push(request); return { abort() {} }; },
  showToast: toast => toasts.push(toast.title),
  showModal: options => options.success({ confirm: true })
};
const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
require('../miniprogram/pages/account/index');

function manager() {
  storage.clear(); requests = []; toasts = [];
  http.saveSession({ token: 'member-session-fixture', postId: '42' });
  auth.savePosts([
    { postId: '42', orgId: '7', roleCode: 'boss', orgType: 'store' },
    { postId: '43', orgId: '8', roleCode: 'owner', orgType: 'store' }
  ]);
  return { ...definition, handoverCtx: { postId: '42', orgId: '7' },
    data: { ...structuredClone(definition.data), apiMembersBusy: false,
      handoverSource: { userId: '71', name: '示例离职人' },
      apiReceivers: [{ userId: '72', name: '示例接收人' }], handoverTargetIndex: 0,
      apiTeamOptions: [], apiRoleOptionsForAdd: [{ id: 'sales', name: '业务员' }],
      form: { memberName: '示例员工', memberMobile: '13800000001' } },
    setData(patch) { Object.assign(this.data, patch); }
  };
}

(async () => {
  const row = { currentTarget: { dataset: { postId: '71', userId: '71', name: '示例员工', active: true } } };
  let cases = 0;
  for (const method of ['confirmHandover', 'confirmEmergency', 'addMember', 'revokeRow', 'toggleActiveRow']) {
    for (const outcome of ['success', 'failure']) {
      const page = manager();
      page[method](row);
      const request = requests.find(item => item.method !== 'GET');
      assert.ok(request, method + ' must issue one command under the original identity');
      assert.equal(request.header['X-Post-Id'], '42');
      auth.switchPost('43');
      Object.assign(page.data, { handoverResult: { title: '新岗位结果' }, handoverBusy: true, apiMembersBusy: true, error: '' });
      const expected = structuredClone(page.data);
      if (outcome === 'success') request.success({ statusCode: 200, data: { code: 0, data: { items: [] } } });
      else request.fail({ errMsg: 'fixture network failure' });
      await new Promise(resolve => setImmediate(resolve));
      assert.deepEqual(page.data, expected, method + ': 旧岗位' + outcome + '不能改写新岗位页面');
      assert.deepEqual(toasts, [], '旧操作不得在新岗位显示结果提示');
      cases += 1;
    }
  }
  console.log('成员操作会话隔离：' + cases + '例通过');
})().catch(error => { console.error(error); process.exitCode = 1; });
