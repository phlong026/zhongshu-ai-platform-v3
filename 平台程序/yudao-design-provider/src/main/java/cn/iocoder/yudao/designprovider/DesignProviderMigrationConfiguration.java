package cn.iocoder.yudao.designprovider;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/** Migration-only context: it never constructs HTTP, Redis, runtime, or design business beans. */
@SpringBootConfiguration
@EnableConfigurationProperties(FlywayProperties.class)
public class DesignProviderMigrationConfiguration {

    @Bean
    ApplicationRunner migrateDesignProvider(FlywayProperties properties, ConfigurableEnvironment environment) {
        return ignored -> {
            ProviderRequiredSecretConfiguration.require(environment,
                    "spring.flyway.password", "DP_MIGRATION_PASSWORD");
            Flyway.configure()
                    .dataSource(properties.getUrl(), properties.getUser(), properties.getPassword())
                    .locations(properties.getLocations().toArray(String[]::new))
                    .table(properties.getTable())
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
        };
    }
}
