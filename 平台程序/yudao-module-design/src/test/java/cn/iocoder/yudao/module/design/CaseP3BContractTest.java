package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * P3B 合同测试（真实 PostgreSQL）：稳定游标分页（同面积不重页/漏页）、下架即时不可见、
 * 版本不可变与 STATE_VERSION_CONFLICT、收藏并发幂等、批量上下架逐项结果、首页只含已发布。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseP3BContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ADMIN = 501L;

    private JdbcTemplate jdbc;
    private CaseCatalogService catalog;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load()
                .migrate();

        catalog = new CaseCatalogService(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_case, design_case_version, design_case_asset, "
                + "case_publication, case_favorite, asset_rights_grant, asset");
    }

    private long createAndPublish(int buildingArea, String style) {
        long caseId = catalog.createCompanyCase(ADMIN, "案例 " + buildingArea + "㎡",
                null, style, 2, buildingArea, null, null, null, List.of(" tags-" + buildingArea));
        seedPublishableImages(caseId);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    private long createAndPublishWithDimension(String faceWidth, String depth) {
        return createAndPublishWithDimension(faceWidth, depth, "BUILDING_OUTLINE");
    }

    private long createAndPublishWithDimension(String faceWidth, String depth, String dimensionBasis) {
        long caseId = catalog.createCompanyCase(ADMIN, "尺寸案例 " + faceWidth + "x" + depth,
                null, "MODERN", 2, 120,
                faceWidth == null ? null : new BigDecimal(faceWidth),
                depth == null ? null : new BigDecimal(depth), null, null, dimensionBasis);
        seedPublishableImages(caseId);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    // ========== 1. 稳定游标分页：同面积不重页/漏页 ==========

    private void seedPublishableImages(long caseId) {
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, caseId);
        for (String role : List.of("COVER", "FLOOR_PLAN")) {
            if (jdbc.queryForObject("SELECT count(*) FROM design_case_asset WHERE case_version_id=? AND asset_role=?", Integer.class, version, role) == 0)
                jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,?)",
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), version, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), role);
        }
        for (Long id : jdbc.queryForList("SELECT DISTINCT asset_id FROM design_case_asset WHERE case_version_id=?", Long.class, version)) {
            jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes,upload_status,security_scan_status,moderation_status) "
                    + "VALUES(?,? ,501,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED') ON CONFLICT(id) DO NOTHING", id, "test/" + id);
            jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) VALUES(?,?,501,'test','PUBLIC_DISPLAY',now())",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), id);
        }
    }

    private long addFloorPlan(long caseId, int floorNo, boolean publicDisplayGranted) {
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, caseId);
        long assetId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no) "
                        + "VALUES(?,?,?,'FLOOR_PLAN',?)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), version, assetId, floorNo);
        jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes,"
                        + "upload_status,security_scan_status,moderation_status) "
                        + "VALUES(?,? ,501,'CASE_IMAGE','AI','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                assetId, "test/" + assetId);
        if (publicDisplayGranted) {
            jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                            + "VALUES(?,?,501,'test','PUBLIC_DISPLAY',now())",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), assetId);
        }
        return assetId;
    }

    @Test
    void publicationRejectsMissingUnsafeAndUnlicensedImages() {
        long id = catalog.createCompanyCase(ADMIN, "缺图", null, "MODERN", 1, 100, null, null, null, null);
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        seedPublishableImages(id);
        jdbc.update("UPDATE asset SET security_scan_status='REJECTED'");
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE asset SET security_scan_status='PASSED'");
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN'");
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        assertThat(catalog.getAdminCase(id).orElseThrow().publicationStatus()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_publication WHERE case_id=?", Integer.class, id)).isZero();
    }

    @Test
    void referenceActionRequiresEveryCurrentPlanGrantAndPreservesActualFloors() {
        long id = catalog.createCompanyCase(ADMIN, "参考权限", null, "MODERN", 3, 120, null, null, null, null);
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, id);
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no) VALUES "
                + "(901,?,801,'FLOOR_PLAN',1),(902,?,802,'FLOOR_PLAN',3)", version, version);
        seedPublishableImages(id);
        catalog.publish(id, "admin");
        assertThat(catalog.getFloorPlans(id)).extracting(CaseCatalogService.FloorPlan::floorNo).containsExactly(1, 3);
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                + "VALUES (911,801,501,'test','GENERATION_REFERENCE',now()-interval '1 hour')");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                + "VALUES (912,802,501,'test','GENERATION_REFERENCE',now()-interval '1 hour')");
        assertThat(catalog.canUseForGeneration(id)).isTrue();
        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()-interval '1 second' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("UPDATE asset_rights_grant SET expires_at=null, effective_at=now()+interval '1 hour' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("UPDATE asset_rights_grant SET effective_at=now()-interval '1 hour', status='WITHDRAWN' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        catalog.offline(id, "test", "admin");
        assertThat(catalog.getPublishedCase(id)).isEmpty();
        assertThat(catalog.getAdminCase(id)).isPresent();
    }

    @Test
    void aiCaseParametersEditableForOperationsCorrection() {
        // 2026-10-02 运营决策：AI 案例开放参数编辑（图纸仍走投稿流程，requireImageEditable 拒绝 AI）
        long id = createAndPublish(120, "MODERN");
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", id);
        catalog.updateCase(id, ADMIN, 1, "运营修正标题", null, "MODERN", 2, 120,
                null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
        assertThat(catalog.getAdminCase(id).orElseThrow().title()).isEqualTo("运营修正标题");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded: published company cases are editable with CAS")
    void publishedCompanyCaseCannotBeChangedWithoutWithdrawal() {
        long id = createAndPublish(120, "MODERN");
        assertThatThrownBy(() -> catalog.updateCase(id, ADMIN, 1, "直接改线上内容", null, "MODERN", 2, 120, null, null, null, null))
                .isInstanceOf(ServiceException.class);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(1);
        catalog.offline(id, "改图", "admin");
        catalog.updateCase(id, ADMIN, 1, "线下修改", null, "MODERN", 2, 120, null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void cursorPaginationCoversAllWithoutDuplication() {
        // 10 个案例，其中 4 个同面积 100㎡，顺序必须稳定
        List<Long> ids = IntStream.range(0, 10)
                .mapToObj(i -> createAndPublish(i < 4 ? 100 : 100 + i, "MODERN"))
                .collect(Collectors.toList());

        List<Long> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            var page = catalog.listPublishedCases(null, null, null, null, null, null, null, null, cursor, 3);
            page.list().forEach(c -> seen.add(c.caseId()));
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertThat(pages).as("游标翻页防御性上限").isLessThan(20);
        }
        assertThat(seen).hasSize(10).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }

    // ========== 2. 下架即时不可见；可重新上架 ==========

    @Test
    void offlineHidesImmediatelyAndRepublishWorks() {
        long caseId = createAndPublish(120, "MODERN");
        assertThat(catalog.getPublishedCase(caseId)).isPresent();

        assertThat(catalog.offline(caseId, "户型调整", "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId)).as("下架即时不可见").isEmpty();
        var page = catalog.listPublishedCases(null, null, null, null, null, null, null, null, null, 20);
        assertThat(page.list()).isEmpty();

        // 公司案例可重新上架（新发布事实）
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_publication WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(3); // PUBLISH + OFFLINE + PUBLISH
    }

    // ========== 3. 版本不可变 + 过期版本冲突 ==========

    @Test
    void editingCreatesImmutableNewVersionAndConflictsOnStale() {
        long caseId = catalog.createCompanyCase(ADMIN, "v1 标题", null, "MODERN", 2, 100,
                null, null, null, null);
        createAndPublish(150, "MODERN"); // 干扰数据

        long v2 = catalog.updateCase(caseId, ADMIN, 1L, "v2 标题", "改描述", "MODERN", 3, 120,
                null, null, null, null);
        assertThat(v2).isNotEqualTo(1L);

        // 旧版本行不可变
        var v1 = jdbc.queryForMap(
                "SELECT title, floor_count, building_area FROM design_case_version "
                        + "WHERE case_id = ? AND version = 1", caseId);
        assertThat(v1.get("title")).isEqualTo("v1 标题");
        assertThat((int) v1.get("floor_count")).isEqualTo(2);

        // 详情读新版本
        var detail = catalog.getPublishedCase(caseId);
        // 未发布不可见 → 先发布
        seedPublishableImages(caseId);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId).orElseThrow().title()).isEqualTo("v2 标题");

        // 过期版本更新 → STATE_VERSION_CONFLICT
        assertThatThrownBy(() -> catalog.updateCase(caseId, ADMIN, 1L, "stale", null,
                "MODERN", 2, 100, null, null, null, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_001));
    }

    // ========== 4. 收藏并发幂等 + 列表 ==========

    @Test
    void favoriteIsIdempotentUnderConcurrency() throws Exception {
        long caseId = createAndPublish(130, "MODERN");
        long userId = 701L;
        ExecutorServicePool pool = new ExecutorServicePool(5);
        List<java.util.concurrent.Callable<Boolean>> tasks = IntStream.range(0, 5)
                .<java.util.concurrent.Callable<Boolean>>mapToObj(i ->
                        () -> catalog.favorite(userId, caseId))
                .collect(Collectors.toList());
        int inserted = pool.run(tasks);
        pool.shutdown();
        assertThat(inserted).as("并发收藏只新增一次").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_favorite WHERE user_id = ? AND case_id = ?",
                Integer.class, userId, caseId)).isEqualTo(1);

        // 收藏列表可见；取消后消失
        var favPage = catalog.listFavorites(userId, null, 20);
        assertThat(favPage.list()).hasSize(1);
        assertThat(catalog.unfavorite(userId, caseId)).isTrue();
        assertThat(catalog.listFavorites(userId, null, 20).list()).isEmpty();
    }

    // ========== 5. 批量上下架逐项结果 ==========

    @Test
    void bulkActionReportsPerItemResult() {
        long published = createAndPublish(140, "MODERN");
        long draft = catalog.createCompanyCase(ADMIN, "草稿", null, "MODERN", 2, 141,
                null, null, null, null); // 未发布 → 下架失败

        var items = catalog.bulkPublicationAction(List.of(published, draft), false, "统一调整", "admin");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("success", true);
        assertThat(items.get(1)).containsEntry("success", false);
        assertThat(items.get(1)).containsEntry("errorCode", "PUBLICATION_VALIDATION_FAILED");
    }

    // ========== 6. 首页精选只含已发布 ==========

    @Test
    void homeFeaturedOnlyPublished() {
        createAndPublish(160, "MODERN");
        catalog.createCompanyCase(ADMIN, "草稿不精选", null, "MODERN", 2, 161,
                null, null, null, null);
        var featured = catalog.listPublishedCases(null, null, null, null, null, null, null, null, null, 6);
        assertThat(featured.list()).hasSize(1);
        assertThat(featured.list().get(0).publicationStatus()).isEqualTo("PUBLISHED");
    }

    // ========== 7. BOLA：未发布案例详情不可见 ==========

    @Test
    void unpublishedCaseDetailHidden() {
        long draft = catalog.createCompanyCase(ADMIN, "草稿", null, "MODERN", 2, 170,
                null, null, null, null);
        assertThat(catalog.getPublishedCase(draft)).isEmpty();
    }

    // ========== 7b. 面宽/进深 ±1m 尺度筛选（2026-10-09 运营需求③） ==========

    @Test
    void dimensionFilterMatchesWithinOneMeter() {
        // narrow 12.6/10.0：输入13 → [12,14] 命中；edge 14.0：闭区间右边界命中；
        // far 10.5 越界排除；mixed 面宽命中但进深越界，用于验证组合筛选各自生效；unnamed 未填尺寸
        long narrow = createAndPublishWithDimension("12.6", "10.0");
        long edge = createAndPublishWithDimension("14.0", "9.0");
        long far = createAndPublishWithDimension("10.5", "12.0");
        long mixed = createAndPublishWithDimension("13.0", "6.0");
        long unnamed = createAndPublish(220, "MODERN");

        var byFace = catalog.listPublishedCases(null, null, null, null, null,
                new BigDecimal("13"), null, null, null, 20);
        assertThat(byFace.list()).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactlyInAnyOrder(narrow, edge, mixed);

        var byDepth = catalog.listPublishedCases(null, null, null, null, null,
                null, new BigDecimal("10"), null, null, 20);
        assertThat(byDepth.list()).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactlyInAnyOrder(narrow, edge);

        var combined = catalog.listPublishedCases(null, null, null, null, null,
                new BigDecimal("13"), new BigDecimal("9"), null, null, 20);
        assertThat(combined.list()).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactlyInAnyOrder(narrow, edge);

        // 不启用尺寸筛选时全部可见；未填尺寸的案例仅在无筛选时出现
        var all = catalog.listPublishedCases(null, null, null, null, null, null, null, null, null, 20);
        assertThat(all.list()).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactlyInAnyOrder(narrow, edge, far, mixed, unnamed);
    }

    @Test
    void dimensionFilterRejectsHostileMagnitudeBeforeAnyArithmetic() {
        // 列表接口匿名可读。BigDecimal 加减会把标度对齐成等长整数：1E+10000000 实测耗时约 5 秒，
        // 1E+100000000 数十秒未结束；1E-10000000 同理（要把 1 放大 10^7 倍）。必须在运算前按量级拒绝。
        for (String hostile : List.of("1E+10000000", "1E-10000000", "-1", "1000", "0.1234567")) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                assertThatThrownBy(() -> catalog.listPublishedCases(null, null, null, null, null,
                        new BigDecimal(hostile), null, null, null, 20))
                        .as("面宽 " + hostile).isInstanceOf(ServiceException.class);
                assertThatThrownBy(() -> catalog.listPublishedCases(null, null, null, null, null,
                        null, new BigDecimal(hostile), null, null, 20))
                        .as("进深 " + hostile).isInstanceOf(ServiceException.class);
            });
        }
        // 合法边界不受影响：0 与 999.9（NUMERIC(4,1) 上限）照常检索
        assertThat(catalog.listPublishedCases(null, null, null, null, null,
                BigDecimal.ZERO, new BigDecimal("999.9"), null, null, 20).list()).isEmpty();
    }

    // ========== 2b. keyword 检索（编号精确 + 名称模糊）与风格筛选项 ==========

    @Test
    void keywordSearchMatchesCaseIdExactlyAndTitleFuzzily() {
        long id = createAndPublish(101, "MODERN");
        createAndPublish(102, "CHINESE"); // 干扰数据

        // 纯数字按案例编号精确命中
        var byId = catalog.listPublishedCases(null, null, null, null, null, null, null,
                String.valueOf(id), null, 20);
        assertThat(byId.list()).extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(id);

        // 名称模糊命中
        var byTitle = catalog.listPublishedCases(null, null, null, null, null, null, null,
                "101㎡", null, 20);
        assertThat(byTitle.list()).extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(id);

        // LIKE 通配符不产生意外命中
        assertThat(catalog.listPublishedCases(null, null, null, null, null, null, null,
                "%", null, 20).list()).isEmpty();
    }

    @Test
    void styleOptionsContainAllPresetsAndPublishedCustomCodes() {
        createAndPublish(110, "NEW_CHINESE");
        createAndPublish(111, "AMERICAN");
        long custom = catalog.createCompanyCase(ADMIN, "自定义风格案例", null, "地中海", 2, 130,
                null, null, null, null);
        seedPublishableImages(custom);
        assertThat(catalog.publish(custom, "admin")).isTrue();
        catalog.createCompanyCase(ADMIN, "未发布日式", null, "JAPANESE", 2, 130,
                null, null, null, null);
        catalog.createCompanyCase(ADMIN, "未发布法式", null, "FRENCH", 2, 130,
                null, null, null, null);

        var options = catalog.listPublishedStyles();
        var codes = options.stream()
                .map(CaseCatalogService.StyleOption::code).collect(Collectors.toList());
        assertThat(codes.subList(0, 9)).containsExactly(
                "NEW_CHINESE", "CHINESE", "EUROPEAN", "NEW_ASIAN", "SONG", "COUNTRYSIDE", "MODERN",
                "HUI_PAI", "SU_SHI");
        assertThat(codes.subList(9, codes.size())).containsExactlyInAnyOrder("AMERICAN", "地中海");
        assertThat(options).filteredOn(option -> option.code().equals("HUI_PAI"))
                .extracting(CaseCatalogService.StyleOption::label).containsExactly("徽派");
        assertThat(options).filteredOn(option -> option.code().equals("SU_SHI"))
                .extracting(CaseCatalogService.StyleOption::label).containsExactly("苏式");
        assertThat(codes).doesNotContain("JAPANESE", "FRENCH", "未发布风格");
        assertThat(options).filteredOn(option -> option.code().equals("NEW_ASIAN"))
                .extracting(CaseCatalogService.StyleOption::label).containsExactly("新亚洲");
        assertThat(options).filteredOn(option -> option.code().equals("SONG"))
                .extracting(CaseCatalogService.StyleOption::label).containsExactly("宋式");
        assertThat(options).filteredOn(option -> option.code().equals("AMERICAN"))
                .extracting(CaseCatalogService.StyleOption::label).containsExactly("美式");
    }

    @Test
    void homeFeaturedOrderingAndToggle() {
        long publishedFirst = createAndPublish(100, "MODERN");
        long publishedSecond = createAndPublish(120, "MODERN"); // createAndPublish 顺序即发布顺序

        // 未标记：按「最新发布」补齐（后发布的在前，面积不再参与首页排序）
        assertThat(catalog.listHomeCases(8)).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactly(publishedSecond, publishedFirst);

        // 标记较早发布的案例仍置顶于未标记的最新案例之前，取消后回落到最新发布顺序
        assertThat(catalog.setHomeFeatured(publishedFirst, true, ADMIN)).isTrue();
        assertThat(catalog.listHomeCases(8)).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactly(publishedFirst, publishedSecond);
        assertThat(catalog.setHomeFeatured(publishedFirst, false, ADMIN)).isTrue();
        assertThat(catalog.listHomeCases(8)).extracting(CaseCatalogService.CaseSummary::caseId)
                .containsExactly(publishedSecond, publishedFirst);
        // 未命中（不存在/已删除）返回 false
        assertThat(catalog.setHomeFeatured(999999L, true, ADMIN)).isFalse();
        // 草稿不可标记（400）
        long draft = catalog.createCompanyCase(ADMIN, "草稿不可精选", null, "MODERN", 2, 130,
                null, null, null, null);
        assertThatThrownBy(() -> catalog.setHomeFeatured(draft, true, ADMIN))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void resolveAdminUploaderReadsPlatformSmallintDeletedColumns() {
        // 回归：平台底座 system_users/system_dept 的 deleted 是 int2 NOT NULL DEFAULT 0
        // （见 sql/postgresql/ruoyi-vue-pro.sql），SQL 写 deleted = FALSE 会报
        // "operator does not exist: smallint = boolean"，生产新增公司案例必 500。
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users "
                + "(id int8 PRIMARY KEY, nickname varchar, dept_id int8, deleted int2 NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_dept "
                + "(id int8 PRIMARY KEY, name varchar, deleted int2 NOT NULL DEFAULT 0)");
        try {
            jdbc.update("INSERT INTO system_dept (id, name) VALUES (10, '合家美宅')");
            jdbc.update("INSERT INTO system_users (id, nickname, dept_id) VALUES (?, '张三', 10)", ADMIN);
            var uploader = catalog.resolveAdminUploader(ADMIN);
            assertThat(uploader.name()).as("操作人昵称").isEqualTo("张三");
            assertThat(uploader.company()).as("所属部门名").isEqualTo("合家美宅");

            // 操作人被逻辑删除 → 回退空上传人（前端回退默认来源标签）
            jdbc.update("UPDATE system_users SET deleted = 1 WHERE id = ?", ADMIN);
            assertThat(catalog.resolveAdminUploader(ADMIN)).isEqualTo(CaseCatalogService.Uploader.EMPTY);
        } finally {
            // 最小同构表只服务于本用例，测试结束删除，避免影响其他用例的 createCompanyCase 探测路径
            jdbc.execute("DROP TABLE IF EXISTS system_users");
            jdbc.execute("DROP TABLE IF EXISTS system_dept");
        }
    }

    @Test
    void uploaderDisplayFieldsRoundTrip() {
        long id = createAndPublish(130, "MODERN");
        // 合同测试库无 system_users：后台创建时上传人为空，前端回退默认来源标签
        assertThat(catalog.listPublishedCases(null, null, null, null, null, null, null, null, null, 20)
                .list().get(0).uploaderName()).isNull();

        // 存量/解析失败的数据可由运营侧补录，列表与详情均带出（来源显示公司·人名）
        jdbc.update("UPDATE design_case SET uploader_name = ?, uploader_company = ? WHERE id = ?",
                "张三", "合家美宅", id);
        var summary = catalog.listPublishedCases(null, null, null, null, null, null, null, null, null, 20).list().get(0);
        assertThat(summary.uploaderName()).isEqualTo("张三");
        assertThat(summary.uploaderCompany()).isEqualTo("合家美宅");
        var detail = catalog.getPublishedCase(id).orElseThrow();
        assertThat(detail.uploaderName()).isEqualTo("张三");
        assertThat(detail.uploaderCompany()).isEqualTo("合家美宅");
    }

    // ========== 2c. 外观设计平面：按尺寸检索，与输入尺寸最接近的排前面 ==========

    @Test
    void byDimensionSearchMatchesBothAxesAndOrdersByClosest() {
        // 输入 13×11.5 的距离 = |面宽差| + |进深差|：near=0.5+0.5=1.0，mid=0.5+1.0=1.5，farEdge=1.0+1.0=2.0
        long near = createAndPublishWithDimension("12.5", "11.0");
        long mid = createAndPublishWithDimension("13.5", "12.5");
        long farEdge = createAndPublishWithDimension("12.0", "12.5");
        long outOfWindow = createAndPublishWithDimension("20.0", "18.0");
        long noDim = createAndPublish(150, "MODERN");

        // w=13→[12,14]、d=11.5→[10.5,12.5]：三例均两面命中（12.0/12.5 为闭区间边界），按距离升序
        var page = catalog.listCasesByDimension(new BigDecimal("13"), new BigDecimal("11.5"),
                "BUILDING_OUTLINE", 50);
        assertThat(page.list()).extracting(CaseCatalogService.DimensionCase::caseId)
                .containsExactly(near, mid, farEdge);
        assertThat(page.nextCursor()).isNull();
        // 未填尺寸的案例不参与（与灵感库筛选同口径），窗外案例被排除
        assertThat(page.list()).extracting(CaseCatalogService.DimensionCase::caseId)
                .doesNotContain(noDim, outOfWindow);
        assertThat(page.list().get(0).faceWidth()).isNotNull();

        // 进深窗口移动：d=13→[12,14] 时 near(11.0) 掉出，mid/farEdge 仍按距离排序
        var deeper = catalog.listCasesByDimension(new BigDecimal("13"), new BigDecimal("13.0"),
                "BUILDING_OUTLINE", 50);
        assertThat(deeper.list()).extracting(CaseCatalogService.DimensionCase::caseId)
                .containsExactly(mid, farEdge);

        // 两面都必填
        assertThatThrownBy(() -> catalog.listCasesByDimension(new BigDecimal("13"), null,
                "BUILDING_OUTLINE", 50))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void byDimensionReturnsPublicFloorPlansWithoutTreatingUnknownAsExact() {
        long matched = createAndPublishWithDimension("12.5", "11.0", "BUILDING_OUTLINE");
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", matched);
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, matched);
        long firstFloorAsset = addFloorPlan(matched, 1, true);

        long unknown = createAndPublishWithDimension("12.4", "11.0", "UNKNOWN");
        long wrongBasis = createAndPublishWithDimension("12.3", "11.0", "SITE");
        long unsafe = createAndPublishWithDimension("12.2", "11.0", "BUILDING_OUTLINE");
        jdbc.update("UPDATE asset SET moderation_status='REJECTED' WHERE id IN (SELECT asset_id "
                + "FROM design_case_asset WHERE case_version_id=(SELECT current_version_id FROM design_case WHERE id=?) "
                + "AND asset_role='FLOOR_PLAN')", unsafe);
        long missingPublicFloor = createAndPublishWithDimension("12.1", "11.0", "BUILDING_OUTLINE");
        addFloorPlan(missingPublicFloor, 1, false);
        long withdrawnPublic = createAndPublishWithDimension("12.0", "11.0", "BUILDING_OUTLINE");
        long expiredPublic = createAndPublishWithDimension("11.9", "11.0", "BUILDING_OUTLINE");
        long futurePublic = createAndPublishWithDimension("11.8", "11.0", "BUILDING_OUTLINE");
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN' WHERE scope='PUBLIC_DISPLAY' AND asset_id IN "
                + "(SELECT asset_id FROM design_case_asset WHERE case_version_id=(SELECT current_version_id "
                + "FROM design_case WHERE id=?) AND asset_role='FLOOR_PLAN')", withdrawnPublic);
        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()-interval '1 second' "
                + "WHERE scope='PUBLIC_DISPLAY' AND asset_id IN (SELECT asset_id FROM design_case_asset "
                + "WHERE case_version_id=(SELECT current_version_id FROM design_case WHERE id=?) "
                + "AND asset_role='FLOOR_PLAN')", expiredPublic);
        jdbc.update("UPDATE asset_rights_grant SET effective_at=now()+interval '1 hour' "
                + "WHERE scope='PUBLIC_DISPLAY' AND asset_id IN (SELECT asset_id FROM design_case_asset "
                + "WHERE case_version_id=(SELECT current_version_id FROM design_case WHERE id=?) "
                + "AND asset_role='FLOOR_PLAN')", futurePublic);

        var page = catalog.listCasesByDimension(new BigDecimal("12.5"), new BigDecimal("11"),
                "BUILDING_OUTLINE", 50);
        assertThat(page.list()).extracting(CaseCatalogService.DimensionCase::caseId)
                .containsExactly(matched)
                .doesNotContain(unknown, wrongBasis, unsafe, missingPublicFloor,
                        withdrawnPublic, expiredPublic, futurePublic);
        var result = page.list().get(0);
        assertThat(result.caseVersionId()).isEqualTo(version);
        assertThat(result.dimensionBasis()).isEqualTo("BUILDING_OUTLINE");
        assertThat(result.floorPlans()).extracting(CaseCatalogService.FloorPlan::floorNo)
                .containsExactly(1, null);
        assertThat(result.planPreviewAssetId()).isEqualTo(String.valueOf(firstFloorAsset));
        assertThat(result.generationAllowed()).isFalse();

        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                        + "VALUES(?,?,501,'test','GENERATION_REFERENCE',now())",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), firstFloorAsset);
        assertThat(catalog.listCasesByDimension(new BigDecimal("12.5"), new BigDecimal("11"),
                "BUILDING_OUTLINE", 50).list().get(0).generationAllowed()).isFalse();

        for (Long assetId : jdbc.queryForList("SELECT asset_id FROM design_case_asset WHERE case_version_id=? "
                + "AND asset_role='FLOOR_PLAN' AND asset_id<>?", Long.class, version, firstFloorAsset)) {
            jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                            + "VALUES(?,?,501,'test','GENERATION_REFERENCE',now())",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), assetId);
        }
        assertThat(catalog.listCasesByDimension(new BigDecimal("12.5"), new BigDecimal("11"),
                "BUILDING_OUTLINE", 50).list().get(0).generationAllowed()).isTrue();
    }

    private long publishCase(int floors, int area, boolean generationGrant) {
        long caseId = catalog.createCompanyCase(ADMIN, "案例 " + floors + "层 " + area + "㎡", null, "MODERN",
                floors, area, null, null, null, null);
        seedPublishableImages(caseId);
        if (generationGrant) {
            long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, caseId);
            for (Long id : jdbc.queryForList("SELECT DISTINCT asset_id FROM design_case_asset WHERE case_version_id=?", Long.class, version)) {
                jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) VALUES(?,?,501,'test','GENERATION_REFERENCE',now())",
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), id);
            }
        }
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    @Test
    void recommendationPrefersSameFloorCountThenClosestAreaAndSkipsCasesNotAuthorizedForGeneration() {
        // 定稿 7.1-10：建项目时按"层数一致优先、建筑面积接近"选默认参考案例；未授权生成参考的案例不可推荐
        assertThat(catalog.recommendForGeneration(2, 240)).as("无已发布案例").isEmpty();
        long twoFloorNear = publishCase(2, 240, true);
        publishCase(2, 400, true);
        long threeFloor = publishCase(3, 250, true);
        publishCase(2, 238, false);   // 面积更近，但未授权生成参考
        catalog.createCompanyCase(ADMIN, "草稿不推荐", null, "MODERN", 2, 236, null, null, null, null);

        assertThat(catalog.recommendForGeneration(2, 236).orElseThrow().caseId())
                .as("同层数里面积最接近且已授权的").isEqualTo(twoFloorNear);
        assertThat(catalog.recommendForGeneration(3, 100).orElseThrow().caseId())
                .as("层数一致优先于面积接近").isEqualTo(threeFloor);
        assertThat(catalog.recommendForGeneration(1, 243).orElseThrow().caseId())
                .as("没有同层数时退回全局面积最接近").isEqualTo(twoFloorNear);
    }

    @Test
    void adminDimensionParsingValidatesRangeAndPrecision() {
        // 管理端录入：合法范围 3~40 米，保留一位小数；空值表示未填；其余一律 400（不再静默当作"未填"）
        assertThat(CaseCatalogService.parseDimension(null)).isNull();
        assertThat(CaseCatalogService.parseDimension("  ")).isNull();
        assertThat(CaseCatalogService.parseDimension(12)).isEqualByComparingTo("12.0");
        assertThat(CaseCatalogService.parseDimension(12.6)).isEqualByComparingTo("12.6");
        assertThat(CaseCatalogService.parseDimension("13.5")).isEqualByComparingTo("13.5");
        assertThat(CaseCatalogService.parseDimension(12.66)).isEqualByComparingTo("12.7");
        assertThat(CaseCatalogService.parseDimension(3)).isEqualByComparingTo("3.0");
        assertThat(CaseCatalogService.parseDimension(40)).isEqualByComparingTo("40.0");
        for (Object bad : new Object[]{-1, 0, 2.9, 40.1, 10000.5, Double.NaN, Double.POSITIVE_INFINITY, "abc", "1E+10000000", "1E-10000000"}) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> CaseCatalogService.parseDimension(bad))
                            .as("入参 " + bad).isInstanceOf(ServiceException.class));
        }
    }

    @Test
    void caseDimensionsPersistWithOneDecimal() {
        long caseId = createAndPublishWithDimension("12.6", "11.6");
        var detail = catalog.getPublishedCase(caseId).orElseThrow();
        assertThat(detail.faceWidth()).isEqualByComparingTo(new BigDecimal("12.6"));
        assertThat(detail.depth()).isEqualByComparingTo(new BigDecimal("11.6"));
        // 版本化编辑保留一位小数
        catalog.updateCase(caseId, ADMIN, 1L, "改尺寸", null, "MODERN", 2, 120,
                new BigDecimal("13.5"), new BigDecimal("9.5"), null, null);
        seedPublishableImages(caseId);
        catalog.publish(caseId, "admin");
        var v2 = catalog.getPublishedCase(caseId).orElseThrow();
        assertThat(v2.faceWidth()).isEqualByComparingTo(new BigDecimal("13.5"));
        assertThat(v2.depth()).isEqualByComparingTo(new BigDecimal("9.5"));
    }

    // ========== 8. 详情资产用版本行 id 关联（评审修复回归） ==========

    @Test
    void caseDetailReturnsAssetsViaVersionRowId() {
        long caseId = catalog.createCompanyCase(ADMIN, "带资产案例", "描述", "MODERN", 2, 180,
                null, null, null, null);
        long versionId = jdbc.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role) "
                        + "VALUES (?,?,?,'COVER')", 8001L, versionId, 9101L);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                        + "VALUES (?,?,?,'FLOOR_PLAN',1)", 8002L, versionId, 9102L);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                        + "VALUES (?,?,?,'FLOOR_PLAN',2)", 8003L, versionId, 9103L);
        seedPublishableImages(caseId);
        catalog.publish(caseId, "admin");

        var detail = catalog.getPublishedCase(caseId).orElseThrow();
        assertThat(detail.coverAssetId()).isEqualTo("9101");
        assertThat(detail.floorPlanAssetIds()).containsExactly("9102", "9103");
    }

    // ========== 9. 管理端列表含草稿；批量对无效 id 逐项失败不整体 500 ==========

    @Test
    void adminPageShowsDraftsAndBulkIsolatesInvalidIds() {
        long draft = catalog.createCompanyCase(ADMIN, "草稿可见于管理端", null, "MODERN", 2, 190,
                null, null, null, null);
        assertThat(catalog.countCasesForAdmin(null, null)).isEqualTo(1);
        assertThat(catalog.pageCasesForAdmin(null, "DRAFT", 1, 20))
                .extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(draft);
        assertThat(catalog.pageCasesForAdmin(null, "PUBLISHED", 1, 20)).isEmpty();

        long published = createAndPublish(191, "MODERN");
        var items = catalog.bulkPublicationAction(
                java.util.List.of(published, 999999999L, draft), false, "批量下架", "admin");
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).as("已发布可下架").containsEntry("success", true);
        assertThat(items.get(1)).as("无效 id 逐项失败但不中断").containsEntry("success", false);
        assertThat(items.get(2)).as("草稿无可下架的发布态，逐项失败").containsEntry("success", false);
    }

    // ========== 10. 收藏游标翻页不漏页（评审修复回归） ==========

    @Test
    void favoritesPaginationUsesFavoriteRowIdCursor() {
        // 旧案例（先创建）后收藏 → 收藏行 id 大于案例 id，覆盖错位场景
        long oldCase = createAndPublish(200, "MODERN");
        long userId = 801L;
        List<Long> favorites = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long c = createAndPublish(201 + i, "MODERN");
            catalog.favorite(userId, c);
            favorites.add(c);
        }
        catalog.favorite(userId, oldCase);

        var page1 = catalog.listFavorites(userId, null, 2);
        assertThat(page1.list()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        var page2 = catalog.listFavorites(userId, page1.nextCursor(), 2);
        assertThat(page2.list()).hasSize(2);
        var seen = new java.util.ArrayList<Long>();
        page1.list().forEach(c -> seen.add(c.caseId()));
        page2.list().forEach(c -> seen.add(c.caseId()));
        assertThat(seen).doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    void publishedCompanyCaseEditingPreservesImmutableVersionAndCas() {
        long id = createAndPublish(120, "MODERN");
        assertThat(catalog.requireImageEditable(id, 1, "COVER", null).publicationStatus())
                .isEqualTo("PUBLISHED");
        catalog.updateCase(id, ADMIN, 1, "published edit", null, "MODERN", 2, 120,
                null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
        assertThat(catalog.getAdminCase(id).orElseThrow().publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT creator FROM design_case_version WHERE case_id=? AND version=2",
                String.class, id)).isEqualTo(String.valueOf(ADMIN));
        assertThatThrownBy(() -> catalog.updateCase(id, ADMIN, 1, "stale", null, "MODERN", 2,
                120, null, null, null, null)).isInstanceOf(ServiceException.class);
    }

    private static class ExecutorServicePool {
        private final java.util.concurrent.ExecutorService pool;

        ExecutorServicePool(int threads) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        }

        int run(List<java.util.concurrent.Callable<Boolean>> tasks) throws Exception {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var futures = tasks.stream().map(t -> pool.submit(() -> {
                latch.await();
                try {
                    return t.call() ? 1 : 0;
                } catch (Exception e) {
                    return 0;
                }
            })).toList();
            latch.countDown();
            int ok = 0;
            for (var f : futures) {
                ok += f.get();
            }
            return ok;
        }

        void shutdown() {
            pool.shutdownNow();
        }

    }

}
