package cn.iocoder.yudao.module.franchise.performance;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 绩效提成经营八表组织过滤注册（SQL 层追加 company_id = 当前组织；engineering 同惯例）。 */
@Configuration
public class PerformanceOrgScope {

    @Bean
    public OrgScopeCustomizer performanceOrgScopeCustomizer() {
        return rule -> {
            rule.register("fr_perf_goal", "company_id");
            rule.register("fr_perf_task", "company_id");
            rule.register("fr_perf_task_archive", "company_id");
            rule.register("fr_perf_cycle", "company_id");
            rule.register("fr_perf_commission_policy", "company_id");
            rule.register("fr_perf_commission_release", "company_id");
            rule.register("fr_perf_reconciliation", "company_id");
            rule.register("fr_perf_operating_snapshot", "company_id");
        };
    }
}
