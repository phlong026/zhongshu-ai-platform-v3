package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController;
import cn.iocoder.yudao.module.design.controller.app.vo.AppSelectionCreateReqVO;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2 合同测试（真实 PostgreSQL 全真实链）：平面选定响应必须区分「纯 FLAT 正式交付」与「历史 ELEVATION 选择成功」。
 * 历史"平面后接立面"项目（stage=ELEVATION，旧选择与旧结果版本都在）重新选定平面候选时：
 * 选择必须成功保存（轮换语义），响应不得把旧结果版本冒充本次交付（resultVersionId 必须为空），
 * 不得创建新的平面交付版本、不得扣点、不得新建收费任务；重复确认幂等；跨 owner 拒绝。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlatSelectionR2ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 3401L;
    private static final long USER_B = 3402L;

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private FakeQuarantineStorage quarantine;
    private AppDesignProjectController controller;

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
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "r2-assets-" + System.nanoTime())
                        .toString());
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights,
                org.mockito.Mockito.mock(cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort.class));
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine,
                org.mockito.Mockito.mock(cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort.class));

        controller = new AppDesignProjectController();
        ReflectionTestUtils.setField(controller, "designProjectService", projects);
        IdentitySessionPort identities = token -> Optional.of(new IdentitySessionPort.SessionContext(
                token != null && token.endsWith("user-b") ? USER_B : USER_A, "test", "test", false));
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_candidate, "
                + "design_selection, design_result_version, design_revision_request, "
                + "ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, ai_job_settlement, "
                + "ai_task_charge, generation_price_rule, design_point_account, design_point_ledger, outbox_event");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9301, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9302, 'ELEVATION', 15, 1, 4, now() - interval '1 minute')");
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
        var points = new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(
                new cn.iocoder.yudao.module.commerce.points.PointAccountService(
                        objectDataSource(), new DataSourceTransactionManager(objectDataSource())));
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

    private void runJob(long jobId, int validCount) {
        var job = orchestration.claim("worker-r2", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("r2-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    private cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmed(String stage) {
        var rows = jdbc.queryForList("SELECT id,version FROM generation_price_rule WHERE stage=? ORDER BY effective_at DESC,id DESC LIMIT 1", stage);
        var row = rows.get(0);
        return new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation(String.valueOf(row.get("id")), ((Number) row.get("version")).longValue());
    }

    /** 历史"平面后接立面"项目：旧平面选择 + 立面任务 + 立面结果版本，stage 已翻转为 ELEVATION。返回旧平面任务的两个候选。 */
    private record HistoricalProject(long projectId, long flatJobId, List<DesignProjectService.CandidateRow> flatCandidates) {
    }

    private HistoricalProject historicalElevationProject(long owner) {
        givePoints(owner, 500);
        long projectId = projects.createProject(owner, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(owner, projectId, 2, "hist-flat-" + projectId, confirmed("FLAT"));
        runJob(flat.jobId(), 2);
        var flatCandidates = projects.promoteCandidates(owner, projectId, flat.jobId());
        projects.selectFlatCandidate(owner, projectId, flatCandidates.get(0).candidateId());
        var elev = projects.createElevationJob(owner, projectId, 1, "hist-elev-" + projectId, null, confirmed("ELEVATION"));
        runJob(elev.jobId(), 1);
        var elevCandidates = projects.promoteCandidates(owner, projectId, elev.jobId());
        projects.selectElevationCandidate(owner, projectId, elevCandidates.get(0).candidateId());
        assertThat(projects.getProject(projectId).orElseThrow().stage()).isEqualTo("ELEVATION");
        return new HistoricalProject(projectId, flat.jobId(), flatCandidates);
    }

    private cn.iocoder.yudao.module.design.controller.app.vo.AppDesignProjectRespVO selectFlatViaController(
            long projectId, long jobId, long candidateId, String bearer) {
        var request = new AppSelectionCreateReqVO();
        request.setJobId(String.valueOf(jobId));
        request.setCandidateId(String.valueOf(candidateId));
        return controller.selectFlat(String.valueOf(projectId), request, bearer).getData();
    }

    @Test
    void historicalElevationSelectionSavesWithoutNewDeliveryAndNeverExposesOldVersion() {
        HistoricalProject hist = historicalElevationProject(USER_A);
        long candidateY = hist.flatCandidates().get(1).candidateId();
        long versionsBefore = jdbc.queryForObject("SELECT count(*) FROM design_result_version", Long.class);
        long ledgerBefore = jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class);
        long jobsBefore = jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class);
        long oldVersionId = jdbc.queryForObject(
                "SELECT id FROM design_result_version WHERE project_id=?", Long.class, hist.projectId());

        var vo = selectFlatViaController(hist.projectId(), hist.flatJobId(), candidateY, "Bearer owner");

        // 选择成功必须显式可达：不能因为没有新平面版本就只剩 HTTP 200 + 空字段（前端无从区分成败）
        assertThat(vo.getSelectionSaved()).isTrue();
        assertThat(vo.getFlatDeliveryCreated()).isFalse();
        // 不得把旧结果版本冒充本次交付：历史立面项目的已有版本不得作为 resultVersionId 下发
        assertThat(vo.getResultVersionId()).isNull();
        // 旧行为复现证据（修复前）：resultVersionId 会带出旧版本 id，且新字段不存在
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_result_version", Long.class)).isEqualTo(versionsBefore);
        // 轮换语义保持：新候选成为唯一 active 平面选择
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_selection WHERE project_id=? AND stage='FLAT' AND active=TRUE AND deleted=FALSE",
                Long.class, hist.projectId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT c.id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id "
                        + "WHERE s.project_id=? AND s.stage='FLAT' AND s.active=TRUE AND s.deleted=FALSE",
                Long.class, hist.projectId())).isEqualTo(candidateY);
        // 不创建新交付版本、不扣点、不新建收费任务
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger", Long.class)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isEqualTo(jobsBefore);
        // 旧结果版本原样保留（未被覆盖/失效）
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_result_version WHERE id=? AND deleted=FALSE",
                Long.class, oldVersionId)).isEqualTo(1L);
    }

    @Test
    void historicalRepeatedConfirmationIsIdempotentAndStable() {
        HistoricalProject hist = historicalElevationProject(USER_A);
        long candidateY = hist.flatCandidates().get(1).candidateId();
        var first = selectFlatViaController(hist.projectId(), hist.flatJobId(), candidateY, "Bearer owner");
        long selectionsAfterFirst = jdbc.queryForObject("SELECT count(*) FROM design_selection", Long.class);

        var second = selectFlatViaController(hist.projectId(), hist.flatJobId(), candidateY, "Bearer owner");

        assertThat(second.getSelectionSaved()).isTrue();
        assertThat(second.getFlatDeliveryCreated()).isFalse();
        assertThat(second.getResultVersionId()).isNull();
        // 同候选重复确认：选择行不新增（幂等返回既有选择），无新版本、无扣点
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_selection", Long.class)).isEqualTo(selectionsAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_selection WHERE project_id=? AND stage='FLAT' AND active=TRUE AND deleted=FALSE",
                Long.class, hist.projectId())).isEqualTo(1L);
    }

    @Test
    void historicalFlatSelectionRejectsCrossOwner() {
        HistoricalProject hist = historicalElevationProject(USER_A);
        long candidateY = hist.flatCandidates().get(1).candidateId();
        long selectionsBefore = jdbc.queryForObject("SELECT count(*) FROM design_selection", Long.class);

        assertThatThrownBy(() -> selectFlatViaController(hist.projectId(), hist.flatJobId(), candidateY, "Bearer user-b"))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_selection", Long.class)).isEqualTo(selectionsBefore);
    }

    @Test
    void serviceContractHistoricalSelectionStillCreatesNoFlatDeliveryVersion() {
        HistoricalProject hist = historicalElevationProject(USER_A);
        long candidateY = hist.flatCandidates().get(1).candidateId();
        // 服务层既有语义锁定：历史立面线路的平面选择不创建平面交付版本（控制器据此映射 flatDeliveryCreated=false）
        assertThat(projects.confirmFlatCandidate(USER_A, hist.projectId(), candidateY)).isNull();
    }

    @Test
    void pureFlatConfirmationBindsThisConfirmationWithExplicitContract() {
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(USER_A, projectId, 2, "pure-flat", confirmed("FLAT"));
        runJob(flat.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, flat.jobId());

        var first = selectFlatViaController(projectId, flat.jobId(), candidates.get(0).candidateId(), "Bearer owner");
        assertThat(first.getSelectionSaved()).isTrue();
        assertThat(first.getFlatDeliveryCreated()).isTrue();
        long firstVersionId = jdbc.queryForObject(
                "SELECT id FROM design_result_version WHERE project_id=? AND result_line='FLAT'", Long.class, projectId);
        assertThat(first.getResultVersionId()).isEqualTo(String.valueOf(firstVersionId));

        var second = selectFlatViaController(projectId, flat.jobId(), candidates.get(1).candidateId(), "Bearer owner");
        long secondVersionId = jdbc.queryForObject(
                "SELECT id FROM design_result_version WHERE project_id=? AND result_line='FLAT' AND superseded=FALSE", Long.class, projectId);
        // 必须绑定"这次确认"的不可变版本，而不是项目最新版本恰好相同时的巧合
        assertThat(second.getResultVersionId()).isEqualTo(String.valueOf(secondVersionId));
        assertThat(second.getResultVersionId()).isNotEqualTo(first.getResultVersionId());
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
}
