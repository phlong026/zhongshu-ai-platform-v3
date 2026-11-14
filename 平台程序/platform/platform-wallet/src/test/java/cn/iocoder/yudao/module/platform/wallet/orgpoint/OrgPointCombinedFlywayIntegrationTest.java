package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 用生产组合中的真实 pf + wallet + design migration locations 防止全库版本或菜单主键冲突。 */
class OrgPointCombinedFlywayIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void actualWalletAndDesignLocationsMigrateTogether() {
        String schema = "combined_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("""
                CREATE TABLE %s.system_menu(
                  id BIGINT PRIMARY KEY,name TEXT,permission TEXT,type INT,sort INT,parent_id BIGINT,
                  path TEXT,icon TEXT,component TEXT,component_name TEXT,status INT,visible BOOLEAN,
                  keep_alive BOOLEAN,always_show BOOLEAN,creator TEXT,updater TEXT,deleted INT DEFAULT 0)
                """.formatted(schema));
        jdbc.execute("CREATE TABLE " + schema + ".system_role(id BIGINT PRIMARY KEY,code TEXT,tenant_id BIGINT,"
                + "status INT DEFAULT 0,deleted INT DEFAULT 0)");
        jdbc.execute("""
                CREATE TABLE %s.system_role_menu(
                  id BIGINT PRIMARY KEY,role_id BIGINT,menu_id BIGINT,creator TEXT,updater TEXT,tenant_id BIGINT,
                  deleted INT DEFAULT 0)
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE %s.system_dept(
                  id BIGINT PRIMARY KEY,name TEXT,parent_id BIGINT,sort INT,status INT,deleted INT,
                  creator TEXT,updater TEXT,tenant_id BIGINT)
                """.formatted(schema));

        Path designLocation = Path.of(System.getProperty("user.dir"))
                .resolve("../../domains/franchise/franchise-design/src/main/resources/db/migration/franchise-design-remote")
                .normalize();
        assertThat(designLocation.resolve("V20261111.961__design_operations_admin.sql")).exists();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration/pf", "classpath:db/migration/platform-wallet",
                        "filesystem:" + designLocation.toAbsolutePath())
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        flyway.validate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".system_menu WHERE id IN (960021,960022,960023,917000,917001,917002,917003,917004)",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".system_menu WHERE id BETWEEN 917000 AND 917004 AND visible=true", Integer.class)).isZero();
    }

    @Test
    void combinedLocationsArePartOfTheProductionMigrationAllowlist() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).resolve("../..").normalize();
        var properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(root.resolve(
                "yudao-platform-server/src/main/resources/application-pg.properties"))) {
            properties.load(reader);
        }
        String locations = properties.getProperty("spring.flyway.locations");
        assertThat(locations).contains("classpath:db/migration/pf")
                .contains("classpath:db/migration/platform-wallet")
                .contains("classpath:db/migration/franchise")
                .contains("classpath:db/migration/franchise-design-remote");

        String policy = Files.readString(root.resolve(
                "yudao-platform-server/src/main/java/cn/iocoder/yudao/platformserver/wiring/PlatformEnvironmentPolicy.java"));
        for (String location : locations.split(",")) {
            assertThat(policy).contains('"' + location + '"');
        }
    }
}
