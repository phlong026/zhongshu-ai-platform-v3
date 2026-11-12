package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/** 从实时岗位取得钱包，锁定到本地业务事务结束；不能从 HTTP 接收此对象。 */
@Component
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class WalletScope {

    record Wallet(long tenantId, long id, long orgId, long actorId, long postId, String roleCode) {
    }

    private final JdbcTemplate jdbc;
    private final CurrentPost currentPost;
    private final StaffPostService posts;

    public WalletScope(DataSource dataSource, CurrentPost currentPost, StaffPostService posts) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.currentPost = currentPost;
        this.posts = posts;
    }

    record Org(long tenantId, long id, long actorId, long postId, String roleCode) {
    }

    Org requireOrg() {
        requireTransaction();
        var selected = currentPost.require();
        long tenant = TenantContextHolder.getRequiredTenantId();
        posts.revalidateActivePost(selected.userId(), selected.postId());
        var post = posts.findActivePost(selected.userId(), selected.postId()).orElseThrow(WalletException::unavailable);
        if (post.orgId() != selected.orgId() || post.userId() != selected.userId()) {
            throw WalletException.unavailable();
        }
        // CurrentPost 和 MyBatis 同事务一级缓存均可能保留锁前快照；锁后用 JDBC 读真实行。
        var roles = jdbc.queryForList("""
                SELECT p.role_code FROM pf_staff_post p JOIN system_users u ON u.id = p.user_id
                WHERE p.id = ? AND p.user_id = ? AND p.org_id = ? AND p.status = 'ACTIVE'
                  AND u.tenant_id = ? AND u.status = 0 AND u.deleted = 0 FOR SHARE OF p, u
                """, String.class, post.postId(), post.userId(), post.orgId(), tenant);
        if (roles.size() != 1 || !roles.get(0).equals(post.roleCode())) throw WalletException.unavailable();
        // pf_org_unit 是无 tenant 的扩展表，租户归属必须回到 system_dept 校验。
        var orgs = jdbc.queryForList("""
                SELECT u.dept_id FROM pf_org_unit u JOIN system_dept d ON d.id = u.dept_id
                WHERE u.dept_id = ? AND u.org_id = u.dept_id AND u.kind = 'ORG'
                  AND u.status = 'ACTIVE' AND d.status = 0 AND d.deleted = 0 AND d.tenant_id = ?
                FOR SHARE OF u, d
                """, Long.class, post.orgId(), tenant);
        if (orgs.isEmpty()) {
            log.warn("[walletScope][tenant={} org={} actor={}] 组织不可用或租户不匹配", tenant, post.orgId(), post.userId());
            throw WalletException.unavailable();
        }
        return new Org(tenant, post.orgId(), post.userId(), post.postId(), post.roleCode());
    }

    Wallet require() {
        var org = requireOrg();
        long tenant = org.tenantId();
        jdbc.update("INSERT INTO pf_wallet (id, org_id, tenant_id) VALUES (?, ?, ?) ON CONFLICT (org_id) DO NOTHING",
                IdWorker.getId(), org.id(), tenant);
        var wallets = jdbc.query("""
                SELECT id FROM pf_wallet WHERE org_id = ? AND tenant_id = ? AND status = 'ACTIVE' FOR UPDATE
                """, (rs, row) -> new Wallet(tenant, rs.getLong("id"), org.id(), org.actorId(), org.postId(), org.roleCode()), org.id(), tenant);
        if (wallets.isEmpty()) throw WalletException.unavailable();
        var wallet = wallets.get(0);
        jdbc.update("INSERT INTO pf_point_account (wallet_id, tenant_id) VALUES (?, ?) ON CONFLICT (wallet_id) DO NOTHING",
                wallet.id(), tenant);
        return wallet;
    }

    static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Wallet operation requires a local transaction");
        }
    }
}
