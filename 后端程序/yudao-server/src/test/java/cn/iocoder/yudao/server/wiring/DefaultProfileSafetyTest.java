package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认配置不得激活任何 profile：local 开启了 mock 登录，漏配 profile 的部署会让固定令牌成为超管。
 */
class DefaultProfileSafetyTest {

    @Test
    void baseConfigurationDoesNotActivateAnyProfile() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        for (PropertySource<?> source : sources) {
            assertThat(source.getProperty("spring.profiles.active")).as(source.getName()).isNull();
            assertThat(source.getProperty("spring.profiles.default")).as(source.getName()).isNull();
        }
    }

    @Test
    void mockLoginIsOnlyEnabledInTheLocalProfile() throws Exception {
        for (String profile : List.of("application.yaml", "application-pg.yaml", "application-prod.yaml",
                "application-zsdev.yaml", "application-dev.yaml")) {
            ClassPathResource resource = new ClassPathResource(profile);
            if (!resource.exists()) {
                continue;
            }
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(profile, resource)) {
                assertThat(source.getProperty("yudao.security.mock-enable")).as(profile).isNotEqualTo(true);
            }
        }
    }

}
