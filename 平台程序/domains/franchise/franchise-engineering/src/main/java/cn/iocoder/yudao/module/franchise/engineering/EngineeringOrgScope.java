package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 工程全周期十三表组织过滤注册（SQL 层追加 company_id = 当前组织；content/sales 同惯例）。 */
@Configuration
public class EngineeringOrgScope {

    @Bean
    public OrgScopeCustomizer engineeringOrgScopeCustomizer() {
        return rule -> {
            rule.register("fr_eng_project", "company_id");
            rule.register("fr_eng_document", "company_id");
            rule.register("fr_eng_plan_version", "company_id");
            rule.register("fr_eng_plan_node", "company_id");
            rule.register("fr_eng_site_record", "company_id");
            rule.register("fr_eng_closure", "company_id");
            rule.register("fr_eng_material", "company_id");
            rule.register("fr_eng_supply", "company_id");
            rule.register("fr_eng_supply_receipt", "company_id");
            rule.register("fr_eng_material_movement", "company_id");
            rule.register("fr_eng_collection_term", "company_id");
            rule.register("fr_eng_collection", "company_id");
            rule.register("fr_eng_followup", "company_id");
        };
    }
}
