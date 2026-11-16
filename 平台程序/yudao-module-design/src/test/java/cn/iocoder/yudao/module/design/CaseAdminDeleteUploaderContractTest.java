package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.controller.admin.CaseAdminController;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PR14/PR15 集成契约：首页标记、上传者快照、旧字段兼容与 OFFLINE 软删必须同时成立。 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseAdminDeleteUploaderContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private CaseCatalogService catalog;
    private CaseAdminController controller;
    private MockMvc mvc;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load().migrate();
        createUploaderUserTables(jdbc);
        catalog = new CaseCatalogService(ds, new DataSourceTransactionManager(ds));
        controller = new CaseAdminController();
        ReflectionTestUtils.setField(controller, "caseCatalogService", catalog);
        // Method security is unchanged. This real-controller fixture checks HTTP binding and SQL,
        // not an authenticated production session or a replacement permission implementation.
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 平台与身份底座表不在 design 迁移基线内，按生产字段类型补最小桩表。 */
    static void createUploaderUserTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_dept ("
                + "id BIGINT PRIMARY KEY, name VARCHAR(64), deleted SMALLINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users ("
                + "id BIGINT PRIMARY KEY, nickname VARCHAR(64), dept_id BIGINT, deleted SMALLINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account ("
                + "id BIGINT PRIMARY KEY, nickname VARCHAR(64), deleted BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS access_code_redemption ("
                + "id BIGINT PRIMARY KEY, account_id BIGINT, company_name VARCHAR(64), "
                + "contact_name VARCHAR(64), deleted BOOLEAN NOT NULL DEFAULT FALSE)");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE design_case, design_case_version, design_case_asset, case_publication, "
                + "case_favorite, audit_event");
        jdbc.update("DELETE FROM system_users");
        jdbc.update("DELETE FROM system_dept");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM access_code_redemption");
        jdbc.update("INSERT INTO system_dept (id, name) VALUES (51, '合家美宅')");
        jdbc.update("INSERT INTO system_users (id, nickname, dept_id) VALUES (501, '运营甲', 51)");
        jdbc.update("INSERT INTO account (id, nickname) VALUES (9001, '投稿用户乙')");
        jdbc.update("INSERT INTO access_code_redemption "
                + "(id, account_id, company_name, contact_name) VALUES (1, 9001, '投稿公司', '联系人乙')");
    }

    private long create(String sourceType, String publicationStatus) {
        long id = catalog.createCompanyCase(501L, "测试案例", null, "MODERN", 2, 120,
                null, null, null, null);
        jdbc.update("UPDATE design_case SET source_type = ?, publication_status = ? WHERE id = ?",
                sourceType, publicationStatus, id);
        return id;
    }

    @Test
    void deleteOnlyOfflineCaseAndKeepsAuditTrail() {
        long draft = create("COMPANY", "DRAFT");
        long published = create("COMPANY", "PUBLISHED");
        long offline = create("COMPANY", "PUBLISHED");

        assertThat(catalog.deleteOfflineCase(draft, "999")).isFalse();
        assertThat(catalog.deleteOfflineCase(published, "999")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();
        assertThat(catalog.getAdminCase(draft)).isPresent();
        assertThat(catalog.getAdminCase(published)).isPresent();

        // 真实下架链路留下架事实；已下架案例可删：软删后全端查询均不可见，操作人留痕
        assertThat(catalog.offline(offline, "测试下架", "888")).isTrue();
        assertThat(catalog.deleteOfflineCase(offline, "999")).isTrue();
        assertThat(catalog.getAdminCase(offline)).isEmpty();
        assertThat(catalog.countCasesForAdmin(null, null, null)).isEqualTo(2);
        Boolean deleted = jdbc.queryForObject(
                "SELECT deleted FROM design_case WHERE id = ?", Boolean.class, offline);
        String updater = jdbc.queryForObject(
                "SELECT updater FROM design_case WHERE id = ?", String.class, offline);
        assertThat(deleted).isTrue();
        assertThat(updater).isEqualTo("999");
        Integer facts = jdbc.queryForObject(
                "SELECT count(*) FROM case_publication WHERE case_id = ? AND action = 'OFFLINE'",
                Integer.class, offline);
        assertThat(facts).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT event_type, actor_type, actor_id, action, biz_type, biz_id, result, detail "
                        + "FROM audit_event WHERE biz_type = 'case' AND biz_id = ?", String.valueOf(offline));
        assertThat(audit).containsEntry("event_type", "ADMIN_DATA_DELETE")
                .containsEntry("actor_type", "ADMIN")
                .containsEntry("actor_id", "999")
                .containsEntry("action", "DELETE")
                .containsEntry("biz_type", "case")
                .containsEntry("biz_id", String.valueOf(offline))
                .containsEntry("result", "SUCCESS");
        assertThat(String.valueOf(audit.get("detail"))).contains("design_case");

        // 已删案例再次删除返回 false（幂等守卫），不产生新痕迹
        assertThat(catalog.deleteOfflineCase(offline, "999")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackDeletionAndRetryRecordsExactlyOnce() {
        long offline = create("COMPANY", "OFFLINE");
        var dataSource = java.util.Objects.requireNonNull(jdbc.getDataSource());
        var failingCatalog = new CaseCatalogService(dataSource, new DataSourceTransactionManager(dataSource));
        var realAudit = new JdbcAuditPort(dataSource);
        ReflectionTestUtils.setField(failingCatalog, "auditPort", (AuditPort) event -> {
            realAudit.record(event);
            throw new IllegalStateException("模拟审计写入后故障");
        });

        assertThatThrownBy(() -> failingCatalog.deleteOfflineCase(offline, "999"))
                .isInstanceOf(IllegalStateException.class).hasMessage("模拟审计写入后故障");
        assertThat(jdbc.queryForObject("SELECT deleted FROM design_case WHERE id = ?",
                Boolean.class, offline)).isFalse();
        assertThat(catalog.getAdminCase(offline)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();

        assertThat(catalog.deleteOfflineCase(offline, "999")).isTrue();
        assertThat(catalog.deleteOfflineCase(offline, "999")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void uploaderSnapshotWinsWhileLegacyBlankSnapshotFallsBackOnlyForAdmin() throws Exception {
        long snapshotted = create("COMPANY", "DRAFT");
        long partialSnapshot = create("COMPANY", "DRAFT");
        long legacy = create("COMPANY", "PUBLISHED");
        long legacyAi = create("AI", "PUBLISHED");
        jdbc.update("UPDATE design_case SET uploader_company = NULL WHERE id = ?", partialSnapshot);
        jdbc.update("UPDATE design_case SET uploader_name = NULL, uploader_company = NULL WHERE id = ?", legacy);
        jdbc.update("UPDATE design_case SET creator_user_id = 9001, uploader_name = NULL, uploader_company = NULL "
                + "WHERE id = ?", legacyAi);
        jdbc.update("INSERT INTO access_code_redemption "
                + "(id, account_id, company_name, contact_name, deleted) "
                + "VALUES (2, 9001, '已删除公司', '已删除联系人', TRUE)");

        // 新数据使用固化快照；迁移前空快照仅后台按 creator_user_id 兼容回查当前姓名/部门。
        jdbc.update("UPDATE system_users SET nickname = '运营甲（已改名）' WHERE id = 501");
        jdbc.update("UPDATE system_dept SET name = '合家美宅（新名）' WHERE id = 51");
        var snapshot = controller.getCase(snapshotted).getData();
        assertThat(snapshot).containsEntry("caseId", String.valueOf(snapshotted));
        assertThat(snapshot.get("uploaderName")).isEqualTo("运营甲");
        assertThat(snapshot.get("uploaderCompany")).isEqualTo("合家美宅");
        var partial = controller.getCase(partialSnapshot).getData();
        assertThat(partial).containsEntry("uploaderName", "运营甲")
                .containsEntry("uploaderCompany", null);

        var legacyList = controller.getCasePage("COMPANY", "PUBLISHED", null, 1, 20).getData().getList().get(0);
        assertThat(legacyList.getUploaderName()).isEqualTo("运营甲（已改名）");
        assertThat(legacyList.getUploaderCompany()).isEqualTo("合家美宅（新名）");
        assertThat(legacyList.getUploader()).isEqualTo("运营甲（已改名）");
        var legacyDetail = controller.getCase(legacy).getData();
        assertThat(legacyDetail).containsEntry("uploaderName", "运营甲（已改名）")
                .containsEntry("uploaderCompany", "合家美宅（新名）")
                .containsEntry("uploader", "运营甲（已改名）");
        var legacyAiDetail = controller.getCase(legacyAi).getData();
        assertThat(legacyAiDetail).containsEntry("uploaderName", "联系人乙")
                .containsEntry("uploaderCompany", "投稿公司");

        // 公开摘要只读取已审核快照：存量空快照不得动态泄露当前后台账号身份。
        var published = catalog.listPublishedCases(
                null, null, null, null, null, null, null, null, null, 20).list();
        assertThat(published).filteredOn(summary -> summary.caseId() == legacy).singleElement().satisfies(summary -> {
            assertThat(summary.caseId()).isEqualTo(legacy);
            assertThat(summary.uploaderName()).isNull();
            assertThat(summary.uploaderCompany()).isNull();
        });
        assertThat(published).filteredOn(summary -> summary.caseId() == legacyAi).singleElement().satisfies(summary -> {
            assertThat(summary.uploaderName()).isNull();
            assertThat(summary.uploaderCompany()).isNull();
        });
        assertThat(published.get(0).getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("uploader", "creatorUserId", "operatorId");
    }

    @Test
    void featuredUploaderAndOfflineDeleteWorkAsOneLifecycle() throws Exception {
        long draft = create("COMPANY", "DRAFT");
        long published = create("COMPANY", "PUBLISHED");
        assertThat(catalog.setHomeFeatured(published, true, 501L)).isTrue();

        mvc.perform(get("/design/v1/cases").param("publicationStatus", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].homeFeatured").value(true))
                .andExpect(jsonPath("$.data.list[0].uploaderName").value("运营甲"))
                .andExpect(jsonPath("$.data.list[0].uploaderCompany").value("合家美宅"))
                .andExpect(jsonPath("$.data.list[0].uploader").value("运营甲"));
        assertThat(catalog.listHomeCases(8))
                .extracting(CaseCatalogService.CaseSummary::caseId).contains(published);

        mvc.perform(delete("/design/v1/cases/" + draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
        assertThat(catalog.offline(published, "组合回归", "501")).isTrue();
        assertThat(catalog.listHomeCases(8))
                .extracting(CaseCatalogService.CaseSummary::caseId).doesNotContain(published);
        mvc.perform(delete("/design/v1/cases/" + published))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        assertThat(catalog.getAdminCase(published)).isEmpty();
    }

    @Test
    void deleteAndFeaturedEndpointsKeepSeparatePermissions() throws Exception {
        Method delete = CaseAdminController.class.getMethod("deleteCase", String.class);
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{caseId}");
        assertThat(delete.getAnnotation(PreAuthorize.class).value()).contains("design:case:delete");

        Method featured = CaseAdminController.class.getMethod("setHomeFeatured", String.class, java.util.Map.class);
        assertThat(featured.getAnnotation(PutMapping.class).value()).containsExactly("/{caseId}/home-featured");
        assertThat(featured.getAnnotation(PreAuthorize.class).value()).contains("design:case:publish");
    }
}
