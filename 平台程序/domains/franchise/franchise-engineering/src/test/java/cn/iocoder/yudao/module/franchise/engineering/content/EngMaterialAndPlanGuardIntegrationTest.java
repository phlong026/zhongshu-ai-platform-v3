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
 * M07-T01 材料幂等（I02）+ M07-T03 资产端口引用校验（I04）：
 * 1. 同键同输入两次返回同结果（重试命中合法重放，不再误判键复用——replay/record canonical 统一）；
 * 2. 同键改输入拒绝；仅一条变更记录；
 * 3. 工程凭据资产域侧校验：假 ID/跨组织/未 ACCEPTED 拒绝（validateReference），类型不符拒绝（assetTypeOf）。
 */
@Import({EngProjectService.class, EngMaterialService.class, EngPlanService.class, EngCollectionService.class,
        EngClosureService.class,
        EngExpenseService.class,
        FranchiseCommands.class, CommandLogImpl.class,
        EngMaterialAndPlanGuardIntegrationTest.Config.class})
class EngMaterialAndPlanGuardIntegrationTest extends FranchisePgIntegrationTest {

    @TestConfiguration
    static class Config {

        /** 资产端口桩：白名单资产=ACCEPTED 本组织；类型可控 */
        @Bean
        cn.iocoder.yudao.module.franchise.api.AssetAccessPort assetAccessPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.api.AssetAccessPort() {
                // R1 P2：属主维度=服务账号（测试约定 serviceAccountId==orgId）——跨组织资产拒之门外
                private boolean existsAccepted(long serviceAccountId, long assetId) {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM eng_test_asset WHERE id = ? AND accepted = TRUE AND org_id = ?",
                            Integer.class, assetId, serviceAccountId);
                    return n != null && n > 0;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.DesignServicePort.UploadTicketView createUploadTicket(long serviceAccountId, String assetType,
                                                                             String mimeType, long sizeBytes, String sha256) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.DesignServicePort.AssetCompleted completeUpload(long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.DesignServicePort.DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String assetTypeOf(long serviceAccountId, long assetId) {
                    return jdbc.queryForObject(
                            "SELECT asset_type FROM eng_test_asset WHERE id = ? AND org_id = ?",
                            String.class, assetId, serviceAccountId);
                }

                @Override
                public boolean validateReference(long serviceAccountId, long assetId) {
                    return existsAccepted(serviceAccountId, assetId);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort storeServiceAccountPort() {
            return orgId -> orgId; // R1 P2 约定：服务账号 id==组织 id，属主维度=组织
        }

        /** M07-T06：统一合同三期桩——CONTRACT_STAGES 非空返回约定（测试静态直控），null=无活跃合同 */
        static volatile cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts CONTRACT_STAGES;

        static final AtomicLong ACTIVE_CONTRACT_ID = new AtomicLong(900L);

        @Bean
        cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort() {
                @Override
                public Long findActiveSignedId(PostSnapshot post, long customerId) {
                    // R3：合同存在性与三期金额是两个独立维度——默认 900（fixture 固定合同），
                    // ACTIVE_CONTRACT_ID 可变（重签拒绝用例）；不随 CONTRACT_STAGES 重置而消失
                    return ACTIVE_CONTRACT_ID.get();
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts findStageAmounts(
                        PostSnapshot post, long customerId) {
                    return CONTRACT_STAGES;
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort businessReceiptPort(JdbcTemplate jdbc) {
            return new cn.iocoder.yudao.module.franchise.test.BusinessReceiptPortTestStub(jdbc);
        }

        /** M07-T06：统一实收账桩——STATIC_COLLECTED 控制门禁读数；collectStage 记录并返回成功 */
        static final String SIGN_AFTER = "SIGN_AFTER";
        static final String BASE_DONE = "BASE_DONE";
        static final String CAP_DONE = "CAP_DONE";
        static final java.util.List<String> COLLECT_CALLS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /** R2 P2-5：按期次返回（SIGN_AFTER 全额/BASE_DONE+CAP_DONE=0）——三期累计=声明总额而非三倍 */
        static volatile java.util.Map<String, Long> STAGE_COLLECTED_MAP = new java.util.concurrent.ConcurrentHashMap<>();

        @Bean
        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStageAccountPort() {
            return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort() {
                @Override
                public Long activeContractId(PostSnapshot post, long customerId) {
                    return ACTIVE_CONTRACT_ID.get(); // 与 SaleContractQueryPort 桩同源（重签用例可变）
                }

                @Override
                public long stageCollectedCents(PostSnapshot post, long contractId, String stage) {
                    Long v = STAGE_COLLECTED_MAP.get(stage);
                    return v == null ? 0 : v;
                }

                @Override
                public cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult collectStage(
                        PostSnapshot post, long contractId, String stage,
                        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.CollectInput input) {
                    COLLECT_CALLS.add(stage + ":" + input.amountCents() + ":" + input.transactionNo());
                    return new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageCollectResult(
                            8001L, input.amountCents(), "OPEN", false);
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort engineeringPayablePort() {
            return new cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort() {
                @Override
                public long registerPayable(PostSnapshot post, PayableCommand command) {
                    return 999001L;
                }
            };
        }

        @Bean
        cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort engineeringLedgerQueryPort() {
            return new cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort() {
                @Override
                public long paidCents(PostSnapshot post, long payableId) {
                    return 0L;
                }

                @Override
                public java.util.Map<Long, Long> paidCentsByPayableIds(PostSnapshot post, java.util.Collection<Long> payableIds) {
                    return java.util.Map.of();
                }
            };
        }
    }

    private static final AtomicLong IDS = new AtomicLong(950_000_000_000L + System.nanoTime() % 100_000_000L);

    @Resource
    private EngMaterialService materialsService;
    @Resource
    private EngPlanService planService;
    @Resource
    private EngCollectionService collectionsService;
    @Resource
    private EngClosureService closuresService;
    @Resource
    private cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContractsPort;
    @Resource
    private JdbcTemplate jdbc;

    private Store store;
    private PostSnapshot engineer;
    private long projectId;
    private long materialId;
    private long supplyId;
    private long nodeId;

    @BeforeEach
    void setUpProjectAndMaterial() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS eng_test_asset (id BIGINT PRIMARY KEY, "
                + "asset_type VARCHAR(64) NOT NULL, accepted BOOLEAN NOT NULL, org_id BIGINT NOT NULL)");
        jdbc.update("DELETE FROM eng_test_asset WHERE org_id <> -1"); // 跨用例清理（R1 P3）
        Config.CONTRACT_STAGES = null; // 静态桩重置
        Config.STAGE_COLLECTED_MAP.clear();
        Config.COLLECT_CALLS.clear();
        store = fixture.store();
        engineer = fixture.post(store.orgId(), "engineer", store.engineeringTeam());
        projectId = insertProject();
        // 统一账关联前提：工程档案挂客户（customer_id NULL 时统一门禁读数恒 0）
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?",
                IDS.incrementAndGet(), projectId);
        nodeId = insertNode();
        materialId = insertMaterial();
        supplyId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_supply WHERE material_id = ?", Long.class, materialId);
    }

    // ========== M07-T01：材料幂等 ==========

    @Test
    void threeStagePrepareAlignsWithUnifiedContract() {
        // 有活跃统一合同：三期金额与合同一致 → 通过并固化三期 term 行（SIGN_AFTER/BASE_DONE/CAP_DONE）
        Config.CONTRACT_STAGES = new cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort.StageAmounts(
                40800000L, 18000000L, 9200000L);
        // 对齐校验前提：工程档案挂客户（customerId NULL 时跳过合同对齐）
        long customerId = IDS.incrementAndGet();
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?", customerId, projectId);
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'SIGN_AFTER'",
                Long.class, projectId)).isEqualTo(40800000L);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'BASE_DONE'",
                Long.class, projectId)).isEqualTo(18000000L);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE project_id = ? AND stage = 'CAP_DONE'",
                Long.class, projectId)).isEqualTo(9200000L);

        // 与合同不一致 → 拒绝（不发明固定比例/不得自行拆分）
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(validAsset, 40000000L, 20000000L, 8000000L), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("三期金额须与统一建房合同约定一致");
    }

    @Test
    void baseDoneStageRequiresFoundationNodeDoneBeforeCollect() {
        // M07-T06：「基础/封顶完成先留痕再触发应收」——BASE_DONE 期收款须有非封顶节点已完成留痕
        Config.CONTRACT_STAGES = null; // 无活跃合同：表单三期即约定（存量兼容）
        long customerId = IDS.incrementAndGet();
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?", customerId, projectId);
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        // start 前置：节点日期齐备 + SIGN_AFTER 门禁（统一账读侧桩置累计 40800000=签约后期已收齐）
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CURRENT_DATE + 30 "
                + "WHERE project_id = ?", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        // R1 P1-1：SIGN_AFTER 门禁读统一账——桩置累计 40800000（签约后期已收齐）
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validAsset, true), idem()));
        // 基础完成节点留痕（非封顶节点 DONE）——completeNode 前置：材料合格到齐
        jdbc.update("UPDATE fr_eng_material SET quantity = 10.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 10.000 WHERE id = ?", supplyId);
        long baseArrivalAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-BASE-ARRIVAL', CURRENT_DATE, ?, 'OK', 10.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, baseArrivalAsset, engineer.userId());
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "基础施工完成", validAsset, true), idem()));
        // BASE_DONE 收款通过（留痕在前）
        var view = as(engineer, () -> collectionsService.register(engineer, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("150000"), "TXN-BASE",
                        java.time.LocalDate.now(), validAsset), idem()));
        assertThat(view.get("collectionId")).isNotNull();
        // R2 P2-8：统一链调用断言——Port.collectStage 收到 BASE_DONE 期分笔；工程留痕行引用统一 receiptId
        assertThat(Config.COLLECT_CALLS).anyMatch(c -> c.startsWith("BASE_DONE:15000000:TXN-BASE"));
        assertThat(jdbc.queryForObject(
                "SELECT receipt_id FROM fr_eng_collection WHERE project_id = ? AND stage = 'BASE_DONE'",
                Long.class, projectId)).isEqualTo(8001L);

        // 作废重签拒绝（R2 P1-2）：冻结合同 900 ≠ 当前活跃 901 → 收款拒绝
        Config.ACTIVE_CONTRACT_ID.set(901L);
        assertThatThrownBy(() -> as(engineer, () -> collectionsService.register(engineer, projectId,
                new EngCollectionService.CollectCommand("BASE_DONE", new BigDecimal("100000"), "TXN-BASE2",
                        java.time.LocalDate.now(), validAsset), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("统一建房合同已变更");
        Config.ACTIVE_CONTRACT_ID.set(900L);
        // 封顶期收款在封顶节点完成前拒绝（既有 toppingDone 门禁）
        assertThatThrownBy(() -> as(engineer, () -> collectionsService.register(engineer, projectId,
                new EngCollectionService.CollectCommand("CAP_DONE", new BigDecimal("100000"), "TXN-CAP",
                        java.time.LocalDate.now(), validAsset), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("请先完成主体封顶");
    }

    @Test
    void sameKeySameInputReplayReturnsSameResultWithoutDuplication() {
        var cmd = new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量");
        String key = "idem-adj-1";

        var first = as(engineer, () -> materialsService.adjust(engineer, materialId, cmd, key));
        // 响应丢失后同键同表单重试：命中合法重放（此前 replay/record 字段集不同被误判键复用）
        var retry = as(engineer, () -> materialsService.adjust(engineer, materialId, cmd, key));

        assertThat(retry).isEqualTo(first);
        // 仅一条变更记录（不重复创建业务）
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE command = 'eng-material-adjust' "
                        + "AND payload LIKE ?", Integer.class, "%\"materialId\":" + materialId + "%");
        assertThat(events).isEqualTo(1);
        assertThat(quantityOf(materialId)).isEqualByComparingTo(new BigDecimal("15.000"));
    }

    @Test
    void sameKeySameQuantityDifferentReasonIsRejected() {
        // R1 建议：reason 属调整意图，实质不同须拒绝复用键
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量"), "idem-adj-r"));
        assertThatThrownBy(() -> as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "换了个说法"), "idem-adj-r")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void startAndNodeCompleteAssetGuardsRejectInvalidAssets() {
        // start 凭据校验：假 ID 拒绝（开工须先 prepare——先合法 prepare 再 start）
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        // start 前置：节点计划日期齐备；SIGN_AFTER 门禁读统一账——桩置累计 40800000（R1 P1-1 读侧）
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CURRENT_DATE + 30 "
                + "WHERE project_id = ?", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        long fakeId = IDS.incrementAndGet();
        org.assertj.core.api.ThrowableAssert.ThrowingCallable startWithFake = () ->
                as(engineer, () -> planService.start(engineer, projectId,
                        new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", fakeId, true), idem()));
        org.assertj.core.api.Assertions.assertThatThrownBy(startWithFake).isInstanceOf(ServiceException.class);
        // completeNode 凭据校验：假 ID 拒绝（M07-T03 旁路封堵回归）
        // 合法凭据通过 start（承接 prepare 状态）
        long validStartAsset = insertAsset("ENG_DRAWING", true, store.orgId());
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validStartAsset, true), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT actual_start_at FROM fr_eng_project WHERE id = ?", java.sql.Date.class, projectId))
                .isNotNull();

        // R2：completeNode 负测在合法 start 之后（actualStartAt 已置，前置门通过）——
        // 假 ID 到达资产校验层，断言资产校验的具体文案（区别于前置门错误，防假阳性）
        long fakeForNode = IDS.incrementAndGet();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                        new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "主体完成", fakeForNode, true), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("凭据资产不存在、未验收或不属于本组织");
        // 合法 completeNode：节点 DONE + 凭据文档落库（前置：材料合格到齐——到货行 quantity>=需用）
        long gateReceiptAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        // 合格到货行（quality=OK + actual_quantity=15 ≥ 需用——共享库中幂等用例已把需用改到 15，
        // 到货须 ≥ 现需用才能过 materialReadyForNode）
        jdbc.update("UPDATE fr_eng_material SET quantity = 15.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 15.000 WHERE id = ?", supplyId);
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-ARRIVAL', CURRENT_DATE, ?, 'OK', 15.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, gateReceiptAsset, engineer.userId());
        long validNodeAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.completeNode(engineer, projectId, nodeId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "主体完成", validNodeAsset, true), idem()));
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_plan_node WHERE id = ?",
                String.class, nodeId)).isEqualTo("DONE");
        // 凭据落 fr_eng_site_record（file_asset_ids JSON 含凭据资产）
        Integer siteRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_eng_site_record WHERE project_id = ? AND node_id = ? "
                        + "AND file_asset_ids::text LIKE ?", Integer.class, projectId, nodeId, "%" + validNodeAsset + "%");
        assertThat(siteRecords).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameKeyDifferentInputIsRejected() {
        as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("15.000"), "采购追加用量"), "idem-adj-2"));

        // 同键改输入（数量与原因都不同）：键复用拒绝
        assertThatThrownBy(() -> as(engineer, () -> materialsService.adjust(engineer, materialId,
                new EngMaterialService.AdjustCommand(new BigDecimal("18.000"), "改口径"), "idem-adj-2")))
                .isInstanceOf(ServiceException.class);
        // 数量保持首次值
        assertThat(quantityOf(materialId)).isEqualByComparingTo(new BigDecimal("15.000"));
    }

    // ========== M07-T03：资产端口引用校验 ==========

    @Test
    void fakeOrForeignOrUnacceptedAssetIsRejectedOnStartPreparation() {
        long fakeId = IDS.incrementAndGet();
        long otherOrg = IDS.incrementAndGet();
        // 假 ID（不存在）
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(fakeId), idem()))).isInstanceOf(ServiceException.class);
        // 未 ACCEPTED
        long unaccepted = insertAsset("ENG_SITE_PHOTO", false, store.orgId());
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(unaccepted), idem()))).isInstanceOf(ServiceException.class);
        // 类型不符（DOC 类不在合同凭据允许集合）
        long wrongType = insertAsset("DOC_OTHER", true, store.orgId());
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(wrongType), idem()))).isInstanceOf(ServiceException.class);
        // 跨组织（他组织属主的已验收资产）：validateReference 属主过滤拒绝（R1 P2）
        long foreign = insertAsset("ENG_SITE_PHOTO", true, otherOrg);
        assertThatThrownBy(() -> as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(foreign), idem()))).isInstanceOf(ServiceException.class);
        // 零落痕：仅 setUp 预置的 plan_version 行（insertMaterial 建行），无 prepare 新增
        Integer preps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_eng_plan_version WHERE project_id = ?", Integer.class, projectId);
        assertThat(preps).isEqualTo(1);
    }

    @Test
    void deliverRejectsInvalidEvidenceAssets() {
        // M09-T01/I01：交付凭据域侧校验——前置链到交付门口（节点全 DONE+材料到齐+全累计收款）
        jdbc.update("UPDATE fr_eng_project SET customer_id = ? WHERE id = ?",
                IDS.incrementAndGet(), projectId); // 交付全累计收款门禁走统一账读侧
        long validAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId, startCmd(validAsset), idem()));
        jdbc.update("UPDATE fr_eng_plan_node SET start_date = CURRENT_DATE, end_date = CURRENT_DATE + 30 "
                + "WHERE project_id = ?", projectId);
        long total = 40800000L + 18000000L + 9200000L;
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 40800000 WHERE project_id = ? AND stage = 'SIGN_AFTER'", projectId);
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 18000000 WHERE project_id = ? AND stage = 'BASE_DONE'", projectId);
        jdbc.update("UPDATE fr_eng_collection_term SET amount_cents = 9200000 WHERE project_id = ? AND stage = 'CAP_DONE'", projectId);
        Config.STAGE_COLLECTED_MAP.put(Config.SIGN_AFTER, 40800000L);
        Config.STAGE_COLLECTED_MAP.put(Config.BASE_DONE, 18000000L);
        Config.STAGE_COLLECTED_MAP.put(Config.CAP_DONE, 9200000L); // 交付全累计收款门禁（统一账读侧）
        as(engineer, () -> planService.start(engineer, projectId,
                new EngPlanService.NodeRecordCommand(java.time.LocalDate.now(), "实际开工", validAsset, true), idem()));
        jdbc.update("UPDATE fr_eng_material SET quantity = 15.000 WHERE id = ?", materialId);
        jdbc.update("UPDATE fr_eng_supply SET quantity = 15.000 WHERE id = ?", supplyId);
        long arrivalAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        jdbc.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, kind, "
                        + "transaction_no, occurred_at, file_asset_id, quality, actual_quantity, "
                        + "author_user_id, create_time) "
                        + "VALUES (?, ?, ?, ?, 'RECEIPT', 'TXN-DLV', CURRENT_DATE, ?, 'OK', 15.000, ?, now())",
                IDS.incrementAndGet(), store.orgId(), projectId, supplyId, arrivalAsset, engineer.userId());
        jdbc.update("UPDATE fr_eng_plan_node SET status = 'DONE' WHERE project_id = ?", projectId);

        // 假 ID 交付凭据：拒绝（M09-T01 核心）
        long fakeId = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", null, fakeId, "交付"), idem())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("凭据资产不存在、未验收或不属于本组织");
        // 未 ACCEPTED：拒绝
        long unaccepted = insertAsset("ENG_SITE_PHOTO", false, store.orgId());
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", null, unaccepted, "交付"), idem())))
                .isInstanceOf(ServiceException.class);
        // 合法凭据（交付+业主签收）：交付成功落列
        long validEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        long ownerEvidence = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        // 固定成功交付的键——后续同键换资产用其重放（R3 P2：指纹含两资产 ID）
        String deliverKey = idem();
        java.util.Map<String, Object> delivered = as(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", ownerEvidence, validEvidence, "交付"), deliverKey));
        assertThat(delivered).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT file_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(validEvidence);

        // 同键换资产（fileAssetId 假资产）：IDEMPOTENCY_KEY_REUSED——指纹含资产 ID（R3 P2）
        long fakeSwap = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", ownerEvidence, fakeSwap, "交付"), deliverKey)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
        // 同键换资产（ownerAssetId 假资产）：同样拒绝
        long fakeSwapOwner = IDS.incrementAndGet();
        assertThatThrownBy(() -> asVoid(engineer, () -> closuresService.deliver(engineer, projectId,
                new EngClosureService.DeliverCommand(java.time.LocalDate.now(), "业主-陈先生", fakeSwapOwner, validEvidence, "交付"), deliverKey)))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode());
        // 库内交付行仍是原凭据（未被换资产覆盖）
        assertThat(jdbc.queryForObject(
                "SELECT file_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(validEvidence);
        assertThat(jdbc.queryForObject(
                "SELECT owner_asset_id FROM fr_eng_closure WHERE project_id = ?", Long.class, projectId))
                .isEqualTo(ownerEvidence); // R4 P2：双凭据保留断言闭环
    }

    @Test
    void validAcceptedAssetOfAllowedTypePasses() {
        long contractAsset = insertAsset("ENG_SITE_PHOTO", true, store.orgId());
        as(engineer, () -> planService.prepare(engineer, projectId,
                startCmd(contractAsset), idem()));
        assertThat(jdbc.queryForObject(
                "SELECT contract_asset_id FROM fr_eng_project WHERE id = ?", Long.class, projectId))
                .isEqualTo(contractAsset);
    }

    // ========== 辅助 ==========

    private long insertProject() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status, "
                        + "manager_user_id, assigned_engineer_user_id, source_commission_id, source_type, "
                        + "revision, author_user_id, create_time, update_time) "
                        + "VALUES (?, ?, NULL, '陈先生', '陈先生 · 乡墅', 'PENDING_START', ?, ?, ?, 'HANDOVER', "
                        + "1, ?, now(), now())",
                id, store.orgId(), engineer.userId(), engineer.userId(), IDS.incrementAndGet(), engineer.userId());
        // closure（requireActionAllowed/requireOwned 依赖）
        jdbc.update("INSERT INTO fr_eng_closure (id, company_id, project_id, create_time) "
                        + "VALUES (?, ?, ?, now())", IDS.incrementAndGet(), store.orgId(), id);
        return id;
    }

    private long insertNode() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_node (id, company_id, project_id, node_index, name, status, create_time) "
                + "VALUES (?, ?, ?, 1, '主体施工', 'PENDING', now())", id, store.orgId(), projectId);
        return id;
    }

    private long insertMaterial() {
        long materialId = IDS.incrementAndGet();
        long planId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_plan_version (id, company_id, project_id, version, nodes, source, author_user_id, create_time) "
                + "VALUES (?, ?, ?, 1, '{}', 'MANUAL', ?, now())", planId, store.orgId(), projectId, engineer.userId());
        jdbc.update("INSERT INTO fr_eng_material (id, company_id, project_id, material_plan_id, node_id, "
                        + "name, unit, quantity, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, '螺纹钢', '吨', 10.000, 'SUBMITTED', now())",
                materialId, store.orgId(), projectId, planId, nodeId);
        jdbc.update("INSERT INTO fr_eng_supply (id, company_id, project_id, material_id, quantity, payer, create_time) "
                        + "VALUES (?, ?, ?, ?, 10.000, 'STORE', now())",
                IDS.incrementAndGet(), store.orgId(), projectId, materialId);
        return materialId;
    }

    /** orgId=属主组织（测试约定=服务账号 id）；跨组织资产传他组织 id 即被拒 */
    private long insertAsset(String type, boolean accepted, long orgId) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO eng_test_asset (id, asset_type, accepted, org_id) VALUES (?, ?, ?, ?)",
                id, type, accepted, orgId);
        return id;
    }

    private EngPlanService.PrepareCommand startCmd(long assetId) {
        return startCmd(assetId, 40800000L, 18000000L, 9200000L);
    }

    /** 三期（分）：签约后/基础完成后/封顶后——默认 Σ=680000 元 与 fixture 总额一致 */
    private EngPlanService.PrepareCommand startCmd(long assetId, long signAfter, long baseDone, long capDone) {
        return new EngPlanService.PrepareCommand(new BigDecimal("680000"),
                new BigDecimal(signAfter).movePointLeft(2), new BigDecimal(baseDone).movePointLeft(2),
                new BigDecimal(capDone).movePointLeft(2),
                assetId, "开工准备：场地与合同就绪", null, null);
    }

    private BigDecimal quantityOf(long materialId) {
        return jdbc.queryForObject("SELECT quantity FROM fr_eng_material WHERE id = ?",
                BigDecimal.class, materialId);
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
