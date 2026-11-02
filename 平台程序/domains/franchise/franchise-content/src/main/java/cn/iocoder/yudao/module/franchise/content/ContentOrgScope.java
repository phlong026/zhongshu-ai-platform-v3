package cn.iocoder.yudao.module.franchise.content;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 内容创作获客九表组织过滤注册（SQL 层追加 company_id = 当前组织；sales/crm 同惯例）。 */
@Configuration
public class ContentOrgScope {

    @Bean
    public OrgScopeCustomizer contentOrgScopeCustomizer() {
        return rule -> {
            rule.register("fr_content_script", "company_id");
            rule.register("fr_content_task", "company_id");
            rule.register("fr_content_draft", "company_id");
            rule.register("fr_content_video_draft", "company_id");
            rule.register("fr_content_course_progress", "company_id");
            rule.register("fr_content_persona", "company_id");
            rule.register("fr_content_publication", "company_id");
            rule.register("fr_content_provider_call", "company_id");
            rule.register("fr_content_ai_usage", "org_id");
        };
    }
}
