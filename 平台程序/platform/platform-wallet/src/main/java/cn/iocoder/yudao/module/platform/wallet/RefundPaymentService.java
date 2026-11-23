package cn.iocoder.yudao.module.platform.wallet;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 整单退款：占位与冻结原子提交，外呼无锁，可信逐笔终态才释放或冲正。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class RefundPaymentService {
    public record RefundResponse(String id, String refundNo, String channelState, String pointState) { }
    private record Claim(RechargePaymentService.OrderSnapshot order, long id, String refundNo,
                         String token, int reason, RefundResponse replay) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;
    private final PointAccountService points;
    private final RechargePaymentService payment;
    private final PaymentFactValidator validator;

    public RefundPaymentService(DataSource source, PlatformTransactionManager manager, WalletScope scope,
                                PointAccountService points, RechargePaymentService payment, PaymentFactValidator validator) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(manager);
        this.scope = scope;
        this.points = points;
        this.payment = payment;
        this.validator = validator;
    }

    public RefundResponse requestRefund(long orderId, String key, int reason) {
        requireNoTransaction();
        PointAccountService.requireText(key, 128);
        if (reason < 0 || reason > 5) throw WalletException.invalidRequest();
        Claim claim = transaction("claimRefund", () -> {
            var wallet = scope.require();
            payment.requirePermission(wallet, "platform:wallet:refund");
            var order = payment.order(wallet.tenantId(), wallet.id(), orderId);
            PaymentPort orderChannel = payment.requireChannel(order.channel());
            if (orderChannel instanceof VirtualPaymentAdapter adapter) throw adapter.refundSubmissionUnavailable();
            String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(wallet.tenantId(), wallet.id(),
                    wallet.actorId(), orderId, reason)));
            var replay = jdbc.queryForList("SELECT * FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND request_key = ?",
                    wallet.tenantId(), wallet.id(), key);
            if (!replay.isEmpty()) {
                if (!hash.equals(replay.get(0).get("request_hash"))) throw WalletException.conflict();
                return new Claim(order, 0, null, null, reason, response(replay.get(0)));
            }
            if (!"SUCCEEDED".equals(order.paymentState()) || !"CREDITED".equals(order.creditState())) throw WalletException.invalidState();
            var types = jdbc.queryForList("SELECT order_type FROM pf_payment_transaction WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?",
                    Integer.class, wallet.tenantId(), wallet.id(), orderId);
            if (types.size() != 1 || !Integer.valueOf(0).equals(types.get(0))) throw WalletException.invalidState();
            long busy = jdbc.queryForObject("SELECT count(*) FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND channel_state <> 'FAILED'",
                    Long.class, wallet.tenantId(), wallet.id(), orderId);
            if (busy != 0) throw WalletException.conflict();
            long id = IdWorker.getId();
            reserveRefund(order,id);
            String no = "PFR" + id;
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO pf_refund_order (id, tenant_id, wallet_id, order_id, refund_no, origin, actor_id,
                        request_key, request_hash, amount_fen, base_points, bonus_points, reason,
                        channel, appid, offer_id, environment, channel_state, point_state, lease_token, lease_until)
                    VALUES (?, ?, ?, ?, ?, 'MERCHANT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNKNOWN', 'RESERVED', ?, clock_timestamp() + interval '60 seconds')
                    """, id, wallet.tenantId(), wallet.id(), orderId, no, wallet.actorId(), key, hash, order.priceFen(),
                    order.basePoints(), order.bonusPoints(), reason, order.channel(), order.appid(), order.offerId(), order.environment(), token);
            return new Claim(order, id, no, token, reason, null);
        });
        if (claim.replay() != null) return claim.replay();
        PaymentPort channel = payment.requireChannel(claim.order().channel());
        if (channel instanceof VirtualPaymentAdapter adapter) throw adapter.refundSubmissionUnavailable();
        PaymentPort.ChannelRefundResult result;
        try {
            result = channel.requestRefund(new PaymentPort.RefundRequest(claim.order().identity(), claim.refundNo(),
                    claim.order().priceFen(), reason, null));
        } catch (RuntimeException failure) {
            // 退款请求可能已到达渠道；保留占位和冻结，绝不能从异常推导失败或重发。
            log.warn("[walletRefund][tenant={} wallet={} refund={} failureType={}] 退款请求结果未知",
                    claim.order().tenantId(), claim.order().walletId(), claim.id(), failure.getClass().getSimpleName());
            result = null;
        }
        final var received = result;
        return transaction("refundAcknowledgement", () -> {
            var order = claim.order();
            payment.lockWallet(order.tenantId(), order.walletId());
            var row = refund(order.tenantId(), order.walletId(), claim.refundNo());
            if (((Number) row.get("order_id")).longValue() != order.id()) throw WalletException.conflict();
            // 已受理的渠道号是不可变事实，不因本地lease过期而丢弃；受理本身不能证明用户到账。
            String channelId = received != null && order.orderNo().equals(received.orderNo())
                    && claim.refundNo().equals(received.refundNo()) ? received.channelRefundId() : null;
            if (channelId != null) {
                PointAccountService.requireText(channelId, 128);
                int recorded = jdbc.update("""
                        UPDATE pf_refund_order SET channel_refund_id = ?, update_time = now()
                        WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ? AND refund_no = ?
                          AND (channel_refund_id IS NULL OR channel_refund_id = ?)
                        """, channelId, order.tenantId(), order.walletId(), order.id(), claim.id(), claim.refundNo(), channelId);
                if (recorded != 1) throw WalletException.conflict();
            }
            // 迟到ACK只补渠道号；不得释放新worker的租约或回退已确认的退款终态。
            jdbc.update("""
                    UPDATE pf_refund_order SET channel_state = CASE WHEN channel_refund_id IS NULL THEN 'UNKNOWN' ELSE 'PROCESSING' END,
                        lease_token = NULL, lease_until = NULL, next_attempt_at = clock_timestamp() + interval '30 seconds', update_time = now()
                    WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ? AND lease_token = ? AND lease_until > clock_timestamp()
                      AND channel_state IN ('UNKNOWN','PROCESSING')
                    """, order.tenantId(), order.walletId(), order.id(), claim.id(), claim.token());
            return response(refund(order.tenantId(), order.walletId(), claim.refundNo()));
        });
    }

    /** 仅接受可信逐笔查单事实；无控制器入口，不能传入通知体伪造此对象。 */
    public void processRefundFact(long tenantId, long walletId, long orderId, PaymentPort.ChannelRefundResult fact) {
        requireNoTransaction();
        apply(tenantId, walletId, orderId, fact, null);
    }

    void apply(long tenantId, long walletId, long orderId, PaymentPort.ChannelRefundResult fact, String lease) {
        requireNoTransaction();
        boolean accepted = transaction("refundFact", () -> {
            payment.lockWallet(tenantId, walletId);
            var order = payment.order(tenantId, walletId, orderId);
            if (fact == null) throw WalletException.invalidRequest();
            PointAccountService.requireText(fact.refundNo(), 128);
            var existing = jdbc.queryForList("SELECT * FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND refund_no = ? FOR UPDATE",
                    tenantId, walletId, orderId, fact.refundNo());
            Map<String, Object> row = existing.isEmpty() ? null : existing.get(0);
            if (lease != null && (row == null || !lease.equals(row.get("lease_token"))
                    || !leaseFresh(tenantId, walletId, ((Number) row.get("id")).longValue()))) return false;
            var request = new PaymentPort.RefundRequest(order.identity(), fact.refundNo(), order.priceFen(),
                    row == null ? 0 : ((Number) row.get("reason")).intValue(), row == null ? null : (String) row.get("channel_refund_id"));
            validator.validateRefund(request, fact);
            boolean succeeded = "SUCCEEDED".equals(fact.state());
            boolean failed = "FAILED".equals(fact.state());
            if (succeeded && !Integer.valueOf(8).equals(fact.orderStatus())
                    || failed && !Integer.valueOf(7).equals(fact.orderStatus())) throw new IllegalArgumentException("Refund terminal status conflicts with per-refund query");
            if (!succeeded && !failed && !"PROCESSING".equals(fact.state()) && !"UNKNOWN".equals(fact.state())) throw WalletException.invalidState();
            if (row == null) {
                // 外部退款必须先被可信渠道证实成功，通知金额/RetCode 不构成此证据。
                if (!succeeded) return false;
                long id = IdWorker.getId();
                jdbc.update("""
                        INSERT INTO pf_refund_order (id, tenant_id, wallet_id, order_id, refund_no, origin, actor_id,
                            request_key, request_hash, amount_fen, base_points, bonus_points, reason,
                            channel, appid, offer_id, environment, channel_refund_id, channel_state, point_state, order_type, order_status)
                        VALUES (?, ?, ?, ?, ?, 'EXTERNAL', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 'SUCCEEDED', 'BLOCKED', ?, 8)
                        """, id, tenantId, walletId, orderId, fact.refundNo(), order.actorId(), "external:" + id,
                        DigestUtil.sha256Hex(fact.channelRefundId()), order.priceFen(), order.basePoints(), order.bonusPoints(),
                        order.channel(), order.appid(), order.offerId(), order.environment(), fact.channelRefundId(), fact.orderType());
                row = refund(tenantId, walletId, fact.refundNo());
            } else if ("SUCCEEDED".equals(row.get("channel_state")) || "FAILED".equals(row.get("channel_state"))) {
                if (!fact.state().equals(row.get("channel_state"))) throw WalletException.conflict();
                return true;
            }
            String pointState = (String) row.get("point_state");
            long total = Math.addExact(order.basePoints(), order.bonusPoints());
            if (succeeded && !"RESERVED".equals(pointState) && "CREDITED".equals(order.creditState())) {
                long available = order.company()
                        ? jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=? FOR UPDATE",Long.class,order.orgId())
                        : jdbc.queryForObject("SELECT available_points FROM pf_point_account WHERE tenant_id = ? AND wallet_id = ? FOR UPDATE",Long.class,tenantId,walletId);
                boolean frozen = order.company()
                        ? !Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='ACTIVE' FROM org_point_account WHERE org_id=?",Boolean.class,order.orgId()))
                        : Boolean.TRUE.equals(jdbc.queryForObject("SELECT spending_frozen FROM pf_wallet WHERE tenant_id = ? AND id = ?", Boolean.class, tenantId, walletId));
                if (available >= total && !frozen) {
                    reserveRefund(order,((Number)row.get("id")).longValue());
                    pointState = "RESERVED";
                } else {
                    pointState = "DEBT";
                    if (order.company()) jdbc.update("UPDATE org_point_account SET status='SUSPENDED',version=version+1,update_time=now() WHERE org_id=?",order.orgId());
                    else jdbc.update("UPDATE pf_wallet SET spending_frozen = TRUE, update_time = now() WHERE tenant_id = ? AND id = ?", tenantId, walletId);
                    jdbc.update("INSERT INTO pf_payment_anomaly_audit (id, tenant_id, wallet_id, order_id, anomaly_type, detail) VALUES (?, ?, ?, ?, 'REFUND_POINT_DEBT', '外部退款已到账，积分不足，冻结支出待追偿')",
                            IdWorker.getId(), tenantId, walletId, orderId);
                    log.warn("[walletRefund][tenant={} wallet={} order={}] 外部退款积分不足，已冻结支出", tenantId, walletId, orderId);
                }
            } else if (succeeded && !"RESERVED".equals(pointState)) {
                pointState = "BLOCKED";
                jdbc.update("UPDATE pf_recharge_order SET credit_state = 'BLOCKED', update_time = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                        tenantId, walletId, orderId);
            }
            int updated = jdbc.update("""
                    UPDATE pf_refund_order SET channel_state = ?, point_state = ?, channel_refund_id = ?, order_type = ?, order_status = ?,
                        lease_token = NULL, lease_until = NULL, next_attempt_at = clock_timestamp() + interval '60 seconds', update_time = now()
                    WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ?
                        AND (CAST(? AS VARCHAR) IS NULL OR (lease_token = ? AND lease_until > clock_timestamp()))
                    """, fact.state(), pointState, fact.channelRefundId(), fact.orderType(), fact.orderStatus(), tenantId, walletId, orderId, row.get("id"), lease, lease);
            if (updated != 1) throw WalletException.conflict();
            return true;
        });
        if (accepted) reconcileRefund(tenantId, walletId, orderId, fact.refundNo());
    }

    /** 渠道终态已独立提交；冲正故障不得回滚用户到账事实。 */
    void reconcileRefund(long tenantId, long walletId, long orderId, String refundNo) {
        requireNoTransaction();
        transaction("refundPoints", () -> {
            payment.lockWallet(tenantId, walletId);
            var order = payment.order(tenantId, walletId, orderId);
            var row = refund(tenantId, walletId, refundNo);
            if (((Number) row.get("order_id")).longValue() != orderId) throw WalletException.conflict();
            if (!"RESERVED".equals(row.get("point_state"))) return null;
            String state = (String) row.get("channel_state");
            if ("SUCCEEDED".equals(state)) reverse(order, row);
            else if ("FAILED".equals(state)) {
                long total=Math.addExact(order.basePoints(),order.bonusPoints());
                if (order.company()) CompanyRechargeLedger.change(jdbc,order,"COMPANY_REFUND_RELEASE",((Number)row.get("id")).longValue(),total);
                else points.releaseReserve(order.wallet(),total);
            }
            else return null;
            jdbc.update("""
                    UPDATE pf_refund_order SET point_state = ?, update_time = now()
                    WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ? AND point_state = 'RESERVED' AND channel_state = ?
                    """, "SUCCEEDED".equals(state) ? "REVERSED" : "RELEASED", tenantId, walletId, orderId, row.get("id"), state);
            return null;
        });
    }

    private void reserveRefund(RechargePaymentService.OrderSnapshot order,long refundId) {
        long total=Math.addExact(order.basePoints(),order.bonusPoints());
        if (order.company()) CompanyRechargeLedger.change(jdbc,order,"COMPANY_REFUND_HOLD",refundId,-total);
        else points.reserve(order.wallet(),total);
    }

    private void reverse(RechargePaymentService.OrderSnapshot order, Map<String, Object> row) {
        // The company pool's available balance was debited at reservation. The refund row records final settlement.
        if (order.company()) return;
        String id = row.get("id").toString();
        points.consumeReserve(order.wallet(), "REFUND_BASE_REVERSAL", order.basePoints(), "REFUND", id,
                "refund:" + id + ":base", "整单退款基础点冲正");
        if (order.bonusPoints() > 0) points.consumeReserve(order.wallet(), "REFUND_BONUS_REVERSAL", order.bonusPoints(),
                "REFUND", id, "refund:" + id + ":bonus", "整单退款赠送点冲正");
    }

    Map<String, Object> refund(long tenantId, long walletId, String refundNo) {
        var rows = jdbc.queryForList("SELECT * FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND refund_no = ? FOR UPDATE", tenantId, walletId, refundNo);
        if (rows.isEmpty()) throw WalletException.unavailable();
        return rows.get(0);
    }

    private boolean leaseFresh(long tenantId, long walletId, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until > clock_timestamp() FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                Boolean.class, tenantId, walletId, id));
    }

    private static RefundResponse response(Map<String, Object> row) {
        return new RefundResponse(row.get("id").toString(), (String) row.get("refund_no"),
                (String) row.get("channel_state"), (String) row.get("point_state"));
    }

    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Refund phases require independent local transactions");
    }

    private <T> T transaction(String operation, Supplier<T> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (DataAccessException failure) {
            log.error("[walletRefund][operation={} failureType={}] 数据库事务失败", operation, failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
