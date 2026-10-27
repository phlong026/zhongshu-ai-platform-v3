package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FranchiseCrmConfiguration {

    /** 客户、客资、跟进只在归属门店内可见 */
    @Bean
    public OrgScopeCustomizer franchiseCrmOrgScope() {
        return rule -> {
            rule.register("fr_customer", "company_id");
            rule.register("fr_lead", "company_id");
            rule.register("fr_follow_up", "company_id");
        };
    }

}
