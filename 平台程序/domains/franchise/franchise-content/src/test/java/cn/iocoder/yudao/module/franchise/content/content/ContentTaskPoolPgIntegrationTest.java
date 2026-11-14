package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.LinkParsePort;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_VERIFICATION_CONFLICT;

/**
 * 内容任务与公司积分池的 PostgreSQL 边界回归。
 * 此处不复制积分算法；只验证任务 CAS、预扣键持久化及恢复扫描所依赖的真实 SQL。
 */
@Import(ContentTaskStore.class)
class ContentTaskPoolPgIntegrationTest extends FranchisePgIntegrationTest {

    @Resource
    private ContentTaskStore store;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTasksAndInstallLedgerContract() {
        jdbc.execute("TRUNCATE fr_content_task");
        // content 模块不依赖 wallet 实现；本表只提供恢复查询所需的三列合同。
        jdbc.execute("CREATE TABLE IF NOT EXISTS org_point_ledger ("
                + "id BIGINT PRIMARY KEY, operation_id BIGINT, biz_key VARCHAR(160), type VARCHAR(16))");
        jdbc.execute("TRUNCATE org_point_ledger");
    }

    @Test
    void migrationPersistsPoolKeyAndConcurrentDuplicateCompletionWinsOnce() throws Exception {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "digital", "{\"text\":\"口播\"}", null);
        store.markPoolReservation(taskId, 100L, "content:" + taskId + ":digital", 12L);
        jdbc.update("UPDATE fr_content_task SET status='RUNNING', provider_task_id='vendor-1' WHERE id=?", taskId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> completeAfter(start, taskId, 12));
            Future<Boolean> duplicate = pool.submit(() -> completeAfter(start, taskId, 13));
            start.countDown();

            assertThat(List.of(first.get(), duplicate.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        FrContentTaskDO completed = store.byId(100L, taskId);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getPoolBizKey()).isEqualTo("content:" + taskId + ":digital");
        assertThat(completed.getPoolReservedUnits()).isEqualTo(12L);
        assertThat(completed.getResultSnapshot()).containsAnyOf("\"durationSec\": 12", "\"durationSec\": 13");
    }

    @Test
    void concurrentClaimAllowsOnlyOneProviderDispatch() throws Exception {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> claimAfter(start, taskId));
            Future<Boolean> duplicate = pool.submit(() -> claimAfter(start, taskId));
            start.countDown();
            assertThat(List.of(first.get(), duplicate.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.byId(100L, taskId).getStatus()).isEqualTo("SUBMITTING");
    }

    @Test
    void concurrentPollLeaseAllowsOnlyOneInstanceAndFailedCannotReturnToUnknown() throws Exception {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "digital", "{\"text\":\"口播\"}", null);
        jdbc.update("UPDATE fr_content_task SET status='RUNNING', provider_task_id='vendor-poll' WHERE id=?", taskId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> claimPollAfter(start, taskId, "instance-a"));
            Future<Boolean> duplicate = pool.submit(() -> claimPollAfter(start, taskId, "instance-b"));
            start.countDown();
            assertThat(List.of(first.get(), duplicate.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.fail(taskId, 100L, "明确失败")).isTrue();
        assertThat(store.markUncertain(taskId, 100L, "迟到网络异常")).isFalse();
        assertThat(store.byId(100L, taskId).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void legacyDailyUsageCounterCanExceedOldLimitWithoutRejecting() {
        ContentQuotaService usage = new ContentQuotaService(jdbc,
                new cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties());

        assertThat(usage.recordUsage(100L, "digital", 4_000)).isEqualTo(4_000);
        assertThat(usage.recordUsage(100L, "digital", 200)).isEqualTo(4_200);
        assertThat(usage.usedToday(100L, "digital")).isEqualTo(4_200);
    }

    @Test
    void unknownKeepsReservePendingAndTerminalRecoveryStopsAfterLedgerAuditTerminal() {
        long taskId = ContentTaskStore.newId();
        String bizKey = "content:" + taskId + ":digital";
        store.insert(taskId, 100L, 7L, "digital", "{\"text\":\"口播\"}", null);
        store.markPoolReservation(taskId, 100L, bizKey, 12L);
        jdbc.update("UPDATE fr_content_task SET status='SUBMISSION_UNCERTAIN', provider_task_id='vendor-2' WHERE id=?",
                taskId);
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (1, 91, ?, 'RESERVE')",
                bizKey);

        assertThat(pending(20)).isEmpty();
        assertThat(store.findInFlight(20)).extracting(FrContentTaskDO::getId).contains(taskId);

        assertThat(store.succeedPolled(taskId, 100L, "https://cdn/video.mp4", "{\"durationSec\":12}"))
                .isTrue();
        assertThat(pending(20)).extracting(FrContentTaskDO::getId).containsExactly(taskId);

        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (2, 91, ?, 'SETTLE')",
                bizKey + ":settled");
        assertThat(pending(20)).isEmpty();
    }

    @Test
    void taskAndPoolKeyRollbackTogetherAtBusinessTransactionBoundary() {
        long taskId = ContentTaskStore.newId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
            store.markPoolReservation(taskId, 100L, "content:" + taskId + ":copy", 1L);
            status.setRollbackOnly();
        });

        assertThat(store.byId(100L, taskId)).isNull();
    }

    @Test
    void poisonFirstPageBacksOffSoLaterPendingSettlementCanProgress() {
        long laterTaskId = 0;
        for (int i = 1; i <= 21; i++) {
            long taskId = ContentTaskStore.newId();
            laterTaskId = taskId;
            String bizKey = "content:" + taskId + ":copy";
            store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
            store.markPoolReservation(taskId, 100L, bizKey, 1L);
            assertThat(store.succeedSync(taskId, 100L, "deepseek", "deepseek", null, null,
                    "{\"text\":\"完成\"}")).isTrue();
            jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (?, ?, ?, 'RESERVE')",
                    i, 1000 + i, bizKey);
            if (i <= 20) {
                store.deferPoolReconcile(taskId, 100L, "永久失败");
            }
        }

        assertThat(pending(20)).extracting(FrContentTaskDO::getId)
                .containsExactly(laterTaskId);
    }

    @Test
    void undoRefusesToDropPoolFactsWhileTaskReferencesReserve() {
        long taskId = ContentTaskStore.newId();
        String bizKey = "content:" + taskId + ":copy";
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        store.markPoolReservation(taskId, 100L, bizKey, 1L);
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (1, 99, ?, 'RESERVE')", bizKey);

        assertThatThrownBy(this::executeUndoInRollbackTransaction)
                .hasStackTraceContaining("fr_content_task 存在积分池资金事实，禁止回滚 916");
        assertThat(store.byId(100L, taskId).getPoolBizKey()).isEqualTo(bizKey);
    }

    @Test
    void undoCanDropRecoveryColumnsOnlyWhenNoPoolFactsRemain() throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                connection.createStatement().execute(undoSql());
                var result = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name='fr_content_task' AND column_name='pool_biz_key'");
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
                var permission = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM system_menu WHERE permission='franchise:content:verify'");
                assertThat(permission.next()).isTrue();
                assertThat(permission.getInt(1)).isZero();
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void migrationSeedsDedicatedManualVerificationPermission() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM system_menu WHERE id=710504 AND permission='franchise:content:verify'",
                Integer.class)).isOne();
    }

    @Test
    void undoAlsoRefusesOrphanPendingContentReserveWithoutTaskKey() {
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) "
                + "VALUES (1, 101, 'content:orphan:copy', 'RESERVE')");

        assertThatThrownBy(this::executeUndoInRollbackTransaction)
                .hasStackTraceContaining("fr_content_task 存在积分池资金事实，禁止回滚 916");
    }

    @Test
    void providerSuccessThenRealDatabaseWriteFailurePersistsUnknownAndPendingReserve() {
        long taskId = ContentTaskStore.newId();
        String bizKey = "content:" + taskId + ":link_resolution";
        store.insert(taskId, 100L, 7L, "link_parse", "{\"url\":\"https://example/video\"}", null);
        store.markPoolReservation(taskId, 100L, bizKey, 1L);
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (1, 77, ?, 'RESERVE')",
                bizKey);
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_content_success() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.id = " + taskId + " AND NEW.status = 'SUCCEEDED' THEN "
                + "RAISE EXCEPTION 'simulated result persistence failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER trg_reject_content_success BEFORE UPDATE ON fr_content_task "
                + "FOR EACH ROW EXECUTE FUNCTION reject_content_success()");
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        LinkParsePort link = (url, audit) -> new LinkParsePort.ParsedLink(
                "douyin", "https://cdn/video", null, "title", "text", null, null, 1000);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                link, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        try {
            service.submitOne(store.byId(100L, taskId));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_content_success ON fr_content_task");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_content_success()");
        }

        FrContentTaskDO task = store.byId(100L, taskId);
        assertThat(task.getStatus()).isEqualTo("SUBMISSION_UNCERTAIN");
        assertThat(task.getDispatchStartedAt()).isNotNull();
        assertThat(pending(20)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type='RESERVE'", Integer.class))
                .isOne();
        verifyNoInteractions(orgPoint);
    }

    @Test
    void rulesFallbackPersistsFreeReleaseIntentForRecovery() {
        long taskId = ContentTaskStore.newId();
        String bizKey = "content:" + taskId + ":copy";
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        store.markPoolReservation(taskId, 100L, bizKey, 1L);
        assertThat(store.claim(taskId)).isTrue();
        assertThat(store.markDispatchStarted(taskId, 100L)).isTrue();
        assertThat(store.succeedRules(taskId, 100L, "{\"text\":\"本地改写\",\"engine\":\"rules\"}"))
                .isTrue();
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (1, 88, ?, 'RESERVE')",
                bizKey);

        FrContentTaskDO task = store.byId(100L, taskId);
        assertThat(task.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(task.getPoolTerminalIntent()).isEqualTo("RELEASE");
        assertThat(pending(20)).extracting(FrContentTaskDO::getId)
                .containsExactly(taskId);
    }

    @Test
    void concurrentReconcileClaimHasSingleLeaseAndSuccessClearsStaleError() throws Exception {
        long taskId = terminalPendingTask("copy", "{\"sourceText\":\"文案\"}", "content:lease:copy", 501);
        jdbc.update("UPDATE fr_content_task SET pool_reconcile_error='旧错误' WHERE id=?", taskId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<List<FrContentTaskDO>> first = pool.submit(() -> claimReconcileAfter(start, "node-a"));
            Future<List<FrContentTaskDO>> second = pool.submit(() -> claimReconcileAfter(start, "node-b"));
            start.countDown();
            assertThat(first.get().size() + second.get().size()).isOne();
        } finally {
            pool.shutdownNow();
        }
        String token = jdbc.queryForObject("SELECT pool_reconcile_lease_token FROM fr_content_task WHERE id=?",
                String.class, taskId);
        assertThat(store.completePoolReconcile(taskId, 100L, token)).isTrue();
        FrContentTaskDO cleared = store.byId(100L, taskId);
        assertThat(cleared.getPoolReconcileError()).isNull();
        assertThat(cleared.getPoolReconcileLeaseToken()).isNull();
    }

    @Test
    void manualUnknownVerificationIsExclusiveOrgScopedAndTransactionRollbackSafe() throws Exception {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        store.markPoolReservation(taskId, 100L, "content:" + taskId + ":copy", 1L);
        jdbc.update("UPDATE fr_content_task SET status='SUBMISSION_UNCERTAIN' WHERE id=?", taskId);

        assertThat(store.manualVerify(taskId, 999L, 8L, 18L, "NOT_DELIVERED", null,
                "跨组织", "无交付回执", "wrong-org")).isFalse();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> delivered = pool.submit(() -> verifyAfter(start, taskId, "DELIVERED", 1L, "verify-a"));
            Future<Boolean> notDelivered = pool.submit(() -> verifyAfter(start, taskId, "NOT_DELIVERED", null, "verify-b"));
            start.countDown();
            assertThat(List.of(delivered.get(), notDelivered.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        FrContentTaskDO closed = store.byId(100L, taskId);
        assertThat(closed.getManualVerifyOperatorUserId()).isEqualTo(8L);
        assertThat(closed.getManualVerifyOperatorPostId()).isEqualTo(18L);
        assertThat(closed.getManualVerifyReason()).isEqualTo("人工核验");
        assertThat(closed.getManualVerifyEvidence()).isEqualTo("供应商证据");

        long rollbackId = ContentTaskStore.newId();
        store.insert(rollbackId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        store.markPoolReservation(rollbackId, 100L, "content:" + rollbackId + ":copy", 1L);
        jdbc.update("UPDATE fr_content_task SET status='SUBMISSION_UNCERTAIN' WHERE id=?", rollbackId);
        OrgPointPort failingPool = mock(OrgPointPort.class);
        when(failingPool.settleByBizKey("content:" + rollbackId + ":copy", 1L))
                .thenThrow(new IllegalStateException("模拟账本结算失败"));
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), failingPool);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.manualVerify(verifier, rollbackId, "DELIVERED", 1L,
                        "人工核验", "供应商证据", "rollback-key")))
                .hasMessageContaining("账本结算失败");
        assertThat(store.byId(100L, rollbackId).getStatus()).isEqualTo("SUBMISSION_UNCERTAIN");
        assertThat(store.byId(100L, rollbackId).getManualVerifyDecision()).isNull();
    }

    @Test
    void manualVerificationUsesFrozenRequestQuantityAndMissingPoolFactRollsBack() {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "ai_video", "{\"duration\":8}", null);
        store.markPoolReservation(taskId, 100L, "content:" + taskId + ":ai_video_768p", 8L);
        jdbc.update("UPDATE fr_content_task SET status='SUBMISSION_UNCERTAIN' WHERE id=?", taskId);
        OrgPointPort missingPool = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), missingPool);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.manualVerify(verifier, taskId, "DELIVERED", 7L,
                        "人工核验", "供应商证据", "frozen-under")))
                .hasMessageContaining("提交内容不合法");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.manualVerify(verifier, taskId, "DELIVERED", 8L,
                        "人工核验", "供应商证据", "missing-pool")))
                .hasMessageContaining("积分池资金事实缺失");

        FrContentTaskDO rolledBack = store.byId(100L, taskId);
        assertThat(rolledBack.getStatus()).isEqualTo("SUBMISSION_UNCERTAIN");
        assertThat(rolledBack.getManualVerifyDecision()).isNull();
    }

    @Test
    void manualVerificationUsesPersistedReservationEvenWhenCurrentEstimatorDiffers() {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "digital", "{\"text\":\"一二三四五六七八\"}", null);
        String bizKey = "content:" + taskId + ":digital";
        // 模拟历史版本预扣9秒，而当前脚本估算公式会得到4秒；核验不得按新公式重算。
        jdbc.update("UPDATE fr_content_task SET pool_biz_key=?, pool_reserved_units=9, "
                + "status='SUBMISSION_UNCERTAIN' WHERE id=?", bizKey, taskId);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        when(orgPoint.settleByBizKey(bizKey, 9L))
                .thenReturn(new OrgPointPort.Settlement(91L, 9L, 0L, false));
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.manualVerify(verifier, taskId, "DELIVERED", 9L,
                        "人工核验", "供应商证据", "historic-reserve"));

        assertThat(store.byId(100L, taskId).getManualVerifyActualUnits()).isEqualTo(9L);
        verify(orgPoint).settleByBizKey(bizKey, 9L);
    }

    @Test
    void manualVerificationFailsClosedWhenLegacyTaskHasNoFrozenReservation() {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        jdbc.update("UPDATE fr_content_task SET pool_biz_key=?, status='SUBMISSION_UNCERTAIN' WHERE id=?",
                "content:" + taskId + ":copy", taskId);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.manualVerify(verifier, taskId, "DELIVERED", 1L,
                        "人工核验", "供应商证据", "legacy-without-units")))
                .hasMessageContaining("积分池资金事实缺失");

        verifyNoInteractions(orgPoint);
        assertThat(store.byId(100L, taskId).getManualVerifyDecision()).isNull();
    }

    @Test
    void automaticSettlementNeverExceedsFrozenReservation() {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "ai_video", "{\"duration\":8}", null);
        String bizKey = "content:" + taskId + ":ai_video_768p";
        jdbc.update("UPDATE fr_content_task SET pool_biz_key=?, pool_reserved_units=8, status='SUCCEEDED', "
                + "result_snapshot='{\"outputSeconds\":12}'::jsonb WHERE id=?", bizKey, taskId);
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        when(orgPoint.settleByBizKey(bizKey, 8L))
                .thenReturn(new OrgPointPort.Settlement(92L, 8L, 0L, false));
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);

        service.reconcilePool(store.byId(100L, taskId));

        verify(orgPoint).settleByBizKey(bizKey, 8L);
    }

    @Test
    void concurrentDifferentTasksSharingManualIdempotencyKeyReturnsOneStableConflict() throws Exception {
        long firstTask = uncertainCopyTask("content:idem:first");
        long secondTask = uncertainCopyTask("content:idem:second");
        OrgPointPort orgPoint = mock(OrgPointPort.class);
        when(orgPoint.releaseByBizKey(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new OrgPointPort.Settlement(91L, 0L, 1L, false));
        ContentTaskService service = new ContentTaskService(store, mock(ContentQuotaService.class),
                null, null, null, null, null, null, null, new ContentProviderProperties(), orgPoint);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = pool.submit(() -> verifyThroughServiceAfter(start, service, firstTask));
            Future<Integer> second = pool.submit(() -> verifyThroughServiceAfter(start, service, secondTask));
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(0, TASK_VERIFICATION_CONFLICT.getCode());
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean completeAfter(CountDownLatch start, long taskId, int duration) throws InterruptedException {
        start.await();
        return store.succeedPolled(taskId, 100L, "https://cdn/" + duration + ".mp4",
                "{\"durationSec\":" + duration + "}");
    }

    private boolean claimAfter(CountDownLatch start, long taskId) throws InterruptedException {
        start.await();
        return store.claim(taskId);
    }

    private boolean claimPollAfter(CountDownLatch start, long taskId, String token) throws InterruptedException {
        start.await();
        return store.claimPoll(taskId, token, 7_200);
    }

    private List<FrContentTaskDO> pending(int limit) {
        return store.claimTerminalWithPendingPool(limit, "test-" + System.nanoTime(), 120);
    }

    private long terminalPendingTask(String kind, String input, String bizKey, long ledgerId) {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, kind, input, null);
        store.markPoolReservation(taskId, 100L, bizKey, 1L);
        assertThat(store.succeedSync(taskId, 100L, "deepseek", "deepseek", null, null,
                "{\"text\":\"完成\"}")).isTrue();
        jdbc.update("INSERT INTO org_point_ledger(id, operation_id, biz_key, type) VALUES (?, ?, ?, 'RESERVE')",
                ledgerId, ledgerId, bizKey);
        return taskId;
    }

    private List<FrContentTaskDO> claimReconcileAfter(CountDownLatch start, String token) throws InterruptedException {
        start.await();
        return store.claimTerminalWithPendingPool(20, token, 120);
    }

    private boolean verifyAfter(CountDownLatch start, long taskId, String decision, Long units, String key)
            throws InterruptedException {
        start.await();
        return store.manualVerify(taskId, 100L, 8L, 18L, decision, units,
                "人工核验", "供应商证据", key);
    }

    private long uncertainCopyTask(String bizKey) {
        long taskId = ContentTaskStore.newId();
        store.insert(taskId, 100L, 7L, "copy", "{\"sourceText\":\"文案\"}", null);
        store.markPoolReservation(taskId, 100L, bizKey, 1L);
        jdbc.update("UPDATE fr_content_task SET status='SUBMISSION_UNCERTAIN' WHERE id=?", taskId);
        return taskId;
    }

    private int verifyThroughServiceAfter(CountDownLatch start, ContentTaskService service, long taskId)
            throws InterruptedException {
        start.await();
        PostSnapshot verifier = new PostSnapshot(18L, 8L, 100L, "store", "boss", null, false, Set.of());
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.manualVerify(verifier, taskId, "NOT_DELIVERED", null,
                            "人工核验", "供应商证据", "shared-idem"));
            return 0;
        } catch (ServiceException e) {
            return e.getCode();
        }
    }

    private void executeUndoInRollbackTransaction() throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                connection.createStatement().execute(undoSql());
            } finally {
                connection.rollback();
            }
        }
    }

    private String undoSql() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration-undo/franchise/V20261114.916__undo_fr_content_pool_biz_key.sql");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
