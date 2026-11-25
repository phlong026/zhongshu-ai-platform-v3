package cn.iocoder.yudao.module.platform.wallet;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** commerce PaymentFactValidator 的严格版：渠道缺失事实必须拒绝，禁止订单或通知声明值兜底。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class PaymentFactValidator {

    public PaymentPort.ChannelQueryResult validate(PaymentPort.OrderIdentity order,
                                                    PaymentPort.ChannelQueryResult fact) {
        if (fact == null || !"SUCCEEDED".equals(fact.state())
                || !"FEN".equals(fact.amountUnit()) || fact.amountCents() == null
                || fact.amountCents() != order.amountCents() || fact.paidAt() == null
                || fact.orderType() == null || (fact.orderType() != 0 && fact.orderType() != 7)
                || fact.channelTransactionId() == null || fact.channelTransactionId().isBlank()
                || !Objects.equals(order.orderNo(), fact.orderNo())
                || !Objects.equals(order.channel(), fact.channel())
                || !Objects.equals(order.appid(), fact.appid())
                || !Objects.equals(order.offerId(), fact.offerId())
                || order.environment() != fact.environment()
                || !Objects.equals(order.openid(), fact.openid())) {
            throw new IllegalArgumentException("Payment query fact is incomplete or conflicts with the fixed order");
        }
        return fact;
    }

    void validateClosed(PaymentPort.OrderIdentity order, PaymentPort.ChannelQueryResult fact) {
        if (fact == null || !"CLOSED".equals(fact.state())
                || fact.orderType() == null || (fact.orderType() != 0 && fact.orderType() != 7)
                || fact.paidAt() != null || (fact.amountCents() != null && fact.amountCents() != 0)
                || !Objects.equals(order.orderNo(), fact.orderNo())
                || !Objects.equals(order.channel(), fact.channel())
                || !Objects.equals(order.appid(), fact.appid())
                || !Objects.equals(order.offerId(), fact.offerId())
                || order.environment() != fact.environment()
                || !Objects.equals(order.openid(), fact.openid())) {
            throw new IllegalArgumentException("Closed payment query fact conflicts with the fixed order");
        }
    }

    public PaymentPort.ChannelRefundResult validateRefund(PaymentPort.RefundRequest request,
                                                           PaymentPort.ChannelRefundResult fact) {
        if (fact == null || !Objects.equals(request.order().orderNo(), fact.orderNo())
                || !Objects.equals(request.refundNo(), fact.refundNo())
                || !"FEN".equals(fact.amountUnit()) || fact.amountCents() == null
                || fact.amountCents() != request.amountCents()
                || fact.channelRefundId() == null || fact.channelRefundId().isBlank()
                || fact.orderType() == null || (fact.orderType() != 1 && fact.orderType() != 8)
                || (request.channelRefundId() != null && !request.channelRefundId().equals(fact.channelRefundId()))
                || ("SUCCEEDED".equals(fact.state()) && !Integer.valueOf(8).equals(fact.orderStatus()))
                || ("FAILED".equals(fact.state()) && !Integer.valueOf(7).equals(fact.orderStatus()))) {
            throw new IllegalArgumentException("Per-refund query fact is incomplete or conflicts with the fixed refund");
        }
        return fact;
    }
}
