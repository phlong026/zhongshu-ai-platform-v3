package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.accesscode.AccessCodeRetirementReportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 D / B-14 验收：激活码退役清单（未兑换/已消费/停用/过期 + 既有有效授权数），只读不改状态。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCodeRetirementReportTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private AccessCodeRetirementReportService service;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/identity")
                .load().migrate();
        service = new AccessCodeRetirementReportService(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE design_access_code, design_access_code_batch, design_access_grant");
    }

    private void batch(long batchId) {
        jdbc.update("INSERT INTO design_access_code_batch (id, quantity, delivery_mode, issued_by, purpose_note) "
                + "VALUES (?, 10, 'INLINE', 'hq-ops', ?)", batchId, "门店" + batchId);
    }

    private void code(long codeId, long batchId, String status, boolean consumed, boolean expired) {
        jdbc.update("INSERT INTO design_access_code (id, batch_id, code_hash, pepper_version, code_mask, "
                        + "status, consumed_at, expires_at) VALUES (?, ?, ?, 'v1', 'ABCD-****', ?, "
                        + (consumed ? "now()" : "NULL") + ", "
                        + (expired ? "now() - interval '1 day'" : "NULL") + ")",
                codeId, batchId, "hash" + codeId, status);
    }

    @Test
    void reportCountsAllStates() {
        batch(8001L);
        code(9001L, 8001L, "ACTIVE", false, false);      // 未兑换
        code(9002L, 8001L, "ACTIVE", false, true);       // 未兑换但已过期
        code(9003L, 8001L, "CONSUMED", true, false);     // 已消费
        code(9004L, 8001L, "DISABLED", false, false);    // 停用
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status) VALUES (?, ?, 'ACTIVE')",
                7001L, 501L);
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status) VALUES (?, ?, 'REVOKED')",
                7002L, 502L);

        var report = service.report();

        assertThat(report.totalCodes()).isEqualTo(4);
        assertThat(report.activeUnused()).isEqualTo(2);
        assertThat(report.consumed()).isEqualTo(1);
        assertThat(report.disabled()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.activeGrants()).isEqualTo(1);
        assertThat(report.byBatch()).singleElement().satisfies(b -> {
            assertThat(b.batchId()).isEqualTo(8001L);
            assertThat(b.activeUnused()).isEqualTo(2);
            assertThat(b.consumed()).isEqualTo(1);
            assertThat(b.disabled()).isEqualTo(1);
            assertThat(b.expired()).isEqualTo(1);
        });
    }

    @Test
    void reportIsReadOnly() {
        batch(8002L);
        code(9011L, 8002L, "ACTIVE", false, false);
        service.report();
        assertThat(jdbc.queryForObject("SELECT status FROM design_access_code WHERE id=9011", String.class))
                .isEqualTo("ACTIVE");
    }
}
