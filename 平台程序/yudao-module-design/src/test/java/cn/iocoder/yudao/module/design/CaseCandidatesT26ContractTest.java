package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.catalog.CaseGenerationCandidateService;
import cn.iocoder.yudao.module.design.catalog.CaseMatchProperties;
import cn.iocoder.yudao.module.design.controller.app.AppCaseController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.GENERATION_REFERENCE_NOT_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Disposable PostgreSQL: actual filtering, immutable versions, replay, migration and controller binding. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseCandidatesT26ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");

    private SimpleDriverDataSource ds;
    private JdbcTemplate jdbc;
    private CaseCatalogService catalog;
    private CaseGenerationCandidateService candidates;
    private CaseMatchProperties policy;
    private DesignProjectService projects;
    private AiJobPort jobs;
    private AppCaseController controller;

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design",
                "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        catalog = new CaseCatalogService(ds, manager);
        policy = new CaseMatchProperties();
        candidates = new CaseGenerationCandidateService(ds, manager, policy);
        jobs = mock(AiJobPort.class);
        projects = new DesignProjectService(ds, manager, jobs, null, null, null,
                mock(cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort.class));
        controller = new AppCaseController();
        ReflectionTestUtils.setField(controller, "caseCatalogService", catalog);
        ReflectionTestUtils.setField(controller, "caseGenerationCandidateService", candidates);
        IdentitySessionPort identities = token -> switch (token == null ? "" : token) {
            case "active" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "test", false));
            case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(2, "test", "restricted", true));
            default -> Optional.empty();
        };
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE design_project_request, design_project, design_requirement_snapshot, design_case, "
                + "design_case_version, design_case_asset, case_publication, case_favorite, asset, asset_rights_grant CASCADE");
        policy.setExactDimensionsEnabled(false);
        policy.setDimensionToleranceMeters(null);
        policy.setStrictFloorCount(false);
        policy.setPolicyVersion("T26-07-v1");
        reset(jobs);
    }

    private long createCase(int floors, String width, String depth, String basis, boolean generationGrant, Integer... planFloors) {
        long id = catalog.createCompanyCase(501, "真实案例", null, "MODERN", floors, 200,
                width == null ? null : new BigDecimal(width), depth == null ? null : new BigDecimal(depth),
                null, null, basis);
        long version = version(id);
        addAsset(version, "COVER", null, false);
        for (Integer floor : planFloors) addAsset(version, "FLOOR_PLAN", floor, generationGrant);
        if (planFloors.length > 0) catalog.publish(id, "fixture");
        return id;
    }

    private long addAsset(long version, String role, Integer floorNo, boolean generationGrant) {
        long asset = IdWorker.getId();
        jdbc.update("INSERT INTO asset(id, object_key, owner_user_id, asset_type, source_type, sha256, declared_mime, "
                        + "size_bytes, upload_status, security_scan_status, moderation_status) "
                        + "VALUES(?, ?, 501, 'CASE_IMAGE', 'COMPANY', 'fixture', 'image/png', 10, 'ACCEPTED', 'PASSED', 'PASSED')",
                asset, "t26-case/" + asset);
        jdbc.update("INSERT INTO design_case_asset(id, case_version_id, asset_id, asset_role, floor_no) VALUES(?,?,?,?,?)",
                IdWorker.getId(), version, asset, role, floorNo);
        grant(asset, "PUBLIC_DISPLAY");
        if (generationGrant) grant(asset, "GENERATION_REFERENCE");
        return asset;
    }

    private long grant(long asset, String scope) {
        long id = IdWorker.getId();
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                + "VALUES(?,?,501,'test',?,now()-interval '1 minute')", id, asset, scope);
        return id;
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id = ?", Long.class, id);
    }

    private List<String> ids() {
        return candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "UNKNOWN")
                .candidates().stream().map(CaseGenerationCandidateService.Candidate::caseId).toList();
    }

    private void moveReferenceOutsideBusinessDomain(long caseId, String boundary) {
        long versionId = version(caseId);
        // These clauses are fixed fixture choices; the production API never accepts a tenant selector.
        switch (boundary) {
            case "case" -> jdbc.update("UPDATE design_case SET tenant_id=7 WHERE id=?", caseId);
            case "version" -> jdbc.update("UPDATE design_case_version SET tenant_id=7 WHERE id=?", versionId);
            case "relation" -> jdbc.update("UPDATE design_case_asset SET tenant_id=7 WHERE case_version_id=? AND floor_no=2", versionId);
            case "asset" -> jdbc.update("UPDATE asset SET tenant_id=7 WHERE id IN (SELECT asset_id FROM design_case_asset "
                    + "WHERE case_version_id=? AND floor_no=2)", versionId);
            case "generationGrant", "displayGrant" -> jdbc.update("UPDATE asset_rights_grant SET tenant_id=7 WHERE scope=? "
                    + "AND asset_id IN (SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND asset_role='FLOOR_PLAN')",
                    "generationGrant".equals(boundary) ? "GENERATION_REFERENCE" : "PUBLIC_DISPLAY", versionId);
            default -> throw new IllegalArgumentException("Unknown fixture boundary");
        }
    }

    private void edit(long id) {
        var current = catalog.getAdminCase(id).orElseThrow();
        catalog.updateCase(id, 501, current.version(), "修改后的案例", current.description(), current.styleCode(),
                current.floorCount(), current.buildingArea(), current.faceWidth(), current.depth(), current.rooms(), current.tags());
    }

    @Test
    void authorizationIsAppliedBeforeLimitWithZeroOneFiveAndMoreThanFiveRealResults() {
        assertThat(ids()).isEmpty();
        // More than the former recommendation scan window: inaccessible rows must not hide later valid rows.
        for (int i = 0; i < 51; i++) createCase(2, "12", "14", "UNKNOWN", false, 1);
        assertThat(ids()).isEmpty();
        var real = new ArrayList<String>();
        real.add(String.valueOf(createCase(2, "12", "14", "UNKNOWN", true, 1)));
        assertThat(ids()).containsExactlyElementsOf(real);
        for (int i = 0; i < 4; i++) real.add(String.valueOf(createCase(2, "12", "14", "UNKNOWN", true, 1)));
        assertThat(ids()).containsExactlyElementsOf(real);
        for (int i = 0; i < 3; i++) createCase(2, "12", "14", "UNKNOWN", true, 1);
        assertThat(ids()).containsExactlyElementsOf(real);
        assertThat(ids()).containsExactlyElementsOf(real); // deterministic repeat, no duplicate version rows
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project", Long.class)).isZero();
        verifyNoInteractions(jobs);
    }

    @Test
    void onlyPublishedCompanyCurrentVersionsWithEverySafeAuthorizedPlanAreCandidates() {
        long valid = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long draft = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long offline = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long deleted = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long ai = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long unsafe = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long partial = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long noPlans = createCase(2, "12", "14", "UNKNOWN", true);
        jdbc.update("UPDATE design_case SET publication_status='DRAFT' WHERE id=?", draft);
        catalog.offline(offline, "fixture", "fixture");
        jdbc.update("UPDATE design_case SET deleted=TRUE WHERE id=?", deleted);
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", ai);
        jdbc.update("UPDATE design_case SET publication_status='PUBLISHED' WHERE id=?", noPlans);
        jdbc.update("UPDATE asset SET security_scan_status='REJECTED' WHERE id IN "
                + "(SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND asset_role='FLOOR_PLAN')", version(unsafe));
        addAsset(version(partial), "FLOOR_PLAN", 2, false);
        assertThat(ids()).containsExactly(String.valueOf(valid));
        jdbc.update("UPDATE design_case_version SET deleted=TRUE WHERE id=?", version(valid));
        assertThat(ids()).isEmpty();
    }

    @Test
    void fixedBusinessDomainRejectsForeignCaseVersionRelationAssetAndGrantsBeforeLimitAndPreview() {
        long visible = createCase(2, "12", "14", "UNKNOWN", true, 1, 2);
        for (String boundary : List.of("case", "version", "relation", "asset", "generationGrant", "displayGrant")) {
            long other = createCase(2, "12", "14", "UNKNOWN", true, 1, 2);
            long selected = version(other);
            moveReferenceOutsideBusinessDomain(other, boundary);
            assertThat(ids()).as(boundary).containsExactly(String.valueOf(visible));
            forbidden(() -> candidates.requireCandidate(other, selected));
        }
        assertThat(candidates.requireCandidate(visible, version(visible)).floorPlans()).hasSize(2);
        verifyNoInteractions(jobs);
    }

    @Test
    void foreignCoverRelationAssetOrDisplayGrantIsNeverReturnedWithAnOtherwiseValidCandidate() {
        for (String boundary : List.of("relation", "asset", "grant")) {
            long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
            long selected = version(id);
            long cover = jdbc.queryForObject("SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND asset_role='COVER'", Long.class, selected);
            assertThat(candidates.requireCandidate(id, selected).coverAssetId()).isEqualTo(String.valueOf(cover));
            switch (boundary) {
                case "relation" -> jdbc.update("UPDATE design_case_asset SET tenant_id=7 WHERE case_version_id=? AND asset_role='COVER'", selected);
                case "asset" -> jdbc.update("UPDATE asset SET tenant_id=7 WHERE id=?", cover);
                case "grant" -> jdbc.update("UPDATE asset_rights_grant SET tenant_id=7 WHERE asset_id=? AND scope='PUBLIC_DISPLAY'", cover);
            }
            assertThat(candidates.requireCandidate(id, selected).coverAssetId()).as(boundary).isNull();
            assertThat(candidates.requireCandidate(id, selected).floorPlans()).hasSize(1);
        }
    }

    @Test
    void versionedFirstCreateRejectsEveryForeignBusinessBoundaryWithoutWritingAProjectReceipt() {
        for (String boundary : List.of("case", "version", "relation", "asset", "generationGrant")) {
            long id = createCase(2, "12", "14", "UNKNOWN", true, 1, 2);
            long selected = version(id);
            moveReferenceOutsideBusinessDomain(id, boundary);
            assertThatThrownBy(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null,
                    Map.of("floorCount", 2), "foreign-domain-" + boundary, "FLAT", selected))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(GENERATION_REFERENCE_NOT_AUTHORIZED.getCode()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project_request", Long.class)).isZero();
        verifyNoInteractions(jobs);
    }

    @Test
    void existingAiCaseLibraryEntryCanStillBindItsAuthorizedVersionInsideBusinessDomainZero() {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long selected = version(id);
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", id);
        assertThat(ids()).isEmpty(); // The separate five-candidate product scope is still COMPANY.
        long project = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null,
                Map.of("floorCount", 2), "authorized-library-ai", "FLAT", selected);
        assertThat(jdbc.queryForObject("SELECT ref_version_id FROM design_project WHERE id=?", Long.class, project)).isEqualTo(selected);
        verifyNoInteractions(jobs);
    }

    @Test
    void selectedVersionWithValidGenerationRightsDoesNotRequireExpiredIndependentDisplayRights() {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long selected = version(id);
        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()-interval '1 second' "
                + "WHERE scope='PUBLIC_DISPLAY' AND asset_id IN (SELECT asset_id FROM design_case_asset WHERE case_version_id=?)", selected);
        assertThat(ids()).isEmpty();
        forbidden(() -> candidates.requireCandidate(id, selected));
        long project = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null,
                Map.of("floorCount", 2), "independent-display-rights", "FLAT", selected);
        assertThat(jdbc.queryForObject("SELECT ref_version_id FROM design_project WHERE id=?", Long.class, project)).isEqualTo(selected);
        verifyNoInteractions(jobs);
    }

    @Test
    void versionedFirstCreateCannotAttachForeignDomainSketchWithTheSameNumericOwner() {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long sketch = IdWorker.getId();
        jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes,"
                        + "upload_status,security_scan_status,moderation_status,tenant_id) "
                        + "VALUES(?, ?, 1, 'USER_SKETCH', 'USER_UPLOAD', 'fixture', 'image/png', 10, 'ACCEPTED', 'PASSED', 'PASSED', 7)",
                sketch, "foreign-sketch/" + sketch);
        forbidden(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, sketch,
                Map.of("floorCount", 2), "foreign-sketch", "FLAT", version(id)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project_request", Long.class)).isZero();
        verifyNoInteractions(jobs);
    }

    @Test
    void laterFlatJobIsPureTextButProjectReceiptStillReplays() {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long selected = version(id);
        long plan = jdbc.queryForObject("SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND asset_role='FLOOR_PLAN'", Long.class, selected);
        long ownGrant = jdbc.queryForObject("SELECT id FROM asset_rights_grant WHERE asset_id=? AND scope='GENERATION_REFERENCE'", Long.class, plan);
        long foreignGrant = grant(plan, "GENERATION_REFERENCE");
        jdbc.update("UPDATE asset_rights_grant SET tenant_id=7 WHERE id=?", foreignGrant);
        Map<String, Object> inputs = Map.of("floorCount", 2);
        long project = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "domain-receipt", "FLAT", selected);
        assertThat(jdbc.queryForObject("SELECT rights_snapshot->0->>'grantId' FROM design_project WHERE id=?", String.class, project))
                .isEqualTo(String.valueOf(ownGrant));

        // 平面已改为纯文生图：授权撤回后新 FLAT 任务仍可创建，但不得再冻结参考资产。
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN' WHERE id=?", ownGrant);
        when(jobs.freezeInput(anyLong(), anyMap())).thenReturn(true);
        // 首建后的第一条 FLAT 任务复用项目创建键，和真实小程序的 initialGenerationKey 一致。
        assertThat(projects.createFlatJob(1, project, 1, "domain-receipt")).isNotNull();
        verify(jobs).createFlatJob(eq(1L), eq(1), eq("domain-receipt"), eq(String.valueOf(project)), any(), any());
        var snapshot = ArgumentCaptor.forClass(Map.class);
        verify(jobs).freezeInput(anyLong(), snapshot.capture());
        assertThat((List<?>) snapshot.getValue().get("assetIds")).isEmpty();
        assertThat(snapshot.getValue()).doesNotContainKeys("references", "referenceRights");

        assertThat(projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "domain-receipt", "FLAT", selected)).isEqualTo(project);
        jdbc.update("UPDATE design_case SET tenant_id=7 WHERE id=?", id);
        assertThat(projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "domain-receipt", "FLAT", selected)).isEqualTo(project);
        assertThatThrownBy(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "foreign-new-receipt", "FLAT", selected))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(GENERATION_REFERENCE_NOT_AUTHORIZED.getCode()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project_request", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isZero();
    }

    @Test
    void withdrawnExpiredFutureOrDisplayRevokedGrantsAreExcludedAndPreviewRechecks() {
        for (String change : List.of("status='WITHDRAWN'", "expires_at=now()-interval '1 second'",
                "effective_at=now()+interval '1 day'")) {
            long id = createCase(2, "12", "14", "UNKNOWN", true, 1, 3);
            long selected = version(id);
            assertThat(candidates.requireCandidate(id, selected).floorPlans()).hasSize(2);
            Long asset = jdbc.queryForObject("SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND floor_no=3", Long.class, selected);
            // change is a fixed test-owned SQL clause, never a request parameter.
            jdbc.update("UPDATE asset_rights_grant SET " + change + " WHERE asset_id=? AND scope='GENERATION_REFERENCE'", asset);
            assertThat(ids()).doesNotContain(String.valueOf(id));
            forbidden(() -> candidates.requireCandidate(id, selected));
        }
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN' WHERE scope='PUBLIC_DISPLAY' AND asset_id IN "
                + "(SELECT asset_id FROM design_case_asset WHERE case_version_id=? AND asset_role='FLOOR_PLAN')", version(id));
        assertThat(ids()).doesNotContain(String.valueOf(id));
    }

    @Test
    void versionPreviewPreservesActualFloorNumbersAndRejectsSilentlySwitchingToLatest() {
        long id = createCase(3, null, null, "UNKNOWN", true, 3, null, 1);
        long selected = version(id);
        var preview = candidates.requireCandidate(id, selected);
        assertThat(preview.caseVersionId()).isEqualTo(String.valueOf(selected));
        assertThat(preview.floorPlans()).extracting(CaseCatalogService.FloorPlan::floorNo).containsExactly(null, 1, 3);
        assertThat(preview.faceWidth()).isNull();
        assertThat(preview.depth()).isNull();
        edit(id);
        versionConflict(() -> candidates.requireCandidate(id, selected));
        assertThat(candidates.requireCandidate(id, version(id)).title()).isEqualTo("修改后的案例");
    }

    @Test
    void unknownBasisAndUnconfirmedToleranceNeverClaimPreciseDimensions() {
        createCase(2, "8", "9", "UNKNOWN", true, 1);
        policy.setExactDimensionsEnabled(true);
        var unknown = candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "UNKNOWN");
        assertThat(unknown.matchMode()).isEqualTo("REFERENCE_ONLY");
        assertThat(unknown.candidates()).hasSize(1);
        var noTolerance = candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "BUILDING_OUTLINE");
        assertThat(noTolerance.matchMode()).isEqualTo("REFERENCE_ONLY");
        policy.setDimensionToleranceMeters(new BigDecimal("-1"));
        assertThat(candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "BUILDING_OUTLINE").matchMode()).isEqualTo("REFERENCE_ONLY");
        policy.setDimensionToleranceMeters(new BigDecimal("0.5"));
        assertThat(candidates.find(2, null, new BigDecimal("14"), "BUILDING_OUTLINE").matchMode()).isEqualTo("REFERENCE_ONLY");
        assertThat(candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "UNKNOWN").matchMode()).isEqualTo("REFERENCE_ONLY");
    }

    @Test
    void confirmedMatchingUsesSameBasisAndExplicitToleranceWithoutWideningAnEmptyResult() {
        createCase(2, "12", "14", "UNKNOWN", true, 1);
        createCase(2, "12", "14", "SITE", true, 1);
        long exact = createCase(2, "12", "14", "BUILDING_OUTLINE", true, 1);
        long edge = createCase(2, "12.5", "14.5", "BUILDING_OUTLINE", true, 1);
        createCase(2, "12.6", "14", "BUILDING_OUTLINE", true, 1);
        policy.setExactDimensionsEnabled(true); policy.setDimensionToleranceMeters(new BigDecimal("0.5"));
        var result = candidates.find(2, new BigDecimal("12"), new BigDecimal("14"), "BUILDING_OUTLINE");
        assertThat(result.matchMode()).isEqualTo("DIMENSION_MATCH");
        assertThat(result.candidates()).extracting(CaseGenerationCandidateService.Candidate::caseId)
                .containsExactly(String.valueOf(exact), String.valueOf(edge));
        var empty = candidates.find(2, new BigDecimal("30"), new BigDecimal("30"), "BUILDING_OUTLINE");
        assertThat(empty.matchMode()).isEqualTo("DIMENSION_MATCH");
        assertThat(empty.candidates()).isEmpty();
    }

    @Test
    void floorPolicyIsExplicitAndOrderingRemainsStable() {
        long other = createCase(3, "12", "14", "UNKNOWN", true, 1);
        long same = createCase(2, "12", "14", "UNKNOWN", true, 1);
        assertThat(ids()).containsExactly(String.valueOf(same), String.valueOf(other));
        assertThat(candidates.find(2, null, null, "UNKNOWN").strictFloorCount()).isFalse();
        policy.setStrictFloorCount(true);
        assertThat(ids()).containsExactly(String.valueOf(same));
        assertThat(candidates.find(2, null, null, "UNKNOWN").strictFloorCount()).isTrue();
    }

    @Test
    void invalidDimensionsAndBasisAreRejectedWithoutRunningAnUnboundedQuery() {
        for (String value : List.of("2.9", "40.1", "1e2147483647", "1e-2147483647", "12.1234567")) {
            assertThatThrownBy(() -> candidates.find(2, new BigDecimal(value), null, "UNKNOWN")).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> candidates.find(0, null, null, "UNKNOWN")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> candidates.find(2, null, null, "guess")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> candidates.requireCandidate(-1, 2)).isInstanceOf(ServiceException.class);
    }

    @Test
    void newProjectFreezesSelectedVersionAndInputsAndReplaysItAfterCaseEdit() {
        long id = createCase(3, "12", "14", "UNKNOWN", true, 1);
        long selected = version(id);
        Map<String, Object> inputs = Map.of("faceWidthM", "18.2", "depthM", "20.6", "floorCount", 2, "note", "保留庭院");
        long project = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "selected-version", "FLAT", selected);
        assertThat(jdbc.queryForObject("SELECT ref_version_id FROM design_project WHERE id=?", Long.class, project)).isEqualTo(selected);
        Map<String, Object> saved = BudgetInputs.readJson(jdbc.queryForObject(
                "SELECT inputs::text FROM design_requirement_snapshot WHERE project_id=?", String.class, project));
        assertThat(saved).containsEntry("note", "保留庭院").containsEntry("floorCount", 2);
        assertThat(new BigDecimal(saved.get("faceWidthM").toString())).isEqualByComparingTo("18.2");
        edit(id);
        assertThat(projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "selected-version", "FLAT", selected)).isEqualTo(project);
        versionConflict(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "new-attempt", "FLAT", selected));
        assertThatThrownBy(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, inputs, "selected-version", "FLAT", version(id)))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project", Long.class)).isEqualTo(1);
        verifyNoInteractions(jobs);
    }

    @Test
    void revocationOrForeignVersionOnFirstCreateLeavesNoProjectOrRequestReceipt() {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long another = createCase(2, "12", "14", "UNKNOWN", true, 1);
        long selected = version(id);
        versionConflict(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, Map.of("floorCount", 2), "foreign", "FLAT", version(another)));
        jdbc.update("UPDATE asset SET deleted=TRUE WHERE id IN (SELECT asset_id FROM design_case_asset "
                + "WHERE case_version_id=? AND asset_role='FLOOR_PLAN')", version(another));
        assertThatThrownBy(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", another, null, Map.of("floorCount", 2), "missing-asset", "FLAT", version(another)))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(GENERATION_REFERENCE_NOT_AUTHORIZED.getCode()));
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN' WHERE scope='GENERATION_REFERENCE'");
        assertThatThrownBy(() -> projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, Map.of("floorCount", 2), "withdrawn", "FLAT", selected))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(GENERATION_REFERENCE_NOT_AUTHORIZED.getCode()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project_request", Long.class)).isZero();
        verifyNoInteractions(jobs);
    }

    @Test
    void legacyFingerprintAndDimensionDefaultsStayCompatibleWhileVersionedInputsNeverGuessDimensions() throws Exception {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        Map<String, Object> input = Map.of("floorCount", 2);
        long legacy = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, input, "legacy", "FLAT");
        String priorCanonicalIntent = "{\"caseId\":" + id + ",\"inputs\":{\"floorCount\":2},\"line\":\"FLAT\",\"sketchId\":null,\"source\":\"CASE_REFERENCE\"}";
        String priorHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(priorCanonicalIntent.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT request_hash FROM design_project_request WHERE user_id=1 AND request_key='legacy'", String.class)).isEqualTo(priorHash);
        assertThat(projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, input, "legacy", "FLAT", null)).isEqualTo(legacy);
        long versioned = projects.createProjectWithKey(1, "CASE_REFERENCE", id, null, input, "versioned", "FLAT", version(id));
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(inputs, 'faceWidthM') FROM design_requirement_snapshot WHERE project_id=?", Boolean.class, legacy)).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(inputs, 'faceWidthM') FROM design_requirement_snapshot WHERE project_id=?", Boolean.class, versioned)).isFalse();
    }

    @Test
    void anonymousAndRestrictedSessionsCannotSearchOrPreviewAndHttpBindingKeepsIdsAsStrings() throws Exception {
        long id = createCase(2, "12", "14", "UNKNOWN", true, 1);
        assertThatThrownBy(() -> controller.getGenerationCandidates(2, null, null, "UNKNOWN", null))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(401));
        assertThatThrownBy(() -> controller.getGenerationCandidate(id, version(id), "Bearer restricted"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(IdentitySessionPort.ACCESS_GRANT_REQUIRED));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper())).build();
        mvc.perform(get("/design/v1/cases/generation-candidates").header("Authorization", "Bearer active")
                        .param("floorCount", "2").param("faceWidth", "12.4").param("dimensionBasis", "UNKNOWN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates[0].caseId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.candidates[0].caseVersionId").value(String.valueOf(version(id))))
                .andExpect(jsonPath("$.data.matchMode").value("REFERENCE_ONLY"));
        mvc.perform(get("/design/v1/cases/" + id + "/generation-candidate").header("Authorization", "Bearer active")
                        .param("caseVersionId", String.valueOf(version(id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.floorPlans[0].floorNo").value(1));
        verifyNoInteractions(jobs);
    }

    @Test
    void recordingBasisCreatesAnImmutableVersionAndLegacyEditsPreserveItsMeaning() {
        long id = createCase(2, "12.6", "13.8", null, true, 1);
        long old = version(id);
        var current = catalog.getAdminCase(id).orElseThrow();
        assertThat(current.dimensionBasis()).isEqualTo("UNKNOWN");
        catalog.updateCase(id, 501, current.version(), current.title(), current.description(), current.styleCode(),
                current.floorCount(), current.buildingArea(), current.faceWidth(), current.depth(), current.rooms(), current.tags(), "BUILDING_OUTLINE");
        assertThat(jdbc.queryForObject("SELECT dimension_basis FROM design_case_version WHERE id=?", String.class, old)).isEqualTo("UNKNOWN");
        edit(id);
        assertThat(catalog.getAdminCase(id).orElseThrow().dimensionBasis()).isEqualTo("BUILDING_OUTLINE");
        assertThat(catalog.getAdminCase(id).orElseThrow().faceWidth()).isEqualByComparingTo("12.6");
    }

    @Test
    void additiveMigrationAndManualUndoKeepHistoricalNumericDimensionsIntact() throws Exception {
        String schema = "t26_case_basis_recovery";
        jdbc.execute("CREATE SCHEMA " + schema);
        var history = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var properties = new java.util.Properties(); properties.setProperty("currentSchema", schema);
        history.setConnectionProperties(properties);
        var oldJdbc = new JdbcTemplate(history);
        try {
            Flyway.configure().dataSource(history).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/platform", "classpath:db/migration/design").target("20261012.508").load().migrate();
            oldJdbc.update("INSERT INTO design_case(id,source_type,creator_user_id,current_version_id) VALUES(101,'COMPANY',501,201)");
            oldJdbc.update("INSERT INTO design_case_version(id,case_id,version,title,style_code,floor_count,building_area,face_width,depth) "
                    + "VALUES(201,101,1,'历史尺寸','MODERN',2,200,12.6,13.8)");
            Flyway.configure().dataSource(history).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
            assertThat(oldJdbc.queryForObject("SELECT dimension_basis FROM design_case_version WHERE id=201", String.class)).isEqualTo("UNKNOWN");
            assertThat(oldJdbc.queryForObject("SELECT face_width FROM design_case_version WHERE id=201", BigDecimal.class)).isEqualByComparingTo("12.6");
            assertThatThrownBy(() -> oldJdbc.update("UPDATE design_case_version SET dimension_basis='INFERRED' WHERE id=201"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            oldJdbc.execute(dimensionBasisUndo());
            assertThat(oldJdbc.queryForObject("SELECT depth FROM design_case_version WHERE id=201", BigDecimal.class)).isEqualByComparingTo("13.8");
            assertThat(oldJdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
                    + "AND table_name='design_case_version' AND column_name='dimension_basis'", Long.class, schema)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void manualUndoRefusesConfirmedBasisIncludingDeletedHistoryAndPreservesSchemaAndDimensions() throws Exception {
        String schema = "t26_case_basis_protected_recovery";
        jdbc.execute("CREATE SCHEMA " + schema);
        var history = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var properties = new java.util.Properties(); properties.setProperty("currentSchema", schema);
        history.setConnectionProperties(properties);
        var oldJdbc = new JdbcTemplate(history);
        try {
            Flyway.configure().dataSource(history).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
            oldJdbc.update("INSERT INTO design_case(id,source_type,creator_user_id,current_version_id) VALUES(101,'COMPANY',501,201)");
            oldJdbc.update("INSERT INTO design_case_version(id,case_id,version,title,style_code,floor_count,building_area,face_width,depth) "
                    + "VALUES(201,101,1,'已核实尺寸','MODERN',2,200,12.6,13.8)");
            String undo = dimensionBasisUndo();
            for (String basis : List.of("SITE", "BUILDING_OUTLINE")) {
                for (boolean deleted : List.of(false, true)) {
                    oldJdbc.update("UPDATE design_case_version SET dimension_basis=?, deleted=? WHERE id=201", basis, deleted);
                    assertThatThrownBy(() -> oldJdbc.execute(undo))
                            .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, error ->
                                    assertThat(error.getMostSpecificCause().getMessage())
                                            .contains("T26_07_DIMENSION_BASIS_ROLLBACK_REQUIRES_DATA_DISPOSITION"));
                    assertThat(oldJdbc.queryForObject("SELECT dimension_basis FROM design_case_version WHERE id=201", String.class)).isEqualTo(basis);
                    assertThat(oldJdbc.queryForObject("SELECT face_width FROM design_case_version WHERE id=201", BigDecimal.class)).isEqualByComparingTo("12.6");
                    assertThat(oldJdbc.queryForObject("SELECT depth FROM design_case_version WHERE id=201", BigDecimal.class)).isEqualByComparingTo("13.8");
                }
            }
            assertThatThrownBy(() -> oldJdbc.update("UPDATE design_case_version SET dimension_basis='INFERRED' WHERE id=201"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private String dimensionBasisUndo() throws Exception {
        try (var input = new ClassPathResource("db/migration-undo/design/V20261012.509__undo_case_dimension_basis.sql").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void forbidden(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode()));
    }

    private void versionConflict(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(STATE_VERSION_CONFLICT.getCode()));
    }
}
