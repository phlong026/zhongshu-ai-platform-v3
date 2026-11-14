package cn.iocoder.yudao.designprovider;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class DesignProviderMigrationTest {
    @Test
    void emptyIndependentPostgresBuildsCapabilityRuntimeClosure() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            DesignProviderApplication.main(new String[]{"migrate",
                    "--spring.flyway.url=" + postgres.getJdbcUrl(),
                    "--spring.flyway.user=" + postgres.getUsername(),
                    "--spring.flyway.password=" + postgres.getPassword()});
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                         + "AND table_name IN ('account','asset','design_project','budget_estimate','ai_job',"
                         + "'platform_cost_business','design_capability_organization','design_capability_receipt')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(8);
            }
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE provider_rollback_sentinel(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO provider_rollback_sentinel VALUES (1)");
            }
            DesignProviderApplication.main(new String[]{"migrate",
                    "--spring.flyway.url=" + postgres.getJdbcUrl(),
                    "--spring.flyway.user=" + postgres.getUsername(),
                    "--spring.flyway.password=" + postgres.getPassword()});
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM provider_rollback_sentinel")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }
}
