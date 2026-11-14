package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_PRICE_CONFLICT;

/**
 * 公司池科目价格（D8 表化）：后台可调价，**预扣写快照**，调价只影响新任务。
 *
 * <p>模型：
 * <ul>
 *   <li>每科目至多一条 ACTIVE 价（数据库部分唯一索引兜底）；</li>
 *   <li>发布新版本 = 旧 ACTIVE 置 RETIRED + 插入新 ACTIVE（version 递增）；</li>
 *   <li>无任何配置时回退代码默认价 {@link BillingPriceCatalog}（v1）——保证开箱可用。</li>
 * </ul>
 */
@Service
public class OrgPointPriceService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrgPointPriceService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record PriceRow(long id, String subject, String unit, long priceMicros, long minCharge,
                           int version, String status, Instant effectiveAt, Instant retiredAt,
                           String operator, String retiredBy) {
    }

    /** 生效价（无配置返回 null → 调用方回退代码默认价）。 */
    public record ResolvedPrice(String unit, long priceMicros, long minCharge, String versionText) {
    }

    /** 科目现价视图：configured=true 表示后台配置生效，false 表示回退代码默认价。 */
    public record CatalogRow(String subject, String label, String unit, long priceMicros,
                             long minCharge, String versionText, boolean configured) {
    }

    /**
     * 全科目现价（含未配置科目 → 代码默认价）。后台价格页据此渲染完整清单，
     * 避免前端硬编码科目/默认价与 {@link BillingPriceCatalog} 漂移。
     */
    public List<CatalogRow> catalog() {
        Map<String, ResolvedPrice> active = new java.util.HashMap<>();
        jdbc.query("SELECT subject, unit, price_micros, min_charge, version FROM org_point_subject_price "
                        + "WHERE status = 'ACTIVE'",
                rs -> {
                    active.put(rs.getString("subject"), new ResolvedPrice(rs.getString("unit"),
                            rs.getLong("price_micros"), rs.getLong("min_charge"),
                            "db-v" + rs.getInt("version")));
                });
        return BillingPriceCatalog.all().stream().map(base -> {
            ResolvedPrice resolved = active.get(base.key());
            return new CatalogRow(base.key(), base.label(),
                    resolved == null ? base.unit().name() : resolved.unit(),
                    resolved == null ? base.priceMicrosPerUnit() : resolved.priceMicros(),
                    resolved == null ? base.minCharge() : resolved.minCharge(),
                    resolved == null ? base.priceVersion() : resolved.versionText(),
                    resolved != null);
        }).toList();
    }

    public ResolvedPrice resolve(String subject) {
        List<ResolvedPrice> rows = jdbc.query(
                "SELECT unit, price_micros, min_charge, version FROM org_point_subject_price "
                        + "WHERE subject = ? AND status = 'ACTIVE'",
                (rs, i) -> new ResolvedPrice(rs.getString("unit"), rs.getLong("price_micros"),
                        rs.getLong("min_charge"), "db-v" + rs.getInt("version")), subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PriceRow> list(String subject) {
        String sql = "SELECT * FROM org_point_subject_price"
                + (subject == null || subject.isBlank() ? "" : " WHERE subject = ?")
                + " ORDER BY subject, version DESC";
        Object[] args = subject == null || subject.isBlank() ? new Object[0] : new Object[]{subject};
        return jdbc.query(sql, (rs, i) -> new PriceRow(rs.getLong("id"), rs.getString("subject"),
                rs.getString("unit"), rs.getLong("price_micros"), rs.getLong("min_charge"),
                rs.getInt("version"), rs.getString("status"),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getTimestamp("retired_at") == null ? null : rs.getTimestamp("retired_at").toInstant(),
                rs.getString("operator"), rs.getString("retired_by")), args);
    }

    /** 发布新版本价：旧 ACTIVE 退休 + 新 ACTIVE 插入（同事务；唯一索引兜底并发）。 */
    public long publish(String subject, long priceMicros, long minCharge, String operator) {
        BillingPriceCatalog.Subject base = BillingPriceCatalog.subject(subject); // 未知科目直接拒绝
        if (priceMicros <= 0) {
            throw new IllegalArgumentException("单价必须为正（微积分）：" + priceMicros);
        }
        // 上限保证 数量×单价 不溢出 long（否则会静默少扣积分）
        if (priceMicros > BillingPriceCatalog.MAX_PRICE_MICROS) {
            throw new IllegalArgumentException("单价超出上限（1 单位最多 "
                    + (BillingPriceCatalog.MAX_PRICE_MICROS / 1_000_000L) + " 积分）：" + priceMicros);
        }
        if (minCharge < 1) {
            throw new IllegalArgumentException("最低收费必须 ≥1：" + minCharge);
        }
        if (minCharge > BillingPriceCatalog.MAX_MIN_CHARGE) {
            // minCharge 直接成为单笔预扣 N：封顶可防止脏配置制造异常巨额单笔扣费。
            throw new IllegalArgumentException("最低收费超出上限（" + BillingPriceCatalog.MAX_MIN_CHARGE
                    + " 积分）：" + minCharge);
        }
        String unit = base.unit().name();
        try {
            return tx.execute(status -> {
                // 同科目发布串行化（事务级 advisory lock）：先锁再读 MAX，避免两笔并发都算出同一 version。
                // 只影响同一科目的发布/调价，不阻塞预扣计费。
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",
                        "org_point_price:" + subject);
                Integer maxVersion = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version), 0) FROM org_point_subject_price WHERE subject = ?",
                        Integer.class, subject);
                int next = (maxVersion == null ? 0 : maxVersion) + 1;
                jdbc.update("UPDATE org_point_subject_price SET status = 'RETIRED', retired_at = now(), "
                        + "retired_by = ? WHERE subject = ? AND status = 'ACTIVE'", operator, subject);
                long id = IdWorker.getId();
                jdbc.update("INSERT INTO org_point_subject_price (id, subject, unit, price_micros, "
                                + "min_charge, version, status, operator) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                        id, subject, unit, priceMicros, minCharge, next, operator);
                return id;
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一键兜底（uk_ops_price_active / uk_ops_price_version）：转稳定业务码，绝不静默覆盖
            throw exception(ORG_PRICE_CONFLICT);
        }
    }

    /** 停用某条 ACTIVE 价（停用后回退代码默认价；历史任务仍按快照结算）。 */
    public void retire(long id, String operator) {
        int updated = jdbc.update("UPDATE org_point_subject_price SET status = 'RETIRED', "
                + "retired_at = now(), retired_by = ? WHERE id = ? AND status = 'ACTIVE'", operator, id);
        if (updated == 0) {
            throw exception(ORG_PRICE_CONFLICT);
        }
    }
}
