package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 所有设计写命令顺序：命令锁→重放→阶段锁→行锁。预读只解析不可变来源，不持行锁。 */
@Component
public class DesignFinanceLocks {
    private final JdbcTemplate jdbc;
    public DesignFinanceLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void command(PostSnapshot actor, String key) {
        activeTransaction();
        String normalized = FranchiseCommandKeys.normalize(key);
        lock(FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise", actor.userId()), normalized));
    }

    public void stage(long commissionId, int version, String stage) {
        activeTransaction();
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) s", Integer.class,
                "design-fee:" + commissionId + ":" + version + ":" + stage.toUpperCase(java.util.Locale.ROOT));
    }

    public void source(String type, String sourceRef) {
        if (!"design".equals(type)) return;
        String[] parts = sourceRef == null ? new String[0] : sourceRef.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("设计费用来源格式错误");
        stage(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), parts[2]);
    }

    public void payment(long paymentId) {
        jdbc.query("SELECT p.source_type,p.source_ref FROM fr_fin_payment m JOIN fr_fin_payable p ON p.id=m.payable_id WHERE m.id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> source(rs.getString(1),rs.getString(2)), paymentId);
    }

    public void receipt(long receiptId) {
        jdbc.query("SELECT source_type,source_ref FROM fr_fin_receipt WHERE id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> source(rs.getString(1),rs.getString(2)), receiptId);
    }

    public boolean designPayment(long paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_fin_payment m JOIN fr_fin_payable p ON p.id=m.payable_id WHERE m.id=? AND p.source_type='design')", Boolean.class,paymentId));
    }

    public boolean designReceipt(long receiptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_fin_receipt WHERE id=? AND source_type='design')", Boolean.class,receiptId));
    }

    public boolean hasNetDesignReceipts(long paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT COALESCE(sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents)
                FROM fr_fin_refund f WHERE f.receipt_id=r.id AND f.status='已核实'),0)),0)>0
            FROM fr_fin_receipt r WHERE r.origin_payment_id=? AND r.source_type='design' AND r.status='已登记'
            """,Boolean.class,paymentId));
    }

    public void requireExpenseRefundVoidCapacity(long paymentId,long restoredCents) {
        var remaining=jdbc.queryForList("""
            SELECT p.total_cents-COALESCE((SELECT sum(m.amount_cents-COALESCE(m.pending_link_cents,0)
                -COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实'),0))
                FROM fr_fin_payment m WHERE m.payable_id=p.id AND m.status='已核实'),0)
            FROM fr_fin_payment origin JOIN fr_fin_payable p ON p.id=origin.payable_id
            WHERE origin.id=? AND origin.status='已核实' AND p.source_type='design'
            """,Long.class,paymentId);
        if(remaining.isEmpty() || restoredCents>remaining.get(0)) throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.PAYMENT_EXCEED_PAYABLE);
    }

    private void lock(long key) { jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,key); }
    public static void activeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("设计结算必须加入业务事务");
    }
}
