package cn.iocoder.yudao.module.franchise.design;

/**
 * 设计赋能权限码（B2 板块 A / A8）：能力岗位门（菜单按钮 franchise:design:use）。
 * 回写/桥接查询另受 CRM CustomerAccess 对象权限约束（能力门与对象权限分离，S1）。
 */
public final class DesignPermissions {

    public static final String USE = "franchise:design:use";

    private DesignPermissions() {
    }
}
