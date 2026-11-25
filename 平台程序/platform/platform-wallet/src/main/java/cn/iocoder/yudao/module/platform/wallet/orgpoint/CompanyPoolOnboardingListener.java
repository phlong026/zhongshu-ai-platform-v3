package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.platform.org.CompanyAccountRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the company's internal quota pool in the same transaction as account registration. */
@Component
public class CompanyPoolOnboardingListener {
    private final JdbcTemplate jdbc;
    private final OrgPointService points;

    public CompanyPoolOnboardingListener(JdbcTemplate jdbc, OrgPointService points) {
        this.jdbc = jdbc;
        this.points = points;
    }

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void registered(CompanyAccountRegistered event) {
        // The event is internal. Still verify the real tenant and organization before creating money-adjacent state.
        boolean valid = event.tenantId() > 0 && event.orgId() > 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pf_org_unit u JOIN system_dept d ON d.id=u.dept_id
                    WHERE u.dept_id=? AND u.org_id=u.dept_id AND u.kind='ORG' AND u.status='ACTIVE'
                      AND u.org_type IN ('store','headquarters') AND d.tenant_id=? AND d.status=0 AND d.deleted=0)
                """, Boolean.class, event.orgId(), event.tenantId()));
        if (!valid) throw new IllegalArgumentException("公司额度开户的组织或租户无效");
        // Existing balances and suspended status remain unchanged. Registration grants no invented promotional quota.
        points.ensurePool(event.orgId());
    }
}
