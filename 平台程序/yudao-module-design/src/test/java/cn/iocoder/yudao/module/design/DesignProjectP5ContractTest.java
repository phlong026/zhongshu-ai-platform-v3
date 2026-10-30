package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetQuarantineAdapter;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5 合同测试（真实 PostgreSQL，全真实模块链）：
 * 页面 04~07 主链——建项目→平面任务扣点→Runtime 产出→结算→候选晋升→选择；
 * 仅公开案例不可生成、授权撤回后新任务拒绝、幂等键防重复点击、对象级权限。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignProjectP5ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 1101L;
    private static final long USER_B = 2202L;
    private static final long ADMIN = 9901L;

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private CaseCatalogService catalog;
    private RightsGrantService rights;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private PointAccountService points;
    private FakeQuarantineStorage quarantine;

    @BeforeAll
    void setUp() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration", "classpath:db/migration/design")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        points = new PointAccountService(dataSource, txManager);
        var priceRules = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        var pricingPort = new PricingPortAdapter(priceRules);
        var ledgerPort = new PointLedgerPortAdapter(points);
        var eventPort = new JdbcReliableEventPort(dataSource);
        quarantine = new FakeQuarantineStorage();
        rights = new RightsGrantService(dataSource);
        catalog = new CaseCatalogService(dataSource, txManager);
        orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var objectStorage = new LocalObjectStorageAdapter(
                Files.createTempDirectory("p5-assets").toString());
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine);
    }

    private void seedPriceRule() {
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9101, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
        // 立面参考用例走完整扣点链，需要 ELEVATION 规则
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9102, 'ELEVATION', 15, 1, 4, now() - interval '1 minute')");
    }

    @Test
    void confirmedPriceMustMatchActualChargeAndRetryKeepsOriginalJob() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "price", "seed-price", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var price = new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation("9101", 1);
        jdbc.update("UPDATE generation_price_rule SET unit_point_cost=15, version=2 WHERE id=9101");
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, projectId, 2, "price-key", price))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_001));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isZero();
        var accepted = new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation("9101", 2);
        long job = projects.createFlatJob(USER_A, projectId, 2, "price-key", accepted).jobId();
        assertThat(jdbc.queryForObject("SELECT total_point_cost FROM ai_task_charge WHERE job_id=?", Long.class, job)).isEqualTo(30);
        jdbc.update("UPDATE generation_price_rule SET version=3 WHERE id=9101");
        assertThat(projects.createFlatJob(USER_A, projectId, 2, "price-key", accepted).jobId()).isEqualTo(job);
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A)).isEqualTo(70);
    }

    @Test
    void flatJobFreezesNoReferenceAssetsForPureTextGeneration() throws Exception {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "pure-text", "seed-pure-text", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, Map.of("floorCount", 2));
        long job = projects.createFlatJob(USER_A, projectId, 1, "pure-text-flat", confirmed("FLAT")).jobId();
        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, job);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot);
        assertThat(json.get("assetIds").size()).isZero();
        assertThat(json.has("references")).isFalse();
        assertThat(json.has("referenceRights")).isFalse();
    }

    @Test
    void projectRecoveryFindsOwnedLatestJobAndMovesToElevationAfterFlatSelection() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "resume", "seed-resume", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "designProjectService", projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A, "app", "id", false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        long jobId = projects.createFlatJob(USER_A, projectId, 2, "resume-job", confirmed("FLAT")).jobId();
        var running = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(running.getJobId()).isEqualTo(String.valueOf(jobId));
        assertThat(running.getResumeAction()).isEqualTo("POLL_JOB");
        jdbc.update("UPDATE ai_job SET status='SUCCEEDED', accepted_count=2 WHERE id=?", jobId);
        assertThat(controller.getProject(String.valueOf(projectId), null, "owner").getData().getResumeAction()).isEqualTo("SELECT_FLAT");
        jdbc.update("INSERT INTO design_selection (id,project_id,stage,candidate_id,selected_by) VALUES (8801,?,'FLAT',8802,?)", projectId, USER_A);
        var selected = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        // 需求①拆分：平面线路选完即收口（不再引导立面），stage 保持线路本位 FLAT
        assertThat(selected.getResumeAction()).isEqualTo("SELECT_FLAT");
        // 当前任务按线路取：平面线路选定后仍携带该条平面任务（恢复态需要它重建候选），不再因"已选平面"而丢失
        assertThat(selected.getJobId()).isEqualTo(String.valueOf(jobId));
        assertThat(selected.getJobStatus()).isEqualTo("SUCCEEDED");
        assertThat(selected.getStage()).isEqualTo("FLAT");
        assertThat(orchestration.latestJob(USER_B, projectId, "FLAT")).isEmpty();
        assertThat(orchestration.latestJob(USER_A, projectId + 1, "FLAT")).isEmpty();
    }

    /** 与上一用例相同的控制器装配，供状态机用例复用 */
    private cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController appController() {
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "designProjectService", projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A, "app", "id", false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        return controller;
    }

    @Test
    void elevationLineRecoverySeesItsOwnElevationJob() {
        // 立面线路项目永远没有平面选择；恢复态必须按线路找立面任务，而不是按"有无平面选择"推导阶段
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "resume-elev", "seed-resume-elev", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null, "ELEVATION");
        var controller = appController();
        long jobId = projects.createElevationJob(USER_A, projectId, 1, "resume-elev-job", null, confirmed("ELEVATION")).jobId();
        var running = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(running.getJobId()).isEqualTo(String.valueOf(jobId));
        assertThat(running.getJobStatus()).isNotNull();
        assertThat(running.getResumeAction()).isEqualTo("POLL_JOB");
        jdbc.update("UPDATE ai_job SET status='SUCCEEDED', accepted_count=1 WHERE id=?", jobId);
        var finished = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(finished.getJobStatus()).isEqualTo("SUCCEEDED");
        assertThat(finished.getResumeAction()).isEqualTo("SELECT_ELEVATION");
    }

    @Test
    void flatLineRegenerationAfterSelectionStaysPollable() {
        // 平面线路选定后允许重新生成平面（allowedActions 含 CREATE_FLAT_JOB）；新任务运行中必须可恢复轮询
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "regen-flat", "seed-regen-flat", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var controller = appController();
        long first = projects.createFlatJob(USER_A, projectId, 1, "regen-flat-1", confirmed("FLAT")).jobId();
        jdbc.update("UPDATE ai_job SET status='SUCCEEDED', accepted_count=1 WHERE id=?", first);
        jdbc.update("INSERT INTO design_candidate (id,project_id,job_id,slot_no,asset_id,ai_result_id) VALUES (8802,?,?,1,8803,8804)",
                projectId, first);
        jdbc.update("INSERT INTO design_selection (id,project_id,stage,candidate_id,selected_by) VALUES (8801,?,'FLAT',8802,?)",
                projectId, USER_A);
        long second = projects.createFlatJob(USER_A, projectId, 1, "regen-flat-2", confirmed("FLAT")).jobId();
        var vo = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(vo.getSelectedFlatCandidateId()).isEqualTo("8802");
        assertThat(vo.getJobId()).isEqualTo(String.valueOf(second));
        assertThat(vo.getResumeAction()).isEqualTo("POLL_JOB");
    }

    /** 已发布、封面图与立面图均授权生成参考的公司案例 */
    private long seedPublishedCaseWithElevation() {
        long caseId = seedPublishedCase(true);
        long versionId = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        long asset = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                asset, "company-cases/" + asset + ".png", ADMIN);
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,'ELEVATION')",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, asset);
        rights.createGrant(ADMIN, asset, "GENERATION_REFERENCE", "平台", "*", "*", Instant.now(), null);
        // 注：seedPublishedCase(true) 已含 COVER(asset1) 且带 GENERATION_REFERENCE 授权——
        // 2026-10-16 需求的「封面+立面两张参考」场景由该封面承担
        return caseId;
    }

    @Test
    void elevationLineMergesUserReferencesWithAuthorizedCaseElevationImages() {
        // 立面参考图"双通道"：户型库带入与自传可同时生效，共享 8 张槽位；用户自传的排在前面，不会被案例图挤掉
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "merge-refs", "seed-merge-refs", null, null);
        long caseId = seedPublishedCaseWithElevation();
        long caseElevationAsset = jdbc.queryForObject("SELECT a.asset_id FROM design_case_asset a JOIN design_case c ON c.current_version_id = a.case_version_id "
                + "WHERE c.id = ? AND a.asset_role = 'ELEVATION'", Long.class, caseId);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (920301, 'user-references/m1', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'aaaa', 'image/png', 10, 'ACCEPTED')", USER_A);
        long projectId = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null,
                Map.of("referenceAssetIds", List.of("920301"), "floorCount", 2), "ELEVATION");
        long jobId = projects.createElevationJob(USER_A, projectId, 1, "merge-refs-job", null, confirmed("ELEVATION")).jobId();
        long caseCoverAsset = jdbc.queryForObject("SELECT a.asset_id FROM design_case_asset a JOIN design_case c ON c.current_version_id = a.case_version_id "
                + "WHERE c.id = ? AND a.asset_role = 'COVER'", Long.class, caseId);
        List<String> assetIds = jdbc.queryForList("SELECT jsonb_array_elements_text(input_snapshot->'assetIds') FROM ai_job WHERE id = ?",
                String.class, jobId);
        // 用户自传最前 → 案例立面图 → 案例封面图（2026-10-16 需求：两张都带）
        assertThat(assetIds).containsExactly("920301", String.valueOf(caseElevationAsset), String.valueOf(caseCoverAsset));
    }

    @Test
    void mergedReferenceImagesNeverExceedEngineLimitOfEightAndKeepUserUploadsFirst() {
        // 契约上限 8 张：自传 3 张 + 案例授权立面图 6 张 → 只留自传 3 张 + 案例前 5 张（按关联 id 稳定排序）
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "cap8", "seed-cap8", null, null);
        long caseId = seedPublishedCaseWithElevation();            // 已含 1 张授权立面图
        long versionId = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        for (int i = 0; i < 5; i++) {
            long asset = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                    + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                    + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                    asset, "company-cases/extra-" + asset + ".png", ADMIN);
            // 同一版本同角色只允许一条无楼层的关联（uk_design_case_asset），多张立面图按楼层号区分
            jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no) VALUES(?,?,?,'ELEVATION',?)",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, asset, i + 1);
            rights.createGrant(ADMIN, asset, "GENERATION_REFERENCE", "平台", "*", "*", Instant.now(), null);
        }
        List<String> caseElevation = jdbc.queryForList("SELECT a.asset_id::text FROM design_case_asset a WHERE a.case_version_id = ? "
                + "AND a.asset_role = 'ELEVATION' ORDER BY a.id", String.class, versionId);
        assertThat(caseElevation).hasSize(6);
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                    + "VALUES (?, ?, ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'aaaa', 'image/png', 10, 'ACCEPTED')", 920400L + i, "user-references/cap-" + i, USER_A);
        }
        long projectId = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null,
                Map.of("referenceAssetIds", List.of("920401", "920402", "920403"), "floorCount", 2), "ELEVATION");
        long jobId = projects.createElevationJob(USER_A, projectId, 1, "cap8-job", null, confirmed("ELEVATION")).jobId();
        List<String> assetIds = jdbc.queryForList("SELECT jsonb_array_elements_text(input_snapshot->'assetIds') FROM ai_job WHERE id = ?",
                String.class, jobId);
        assertThat(assetIds).hasSize(8);
        assertThat(assetIds.subList(0, 3)).containsExactly("920401", "920402", "920403");
        assertThat(assetIds.subList(3, 8)).containsExactlyElementsOf(caseElevation.subList(0, 5));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_candidate, "
                + "design_selection, ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, "
                + "ai_job_settlement, ai_task_charge, design_case, design_case_version, design_case_asset, "
                + "case_publication, asset_rights_grant, design_point_account, design_point_ledger, "
                + "generation_price_rule, outbox_event, asset CASCADE");
        seedPriceRule();
        quarantine.clear();
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 发布带 2 张平面图的公司案例，可选拨权生成参考 */
    private long seedPublishedCase(boolean grantGenerationReference) {
        long caseId = catalog.createCompanyCase(ADMIN, "参考案例", null, "MODERN", 2, 100,
                null, null, null, null);
        long versionId = jdbc.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        long relation1 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long relation2 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long asset1 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long asset2 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                + "VALUES (?,?,?,'FLOOR_PLAN',1)", relation1, versionId, asset1);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                + "VALUES (?,?,?,'FLOOR_PLAN',2)", relation2, versionId, asset2);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                asset1, "company-cases/" + asset1 + ".png", ADMIN);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                asset2, "company-cases/" + asset2 + ".png", ADMIN);
        if (grantGenerationReference) {
            rights.createGrant(ADMIN, asset1, "GENERATION_REFERENCE", "平台", "*", "*",
                    Instant.now(), null);
            rights.createGrant(ADMIN, asset2, "GENERATION_REFERENCE", "平台", "*", "*",
                    Instant.now(), null);
        }
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,'COVER')",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, asset1);
        rights.createGrant(ADMIN, asset1, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        rights.createGrant(ADMIN, asset2, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    /** 假 Runtime：领取任务、产出 N 个有效结果、校验、结算 */
    private void runProviderToSettled(long jobId, int validCount, int requested) {
        var job = orchestration.claim("worker-p5", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("p5-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            var outcome = orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
            assertThat(outcome).isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
        }
        for (int slot = validCount + 1; slot <= requested; slot++) {
            byte[] content = ("p5-bad-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-bad-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
            quarantine.remove(objectKey); // 对象缺失 → 校验拒绝
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    private long available(long userId) {
        return points.findAccount(userId)
                .map(PointAccountService.PointAccount::availablePoints).orElse(0L);
    }

    // ========== 1. 自主设计主链：建项目→任务扣点→产出→候选晋升→选择 ==========

    @Test
    void selfUploadFlatChainEndToEnd() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r",
                "k-seed-a", null, null);

        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("family", "三室两厅", "floorCount", 2));
        var created = projects.createFlatJob(USER_A, projectId, 2, "flat-key-1", confirmed("FLAT"));
        assertThat(available(USER_A)).as("创建任务扣 2×10 点").isEqualTo(80);

        // 幂等键防重复点击
        var replay = projects.createFlatJob(USER_A, projectId, 2, "flat-key-1", confirmed("FLAT"));
        assertThat(replay.jobId()).isEqualTo(created.jobId());
        assertThat(available(USER_A)).isEqualTo(80);

        // 未终态不可晋升/不可选择
        assertThatThrownBy(() -> projects.promoteCandidates(USER_A, projectId, created.jobId()))
                .isInstanceOf(IllegalStateException.class);

        runProviderToSettled(created.jobId(), 2, 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());
        assertThat(candidates).hasSize(2);

        long selectionId = projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        // 同候选重复选择：幂等返回同一 selection
        long same = projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(same).isEqualTo(selectionId);
        // 换选（审查对称性修复）：旧选择失效，新选择生效
        projects.selectFlatCandidate(USER_A, projectId, candidates.get(1).candidateId());
        assertThat(jdbc.queryForObject(
                "SELECT candidate_id FROM design_selection WHERE project_id = ? AND stage = 'FLAT' "
                        + "AND active = TRUE", Long.class, projectId))
                .isEqualTo(candidates.get(1).candidateId());

        // 晋升的候选资产属用户且可走下载链
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM asset WHERE id = ?",
                Long.class, candidates.get(0).assetId())).isEqualTo(USER_A);
    }

    // ========== 2. 参考案例：有生成参考授权才能建任务；冻结快照；他人不可见 ==========

    @Test
    void legacyCaseReferenceProjectCreatesPureTextFlatJobsWithoutGrantChecks() throws Exception {
        long grantedCase = seedPublishedCase(true);
        long ungrantedCase = seedPublishedCase(false);
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "pure-case", "seed-pure-case", null, null);
        for (long caseId : List.of(ungrantedCase, grantedCase)) {
            long project = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null, null);
            long job = projects.createFlatJob(USER_A, project, 1, "pure-case-" + caseId, confirmed("FLAT")).jobId();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, job));
            assertThat(json.get("assetIds").size()).isZero();
            assertThat(json.has("references")).isFalse();
            assertThat(json.has("referenceRights")).isFalse();
        }
    }

    // ========== 3. 对象级权限：B 拿不到 A 的项目/候选/选择 ==========

    @Test
    void crossUserAccessRejected() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r3", "k-seed-c", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var created = projects.createFlatJob(USER_A, projectId, 1, "flat-b", confirmed("FLAT"));
        runProviderToSettled(created.jobId(), 1, 1);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());

        assertThat(projects.getProject(projectId, USER_B)).isEmpty();
        assertThatThrownBy(() -> projects.promoteCandidates(USER_B, projectId, created.jobId()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.selectFlatCandidate(USER_B, projectId,
                candidates.get(0).candidateId()))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 4. 部分结果：N-1 候选 + 差额退点 ==========

    @Test
    void partialResultsPromoteAndRefundDifference() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r4", "k-seed-d", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var created = projects.createFlatJob(USER_A, projectId, 2, "flat-partial", confirmed("FLAT"));

        runProviderToSettled(created.jobId(), 1, 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());
        assertThat(candidates).hasSize(1);
        assertThat(available(USER_A)).as("差额退 10 点").isEqualTo(90);
    }

    // ========== 5. 并发重复点击：同幂等键只建一单扣一次 ==========

    @Test
    void concurrentDuplicateClicksCreateOneJob() throws Exception {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r5", "k-seed-e", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return projects.createFlatJob(USER_A, projectId, 2, "flat-race", confirmed("FLAT")).jobId();
            }));
        }
        start.countDown();
        long first = futures.get(0).get();
        for (Future<Long> f : futures) {
            assertThat(f.get()).isEqualTo(first);
        }
        pool.shutdownNow();
        assertThat(available(USER_A)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE project_ref = ?",
                Integer.class, String.valueOf(projectId))).isEqualTo(1);
    }

    /** 内存隔离区 */
    private static class FakeQuarantineStorage implements QuarantineObjectPort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        void clear() {
            objects.clear();
        }

        void remove(String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public boolean existsWithSize(String objectKey, long expectedSize) {
            byte[] data = objects.get(objectKey);
            return data != null && data.length == expectedSize;
        }

        @Override
        public byte[] getObject(String objectKey) {
            byte[] data = objects.get(objectKey);
            if (data == null) {
                throw new IllegalStateException("对象不存在: " + objectKey);
            }
            return data;
        }

        @Override
        public void putObject(String objectKey, byte[] content) {
            objects.put(objectKey, content);
        }

    }


    private cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmed(String stage) {
        var rows=jdbc.queryForList("SELECT id,version FROM generation_price_rule WHERE stage=? ORDER BY effective_at DESC,id DESC LIMIT 1",stage);
        var row=rows.get(0);
        return new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation(String.valueOf(row.get("id")),((Number)row.get("version")).longValue());
    }

    @Test
    void deleteProjectSoftDeletesAndHidesFromList() {
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        assertThat(projects.getProject(projectId, USER_A)).isPresent();
        projects.deleteProject(USER_A, projectId);
        assertThat(projects.getProject(projectId, USER_A)).isEmpty();
        assertThat(projects.listProjects(USER_A, null, 20).list())
                .noneMatch(item -> item.projectId() == projectId);
        // 他人删除他人的项目 = 权限错误（归属校验先于一切）
        assertThatThrownBy(() -> projects.deleteProject(USER_B, projectId))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test
    void deleteProjectRejectedWhileLatestJobStillActive() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "seed", "seed-del-" + System.nanoTime(), null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        long job = projects.createFlatJob(USER_A, projectId, 2, "del-job-" + projectId, confirmed("FLAT")).jobId();
        jdbc.update("UPDATE ai_job SET status='RUNNING' WHERE id=?", job);
        assertThatThrownBy(() -> projects.deleteProject(USER_A, projectId))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_006));
        assertThat(projects.getProject(projectId, USER_A)).isPresent();
        jdbc.update("UPDATE ai_job SET status='CANCELLED' WHERE id=?", job);
        projects.deleteProject(USER_A, projectId);
        assertThat(projects.getProject(projectId, USER_A)).isEmpty();
    }

    // ========== 6. 案例立面参考：立面任务输入 = 已选平面 + 有授权的案例立面图 ==========
    // uk_design_case_asset 决定每个案例版本至多一张立面图，授权/未授权两条路径各用一个案例验证

    private long seedCaseElevationAsset(long caseId, boolean grant) {
        long versionId = jdbc.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        long assetId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role) "
                        + "VALUES (?,?,?,'ELEVATION')",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, assetId);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                        + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                        + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                assetId, "company-cases/" + assetId + ".png", ADMIN);
        if (grant) {
            rights.createGrant(ADMIN, assetId, "GENERATION_REFERENCE", "平台", "*", "*",
                    Instant.now(), null);
        }
        return assetId;
    }

    @Test
    void preT26FlatReceiptReplaysWithoutAddingEvidenceAndStillRejectsChangedIntent() throws Exception {
        long caseId = seedPublishedCase(true);
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "fixture", "legacy-replay", "legacy-replay-seed", null, null);
        long project = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null, Map.of("floorCount", 2, "note", "原始需求"));
        long job = projects.createFlatJob(USER_A, project, 1, "legacy-flat-replay", confirmed("FLAT")).jobId();
        // B3 persisted this exact envelope without the two new T26-08 evidence fields.
        jdbc.update("UPDATE ai_job SET input_snapshot=input_snapshot-'references'-'referenceRights' WHERE id=?", job);
        String original = jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, job);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(original).has("references")).isFalse();
        jdbc.update("UPDATE asset_rights_grant SET rights_version=2 WHERE scope='GENERATION_REFERENCE'");

        var replay = projects.createFlatJob(USER_A, project, 1, "legacy-flat-replay", confirmed("FLAT"));

        assertThat(replay.jobId()).isEqualTo(job);
        assertThat(replay.created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, job)).isEqualTo(original);
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, project, 2, "legacy-flat-replay", confirmed("FLAT")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(
                        cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
        var landscape = cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("FLAT", "2K", "LANDSCAPE");
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, project, 1, "legacy-flat-replay", confirmed("FLAT"), landscape))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(
                        cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
        jdbc.update("UPDATE design_requirement_snapshot SET inputs=inputs || '{\"note\":\"变更后的需求\"}'::jsonb WHERE project_id=?", project);
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, project, 1, "legacy-flat-replay", confirmed("FLAT")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(
                        cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, job)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE user_id=? AND type='FLAT_GENERATION_DEBIT'", Long.class, USER_A)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A)).isEqualTo(90L);
    }

    @Test
    void flatReceiptReplayAndNewKeysRemainPureTextAfterGrantChanges() throws Exception {
        long caseId = seedPublishedCase(true);
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "fixture", "pure-replay", "pure-replay-seed", null, null);
        long project = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null, Map.of("floorCount", 2));
        long first = projects.createFlatJob(USER_A, project, 1, "pure-replay-first", confirmed("FLAT")).jobId();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String original = jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, first);
        assertThat(mapper.readTree(original).get("assetIds").size()).isZero();
        assertThat(mapper.readTree(original).has("references")).isFalse();

        jdbc.update("UPDATE asset_rights_grant SET rights_version=2 WHERE scope='GENERATION_REFERENCE'");
        var replay = projects.createFlatJob(USER_A, project, 1, "pure-replay-first", confirmed("FLAT"));
        assertThat(replay.jobId()).isEqualTo(first);
        assertThat(replay.created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, first)).isEqualTo(original);

        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()-interval '1 second' WHERE scope='GENERATION_REFERENCE'");
        long expiredNewKey = projects.createFlatJob(USER_A, project, 1, "pure-replay-expired", confirmed("FLAT")).jobId();
        assertThat(expiredNewKey).isPositive();
        var expiredJson = mapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, expiredNewKey));
        assertThat(expiredJson.get("assetIds").size()).isZero();
        assertThat(expiredJson.has("referenceRights")).isFalse();

        jdbc.update("UPDATE asset_rights_grant SET expires_at=NULL,status='WITHDRAWN' WHERE scope='GENERATION_REFERENCE'");
        long revokedNewKey = projects.createFlatJob(USER_A, project, 1, "pure-replay-revoked", confirmed("FLAT")).jobId();
        assertThat(revokedNewKey).isPositive();
        var revokedJson = mapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, revokedNewKey));
        assertThat(revokedJson.get("assetIds").size()).isZero();
        assertThat(revokedJson.has("referenceRights")).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE user_id=? AND type='FLAT_GENERATION_DEBIT'", Long.class, USER_A)).isEqualTo(3L);
        assertThat(available(USER_A)).isEqualTo(70);
    }

    @Test
    void actualScannerPixelsAndOriginalDigestSurviveSanitizationAndCandidatePromotion() throws Exception {
        var ds=jdbc.getDataSource();
        var manager=new DataSourceTransactionManager(ds);
        var scanner=new AssetContentScanner();
        var core=new AiJobOrchestrationService(ds,manager,new JdbcReliableEventPort(ds),quarantine,(mime,content) -> {
            var report=scanner.scan(mime,content);
            return new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                    report.passed(),report.failures(),report.width(),report.height(),report.pageCount(),
                    report.sanitizedContent(),"real-image-scanner-fixture");
        });
        points.credit(USER_A,"RECHARGE_BASE_CREDIT",100,"fixture","scan-pixels","scan-pixels-seed",null,null);
        long project=projects.createProject(USER_A,"SELF_UPLOAD",null,null,Map.of("floorCount",2));
        long job=projects.createFlatJob(USER_A,project,1,"scan-pixels-job",confirmed("FLAT")).jobId();
        var claim=core.claim("pixel-test-worker",1,60,"fixture").get(0);
        assertThat(claim.jobId()).isEqualTo(job);
        var png=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(13,7,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",png);
        // Existing metadata stripping changes the byte digest without changing the actual 13 × 7 pixels.
        png.write("trailing-fixture-metadata".getBytes(StandardCharsets.UTF_8));
        byte[] raw=png.toByteArray();
        String key=claim.outputPrefix()+"/pixels.png";
        quarantine.putObject(key,raw);
        assertThat(core.reportResult(job,claim.attemptNo(),claim.fencingToken(),"fixture","pixel-event",1,key,
                sha256(raw),"image/png",raw.length)).isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
        assertThat(core.validateQuarantinedResults(job)).isEqualTo(1);
        var result=jdbc.queryForMap("SELECT source_content_sha256,content_sha256,pixel_width,pixel_height,object_key "
                + "FROM ai_job_result WHERE job_id=? AND validation_state='ACCEPTED'",job);
        assertThat(result.get("source_content_sha256")).isEqualTo(sha256(raw));
        assertThat(result.get("content_sha256")).isNotEqualTo(sha256(raw));
        assertThat(result.get("pixel_width")).isEqualTo(13);
        assertThat(result.get("pixel_height")).isEqualTo(7);
        assertThat(sha256(quarantine.getObject((String)result.get("object_key")))).isEqualTo(result.get("content_sha256"));
        settlement.settle(job,"SETTLE");
        var candidates=projects.promoteCandidates(USER_A,project,job);
        assertThat(candidates).hasSize(1);
        var asset=jdbc.queryForMap("SELECT width,height,stored_sha256 FROM asset WHERE id=?",candidates.get(0).assetId());
        assertThat(asset.get("width")).isEqualTo(13);
        assertThat(asset.get("height")).isEqualTo(7);
        assertThat(asset.get("stored_sha256")).isEqualTo(result.get("content_sha256"));
        assertThat(projects.promoteCandidates(USER_A,project,job)).hasSize(1);
    }

    @Test
    void caseElevationReferencesJoinElevationJobInput() throws Exception {
        long grantedCase = seedPublishedCase(true);
        long grantedElevation = seedCaseElevationAsset(grantedCase, true);
        long ungrantedCase = seedPublishedCase(true);
        long ungrantedElevation = seedCaseElevationAsset(ungrantedCase, false);

        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 200, "recharge_order", "r6", "k-seed-elev", null, null);
        for (long caseId : List.of(grantedCase, ungrantedCase)) {
            long projectId = projects.createProject(USER_A, "CASE_REFERENCE", caseId, null, null);
            var flat = projects.createFlatJob(USER_A, projectId, 2, "flat-elev-" + caseId, confirmed("FLAT"));
            runProviderToSettled(flat.jobId(), 2, 2);
            var candidates = projects.promoteCandidates(USER_A, projectId, flat.jobId());
            projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());

            var elev = projects.createElevationJob(USER_A, projectId, 2, "elev-ref-" + caseId,
                    Map.of("styleCode", "MODERN"), confirmed("ELEVATION"));
            Map<String, Object> input = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?",
                            String.class, elev.jobId()), Map.class);
            @SuppressWarnings("unchecked")
            List<String> frozenAssetIds = (List<String>) input.get("assetIds");
            // 顺序契约：已选平面（空间约束基准）在前；案例立面图有授权才随后进入，未授权的被排除
            // （若未授权资产混入输入，运行期会被 RUNTIME_INPUT_RIGHTS 拒单）
            if (caseId == grantedCase) {
                // 2026-10-16 需求：案例封面（asset1，已授权）随立面图一并进入立面参考
                long grantedCover = jdbc.queryForObject(
                        "SELECT a.asset_id FROM design_case_asset a JOIN design_case c ON c.current_version_id = a.case_version_id "
                        + "WHERE c.id = ? AND a.asset_role = 'COVER'", Long.class, caseId);
                assertThat(frozenAssetIds).containsExactly(
                        String.valueOf(candidates.get(0).assetId()), String.valueOf(grantedElevation),
                        String.valueOf(grantedCover));
            } else {
                // 该案例仅立面未授权：立面被排除，但封面（种子 asset1 已授权）按新规则照常带入
                long ungrantedCover = jdbc.queryForObject(
                        "SELECT a.asset_id FROM design_case_asset a JOIN design_case c ON c.current_version_id = a.case_version_id "
                        + "WHERE c.id = ? AND a.asset_role = 'COVER'", Long.class, caseId);
                assertThat(frozenAssetIds).containsExactly(
                        String.valueOf(candidates.get(0).assetId()), String.valueOf(ungrantedCover));
                assertThat(frozenAssetIds).doesNotContain(String.valueOf(ungrantedElevation));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceFlat = (Map<String, Object>) input.get("sourceFlat");
            assertThat(sourceFlat).containsEntry("assetId", String.valueOf(candidates.get(0).assetId()));
            // 结清立面任务再进入下一案例：避免 QUEUED 任务被下一轮 claim 误领
            runProviderToSettled(elev.jobId(), 2, 2);
        }
    }
}
