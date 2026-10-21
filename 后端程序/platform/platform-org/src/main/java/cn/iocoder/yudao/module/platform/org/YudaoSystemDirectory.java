package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.RoleDO;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
        return roleService.getRoleList().stream()
                .filter(r -> Objects.equals(r.getCode(), roleCode))
                .map(RoleDO::getId)
                .findFirst().orElse(null);
    }

    @Override
    public boolean userExists(long userId) {
        return adminUserService.getUser(userId) != null;
    }

    @Override
    public Set<Long> userRoleIds(long userId) {
        return new HashSet<>(permissionService.getUserRoleIdListByUserId(userId));
    }

    @Override
    public void replaceUserRoles(long userId, Set<Long> roleIds) {
        permissionService.assignUserRole(userId, roleIds);
    }

}
