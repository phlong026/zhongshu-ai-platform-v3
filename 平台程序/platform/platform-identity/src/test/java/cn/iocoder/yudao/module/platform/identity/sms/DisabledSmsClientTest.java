package cn.iocoder.yudao.module.platform.identity.sms;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledSmsClientTest {

    @Test
    void sendAlwaysFailsWithoutReturningACaptcha() {
        assertThatThrownBy(() -> new DisabledSmsClient().sendLoginCaptcha("13912345678", "882913"))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getCode())
                                .isEqualTo(PlatformIdentityErrorCodes.SMS_SERVICE_UNAVAILABLE.getCode()));
    }

    @Test
    void beanExistsOnlyForIntegrationOutsideProduction() {
        assertThat(hasBean(new String[]{"pg", "integration"}, "disabled")).isTrue();
        assertThat(hasBean(new String[]{"pg"}, "disabled")).isFalse();
        assertThat(hasBean(new String[]{"pg", "integration", "prod"}, "disabled")).isFalse();
        assertThat(hasBean(new String[]{"pg", "integration", "production"}, "disabled")).isFalse();
        assertThat(hasBean(new String[]{"pg", "integration"}, "real")).isFalse();
    }

    private boolean hasBean(String[] profiles, String provider) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("platform.identity.sms.provider", provider)));
            context.register(DisabledSmsClient.class);
            context.refresh();
            return !context.getBeansOfType(DisabledSmsClient.class).isEmpty();
        }
    }
}
