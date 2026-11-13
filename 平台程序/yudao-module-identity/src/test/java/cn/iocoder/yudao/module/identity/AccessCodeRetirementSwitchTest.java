package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
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

    private AccessCodeService batchService(boolean enabled) {
        AccessCodeService service = new AccessCodeService(
                mock(javax.sql.DataSource.class), null, mock(AccessCodeCipher.class),
                mock(cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort.class));
        ReflectionTestUtils.setField(service, "accessCodeEnabled", enabled);
        return service;
    }

    @Test
    void retiredSwitchBlocksBatchCreation() {
        assertThatThrownBy(() -> batchService(false).createBatch(1, "INLINE", 30, "备注", "op"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getCode()).isEqualTo(1_070_000_011));
    }

    @Test
    void enabledSwitchPassesGuardAndFailsOnlyOnMissingDependencies() {
        // 开启后必须越过退役守卫：依赖为 null 时会抛 IllegalStateException（事务未装配），而不是退役码
        assertThatThrownBy(() -> batchService(true).createBatch(1, "INLINE", 30, "备注", "op"))
                .isInstanceOf(IllegalStateException.class)
                // 越过退役守卫后因事务管理器缺失而失败（依赖为 mock/null）——证明不是退役码
                .hasMessageContaining("PlatformTransactionManager");
    }
}
