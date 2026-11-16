package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetQuarantineAdapter;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6 合同测试（真实 PostgreSQL 全真实链）：未选平面不能生成立面、跨项目候选拒绝、
 * 立面选择生成不可变最终版本、调整请求生成新版本（旧版本只读）、重复选择幂等。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultVersionP6ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 3301L;
    private static final cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort ACTIVE_FIXTURE_ACCOUNTS = accountId -> {
        if (!List.of(USER_A, 4402L).contains(accountId)) {
            throw new ServiceException(403, "测试账户未开通");
        }
    };

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private FakeQuarantineStorage quarantine;

    @BeforeAll
    void setUp() {
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
        var points = new cn.iocoder.yudao.module.commerce.points.PointAccountService(dataSource, txManager);
        var priceRules = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        var pricingPort = new cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter(priceRules);
        var ledgerPort = new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(points);
        var eventPort = new JdbcReliableEventPort(dataSource);
        quarantine = new FakeQuarantineStorage();
        var rights = new RightsGrantService(dataSource);
        orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var objectStorage = new LocalObjectStorageAdapter(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "p6-assets-" + System.nanoTime())
                        .toString());
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights,
                ACTIVE_FIXTURE_ACCOUNTS);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine,
                ACTIVE_FIXTURE_ACCOUNTS);
        for (Object service : List.of(points, orchestration, settlement)) {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "accountStatePort", ACTIVE_FIXTURE_ACCOUNTS);
        }
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_candidate, "
                + "design_selection, design_result_version, design_revision_request, "
                + "ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, ai_job_settlement, "
                + "ai_task_charge, generation_price_rule, design_point_account, design_point_ledger, outbox_event");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9201, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9202, 'ELEVATION', 15, 1, 4, now() - interval '1 minute')");
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

    private void givePoints(long userId, long amount) {
        var accounts = new cn.iocoder.yudao.module.commerce.points.PointAccountService(
                objectDataSource(), new DataSourceTransactionManager(objectDataSource()));
        org.springframework.test.util.ReflectionTestUtils.setField(accounts, "accountStatePort", ACTIVE_FIXTURE_ACCOUNTS);
        var points = new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(accounts);
        points.credit(userId, "RECHARGE_BASE_CREDIT", amount, "recharge_order", "r-" + userId,
                "k-seed-" + userId + "-" + amount, null, null);
    }

    private DataSource objectDataSource() {
        try {
            var ds = new SimpleDriverDataSource();
            ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(PG.getJdbcUrl());
            ds.setUsername(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void runElevation(long jobId, int validCount) {
        var job = orchestration.claim("worker-p6", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("p6-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    /** 完整走到「已选定平面」的项目 */
    private long projectWithFlatSelected() {
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(USER_A, projectId, 2, "flat-" + projectId, confirmed("FLAT"));
        runElevationJobAsFlat(flat.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, flat.jobId());
        projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        return projectId;
    }

    private void runElevationJobAsFlat(long jobId, int validCount) {
        runElevation(jobId, validCount); // 假 Runtime 与阶段无关
    }

    @Test
    void explicitFlatConfirmationIsConcurrentIdempotentAndDoesNotChargeOrBackfill() throws Exception {
        long projectId = projectWithFlatSelected();
        assertThat(projects.listResultVersions(USER_A, projectId)).isEmpty();
        long candidate = jdbc.queryForObject("SELECT candidate_id FROM design_selection WHERE project_id=? AND active=TRUE", Long.class, projectId);
        long ledgerCount = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> projects.confirmFlatCandidate(USER_A, projectId, candidate));
            var b = pool.submit(() -> projects.confirmFlatCandidate(USER_A, projectId, candidate));
            assertThat(a.get()).isEqualTo(b.get());
        } finally { pool.shutdownNow(); }
        var versions = projects.listResultVersions(USER_A, projectId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("selected_flat_asset_id")).isNotNull();
        assertThat(versions.get(0).get("selected_elevation_asset_id")).isNull();
        assertThatThrownBy(() -> projects.confirmFlatCandidate(USER_A + 1, projectId, candidate)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.listResultVersions(USER_A + 1, projectId)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.createRevisionRequest(USER_A, projectId, "不能借平面确认开启付费立面", null, 1, "flat-revision", confirmed("ELEVATION"))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledgerCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(1L);
    }

    @Test
    void flatVersionsKeepOwnSourceSpecsAndAssetsAfterLaterJobs() {
        long projectId = projectWithFlatSelected();
        var candidates = jdbc.queryForList("SELECT id FROM design_candidate WHERE project_id=? ORDER BY slot_no", Long.class, projectId);
        var first = projects.confirmFlatCandidate(USER_A, projectId, candidates.get(0));
        var second = projects.confirmFlatCandidate(USER_A, projectId, candidates.get(1));
        assertThat(second.version()).isEqualTo(first.version() + 1);
        var before = projects.listResultVersions(USER_A, projectId);
        var later = projects.createFlatJob(USER_A, projectId, 1, "later-job", confirmed("FLAT"));
        // 仅隔离夹具：模拟后续任务采用不同规格；历史版本必须仍按各自来源读取。
        jdbc.update("UPDATE ai_job SET input_snapshot=jsonb_set(input_snapshot, '{imageOptions}', '{\"resolution\":\"4K\",\"orientation\":\"PORTRAIT\"}'::jsonb) WHERE id=?", later.jobId());
        var after = projects.listResultVersions(USER_A, projectId);
        assertThat(after).isEqualTo(before);
        assertThat(after.get(0).get("superseded")).isEqualTo(false);
        assertThat(after.get(1).get("superseded")).isEqualTo(true);
        assertThat(after.get(0).get("selected_flat_asset_id")).isNotEqualTo(after.get(1).get("selected_flat_asset_id"));
        assertThat(after.get(0).get("flat_image_options").toString()).contains("2K");
    }

    @Test
    void flatConfirmationEndpointReturnsRecoverableVersionAndFrozenMetadata() {
        long projectId = projectWithFlatSelected();
        projects.renameProject(USER_A, projectId, "我的院子");
        var selected = jdbc.queryForMap("SELECT c.id,c.job_id,c.asset_id FROM design_candidate c JOIN design_selection s ON s.candidate_id=c.id WHERE s.project_id=? AND s.active=TRUE", projectId);
        long assetId = ((Number) selected.get("asset_id")).longValue();
        jdbc.update("UPDATE asset SET width=1536,height=1024 WHERE id=?", assetId);
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "designProjectService", projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A, "test", "test", false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        var request = new cn.iocoder.yudao.module.design.controller.app.vo.AppSelectionCreateReqVO();
        request.setCandidateId(selected.get("id").toString()); request.setJobId(selected.get("job_id").toString());
        var result = controller.selectFlat(String.valueOf(projectId), request, "Bearer owner").getData();
        assertThat(result.getResultVersionId()).isNotBlank();
        assertThat(result.getResumeAction()).isEqualTo("VIEW_RESULT");
        assertThat(result.getAllowedActions()).contains("VIEW_RESULT").doesNotContain("CREATE_REVISION");
        assertThat(result.getName()).isEqualTo("我的院子");
        var versions = controller.getResultVersions(String.valueOf(projectId), "Bearer owner").getData().getList();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getFlatImageInfo()).containsEntry("resolution", "2K").containsEntry("width", 1536).containsEntry("height", 1024);
        assertThat(versions.get(0).getSelectedFlatAssetId()).isEqualTo(String.valueOf(assetId));
        assertThat(projects.listProjects(USER_A, null, 20).list().get(0).hasResult()).isTrue();
        assertThat(projects.listProjects(USER_A, null, 20).list().get(0).hasDualImages()).isFalse();
    }


    @Test
    void legacyFlatConfirmationReturnsNextActionWithoutOldVersionOrCharge() {
        long projectId = projectWithFlatSelected();
        var selected = jdbc.queryForMap("SELECT c.id,c.job_id FROM design_candidate c JOIN design_selection s ON s.candidate_id=c.id WHERE s.project_id=? AND s.active=TRUE", projectId);
        projects.confirmFlatCandidate(USER_A, projectId, ((Number)selected.get("id")).longValue());
        jdbc.update("UPDATE design_project SET stage='ELEVATION' WHERE id=?", projectId);
        long ledgerCount = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"designProjectService",projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A,"test","test",false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"identitySessionPort",identities);
        var request = new cn.iocoder.yudao.module.design.controller.app.vo.AppSelectionCreateReqVO();
        request.setCandidateId(selected.get("id").toString()); request.setJobId(selected.get("job_id").toString());
        for (int retry=0; retry<2; retry++) {
            var result = controller.selectFlat(String.valueOf(projectId),request,"Bearer owner").getData();
            assertThat(result.getResultVersionId()).isNull();
            assertThat(result.getResumeAction()).isEqualTo("CREATE_ELEVATION_JOB");
            assertThat(result.getSelectedFlatCandidateId()).isEqualTo(request.getCandidateId());
        }
        assertThat(projects.listResultVersions(USER_A,projectId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Long.class)).isEqualTo(ledgerCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job",Long.class)).isEqualTo(1L);
    }

    @Test
    void resumedElevationFreezesNewInputsAndAuthorizedReferencesWithoutNewProjectOrChargeOnRetry() throws Exception {
        long projectId = projectWithFlatSelected();
        asset(928101L, USER_A, "DESIGN_REFERENCE", "ACCEPTED");
        asset(928102L, USER_A + 1, "DESIGN_REFERENCE", "ACCEPTED");
        asset(928103L, USER_A + 1, "DESIGN_REFERENCE", "ACCEPTED");
        jdbc.update("UPDATE asset SET security_scan_status='PASSED', moderation_status='PASSED' WHERE id IN (928101,928102,928103)");
        jdbc.update("INSERT INTO design_case(id,source_type,creator_user_id,current_version_id,publication_status) VALUES(928201,'COMPANY',1,928202,'PUBLISHED')");
        jdbc.update("INSERT INTO design_case_version(id,case_id,version,title,style_code,floor_count,building_area) VALUES(928202,928201,1,'fixture','MODERN',2,200)");
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(928203,928202,928102,'ELEVATION')");
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) VALUES(928204,928102,1,'fixture','GENERATION_REFERENCE',now()-interval '1 minute')");
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"designProjectService",projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A,"test","test",false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"identitySessionPort",identities);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        var request = mapper.convertValue(Map.of("count",1,"requirementInputs",Map.of("note","保留庭院","floorCount",3,"referenceAssetIds",List.of("928101")),"refCaseId","928201"),
                cn.iocoder.yudao.module.design.controller.app.vo.AppDesignJobCreateReqVO.class);
        request.setPriceConfirmation(confirmed("ELEVATION"));
        String job = controller.createElevationJob(String.valueOf(projectId),request,"resume-inputs","Bearer owner").getData().getJobId();
        var frozen = mapper.readValue(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?",String.class,Long.parseLong(job)),Map.class);
        assertThat((Map<String,Object>)frozen.get("requirements")).containsEntry("note","保留庭院").containsEntry("floorCount",3);
        assertThat((List<String>)frozen.get("assetIds")).contains("928101","928102");
        assertThat(frozen).containsKey("sourceFlat");
        long ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Long.class);
        assertThat(controller.createElevationJob(String.valueOf(projectId),request,"resume-inputs","Bearer owner").getData().getJobId()).isEqualTo(job);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Long.class)).isEqualTo(ledger);
        for (var bad : List.of(Map.of("referenceAssetIds",List.of("928103")),Map.of("referenceAssetIds",List.of("999999999")))) {
            var denied = mapper.convertValue(Map.of("count",1,"requirementInputs",bad),cn.iocoder.yudao.module.design.controller.app.vo.AppDesignJobCreateReqVO.class);
            denied.setPriceConfirmation(confirmed("ELEVATION"));
            assertThatThrownBy(() -> controller.createElevationJob(String.valueOf(projectId),denied,"bad-upload","Bearer owner")).isInstanceOf(ServiceException.class);
        }
        var changed = mapper.convertValue(Map.of("count",1,"requirementInputs",Map.of("note","changed","floorCount",3,"referenceAssetIds",List.of("928101")),"refCaseId","928201"),
                cn.iocoder.yudao.module.design.controller.app.vo.AppDesignJobCreateReqVO.class);
        changed.setPriceConfirmation(confirmed("ELEVATION"));
        assertIdempotencyConflict(() -> controller.createElevationJob(String.valueOf(projectId), changed, "resume-inputs", "Bearer owner"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledger);
        for (String column : List.of("publication_status='OFFLINE'", "tenant_id=1")) {
            jdbc.update("UPDATE design_case SET " + column + " WHERE id=928201");
            assertThatThrownBy(() -> controller.createElevationJob(String.valueOf(projectId),request,"forbidden-case","Bearer owner")).isInstanceOf(ServiceException.class);
            jdbc.update("UPDATE design_case SET publication_status='PUBLISHED',tenant_id=0 WHERE id=928201");
        }
        var cleared = mapper.convertValue(Map.of("count",1,"requirementInputs",Map.of("note","","floorCount",2)),
                cn.iocoder.yudao.module.design.controller.app.vo.AppDesignJobCreateReqVO.class);
        cleared.setPriceConfirmation(confirmed("ELEVATION"));
        String clearedJob = controller.createElevationJob(String.valueOf(projectId),cleared,"clear-refs","Bearer owner").getData().getJobId();
        var clearedInput = mapper.readValue(jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?",String.class,Long.parseLong(clearedJob)),Map.class);
        assertThat((List<String>)clearedInput.get("assetIds")).doesNotContain("928101","928102");
        assertThat(clearedInput).containsKey("sourceFlat");
        ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Long.class);
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN' WHERE id=928204");
        assertThatThrownBy(() -> controller.createElevationJob(String.valueOf(projectId),request,"revoked-case","Bearer owner")).isInstanceOf(ServiceException.class);
        assertThat(controller.createElevationJob(String.valueOf(projectId), request, "resume-inputs", "Bearer owner")
                .getData().getJobId()).isEqualTo(job);
        assertThat(mapper.readValue(jobSnapshot(Long.parseLong(job)), Map.class)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger",Long.class)).isEqualTo(ledger);
    }

    @Test
    void elevationReplayComparesNormalizedRequestBeforeMutableState() {
        givePoints(USER_A, 500);
        long project = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null, "ELEVATION");
        var options = cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", "2K", "PORTRAIT");
        var config = Map.<String, Object>of("note", "  保留庭院  ", "floorCount", "3");
        long job = projects.createElevationJob(USER_A, project, 1, "normalized-replay", config,
                confirmed("ELEVATION"), options).jobId();
        String original = jobSnapshot(job);
        long ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        long balance = jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A);
        jdbc.update("UPDATE design_project SET stage='FLAT' WHERE id=?", project);
        jdbc.update("UPDATE design_requirement_snapshot SET inputs='{\"note\":\"后续需求\"}'::jsonb WHERE project_id=?", project);
        jdbc.update("UPDATE generation_price_rule SET version=version+1");

        var normalized = Map.<String, Object>of("floorCount", 3, "note", "保留庭院");
        var replay = projects.createElevationJob(USER_A, project, 1, "normalized-replay", normalized, null, options);
        assertThat(replay.jobId()).isEqualTo(job);
        assertThat(replay.created()).isFalse();
        for (Map<String, Object> changed : List.of(Map.<String, Object>of("floorCount", 3),
                Map.<String, Object>of("floorCount", 3, "note", "不同需求"), Map.<String, Object>of())) {
            assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "normalized-replay", changed, null, options));
        }
        for (var changed : List.of(
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", "2K", "LANDSCAPE"),
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", "4K", "PORTRAIT"))) {
            assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "normalized-replay", normalized, null, changed));
        }
        assertThat(jobSnapshot(job)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A)).isEqualTo(balance);
    }

    @Test
    void legacyElevationReplayUsesCompleteFrozenConfigAndImageOptions() {
        givePoints(USER_A, 500);
        long project = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null, "ELEVATION");
        var config = Map.<String, Object>of("note", "庭院", "floorCount", 3);
        long job = projects.createElevationJob(USER_A, project, 1, "legacy-elevation", config, confirmed("ELEVATION")).jobId();
        jdbc.update("UPDATE ai_job SET input_snapshot=input_snapshot-'requestedImageOptions'-'requestConfig' WHERE id=?", job);
        String original = jobSnapshot(job);
        long ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        jdbc.update("UPDATE design_project SET stage='FLAT' WHERE id=?", project);
        jdbc.update("UPDATE design_requirement_snapshot SET inputs='{}'::jsonb WHERE project_id=?", project);

        var replay = projects.createElevationJob(USER_A, project, 1, "legacy-elevation",
                Map.of("note", " 庭院 ", "floorCount", "3"), null);
        assertThat(replay.jobId()).isEqualTo(job);
        assertThat(replay.created()).isFalse();
        // 确证旧快照缺 requestConfig：省略字段继承原冻结有效输入，同值覆写仍是同一设计意图。
        for (Map<String, Object> retry : List.of(Map.<String, Object>of("note", " 庭院 "), Map.<String, Object>of())) {
            var legacyReplay = projects.createElevationJob(USER_A, project, 1, "legacy-elevation", retry, null);
            assertThat(legacyReplay.jobId()).isEqualTo(job);
            assertThat(legacyReplay.created()).isFalse();
        }
        assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "legacy-elevation", Map.of("note", "变更", "floorCount", 3), null));
        assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "legacy-elevation", config, null,
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", "2K", "PORTRAIT")));
        assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "legacy-elevation", config, null,
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", "4K", "LANDSCAPE")));
        assertThat(jobSnapshot(job)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledger);
    }

    @Test
    void legacyElevationWithEmptyRequestReplaysInheritedFrozenRequirements() {
        givePoints(USER_A, 500);
        long project = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("note", "原始需求", "floorCount", 2), "ELEVATION");
        for (Map<String, Object> config : List.of(Map.<String, Object>of(), Map.<String, Object>of("note", "局部覆盖"))) {
            String key = "legacy-inherited-" + config.size();
            long job = projects.createElevationJob(USER_A, project, 1, key, config, confirmed("ELEVATION")).jobId();
            jdbc.update("UPDATE ai_job SET input_snapshot=input_snapshot-'requestConfig'-'requestedImageOptions' WHERE id=?", job);
            String original = jobSnapshot(job);
            long jobs = jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class);
            long ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
            var replay = projects.createElevationJob(USER_A, project, 1, key, config, null);
            assertThat(replay.jobId()).isEqualTo(job);
            assertThat(replay.created()).isFalse();
            assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, key, Map.of("floorCount", 3), null));
            assertThat(jobSnapshot(job)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(jobs);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledger);
        }
    }

    @Test
    void revisionReplayRejectsChangedOrientationAndKeepsFirstSpecsAfterVersionEvolution() {
        long project = projectWithFlatSelected();
        long elevation = projects.createElevationJob(USER_A, project, 1, "revision-source", null, confirmed("ELEVATION")).jobId();
        runElevation(elevation, 1);
        projects.selectElevationCandidate(USER_A, project, projects.promoteCandidates(USER_A, project, elevation).get(0).candidateId());
        var config = Map.<String, Object>of("note", "调整庭院", "floorCount", 3);
        var created = projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 1, "revision-replay",
                confirmed("ELEVATION"), null, null);
        runElevation(created.newJobId(), 1);
        var version = projects.selectElevationCandidate(USER_A, project,
                projects.promoteCandidates(USER_A, project, created.newJobId()).get(0).candidateId());
        long ledger = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        long balance = jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A);
        // 隔离夹具模拟后续版本规格变化；省略规格的旧请求必须仍按首次冻结值恢复。
        jdbc.update("UPDATE design_result_version SET config_snapshot=jsonb_set(config_snapshot,'{imageOptions}','{\"resolution\":\"4K\",\"orientation\":\"PORTRAIT\"}'::jsonb) WHERE id=?", version.versionId());
        jdbc.update("UPDATE design_selection SET active=FALSE WHERE project_id=?", project);
        jdbc.update("UPDATE generation_price_rule SET version=version+1");
        for (boolean legacy : List.of(false, true)) {
            if (legacy) {
                jdbc.update("UPDATE ai_job SET input_snapshot=input_snapshot-'requestedImageOptions'-'requestConfig' WHERE id=?", created.newJobId());
            }
            String original = jobSnapshot(created.newJobId());
            var replay = projects.createRevisionRequestReceipt(USER_A, project, "改庭院",
                    config, 1, "revision-replay", null, null, null);
            assertThat(replay.requestId()).isEqualTo(created.requestId());
            assertThat(replay.newJobId()).isEqualTo(created.newJobId());
            assertThat(replay.replayed()).isTrue();
            assertThat(projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 1,
                    "revision-replay", null, "2K", "LANDSCAPE").replayed()).isTrue();
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 1,
                    "revision-replay", null, "2K", "PORTRAIT"));
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 1,
                    "revision-replay", null, "4K", "LANDSCAPE"));
            assertThat(projects.createRevisionRequestReceipt(USER_A, project, "改庭院",
                    Map.of("floorCount", "3", "note", " 调整庭院 "), 1, "revision-replay", null, null, null).replayed()).isTrue();
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "不同理由", config, 1,
                    "revision-replay", null, null, null));
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "改庭院", Map.of(), 1,
                    "revision-replay", null, null, null));
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 2,
                    "revision-replay", null, null, null));
            assertIdempotencyConflict(() -> projects.createElevationJob(USER_A, project, 1, "revision-replay", config, null));
            assertIdempotencyConflict(() -> projects.createRevisionRequestReceipt(USER_A, project, "改庭院", config, 1,
                    "revision-source", null, null, null));
            assertThat(jobSnapshot(created.newJobId())).isEqualTo(original);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_revision_request", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A)).isEqualTo(balance);
    }

    private String jobSnapshot(long jobId) {
        return jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?", String.class, jobId);
    }

    private void assertIdempotencyConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        assertThatThrownBy(request).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(
                        cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
    }

    // ========== 1. 需求①拆分：未选平面可直接生成立面（纯立面线路，零参考文生图） ==========

    @Test
    void elevationJobAllowedWithoutFlatSelection() {
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null, "ELEVATION");
        var elev = projects.createElevationJob(USER_A, projectId, 2, "elev-solo", null, confirmed("ELEVATION"));
        assertThat(elev.jobId()).isPositive();
        assertThat(projects.getProject(projectId).orElseThrow().stage()).isEqualTo("ELEVATION");
    }

    @Test
    void flatLineProjectWithoutFlatSelectionCannotStartElevation() {
        // 线路出生即定：平面线路且没有存量平面选择的项目，不能被直接带进立面（否则 stage 被翻成 ELEVATION，状态机由客户端行为左右）
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        assertThatThrownBy(() -> projects.createElevationJob(USER_A, projectId, 1, "cross-line", null, confirmed("ELEVATION")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(1_071_000_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isZero();
        assertThat(projects.getProject(projectId).orElseThrow().stage()).isEqualTo("FLAT");
    }

    @Test
    void sketchAssetMustBeOwnedAcceptedUserSketchBeforeProjectCreation() {
        // 与 referenceAssetIds 同口径：建项目时快速失败，而不是等 runtime 下发图片时才失败并走退款
        asset(920201L, USER_A, "USER_SKETCH", "ACCEPTED");
        asset(920202L, USER_A + 1, "USER_SKETCH", "ACCEPTED");      // 他人的
        asset(920203L, USER_A, "USER_SKETCH", "PENDING");           // 未通过上传校验
        asset(920204L, USER_A, "DESIGN_REFERENCE", "ACCEPTED");     // 类型不对
        assertThat(projects.createProject(USER_A, "SELF_UPLOAD", null, 920201L, null)).isPositive();
        for (long bad : List.of(920202L, 920203L, 920204L, 999999999L)) {
            assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, bad, null))
                    .as("sketchAssetId=" + bad).isInstanceOf(ServiceException.class);
        }
    }

    private void asset(long id, long owner, String type, String uploadStatus) {
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, ?, ?, ?, 'USER_UPLOAD', 'aaaa', 'image/png', 10, ?)", id, "uploads/" + id, owner, type, uploadStatus);
    }

    // ========== 1b. 需求五b：DESIGN_REFERENCE 参考图进快照；他人资产拒绝 ==========

    @Test
    void elevationInputCarriesOwnedDesignReferencesAndRejectsForeignAssets() {
        givePoints(USER_A, 500);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, 'user-references/r1', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'aaaa', 'image/png', 10, 'ACCEPTED')", 920101L, USER_A);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, 'user-references/r2', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'bbbb', 'image/png', 10, 'ACCEPTED')", 920102L, USER_A);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                java.util.Map.of("referenceAssetIds", java.util.List.of("920101", "920102"), "floorCount", 2), "ELEVATION");
        var elev = projects.createElevationJob(USER_A, projectId, 1, "elev-refs", null, confirmed("ELEVATION"));
        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id = ?", String.class, elev.jobId());
        assertThat(snapshot).contains("\"920101\"").contains("\"920102\"");
        // 他人资产（未授权）不可作为参考图建项目
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                java.util.Map.of("referenceAssetIds", java.util.List.of("920103")), "ELEVATION"))
                .isInstanceOf(ServiceException.class);
        // 非ACCEPTED状态同样拒绝
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, 'user-references/r3', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'cccc', 'image/png', 10, 'PENDING')", 920104L, USER_A);
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                java.util.Map.of("referenceAssetIds", java.util.List.of("920104")), "ELEVATION"))
                .isInstanceOf(ServiceException.class);
    }

    /** 回归（2026-10-10 生产联调实测）：自传参考图的独立立面任务选图被误判旧版平面引用 403。
     *  顶层 assetIds 自 22 号文档 5b 起是「自传参考+存量平面+案例立面」混合清单；
     *  无 sourceFlat 且资产解析不到 FLAT 选择时 = 无平面谱系，应正常选图而非拒绝。 */
    @Test
    void selfUploadElevationWithDesignReferencesSelectable() {
        givePoints(USER_A, 500);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, 'user-references/reg1', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'aaaa', 'image/png', 10, 'ACCEPTED')", 920111L, USER_A);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                java.util.Map.of("referenceAssetIds", java.util.List.of("920111"), "floorCount", 2), "ELEVATION");
        var elev = projects.createElevationJob(USER_A, projectId, 1, "elev-selfref", null, confirmed("ELEVATION"));
        runElevation(elev.jobId(), 1);
        var candidates = projects.promoteCandidates(USER_A, projectId, elev.jobId());
        // 修复前：sourceFlatSelection 把 assetIds 当旧版平面引用 → RESOURCE_FORBIDDEN
        var version = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(version.version()).isEqualTo(1);
    }

    // ========== 2. 立面选择生成不可变最终版本；调整链生成新版本 ==========

    /** 锁定 22 号文档 5b 的顶层 assetIds 组合语义（解析侧回归 selfUploadElevationWithDesignReferencesSelectable 的另一半）：
     *  自传参考排最前；平面已选时并入存量平面资产且带 sourceFlat；谱系字段缺失即视为无平面来源。 */
    @Test
    void elevationSnapshotAssetIdsCompositionSemantics() throws Exception {
        givePoints(USER_A, 900);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, size_bytes, upload_status) "
                + "VALUES (?, 'user-references/comp1', ?, 'DESIGN_REFERENCE', 'USER_UPLOAD', 'aaaa', 'image/png', 10, 'ACCEPTED')", 920121L, USER_A);

        // a) 平面已选 + 自传参考：assetIds = [自传, 平面资产]（自传排最前），sourceFlat 在且资产一致
        long flatProj = projectWithFlatSelected();
        var elev2 = projects.createElevationJob(USER_A, flatProj, 1, "elev-comp-flat",
                java.util.Map.of("referenceAssetIds", java.util.List.of("920121")), confirmed("ELEVATION"));
        var snap2 = mapper.readValue(jdbc.queryForObject(
                "SELECT input_snapshot::text FROM ai_job WHERE id = ?", String.class, elev2.jobId()), java.util.Map.class);
        var assetIds2 = (java.util.List<?>) snap2.get("assetIds");
        var sourceFlat = (java.util.Map<?, ?>) snap2.get("sourceFlat");
        org.assertj.core.api.Assertions.assertThat(sourceFlat).isNotNull();
        org.assertj.core.api.Assertions.assertThat(assetIds2.indexOf("920121"))
                .as("自传参考必须排在存量平面资产之前").isLessThan(assetIds2.indexOf(String.valueOf(sourceFlat.get("assetId"))));
        // 混合参考任务照常出图晋升（修复后自传参考不阻断选择链）
        runElevation(elev2.jobId(), 1);
        var elev2Candidates = projects.promoteCandidates(USER_A, flatProj, elev2.jobId());
        projects.selectElevationCandidate(USER_A, flatProj, elev2Candidates.get(0).candidateId());

        assertThat(projects.listProjects(USER_A, null, 20).list().stream().filter(item -> item.projectId() == flatProj).findFirst().orElseThrow().hasDualImages()).isTrue();

        // b) SELF_UPLOAD 纯自传参考：assetIds = 自传参考，无 sourceFlat（跑完收口，不留可认领任务干扰后续断言）
        long selfProj = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                java.util.Map.of("referenceAssetIds", java.util.List.of("920121"), "floorCount", 2), "ELEVATION");
        var selfElev = projects.createElevationJob(USER_A, selfProj, 1, "elev-comp-self", null, confirmed("ELEVATION"));
        var snapSelf = mapper.readValue(jdbc.queryForObject(
                "SELECT input_snapshot::text FROM ai_job WHERE id = ?", String.class, selfElev.jobId()), java.util.Map.class);
        org.assertj.core.api.Assertions.assertThat((java.util.List<Object>) snapSelf.get("assetIds")).containsExactly("920121");
        org.assertj.core.api.Assertions.assertThat(snapSelf).doesNotContainKey("sourceFlat");
        runElevation(selfElev.jobId(), 1);
        var selfCandidates = projects.promoteCandidates(USER_A, selfProj, selfElev.jobId());
        projects.selectElevationCandidate(USER_A, selfProj, selfCandidates.get(0).candidateId());
    }

    @Test
    void elevationSelectionCreatesVersionAndRevisionCreatesNext() {
        long projectId = projectWithFlatSelected();

        // 立面任务 + 选择 → v1
        var elev1 = projects.createElevationJob(USER_A, projectId, 2, "elev-1",
                Map.of("styleCode", "MODERN", "roofType", "gable"), confirmed("ELEVATION"));
        runElevation(elev1.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, elev1.jobId());
        var v1 = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(v1.version()).isEqualTo(1);

        // 版本列表：1 条，未被取代
        var versions = projects.listResultVersions(USER_A, projectId);
        assertThat(versions).hasSize(1);

        // 调整请求 → 新立面任务 → 选择 → v2（旧版本只读保留）
        var revision = projects.createRevisionRequest(USER_A, projectId, "屋顶改双坡",
                Map.of("roofType", "hip"), 1, "elev-2", confirmed("ELEVATION"));
        runElevation(revision.newJobId(), 1);
        var allAfterRevision = projects.promoteCandidates(USER_A, projectId, revision.newJobId());
        var candidates2 = allAfterRevision.stream()
                .filter(c -> c.jobId() == revision.newJobId()).toList();
        var v2 = projects.selectElevationCandidate(USER_A, projectId, candidates2.get(0).candidateId());
        assertThat(v2.version()).isEqualTo(2);

        versions = projects.listResultVersions(USER_A, projectId);
        assertThat(versions).hasSize(2);
        // 旧版本内容只读：v1 行仍是 v1 的内容（快照未变）
        assertThat(jdbc.queryForObject(
                "SELECT version FROM design_result_version WHERE id = ?", Long.class, v1.versionId()))
                .isEqualTo(1);
        // 调整请求完成
        assertThat(jdbc.queryForObject(
                "SELECT status FROM design_revision_request WHERE id = ?",
                String.class, revision.requestId())).isEqualTo("COMPLETED");
        // 新版本活跃，旧版本 superseded
        assertThat(jdbc.queryForObject(
                "SELECT superseded FROM design_result_version WHERE id = ?", Boolean.class, v1.versionId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT superseded FROM design_result_version WHERE id = ?", Boolean.class, v2.versionId()))
                .isFalse();
    }

    // ========== 3. 重复立面选择幂等 ==========

    @Test
    void repeatedElevationSelectionIsIdempotent() {
        long projectId = projectWithFlatSelected();
        var elev1 = projects.createElevationJob(USER_A, projectId, 2, "elev-i1", null, confirmed("ELEVATION"));
        runElevation(elev1.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, elev1.jobId());

        var first = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        var again = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(again.versionId()).isEqualTo(first.versionId());
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(projects.listResultVersions(USER_A, projectId)).hasSize(1);
    }

    // ========== 4. BOLA：B 不可读/操作 A 的项目版本 ==========

    @Test
    void crossUserResultAccessRejected() {
        long projectId = projectWithFlatSelected();
        long USER_B = 4402L;
        assertThatThrownBy(() -> projects.listResultVersions(USER_B, projectId))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.createElevationJob(USER_B, projectId, 1, "elev-b", null, confirmed("ELEVATION")))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 5. 无最终版本时调整请求拒绝 ==========

    @Test
    void revisionRequiresExistingVersion() {
        long projectId = projectWithFlatSelected();
        assertThatThrownBy(() -> projects.createRevisionRequest(USER_A, projectId, "改屋顶",
                null, 1, "rev-x", confirmed("ELEVATION")))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_000));
    }

    @Test
    void recoveredVersionAssetsUseFrozenSelectionsAndRemainOwnerScoped() {
        long projectId = projectWithFlatSelected();
        var firstJob = projects.createElevationJob(USER_A, projectId, 1, "recover-one", null, confirmed("ELEVATION"));
        runElevation(firstJob.jobId(), 1);
        var firstCandidate = projects.promoteCandidates(USER_A, projectId, firstJob.jobId()).stream()
                .filter(c -> c.jobId() == firstJob.jobId()).findFirst().orElseThrow();
        var firstVersion = projects.selectElevationCandidate(USER_A, projectId, firstCandidate.candidateId());
        Long flatAsset = jdbc.queryForObject("SELECT c.asset_id FROM design_selection s JOIN design_candidate c "
                + "ON c.id = s.candidate_id WHERE s.project_id = ? AND s.stage = 'FLAT' AND s.deleted = FALSE", Long.class, projectId);
        var second = projects.createRevisionRequest(USER_A, projectId, "改立面", null, 1, "recover-two", confirmed("ELEVATION"));
        runElevation(second.newJobId(), 1);
        var nextCandidate = projects.promoteCandidates(USER_A, projectId, second.newJobId()).stream()
                .filter(c -> c.jobId() == second.newJobId()).findFirst().orElseThrow();
        projects.selectElevationCandidate(USER_A, projectId, nextCandidate.candidateId());
        var rows = projects.listResultVersions(USER_A, projectId);
        var old = rows.stream().filter(r -> ((Number) r.get("id")).longValue() == firstVersion.versionId()).findFirst().orElseThrow();
        assertThat(old.get("selected_flat_asset_id")).isEqualTo(flatAsset);
        assertThat(old.get("selected_elevation_asset_id")).isEqualTo(firstCandidate.assetId());
        assertThat(rows.get(0).get("selected_elevation_asset_id")).isEqualTo(nextCandidate.assetId());
        assertThatThrownBy(() -> projects.listResultVersions(9999, projectId)).isInstanceOf(ServiceException.class);
    }

    private static class FakeQuarantineStorage implements QuarantineObjectPort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        void clear() {
            objects.clear();
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
}
