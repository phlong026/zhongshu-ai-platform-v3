/**
 * 组织成员管理（管理员指派制）：老板/门店负责人在本组织内加人、指派岗位、撤销、停用。
 * 对应后端 /platform/org/**；写操作需当前岗位为 boss/owner，且只能管理本组织。
 */
const http = require('./http');

/** 本组织部门列表 */
function orgTeams(orgId) {
  return http.get('/platform/org/teams', { orgId });
}

/** 本组织成员与任职列表 */
function orgStaff(orgId) {
  return http.get('/platform/org/staff', { orgId });
}

/** 录入员工并指派岗位（手机号已存在则复用账号；同一岗位幂等） */
function addStaff(payload) {
  return http.post('/platform/org/staff', payload);
}

/** 给已有员工追加岗位 */
function assignPost(payload) {
  return http.post('/platform/org/posts', payload);
}

/** 撤销任职（该员工登录后此岗位立即消失） */
function revokePost(postId) {
  return http.del('/platform/org/posts/' + postId);
}

/** 启用或停用员工 */
function setStaffStatus(userId, active) {
  return http.post('/platform/org/staff-status', { userId, active });
}

module.exports = { orgTeams, orgStaff, addStaff, assignPost, revokePost, setStaffStatus };
