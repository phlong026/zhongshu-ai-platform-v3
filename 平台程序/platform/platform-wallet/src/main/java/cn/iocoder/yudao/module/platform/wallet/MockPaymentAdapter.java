package cn.iocoder.yudao.module.platform.wallet;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

/** Server-side deterministic simulator. It never invokes an external payment provider. */
final class MockPaymentAdapter implements PaymentPort {
    private final JdbcTemplate jdbc;

    MockPaymentAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PrepayResult createPrepay(OrderIdentity order, String productId, String wxLoginCode) {
        if (!"MOCK".equals(order.channel())) throw new IllegalArgumentException("Mock channel mismatch");
        return new PrepayResult(Map.of("mode", "MOCK"));
    }

    @Override public boolean verifyNotification(Map<String, String> headers, byte[] body) { return false; }
    @Override public NotificationHint parseNotification(Map<String, String> headers, byte[] body) {
        throw new IllegalArgumentException("External notifications are not accepted by mock payment");
    }

    @Override
    public ChannelQueryResult queryOrder(OrderIdentity order) {
        if (!"MOCK".equals(order.channel())) throw new IllegalArgumentException("Mock channel mismatch");
        if (!confirmed(order)) {
            return new ChannelQueryResult("UNKNOWN", order.orderNo(), "MOCK", order.appid(), "MOCK", 0,
                    order.openid(), null, null, "FEN", null, null);
        }
        return new ChannelQueryResult("SUCCEEDED", order.orderNo(), "MOCK", order.appid(), "MOCK", 0,
                order.openid(), "MOCK-" + order.orderNo(), order.amountCents(), "FEN", Instant.now(), 0);
    }

    // completeMockPrepay commits this marker only after payer, quote and lease revalidation.
    // Recovery may finish that confirmed payment after a crash, but querying never confirms it.
    boolean confirmed(OrderIdentity order) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pf_payment_attempt
                    WHERE tenant_id=? AND wallet_id=? AND order_id=? AND state='SUCCEEDED')
                """, Boolean.class, order.tenantId(), order.walletId(), order.orderId()));
    }

    @Override
    public ChannelRefundResult requestRefund(RefundRequest request) {
        return new ChannelRefundResult("SUCCEEDED", request.order().orderNo(), request.refundNo(),
                "MOCK-REFUND-" + request.refundNo(), request.amountCents(), "FEN", 1, 8);
    }

    @Override public ChannelRefundResult queryRefund(RefundRequest request) { return requestRefund(request); }
}
