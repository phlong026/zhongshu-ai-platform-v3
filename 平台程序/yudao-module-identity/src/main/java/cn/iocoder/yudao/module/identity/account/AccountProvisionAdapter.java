package cn.iocoder.yudao.module.identity.account;

import cn.iocoder.yudao.module.infra.zhongshu.api.AccountProvisionPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 账号供给适配器（P-5）：identity 是 account 表唯一写方。
 * 幂等源=external_key（UNIQUE）：ON CONFLICT DO NOTHING + 同事务查询；
 * 不复活已停用/关闭账号（active=false 由调用方换译，禁止惰性换建）。
 */
@Component
public class AccountProvisionAdapter implements AccountProvisionPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public AccountProvisionAdapter(DataSource dataSource,
                                   org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** fr-service: 外键前缀——组织服务账号（09 钱包会话桥接）：技术型 grant 的授予对象 */
    private static final String FR_SERVICE_PREFIX = "fr-service:";

    @Override
    public EnsuredAccount ensureActiveAccount(String externalKey, String nickname) {
        if (externalKey == null || externalKey.isBlank() || externalKey.length() > 64) {
            throw new IllegalArgumentException("external key invalid");
        }
        return txTemplate.execute(status -> {
            var existing = selectByKey(externalKey);
            if (existing != null) {
                ensureTechnicalGrant(externalKey, existing);
                return existing;
            }
            jdbcTemplate.update(
                    "INSERT INTO account (id, status, nickname, external_key, tenant_id) "
                            + "VALUES (?, 'ACTIVE', ?, ?, 0) ON CONFLICT (external_key) DO NOTHING",
                    IdWorker.getId(), nickname, externalKey);
            EnsuredAccount created = selectByKey(externalKey);
            if (created == null) throw new IllegalStateException("ACCOUNT_PROVISION_CONFLICT");
            ensureTechnicalGrant(externalKey, created);
            return created;
        });
    }

    /**
     * 技术型 grant 幂等补挂（09 钱包会话桥接）：服务账号的会话受限状态由 design_access_grant
     * 实时重算（validateAccessToken），无 ACTIVE grant 的会话连只读都被拒。仅对 fr-service:
     * 且账号 ACTIVE 时补挂；以 ACTIVE 为前提——停用账号（disableAccount 已撤 grant）不复活。
     * 管理侧要断钱包 = 停用账号；通用撤销入口对技术型 grant 有守卫（AccessGrantService）。
     */
    private void ensureTechnicalGrant(String externalKey, EnsuredAccount ensured) {
        if (!externalKey.startsWith(FR_SERVICE_PREFIX) || !ensured.active()) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO design_access_grant (id, account_id, status, tenant_id) "
                        + "SELECT ?, ?, 'ACTIVE', 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM design_access_grant "
                        + "WHERE account_id = ? AND status = 'ACTIVE' AND deleted = FALSE)",
                IdWorker.getId(), ensured.accountId(), ensured.accountId());
    }

    private EnsuredAccount selectByKey(String externalKey) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, status FROM account WHERE external_key = ? AND deleted = FALSE", externalKey);
        if (rows.isEmpty()) return null;
        long id = ((Number) rows.get(0).get("id")).longValue();
        return new EnsuredAccount(id, "ACTIVE".equals(rows.get(0).get("status")));
    }
}
