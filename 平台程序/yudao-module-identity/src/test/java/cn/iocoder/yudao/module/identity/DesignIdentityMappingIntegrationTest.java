package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.mapping.DesignIdentityMappingService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 D / B-15 验收：旧设计账号 → 员工 → 组织 映射（真实 PostgreSQL）。
 * 覆盖：唯一手机号+单一有效任职→候选；缺手机/无员工/多员工/多岗位→未匹配；生成幂等不覆盖人工结论；
 * 人工确认/驳回；报告口径与未匹配清单。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignIdentityMappingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private DesignIdentityMappingService service;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/identity")
                .load().migrate();
        // 底座/平台表 shim（生产来自 ruoyi-vue-pro.sql 与 pf 迁移；identity 模块测试类路径不含 pf 资源）
        // system_users.deleted 用 smallint，与 2026-11-09 生产实测一致
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users (id BIGINT PRIMARY KEY, "
                + "mobile VARCHAR(20), deleted SMALLINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pf_staff_post (id BIGINT PRIMARY KEY, "
                + "user_id BIGINT NOT NULL, org_id BIGINT NOT NULL, role_code VARCHAR(64) NOT NULL, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', "
                + "create_time TIMESTAMPTZ NOT NULL DEFAULT now())");
        service = new DesignIdentityMappingService(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE design_identity_mapping, access_code_redemption, design_access_code, "
                + "design_access_code_batch, design_access_grant, system_users, pf_staff_post");
    }

    private void seedStaff(long userId, String mobile, long orgId, String role) {
        jdbc.update("INSERT INTO system_users (id, mobile) VALUES (?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET mobile = EXCLUDED.mobile", userId, mobile);
        jdbc.update("INSERT INTO pf_staff_post (id, user_id, org_id, role_code, status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')", userId * 10 + orgId, userId, orgId, role);
    }

    private void seedRedemption(long accountId, String phone) {
        long batchId = 7000 + accountId;
        long codeId = 8000 + accountId;
        jdbc.update("INSERT INTO design_access_code_batch (id, quantity, delivery_mode, issued_by, "
                + "purpose_note) VALUES (?, 1, 'INLINE', 'hq-ops', ?)", batchId, "门店" + accountId);
        jdbc.update("INSERT INTO design_access_code (id, batch_id, code_hash, pepper_version, code_mask, "
                + "status) VALUES (?, ?, ?, 'v1', 'ABCD-****', 'CONSUMED')",
                codeId, batchId, "hash" + accountId);
        jdbc.update("INSERT INTO access_code_redemption (id, code_id, account_id, appid, openid, "
                + "contact_phone) VALUES (?, ?, ?, 'wx-app', ?, ?)",
                accountId * 100 + 1, codeId, accountId, "openid" + accountId, phone);
    }

    @Test
    void uniquePhoneAndSinglePostBecomesCandidate() {
        seedStaff(501L, "13800000001", 1001L, "boss");
        seedRedemption(9001L, "13800000001");

        var result = service.generate();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.matched()).isEqualTo(1);
        var row = service.list("CANDIDATE", null, 1, 10).get(0);
        assertThat(row.matchedUserId()).isEqualTo(501L);
        assertThat(row.matchedOrgId()).isEqualTo(1001L);
        assertThat(row.matchType()).isEqualTo("PHONE");
    }

    @Test
    void missingPhoneUnmatched() {
        seedRedemption(9002L, null);
        service.generate();
        var row = service.list("UNMATCHED", null, 1, 10).get(0);
        assertThat(row.matchedUserId()).isNull();
        assertThat(row.note()).contains("手机号");
    }

    @Test
    void duplicateMobileOrMultiplePostsUnmatched() {
        seedStaff(502L, "13800000002", 1001L, "boss");
        seedStaff(503L, "13800000002", 1002L, "sales"); // 同手机号两个账号 → 歧义
        seedStaff(504L, "13800000003", 1001L, "boss");
        seedStaff(504L, "13800000003", 1002L, "sales"); // 同员工两个有效任职 → 歧义
        seedRedemption(9003L, "13800000002");
        seedRedemption(9004L, "13800000003");

        service.generate();

        assertThat(service.list("UNMATCHED", null, 1, 10)).hasSize(2);
    }

    @Test
    void generateIsIdempotentAndKeepsManualDecision() {
        seedStaff(505L, "13800000004", 1001L, "boss");
        seedRedemption(9005L, "13800000004");
        service.generate();
        service.confirm(9005L, 505L, 1001L, "人工核对", "admin-1");

        var second = service.generate(); // 已存在行跳过，不覆盖 CONFIRMED

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(service.list("CONFIRMED", 1001L, 1, 10)).hasSize(1);
    }

    @Test
    void rejectMovesToTransitionList() {
        seedRedemption(9006L, null);
        service.generate();
        service.reject(9006L, "无法归属，保留只读续期", "admin-2");

        var report = service.report();
        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.unmatchedRows()).extracting(DesignIdentityMappingService.MappingRow::accountId)
                .containsExactly(9006L);
    }

    @Test
    void confirmRequiresGeneratedRow() {
        seedStaff(510L, "13800000010", 1001L, "boss");
        assertThatThrownBy(() -> service.confirm(9999L, 510L, 1001L, "依据", "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或已处置");
    }

    @Test
    void grantOnlyAccountStillEnteredAsUnmatched() {
        // P1①：人工审批开通不产生 redemption，仅 ACTIVE grant —— 必须进映射全集
        jdbc.update("INSERT INTO design_access_grant (id, account_id, status) VALUES (?, ?, 'ACTIVE')",
                61_001L, 9100L);

        var result = service.generate();

        assertThat(result.created()).isEqualTo(1);
        var row = service.list("UNMATCHED", null, 1, 10).get(0);
        assertThat(row.accountId()).isEqualTo(9100L);
        assertThat(row.note()).contains("人工审批开通");
    }

    @Test
    void multiplePhonesForSameAccountIsConflict() {
        seedStaff(507L, "13800000006", 1001L, "boss");
        seedRedemption(9101L, "13800000006");
        seedRedemption(9102L, "13800000007"); // 同 account 不同号
        jdbc.update("UPDATE access_code_redemption SET account_id = 9101 WHERE account_id = 9102");

        service.generate();

        var row = service.list("UNMATCHED", null, 1, 10).get(0);
        assertThat(row.note()).contains("多个不同手机号");
    }

    @Test
    void confirmRejectsInvalidEmployeeOrOrg() {
        seedStaff(508L, "13800000008", 1001L, "boss");
        seedRedemption(9103L, "13800000008");
        service.generate();

        assertThatThrownBy(() -> service.confirm(9103L, 508L, 1002L, "错误组织", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无有效任职");
        assertThatThrownBy(() -> service.confirm(9103L, 999_999L, 1001L, "不存在员工", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(9103L, 508L, 1001L, " ", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("依据");
    }

    @Test
    void invalidMobileSegmentIsNotUsedAsEvidence() {
        seedStaff(511L, "10012345678", 1001L, "boss"); // 11 位但非手机号段
        seedRedemption(9105L, "10012345678");
        service.generate();
        var row = service.list("UNMATCHED", null, 1, 10).get(0);
        assertThat(row.matchedUserId()).isNull();
        assertThat(row.note()).contains("手机号");
    }

    @Test
    void rejectedStateCannotBeReprocessed() {
        seedStaff(512L, "13800000011", 1001L, "boss");
        seedRedemption(9106L, null);
        service.generate();
        service.reject(9106L, "无法归属", "admin");
        assertThatThrownBy(() -> service.confirm(9106L, 512L, 1001L, "试图确认", "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.reject(9106L, "再次驳回", "admin"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStateCannotBeReprocessed() {
        seedStaff(509L, "13800000009", 1001L, "boss");
        seedRedemption(9104L, "13800000009");
        service.generate();
        service.confirm(9104L, 509L, 1001L, "确认", "admin");
        assertThatThrownBy(() -> service.confirm(9104L, 509L, 1001L, "再次确认", "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.reject(9104L, "驳回", "admin"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportCountsAndMatchTypes() {
        seedStaff(506L, "13800000005", 1001L, "boss");
        seedRedemption(9007L, "13800000005");
        seedRedemption(9008L, null);
        service.generate();
        service.confirm(9007L, 506L, 1001L, "确认", "admin-3");

        var report = service.report();
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(report.unmatched()).isEqualTo(1);
        assertThat(report.byMatchType()).containsEntry("MANUAL", 1).containsEntry("NONE", 1);
    }
}
