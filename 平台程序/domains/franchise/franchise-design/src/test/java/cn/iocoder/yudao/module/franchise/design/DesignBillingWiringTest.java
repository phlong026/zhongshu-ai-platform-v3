package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.design.billing.DesignWalletChargeAdapter;
import cn.iocoder.yudao.module.platform.wallet.WalletChargeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesignBillingWiringTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(DesignWalletChargeAdapter.class);

    @Test void legacyDefaultAndExplicitLegacyNeverCreateBWalletAdapter() {
        context.run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(DesignWalletChargeAdapter.class));
        context.withPropertyValues("platform.legacy-design-bridge.enabled=true")
                .run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(DesignWalletChargeAdapter.class));
    }

    @Test void explicitBBridgeCreatesRealAdapterWithBWalletDependencies() {
        context.withPropertyValues("platform.legacy-design-bridge.enabled=false")
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(WalletChargeService.class, () -> mock(WalletChargeService.class))
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(DesignWalletChargeAdapter.class));
    }
}
