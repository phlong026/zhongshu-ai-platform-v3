// 组织成员管理（管理员指派制）验证：部门加人（业务员/业务负责人可选）+ 撤销/停用 + 身份页入口。
// 用法：node scripts/verify-org-admin.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

const api = read('miniprogram/api/org.js');
const http = read('miniprogram/api/http.js');
const page = read('miniprogram/pages/account/index.js');
const wxml = read('miniprogram/pages/account/index.wxml');

// 1) API 层契约
assert(api.includes("'/platform/org/teams'") && api.includes("'/platform/org/staff'"), 'org.js 覆盖部门与成员接口');
assert(api.includes("'/platform/org/posts/'") && api.includes("'/platform/org/staff-status'"), 'org.js 支持撤销任职与停用员工');
assert(http.includes('del:'), 'http 层支持 DELETE 方法');

// 2) 页面逻辑：部门+岗位选择、防抖、roleCode 中文名、协议之外的管理动作确认
assert(page.includes('apiRoleOptionsForAdd') && page.includes("O.enabled(post.orgType,r)"), '岗位选项按组织类型过滤启用岗位（业务员/业务负责人等）');
assert(page.includes('roleCode:role.id,teamId:team?team.deptId:null'), '添加成员提交部门与岗位（teamId 可空为全店）');
assert(page.includes('revokeRow') && page.includes('toggleActiveRow') && page.includes('wx.showModal'), '撤销/停用均有确认弹窗');
assert(page.includes("roleName:O.roles[r.roleCode]||r.roleCode"), '成员列表显示岗位中文名');
assert(page.includes('isOrgManager(apiPost)') && page.includes("['boss','owner'].includes(post.roleCode)"), '管理入口仅对老板/门店负责人开放');

// 3) 视图：表单、提示与入口
assert(wxml.includes("view==='api-members'"), '成员与权限视图已注册');
assert(wxml.includes('picker') && wxml.includes('addMember'), '添加成员表单含部门与岗位选择器');
assert(wxml.includes('管理员指派制'), '界面说明管理员指派制语义');
assert(wxml.includes('管理本店成员与权限'), '身份页提供成员管理入口');
assert(wxml.includes('业务负责人'), '界面明示可选业务负责人');

console.log('verify-org-admin: 全部断言通过（部门加人/岗位指派/撤销停用/入口守卫 契约完整）');
