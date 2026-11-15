const assert = require('node:assert/strict');

const storage = new Map();
let definition;
let requests = [];
let actionSheet = null;

global.Page = page => { definition = page; };
global.getApp = () => ({ globalData: {} });
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  request: request => { requests.push(request); return { abort() {} }; },
  showToast() {},
  showModal: options => options.success({ confirm: true }),
  showActionSheet: options => { actionSheet = options; }
};

const http = require('../miniprogram/api/http');
const auth = require('../miniprogram/api/auth');
require('../miniprogram/pages/account/index');

const flush = () => new Promise(resolve => setImmediate(resolve));

function manager(rows) {
  storage.clear();
  requests = [];
  actionSheet = null;
  http.saveSession({ token: 'member-mobile-fixture', postId: '42' });
  // 总部保留旧 addStaff 同号消歧；门店改走 staff-accounts 独立账号开通接口。
  auth.savePosts([{ postId: '42', orgId: '7', roleCode: 'boss', orgType: 'headquarters' }]);
  return {
    ...definition,
    handoverCtx: { postId: '42', orgId: '7' },
    data: {
      ...structuredClone(definition.data),
      apiMembersBusy: false,
      apiStaffRows: structuredClone(rows),
      apiTeamOptions: [{ deptId: '88', name: '业务部' }],
      apiRoleOptionsForAdd: [{ id: 'sales', name: '业务员' }, { id: 'finance', name: '财务专员' }],
      form: { memberName: '张测试', memberMobile: '13800000001', roleIndex: 0, teamIndex: 0 },
      error: ''
    },
    setData(patch) { Object.assign(this.data, patch); }
  };
}

function rejectAsAmbiguous() {
  assert.equal(requests.length, 1, '先只能发起一次未指定账号的请求');
  requests[0].success({ statusCode: 200, data: { code: 1101000023, msg: '该手机号对应多个员工账号' } });
  return flush();
}

(async () => {
  const rows = [
    { userId: '101', name: '张老板', mobile: '13800000001', roleName: '老板' },
    { userId: '101', name: '张老板', mobile: '13800000001', roleName: '业务员' },
    { userId: '202', name: '张经理', mobile: '13800000001', roleName: '工程人员' },
    { userId: '303', name: '其他员工', mobile: '13900000002', roleName: '财务专员' }
  ];

  const page = manager(rows);
  page.addMember();
  await rejectAsAmbiguous();
  assert.ok(actionSheet, '歧义时必须让管理员选择账号');
  assert.equal(actionSheet.itemList.length, 2, '同一 userId 的多岗位行须合并为一个候选');
  assert.match(actionSheet.itemList[0], /张老板.*老板.*业务员.*101/, '候选须展示姓名、全部岗位和编号');
  assert.match(actionSheet.itemList[1], /张经理.*工程人员.*202/, '每个账号必须可被明确辨认');
  assert.equal(requests.length, 1, '弹出选择前不得自动取第一个重试');

  actionSheet.success({ tapIndex: 1 });
  await flush();
  assert.equal(requests.length, 2, '明确选择后才能重试');
  assert.equal(requests[1].data.userId, '202', '重试必须携带管理员选中的 userId');
  assert.equal(requests[1].data.mobile, '13800000001');

  const changed = manager(rows);
  changed.addMember();
  await rejectAsAmbiguous();
  changed.data.form.memberMobile = '13900000002';
  actionSheet.success({ tapIndex: 0 });
  await flush();
  assert.equal(requests.length, 1, '弹窗期间手机号变化不得重试');
  assert.match(changed.data.error, /已变化|重新/, '必须告知管理员重新确认');

  const roleChanged = manager(rows);
  roleChanged.addMember();
  await rejectAsAmbiguous();
  roleChanged.data.form.roleIndex = 1;
  actionSheet.success({ tapIndex: 0 });
  await flush();
  assert.equal(requests.length, 1, '弹窗期间待指派岗位变化不得重试');

  const switched = manager(rows);
  switched.addMember();
  await rejectAsAmbiguous();
  auth.switchPost('missing-post');
  actionSheet.success({ tapIndex: 0 });
  await flush();
  assert.equal(requests.length, 1, '弹窗期间会话/岗位变化不得重试');

  const missing = manager([{ userId: '303', name: '其他员工', mobile: '13900000002', roleName: '财务专员' }]);
  missing.addMember();
  await rejectAsAmbiguous();
  assert.equal(actionSheet, null, '本店列表无候选时不能伪造选择');
  assert.match(missing.data.error, /总部.*核对|总部.*分辨/, '无候选必须请总部分辨');

  console.log('总部旧成员同号账号消歧：5 组行为通过');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
