package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Map;
import java.util.Objects;

/**
 * 加盟协同域的组织类型与岗位编码（与小程序 organization.js 一致），以及岗位判断。
 * 继承关系与迁移脚本写入的 pf_post_template 相同，由 FranchiseRolesTest 锁定。
 */
public final class FranchiseRoles {

    public static final String DOMAIN = "franchise";

    public static final String ORG_HEADQUARTERS = "headquarters";
    public static final String ORG_STORE = "store";

    public static final String SALES = "sales";
    public static final String SALES_LEAD = "sales-lead";
    public static final String ENGINEER = "engineer";
    public static final String PROJECT_MANAGER = "project-manager";
    public static final String FINANCE = "finance";
    public static final String FINANCE_LEAD = "finance-lead";
    public static final String BOSS = "boss";
    public static final String OWNER = "owner";
    public static final String DESIGNER = "hq-designer";
    public static final String DESIGN_LEAD = "hq-design-lead";
    public static final String DESIGN_ADMIN = "hq-design-admin";
    public static final String OPERATIONS = "hq-operations";
    public static final String OPERATIONS_LEAD = "hq-operations-lead";

    /** 负责人继承的专员岗位：负责人拥有本条线全部办理权 */
    static final Map<String, String> INHERITS = Map.of(
            SALES_LEAD, SALES,
            PROJECT_MANAGER, ENGINEER,
            DESIGN_LEAD, DESIGNER,
            FINANCE_LEAD, FINANCE,
            OPERATIONS_LEAD, OPERATIONS);

    private FranchiseRoles() {
    }

    /** 岗位是 wanted，或继承了 wanted（如 sales-lead 具备 sales 身份） */
    public static boolean has(PostSnapshot post, String wanted) {
        return Objects.equals(post.roleCode(), wanted) || Objects.equals(INHERITS.get(post.roleCode()), wanted);
    }

    /** 老板与门店负责人：本组织数据全部可见 */
    public static boolean isBoss(PostSnapshot post) {
        return BOSS.equals(post.roleCode()) || OWNER.equals(post.roleCode());
    }

    public static boolean atStore(PostSnapshot post) {
        return ORG_STORE.equals(post.orgType());
    }

    public static boolean atHeadquarters(PostSnapshot post) {
        return ORG_HEADQUARTERS.equals(post.orgType());
    }

}
