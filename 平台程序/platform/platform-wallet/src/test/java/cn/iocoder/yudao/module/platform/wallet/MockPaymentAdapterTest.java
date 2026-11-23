package cn.iocoder.yudao.module.platform.wallet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockPaymentAdapterTest {
    private final MockPaymentAdapter adapter = new MockPaymentAdapter();
    private final PaymentPort.OrderIdentity order =
        new PaymentPort.OrderIdentity(1, 2, 3, "PFW3", "MOCK", "app", "MOCK", 0, "payer", 1234);

    @Test void generatesOnlyExplicitMockPaymentFacts() {
        var result = adapter.createPrepay(order, "goods", "unused-code");
        assertThat(result.callParams()).containsOnlyKeys("mode").containsEntry("mode", "MOCK");
        var fact = adapter.queryOrder(order);
        assertThat(fact.state()).isEqualTo("SUCCEEDED");
        assertThat(fact.channel()).isEqualTo("MOCK");
        assertThat(fact.orderNo()).isEqualTo("PFW3");
        assertThat(fact.channelTransactionId()).isEqualTo("MOCK-PFW3");
        assertThat(fact.amountCents()).isEqualTo(1234L);
        assertThat(fact.amountUnit()).isEqualTo("FEN");
    }

    @Test void neverAcceptsExternalNotifications() {
        assertThat(adapter.verifyNotification(java.util.Map.of(), new byte[]{1})).isFalse();
        assertThatThrownBy(() -> adapter.parseNotification(java.util.Map.of(), new byte[]{1}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesAnOrderFromAnotherChannel() {
        var wrong = new PaymentPort.OrderIdentity(1,2,3,"PFW3","XPAY","app","offer",0,"payer",1234);
        assertThatThrownBy(() -> adapter.queryOrder(wrong)).isInstanceOf(IllegalArgumentException.class);
    }
}
