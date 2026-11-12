package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/** Only injected into qualified service business beans, never the consumer/default AccountStatePort. */
public final class CapabilityServiceAccountGuard implements AccountStatePort {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    public CapabilityServiceAccountGuard(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public void requireActiveForWrite(long accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Service resource authorization requires the business transaction");
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ServicePrincipal principal)
                || principal.serviceAccountId() != accountId) {
            throw new SecurityException("Authenticated service principal required for resource writes");
        }
        var rows = jdbc.queryForList("SELECT service_account_id FROM design_capability_organization WHERE caller_app_id=? "
                        + "AND org_id=? AND tenant_id=? AND service_account_id=? AND active=true FOR SHARE", Long.class,
                principal.callerAppId(), principal.orgId(), principal.tenantId(), accountId);
        if (rows.size() != 1) throw new SecurityException("Service resource mapping is missing, disabled or mismatched");
    }
}
