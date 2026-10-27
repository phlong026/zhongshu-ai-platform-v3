package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 设计委托各表的组织范围登记：归属门店 company_id；总部设计行政需要读的表另登记 design_org_id。
 */
@Configuration
public class CommissionOrgScope {

    @Bean
    OrgScopeCustomizer commissionScope() {
        return rule -> {
            rule.register("fr_design_policy", "company_id");
            rule.register("fr_commission", "company_id", "design_org_id");
            rule.register("fr_commission_submission", "company_id", "design_org_id");
            rule.register("fr_commission_progress", "company_id", "design_org_id");
            rule.register("fr_commission_handover", "company_id");
        };
    }

}
