package cn.iocoder.yudao.module.platform.wallet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentModeTest {
    @Test void parsesOnlySupportedModes() {
        assertThat(PaymentMode.parse("MOCK")).isEqualTo(PaymentMode.MOCK);
        assertThat(PaymentMode.parse(" real ")).isEqualTo(PaymentMode.REAL);
        assertThatThrownBy(() -> PaymentMode.parse("AUTO")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentMode.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
