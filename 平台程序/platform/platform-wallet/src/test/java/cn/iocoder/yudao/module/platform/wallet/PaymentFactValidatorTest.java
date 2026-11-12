package cn.iocoder.yudao.module.platform.wallet;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentFactValidatorTest {

    private final PaymentPort.OrderIdentity order = new PaymentPort.OrderIdentity(1, 2, 3, "PFW3",
            "XPAY", "app", "offer", 0, "payer", 100);
    private final PaymentFactValidator validator = new PaymentFactValidator();

    @Test
    void acceptsOnlyCompleteMatchingQueryFacts() {
        var fact = fact("PFW3", "transaction", 100L, "FEN", "app", "payer");
        assertThat(validator.validate(order, fact)).isEqualTo(fact);
    }

    @Test
    void missingAmountTransactionOrUnitNeverFallsBackToOrderDeclarations() {
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", "transaction", null, "FEN", "app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", null, 100L, "FEN", "app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", "transaction", 100L, "UNVERIFIED", "app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderAppPayerAndAmountMustMatchExactly() {
        assertThatThrownBy(() -> validator.validate(order, fact("PFW4", "transaction", 100L, "FEN", "app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", "transaction", 100L, "FEN", "foreign-app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", "transaction", 100L, "FEN", "app", "foreign-payer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(order, fact("PFW3", "transaction", 99L, "FEN", "app", "payer")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundTerminalFactsRequireTheExactRefundAndOfficialTerminalStatus() {
        var request = new PaymentPort.RefundRequest(order, "refund-1", 100, 0, "channel-refund-1");
        assertThatThrownBy(() -> validator.validateRefund(request, new PaymentPort.ChannelRefundResult("SUCCEEDED", "PFW3",
                "refund-1", "channel-refund-1", 100L, "FEN", 1, 5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateRefund(request, new PaymentPort.ChannelRefundResult("FAILED", "PFW3",
                "refund-1", "channel-refund-1", 100L, "FEN", 1, 5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateRefund(request, new PaymentPort.ChannelRefundResult("SUCCEEDED", "PFW3",
                "refund-1", "other-refund", 100L, "FEN", 1, 8))).isInstanceOf(IllegalArgumentException.class);
        assertThat(validator.validateRefund(request, new PaymentPort.ChannelRefundResult("FAILED", "PFW3",
                "refund-1", "channel-refund-1", 100L, "FEN", 1, 7)).state()).isEqualTo("FAILED");
        assertThat(validator.validateRefund(request, new PaymentPort.ChannelRefundResult("SUCCEEDED", "PFW3",
                "refund-1", "channel-refund-1", 100L, "FEN", 1, 8)).state()).isEqualTo("SUCCEEDED");
    }

    private PaymentPort.ChannelQueryResult fact(String orderNo, String transaction, Long amount,
                                                String unit, String appid, String payer) {
        return new PaymentPort.ChannelQueryResult("SUCCEEDED", orderNo, "XPAY", appid, "offer", 0,
                payer, transaction, amount, unit, Instant.parse("2026-11-11T00:00:00Z"), 0);
    }
}
