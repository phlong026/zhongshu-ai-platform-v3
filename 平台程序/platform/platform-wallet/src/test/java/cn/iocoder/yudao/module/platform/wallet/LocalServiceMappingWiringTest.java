package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalServiceMappingWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(WalletScope.class, () -> mock(WalletScope.class))
            .withUserConfiguration(LocalStoreServiceAccountService.class);

    @Test
    void explicitIndependentModeProvidesTheAssetPortWithoutIdentityBeans() {
        context.withPropertyValues("platform.legacy-design-bridge.enabled=false").run(ctx -> {
            assertThat(ctx).hasSingleBean(StoreServiceAccountPort.class);
            assertThat(ctx.getBean(StoreServiceAccountPort.class)).isInstanceOf(LocalStoreServiceAccountService.class);
        });
    }

    @Test
    void legacyServerDoesNotInstallIndependentWalletBeansFromTransitiveDependencies() {
        var independent = new ApplicationContextRunner().withUserConfiguration(
                WalletScope.class, PointAccountService.class, WalletChargeService.class, WalletQueryService.class,
                RechargePaymentService.class, PaymentFactValidator.class,
                cn.iocoder.yudao.module.platform.wallet.controller.admin.WalletController.class);
        for (var runner : java.util.List.of(independent, independent.withPropertyValues("platform.legacy-design-bridge.enabled=true"))) {
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(PointAccountService.class);
                assertThat(ctx).doesNotHaveBean(RechargePaymentService.class);
                assertThat(ctx).doesNotHaveBean(PaymentFactValidator.class);
                assertThat(ctx).doesNotHaveBean(cn.iocoder.yudao.module.platform.wallet.controller.admin.WalletController.class);
            });
        }
    }

    @Test
    void defaultAndLegacyModesDoNotInstallTheIndependentMapping() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(StoreServiceAccountPort.class));
        context.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StoreServiceAccountPort.class));
    }
}
