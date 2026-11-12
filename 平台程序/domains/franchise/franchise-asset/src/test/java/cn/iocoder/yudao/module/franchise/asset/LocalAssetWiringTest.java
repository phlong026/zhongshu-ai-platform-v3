package cn.iocoder.yudao.module.franchise.asset;

import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalAssetWiringTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalAssetService.class, LocalObjectStorageAdapter.class,
                    CosObjectStorageAdapter.class, AssetContentScanner.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(CurrentPost.class, () -> mock(CurrentPost.class))
            .withBean(StoreServiceAccountPort.class, () -> mock(StoreServiceAccountPort.class));

    @Test
    void defaultFusionModeDoesNotReplaceDesignAssetsOrStorage() {
        context.withPropertyValues("zhongshu.franchise.asset.storage.provider=local").run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app).doesNotHaveBean(LocalAssetService.class);
            assertThat(app).doesNotHaveBean(ObjectStoragePort.class);
            assertThat(app).doesNotHaveBean(AssetContentScanner.class);
        });
    }

    @Test
    void platformModeHasExactlyOneLocalAssetPortAndNoDesignImplementation() {
        context.withPropertyValues("platform.legacy-design-bridge.enabled=false",
                "zhongshu.franchise.asset.storage.provider=local").run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(AssetAccessPort.class).hasSingleBean(ObjectStoragePort.class);
            assertThat(app.getBean(AssetAccessPort.class)).isInstanceOf(LocalAssetService.class);
            assertThat(app.getBean(ObjectStoragePort.class)).isInstanceOf(LocalObjectStorageAdapter.class);
        });
    }

    @Test
    void platformModeWithoutStorageConfigurationFailsAtStartup() {
        context.withPropertyValues("platform.legacy-design-bridge.enabled=false").run(app -> assertThat(app).hasFailed());
    }
}
