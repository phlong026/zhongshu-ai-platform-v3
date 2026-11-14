package cn.iocoder.yudao.designprovider;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;
import java.util.Arrays;

/** Read-only startup gate: the Provider cannot serve on an old, checksum-mismatched, or future schema. */
@Configuration(proxyBeanMethods = false)
public class ProviderMigrationReadinessConfiguration {

    record ProviderSchemaReadiness() { }

    static final String[] LOCATIONS = {
            "classpath:db/migration/platform", "classpath:db/migration/identity", "classpath:db/migration/design",
            "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
            "classpath:db/migration/design-capability", "classpath:db/migration/design-provider"
    };

    @Bean
    @Lazy(false)
    ProviderSchemaReadiness verifyCandidateSchema(DataSource dataSource) {
            var flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(LOCATIONS)
                    .table("flyway_schema_history")
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .failOnMissingLocations(true)
                    .load();
            try {
                flyway.validate();
                var info = flyway.info();
                if (info.pending().length != 0 || Arrays.stream(info.all())
                        .anyMatch(migration -> migration.getState().name().contains("FUTURE"))) {
                    throw new IllegalStateException("PROVIDER_SCHEMA_NOT_READY");
                }
            } catch (RuntimeException error) {
                if ("PROVIDER_SCHEMA_NOT_READY".equals(error.getMessage())) {
                    throw error;
                }
                throw new IllegalStateException("PROVIDER_SCHEMA_NOT_READY", error);
            }
            return new ProviderSchemaReadiness();
    }
}
