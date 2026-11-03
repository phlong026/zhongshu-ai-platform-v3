package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
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
        AdminUserDO user = adminUserService.getUserByMobile(mobile);
        return user == null ? null : user.getId();
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
    public List<UserBrief> usersByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UserBrief> result = new ArrayList<>();
        for (Long id : userIds) {
            AdminUserDO user = adminUserService.getUser(id);
            if (user != null) {
                result.add(new UserBrief(user.getId(), user.getNickname(), user.getMobile(),
                        CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())));
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setUserActive(long userId, boolean active) {
        // 09 钱包会话桥接撤权协议：成员停用先取用户全部岗位行排他锁（覆盖其全部组织，堵跨组织窗口），
        // 状态修改与钱包会话吊销同事务提交/回滚
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

}
