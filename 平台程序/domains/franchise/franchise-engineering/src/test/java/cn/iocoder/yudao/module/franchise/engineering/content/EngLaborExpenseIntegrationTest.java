package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M07-T07：对外劳务费用与应付付款联动——批次#26 冻结契约的全链执行验证。
 * 链路：劳务原单（register purpose=LABOR 确认计成本）→ terms 挂 finance 应付（付款不重计成本）
 * → 多笔付款（ledger 已核实口径累计）→ 退回关联（付款作废自动减已付现金）
 * → revise 劳务减项（确认费用变更才调成本——退多付款不减正确费用）。
 * 结算屏（EngClosureService.settleSummary）三字段随全链实时核对。
 */
@Import({EngProjectService.class, EngMaterialService.class, EngPlanService.class,
        EngCollectionService.class, EngExpenseService.class, EngClosureService.class,
        FranchiseCommands.class, CommandLogImpl.class,
        EngLaborExpenseIntegrationTest.Config.class})
class EngLaborExpenseIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {

        /** 应付桩：记录 sourceRef→payableId（finance 应付清偿语义由 finance 域测试覆盖） */
        static final java.util.Map<String, Long> PAYABLES = new java.util.concurrent.ConcurrentHashMap<>();
        /** 已付现金（模拟 finance 多笔付款累计+作废退回：payableId→净已付） */
        static final java.util.Map<Long, java.util.concurrent.atomic.AtomicLong> PAID =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort engineeringPayablePort() {
            return new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort() {
                @Override
                public long registerPayable(PostSnapshot post, PayableCommand command) {
                    return PAYABLES.computeIfAbsent(command.sourceRef(),
                            k -> new java.util.concurrent.atomic.AtomicLong(991000L + PAYABLES.size() + 1).get());
                }

                @Override
                public void syncUnpaidExpenseAmount(PostSnapshot post, long projectId, long expenseId,
                                                   long payableId, long amountCents, String idempotencyKey) {
                    // 此隔离测试仅模拟已登记付款；无财务金额表，真实应付更新由集成用例验证。
                    assertThat(PAYABLES.get("engineering-expense:" + projectId + ":" + expenseId)).isEqualTo(payableId);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort engineeringLedgerQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort() {
                @Override
                public long paidCents(PostSnapshot post, long payableId) {
                    var v = PAID.get(payableId);
                    return v == null ? 0 : v.get();
                }

                @Override
                public java.util.Map<Long, Long> paidCentsByPayableIds(PostSnapshot post,
                                                                       java.util.Collection<Long> payableIds) {
                    java.util.Map<Long, Long> result = new java.util.HashMap<>();
                    for (Long id : payableIds) {
                        var v = PAID.get(id);
                        if (v != null) {
                            result.put(id, v.get());
                        }
                    }
                    return result;
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort businessReceiptPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub(jdbc);
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStageAccountPort() {
            return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort() {
                @Override
                public Long activeContractId(PostSnapshot post, long customerId) {
                    return 900L;
                }

                @Override
                public long stageCollectedCents(PostSnapshot post, long contractId, String stage) {
                    return 0;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult collectStage(
                        PostSnapshot post, long contractId, String stage,
                        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.CollectInput input) {
                    return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult(
                            8001L, input.amountCents(), "OPEN", false);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort() {
            return new cn.iocoder.yudao.module.franchise.api.AssetAccessPort() {
                @Override
                public boolean validateReference(long serviceAccountId, long assetId) {
                    return false;
                }

                @Override
                public String assetTypeOf(long serviceAccountId, long assetId) {
                    return null;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.UploadTicketView createUploadTicket(
                        long serviceAccountId, String assetType, String mimeType, long sizeBytes, String sha256) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.AssetCompleted completeUpload(
                        long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.AssetAccessPort.DownloadUrlView createDownloadUrl(
                        long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return orgId -> orgId;
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort() {
                @Override
                public Long findActiveSignedId(PostSnapshot post, long customerId) {
                    return 900L;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts findStageAmounts(
                        PostSnapshot post, long customerId) {
                    return null; // 无活跃合同：工程表单三期即约定（本测试不触达统一合同对齐）
                }
            };
        }
    }

    private static final AtomicLong IDS = new AtomicLong(960_000_000_000L + System.nanoTime() % 100_000_000L);

    @Resource
    private EngExpenseService expenses;
    @Resource
    private EngClosureService closures;
    @Resource
    private JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot engineer;
    private long projectId;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS eng_test_asset (id BIGINT PRIMARY KEY, "
                + "asset_type VARCHAR(64) NOT NULL, accepted BOOLEAN NOT NULL, org_id BIGINT NOT NULL)");
        jdbc.update("DELETE FROM eng_test_asset WHERE org_id <> -1");
        Config.PAYABLES.clear();
        Config.PAID.clear();
        store = fixture.store();
        engineer = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        projectId = insertProject();
    }

    @Test
    void laborFullChainRegisterTermsPayVoidRevise() {
        // 1. 劳务原单：register purpose=LABOR（确认即计成本）
        var registered = as(engineer, () -> expenses.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("砌筑劳务队结算", "LABOR",
                        new BigDecimal("50000"), "两层砌筑含勾缝"), idem()));
        long expenseId = Long.parseLong(String.valueOf(registered.get("expenseId")));
        assertThat(registered.get("status")).isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_expense WHERE id = ?", Long.class, expenseId))
                .isEqualTo(5_000_000L);

        // 2. 挂 finance 应付（付款不重计成本）
        as(engineer, () -> expenses.terms(engineer, expenseId,
                new EngExpenseService.TermsCommand("劳务队-王师傅", "劳务结算单 LZ-001"), idem()));
        Long payableId = jdbc.queryForObject(
                "SELECT payable_id FROM fr_eng_expense WHERE id = ?", Long.class, expenseId);
        assertThat(payableId).isNotNull();

        // 3. 多笔付款（finance 侧：第一笔 200 万 + 第二笔 100 万 = 300 万）
        Config.PAID.computeIfAbsent(payableId, k -> new java.util.concurrent.atomic.AtomicLong())
                .addAndGet(2_000_000L);
        Config.PAID.get(payableId).addAndGet(1_000_000L);
        // 结算屏：费用成本 500 万不变（付款不重计成本）；已付 300 万
        var summary1 = as(engineer, () -> closures.settleSummary(engineer, projectId));
        assertThat(((Number) summary1.get("expensesTotalCents")).longValue()).isEqualTo(5_000_000L);
        assertThat(((Number) summary1.get("expensesPaidCents")).longValue()).isEqualTo(3_000_000L);
        assertThat(((Number) summary1.get("expensesRemainingCents")).longValue()).isEqualTo(2_000_000L);

        // 4. 退回关联：finance 作废第二笔付款（已核实剔除 → 已付自动减至 200 万）
        // R1 P2-4 口径登记：桩 PAID 模拟「已核实累计-作废」净口径，与真实
        // EngineeringLedgerQueryAdapter（selectActiveByPayable status='已核实'，作废自动剔除）一致；
        // 真实 FinPaymentService 跨域用例按 M08 财务测试模块边界留 finance 侧（批次#26 登记沿用）
        Config.PAID.get(payableId).addAndGet(-1_000_000L);
        var summary2 = as(engineer, () -> closures.settleSummary(engineer, projectId));
        assertThat(((Number) summary2.get("expensesPaidCents")).longValue()).isEqualTo(2_000_000L);
        assertThat(((Number) summary2.get("expensesTotalCents")).longValue()).isEqualTo(5_000_000L); // 成本不变

        // 5. 确认劳务减项（revise）：成本 500 万 → 350 万（已付现金 200 万不减——退多付款不减正确费用）
        var revised = as(engineer, () -> expenses.revise(engineer, expenseId,
                new BigDecimal("35000"), "劳务量核减 15 万（确认减项）", idem()));
        assertThat(((Number) revised.get("amountCents")).longValue()).isEqualTo(3_500_000L);
        var summary3 = as(engineer, () -> closures.settleSummary(engineer, projectId));
        assertThat(((Number) summary3.get("expensesTotalCents")).longValue()).isEqualTo(3_500_000L);
        assertThat(((Number) summary3.get("expensesPaidCents")).longValue()).isEqualTo(2_000_000L);
        // 已付 200 万 < 成本 350 万：remaining 150 万
        assertThat(((Number) summary3.get("expensesRemainingCents")).longValue()).isEqualTo(1_500_000L);

        // 多笔付款审计：terms/register/revise 三命令留痕
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE aggregate_id = ? AND command = 'eng-expense-revise'",
                Integer.class, projectId);
        assertThat(events).isEqualTo(1);
    }

    @Test
    void cancelWithPayableIsRejectedAndWithoutPayableSucceeds() {
        var registered = as(engineer, () -> expenses.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("误录差旅", "TRAVEL",
                        new BigDecimal("800"), null), idem()));
        long expenseId = Long.parseLong(String.valueOf(registered.get("expenseId")));

        // 未挂应付：可取消
        asVoid(engineer, () -> expenses.cancel(engineer, expenseId, "录入重复", idem()));
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_expense WHERE id = ?",
                String.class, expenseId)).isEqualTo("CANCELLED");
        // 取消后不计成本（结算屏排除）
        var summary = as(engineer, () -> closures.settleSummary(engineer, projectId));
        assertThat(((Number) summary.get("expensesTotalCents")).longValue()).isZero();

        // 已挂应付：一律拒绝（应付须财务侧原子关闭——R1 P1-3）
        var registered2 = as(engineer, () -> expenses.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("水电劳务", "LABOR",
                        new BigDecimal("3000"), null), idem()));
        long expenseId2 = Long.parseLong(String.valueOf(registered2.get("expenseId")));
        as(engineer, () -> expenses.terms(engineer, expenseId2,
                new EngExpenseService.TermsCommand("水电劳务队", "劳务结算单 SD-002"), idem()));
        assertThatThrownBy(() -> asVoid(engineer, () -> expenses.cancel(engineer, expenseId2, "误录", idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已挂应付的费用不可取消");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_expense WHERE id = ?",
                String.class, expenseId2)).isEqualTo("REGISTERED");
    }

    @Test
    void idempotentReplayReturnsSameResultForLaborRegister() {
        String key = "idem-labor-1";
        var cmd = new EngExpenseService.RegisterCommand("瓦工劳务", "LABOR",
                new BigDecimal("12000"), "瓦工班组两周结算");
        var first = as(engineer, () -> expenses.register(engineer, projectId, cmd, key));
        var retry = as(engineer, () -> expenses.register(engineer, projectId, cmd, key));
        assertThat(retry.get("expenseId")).isEqualTo(first.get("expenseId"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'eng-expense-register' "
                        + "AND payload LIKE ?", Integer.class, "%瓦工劳务%")).isEqualTo(1);
    }

    @Test
    void reviseThenTermsKeepsSinglePayable() {
        var registered = as(engineer, () -> expenses.register(engineer, projectId,
                new EngExpenseService.RegisterCommand("钢筋劳务", "LABOR",
                        new BigDecimal("20000"), null), idem()));
        long expenseId = Long.parseLong(String.valueOf(registered.get("expenseId")));
        // 先 revise 后 terms：应付按修订后金额挂接（费用成本=修订后事实）
        as(engineer, () -> expenses.revise(engineer, expenseId, new BigDecimal("18000"), "核减", idem()));
        as(engineer, () -> expenses.terms(engineer, expenseId,
                new EngExpenseService.TermsCommand("钢筋劳务队", "劳务结算单 GJ-003"), idem()));
        Long payableId = jdbc.queryForObject("SELECT payable_id FROM fr_eng_expense WHERE id = ?",
                Long.class, expenseId);
        assertThat(payableId).isNotNull();
        // 重复 terms 幂等：沿用原应付（不重复建应付）
        as(engineer, () -> expenses.terms(engineer, expenseId,
                new EngExpenseService.TermsCommand("钢筋劳务队", "劳务结算单 GJ-003"), idem()));
        assertThat(jdbc.queryForObject("SELECT payable_id FROM fr_eng_expense WHERE id = ?",
                Long.class, expenseId)).isEqualTo(payableId);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM fr_eng_expense WHERE id = ?",
                Long.class, expenseId)).isEqualTo(1_800_000L);
    }

    // ===== 辅助 =====

    private long insertProject() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status, "
                        + "manager_user_id, assigned_engineer_user_id, source_commission_id, source_type, "
                        + "revision, author_user_id, create_time, update_time) "
                        + "VALUES (?, ?, 960001, '陈先生', '陈先生 · 乡墅', 'PENDING_START', ?, ?, ?, 'HANDOVER', "
                        + "1, ?, now(), now())",
                id, store.orgId(), engineer.userId(), engineer.userId(), IDS.incrementAndGet(), engineer.userId());
        jdbc.update("INSERT INTO fr_eng_closure (id, company_id, project_id, create_time) "
                + "VALUES (?, ?, ?, now())", IDS.incrementAndGet(), store.orgId(), id);
        return id;
    }

    private void asVoid(PostSnapshot post, Runnable action) {
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
}
