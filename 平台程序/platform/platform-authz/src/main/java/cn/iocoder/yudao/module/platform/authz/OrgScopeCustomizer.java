package cn.iocoder.yudao.module.platform.authz;

/**
 * 业务域登记需要按组织过滤的表。实现为 Spring Bean 即生效，例如：
 * <pre>{@code
 * @Bean
 * OrgScopeCustomizer franchiseScope() {
 *     return rule -> {
 *         rule.register("fr_customer", "company_id");
 *         rule.register("fr_commission", "company_id", "design_org_id");
 *     };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface OrgScopeCustomizer {

    void customize(OrgScopeRule rule);

}
