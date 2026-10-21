package cn.iocoder.yudao.module.platform.org;

import java.util.Set;

/**
 * 平台组织模块对 yudao 系统模块（部门、角色、用户）的最小依赖面。
 * 生产实现为 {@link YudaoSystemDirectory}；测试中替换为内存实现，避免引入 yudao 的全部系统表。
 */
public interface SystemDirectory {

    /** 在 parentDeptId 下新建部门（null 表示根），返回部门编号 */
    long createDept(Long parentDeptId, String name);

    /** 部门自身及全部下级部门编号 */
    Set<Long> deptAndDescendants(long deptId);

    /** 岗位编码对应的 yudao 角色编号；未配置返回 null */
    Long roleIdByCode(String roleCode);

    boolean userExists(long userId);

    Set<Long> userRoleIds(long userId);

    /** 用给定集合整体替换用户的角色 */
    void replaceUserRoles(long userId, Set<Long> roleIds);

    /**
     * 岗位对应的角色（须为启用状态）是否拥有该权限码。
     * 与 yudao 一致采用严格模式：权限码找不到对应菜单时视为没有权限。
     */
    boolean roleHasPermission(String roleCode, String permission);

}
