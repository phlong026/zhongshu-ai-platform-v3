package cn.iocoder.yudao.designprovider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMigrationReadinessConfigurationTest {

    private static final String[] BASE_LOCATIONS = {
            "classpath:db/migration/platform", "classpath:db/migration/identity", "classpath:db/migration/design",
            "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration",
            "classpath:db/migration/design-capability"
    };

    @Test
    void providerRefusesOldSchemaUntilCandidateMigrationsAreApplied() throws Exception {
        var externalUrl = System.getenv("PROVIDER_TEST_JDBC_URL");
        if (externalUrl != null) {
            verifyScenario(externalUrl, System.getenv("PROVIDER_TEST_JDBC_USER"),
                    System.getenv("PROVIDER_TEST_JDBC_PASSWORD"));
            return;
        }
        try (var postgres = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:16.15-alpine"))
                .withDatabaseName("design_provider").withUsername("owner").withPassword("test-password")) {
            postgres.start();
            verifyScenario(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    private void verifyScenario(String jdbcUrl, String username, String password) throws Exception {
            var initial = Flyway.configure().dataSource(jdbcUrl, username, password).locations(BASE_LOCATIONS).load();
            var cutoff = org.flywaydb.core.api.MigrationVersion.fromVersion("20261114.950");
            var legacyVersion = java.util.Arrays.stream(initial.info().all()).map(info -> info.getVersion())
                    .filter(version -> version != null && version.compareTo(cutoff) < 0)
                    .max(org.flywaydb.core.api.MigrationVersion::compareTo).orElseThrow();
            Flyway.configure().dataSource(jdbcUrl, username, password)
                    // The legacy fixture must precede candidate migrations, including when newer shared migrations exist.
                    .locations(BASE_LOCATIONS).target(legacyVersion).load().migrate();
            var guard = new ProviderMigrationReadinessConfiguration();
            String appRole = "provider_app_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try (var owner = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = owner.createStatement()) {
                String database;
                try (var row = statement.executeQuery("SELECT current_database()")) { row.next(); database = row.getString(1); }
                statement.execute("CREATE ROLE " + appRole + " LOGIN PASSWORD 'test-app-password'");
                statement.execute("GRANT CONNECT ON DATABASE \"" + database.replace("\"", "\"\"") + "\" TO " + appRole);
                statement.execute("GRANT USAGE ON SCHEMA public TO " + appRole);
                statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + appRole);
            }
            try {
            var dataSource = new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setURL(jdbcUrl);
            dataSource.setUser(appRole);
            dataSource.setPassword("test-app-password");

            assertThatThrownBy(() -> guard.verifyCandidateSchema(dataSource))
                    .hasMessageContaining("PROVIDER_SCHEMA_NOT_READY");

            Flyway.configure().dataSource(jdbcUrl, username, password)
                    .locations(ProviderMigrationReadinessConfiguration.LOCATIONS).load().migrate();
            try (var owner = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = owner.createStatement()) {
                statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + appRole);
            }
            assertThatCode(() -> guard.verifyCandidateSchema(dataSource)).doesNotThrowAnyException();
            assertThatThrownBy(() -> {
                try (var app = dataSource.getConnection(); var statement = app.createStatement()) {
                    statement.execute("CREATE TABLE forbidden_ddl(id bigint)");
                }
            }).isInstanceOf(java.sql.SQLException.class);

            try (var owner = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = owner.createStatement()) {
                int checksum;
                try (var row = statement.executeQuery("SELECT checksum FROM flyway_schema_history "
                        + "WHERE version='20261114.951'")) { row.next(); checksum = row.getInt(1); }
                statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 "
                        + "WHERE version='20261114.951'");
                assertThatThrownBy(() -> guard.verifyCandidateSchema(dataSource))
                        .hasMessageContaining("PROVIDER_SCHEMA_NOT_READY");
                statement.executeUpdate("UPDATE flyway_schema_history SET checksum=" + checksum
                        + " WHERE version='20261114.951'");
                statement.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,"
                        + "script,checksum,installed_by,execution_time,success) SELECT max(installed_rank)+1,"
                        + "'99999999.999','future probe','SQL','V99999999_999__future_probe.sql',NULL,current_user,0,true "
                        + "FROM flyway_schema_history");
                assertThatThrownBy(() -> guard.verifyCandidateSchema(dataSource))
                        .hasMessageContaining("PROVIDER_SCHEMA_NOT_READY");
                statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version='99999999.999'");
            }
            } finally {
                try (var owner = DriverManager.getConnection(jdbcUrl, username, password);
                     var statement = owner.createStatement()) {
                    statement.execute("DROP OWNED BY " + appRole);
                    statement.execute("DROP ROLE " + appRole);
                }
            }
    }
}
