package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 C 评审 P2-2 补强：**路由级**验收（不是只测 OrgPointService 原语）。
 * 覆盖：公司池建任务零写旧表 + request_resolution 透传 → 终态按 ACCEPTED 走公司池 settle/release；
 * 无绑定无个人扣费的任务拒绝结算（防幻影退款）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiJobOrgChargeRoutingTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    /** 记录型假端口：只关心 settle/release/findOperationByRef。 */
    static class RecordingPort implements OrgPointPort {
        final List<Long> settled = new ArrayList<>();
        final List<Long> released = new ArrayList<>();
        long settleQuantity;
        /** 可配置：默认无绑定（走原个人链/拒绝）；公司池用例设为 77。 */
        Long bindingOperationId;

        @Override
        public Long findOperationByRef(String refType, long refId, String subject) {
            return bindingOperationId;
        }

        @Override
        public Settlement settle(long operationId, long actualQuantity) {
            settled.add(operationId);
            settleQuantity = actualQuantity;
            return new Settlement(operationId, 4, 4 - actualQuantity, false);
        }

        @Override
        public Settlement release(long operationId) {
            released.add(operationId);
            return new Settlement(operationId, 4, 4, false);
        }

        @Override public long reserve(ReserveCommand command) { throw new UnsupportedOperationException(); }
        @Override public Settlement settleByBizKey(String k, long q) { throw new UnsupportedOperationException(); }
        @Override public Settlement releaseByBizKey(String k) { throw new UnsupportedOperationException(); }
        @Override public PoolSummary summary(long orgId) { throw new UnsupportedOperationException(); }
        @Override public List<LedgerEntry> ledger(long o, Long a, String s, String m, int p, int z) { throw new UnsupportedOperationException(); }
        @Override public List<OperationView> operations(long orgId, int p, int z) { throw new UnsupportedOperationException(); }
        @Override public long monthUsed(long o, long u, String m) { throw new UnsupportedOperationException(); }
        @Override public MyUsage myUsage(long o, long u, String m, int p, int z) { throw new UnsupportedOperationException(); }
        @Override public Long memberQuotaOf(long o, long u) { throw new UnsupportedOperationException(); }
        @Override public List<MemberQuota> memberQuotas(long orgId) { throw new UnsupportedOperationException(); }
        @Override public void setMemberQuota(long o, long u, long c, long op) { throw new UnsupportedOperationException(); }
        @Override public void clearMemberQuota(long o, long u, long op) { throw new UnsupportedOperationException(); }
        @Override public List<MemberPermission> memberPermissions(long orgId) { throw new UnsupportedOperationException(); }
        @Override public void setMemberPermission(long o, long u, List<String> a, long op) { throw new UnsupportedOperationException(); }
        @Override public void clearMemberPermission(long o, long u, long op) { throw new UnsupportedOperationException(); }
        @Override public void assertSubjectAllowed(long o, long u, String s) { throw new UnsupportedOperationException(); }
        @Override public long quoteCredits(String s, long q) { return 2 * q; }
        @Override public void bindOperation(String origin, long orgId, long actor, Long post, String refType,
                                            long refId, String subject, long operationId, String bizKey) { }
        @Override public PoolSummary ensurePool(long orgId) { throw new UnsupportedOperationException(); }
        @Override public boolean grantOnboardOnce(long orgId, long c, String n) { throw new UnsupportedOperationException(); }
        @Override public PoolSummary adjust(long orgId, long c, String k, String n, Long admin) { throw new UnsupportedOperationException(); }
    }

    private JdbcTemplate jdbc;
    private AiJobSettlementService settlement;
    private final RecordingPort port = new RecordingPort();

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration")
                .load().migrate();
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        settlement = new AiJobSettlementService(dataSource, tx, null, null, null, message -> 0L);
        ReflectionTestUtils.setField(settlement, "orgPointPort", port);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE ai_job, ai_job_result, ai_job_settlement, design_point_ledger, "
                + "design_billing_binding");
        port.settled.clear();
        port.released.clear();
        port.settleQuantity = 0;
        port.bindingOperationId = null;
    }

    /** 直接落一条"公司池任务"行（charge_id NULL、prompt 不计费），模拟桥侧建任务结果。 */
    private long newOrgJob(int count, String resolution) {
        long jobId = System.nanoTime() % 1_000_000_000L + 1000;
        jdbc.update("INSERT INTO ai_job (id, user_id, project_ref, phase, status, requested_count, "
                        + "output_prefix, idempotency_key, unit_point_cost, total_point_cost, charge_id, "
                        + "prompt_pricing_version, request_resolution) "
                        + "VALUES (?, 1, 'proj-1', 'FLAT', 'RUNNING', ?, 'ai-quarantine/x', ?, 2, ?, NULL, 0, ?)",
                jobId, count, "key-" + count, 2L * count, resolution);
        return jobId;
    }

    private void accept(long jobId, int slots) {
        for (int i = 1; i <= slots; i++) {
            jdbc.update("INSERT INTO ai_job_result (id, job_id, attempt_no, provider_code, "
                            + "candidate_slot_no, content_sha256, object_key, mime_type, size_bytes, "
                            + "validation_state) VALUES (?, ?, 1, 'fake', ?, ?, ?, 'image/png', 10, 'ACCEPTED')",
                    jobId * 100 + i, jobId, i, "sha" + i, "k/" + i);
        }
    }

    @Test
    void orgJobCreatesWithoutPersonalDebitAndKeepsResolution() {
        long jobId = newOrgJob(2, "4K");
        var row = jdbc.queryForMap("SELECT charge_id, request_resolution, total_point_cost, "
                + "prompt_pricing_version FROM ai_job WHERE id = ?", jobId);
        assertThat(row.get("charge_id")).isNull();
        assertThat(row.get("request_resolution")).isEqualTo("4K");
        assertThat(((Number) row.get("total_point_cost")).longValue()).isEqualTo(4);
        assertThat(((Number) row.get("prompt_pricing_version")).intValue()).isZero();
        Integer personal = jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE biz_type='ai_job' AND biz_id=?",
                Integer.class, String.valueOf(jobId));
        assertThat(personal).isZero();
    }

    @Test
    void terminalSettlesOrgPoolByAcceptedCount() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(2, "2K");
        jdbc.update("UPDATE ai_job SET status='RUNNING' WHERE id=?", jobId);
        accept(jobId, 1);

        settlement.settle(jobId, "SETTLE");

        assertThat(port.settled).containsExactly(77L);
        assertThat(port.settleQuantity).isEqualTo(1);
        assertThat(port.released).isEmpty();
    }

    @Test
    void zeroAcceptedReleasesOrgPool() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(2, "2K");
        jdbc.update("UPDATE ai_job SET status='RUNNING' WHERE id=?", jobId);

        settlement.settle(jobId, "SETTLE");

        assertThat(port.released).containsExactly(77L);
        assertThat(port.settled).isEmpty();
    }

    @Test
    void jobWithoutAnyChargeSourceRejected() {
        // 既无个人扣费流水、也无公司池绑定 → 拒绝结算（防幻影退款）
        long jobId = 9001L;
        jdbc.update("INSERT INTO ai_job (id, user_id, project_ref, phase, status, requested_count, "
                + "output_prefix, unit_point_cost, total_point_cost, charge_id) "
                + "VALUES (?, 1, 'p', 'FLAT', 'RUNNING', 2, 'ai-quarantine/x', 2, 4, NULL)", jobId);

        assertThatThrownBy(() -> settlement.settle(jobId, "SETTLE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝结算");
    }
}
