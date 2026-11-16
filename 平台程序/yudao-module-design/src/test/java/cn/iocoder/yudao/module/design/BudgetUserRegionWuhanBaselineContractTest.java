package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetAccountPriceService;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.budget.BudgetPriceBookService;
import cn.iocoder.yudao.module.design.budget.BudgetPriceProfileService;
import cn.iocoder.yudao.module.design.budget.BudgetPriceResolver;
import cn.iocoder.yudao.module.design.budget.ItemizedBudgetService;
import cn.iocoder.yudao.module.design.controller.app.AppAccountPriceController;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetCatalogController;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_PRICE_PROFILE_STALE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-05：自定义地区默认基准价回落武汉市公布价（覆盖价仍优先）。
 * 背景：用户自建地区（budget_user_region）自身无价目行，此前未覆盖项全部 MISSING_PRICE
 * 导致"预算不完整、仅展示已计价小计"（生产案例：黄岗）。修复后列表/目录/测算三处统一按武汉基准。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetUserRegionWuhanBaselineContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:17-alpine");
    private static final long PROJECT = 9007199254741101L;
    private static final long VERSION = 9007199254742101L;
    private static final long REQUIREMENT = 9007199254743101L;
    private static final long TEST_REGION = 810011L;
    private static final long WUHAN_REGION = 810012L;
    private static final long TOTAL = 48_202_000L;
    private static final String BASE = "/design/v1/budget";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> OPTIONS = List.of("208001", "208006", "208009", "208012", "208015",
            "208020", "208023", "208026", "208029", "208030", "208032", "208034", "208035");
    private static final long[] PRICES = {52_000, 98_000, 52_000, 12_000, 1_800_000, 49_000,
            12_000, 12_000, 12_000, 6_000, 8_500, 500_000, 500_000};
    private SimpleDriverDataSource ds;
    private DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private ItemizedBudgetService budgets;
    private BudgetAccountPriceService accounts;
    private BudgetPriceProfileService profiles;
    private BudgetPriceBookService priceBooks;
    private MockMvc mvc;
    private final cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> "owner".equals(token)
            ? java.util.Optional.of(new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(1, "test", "owner", false))
            : java.util.Optional.empty();

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        transactions = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        projects = new DesignProjectService(ds, transactions, mock(cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort.class),
                null, null, null, mock(cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort.class));
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE budget_quote, budget_line, budget_revision, budget_estimate, budget_item_price, budget_account_price, budget_price_book_version, "
                + "budget_region, budget_user_region, budget_app_command, budget_rule_version, design_project, design_requirement_snapshot, "
                + "design_result_version, audit_event");
        jdbc.update("DELETE FROM budget_option WHERE item_id IN (SELECT id FROM budget_item WHERE source = 'CUSTOM_TEMPLATE')");
        jdbc.update("DELETE FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'");
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE, deleted = FALSE");
        jdbc.update("UPDATE budget_option SET enabled = TRUE, deleted = FALSE");
        jdbc.update("INSERT INTO budget_region (id, code, name, enabled) VALUES (?, 'TEST_A', '金样测试地区', TRUE)", TEST_REGION);
        jdbc.update("INSERT INTO budget_region (id, code, name, enabled) VALUES (?, 'WUHAN', '武汉市', TRUE)", WUHAN_REGION);
        for (int i = 0; i < OPTIONS.size(); i++) price(820101L + i, TEST_REGION, Long.parseLong(OPTIONS.get(i)), PRICES[i]);
        // 武汉只有 208001 的价（验证回落只对有价项生效），其余项武汉仍缺价
        price(820201L, WUHAN_REGION, 208001L, 60_000);
        jdbc.update("INSERT INTO budget_user_region (id, user_id, code, name, tenant_id, creator, updater) VALUES (?, 1, 'MYHOME', '黄岗', 0, '1', '1')", 910001L);
        project(PROJECT, 1, REQUIREMENT, inputs("MYHOME"));
        version(VERSION, PROJECT);
        accounts = new BudgetAccountPriceService(ds, transactions, new JdbcAuditPort(ds));
        profiles = new BudgetPriceProfileService(ds, transactions, new JdbcAuditPort(ds));
        priceBooks = new BudgetPriceBookService(ds, transactions, new JdbcAuditPort(ds));
        budgets = new ItemizedBudgetService(ds, transactions, projects, new JdbcAuditPort(ds));
        var priceController = new AppAccountPriceController();
        ReflectionTestUtils.setField(priceController, "accountPriceService", accounts);
        ReflectionTestUtils.setField(priceController, "budgetCatalogService",
                new BudgetCatalogService(ds, transactions, new JdbcAuditPort(ds)));
        ReflectionTestUtils.setField(priceController, "identitySessionPort", identities);
        ReflectionTestUtils.setField(priceController, "verifiedAccountRateLimiter",
                mock(cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter.class));
        var catalogController = new AppBudgetCatalogController();
        ReflectionTestUtils.setField(catalogController, "budgetCatalogService",
                new BudgetCatalogService(ds, transactions, new JdbcAuditPort(ds)));
        ReflectionTestUtils.setField(catalogController, "identitySessionPort", identities);
        mvc = MockMvcBuilders.standaloneSetup(priceController, catalogController)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON)).build();
    }

    @Test
    void customRegionListAndCatalogFallBackToWuhanPublishedPrices() throws Exception {
        // 单价列表：208001 基准=武汉 60000（不再 MISSING）
        var prices = ok(get(BASE + "/my-prices?regionCode=MYHOME"));
        assertThat(option(prices, "208001").path("baselinePriceCents").asLong()).isEqualTo(60_000L);
        assertThat(option(prices, "208001").path("priceSource").asText()).isEqualTo("WUHAN_FALLBACK");
        // 武汉也没有的项（208006）仍 MISSING
        assertThat(option(prices, "208006").path("priceSource").asText()).isEqualTo("MISSING");
        // 目录：未覆盖但武汉有价 → AVAILABLE（此前只有覆盖价才算可选）
        var catalog = ok(get(BASE + "/options?regionCode=MYHOME"));
        assertThat(option(catalog, "208001").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(option(catalog, "208006").path("availability").asText()).isEqualTo("MISSING_PRICE");
    }

    @Test
    void customRegionEstimateUsesWuhanBaselineAndOverrideWins() {
        // 测算：208001 按武汉 60000 计价（覆盖 52000 的 TEST_A 价不参与），其余武汉缺价项仍待补
        var result = tree(budgets.create(1, PROJECT, body(), "wuhan-baseline"));
        assertThat(result.path("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_line WHERE option_id = 208001", Long.class)).isEqualTo(60_000L);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_line WHERE option_id = 208006", String.class)).isEqualTo("MISSING_PRICE");
        // 覆盖价优先于武汉基准
        jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, status) VALUES (1, 1, 208001, 88_000, 'ACTIVE')");
        budgets.create(1, PROJECT, body(), "wuhan-override");
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_line WHERE option_id = 208001 AND revision_id = "
                + "(SELECT max(id) FROM budget_revision)", Long.class)).isEqualTo(88_000L);
    }

    @Test
    void profileCopiesWuhanPricesWithCoefficientAndIsolatesOtherProfiles() {
        var profile = profiles.create(1, Map.of("name", "黄冈配置", "coefficient", 0.95));
        assertThat(profile.priceVersion()).isEqualTo(1);
        assertThat(profile.itemCount()).isEqualTo(1);
        assertThat(profile.missingCount()).isEqualTo(profile.totalCount() - 1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, profile.code())).isEqualTo(57_000L);
        assertThat(jdbc.queryForObject("SELECT price_source FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", String.class, profile.code())).isEqualTo("COEFFICIENT");

        var second = profiles.create(1, Map.of("name", "第二配置", "coefficient", 1.0));
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, second.code())).isEqualTo(60_000L);

        // 改 A 配置不影响 B；手工价 + 版本递增
        profiles.upsertOne(1, profile.code(), 208001L, Map.of("unitPriceCents", 50_000));
        assertThat(profiles.get(1, profile.code()).priceVersion()).isEqualTo(2);
        assertThat(profiles.get(1, profile.code()).manualCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, second.code())).isEqualTo(60_000L);

        var copy = profiles.create(1, Map.of("name", "副本配置", "coefficient", 0.9, "copyFrom", profile.code()));
        assertThat(copy.itemCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, copy.code())).isEqualTo(50_000L);

        var prices = profiles.listPrices(1, profile.code());
        assertThat(prices.profile().priceVersion()).isEqualTo(2);
        assertThat(prices.groups().stream().flatMap(group -> group.options().stream())
                .filter(option -> "208001".equals(option.optionId())).findFirst().orElseThrow().priceSource())
                .isEqualTo("PROFILE_MANUAL");
        assertThat(prices.groups().stream().flatMap(group -> group.options().stream())
                .filter(option -> "208006".equals(option.optionId())).findFirst().orElseThrow().priceSource())
                .isEqualTo("MISSING");

        // 批量保存：服务端按 base × 系数重算，忽略客户端伪造的 1 分
        var saved = profiles.save(1, profile.code(), Map.of("expectedVersion", 2, "coefficient", 0.9,
                "items", List.of(Map.of("optionId", "208001", "unitPriceCents", 1, "source", "COEFFICIENT"))));
        assertThat(saved.priceVersion()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, profile.code())).isEqualTo(54_000L);
        assertThatThrownBy(() -> profiles.save(1, profile.code(), Map.of("expectedVersion", 2, "coefficient", 0.9,
                "items", List.of(Map.of("optionId", "208001", "unitPriceCents", 1, "source", "COEFFICIENT")))))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BUDGET_PRICE_PROFILE_STALE.getCode()));

        // 管理端不暴露用户配置明细
        assertThat(accounts.pageByAccount(1, "TEST_A", 1, 20).getList()).isEmpty();
    }

    @Test
    void wuhanPriceBookVersionsDriveNewProfilesAndManualSync() {
        var v1 = priceBooks.latest("WUHAN");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.prices()).containsKey(208001L);

        var profile = profiles.create(1, Map.of("name", "版本配置", "coefficient", 0.95));
        assertThat(profile.baseVersion()).isEqualTo(1);

        // 平台新增一条武汉 PUBLISHED 价，但尚未生成新版本：版本仍是 v1，dirty=true
        price(820202L, WUHAN_REGION, 208006L, 98_000L);
        assertThat(priceBooks.current("WUHAN").dirty()).isTrue();
        assertThat(priceBooks.current("WUHAN").version()).isEqualTo(1);

        var v2 = priceBooks.publish(99, "WUHAN", Map.of("expectedVersion", 1, "note", "新增结构价"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.dirty()).isFalse();
        assertThat(priceBooks.latest("WUHAN").prices()).containsKey(208006L);

        // 已建配置仍锁定 v1，提示可同步；同步后锁定 v2，且非手工项按最新基准价重算
        assertThat(profiles.get(1, profile.code()).baseVersion()).isEqualTo(1);
        var stale = profiles.listPrices(1, profile.code());
        assertThat(stale.profile().baseVersion()).isEqualTo(1);
        assertThat(stale.profile().latestBaseVersion()).isEqualTo(2);
        assertThat(stale.profile().baseStale()).isTrue();

        var synced = profiles.copy(1, profile.code(), Map.of("coefficient", 0.95, "mode", "COEFFICIENT_ONLY"));
        assertThat(synced.profile().baseVersion()).isEqualTo(2);
        assertThat(synced.profile().itemCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND status = 'ACTIVE'", Integer.class, profile.code())).isEqualTo(2);

        var newer = profiles.create(1, Map.of("name", "新版本配置", "coefficient", 1.0));
        assertThat(newer.baseVersion()).isEqualTo(2);
        assertThat(newer.itemCount()).isEqualTo(2);
    }

    @Test
    void coefficientOnlyRecalcPreservesManualPrices() {
        var profile = profiles.create(1, Map.of("name", "手工保留", "coefficient", 0.95));
        profiles.upsertOne(1, profile.code(), 208001L, Map.of("unitPriceCents", 50_000));
        var result = profiles.copy(1, profile.code(), Map.of("coefficient", 0.9, "mode", "COEFFICIENT_ONLY"));
        assertThat(result.preservedManualCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, profile.code())).isEqualTo(50_000L);
        var all = profiles.copy(1, profile.code(), Map.of("coefficient", 0.9, "mode", "ALL"));
        assertThat(all.updatedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_account_price WHERE region_scope = 'USER' "
                + "AND region_code = ? AND option_id = 208001", Long.class, profile.code())).isEqualTo(54_000L);
    }

    private Map<String, Object> body() {
        var result = new LinkedHashMap<String, Object>();
        result.put("resultVersionId", Long.toString(VERSION));
        result.put("inputOverrides", Map.of());
        result.put("optionIds", OPTIONS);
        return result;
    }

    private Map<String, Object> inputs(String regionCode) {
        var result = new LinkedHashMap<String, Object>();
        result.put("regionCode", regionCode);
        result.put("footprintArea", "120");
        result.put("floorCount", 2);
        result.put("roofArea", "112");
        result.put("quantities", new LinkedHashMap<>(Map.of("DOOR_HOUSEHOLDS", "1", "CULTURE_STONE_LENGTH", "44",
                "LIGHTING_WASHER_LENGTH", "20", "LIGHTING_STRIP_LENGTH", "30", "WATERPROOF_AREA", "20")));
        return result;
    }

    private void project(long id, long userId, long requirementId, Map<String, Object> inputs) {
        jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (?, ?, 'SELF_UPLOAD')", id, userId);
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                        + "VALUES (?, ?, 1, CAST(? AS jsonb), '2026-01-01T00:00:00Z')", requirementId, id,
                tree(Map.of("budgetInputs", inputs, "prompt", "private fixture prompt")).toString());
    }

    private void version(long id, long projectId) {
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids, config_snapshot, create_time) "
                + "VALUES (?, ?, 1, 1, '[]', CAST('{}' AS jsonb), '2026-01-02T00:00:00Z')", id, projectId);
    }

    private void price(long id, long regionId, long optionId, long cents) {
        jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id, unit_price_cents, status, effective_at, published_by, published_at) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', '2020-01-01T00:00:00Z', 99, now())", id, regionId, optionId, cents);
    }

    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        // 不能走 tree(String)：会把响应字符串再序列化一层变成文本节点
        return JSON.readTree(mvc.perform(request.header("Authorization", "Bearer owner")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode option(JsonNode data, String optionId) {
        String container = data.has("groups") ? "groups" : "items";
        for (JsonNode group : data.path(container)) {
            for (JsonNode option : group.path("options")) if (option.path("optionId").asText().equals(optionId)) return option;
        }
        throw new AssertionError("Missing option " + optionId + ": " + data);
    }

    private JsonNode tree(Object value) {
        try {
            return JSON.readTree(JSON.writeValueAsBytes(value));
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not serialize the test fixture to JSON", exception);
        }
    }
}
