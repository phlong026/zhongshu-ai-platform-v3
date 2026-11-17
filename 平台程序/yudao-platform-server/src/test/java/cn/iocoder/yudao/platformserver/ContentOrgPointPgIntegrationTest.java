package cn.iocoder.yudao.platformserver;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.content.content.ContentQuotaService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskStore;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderException;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.ScriptRewritePort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * B 内容任务与公司积分池的真实 PostgreSQL 跨模块契约。
 *
 * <p>不 mock {@link OrgPointService}：任务、账户和只追加账本共用同一 DataSource 与
 * {@link DataSourceTransactionManager}。只有真实供应商边界被可控 fixture 取代，测试不访问外网。
 */
class ContentOrgPointPgIntegrationTest {

    private static final long ORG = 10_001L;
    private static final long USER = 20_001L;
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.15-alpine"))
            .withDatabaseName("content_wallet_contract")
            .withUsername("contract_owner")
            .withPassword("contract-test-password");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static ContentTaskStore store;
    private static OrgPointService points;
    private static OrgPointPriceService prices;

    @BeforeAll
    static void startDatabaseAndInstallRealSchemas() throws Exception {
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new ContentTaskStore(jdbc);
        prices = new OrgPointPriceService(dataSource, transactionManager);
        points = new OrgPointService(dataSource, transactionManager, mock(AuditPort.class), prices);

        executeResource("db/migration/franchise/V20261102.812__fr_content_core.sql");
        executeResource("db/migration/platform-wallet/V20261114.911__org_point_pool.sql");
        executeResource("db/migration/platform-wallet/V20261114.914__org_point_subject_price.sql");
        String contentPoolMigration = resourceText(
                "db/migration/franchise/V20261114.916__fr_content_pool_biz_key.sql");
        // 本契约只需内容/账本表；权限菜单种子属于完整应用迁移测试的责任。
        jdbc.execute(contentPoolMigration.substring(0, contentPoolMigration.indexOf("-- 人工账务核验权限")));
        executeResource("db/migration/franchise/V20261117.961__content_result_delivery.sql");
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_content_insert ON fr_content_task");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_content_insert()");
        jdbc.execute("TRUNCATE fr_content_task, fr_content_ai_usage, org_point_ledger, "
                + "org_point_account, org_member_quota, org_member_permission, org_point_subject_price");
        points.ensurePoolAccount(ORG);
        assertThat(points.grantOnboardOnce(ORG, 100, "cross-module-test")).isTrue();
    }

    @Test
    void taskInsertFailureRollsBackReserveBalanceLedgerAndTaskTogether() {
        jdbc.execute("CREATE FUNCTION reject_content_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced task binding failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_content_insert BEFORE INSERT ON fr_content_task "
                + "FOR EACH ROW EXECUTE FUNCTION reject_content_insert()");
        ContentTaskService service = transactionalService(successfulRewrite());

        assertThatThrownBy(() -> service.createCopy(ORG, USER, "原稿", "改写", null, null))
                .hasStackTraceContaining("forced task binding failure");

        assertThat(balance()).isEqualTo(100);
        assertThat(count("org_point_ledger")).isOne(); // 仅开通赠点，没有泄漏 RESERVE
        assertThat(ledgerTypeCount("RESERVE")).isZero();
        assertThat(count("fr_content_task")).isZero();
    }

    @Test
    void successfulTaskSettlesUsingFrozenQuantityPriceAndBillingMonth() {
        prices.publish("ai_video_768p", 500_000L, 1L, "test-old-price");
        ContentTaskService service = transactionalService(successfulRewrite());
        FrContentTaskDO task = service.createAiVideo(ORG, USER, "室内走镜", List.of(), "768P", 8, "16:9");
        Long operationId = operationId(task.getPoolBizKey());

        // 将已完成的历史预扣夹具放到上月，模拟跨月才收到成功回执。
        jdbc.execute("ALTER TABLE org_point_ledger DISABLE TRIGGER trg_org_point_ledger_append_only");
        try {
            jdbc.update("UPDATE org_point_ledger SET billing_month='2026-10', "
                    + "operation_time='2026-10-31 15:59:59+00' WHERE operation_id=? AND type='RESERVE'", operationId);
        } finally {
            jdbc.execute("ALTER TABLE org_point_ledger ENABLE TRIGGER trg_org_point_ledger_append_only");
        }
        prices.publish("ai_video_768p", 2_000_000L, 1L, "test-new-price");
        assertThat(store.succeedSync(task.getId(), ORG, "fake", "fake", "fixture", null,
                "{\"outputSeconds\":4}")).isTrue();

        service.reconcilePool(store.byId(ORG, task.getId()));

        assertThat(balance()).isEqualTo(98); // 旧价 0.5 x 实际 4 秒，而非新价 2 x 4
        assertThat(jdbc.queryForObject("SELECT available_delta FROM org_point_ledger "
                + "WHERE operation_id=? AND type='SETTLE'", Long.class, operationId)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT billing_month FROM org_point_ledger WHERE operation_id=? "
                + "ORDER BY type", String.class, operationId)).containsOnly("2026-10");
        assertThat(jdbc.queryForObject("SELECT price_snapshot->>'priceVersion' FROM org_point_ledger "
                + "WHERE operation_id=? AND type='SETTLE'", String.class, operationId)).isEqualTo("db-v1");
    }

    @Test
    void explicitProviderRejectionReleasesTheWholeReserve() {
        ScriptRewritePort rejected = call -> {
            throw new ContentProviderException.Rejection("fixture rejected", 400, "FIXTURE_REJECTED");
        };
        ContentTaskService service = transactionalService(rejected);
        FrContentTaskDO task = service.createCopy(ORG, USER, "原稿", null, null, null);

        service.submitOne(task);

        // copy 的明确未受理会转本地 rules 免费产物，但不得实扣公司积分。
        assertThat(store.byId(ORG, task.getId()).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(store.byId(ORG, task.getId()).getPoolTerminalIntent()).isEqualTo("RELEASE");
        assertThat(balance()).isEqualTo(100);
        assertThat(ledgerTypeCount("RELEASE")).isOne();
        assertThat(ledgerTypeCount("SETTLE")).isZero();
    }

    @Test
    void unknownProviderOutcomeKeepsReservePending() {
        ScriptRewritePort unknown = call -> {
            throw new ContentProviderException.Uncertain("fixture transport outcome unknown");
        };
        ContentTaskService service = transactionalService(unknown);
        FrContentTaskDO task = service.createCopy(ORG, USER, "原稿", null, null, null);

        service.submitOne(task);

        assertThat(store.byId(ORG, task.getId()).getStatus()).isEqualTo("SUBMISSION_UNCERTAIN");
        assertThat(balance()).isEqualTo(98);
        assertThat(ledgerTypeCount("RESERVE")).isOne();
        assertThat(terminalCount(task.getPoolBizKey())).isZero();
    }

    @Test
    void concurrentManualVerificationCreatesExactlyOneMutuallyExclusiveTerminal() throws Exception {
        ScriptRewritePort unknown = call -> {
            throw new ContentProviderException.Uncertain("fixture transport outcome unknown");
        };
        ContentTaskService service = transactionalService(unknown);
        FrContentTaskDO task = service.createCopy(ORG, USER, "原稿", null, null, null);
        service.submitOne(task);
        PostSnapshot verifier = new PostSnapshot(31L, USER, ORG, "store", "boss", null, false, Set.of());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> delivered = pool.submit(() -> verifyAfter(start, service, verifier, task,
                    "DELIVERED", 1L, "manual-delivered"));
            Future<Boolean> rejected = pool.submit(() -> verifyAfter(start, service, verifier, task,
                    "NOT_DELIVERED", null, "manual-not-delivered"));
            start.countDown();

            assertThat(List.of(delivered.get(), rejected.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(terminalCount(task.getPoolBizKey())).isOne();
        assertThat(store.byId(ORG, task.getId()).getManualVerifyDecision())
                .isIn("DELIVERED", "NOT_DELIVERED");
    }

    @Test
    void legacyDailyUsageDoesNotLimitCompanyPointFundedTask() {
        jdbc.update("INSERT INTO fr_content_ai_usage(id,org_id,service,day,count) VALUES (1,?,?,?,1000000)",
                ORG, "copy", LocalDate.now(ZoneId.of("Asia/Shanghai")));
        ContentTaskService service = transactionalService(successfulRewrite());

        FrContentTaskDO task = service.createCopy(ORG, USER, "原稿", null, null, null);

        assertThat(task.getPoolBizKey()).startsWith("content:");
        assertThat(balance()).isEqualTo(98);
    }

    @Test
    void legacyMonthlyMemberQuotaDoesNotLimitCompanyPointFundedTask() {
        jdbc.update("INSERT INTO org_member_quota(id,org_id,user_id,monthly_credits) VALUES (1,?,?,1)", ORG, USER);
        ContentTaskService service = transactionalService(successfulRewrite());

        FrContentTaskDO task = service.createCopy(ORG, USER, "原稿", null, null, null);

        assertThat(task.getPoolBizKey()).startsWith("content:");
        assertThat(balance()).isEqualTo(98);
    }

    @Test
    void subjectPermissionStillRejectsTaskWithoutChangingFunds() {
        jdbc.update("INSERT INTO org_member_permission(id,org_id,user_id,allowed_subjects) VALUES (1,?,?, '[]')",
                ORG, USER);
        ContentTaskService service = transactionalService(successfulRewrite());

        assertThatThrownBy(() -> service.createCopy(ORG, USER, "原稿", null, null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未被授权")
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(1_072_000_015));

        assertThat(balance()).isEqualTo(100);
        assertThat(ledgerTypeCount("RESERVE")).isZero();
        assertThat(count("fr_content_task")).isZero();
    }

    @Test
    void insufficientCompanyBalanceStillRejectsTaskWithoutLeakingFacts() {
        jdbc.update("UPDATE org_point_account SET balance=1 WHERE org_id=?", ORG);
        ContentTaskService service = transactionalService(successfulRewrite());

        assertThatThrownBy(() -> service.createCopy(ORG, USER, "原稿", null, null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不足")
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(1_072_000_011));

        assertThat(balance()).isOne();
        assertThat(ledgerTypeCount("RESERVE")).isZero();
        assertThat(count("fr_content_task")).isZero();
    }

    private static ContentTaskService transactionalService(ScriptRewritePort rewrite) {
        ContentProviderProperties properties = new ContentProviderProperties();
        ContentQuotaService legacyUsage = new ContentQuotaService(jdbc, properties);
        ContentTaskService target = new ContentTaskService(store, legacyUsage,
                null, null, rewrite, null, null, null, null, properties, points);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (ContentTaskService) factory.getProxy();
    }

    private static ScriptRewritePort successfulRewrite() {
        return call -> new ScriptRewritePort.Result("可控夹具改写结果", "fixture-v1");
    }

    private static boolean verifyAfter(CountDownLatch start, ContentTaskService service,
                                       PostSnapshot verifier, FrContentTaskDO task,
                                       String decision, Long units, String key) throws InterruptedException {
        start.await();
        try {
            service.manualVerify(verifier, task.getId(), decision, units,
                    "人工核验", "可控供应商证据", key);
            return true;
        } catch (ServiceException expectedConflict) {
            return false;
        }
    }

    private static long balance() {
        return jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?", Long.class, ORG);
    }

    private static long operationId(String bizKey) {
        return jdbc.queryForObject("SELECT operation_id FROM org_point_ledger WHERE biz_key=?", Long.class, bizKey);
    }

    private static int ledgerTypeCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE type=?", Integer.class, type);
    }

    private static int terminalCount(String bizKey) {
        return jdbc.queryForObject("SELECT count(*) FROM org_point_ledger t JOIN org_point_ledger r "
                        + "ON r.operation_id=t.operation_id WHERE r.biz_key=? AND r.type='RESERVE' "
                        + "AND t.type IN ('SETTLE','RELEASE')", Integer.class, bizKey);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void executeResource(String resource) throws Exception {
        jdbc.execute(resourceText(resource));
    }

    private static String resourceText(String resource) throws Exception {
        ClassPathResource file = new ClassPathResource(resource);
        assertThat(file.exists()).as(resource).isTrue();
        return new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
