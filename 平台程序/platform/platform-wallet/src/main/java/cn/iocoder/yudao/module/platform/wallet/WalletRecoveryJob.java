package cn.iocoder.yudao.module.platform.wallet;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 有界补偿；短事务领取、无事务查询渠道、短事务CAS确认。绝不重发退款。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class WalletRecoveryJob {
    record Task(long id, long tenant, long wallet, long order, String kind, String token) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RechargePaymentService payment;
    private final RefundPaymentService refunds;
    private final WechatXPayProperties properties;
    private final ObjectProvider<PaymentPort> ports;
    @Value("${platform.wallet.recovery.enabled:false}") private boolean enabled;

    public WalletRecoveryJob(DataSource source, PlatformTransactionManager manager, RechargePaymentService payment,
                             RefundPaymentService refunds, WechatXPayProperties properties, ObjectProvider<PaymentPort> ports) {
        this.jdbc = new JdbcTemplate(source);
        this.tx = new TransactionTemplate(manager);
        this.payment = payment;
        this.refunds = refunds;
        this.properties = properties;
        this.ports = ports;
    }

    @Scheduled(fixedDelayString = "${platform.wallet.recovery.interval-ms:60000}", initialDelayString = "${platform.wallet.recovery.initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) return;
        try { runOnce(50); }
        catch (RuntimeException failure) {
            log.error("[walletRecovery][failureType={}] 补偿批次失败，下轮重新领取", failure.getClass().getSimpleName());
        }
    }

    /** 入口只接收验签所需元数据；没有HTTP tenant/wallet参数。原文先入inbox，再由后台查单。 */
    public long acceptNotification(Map<String, String> headers, byte[] body) {
        RefundPaymentService.requireNoTransaction();
        var port = channel();
        if (body == null || body.length == 0 || body.length > 65536 || !port.verifyNotification(headers, body)) throw WalletException.invalidRequest();
        if (properties.getNotificationTenantId() <= 0) throw new IllegalStateException("Notification local tenant is not configured");
        PointAccountService.requireText(properties.getAppid(), 64);
        PointAccountService.requireText(properties.getOfferId(), 128);
        String hash = DigestUtil.sha256Hex(body);
        return transaction("notificationPersist", () -> {
            jdbc.update("""
                    INSERT INTO pf_payment_notification_inbox (id, tenant_id, channel, appid, offer_id, environment, body_hash, payload)
                    VALUES (?, ?, 'XPAY', ?, ?, ?, ?, ?) ON CONFLICT (channel, appid, offer_id, environment, body_hash) DO NOTHING
                    """, IdWorker.getId(), properties.getNotificationTenantId(), properties.getAppid(), properties.getOfferId(), properties.getEnvironment(), hash, body);
            var inbox = jdbc.queryForList("""
                    SELECT id FROM pf_payment_notification_inbox WHERE tenant_id = ? AND channel = 'XPAY'
                        AND appid = ? AND offer_id = ? AND environment = ? AND body_hash = ?
                    """, Long.class, properties.getNotificationTenantId(), properties.getAppid(), properties.getOfferId(), properties.getEnvironment(), hash);
            if (inbox.size() != 1) throw WalletException.conflict();
            return inbox.get(0);
        });
    }

    public void runOnce(int batchSize) {
        RefundPaymentService.requireNoTransaction();
        bounded(batchSize);
        seedTasks(batchSize);
        for (var task : claimTasks(batchSize)) {
            boolean done = false;
            try {
                if ("CREDIT".equals(task.kind())) {
                    done = payment.fulfillOrder(task.tenant(), task.wallet(), task.order());
                    if (!done) done = transaction("creditBlocked", () -> "BLOCKED".equals(payment.order(task.tenant(), task.wallet(), task.order()).creditState()));
                } else {
                    var order = transaction("paymentSnapshot", () -> payment.order(task.tenant(), task.wallet(), task.order()));
                    if ("SUCCEEDED".equals(order.paymentState()) || "BLOCKED".equals(order.creditState())) done = true;
                    else {
                        var fact = channel().queryOrder(order.identity());
                        if (fact != null && "SUCCEEDED".equals(fact.state())) {
                            payment.processPaymentFact(task.tenant(), task.wallet(), task.order(), fact);
                            done = true;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("[walletRecovery][task={} kind={} tenant={} wallet={} failureType={}] 任务未完成", task.id(), task.kind(), task.tenant(), task.wallet(), failure.getClass().getSimpleName());
            }
            finishTask(task, done);
        }
        recoverRefunds(batchSize);
        if (ports.getIfAvailable() != null) recoverInbox(batchSize);
    }

    void seedTasks(int limit) {
        bounded(limit);
        transaction("seed", () -> {
            for (String kind : List.of("PAYMENT", "CREDIT")) {
                String predicate = "CREDIT".equals(kind) ? "o.payment_state = 'SUCCEEDED' AND o.credit_state = 'PENDING'"
                        : "o.payment_state IN ('CREATED','PENDING','UNKNOWN') AND o.credit_state <> 'BLOCKED'";
                jdbc.update("""
                        INSERT INTO pf_wallet_recovery_task (tenant_id, wallet_id, order_id, kind)
                        SELECT o.tenant_id, o.wallet_id, o.id, ? FROM pf_recharge_order o
                        WHERE %s
                         AND NOT EXISTS (SELECT 1 FROM pf_wallet_recovery_task t WHERE t.tenant_id = o.tenant_id
                             AND t.wallet_id = o.wallet_id AND t.order_id = o.id AND t.kind = ?)
                        ORDER BY o.id LIMIT ? ON CONFLICT DO NOTHING
                        """.formatted(predicate), kind, kind, limit);
            }
            return null;
        });
    }

    List<Task> claimTasks(int limit) {
        bounded(limit);
        String token = UUID.randomUUID().toString();
        return transaction("claim", () -> jdbc.query("""
                WITH due AS (SELECT id FROM pf_wallet_recovery_task WHERE status <> 'DONE' AND next_attempt_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE pf_wallet_recovery_task t SET status = 'RUNNING', lease_token = ?, lease_until = clock_timestamp() + interval '60 seconds', attempts = attempts + 1
                FROM due WHERE t.id = due.id RETURNING t.*
                """, (rs, index) -> new Task(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("wallet_id"),
                rs.getLong("order_id"), rs.getString("kind"), token), limit, token));
    }

    boolean finishTask(Task task, boolean done) {
        return transaction("finish", () -> {
            jdbc.queryForList("SELECT id FROM pf_wallet_recovery_task WHERE id = ? AND tenant_id = ? AND wallet_id = ? AND order_id = ? FOR UPDATE",
                    task.id(), task.tenant(), task.wallet(), task.order());
            // 先等待行锁，再求实时钟；不能在阻塞前用事务开始时间判定租约。
            return jdbc.update("""
                    UPDATE pf_wallet_recovery_task SET status = ?, lease_token = NULL, lease_until = NULL,
                        next_attempt_at = clock_timestamp() + interval '60 seconds'
                    WHERE id = ? AND tenant_id = ? AND wallet_id = ? AND order_id = ? AND status = 'RUNNING' AND lease_token = ? AND lease_until > clock_timestamp()
                    """, done ? "DONE" : "READY", task.id(), task.tenant(), task.wallet(), task.order(), task.token()) == 1;
        });
    }

    private void recoverRefunds(int limit) {
        String token = UUID.randomUUID().toString();
        var rows = transaction("claimRefunds", () -> jdbc.queryForList("""
                WITH due AS (SELECT id FROM pf_refund_order WHERE (channel_state IN ('UNKNOWN','PROCESSING')
                    OR (channel_state IN ('SUCCEEDED','FAILED') AND point_state = 'RESERVED')) AND next_attempt_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE pf_refund_order r SET lease_token = ?, lease_until = clock_timestamp() + interval '60 seconds', attempts = attempts + 1
                FROM due WHERE r.id = due.id RETURNING r.*
                """, limit, token));
        for (var row : rows) {
            long tenant = number(row, "tenant_id"), wallet = number(row, "wallet_id"), orderId = number(row, "order_id");
            try {
                if (List.of("SUCCEEDED", "FAILED").contains(row.get("channel_state"))) {
                    refunds.reconcileRefund(tenant, wallet, orderId, (String) row.get("refund_no"));
                } else {
                    var order = transaction("refundSnapshot", () -> payment.order(tenant, wallet, orderId));
                    var request = new PaymentPort.RefundRequest(order.identity(), (String) row.get("refund_no"), number(row, "amount_fen"),
                            ((Number) row.get("reason")).intValue(), (String) row.get("channel_refund_id"));
                    var fact = channel().queryRefund(request);
                    if (fact != null && fact.amountCents() != null) refunds.apply(tenant, wallet, orderId, fact, token);
                }
            } catch (RuntimeException failure) {
                log.warn("[walletRecovery][refund={} tenant={} wallet={} failureType={}] 逐笔退款未收口，保持冻结", row.get("id"), tenant, wallet, failure.getClass().getSimpleName());
            }
            transaction("deferRefund", () -> {
                jdbc.queryForList("SELECT id FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ? FOR UPDATE",
                        tenant, wallet, orderId, row.get("id"));
                return jdbc.update("""
                        UPDATE pf_refund_order SET lease_token = NULL, lease_until = NULL, next_attempt_at = clock_timestamp() + interval '60 seconds'
                        WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ? AND lease_token = ? AND lease_until > clock_timestamp()
                            AND (channel_state IN ('UNKNOWN','PROCESSING') OR point_state = 'RESERVED')
                        """, tenant, wallet, orderId, row.get("id"), token);
            });
        }
    }

    private void recoverInbox(int limit) {
        String token = UUID.randomUUID().toString();
        var rows = transaction("claimInbox", () -> jdbc.queryForList("""
                WITH due AS (SELECT id FROM pf_payment_notification_inbox WHERE status IN ('PENDING','UNKNOWN') AND next_attempt_at <= clock_timestamp()
                    AND (lease_until IS NULL OR lease_until <= clock_timestamp()) ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                UPDATE pf_payment_notification_inbox i SET lease_token = ?, lease_until = clock_timestamp() + interval '60 seconds', attempts = attempts + 1
                FROM due WHERE i.id = due.id RETURNING i.*
                """, limit, token));
        for (var row : rows) {
            boolean done = false;
            try {
                var port = channel();
                var hint = port.parseNotification(Map.of(), (byte[]) row.get("payload"));
                // 通知只在接收时固定的tenant内定位订单，不能凭body改写inbox的归属。
                long tenant = number(row, "tenant_id");
                var orders = transaction("inboxLookup", () -> jdbc.queryForList("""
                        SELECT wallet_id, id FROM pf_recharge_order WHERE tenant_id = ? AND order_no = ? AND channel = ? AND payer_appid = ? AND offer_id = ? AND environment = ?
                        """, tenant, hint.orderNo(), row.get("channel"), row.get("appid"), row.get("offer_id"), row.get("environment")));
                if (orders.size() == 1) {
                    var local = orders.get(0);
                    long wallet = number(local, "wallet_id"), orderId = number(local, "id");
                    boolean claimed = transaction("inboxBind", () -> {
                        jdbc.queryForList("SELECT id FROM pf_payment_notification_inbox WHERE tenant_id = ? AND id = ? FOR UPDATE", tenant, row.get("id"));
                        return jdbc.update("""
                                UPDATE pf_payment_notification_inbox SET wallet_id = ?, order_id = ?, event_type = ?
                                WHERE tenant_id = ? AND id = ? AND lease_token = ? AND lease_until > clock_timestamp() AND status IN ('PENDING','UNKNOWN')
                                """, wallet, orderId, hint.eventType(), tenant, row.get("id"), token) == 1;
                    });
                    if (claimed) {
                        var order = transaction("inboxOrder", () -> payment.order(tenant, wallet, orderId));
                        if (hint.refundNo() == null) {
                            var fact = port.queryOrder(order.identity());
                            if (fact != null && "SUCCEEDED".equals(fact.state())) {
                                payment.processPaymentFact(tenant, wallet, orderId, fact);
                                done = true;
                            }
                        } else {
                            var refundRows = transaction("inboxRefund", () -> jdbc.queryForList("SELECT channel_refund_id FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND refund_no = ?",
                                    tenant, wallet, orderId, hint.refundNo()));
                            String channelId = refundRows.isEmpty() ? null : (String) refundRows.get(0).get("channel_refund_id");
                            var fact = port.queryRefund(new PaymentPort.RefundRequest(order.identity(), hint.refundNo(), order.priceFen(), 0, channelId));
                            if (fact != null && List.of("SUCCEEDED", "FAILED").contains(fact.state())) {
                                refunds.processRefundFact(tenant, wallet, orderId, fact);
                                done = true;
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("[walletRecovery][inbox={} failureType={}] 通知仍待可信查单", row.get("id"), failure.getClass().getSimpleName());
            }
            final boolean complete = done;
            transaction("finishInbox", () -> {
                jdbc.queryForList("SELECT id FROM pf_payment_notification_inbox WHERE tenant_id = ? AND id = ? FOR UPDATE", row.get("tenant_id"), row.get("id"));
                return jdbc.update("""
                        UPDATE pf_payment_notification_inbox SET status = ?, lease_token = NULL, lease_until = NULL, next_attempt_at = clock_timestamp() + interval '60 seconds'
                        WHERE tenant_id = ? AND id = ? AND lease_token = ? AND lease_until > clock_timestamp() AND status IN ('PENDING','UNKNOWN')
                        """, complete ? "PROCESSED" : "UNKNOWN", row.get("tenant_id"), row.get("id"), token);
            });
        }
    }

    private PaymentPort channel() {
        var port = ports.getIfAvailable();
        if (port == null) throw new IllegalStateException("No payment query channel is configured");
        return port;
    }

    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static void bounded(int size) {
        if (size < 1 || size > 100) throw new IllegalArgumentException("Recovery batch must contain 1-100 rows per kind");
    }

    private <T> T transaction(String operation, Supplier<T> action) {
        try { return tx.execute(status -> action.get()); }
        catch (DataAccessException failure) {
            log.error("[walletRecovery][operation={} failureType={}] 数据库事务失败", operation, failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
