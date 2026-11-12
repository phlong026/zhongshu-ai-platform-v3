package cn.iocoder.yudao.module.platform.wallet;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 当前岗位钱包内双人调点；不会改写任何支付、退款事实，也没有跨钱包管理员捷径。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class ManualPointAdjustmentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;
    private final PointAccountService points;
    private final RechargePaymentService payment;

    public ManualPointAdjustmentService(DataSource source, PlatformTransactionManager manager, WalletScope scope,
                                        PointAccountService points, RechargePaymentService payment) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(manager);
        this.scope = scope;
        this.points = points;
        this.payment = payment;
    }

    public long submit(long delta, String reason, String key) {
        if (delta == 0 || delta == Long.MIN_VALUE) throw WalletException.invalidRequest();
        PointAccountService.requireText(reason, 512);
        PointAccountService.requireText(key, 128);
        return transaction("submit", () -> {
            var wallet = scope.require();
            payment.requirePermission(wallet, "platform:wallet:adjustment:submit");
            if (delta < 0) requireSpendingAllowed(wallet);
            String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(wallet.tenantId(), wallet.id(), wallet.actorId(), delta, reason)));
            var rows = jdbc.queryForList("SELECT id, request_hash FROM pf_manual_point_adjustment WHERE tenant_id = ? AND wallet_id = ? AND request_key = ?",
                    wallet.tenantId(), wallet.id(), key);
            if (!rows.isEmpty()) {
                if (!hash.equals(rows.get(0).get("request_hash"))) throw WalletException.conflict();
                return ((Number) rows.get(0).get("id")).longValue();
            }
            long id = IdWorker.getId();
            jdbc.update("""
                    INSERT INTO pf_manual_point_adjustment (id, tenant_id, wallet_id, maker_id, delta, reason, request_key, request_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, wallet.tenantId(), wallet.id(), wallet.actorId(), delta, reason, key, hash);
            log.warn("[walletAdjustment][tenant={} wallet={} adjustment={} actor={}] 人工调点已提交复核", wallet.tenantId(), wallet.id(), id, wallet.actorId());
            return id;
        });
    }

    public Long review(long adjustmentId, boolean approve, String comment) {
        PointAccountService.requireText(comment, 512);
        return transaction("review", () -> {
            var wallet = scope.require();
            payment.requirePermission(wallet, "platform:wallet:adjustment:review");
            var rows = jdbc.queryForList("SELECT * FROM pf_manual_point_adjustment WHERE tenant_id = ? AND wallet_id = ? AND id = ? FOR UPDATE",
                    wallet.tenantId(), wallet.id(), adjustmentId);
            if (rows.isEmpty()) throw WalletException.unavailable();
            var row = rows.get(0);
            if (((Number) row.get("maker_id")).longValue() == wallet.actorId()) throw WalletException.unavailable();
            String terminal = approve ? "EXECUTED" : "REJECTED";
            if (!"SUBMITTED".equals(row.get("status"))) {
                if (!terminal.equals(row.get("status")) || ((Number) row.get("checker_id")).longValue() != wallet.actorId()
                        || !Objects.equals(comment, row.get("checker_comment"))) throw WalletException.conflict();
                return row.get("ledger_id") == null ? null : ((Number) row.get("ledger_id")).longValue();
            }
            Long ledger = null;
            if (approve) {
                long delta = ((Number) row.get("delta")).longValue();
                String key = "manual:" + adjustmentId;
                if (delta > 0) {
                    ledger = points.credit(wallet, "MANUAL_CREDIT", delta, "MANUAL_ADJUSTMENT", Long.toString(adjustmentId), key, (String) row.get("reason"));
                } else {
                    requireSpendingAllowed(wallet);
                    points.reserve(wallet, Math.negateExact(delta));
                    ledger = points.consumeReserve(wallet, "MANUAL_DEBIT", Math.negateExact(delta), "MANUAL_ADJUSTMENT",
                            Long.toString(adjustmentId), key, (String) row.get("reason"));
                }
            }
            jdbc.update("""
                    UPDATE pf_manual_point_adjustment SET status = ?, checker_id = ?, checker_comment = ?, ledger_id = ?, update_time = now()
                    WHERE tenant_id = ? AND wallet_id = ? AND id = ? AND status = 'SUBMITTED'
                    """, terminal, wallet.actorId(), comment, ledger, wallet.tenantId(), wallet.id(), adjustmentId);
            log.warn("[walletAdjustment][tenant={} wallet={} adjustment={} checker={} state={}] 人工调点复核已落账",
                    wallet.tenantId(), wallet.id(), adjustmentId, wallet.actorId(), terminal);
            return ledger;
        });
    }

    private void requireSpendingAllowed(WalletScope.Wallet wallet) {
        // scope.require持有钱包锁；双人审批不能绕过外部退款追偿造成的支出冻结。
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT spending_frozen FROM pf_wallet WHERE tenant_id = ? AND id = ?",
                Boolean.class, wallet.tenantId(), wallet.id()))) throw WalletException.invalidState();
    }

    private <T> T transaction(String operation, Supplier<T> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (DataAccessException failure) {
            log.error("[walletAdjustment][operation={} failureType={}] 数据库事务失败", operation, failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
