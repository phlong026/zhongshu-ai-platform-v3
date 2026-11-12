package cn.iocoder.yudao.platformmigration;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Only explicit Flyway migration is available: no clean, repair, SQL init or API auto-configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlywayProperties.class)
public class PlatformMigrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformMigrationConfiguration.class);

    @Bean
    ApplicationRunner platformMigration(FlywayProperties properties) {
        return args -> {
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(properties.getUrl(), properties.getUser(), properties.getPassword())
                        .locations(properties.getLocations().toArray(String[]::new))
                        .table(properties.getTable())
                        .baselineOnMigrate(properties.isBaselineOnMigrate())
                        .baselineVersion(properties.getBaselineVersion())
                        .outOfOrder(properties.isOutOfOrder())
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .failOnMissingLocations(true)
                        // Flyway's default ignores future migrations. B must detect that mismatch too.
                        .ignoreMigrationPatterns(new String[0])
                        .jdbcProperties(Map.of("connectTimeout", "10", "socketTimeout", "60"))
                        .load();
                var result = flyway.migrate();
                log.info("[platform-migration][完成，执行迁移数={}]", result.migrationsExecuted);
            } catch (RuntimeException ex) {
                log.error("[platform-migration][迁移失败，异常类型={}]", ex.getClass().getSimpleName());
                throw ex;
            }
        };
    }
}
