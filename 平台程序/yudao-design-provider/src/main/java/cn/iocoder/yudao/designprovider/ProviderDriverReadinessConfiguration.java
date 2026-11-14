package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.security.CapabilityProviderReadiness;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProviderDriverReadinessConfiguration {

    @Bean
    static BeanPostProcessor capabilityDriverReadiness(ObjectProvider<ProviderRuntimeDriver> drivers) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof CapabilityProviderReadiness delegate)) return bean;
                return (CapabilityProviderReadiness) () -> {
                    delegate.verifyReady();
                    requireDriver(drivers);
                };
            }
        };
    }

    @Bean
    HealthIndicator designProviderDriverHealth(ObjectProvider<ProviderRuntimeDriver> drivers) {
        return () -> {
            try {
                requireDriver(drivers);
                return Health.up().build();
            } catch (IllegalStateException unavailable) {
                return Health.down().withDetail("reason", unavailable.getMessage()).build();
            }
        };
    }

    private static void requireDriver(ObjectProvider<ProviderRuntimeDriver> drivers) {
        ProviderRuntimeDriver driver = drivers.getIfUnique();
        if (driver == null) throw new IllegalStateException("DESIGN_PROVIDER_JOB_DRIVER_NOT_READY");
        driver.verifyReady();
    }
}
