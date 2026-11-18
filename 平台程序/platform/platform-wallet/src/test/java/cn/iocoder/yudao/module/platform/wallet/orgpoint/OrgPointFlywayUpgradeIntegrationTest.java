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

@org.springframework.test.context.TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
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
        assertThat(current.migrate().migrationsExecuted).isEqualTo(4);
        current.validate();
        assertThat(jdbc.queryForObject("SELECT balance FROM " + schema
                + ".org_point_account WHERE org_id=7", Long.class)).isEqualTo(99L);
        assertThat(jdbc.queryForObject("SELECT monthly_credits FROM " + schema
                + ".org_member_quota WHERE org_id=7 AND user_id=8", Long.class)).isEqualTo(25L);
    }

    @Test
    void usedSimulationThenFormalRechargeRequiresReviewAndNoOpenReserve() throws Exception {
        String schema = "simreview_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var st = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                st.execute("CREATE SCHEMA " + schema);
                st.execute("SET LOCAL search_path TO " + schema);
                st.execute(read(MIGRATIONS[0]));
                st.execute(read("V20261118.990__isolated_simulated_package.sql"));
                st.execute("INSERT INTO org_point_account(id,org_id,balance) VALUES (1,7,205)");
                st.execute("""
                        INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta,operation_id,price_snapshot)
                        VALUES (10,7,'package-sim:1:7:8:9:one','SIMULATED_PACKAGE',120,NULL,'{"priceFen":990}'),
                               (11,7,'task-reserve','RESERVE',-15,51,NULL),
                               (12,7,'formal-paid','RECHARGE',100,NULL,NULL)
                        """);
                String transfer = read("V20261118.991__transfer_reviewed_simulated_points.sql");
                var before = connection.setSavepoint();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(transfer)).hasMessageContaining("per-receipt finance evidence");
                connection.rollback(before);
                st.execute("INSERT INTO org_point_simulation_review VALUES(10,105,3,205,'fixture finance','ledger review 118',now())");
                before = connection.setSavepoint();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(transfer)).hasMessageContaining("Outstanding task reservations");
                connection.rollback(before);
                st.execute("INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta,operation_id) VALUES(13,7,'task-settle','SETTLE',0,51)");
                before = connection.setSavepoint();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute(transfer)).hasMessageContaining("per-receipt finance evidence");
                connection.rollback(before);
                st.execute("UPDATE org_point_simulation_review SET reviewed_ledger_count=4 WHERE source_ledger_id=10");
                st.execute(transfer);
                try (var rows = st.executeQuery("SELECT a.balance,s.test_points FROM org_point_account a JOIN org_point_simulated_purchase s USING(org_id)")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(100);
                    assertThat(rows.getLong(2)).isEqualTo(105);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String read(String name) throws Exception {
        try (var stream = new ClassPathResource(LOCATION + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
