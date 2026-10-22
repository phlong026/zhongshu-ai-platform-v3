package cn.iocoder.yudao.module.franchise.org;

import java.util.List;

/**
 * 加盟协同域岗位编码（即 yudao 角色编码），由迁移 V20261022.801 注册。
 */
public interface FranchiseRoles {

    String DOMAIN = "franchise";
    String ORG_HEADQUARTERS = "headquarters";
    String ORG_STORE = "store";

    String SALES = "fr_sales";
    String SALES_LEAD = "fr_sales_lead";
    String ENGINEER = "fr_engineer";
    String PROJECT_MANAGER = "fr_project_manager";
    String FINANCE = "fr_finance";
    String FINANCE_LEAD = "fr_finance_lead";
    String BOSS = "fr_boss";
    String OWNER = "fr_owner";
    String HQ_DESIGN_ADMIN = "fr_hq_design_admin";

    List<String> ALL = List.of(SALES, SALES_LEAD, ENGINEER, PROJECT_MANAGER, FINANCE, FINANCE_LEAD, BOSS, OWNER,
            HQ_DESIGN_ADMIN);

}
