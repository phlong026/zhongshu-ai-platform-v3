package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.controller.admin.CaseAdminController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T26-04: HTTP keyword binding and literal current-title filtering against migrated PostgreSQL. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseKeywordT26ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private CaseCatalogService catalog;
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
        // 案例分页响应现含上传者展示名（join system_users/account）；两张底座表不在迁移基线内，补最小桩表
        CaseAdminDeleteUploaderContractTest.createUploaderUserTables(jdbc);
        catalog = new CaseCatalogService(ds, new DataSourceTransactionManager(ds));
        CaseAdminController controller = new CaseAdminController();
        ReflectionTestUtils.setField(controller, "caseCatalogService", catalog);
        // Method security is unchanged. This real-controller fixture checks HTTP binding and SQL,
        // not an authenticated production session or a replacement permission implementation.
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE design_case, design_case_version, design_case_asset, case_publication, case_favorite");
    }

    private long create(String title, String description, String source, String publication) {
        long id = catalog.createCompanyCase(501L, title, description, "MODERN", 2, 120,
                null, null, null, null);
        jdbc.update("UPDATE design_case SET source_type = ?, publication_status = ? WHERE id = ?",
                source, publication, id);
        return id;
    }

    @Test
    void chineseKeywordCombinesWithSourceStatusAndStablePagination() {
        long first = create("南方合院", null, "COMPANY", "DRAFT");
        long second = create("北方合院", null, "COMPANY", "DRAFT");
        create("已发布合院", null, "COMPANY", "PUBLISHED");
        create("AI合院", null, "AI", "DRAFT");
        create("独栋别墅", null, "COMPANY", "DRAFT");
        long deleted = create("已删除合院", null, "COMPANY", "DRAFT");
        jdbc.update("UPDATE design_case SET deleted = TRUE WHERE id = ?", deleted);

        assertThat(catalog.countCasesForAdmin(null, null, "合院")).isEqualTo(4);
        assertThat(catalog.countCasesForAdmin("COMPANY", "DRAFT", " 合院 ")).isEqualTo(2);
        var page1 = catalog.pageCasesForAdmin("COMPANY", "DRAFT", "合院", 1, 1);
        var page2 = catalog.pageCasesForAdmin("COMPANY", "DRAFT", "合院", 2, 1);
        assertThat(page1).extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(second);
        assertThat(page2).extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(first);
        assertThat(catalog.pageCasesForAdmin("COMPANY", "DRAFT", "合院", 3, 1)).isEmpty();
    }

    @Test
    void keywordIsLiteralAndDoesNotSearchDescriptionsOrHistoricalTitles() {
        long literal = create("客户'O_100% 合院", "内部检索词", "COMPANY", "DRAFT");
        create("客户OA1000 合院", null, "COMPANY", "DRAFT");
        for (String keyword : List.of("'", "_", "%", "O_100%")) {
            assertThat(catalog.pageCasesForAdmin(null, null, keyword, 1, 20))
                    .extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(literal);
            assertThat(catalog.countCasesForAdmin(null, null, keyword)).isEqualTo(1);
        }
        for (String keyword : List.of("' OR 1=1 --", "内部检索词", "不存在")) {
            assertThat(catalog.countCasesForAdmin(null, null, keyword)).isZero();
            assertThat(catalog.pageCasesForAdmin(null, null, keyword, 1, 20)).isEmpty();
        }

        long renamed = create("旧名称", null, "COMPANY", "DRAFT");
        catalog.updateCase(renamed, 501L, 1L, "新名称", null, "MODERN", 2, 120,
                null, null, null, null);
        assertThat(catalog.countCasesForAdmin(null, null, "旧名称")).isZero();
        assertThat(catalog.pageCasesForAdmin(null, null, "新名称", 1, 20))
                .extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(renamed);
    }

    @Test
    void absentAndBlankKeywordPreserveLegacyFilterResults() {
        create("公司草稿", null, "COMPANY", "DRAFT");
        create("公司已发布", null, "COMPANY", "PUBLISHED");
        create("AI草稿", null, "AI", "DRAFT");
        assertThat(catalog.countCasesForAdmin("COMPANY", "DRAFT", null))
                .isEqualTo(catalog.countCasesForAdmin("COMPANY", "DRAFT"));
        for (String blank : List.of("", " ", "\t\n", "　")) {
            assertThat(catalog.countCasesForAdmin("COMPANY", "DRAFT", blank)).isEqualTo(1);
            assertThat(catalog.pageCasesForAdmin("COMPANY", "DRAFT", blank, 1, 20))
                    .isEqualTo(catalog.pageCasesForAdmin("COMPANY", "DRAFT", 1, 20));
        }
    }

    @Test
    void httpKeywordReachesBothRealCountAndPageQueries() throws Exception {
        create("合院甲", null, "COMPANY", "DRAFT");
        long newest = create("合院乙", null, "COMPANY", "DRAFT");
        create("无关案例", null, "COMPANY", "DRAFT");
        mvc.perform(get("/design/v1/cases")
                        .param("keyword", "合院").param("sourceType", "COMPANY")
                        .param("publicationStatus", "DRAFT").param("pageNo", "1").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].caseId").value(String.valueOf(newest)));
        mvc.perform(get("/design/v1/cases").param("keyword", "没有命中"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());
    }
}
