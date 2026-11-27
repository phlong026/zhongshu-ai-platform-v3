package cn.iocoder.yudao.platformmigration;

import cn.iocoder.yudao.module.platform.test.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real forward/undo resources and original PostgreSQL constraints, with pre-retirement rows. */
class DesignPostRetirementPgIntegrationTest {
    private static final String FORWARD = "db/migration/franchise/V20261127.1200__retire_design_posts.sql";
    private static final String ROLES = "db/migration/franchise-system/V20261127.1201__retire_design_posts.sql";
    private static final String COMPATIBILITY = "db/migration/franchise/V20261127.1211__design_post_history.sql";
    private static final String UNDO_TEMPLATES = "db/migration-undo/franchise/V20261127.1200__undo_retire_design_posts.sql";
    private static final String UNDO_ROLES = "db/migration-undo/franchise-system/V20261127.1201__undo_retire_design_posts.sql";
    private static final String CODES = "('hq-designer','hq-design-lead','hq-design-admin')";
    private static final Map<String, String> BACKUPS = Map.of(
            "pf_post_template", "retired_design_post_template_backup",
            "system_role", "retired_design_role_backup",
            "system_role_menu", "retired_design_role_menu_backup",
            "system_user_role", "retired_design_user_role_backup");

    @TempDir Path temporary;

    @Test
    void bothMigrationEntrypointsRetireExistingPostsWithoutChangingUnrelatedRolesOrNewWriteGuards() throws Exception {
        for (boolean springAutoConfiguration : List.of(false, true)) {
            try (var pg = TestPostgres.database("postgres:17-alpine")) {
                pg.start();
                try (var connection = connect(pg)) {
                    fixture(connection, true, 1, 7);
                    String originalRevoked = rows(connection, "pf_staff_post", "id=1011", "id");
                    String originalBoss = rows(connection, "pf_staff_post", "role_code='boss'", "id");
                    // The published migration alone really fails with historical production constraints.
                    assertThatThrownBy(() -> execute(connection, resource(FORWARD))).isInstanceOf(SQLException.class)
                            .hasMessageContaining("ck_pf_staff_post_status");
                    migrate(pg, springAutoConfiguration);
                    assertRetired(connection);
                    assertThat(rows(connection, "pf_staff_post", "id=1011", "id")).isEqualTo(originalRevoked);
                    assertThat(rows(connection, "pf_staff_post", "role_code='boss'", "id")).isEqualTo(originalBoss);
                    assertThat(scalar(connection, "SELECT convalidated::text FROM pg_constraint WHERE conrelid='pf_staff_post'::regclass AND conname='fk_pf_staff_post_role'"))
                            .isEqualTo("false");
                    assertThatThrownBy(() -> execute(connection,
                            "INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(999,101,1,'hq-designer')"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("fk_pf_staff_post_role");
                    assertThatThrownBy(() -> execute(connection,
                            "UPDATE pf_staff_post SET role_code='unknown-role' WHERE id=1012"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("fk_pf_staff_post_role");
                    execute(connection, "INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(999,999,1,'boss')");
                    Map<String, String> after = snapshot(connection);
                    String history = scalar(connection, "SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM flyway_schema_history h");
                    migrate(pg, springAutoConfiguration);
                    assertThat(snapshot(connection)).isEqualTo(after);
                    assertThat(scalar(connection, "SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM flyway_schema_history h"))
                            .isEqualTo(history);
                }
            }
        }
    }

    @Test
    void revokedOnlyHistoryStillUpgradesWithoutDeletingOrRewritingTheOriginalPosts() throws Exception {
        try (var pg = TestPostgres.database("postgres:17-alpine")) {
            pg.start();
            try (var connection = connect(pg)) {
                fixture(connection, true, 1);
                execute(connection, "UPDATE pf_staff_post SET status='REVOKED',revoked_at='2026-02-03T00:00:00Z',revoked_by=456 "
                        + "WHERE status='ACTIVE' AND role_code IN " + CODES);
                String posts = rows(connection, "pf_staff_post", "true", "id");
                assertThatThrownBy(() -> execute(connection, resource(FORWARD))).isInstanceOf(SQLException.class)
                        .hasMessageContaining("fk_pf_staff_post_role");
                migrate(pg, false);
                assertRetired(connection);
                assertThat(rows(connection, "pf_staff_post", "true", "id")).isEqualTo(posts);
            }
        }
    }

    @Test
    void originalBackupsRestoreRealTenantIdsAndCustomBindingsIdempotentlyWithoutRevivingPosts() throws Exception {
        for (int[] tenants : List.of(new int[]{1}, new int[]{1, 7})) {
            try (var pg = TestPostgres.database("postgres:17-alpine")) {
                pg.start();
                try (var connection = connect(pg)) {
                    fixture(connection, true, tenants);
                    Map<String, String> original = snapshot(connection);
                    Map<String, String> backup = backup(connection);
                    migrate(pg, false);
                    String retiredPosts = rows(connection, "pf_staff_post", "true", "id");
                    loadBackup(connection, backup);
                    execute(connection, "BEGIN");
                    execute(connection, resource(UNDO_ROLES));
                    execute(connection, resource(UNDO_TEMPLATES));
                    execute(connection, "COMMIT");
                    for (String table : BACKUPS.keySet()) assertThat(snapshot(connection).get(table)).isEqualTo(original.get(table));
                    assertThat(rows(connection, "pf_staff_post", "true", "id")).isEqualTo(retiredPosts);
                    assertThat(scalar(connection, "SELECT count(*) FROM system_role WHERE tenant_id=0")).isEqualTo("0");
                    assertThat(scalar(connection, "SELECT code FROM system_role WHERE id=810009")).isEqualTo("boss");
                    Map<String, String> recovered = snapshot(connection);
                    execute(connection, resource(UNDO_ROLES));
                    execute(connection, resource(UNDO_TEMPLATES));
                    assertThat(snapshot(connection)).isEqualTo(recovered);
                }
            }
        }
    }

    @Test
    void recoveryRefusesMissingBackupAndConflictingIdentityOrCrossTenantBindingsWithoutPartialWrites() throws Exception {
        for (String conflict : List.of("missing", "identity", "binding", "tenant", "template")) {
            try (var pg = TestPostgres.database("postgres:17-alpine")) {
                pg.start();
                try (var connection = connect(pg)) {
                    fixture(connection, true, 1, 7);
                    Map<String, String> backup = backup(connection);
                    migrate(pg, false);
                    if (!conflict.equals("missing")) loadBackup(connection, backup);
                    if (conflict.equals("identity")) execute(connection,
                            "INSERT INTO system_role(id,name,code,sort,status,type,tenant_id) VALUES(1101,'Occupied','finance',1,0,1,1)");
                    if (conflict.equals("binding")) execute(connection,
                            "INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(1110,810009,500,1)");
                    if (conflict.equals("tenant")) execute(connection,
                            "UPDATE retired_design_user_role_backup SET tenant_id=7 WHERE tenant_id=1");
                    if (conflict.equals("template")) execute(connection,
                            "INSERT INTO pf_post_template(role_code,domain,org_types) VALUES('hq-designer','franchise','headquarters')");
                    Map<String, String> before = snapshot(connection);
                    String sql = resource(conflict.equals("template") ? UNDO_TEMPLATES : UNDO_ROLES);
                    assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
                    assertThat(snapshot(connection)).isEqualTo(before);
                }
            }
        }
    }

    @Test
    void compatibilityFailureRollsBackRevocationAndDoesNotWeakenUnknownConstraints() throws Exception {
        for (boolean unknownConstraint : List.of(false, true)) {
            try (var pg = TestPostgres.database("postgres:17-alpine")) {
                pg.start();
                try (var connection = connect(pg)) {
                    fixture(connection, true, 1);
                    if (unknownConstraint) {
                        execute(connection, "ALTER TABLE pf_staff_post DROP CONSTRAINT fk_pf_staff_post_role; "
                                + "ALTER TABLE pf_staff_post ADD CONSTRAINT fk_pf_staff_post_role FOREIGN KEY(role_code) REFERENCES pf_post_template(role_code) ON DELETE CASCADE");
                    } else {
                        execute(connection, "CREATE FUNCTION reject_retirement() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'fixture refuses template deletion'; END$$; "
                                + "CREATE TRIGGER reject_retirement BEFORE DELETE ON pf_post_template FOR EACH ROW EXECUTE FUNCTION reject_retirement()");
                    }
                    Map<String, String> before = snapshot(connection);
                    String constraint = scalar(connection, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='pf_staff_post'::regclass AND conname='fk_pf_staff_post_role'");
                    assertThatThrownBy(() -> migrate(pg, false)).hasStackTraceContaining("Design post retirement compatibility failed");
                    assertThat(snapshot(connection)).isEqualTo(before);
                    assertThat(scalar(connection, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='pf_staff_post'::regclass AND conname='fk_pf_staff_post_role'"))
                            .isEqualTo(constraint);
                    assertThat(scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='20261127.1200' AND success")).isEqualTo("0");
                }
            }
        }
    }

    @Test
    void freshInstallWithNoHistoricalPostsDoesNotNeedForeignKeyRelaxation() throws Exception {
        try (var pg = TestPostgres.database("postgres:17-alpine")) {
            pg.start();
            try (var connection = connect(pg)) {
                fixture(connection, false, 1);
                migrate(pg, false);
                assertRetired(connection);
                assertThat(scalar(connection, "SELECT convalidated::text FROM pg_constraint WHERE conrelid='pf_staff_post'::regclass AND conname='fk_pf_staff_post_role'"))
                        .isEqualTo("true");
            }
        }
    }

    private void migrate(PostgreSQLContainer<?> pg, boolean springAutoConfiguration) throws Exception {
        Path directory = temporary.resolve(pg.getDatabaseName());
        Files.createDirectories(directory);
        for (String name : List.of(FORWARD, ROLES, COMPATIBILITY)) {
            Files.writeString(directory.resolve(name.substring(name.lastIndexOf('/') + 1)), resource(name));
        }
        String location = "filesystem:" + directory;
        if (springAutoConfiguration) {
            var source = new SimpleDriverDataSource(new org.postgresql.Driver(), pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                    .withBean(javax.sql.DataSource.class, () -> source)
                    .withPropertyValues("spring.flyway.locations=" + location, "spring.flyway.baseline-on-migrate=true",
                            "spring.flyway.baseline-version=20261126.1199", "spring.flyway.clean-disabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(Flyway.class).getConfiguration().getCallbacks())
                                .anyMatch(callback -> callback.getCallbackName().equals("design-post-retirement-1200"));
                    });
        } else {
            FlywayProperties properties = new FlywayProperties();
            properties.setUrl(pg.getJdbcUrl());
            properties.setUser(pg.getUsername());
            properties.setPassword(pg.getPassword());
            properties.setLocations(List.of(location));
            properties.setBaselineOnMigrate(true);
            properties.setBaselineVersion("20261126.1199");
            new PlatformMigrationConfiguration().platformMigration(properties).run(new DefaultApplicationArguments());
        }
    }

    private static void fixture(Connection connection, boolean historicalPosts, int... tenants) throws Exception {
        String bootstrap = resource("db/migration/platform-bootstrap/V20260904.001__platform_system_infra.sql");
        for (String table : List.of("system_role", "system_role_menu", "system_user_role", "system_menu", "system_users")) {
            var definition = Pattern.compile("CREATE TABLE " + table + " \\(.*?\\n\\);", Pattern.DOTALL).matcher(bootstrap);
            assertThat(definition.find()).as("original schema for %s", table).isTrue();
            execute(connection, definition.group());
            execute(connection, "ALTER TABLE " + table + " ADD PRIMARY KEY(id)");
        }
        execute(connection, resource("db/migration/pf/V20261022.702__pf_org.sql"));
        execute(connection, "INSERT INTO pf_org_type(code,name,domain) VALUES('store','Store','franchise')");
        execute(connection, "INSERT INTO pf_post_template(role_code,domain,org_types,line,lead) VALUES "
                + "('hq-designer','franchise','store','custom-design',false),('hq-design-lead','franchise','store','custom-lead',true),"
                + "('hq-design-admin','franchise','store','custom-admin',false),('boss','franchise','store','management',true)");
        execute(connection, "INSERT INTO system_menu(id,name,type,permission) VALUES(500,'Custom permission',3,'custom:read')");
        // The old fixed seed ID is legitimately occupied by an unrelated role.
        execute(connection, "INSERT INTO system_role(id,name,code,sort,status,type,tenant_id) VALUES(810009,'Boss','boss',1,0,1,1)");
        for (int tenant : tenants) {
            int base = tenant * 1000;
            execute(connection, "INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(" + tenant + ",'ORG'," + tenant + ",'store')");
            execute(connection, "INSERT INTO system_users(id,username,nickname,tenant_id) VALUES(" + (base + 1) + ",'user-" + tenant + "','Fixture'," + tenant + ")");
            for (int index = 0; index < 3; index++) {
                String code = List.of("hq-designer", "hq-design-lead", "hq-design-admin").get(index);
                int role = base + 101 + index;
                execute(connection, "INSERT INTO system_role(id,name,code,sort,data_scope,data_scope_dept_ids,status,type,tenant_id,remark) VALUES(" + role
                        + ",'Custom " + index + "','" + code + "'," + index + ",2,'[" + tenant + "]'," + (index == 1 ? 1 : 0) + ",1," + tenant + ",'Keep custom scope')");
                execute(connection, "INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(" + (base + 110 + index) + "," + role + ",500," + tenant + ")");
                execute(connection, "INSERT INTO system_user_role(id,user_id,role_id,tenant_id) VALUES(" + (base + 120 + index) + "," + (base + 1) + "," + role + "," + tenant + ")");
            }
            if (historicalPosts) {
                execute(connection, "INSERT INTO pf_staff_post(id,user_id,org_id,role_code,status,revoked_at,revoked_by) VALUES("
                        + (base + 10) + "," + (base + 1) + "," + tenant + ",'hq-designer','ACTIVE',NULL,NULL),("
                        + (base + 11) + "," + (base + 1) + "," + tenant + ",'hq-design-lead','REVOKED','2026-01-02T00:00:00Z',123)");
            }
            execute(connection, "INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(" + (base + 12) + "," + (base + 1) + "," + tenant + ",'boss')");
        }
    }

    private static Map<String, String> backup(Connection connection) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : BACKUPS.keySet()) {
            String filter = table.equals("pf_post_template") ? "domain='franchise' AND role_code IN " + CODES
                    : table.equals("system_role") ? "code IN " + CODES
                    : "role_id IN (SELECT id FROM system_role WHERE code IN " + CODES + ")";
            result.put(table, rows(connection, table, filter, table.equals("pf_post_template") ? "role_code" : "id"));
        }
        return result;
    }

    private static void loadBackup(Connection connection, Map<String, String> backup) throws Exception {
        for (var entry : BACKUPS.entrySet()) {
            execute(connection, "CREATE TEMP TABLE " + entry.getValue() + " (LIKE " + entry.getKey() + " INCLUDING ALL)");
            try (var statement = connection.prepareStatement("INSERT INTO " + entry.getValue()
                    + " SELECT * FROM jsonb_populate_recordset(NULL::" + entry.getKey() + ", ?::jsonb)")) {
                statement.setString(1, backup.get(entry.getKey()));
                statement.executeUpdate();
            }
        }
    }

    private static Map<String, String> snapshot(Connection connection) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("system_role", "system_role_menu", "system_user_role", "pf_post_template", "pf_staff_post")) {
            result.put(table, rows(connection, table, "true", table.equals("pf_post_template") ? "role_code" : "id"));
        }
        return result;
    }

    private static void assertRetired(Connection connection) throws Exception {
        assertThat(scalar(connection, "SELECT count(*) FROM pf_staff_post WHERE status='ACTIVE' AND role_code IN " + CODES)).isEqualTo("0");
        assertThat(scalar(connection, "SELECT count(*) FROM pf_post_template WHERE role_code IN " + CODES)).isEqualTo("0");
        assertThat(scalar(connection, "SELECT count(*) FROM system_role WHERE code IN " + CODES)).isEqualTo("0");
        assertThat(scalar(connection, "SELECT count(*) FROM system_role_menu")).isEqualTo("0");
        assertThat(scalar(connection, "SELECT count(*) FROM system_user_role")).isEqualTo("0");
    }

    private static String rows(Connection connection, String table, String filter, String order) throws Exception {
        return scalar(connection, "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY " + order + "),'[]'::jsonb)::text FROM " + table + " t WHERE " + filter);
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static Connection connect(PostgreSQLContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static String resource(String name) throws Exception {
        try (var stream = DesignPostRetirementPgIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as("resource %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
