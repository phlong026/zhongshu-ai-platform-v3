package cn.iocoder.yudao.module.platform.wallet.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 总部运营只读取 B 持久事实。每个 SQL 自带 tenant 条件，不借用组织岗位钱包作用域。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class WalletOperationsService {
    private static final Logger log = LoggerFactory.getLogger(WalletOperationsService.class);
    private final JdbcTemplate jdbc;

    public record Wallet(String id, String orgId, String status, boolean spendingFrozen,
                         long availablePoints, long reservedPoints) { }
    public record Plan(String id, String name, long priceFen, long basePoints, long bonusPoints,
                       long version, String status, int sort, String wxProductId) { }
    public record Order(String id, String orgId, String walletId, String orderNo, String status,
                        String creditState, long priceFen, long basePoints, long bonusPoints, Instant createTime) { }
    public record Ledger(String id, String orgId, String walletId, String actorId, String type, long delta,
                         long availableAfter, long reservedAfter, String bizType, String bizId,
                         String reason, Instant createTime) { }
    public record Anomaly(String id, String orgId, String walletId, String orderId,
                          String anomalyType, String detail, Instant createTime) { }
    public record WalletSummary(long walletCount, long totalAvailablePoints, List<Wallet> topWallets) { }

    public WalletOperationsService(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
    }

    public PageResult<Wallet> wallets(int pageNo, int pageSize, String orgId) {
        return page("wallets", """
                SELECT w.id,w.org_id,w.status,w.spending_frozen,a.available_points,a.reserved_points
                FROM pf_wallet w JOIN pf_point_account a ON a.wallet_id=w.id AND a.tenant_id=w.tenant_id
                WHERE w.tenant_id=?
                """, "w.org_id", "w.id DESC", pageNo, pageSize, orgId,
                (rs, row) -> new Wallet(rs.getString("id"), rs.getString("org_id"), rs.getString("status"),
                        rs.getBoolean("spending_frozen"), rs.getLong("available_points"), rs.getLong("reserved_points")));
    }

    public WalletSummary walletSummary() {
        long tenant = requireTenant();
        try {
            var totals = jdbc.queryForObject("""
                    SELECT count(*) AS wallet_count,coalesce(sum(a.available_points),0) AS total_available_points
                    FROM pf_wallet w JOIN pf_point_account a ON a.wallet_id=w.id AND a.tenant_id=w.tenant_id
                    WHERE w.tenant_id=?
                    """, (rs, row) -> new long[] {rs.getLong("wallet_count"), rs.getLong("total_available_points")}, tenant);
            var topWallets = jdbc.query("""
                    SELECT w.id,w.org_id,w.status,w.spending_frozen,a.available_points,a.reserved_points
                    FROM pf_wallet w JOIN pf_point_account a ON a.wallet_id=w.id AND a.tenant_id=w.tenant_id
                    WHERE w.tenant_id=?
                    ORDER BY a.available_points DESC,w.id DESC LIMIT 10
                    """, (rs, row) -> new Wallet(rs.getString("id"), rs.getString("org_id"), rs.getString("status"),
                    rs.getBoolean("spending_frozen"), rs.getLong("available_points"), rs.getLong("reserved_points")), tenant);
            return new WalletSummary(totals[0], totals[1], List.copyOf(topWallets));
        } catch (DataAccessException error) {
            log.error("[platformOps][operation=walletSummary,tenant={}] 本地运营查询失败", tenant, error);
            throw error;
        }
    }

    public PageResult<Plan> plans(int pageNo, int pageSize) {
        return page("plans", "SELECT id,name,price_fen,base_points,bonus_points,version,status,sort,wx_product_id FROM pf_recharge_plan WHERE tenant_id=?",
                null, "sort,id DESC", pageNo, pageSize, null,
                (rs, row) -> new Plan(rs.getString("id"), rs.getString("name"), rs.getLong("price_fen"),
                        rs.getLong("base_points"), rs.getLong("bonus_points"), rs.getLong("version"),
                        rs.getString("status"), rs.getInt("sort"), rs.getString("wx_product_id")));
    }

    public PageResult<Order> orders(int pageNo, int pageSize, String orgId) {
        return page("orders", """
                SELECT id,org_id,wallet_id,order_no,payment_state,credit_state,price_fen,base_points,bonus_points,create_time
                FROM pf_recharge_order WHERE tenant_id=?
                """, "org_id", "create_time DESC,id DESC", pageNo, pageSize, orgId,
                (rs, row) -> new Order(rs.getString("id"), rs.getString("org_id"), rs.getString("wallet_id"),
                        rs.getString("order_no"), rs.getString("payment_state"), rs.getString("credit_state"),
                        rs.getLong("price_fen"), rs.getLong("base_points"), rs.getLong("bonus_points"), rs.getTimestamp("create_time").toInstant()));
    }

    public PageResult<Ledger> ledger(int pageNo, int pageSize, String orgId) {
        return page("ledger", """
                SELECT l.id,w.org_id,l.wallet_id,l.actor_id,l.type,l.delta,l.available_after,l.reserved_after,
                       l.biz_type,l.biz_id,l.reason,l.create_time
                FROM pf_point_ledger l JOIN pf_wallet w ON w.id=l.wallet_id AND w.tenant_id=l.tenant_id
                WHERE l.tenant_id=?
                """, "w.org_id", "l.create_time DESC,l.id DESC", pageNo, pageSize, orgId,
                (rs, row) -> new Ledger(rs.getString("id"), rs.getString("org_id"), rs.getString("wallet_id"),
                        rs.getString("actor_id"), rs.getString("type"), rs.getLong("delta"), rs.getLong("available_after"),
                        rs.getLong("reserved_after"), rs.getString("biz_type"), rs.getString("biz_id"),
                        rs.getString("reason"), rs.getTimestamp("create_time").toInstant()));
    }

    public PageResult<Anomaly> anomalies(int pageNo, int pageSize, String orgId) {
        return page("anomalies", """
                SELECT a.id,w.org_id,a.wallet_id,a.order_id,a.anomaly_type,a.detail,a.create_time
                FROM pf_payment_anomaly_audit a JOIN pf_wallet w ON w.id=a.wallet_id AND w.tenant_id=a.tenant_id
                WHERE a.tenant_id=?
                """, "w.org_id", "a.create_time DESC,a.id DESC", pageNo, pageSize, orgId,
                (rs, row) -> new Anomaly(rs.getString("id"), rs.getString("org_id"), rs.getString("wallet_id"),
                        rs.getString("order_id"), rs.getString("anomaly_type"), rs.getString("detail"), rs.getTimestamp("create_time").toInstant()));
    }

    private <T> PageResult<T> page(String operation, String sql, String orgColumn, String order,
                                  int pageNo, int pageSize, String orgId, RowMapper<T> mapper) {
        long tenant = requireTenant();
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw new ServiceException(400, "分页范围无效");
        var args = new ArrayList<Object>();
        args.add(tenant);
        if (orgId != null && !orgId.isBlank()) {
            args.add(parseId(orgId, "组织编号无效"));
            sql += " AND " + orgColumn + "=?";
        }
        try {
            Long total = jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") rows", Long.class, args.toArray());
            args.add(pageSize);
            args.add((long) (pageNo - 1) * pageSize);
            return new PageResult<>(jdbc.query(sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", mapper, args.toArray()), total);
        } catch (DataAccessException error) {
            log.error("[platformOps][operation={},tenant={}] 本地运营查询失败", operation, tenant, error);
            throw error;
        }
    }

    static long requireTenant() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || !Integer.valueOf(2).equals(user.getUserType())) {
            throw new ServiceException(403, "需要有效管理员身份");
        }
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || user.getTenantId() == null || !Objects.equals(user.getTenantId(), tenant)
                || TenantContextHolder.isIgnore()) throw new ServiceException(403, "管理员租户不匹配");
        return tenant;
    }

    static long parseId(String value, String message) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !Long.toString(id).equals(value)) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException error) {
            throw new ServiceException(400, message);
        }
    }
}
