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

/** 门店老板为员工开通独立账号并指派岗位；初始密码仅在本次响应返回。 */
function openStaffAccount(payload) {
  return http.post('/platform/org/staff-accounts', payload);
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

/** 正常离职交接（M01-T04：先校验接收人，逐域批量转移在途对象，再撤本组织任职；失败整体回滚可重试）{orgId,fromUserId,toUserId} → {mode,items:[{domain,label,inFlight,transferred,status}]} */
function staffHandover(payload) {
  return http.post('/platform/org/staff-handover', payload);
}

/** 紧急撤权（M01-T04：立即撤本组织任职与钱包会话；在途事项逐域转入待分配并返回清单）{orgId,userId} */
function staffEmergencyRevoke(payload) {
  return http.post('/platform/org/staff-emergency-revoke', payload);
}

/** 待分配清单（M01-T04：紧急撤权后在途事项，可对其发起补交接）→ [{id,fromUserId,domain,domainLabel,inFlight,createTime}] */
function handoverPending(orgId) {
  return http.get('/platform/org/handover-pending', { orgId });
}

/** 按姓名、手机号或员工编号分页查找本组织曾任职人员（含未登记待分配的旧离职员工）。 */
function handoverSources(orgId, keyword, pageNo) {
  return http.get('/platform/org/handover-sources', { orgId, keyword, pageNo: pageNo || 1 });
}

module.exports = { orgTeams, orgStaff, addStaff, openStaffAccount, assignPost, revokePost, setStaffStatus, staffHandover, staffEmergencyRevoke, handoverPending, handoverSources };
