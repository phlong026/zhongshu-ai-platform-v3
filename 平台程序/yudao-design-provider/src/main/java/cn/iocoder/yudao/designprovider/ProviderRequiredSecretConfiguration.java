package cn.iocoder.yudao.designprovider;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Fails before external clients are constructed when provider secrets are absent. */
@Configuration(proxyBeanMethods = false)
class ProviderRequiredSecretConfiguration {

    @Bean
    static BeanFactoryPostProcessor requireProviderRuntimeSecrets(ConfigurableEnvironment environment) {
        require(environment, "spring.datasource.dynamic.datasource.master.password", "DP_PG_PASSWORD");
        require(environment, "spring.data.redis.password", "DP_REDIS_PASSWORD");
        requireUnlimited(environment, "zhongshu.design.asset.moderation.daily-limit");
        return beanFactory -> { };
    }

    static void require(ConfigurableEnvironment environment, String property, String sourceName) {
        final String value;
        try {
            value = environment.getRequiredProperty(property);
        } catch (IllegalArgumentException missing) {
            throw new IllegalStateException(sourceName + " is required", missing);
        }
        if (value.isBlank() || value.equals("${" + sourceName + "}")) {
            throw new IllegalStateException(sourceName + " is required");
        }
    }

    static void requireUnlimited(ConfigurableEnvironment environment, String property) {
        String value = environment.getProperty(property, "0");
        try {
            if (Integer.parseInt(value) != 0) throw new IllegalStateException("B_PROVIDER_DAILY_LIMIT_MUST_BE_ZERO");
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("B_PROVIDER_DAILY_LIMIT_MUST_BE_ZERO", invalid);
        }
    }
}
