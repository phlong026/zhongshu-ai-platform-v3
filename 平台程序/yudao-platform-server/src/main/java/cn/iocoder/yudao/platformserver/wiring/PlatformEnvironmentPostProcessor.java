package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.platformmigration.PlatformAdminBootstrapConfiguration;
import cn.iocoder.yudao.platformmigration.PlatformMigrationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public class PlatformEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // The executable's source determines migration mode; external properties cannot bypass API guards.
        boolean migration = application.getAllSources().contains(PlatformMigrationConfiguration.class)
                || application.getAllSources().contains(PlatformAdminBootstrapConfiguration.class);
        PlatformEnvironmentPolicy.validate(environment, migration);
        SensitiveLoggingPolicy.validate(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
