package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** B 资产服务资源映射；不是登录账号，禁止创建旧 account/designgrant 或发行会话。 */
@Service
@Slf4j
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class LocalStoreServiceAccountService implements StoreServiceAccountPort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;

    public LocalStoreServiceAccountService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                          WalletScope scope) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.scope = scope;
    }

    /** 仅供持久化任务恢复调用：调用方必须从可信任务取得租户和组织，不接受 HTTP 参数。 */
    @Override
    public long requireExistingServiceAccountForTask(long tenantId, long orgId) {
        try {
            var ids = jdbc.queryForList("""
                    SELECT a.id FROM pf_org_service_account a
                    JOIN pf_org_unit u ON u.dept_id = a.org_id AND u.kind = 'ORG' AND u.org_id = u.dept_id
                    JOIN system_dept d ON d.id = u.dept_id
                    WHERE a.tenant_id = ? AND a.org_id = ? AND d.tenant_id = a.tenant_id
                      AND a.status = 'ACTIVE' AND u.status = 'ACTIVE' AND d.status = 0 AND d.deleted = 0
                    """, Long.class, tenantId, orgId);
            if (ids.isEmpty()) throw new ServiceAccountInactiveException(orgId);
            return ids.get(0);
        } catch (DataAccessException e) {
            log.error("[serviceMappingTask][tenant={} org={}] 读取任务服务资源映射失败", tenantId, orgId, e);
            throw e;
        }
    }

    @Override
    public long requireServiceAccount(long orgId) {
        try {
            return tx.execute(status -> {
                var org = scope.requireOrg();
                if (org.id() != orgId) throw new ServiceAccountInactiveException(orgId);
                jdbc.update("""
                        INSERT INTO pf_org_service_account (id, org_id, tenant_id) VALUES (?, ?, ?)
                        ON CONFLICT (org_id) DO NOTHING
                        """, IdWorker.getId(), orgId, org.tenantId());
                var ids = jdbc.queryForList("""
                        SELECT id FROM pf_org_service_account
                        WHERE org_id = ? AND tenant_id = ? AND status = 'ACTIVE' FOR SHARE
                        """, Long.class, orgId, org.tenantId());
                if (ids.isEmpty()) {
                    log.warn("[serviceMapping][tenant={} org={}] 服务资源映射不可用", org.tenantId(), orgId);
                    throw new ServiceAccountInactiveException(orgId);
                }
                return ids.get(0);
            });
        } catch (DataAccessException e) {
            log.error("[serviceMapping][org={}] 服务资源映射数据库事务失败", orgId, e);
            throw e;
        }
    }
}
