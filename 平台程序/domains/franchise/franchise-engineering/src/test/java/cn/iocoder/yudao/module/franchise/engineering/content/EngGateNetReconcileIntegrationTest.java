package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.engineering.todo.EngTodoProvider;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.SaleContractService;
import cn.iocoder.yudao.module.franchise.sales.SaleRequirementService;
import cn.iocoder.yudao.module.franchise.sales.UnifiedStageAccountAdapter;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.GATE_NOT_MET;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M09-T03（批次#35）：退款后未来资金门槛按净实收 + 门槛不足待办 + M10-T07 收款只算一次核对。
 * 真实链（真 SaleContractService.collect→BusinessReceiptPortTestStub→fr_sale_stage_receipt）：
 * 1. 收满→退款回缺→requireStartGate 拒 GATE_NOT_MET（净口径=Σ分笔−Σ已核实退款，同源
 *    UnifiedStageAccountPort）；补收恢复后放行；
 * 2. 门槛不足投影 fr-eng:gate-shortfall 待办（owner 可见/非 owner 不可见/补收后消失）；
 * 3. 单次计数结构固化：fr_sale_stage_receipt 一笔 receipt 至多归一期（uk 结构直插反证）、
 *    期次读数不串（资格/门禁/待办同源一个 port）。
 */
@Import({EngProjectService.class, EngMaterialService.class, EngPlanService.class, EngCollectionService.class, EngFollowupService.class,
        EngClosureService.class, EngExpenseService.class, EngTodoProvider.class,
        SaleContractService.class, SaleRequirementService.class,
        cn.iocoder.yudao.module.franchise.sales.SalesAccess.class, UnifiedStageAccountAdapter.class,
        FranchiseCommands.class, CommandLogImpl.class,
        EngGateNetReconcileIntegrationTest.Config.class})
class EngGateNetReconcileIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {

        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort() {
            var port = org.mockito.Mockito.mock(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.class);
            org.mockito.Mockito.when(port.validateReference(org.mockito.ArgumentMatchers.any(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor.class),
                    org.mockito.ArgumentMatchers.eq(9001L), org.mockito.ArgumentMatchers.eq(java.util.Set.of("CONTRACT_ATTACHMENT")))).thenReturn(true);
            org.mockito.Mockito.when(port.validateReference(org.mockito.ArgumentMatchers.any(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor.class),
                    org.mockito.ArgumentMatchers.eq(9002L), org.mockito.ArgumentMatchers.eq(java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING", "FIN_VOUCHER")))).thenReturn(true);
            org.mockito.Mockito.when(port.validateReference(org.mockito.ArgumentMatchers.any(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor.class),
                    org.mockito.ArgumentMatchers.eq(9002L), org.mockito.ArgumentMatchers.eq(java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING")))).thenReturn(true);
            org.mockito.Mockito.when(port.validateReference(org.mockito.ArgumentMatchers.any(cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor.class),
                    org.mockito.ArgumentMatchers.eq(9003L), org.mockito.ArgumentMatchers.eq(java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING", "FIN_VOUCHER")))).thenReturn(true);
            return port;
        }

        @Bean cn.iocoder.yudao.module.franchise.api.AssetEvidenceQueryPort evidence() {
            return (tenant, ids, types) -> ids.contains(9001L) && types.contains("CONTRACT_ATTACHMENT")
                    ? java.util.Map.of(9001L, tenant) : java.util.Map.of();
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return orgId -> orgId;
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.DesignServicePort designServicePort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.DesignServicePort.class);
        }

        /** SalesAccess 客户组织校验桩：客户存在且同组织即过 */
        @Bean
        cn.iocoder.yudao.module.franchise.api.CustomerSchemePort customerSchemePort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.api.CustomerSchemePort() {
                @Override
                public void requireAccessible(PostSnapshot post, long customerId) {
                    Integer count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM fr_customer WHERE id = ? AND company_id = ?",
                            Integer.class, customerId, post.orgId());
                    if (count == null || count == 0) {
                        throw new ServiceException(
                                cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID);
                    }
                }

                @Override
                public SchemeSaved saveScheme(PostSnapshot post, long customerId, SchemeSave command) {
                    throw new UnsupportedOperationException("本测试不触达方案回写");
                }

                @Override
                public void requireWritable(PostSnapshot post, long customerId) {
                    requireAccessible(post, customerId);
                }

                @Override
                public java.util.List<Long> listDesignProjectIds(PostSnapshot post, long customerId) {
                    return java.util.List.of();
                }

                @Override
                public CustomerBrief getCustomerBrief(PostSnapshot post, long customerId) {
                    return new CustomerBrief(customerId, "陈先生", "");
                }

                @Override
                public SchemeSnapshot getSchemeSnapshot(PostSnapshot post, long schemeId) {
                    return null;
                }

                @Override
                public SchemeSnapshot promoteToFormal(PostSnapshot post, long schemeId, String idempotencyKey) {
                    throw new UnsupportedOperationException("本测试不触达方案升级");
                }
            };
        }

        /** 统一合同三期查询桩：本测试合同经冻结列解析，不触达回退查询 */
        @Bean
        cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort() {
                @Override
                public Long findActiveSignedId(PostSnapshot post, long customerId) {
                    return null; // 冻结列优先；回退桩恒 null（解析失败=门禁读数 0，用例不触达）
                }

                @Override
                public StageAmounts findStageAmounts(PostSnapshot post, long customerId) {
                    return null;
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort businessReceiptPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub(jdbc);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort engineeringLedgerQueryPort() {
            return untouchedStub(cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort.class);
        }

        @SuppressWarnings("unchecked")
        private static <T> T untouchedStub(Class<T> type) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        throw new UnsupportedOperationException("本测试不触达 " + type.getSimpleName() + "." + method.getName());
                    });
        }
    }

    private static final AtomicLong IDS = new AtomicLong(960_000_000_000L + System.nanoTime() % 90_000_000L);
    private static final String SIGN_AFTER = "SIGN_AFTER";

    @Resource
    private EngPlanService planService;
    @Resource
    private EngCollectionService collectionService;
    @Resource
    private EngTodoProvider todoProvider;
    @Resource
    private SaleContractService contracts;
    @Resource
    private SalePaymentStageMapper stageRows;
    @Resource
    private UnifiedStageAccountPort unifiedStages;
    @Resource
    private JdbcTemplate jdbc;
    @Resource private EngFollowupService followupService;
    @Resource private EngClosureService closureService;
    @Resource private org.springframework.transaction.PlatformTransactionManager transactions;

    private Store store;
    private PostSnapshot engineer;
    private PostSnapshot finance;
    private long projectId;
    private long customerId;
    private long signStageId;

    @BeforeEach
    void setUpProjectWithRealContract() {
        store = fixture.store();
        jdbc.update("INSERT INTO system_dept(id,name,status,deleted,tenant_id) VALUES(?,'工程合同测试',0,0,?) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id", store.orgId(), store.orgId());
        TenantContextHolder.setTenantId(store.orgId());
        engineer = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        finance = fixture.post(engineer.orgId(), "finance", null);
        seedProjectWithSignedPlan(null);
    }

    private void seedProjectWithSignedPlan(java.util.List<cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input> installments) {
        customerId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_customer (id, company_id, owner_user_id, name, phone, source, status, create_time, update_time) "
                        + "VALUES (?, ?, ?, '陈先生', ?, '视频号咨询', 'REGISTERED', now(), now())",
                customerId, store.orgId(), engineer.userId(), String.valueOf(IDS.incrementAndGet()));
        SaleContractDO created = as(engineer, () -> contracts.create(engineer,
                new SaleContractService.ContractCommand(customerId, 68_000_000L, 40_000_000L, 18_000_000L,
                        10_000_000L, null, null, null, installments),
                idem()));
        as(engineer, () -> contracts.sign(engineer, created.getId(),
                new SaleContractService.SignCommand(LocalDate.now(), 9001L),
                revisionOf(created.getId()), idem()));
        if (installments==null) signStageId = stageRowOf(created.getId(), SIGN_AFTER);
        // 工程项目：挂同一客户 + 冻结统一合同 id；约定行（term）SIGN_AFTER 40 万
        projectId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status, "
                        + "manager_user_id, assigned_engineer_user_id, sale_contract_id, source_commission_id, source_type, "
                        + "revision, author_user_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, '陈先生', '陈先生 · 乡墅', 'PENDING_START', ?, ?, ?, ?, 'HANDOVER', "
                        + "1, ?, now(), now())",
                projectId, store.orgId(), customerId, engineer.userId(), engineer.userId(),
                created.getId(), IDS.incrementAndGet(), engineer.userId());
        jdbc.update("INSERT INTO fr_eng_closure (id, company_id, project_id, create_time) "
                + "VALUES (?, ?, ?, now())", IDS.incrementAndGet(), store.orgId(), projectId);
        jdbc.update("INSERT INTO fr_eng_collection_term (id, company_id, project_id, stage, amount_cents) "
                + "VALUES (?, ?, ?, ?, 40000000)", IDS.incrementAndGet(), store.orgId(), projectId, SIGN_AFTER);
    }

    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test
    void collectionRegistrationRejectsSameKeyWhenProjectDateOrEvidenceChanges() {
        String key = idem();
        String transactionNo = txn();
        LocalDate occurredAt = LocalDate.now().minusDays(1);
        var command = new EngCollectionService.CollectCommand(
                SIGN_AFTER, new java.math.BigDecimal("100.00"), transactionNo, occurredAt, 9002L);

        var first = as(finance, () -> collectionService.register(finance, projectId, command, key));
        var replay = as(finance, () -> collectionService.register(finance, projectId, command, key));
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("collectionId")).isEqualTo(first.get("collectionId"));
        assertThat(replay.get("receiptId")).isEqualTo(first.get("receiptId"));

        var changedDate = new EngCollectionService.CollectCommand(
                SIGN_AFTER, command.amount(), transactionNo, occurredAt.plusDays(1), 9002L);
        assertThatThrownBy(() -> as(finance,
                () -> collectionService.register(finance, projectId, changedDate, key)))
                .isInstanceOf(ServiceException.class);

        var changedEvidence = new EngCollectionService.CollectCommand(
                SIGN_AFTER, command.amount(), transactionNo, occurredAt, 9003L);
        assertThatThrownBy(() -> as(finance,
                () -> collectionService.register(finance, projectId, changedEvidence, key)))
                .isInstanceOf(ServiceException.class);

        // Same actor and command shape on another project must never replay the first project's collection.
        long anotherProjectId = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status,
                    manager_user_id, assigned_engineer_user_id, sale_contract_id, source_commission_id,
                    source_type, revision, author_user_id, create_time, update_time)
                SELECT ?, company_id, customer_id, customer_name, '同合同第二工程', status,
                    manager_user_id, assigned_engineer_user_id, sale_contract_id, ?, source_type,
                    1, author_user_id, now(), now()
                FROM fr_eng_project WHERE id=?
                """, anotherProjectId, IDS.incrementAndGet(), projectId);
        jdbc.update("INSERT INTO fr_eng_closure (id, company_id, project_id, create_time) VALUES (?, ?, ?, now())",
                IDS.incrementAndGet(), store.orgId(), anotherProjectId);
        assertThatThrownBy(() -> as(finance,
                () -> collectionService.register(finance, anotherProjectId, command, key)))
                .isInstanceOf(ServiceException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_collection WHERE project_id=?",
                Long.class, projectId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_collection WHERE project_id=?",
                Long.class, anotherProjectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE stage_id=?",
                Long.class, signStageId)).isEqualTo(1L);
    }

    @Test
    void overlappingIdenticalCollectionsReturnTheOriginalReceipt() throws Exception {
        String key = idem();
        var cmd = new EngCollectionService.CollectCommand(SIGN_AFTER, new java.math.BigDecimal("100.00"), txn(), null, 9002L);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var waiter = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var second = tx.execute(status -> {
                int firstPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                var first = as(finance, () -> collectionService.register(finance, projectId, cmd, key));
                var pending = pool.submit(() -> {
                    TenantContextHolder.setTenantId(store.orgId());
                    try {
                        return tx.execute(other -> {
                            waiter.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            var replay = as(finance, () -> collectionService.register(finance, projectId, cmd, key));
                            assertThat(replay.get("receiptId")).isEqualTo(first.get("receiptId"));
                            return replay.get("replayed");
                        });
                    } finally { TenantContextHolder.clear(); }
                });
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() ->
                        waiter.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, firstPid, waiter.get())));
                return pending;
            });
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_eng_collection WHERE project_id=?", Integer.class, projectId)).isOne();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void collectionWithoutDateReplaysAfterTheServerDayChanges() {
        String key = idem();
        String transactionNo = txn();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        var command = new EngCollectionService.CollectCommand(
                SIGN_AFTER, new java.math.BigDecimal("100.00"), transactionNo, null, 9002L);
        Map<String, Object> first;
        try (var dates = org.mockito.Mockito.mockStatic(LocalDate.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(yesterday);
            first = as(finance, () -> collectionService.register(finance, projectId, command, key));
        }

        var replay = as(finance, () -> collectionService.register(finance, projectId, command, key));
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("collectionId")).isEqualTo(first.get("collectionId"));
        assertThat(replay.get("receiptId")).isEqualTo(first.get("receiptId"));
        assertThat(jdbc.queryForObject("SELECT CAST(occurred_at AS text) FROM fr_eng_collection WHERE id=?",
                String.class, first.get("collectionId"))).isEqualTo(yesterday.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE stage_id=?",
                Long.class, signStageId)).isEqualTo(1L);
    }

    @Test
    void collectionWithoutDateReplaysAPreviousVersionFingerprintUsingTheRecordedDay() {
        String key = idem();
        String transactionNo = txn();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        var dated = new EngCollectionService.CollectCommand(
                SIGN_AFTER, new java.math.BigDecimal("100.00"), transactionNo, yesterday, 9002L);
        var first = as(finance, () -> collectionService.register(finance, projectId, dated, key));
        var old = new java.util.LinkedHashMap<String, Object>();
        old.put("projectId", projectId);
        old.put("stage", SIGN_AFTER);
        old.put("amountCents", 10_000L);
        old.put("transactionNo", transactionNo);
        old.put("occurredAt", yesterday.toString());
        old.put("fileAssetId", 9002L);
        String payload = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(old);
        jdbc.update("UPDATE pf_command_event SET occurred_at=?,payload=?,payload_hash=? "
                        + "WHERE aggregate_id=? AND command='eng-collect'",
                yesterday.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime(),
                payload, cn.hutool.crypto.digest.DigestUtil.sha256Hex(payload), first.get("collectionId"));
        var original = new EngCollectionService.CollectCommand(
                SIGN_AFTER, dated.amount(), transactionNo, null, 9002L);
        var replay = as(finance, () -> collectionService.register(finance, projectId, original, key));

        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("collectionId")).isEqualTo(first.get("collectionId"));
        assertThat(replay.get("receiptId")).isEqualTo(first.get("receiptId"));
        assertThatThrownBy(() -> as(finance, () -> collectionService.register(finance, projectId,
                new EngCollectionService.CollectCommand(
                        SIGN_AFTER, dated.amount(), transactionNo, yesterday.plusDays(1), 9002L), key)))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE stage_id=?",
                Long.class, signStageId)).isEqualTo(1L);
    }

    @Test
    void legacyCollectionReplayChecksTheOriginalReceiptFacts() {
        String key = idem();
        String txn = txn();
        LocalDate date = LocalDate.now().minusDays(1);
        var command = new EngCollectionService.CollectCommand(
                SIGN_AFTER, new java.math.BigDecimal("100.00"), txn, date, 9002L);
        var first = as(finance, () -> collectionService.register(finance, projectId, command, key));
        String legacy = "{\"stage\":\"SIGN_AFTER\",\"amount\":\"100.00\",\"transactionNo\":\"" + txn + "\"}";
        assertThat(jdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? "
                        + "WHERE aggregate_id=? AND command='eng-collect'",
                legacy, cn.hutool.crypto.digest.DigestUtil.sha256Hex(legacy), first.get("collectionId"))).isOne();
        var replay = as(finance, () -> collectionService.register(finance, projectId, command, key));
        assertThat(replay).containsEntry("collectionId", first.get("collectionId"))
                .containsEntry("receiptId", first.get("receiptId")).containsEntry("replayed", true);
        var changed = new EngCollectionService.CollectCommand(SIGN_AFTER, command.amount(), txn, date, 9003L);
        assertError(() -> as(finance, () -> collectionService.register(finance, projectId, changed, key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void retryCannotRemoveAnExplicitCollectionDate() {
        String key = idem();
        var original = new EngCollectionService.CollectCommand(
                SIGN_AFTER, new java.math.BigDecimal("100.00"), txn(), LocalDate.now().minusDays(1), 9002L);
        as(finance, () -> collectionService.register(finance, projectId, original, key));
        var omitted = new EngCollectionService.CollectCommand(
                SIGN_AFTER, original.amount(), original.transactionNo(), null, 9002L);
        assertError(() -> as(finance, () -> collectionService.register(finance, projectId, omitted, key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void existingAmendedContractIncludesItsOriginalTailInEngineeringBalances() {
        long contractId = jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id=?",Long.class,projectId);
        // 已部署版本历史上保存的 V2 已签合同及尾款，不回溯重签。
        jdbc.update("UPDATE fr_sale_contract SET total_cents=69500000,first_cents=41000000,payment_plan_version=2 WHERE id=?",contractId);
        jdbc.update("UPDATE fr_sale_payment_stage SET plan_version=2,amount_cents=CASE WHEN stage='SIGN_AFTER' THEN 41000000 ELSE amount_cents END WHERE contract_id=?",contractId);
        jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,customer_id,contract_id,stage,label,sequence,plan_version,amount_cents,after_delivery,trigger_type,status,deleted) VALUES (?,?,?,?, 'tail','历史尾款',4,2,500000,true,'ON_FINISH','PENDING',false)",IDS.incrementAndGet(),store.orgId(),customerId,contractId);
        assertThatThrownBy(()->asRun(finance,()->contracts.changeAmounts(finance,contractId,
                new SaleContractService.ContractChange(40_000_000L,18_000_000L,10_000_000L,0,null,"移除尾款"),idem())))
                .hasMessageContaining("付款计划已锁定");
        var summary=as(finance,()->collectionService.summary(finance,projectId));
        assertThat(summary).hasSize(4);
        assertThat(as(finance,()->planService.termAmount(finance,projectId,SIGN_AFTER))).isEqualTo(41_000_000L);
        assertThat(summary.stream().mapToLong(s->((Number)s.get("expectedCents")).longValue()).sum()).isEqualTo(69_500_000L);
        assertThat(summary.stream().filter(s->SIGN_AFTER.equals(s.get("stage"))).findFirst().orElseThrow().get("expectedCents")).isEqualTo(41_000_000L);
        assertThat(summary.stream().filter(s->"tail".equals(s.get("stage"))).findFirst().orElseThrow().get("remainingCents")).isEqualTo(500_000L);
    }

    @Test
    void refundReopensStartGateAndShortfallTodoFlows() {
        // 收满 40 万：门槛过（不抛）
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(40_000_000L, "bank", txn(), LocalDate.now()), idem()));
        asRun(engineer, () -> planService.requireStartGate(engineer, projectId));
        assertThat(gateShortfallTodo(engineer)).isNull();
        // 业务已收齐时，工程催收和资金门槛都从统一实收读取，不再重复催收。
        assertThat(engCollectTodo(engineer)).as("统一款项已收齐，不重复催收").isNull();
        assertThat(gateShortfallTodo(engineer)).isNull();

        // 退款 10 万（真 fr_fin_refund 行，替代链未闭合）：净 30<40 → 门槛重新不足
        long receiptId = jdbc.queryForObject(
                "SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId);
        jdbc.update("INSERT INTO fr_fin_refund (id, receipt_id, company_id, amount_cents, status, reason, "
                        + "operator_user_id, refund_direction, date) "
                        + "VALUES (?, ?, ?, 10000000, '已核实', '协商退款', ?, 'client', CURRENT_DATE)",
                IDS.incrementAndGet(), receiptId, store.orgId(), engineer.userId());
        assertError(() -> asRun(engineer, () -> planService.requireStartGate(engineer, projectId)), GATE_NOT_MET);

        // 退款后催收及门槛都显示同一净缺口。
        assertThat(engCollectTodo(engineer)).as("退款后恢复催收").isNotNull();
        assertThat(engCollectTodo(engineer).amountCents()).isEqualTo(10_000_000L);
        // 门槛不足待办：owner 可见（缺口=10 万）、非 owner 不可见
        TodoItem shortfall = gateShortfallTodo(engineer);
        assertThat(shortfall).as("退款回缺后门槛待办出现").isNotNull();
        assertThat(shortfall.amountCents()).isEqualTo(10_000_000L);
        PostSnapshot otherEngineer = fixture.post(store.orgId(), "engineer", store.salesTeamB());
        assertThat(gateShortfallTodo(otherEngineer)).as("非 owner 不可见").isNull();

        // 补收 10 万：待办消失 + 门禁放行（不撤任何事实——项目从未被改状态）
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(10_000_000L, "bank", txn(), LocalDate.now()), idem()));
        asRun(engineer, () -> planService.requireStartGate(engineer, projectId));
        assertThat(gateShortfallTodo(engineer)).as("补收后待办消失").isNull();
        assertThat(engCollectTodo(engineer)).as("补收后催收消失").isNull();
    }

    @Test
    void deliveryUsesEachCustomInstallmentsNetAndIgnoresUnpaidTail() {
        var inputs = java.util.List.of(
                installment("签约款", 10_000_000L, "ON_SIGN"),
                installment("施工前款", 15_000_000L, "BEFORE_START"),
                new cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input(null,"主体进度款",12_000_000L,null,null,"NODE_COMPLETED",2),
                new cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input(null,"交付前款",8_000_000L,null,LocalDate.now(),"DATE",null),
                installment("交付后尾款", 23_000_000L, "ON_FINISH"));
        seedProjectWithSignedPlan(inputs);
        long contractId=contractsActiveId();
        var ids = jdbc.queryForList("""
                SELECT id FROM fr_sale_payment_stage WHERE contract_id=? AND NOT deleted ORDER BY sequence
                """, Long.class, contractId);
        assertThat(ids).hasSize(5);
        jdbc.update("UPDATE fr_eng_project SET actual_start_at=CURRENT_DATE WHERE id=?", projectId);
        var command = new EngClosureService.DeliverCommand(LocalDate.now(), "接收人", null, 9002L, "交付");
        for (int i = 0; i < 4; i++) {
            int n = i;
            assertError(() -> asRun(engineer, () -> closureService.deliver(engineer, projectId, command, idem())), GATE_NOT_MET);
            as(finance, () -> contracts.collect(finance, ids.get(n),
                    new SaleContractService.CollectCommand(inputs.get(n).amountCents(), "bank", txn(), LocalDate.now()), idem()));
        }
        long receiptId = jdbc.queryForObject(
                "SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id=?", Long.class, ids.get(2));
        jdbc.update("""
                INSERT INTO fr_fin_refund (id, receipt_id, company_id, amount_cents, status, reason, operator_user_id, refund_direction, date)
                VALUES (?, ?, ?, 10000, '已核实', '协商退款', ?, 'client', CURRENT_DATE)
                """, IDS.incrementAndGet(), receiptId, store.orgId(), finance.userId());
        assertError(() -> asRun(engineer, () -> closureService.deliver(engineer, projectId, command, idem())), GATE_NOT_MET);
        as(finance, () -> contracts.collect(finance, ids.get(2),
                new SaleContractService.CollectCommand(10_000L, "bank", txn(), LocalDate.now()), idem()));
        assertThat(as(engineer, () -> closureService.deliver(engineer, projectId, command, idem()))).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_sale_stage_receipt WHERE stage_id=?", Long.class, ids.get(4))).isZero();
        var unchanged=as(finance,()->contracts.stageSummary(finance,contractId));
        assertThatThrownBy(()->as(finance,()->contracts.replacePaymentPlan(finance,contractId,
                new SaleContractService.PaymentPlanCommand(68_000_000L,inputs,revisionOf(contractId),"交付后重排"),idem())))
                .hasMessageContaining("付款计划已锁定");
        assertThat(as(finance,()->contracts.paymentPlanDetail(finance,contractId))).containsEntry("canEdit",false).containsEntry("stages",unchanged);
    }

    private static cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input installment(String label, long cents, String trigger) {
        return new cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input(null, label, cents, null, null, trigger, null);
    }

    @Test
    void singleCountStructureOneReceiptOneStage() {
        as(finance, () -> contracts.collect(finance, signStageId,
                new SaleContractService.CollectCommand(25_000_000L, "bank", txn(), LocalDate.now()), idem()));
        long receiptId = jdbc.queryForObject(
                "SELECT receipt_id FROM fr_sale_stage_receipt WHERE stage_id = ?", Long.class, signStageId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_sale_stage_receipt WHERE receipt_id = ?",
                Long.class, receiptId)).isEqualTo(1L);

        // 同一笔 receipt 归另一期（BASE_DONE）→ uk_fr_sale_stage_receipt 结构拒绝（单次计数的结构保证）
        // R1 P2-1：INSERT 列齐全（排除非空约束干扰）+ 断言 DuplicateKey/SQLState 23505/约束名——防唯一约束反证假绿
        long baseStageId = stageRowOf(contractsActiveId(), "BASE_DONE");
        long contractId = contractsActiveId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fr_sale_stage_receipt (id, company_id, contract_id, stage_id, "
                        + "receipt_id, amount_cents) VALUES (?, ?, ?, ?, ?, 100)",
                IDS.incrementAndGet(), store.orgId(), contractId, baseStageId, receiptId))
                .isInstanceOfSatisfying(org.springframework.dao.DuplicateKeyException.class, e -> {
                    org.postgresql.util.PSQLException pg = findPg(e);
                    assertThat((Object) pg).as("根因应为 PG 异常").isNotNull();
                    assertThat(pg.getSQLState()).as("SQLState=23505 唯一约束冲突").isEqualTo("23505");
                    assertThat(pg.getServerErrorMessage().getConstraint()).as("约束名").isEqualTo("uk_fr_sale_stage_receipt");
                });

        // 期次读数不串：SIGN_AFTER 含该笔、BASE_DONE 恒 0
        assertThat(unifiedStages.stageCollectedCents(engineer, contractsActiveId(), SIGN_AFTER)).isEqualTo(25_000_000L);
        assertThat(unifiedStages.stageCollectedCents(engineer, contractsActiveId(), "BASE_DONE")).isZero();
    }

    @Test
    void dunningWaitsForTheProjectBeforeTakingTheContractLock() throws Exception {
        long contractId = contractsActiveId();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var waitingPid = new java.util.concurrent.atomic.AtomicInteger();
        var pending = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<java.util.List<java.util.Map<String,Object>>>>();
        currentPost.use(engineer);
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM fr_eng_project WHERE id=? FOR UPDATE", Long.class, projectId);
                int holdingPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> {
                    TenantContextHolder.setTenantId(store.orgId());
                    try {
                        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                            waitingPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            return followupService.dunning(engineer, projectId,
                                    new EngFollowupService.DunningCommand(SIGN_AFTER, LocalDate.now().plusDays(1), "并发催收"), idem());
                        });
                    } finally { TenantContextHolder.clear(); }
                }));
                boolean waiting = false;
                for (int attempt = 0; attempt < 250; attempt++) {
                    if (waitingPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holdingPid, waitingPid.get()))) {
                        waiting = true; break;
                    }
                    try { Thread.sleep(20); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                }
                assertThat(waiting).as("催收正在等待本事务持有的工程锁").isTrue();
                // With the old lock order the waiting dunning request already owns
                // this contract lock; NOWAIT proves the inversion without hanging.
                assertThat(jdbc.queryForObject("SELECT id FROM fr_sale_contract WHERE id=? FOR UPDATE NOWAIT", Long.class, contractId)).isEqualTo(contractId);
                collectionService.register(finance, projectId,
                        new EngCollectionService.CollectCommand(SIGN_AFTER, new java.math.BigDecimal("100.00"), txn(), LocalDate.now(), 9002L), idem());
            });
            assertThat(pending.get().get(10, java.util.concurrent.TimeUnit.SECONDS)).hasSize(1);
            assertThat(unifiedStages.stageCollectedCents(engineer, contractId, SIGN_AFTER)).isEqualTo(10000);
            assertThat(jdbc.queryForObject("SELECT revision FROM fr_eng_project WHERE id=?", Long.class, projectId)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_followup WHERE project_id=?", Long.class, projectId)).isEqualTo(1);
        } finally { pool.shutdownNow(); currentPost.clear(); }
    }

    // ========== helpers ==========

    private static org.postgresql.util.PSQLException findPg(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.postgresql.util.PSQLException) {
                return (org.postgresql.util.PSQLException) t;
            }
            t = t.getCause();
        }
        return null;
    }

    private long contractsActiveId() {
        return jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id = ?", Long.class, projectId);
    }

    private TodoItem engCollectTodo(PostSnapshot post) {
        return as(post, () -> todoProvider.list(post)).stream()
                .filter(t -> t.key().equals("fr-eng:eng-collect:" + projectId + ":SIGN_AFTER")
                        || t.key().equals("eng-collect:" + projectId + ":SIGN_AFTER"))
                .findFirst().orElse(null);
    }

    private TodoItem gateShortfallTodo(PostSnapshot post) {
        return as(post, () -> todoProvider.list(post)).stream()
                .filter(t -> t.key().equals("fr-eng:gate-shortfall:" + projectId))
                .findFirst().orElse(null);
    }

    private long stageRowOf(long contractId, String stage) {
        SalePaymentStageDO row = stageRows.selectByContract(contractId).stream()
                .filter(t -> stage.equals(t.getStage())).findFirst().orElseThrow();
        return row.getId();
    }

    private long revisionOf(long contractId) {
        return jdbc.queryForObject("SELECT revision FROM fr_sale_contract WHERE id = ?", Long.class, contractId);
    }

    private void asRun(PostSnapshot post, Runnable action) {
        currentPost.use(post);
        try {
            action.run();
        } finally {
            currentPost.clear();
        }
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private static String idem() {
        return "idem-" + IDS.incrementAndGet();
    }

    private static String txn() {
        return "TXN" + IDS.incrementAndGet();
    }
}
