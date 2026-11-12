package cn.iocoder.yudao.module.platform.wallet;

import java.time.Instant;
import java.util.Map;

/**
 * 从 commerce PaymentPort 迁移的渠道边界。B 补充不可变订单作用域与完整可信事实；
 * 缺金额单位、交易号或逐笔退款证据时拒绝确认，不允许订单声明值或通知体 fallback。
 */
public interface PaymentPort {

    record OrderIdentity(long tenantId, long walletId, long orderId, String orderNo, String channel,
                         String appid, String offerId, int environment, String openid, long amountCents) {
    }

    record PrepayResult(Map<String, String> callParams) {
    }

    record ChannelQueryResult(String state, String orderNo, String channel, String appid, String offerId,
                              int environment, String openid, String channelTransactionId, Long amountCents,
                              String amountUnit, Instant paidAt, Integer orderType) {
    }

    record RefundRequest(OrderIdentity order, String refundNo, long amountCents, int reason, String channelRefundId) {
    }

    record ChannelRefundResult(String state, String orderNo, String refundNo, String channelRefundId,
                               Long amountCents, String amountUnit, Integer orderType, Integer orderStatus) {
    }

    record NotificationHint(String eventId, String orderNo, String refundNo, String eventType) {
    }

    PrepayResult createPrepay(OrderIdentity order, String productId, String wxLoginCode);

    boolean verifyNotification(Map<String, String> headers, byte[] body);

    /** 仅在验签、持久 inbox 后提取提示；不得在解析通知时外呼或构造支付事实。 */
    NotificationHint parseNotification(Map<String, String> headers, byte[] body);

    ChannelQueryResult queryOrder(OrderIdentity order);

    ChannelRefundResult requestRefund(RefundRequest request);

    /** 必须按退款单号逐笔查单；原支付订单总体状态不构成退款成功证据。 */
    ChannelRefundResult queryRefund(RefundRequest request);
}
