package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 交易流五表组织过滤注册（SQL 层追加 company_id = 当前组织；crm/finance 同惯例） */
@Configuration
public class SalesOrgScope {

    @Bean
    public OrgScopeCustomizer salesOrgScopeCustomizer() {
        return rule -> {
            rule.register("fr_sale_requirement", "company_id");
            rule.register("fr_sale_quote", "company_id");
            rule.register("fr_sale_contract", "company_id");
            rule.register("fr_sale_payment_stage", "company_id");
            rule.register("fr_sale_delivery", "company_id");
        };
    }
}
