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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        /** prompt 预留：bizKey → operationId。 */
        final java.util.Map<String, Long> promptOperations = new java.util.HashMap<>();
        final java.util.Set<Long> promptTerminal = new java.util.HashSet<>();
        final java.util.Map<String, String> promptReceipts = new java.util.HashMap<>();
        long nextPromptOperation = 88L;

        @Override public java.util.List<PendingPromptOperation> findPendingPromptOperations(long jobId) {
            return promptOperations.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("design:prompt:" + jobId + ":"))
                    .filter(e -> !promptTerminal.contains(e.getValue()))
                    .map(e -> new PendingPromptOperation(e.getValue(), e.getKey()))
                    .sorted(java.util.Comparator.comparingLong(PendingPromptOperation::operationId))
                    .toList();
        }

        @Override public int countPromptOperations(String bizKeyPrefix) {
            return (int) promptOperations.keySet().stream()
                    .filter(k -> k.startsWith(bizKeyPrefix)).count();
        }

        @Override public void savePromptReceipt(long jobId, int attemptNo, String callId,
                                                String responseHash, String outcome, Long operationId) {
            String key = jobId + ":" + attemptNo;
            String existing = promptReceipts.putIfAbsent(key, responseHash + "|" + outcome);
            if (existing != null && !existing.equals(responseHash + "|" + outcome)) {
                throw new IllegalStateException("Prompt receipt conflict");
            }
        }

        @Override
        public Long findOperationByRef(String refType, long refId, String subject) {
            return bindingOperationId;
        }

        @Override
        public Settlement settle(long operationId, long actualQuantity) {
            settled.add(operationId);
            promptTerminal.add(operationId);
            settleQuantity = actualQuantity;
            return new Settlement(operationId, 4, 4 - actualQuantity, false);
        }

        @Override
        public Settlement release(long operationId) {
            released.add(operationId);
            promptTerminal.add(operationId);
            return new Settlement(operationId, 4, 4, false);
        }

        @Override public long reserve(ReserveCommand command) {
            if ("ai_prompt".equals(command.subject())) {
                long operationId = nextPromptOperation++;
                promptOperations.put(command.bizKey(), operationId);
                return operationId;
            }
            throw new UnsupportedOperationException();
        }

        @Override public Long findOperationByBizKey(String bizKey) { return promptOperations.get(bizKey); }

        @Override public PoolBinding findBinding(String refType, long refId, String subject) {
            if (bindingOperationId == null || !"ai_job".equals(refType)) {
                return null;
            }
            return new PoolBinding(7001L, 9L, null, bindingOperationId);
        }
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
        // 创建路由也走真实 createJobWithOrgCharge：只桩掉重放查询（返回 null=无重放）
        cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService orchestration =
                mock(cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService.class);
        when(orchestration.findRequestReplay(anyLong(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        settlement = new AiJobSettlementService(dataSource, tx, null, null, orchestration,
                message -> 0L);
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
        port.promptOperations.clear();
        port.promptTerminal.clear();
        port.promptReceipts.clear();
        port.nextPromptOperation = 88L;
    }

    /** 走真实创建路由（createJobWithOrgCharge）：charge_id NULL、prompt 不计费、resolution 透传。 */
    private long newOrgJob(int count, String resolution) {
        long jobId = settlement.createJobWithOrgCharge(1L, "FLAT", count, "key-" + count,
                "proj-1", resolution, 2, 2L * count);
        jdbc.update("UPDATE ai_job SET status='RUNNING' WHERE id=?", jobId);
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

        settlement.settle(jobId, "SETTLE");

        assertThat(port.released).containsExactly(77L);
        assertThat(port.settled).isEmpty();
    }

    private void seedPromptAttempt(long jobId, int attemptNo, long token) {
        jdbc.update("UPDATE ai_job SET status='RUNNING', fencing_token=?, "
                + "claim_expires_at=now()+interval '10 minute' WHERE id=?", token, jobId);
        jdbc.update("INSERT INTO ai_job_attempt (id, job_id, attempt_no, worker_id, fencing_token) "
                + "VALUES (?, ?, ?, 'w-1', ?)", jobId * 1000 + attemptNo, jobId, attemptNo, token);
    }

    @Test
    void orgPromptSuccessChargesPoolOnce() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 1234L);

        assertThat(settlement.reservePromptCall(jobId, 1, 1234L)).isEqualTo("ORG_RESERVED");
        // 幂等：重复预留不新增 operation
        assertThat(settlement.reservePromptCall(jobId, 1, 1234L)).isEqualTo("ORG_RESERVED");
        assertThat(port.promptOperations).hasSize(1);

        assertThat(settlement.finishPromptCall(jobId, 1, 1234L, "SUCCEEDED",
                "plan-" + jobId, "a".repeat(64))).isTrue();
        assertThat(port.settled).containsExactly(88L);
        assertThat(port.settleQuantity).isEqualTo(1);
    }

    @Test
    void orgPromptNotSentReleasesReservation() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 2222L);
        settlement.reservePromptCall(jobId, 1, 2222L);

        settlement.finishPromptCall(jobId, 1, 2222L, "NOT_SENT");

        assertThat(port.released).containsExactly(88L);
        assertThat(port.settled).isEmpty();
    }

    @Test
    void orgPromptUnknownKeepsPending() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 3333L);
        settlement.reservePromptCall(jobId, 1, 3333L);

        settlement.finishPromptCall(jobId, 1, 3333L, "UNKNOWN");

        assertThat(port.settled).isEmpty();
        assertThat(port.released).isEmpty();
    }

    @Test
    void notSentRetryCreatesNewOperation() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 5555L);
        assertThat(settlement.reservePromptCall(jobId, 1, 5555L)).isEqualTo("ORG_RESERVED");
        settlement.finishPromptCall(jobId, 1, 5555L, "NOT_SENT");
        assertThat(port.released).containsExactly(88L);

        assertThat(settlement.reservePromptCall(jobId, 1, 5555L)).isEqualTo("ORG_RESERVED");
        assertThat(port.promptOperations).hasSize(2);
        assertThat(port.promptOperations.keySet()).anyMatch(k -> k.endsWith(":r1"));

        settlement.finishPromptCall(jobId, 1, 5555L, "SUCCEEDED",
                "plan-" + jobId, "b".repeat(64));
        assertThat(port.settled).containsExactly(89L);
    }

    @Test
    void unknownBlocksNewAttemptReservation() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 6666L);
        settlement.reservePromptCall(jobId, 1, 6666L);
        settlement.finishPromptCall(jobId, 1, 6666L, "UNKNOWN");

        // 新 attempt（新 token）抢到租约后不允许再次预留（旧调用可能已执行）
        jdbc.update("UPDATE ai_job SET fencing_token=7777, claim_expires_at=now()+interval '10 minute' "
                + "WHERE id=?", jobId);
        jdbc.update("INSERT INTO ai_job_attempt (id, job_id, attempt_no, worker_id, fencing_token) "
                + "VALUES (?, ?, 2, 'w-2', 7777)", jobId * 1000 + 2, jobId);
        assertThatThrownBy(() -> settlement.reservePromptCall(jobId, 2, 7777L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止重投");
    }

    @Test
    void invalidOutcomeRejected() {
        port.bindingOperationId = 77L;
        long jobId = newOrgJob(1, "2K");
        seedPromptAttempt(jobId, 1, 8888L);
        settlement.reservePromptCall(jobId, 1, 8888L);
        assertThatThrownBy(() -> settlement.finishPromptCall(jobId, 1, 8888L, "COMPLETED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid prompt outcome");
        assertThatCode(() -> settlement.finishPromptCall(jobId, 1, 8888L, "SUCCEEDED",
                "plan-" + jobId, "c".repeat(64))).doesNotThrowAnyException();
        assertThatThrownBy(() -> settlement.finishPromptCall(jobId, 1, 8888L, "SUCCEEDED",
                "plan-" + jobId, "d".repeat(64)))
                .isInstanceOf(RuntimeException.class); // 收据 hash 冲突
    }

    @Test
    void nonOrgJobPromptIsExempt() {
        long jobId = newOrgJob(1, "2K"); // 无绑定 → 非公司池任务
        seedPromptAttempt(jobId, 1, 4444L);
        assertThat(settlement.reservePromptCall(jobId, 1, 4444L)).isEqualTo("EXEMPT");
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
