package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRedemptionService;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 批 D / B-14 验收：激活码退役开关关闭后，兑换入口拒绝（只停新写，不动既有授权）。
 */
class AccessCodeRetirementSwitchTest {

    private AccessCodeRedemptionService service(boolean enabled) {
        AccessCodeRedemptionService service = new AccessCodeRedemptionService(
                mock(javax.sql.DataSource.class), null, mock(AccessCodeCipher.class),
                mock(AccountLoginService.class));
        ReflectionTestUtils.setField(service, "accessCodeEnabled", enabled);
        return service;
    }

    @Test
    void retiredSwitchBlocksRedemption() {
        assertThatThrownBy(() -> service(false).redeem("wx-app", "openid", null, "ABC123"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getCode()).isEqualTo(1_070_000_011));
    }

    @Test
    void enabledSwitchDoesNotHitRetirementGuard() {
        // 手动构造时字段默认 false（Spring 由 @Value 注入 true）；显式开启后不再命中退役码
        AccessCodeRedemptionService service = service(true);
        try {
            service.redeem("wx-app", "openid", null, "ABC123");
        } catch (RuntimeException e) {
            // 依赖为 mock/null，后续流程可能 NPE——关键是不再抛退役码
            assertThat(e).isNotInstanceOf(ServiceException.class);
        }
    }
}
