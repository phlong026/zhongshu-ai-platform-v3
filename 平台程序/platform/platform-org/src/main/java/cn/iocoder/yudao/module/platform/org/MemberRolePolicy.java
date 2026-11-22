package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.STAFF_RESERVED_ROLE;

/** 员工入口只分配员工岗位；老板主账号和门店负责人由总部配置。 */
public final class MemberRolePolicy {
    private MemberRolePolicy() { }

    public static boolean isEmployeeRole(String roleCode) {
        return !"boss".equals(roleCode) && !"owner".equals(roleCode);
    }

    public static boolean isAssignable(String orgType, String roleCode) {
        return !"store".equals(orgType) || isEmployeeRole(roleCode);
    }

    public static void requireAssignable(String orgType, String roleCode) {
        if ("store".equals(orgType)) requireEmployeeRole(roleCode);
    }

    public static void requireEmployeeRole(String roleCode) {
        if (!isEmployeeRole(roleCode)) {
            throw new ServiceException(403, "老板和门店负责人账号由总部配置，请选择员工岗位");
        }
    }

    public static void requireRevocable(String orgType, String roleCode) {
        if ("store".equals(orgType) && !isEmployeeRole(roleCode)) {
            throw new ServiceException(STAFF_RESERVED_ROLE);
        }
    }
}
