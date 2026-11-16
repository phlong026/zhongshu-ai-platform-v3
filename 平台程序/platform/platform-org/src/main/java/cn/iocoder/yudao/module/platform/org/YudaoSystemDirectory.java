package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.STAFF_MOBILE_AMBIGUOUS;

/**
 * 基于 yudao system 模块 Service 的 {@link SystemDirectory} 实现。
 */
@Component
public class YudaoSystemDirectory implements SystemDirectory {

    @Resource
    private DeptService deptService;
    @Resource
    private RoleService roleService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private AdminUserService adminUserService;
    @Resource
    private AdminUserMapper adminUserMapper;
    @Resource
    private MenuService menuService;
    @Resource
    private cn.iocoder.yudao.module.platform.org.dal.StaffPostMapper staffPostMapper;
    /** 钱包会话撤权回调（franchise-org 实现；组装裁剪时静默跳过） */
    @Resource
    private ObjectProvider<WalletSessionRevocation> walletSessionRevocation;

    @Override
    public long createDept(Long parentDeptId, String name) {
        DeptSaveReqVO req = new DeptSaveReqVO();
        req.setName(name);
        req.setParentId(parentDeptId == null ? DeptDO.PARENT_ID_ROOT : parentDeptId);
        req.setSort(0);
        req.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return deptService.createDept(req);
    }

    @Override
    public Set<Long> deptAndDescendants(long deptId) {
        Set<Long> result = new HashSet<>(deptService.getChildDeptIdListFromCache(deptId));
        result.add(deptId);
        return result;
    }

    @Override
    public Long roleIdByCode(String roleCode) {
        RoleDO role = roleByCode(roleCode);
        return role == null ? null : role.getId();
    }

    @Override
    public boolean roleHasPermission(String roleCode, String permission) {
        RoleDO role = roleByCode(roleCode);
        if (role == null || !CommonStatusEnum.ENABLE.getStatus().equals(role.getStatus())) {
            return false;
        }
        List<Long> menuIds = menuService.getMenuIdListByPermissionFromCache(permission);
        if (menuIds == null || menuIds.isEmpty()) {
            return false;
        }
        Set<Long> roleMenuIds = permissionService.getRoleMenuListByRoleId(role.getId());
        return menuIds.stream().anyMatch(roleMenuIds::contains);
    }

    private RoleDO roleByCode(String roleCode) {
        return roleService.getRoleList().stream()
                .filter(r -> Objects.equals(r.getCode(), roleCode))
                .findFirst().orElse(null);
    }

    @Override
    public boolean userExists(long userId) {
        return adminUserService.getUser(userId) != null;
    }

    @Override
    public boolean userUsable(long userId) {
        AdminUserDO user = adminUserService.getUser(userId);
        return user != null && CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus());
    }

    @Override
    public Set<Long> userRoleIds(long userId) {
        return new HashSet<>(permissionService.getUserRoleIdListByUserId(userId));
    }

    @Override
    public void replaceUserRoles(long userId, Set<Long> roleIds) {
        permissionService.assignUserRole(userId, roleIds);
    }

    @Override
    public Long userIdByMobile(String mobile) {
        // system_users.mobile 只是普通索引；selectOne 会把合法的历史重号直接变成 500。
        // Mapper 仍由底座统一注入 tenant_id + deleted=0，这里只在当前租户的候选集内消歧。
        List<AdminUserDO> users = adminUserMapper.selectListByMobile(mobile);
        if (users.isEmpty()) {
            return null;
        }
        if (users.size() == 1) {
            return users.get(0).getId();
        }
        List<Long> userIds = users.stream().map(AdminUserDO::getId).toList();
        List<Long> activeUserIds = staffPostMapper.selectActiveByUsers(userIds).stream()
                .map(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getUserId)
                .distinct()
                .toList();
        if (activeUserIds.size() == 1) {
            return activeUserIds.get(0);
        }
        // 多个在岗账号无法证明是同一人；多个未任职账号也没有可靠判据。
        // 显式拒绝并要求上层指定 userId，不按创建时间或编号静默取第一条。
        throw exception(STAFF_MOBILE_AMBIGUOUS);
    }

    @Override
    public long createUser(String name, String mobile, Long deptId) {
        UserSaveReqVO req = new UserSaveReqVO();
        req.setUsername(mobile); // username 即手机号；员工经微信/短信登录，不使用密码
        req.setMobile(mobile);
        req.setNickname(name);
        req.setDeptId(deptId);
        // 底座要求密码必填：给一次性随机值，任何人都不知道；员工经微信/短信登录，密码通道不启用
        req.setPassword(java.util.UUID.randomUUID() + "-Zs" + new java.security.SecureRandom().nextInt(9));
        return adminUserService.createUser(req);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createUserWithPassword(String name, String mobile, Long deptId, String initialPassword) {
        UserSaveReqVO req = new UserSaveReqVO();
        // 保留底座账号约束；面向加盟商的正式账号/简称映射至此 userId。
        req.setUsername(mobile);
        req.setMobile(mobile);
        req.setNickname(name);
        req.setDeptId(deptId);
        req.setPassword(initialPassword);
        long userId = adminUserService.createUser(req);
        // 旧底座默认成本因子为4，新交付密码采用BCrypt 10；不修改其他用户的密码。
        String encoded = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode(initialPassword);
        adminUserMapper.updateById(new AdminUserDO().setId(userId).setPassword(encoded));
        return userId;
    }

    @Override
    public List<UserBrief> usersByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        // 单次 IN 查询，避免按 ID 循环（客户/候选批量映射时的 N+1）。
        return adminUserService.getUserList(userIds).stream()
                .map(user -> new UserBrief(user.getId(), user.getNickname(), user.getMobile(),
                        CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setUserActive(long userId, boolean active) {
        // 统一锁序为用户行 → 岗位行：与微信绑定的外呼后复核一致，避免交叉等待。
        // lockById 只取锁不写状态，租户条件由底座拦截器保持。
        adminUserMapper.lockById(userId);
        // 09 钱包会话桥接撤权协议：覆盖该用户全部组织的岗位窗口。
        staffPostMapper.lockUserPostsForUpdate(userId);
        adminUserService.updateUserStatus(userId,
                active ? CommonStatusEnum.ENABLE.getStatus() : CommonStatusEnum.DISABLE.getStatus());
        if (!active) {
            List<Long> orgIds = staffPostMapper.selectOrgIdsEverHeld(userId);
            if (!orgIds.isEmpty()) {
                walletSessionRevocation.ifAvailable(cb -> cb.revokeForUserOrgs(userId, orgIds));
            }
        }
    }

    @Override
    public String deptName(Long deptId) {
        if (deptId == null) {
            return "";
        }
        DeptDO dept = deptService.getDept(deptId);
        return dept == null ? "" : dept.getName();
    }

    @Override
    public boolean deptUnavailable(Long deptId) {
        if (deptId == null) return true;
        DeptDO dept = deptService.getDept(deptId);
        return dept == null || !CommonStatusEnum.ENABLE.getStatus().equals(dept.getStatus());
    }

    @Override
    public java.util.List<Long> findRootDeptIdsByName(String name) {
        if (name == null || name.isBlank()) {
            return java.util.List.of();
        }
        // 根层级（parent=ROOT）且同名：组织开通幂等查重只认顶层组织部门（按名查后过滤根层级）
        return deptService.getDeptList(new cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptListReqVO()
                        .setName(name)).stream()
                .filter(d -> d != null && name.equals(d.getName()) && java.util.Objects.equals(DeptDO.PARENT_ID_ROOT, d.getParentId()))
                .map(DeptDO::getId)
                .toList();
    }

}
