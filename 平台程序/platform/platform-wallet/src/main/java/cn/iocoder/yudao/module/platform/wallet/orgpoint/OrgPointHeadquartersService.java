package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** B 总部公司积分管理。总部身份和目标公司在每个事务内重新核验。 */
@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class OrgPointHeadquartersService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrgPointService points;
    private final OrgPointPriceService prices;
    private final long headquartersTenantId;

    public OrgPointHeadquartersService(DataSource source, PlatformTransactionManager manager,
                                       OrgPointService points, OrgPointPriceService prices,
                                       @Value("${zhongshu.org-point.headquarters-tenant-id}") long headquartersTenantId) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(manager);
        this.points = points;
        this.prices = prices;
        if (headquartersTenantId <= 0) throw new IllegalArgumentException("公司积分总部 tenant 配置无效");
        this.headquartersTenantId = headquartersTenantId;
    }

    public record CompanyPool(long orgId, String name, String status, Long balance, Long inFlight) { }
    public record Adjustment(long id, long orgId, long delta, String reason, String status,
                             long makerId, Long checkerId, String checkerComment, Long resultBalance) { }

    public List<CompanyPool> companies(long actorId) {
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            return jdbc.query("""
                    SELECT u.org_id,d.name,a.status,a.balance,
                           COALESCE(SUM(CASE WHEN r.type='RESERVE' AND t.operation_id IS NULL
                                  THEN -r.available_delta ELSE 0 END),0) AS in_flight
                    FROM pf_org_unit u JOIN system_dept d ON d.id=u.dept_id
                    LEFT JOIN org_point_account a ON a.org_id=u.org_id
                    LEFT JOIN org_point_ledger r ON r.org_id=u.org_id AND r.type='RESERVE'
                    LEFT JOIN org_point_ledger t ON t.operation_id=r.operation_id AND t.type IN ('SETTLE','RELEASE')
                    WHERE u.kind='ORG' AND u.org_type='store' AND u.status='ACTIVE'
                      AND d.status=0 AND d.deleted=0 AND d.tenant_id=?
                    GROUP BY u.org_id,d.name,a.status,a.balance ORDER BY u.org_id
                    """, (rs, i) -> new CompanyPool(rs.getLong("org_id"), rs.getString("name"),
                    rs.getString("status"), (Long) rs.getObject("balance"), rs.getLong("in_flight")), scope.tenantId());
        });
    }

    public cn.iocoder.yudao.module.franchise.api.OrgPointPort.PoolSummary summary(long actorId, long orgId) {
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            requireActiveStore(scope.tenantId(), orgId);
            return points.summary(orgId);
        });
    }

    public List<cn.iocoder.yudao.module.franchise.api.OrgPointPort.LedgerEntry> ledger(
            long actorId, long orgId, int page, int size) {
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            requireActiveStore(scope.tenantId(), orgId);
            return points.ledger(orgId, null, null, null, page, size);
        });
    }

    public long submitAdjustment(long actorId, long orgId, long delta, String reason, String requestKey) {
        requireText(reason, 512);
        requireText(requestKey, 128);
        if (delta == 0 || delta == Long.MIN_VALUE) throw bad("调点数量无效");
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            requireActiveStore(scope.tenantId(), orgId);
            String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(orgId, actorId, delta, reason)));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 917))", requestKey);
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id,request_hash FROM org_point_hq_adjustment WHERE request_key=?", requestKey);
            if (!existing.isEmpty()) {
                if (!hash.equals(existing.get(0).get("request_hash"))) throw conflict();
                return ((Number) existing.get(0).get("id")).longValue();
            }
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO org_point_hq_adjustment(id,org_id,delta,reason,request_key,request_hash,maker_id) "
                            + "VALUES(?,?,?,?,?,?,?)", id, orgId, delta, reason, requestKey, hash, actorId);
            return id;
        });
    }

    public Long reviewAdjustment(long actorId, long requestId, boolean approve, String comment) {
        requireText(comment, 512);
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            Map<String, Object> row = one("SELECT * FROM org_point_hq_adjustment WHERE id=? FOR UPDATE", requestId);
            long maker = ((Number) row.get("maker_id")).longValue();
            if (maker == actorId) throw bad("提交人与复核人必须不同");
            long orgId = ((Number) row.get("org_id")).longValue();
            requireActiveStore(scope.tenantId(), orgId);
            String terminal = approve ? "EXECUTED" : "REJECTED";
            if (!"SUBMITTED".equals(row.get("status"))) {
                if (!terminal.equals(row.get("status"))
                        || !Objects.equals(((Number) row.get("checker_id")).longValue(), actorId)
                        || !Objects.equals(row.get("checker_comment"), comment)) throw conflict();
                return (Long) row.get("result_balance");
            }
            Long balance = null;
            if (approve) {
                long delta = ((Number) row.get("delta")).longValue();
                balance = points.adjustBalance(orgId, delta, "hq-adjust:" + requestId,
                        (String) row.get("reason"), actorId).balance();
            }
            int updated = jdbc.update("UPDATE org_point_hq_adjustment SET status=?,checker_id=?,checker_comment=?,"
                            + "result_balance=?,update_time=now() WHERE id=? AND status='SUBMITTED'",
                    terminal, actorId, comment, balance, requestId);
            if (updated != 1) throw conflict();
            return balance;
        });
    }

    public List<Adjustment> adjustments(long actorId, long orgId) {
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            requireActiveStore(scope.tenantId(), orgId);
            return jdbc.query("SELECT * FROM org_point_hq_adjustment WHERE org_id=? ORDER BY create_time DESC,id DESC LIMIT 100",
                    (rs, i) -> new Adjustment(rs.getLong("id"), rs.getLong("org_id"), rs.getLong("delta"),
                            rs.getString("reason"), rs.getString("status"), rs.getLong("maker_id"),
                            (Long) rs.getObject("checker_id"), rs.getString("checker_comment"),
                            (Long) rs.getObject("result_balance")), orgId);
        });
    }

    public List<OrgPointPriceService.CatalogRow> catalog(long actorId) {
        return required(() -> {
            lockHeadquartersActor(actorId);
            return prices.catalog();
        });
    }

    public long publishPrice(long actorId, String subject, long priceMicros, long minCharge,
                             String reason, String requestKey) {
        requireText(subject, 64);
        requireText(reason, 512);
        requireText(requestKey, 128);
        return required(() -> {
            Scope scope = lockHeadquartersActor(actorId);
            String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(
                    List.of(scope.tenantId(), actorId, subject, priceMicros, minCharge, reason)));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 918))", requestKey);
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT price_id,request_hash FROM org_point_hq_price_request WHERE request_key=?", requestKey);
            if (!existing.isEmpty()) {
                if (!hash.equals(existing.get(0).get("request_hash"))) throw conflict();
                return ((Number) existing.get(0).get("price_id")).longValue();
            }
            long priceId = prices.publish(subject, priceMicros, minCharge, Long.toString(actorId));
            jdbc.update("INSERT INTO org_point_price_audit(id,subject,price_id,price_micros,min_charge,actor_id,reason) "
                            + "VALUES(?,?,?,?,?,?,?)", IdWorker.getId(), subject, priceId, priceMicros,
                    minCharge, actorId, reason);
            jdbc.update("INSERT INTO org_point_hq_price_request(id,request_key,request_hash,tenant_id,actor_id,"
                            + "subject,price_micros,min_charge,reason,price_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    IdWorker.getId(), requestKey, hash, scope.tenantId(), actorId, subject, priceMicros,
                    minCharge, reason, priceId);
            return priceId;
        });
    }

    private Scope lockHeadquartersActor(long actorId) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId != headquartersTenantId) throw forbidden();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", actorId);
        List<Integer> allowed = jdbc.query("""
                SELECT 1 FROM system_users su
                JOIN pf_staff_post sp ON sp.user_id=su.id AND sp.status='ACTIVE'
                JOIN pf_org_unit ou ON ou.dept_id=sp.org_id AND ou.kind='ORG'
                     AND ou.org_type='headquarters' AND ou.status='ACTIVE'
                JOIN system_dept d ON d.id=ou.dept_id AND d.status=0 AND d.deleted=0
                WHERE su.id=? AND su.tenant_id=? AND su.status=0 AND su.deleted=0 AND d.tenant_id=?
                FOR SHARE OF su,sp,ou,d
                """, (rs, i) -> 1, actorId, tenantId, tenantId);
        if (allowed.isEmpty()) throw forbidden();
        return new Scope(tenantId);
    }

    private void requireActiveStore(long tenantId, long orgId) {
        List<Integer> allowed = jdbc.query("""
                SELECT 1 FROM pf_org_unit u JOIN system_dept d ON d.id=u.dept_id
                WHERE u.dept_id=? AND u.kind='ORG' AND u.org_type='store' AND u.status='ACTIVE'
                  AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                FOR SHARE OF u,d
                """, (rs, i) -> 1, orgId, tenantId);
        if (allowed.size() != 1) throw forbidden();
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.size() != 1) throw bad("记录不存在");
        return rows.get(0);
    }

    private <T> T required(java.util.function.Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    private static void requireText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max) throw bad("文本参数无效");
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException forbidden() {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(403, "总部公司积分权限范围无效");
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException bad(String message) {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(400, message);
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException conflict() {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(409, "请求幂等冲突");
    }

    private record Scope(long tenantId) { }
}
