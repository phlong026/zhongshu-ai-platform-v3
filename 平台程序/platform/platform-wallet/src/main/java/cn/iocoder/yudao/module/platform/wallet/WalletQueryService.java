package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Service
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class WalletQueryService {

    public record Account(String walletId, String orgId, String status, long availablePoints,
                          long reservedPoints, long version) {
    }

    public record LedgerItem(String id, String type, long delta, long availableAfter, long reservedAfter,
                             String bizType, String bizId, String actorId, String reason, Instant createTime) {
    }

    public record RechargePlan(String id, String name, long priceFen, long basePoints, long bonusPoints) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;

    public WalletQueryService(DataSource dataSource, PlatformTransactionManager transactionManager, WalletScope scope) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.scope = scope;
    }

    public Account account() {
        return query("account", () -> {
            var wallet = scope.require();
            return jdbc.queryForObject("""
                    SELECT a.available_points, a.reserved_points, a.version, w.spending_frozen
                    FROM pf_point_account a JOIN pf_wallet w ON w.id = a.wallet_id AND w.tenant_id = a.tenant_id
                    WHERE a.tenant_id = ? AND a.wallet_id = ?
                    """, (rs, row) -> new Account(Long.toString(wallet.id()), Long.toString(wallet.orgId()),
                    rs.getBoolean("spending_frozen") ? "FROZEN" : "ACTIVE",
                    rs.getLong("available_points"), rs.getLong("reserved_points"), rs.getLong("version")),
                    wallet.tenantId(), wallet.id());
        });
    }

    public PageResult<LedgerItem> ledger(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw WalletException.invalidRequest();
        return query("ledger", () -> {
            var wallet = scope.require();
            long total = jdbc.queryForObject("SELECT count(*) FROM pf_point_ledger WHERE tenant_id = ? AND wallet_id = ?",
                    Long.class, wallet.tenantId(), wallet.id());
            var list = jdbc.query("""
                    SELECT id, type, delta, available_after, reserved_after, biz_type, biz_id, actor_id, reason, create_time
                    FROM pf_point_ledger WHERE tenant_id = ? AND wallet_id = ?
                    ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?
                    """, (rs, row) -> new LedgerItem(Long.toString(rs.getLong("id")), rs.getString("type"),
                    rs.getLong("delta"), rs.getLong("available_after"), rs.getLong("reserved_after"),
                    rs.getString("biz_type"), rs.getString("biz_id"), Long.toString(rs.getLong("actor_id")),
                    rs.getString("reason"), rs.getTimestamp("create_time").toInstant()),
                    wallet.tenantId(), wallet.id(), pageSize, (long) (pageNo - 1) * pageSize);
            return new PageResult<>(list, total);
        });
    }

    public List<RechargePlan> rechargePlans() {
        return query("rechargePlans", () -> {
            var org = scope.requireOrg();
            return jdbc.query("""
                    SELECT id, name, price_fen, base_points, bonus_points FROM pf_recharge_plan
                    WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY sort, id LIMIT 100
                    """, (rs, row) -> new RechargePlan(Long.toString(rs.getLong("id")), rs.getString("name"),
                    rs.getLong("price_fen"), rs.getLong("base_points"), rs.getLong("bonus_points")), org.tenantId());
        });
    }

    private <T> T query(String operation, Supplier<T> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (DataAccessException e) {
            log.error("[walletQuery][operation={}] 钱包数据库查询失败", operation, e);
            throw e;
        }
    }
}
