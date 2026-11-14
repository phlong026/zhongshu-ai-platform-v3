package cn.iocoder.yudao.module.franchise.design.publiccase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PublicCaseCatalogConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PublicCaseCatalogConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("zhongshu.design.remote.enabled=true");

    @Test
    void missingDedicatedPublicOriginFailsClosedEvenWhenSignedRemoteOriginExists() {
        context.withPropertyValues("zhongshu.design.remote.origin=https://signed.example.test")
                .run(result -> {
                    assertThat(result).hasFailed();
                    assertThat(result.getStartupFailure()).hasMessageContaining("zhongshu.design.public-cases.origin");
                });
    }

    @Test
    void dedicatedHttpsPublicOriginCreatesCatalogWithoutUsingSignedRemoteOrigin() {
        context.withPropertyValues(
                        "zhongshu.design.remote.origin=https://signed.example.test",
                        "zhongshu.design.public-cases.origin=https://public.example.test")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    var client = result.getBean(PublicCaseCatalogClient.class);
                    var settings = (PublicCaseCatalogSettings) org.springframework.test.util.ReflectionTestUtils
                            .getField(client, "settings");
                    assertThat(settings.origin).hasToString("https://public.example.test");
                });
    }
}
