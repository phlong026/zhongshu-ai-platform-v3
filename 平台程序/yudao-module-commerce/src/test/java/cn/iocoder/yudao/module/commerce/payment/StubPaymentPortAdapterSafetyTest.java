package cn.iocoder.yudao.module.commerce.payment;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stub 支付通道安全回归：生产基线关闭自动结算后，不得再出现「未支付订单查成 SUCCEEDED」的路径。
 *
 * <p>该用例不依赖数据库/容器，直接覆盖 {@link StubPaymentPortAdapter} 的默认行为；
 * 手工 new 的合同测试仍可通过字段初值保持既有 SUCCEEDED 剧本兼容。
 */
class StubPaymentPortAdapterSafetyTest {

    @Test
    void autoSettleDisabled_neverTreatsUnknownOrderAsPaid() {
        StubPaymentPortAdapter adapter = new StubPaymentPortAdapter();
        ReflectionTestUtils.setField(adapter, "stubAutoSettle", false);

        assertThat(adapter.queryOrder("R-UNPAID-1").getState()).isEqualTo("UNKNOWN");
        assertThat(adapter.verifyNotification(Map.of(), new byte[]{1})).isFalse();
        assertThatThrownBy(() -> adapter.createPrepay("R-UNPAID-1", 1000, "充值", "openid-1", null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("充值暂不可用");
    }

    @Test
    void autoSettleEnabled_keepsExplicitFixtureScriptsWorking() {
        StubPaymentPortAdapter adapter = new StubPaymentPortAdapter();
        ReflectionTestUtils.setField(adapter, "stubAutoSettle", true);
        adapter.scriptQuery("R-SCRIPTED", "PENDING");

        assertThat(adapter.queryOrder("R-SCRIPTED").getState()).isEqualTo("PENDING");
        // 未注入剧本时保留本地联调默认值：仍按已支付处理
        assertThat(adapter.queryOrder("R-UNSCRIPTED").getState()).isEqualTo("SUCCEEDED");
        assertThat(adapter.createPrepay("R-UNSCRIPTED", 1000, "充值", "openid-2", null).getPrepayId())
                .isEqualTo("stub-prepay-R-UNSCRIPTED");
        assertThat(adapter.verifyNotification(Map.of(), new byte[]{1})).isTrue();
    }
}
