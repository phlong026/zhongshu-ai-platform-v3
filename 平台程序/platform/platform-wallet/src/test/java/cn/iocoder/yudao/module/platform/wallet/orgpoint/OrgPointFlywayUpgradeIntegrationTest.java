package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrgPointFlywayUpgradeIntegrationTest extends PlatformPgIntegrationTest {

    private static final String LOCATION = "db/migration/platform-wallet/";
    private static final String LEGACY_911_HEADER =
            "-- V20261114.911 B独立公司积分池：账户 + 三态流水 + 员工月度额度 + 业务权限矩阵";
    private static final String[] MIGRATIONS = {
            "V20261114.911__org_point_pool.sql",
            "V20261114.912__design_billing_binding.sql",
            "V20261114.913__design_prompt_receipt.sql",
            "V20261114.914__org_point_subject_price.sql"
    };

    @Autowired private DataSource dataSource;
    @TempDir Path legacyLocation;

    @Test
    void legacy911ChecksumValidatesAndHistoricalQuotaSurvivesCurrentMigration() throws Exception {
        String schema = "orgupgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);

        for (String migration : MIGRATIONS) {
            String sql = read(migration);
            if (migration.startsWith("V20261114.911")) {
                sql = sql.replaceFirst("(?m)^-- V20261114\\.911 .*?$", LEGACY_911_HEADER);
            }
            Files.writeString(legacyLocation.resolve(migration), sql, StandardCharsets.UTF_8);
        }

        Flyway legacy = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("filesystem:" + legacyLocation.toAbsolutePath()).load();
        assertThat(legacy.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT checksum FROM " + schema
                + ".flyway_schema_history WHERE version='20261114.911'", Integer.class))
                .isEqualTo(-1_698_126_318);
        jdbc.update("INSERT INTO " + schema + ".org_point_account(id,org_id,balance) VALUES (1,7,99)");
        jdbc.update("INSERT INTO " + schema + ".org_member_quota(id,org_id,user_id,monthly_credits) "
                + "VALUES (2,7,8,25)");
        jdbc.execute("CREATE TABLE " + schema + ".system_menu (id BIGINT PRIMARY KEY,name TEXT,permission TEXT,"
                + "type INT,sort INT,parent_id BIGINT,path TEXT,icon TEXT,component TEXT,component_name TEXT,"
                + "status INT,visible BOOLEAN,keep_alive BOOLEAN,always_show BOOLEAN,creator TEXT,updater TEXT)");

        Flyway current = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                // 测试 schema 只模拟 911~914 的历史；更早且低于当前版本的迁移按已忽略处理。
                .ignoreMigrationPatterns("*:ignored")
                .locations("classpath:" + LOCATION).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        current.validate();
        assertThat(jdbc.queryForObject("SELECT balance FROM " + schema
                + ".org_point_account WHERE org_id=7", Long.class)).isEqualTo(99L);
        assertThat(jdbc.queryForObject("SELECT monthly_credits FROM " + schema
                + ".org_member_quota WHERE org_id=7 AND user_id=8", Long.class)).isEqualTo(25L);
    }

    private static String read(String name) throws Exception {
        try (var stream = new ClassPathResource(LOCATION + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
