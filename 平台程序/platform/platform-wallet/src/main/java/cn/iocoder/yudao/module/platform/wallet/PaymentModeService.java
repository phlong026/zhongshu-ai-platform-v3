package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * System-wide payment mode registry. REAL is deliberately fail-closed until both
 * production adapters and their readiness checks are integrated.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class PaymentModeService {
    public record Snapshot(PaymentMode mode, long version, Instant updatedAt, String updatedBy, String reason) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PaymentModeService(DataSource source, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(10);
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    public Snapshot get() {
        return jdbc.queryForObject("""
            SELECT mode, version, updated_at, updated_by, reason
            FROM pf_payment_runtime_mode WHERE id = 1
            """, (rs, row) -> new Snapshot(PaymentMode.parse(rs.getString("mode")),
                rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"), rs.getString("reason")));
    }

    public Snapshot change(String requestedMode, String reason, long expectedVersion) {
        PaymentMode next = PaymentMode.parse(requestedMode);
        if (reason == null || reason.isBlank() || reason.trim().length() > 512)
            throw new IllegalArgumentException("切换原因必须填写，且不能超过512个字符");
        if (expectedVersion < 1) throw new IllegalArgumentException("配置版本无效");
        if (next == PaymentMode.REAL) {
            // Do not allow a UI switch to route money to a partially configured channel.
            throw new ServiceException(1_120_000_020, "真实支付通道尚未完成双渠道配置验证，当前仅允许模拟支付");
        }
        return tx.execute(status -> {
            Snapshot before = getForUpdate();
            if (before.version() != expectedVersion)
                throw new ServiceException(1_120_000_021, "支付配置已被其他管理员修改，请刷新后重试");
            long actor = SecurityFrameworkUtils.getLoginUserId();
            long version = before.version() + 1;
            jdbc.update("""
                UPDATE pf_payment_runtime_mode
                SET mode=?, version=?, updated_at=now(), updated_by=?, reason=?
                WHERE id=1 AND version=?
                """, next.name(), version, Long.toString(actor), reason.trim(), expectedVersion);
            jdbc.update("""
                INSERT INTO pf_payment_runtime_mode_audit
                  (id, config_version, old_mode, new_mode, actor_id, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, IdWorker.getId(), version, before.mode().name(), next.name(), actor, reason.trim());
            return get();
        });
    }

    private Snapshot getForUpdate() {
        return jdbc.queryForObject("""
            SELECT mode, version, updated_at, updated_by, reason
            FROM pf_payment_runtime_mode WHERE id=1 FOR UPDATE
            """, (rs, row) -> new Snapshot(PaymentMode.parse(rs.getString("mode")),
                rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("updated_by"), rs.getString("reason")));
    }
}
