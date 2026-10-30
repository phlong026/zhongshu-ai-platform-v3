package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import org.springframework.stereotype.Component;

/** 客资与客户的表都只对归属门店可见 */
@Component
public class CrmOrgScope implements OrgScopeCustomizer {

    @Override
    public void customize(OrgScopeRule rule) {
        rule.register("fr_lead", "company_id");
        rule.register("fr_customer", "company_id");
        rule.register("fr_customer_follow", "company_id");
        rule.register("fr_customer_scheme", "company_id");
    }

}
