package cn.iocoder.yudao.module.design;

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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10-09 起新增的两个 design 迁移的回滚脚本：回滚有前提，必须在不满足前提时明确拒绝，
 * 而不是抛出让人看不懂的数据库原文，更不能悄悄丢数据。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignMigrationUndoContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design").withUsername("zhongshu").withPassword("zhongshu");

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
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load().migrate();
        catalog = new CaseCatalogService(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_result_version, design_case, design_case_version, design_case_asset CASCADE");
    }

    private void runUndo(String script) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration-undo/design/" + script)) {
            assertThat(in).as("找不到回滚脚本 " + script).isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private boolean flatSelectionNullable() {
        return "YES".equals(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'design_result_version' AND column_name = 'flat_selection_id'", String.class));
    }

    private void insertVersion(long id, long projectId, Long flatSelectionId) {
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids) "
                + "VALUES (?, ?, 1, ?, '[]'::jsonb)", id, projectId, flatSelectionId);
    }

    @Test
    void flatConfirmationUndoKeepsHistoricalRowsAndCanReapply() throws Exception {
        insertVersion(1001, 5001, 7001L);
        try {
            runUndo("V20261012.507__undo_flat_result_confirmation.sql");
            assertThat(jdbc.queryForObject("SELECT flat_selection_id FROM design_result_version WHERE id=1001", Long.class)).isEqualTo(7001L);
        } finally {
            try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/design/V20261012.507__flat_result_confirmation.sql")) {
                jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_result_version WHERE result_line IS NULL", Long.class)).isEqualTo(1L);
    }

    @Test
    void flatConfirmationUndoRefusesToEraseNewVersionFacts() {
        insertVersion(1001, 5001, 7001L);
        jdbc.update("UPDATE design_result_version SET result_line='FLAT' WHERE id=1001");
        assertThatThrownBy(() -> runUndo("V20261012.507__undo_flat_result_confirmation.sql"))
                .hasMessageContaining("无法回滚").hasMessageContaining("保留扩展表结构");
        assertThat(jdbc.queryForObject("SELECT result_line FROM design_result_version WHERE id=1001", String.class)).isEqualTo("FLAT");
    }

    // ===== 506：flat_selection_id 可空 → 恢复 NOT NULL =====

    @Test
    void undoElevationIndependentRefusesWithActionableMessageWhileSoloElevationVersionsExist() throws Exception {
        insertVersion(1001, 5001, null);   // 纯立面项目产生的最终版本
        insertVersion(1002, 5002, null);
        insertVersion(1003, 5003, 7001L);  // 带平面选择的存量版本
        assertThatThrownBy(() -> runUndo("V20261009.506__undo_elevation_independent.sql"))
                .as("存在纯立面版本时必须拒绝回滚，并说清楚原因与条数")
                .hasMessageContaining("无法回滚")
                .hasMessageContaining("2 条")
                .hasMessageContaining("纯立面");
        assertThat(flatSelectionNullable()).as("拒绝时不得改动表结构").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_result_version", Long.class))
                .as("拒绝时不得丢数据").isEqualTo(3);
    }

    @Test
    void undoElevationIndependentSucceedsOnceSoloElevationVersionsAreHandled() throws Exception {
        insertVersion(1003, 5003, 7001L);
        runUndo("V20261009.506__undo_elevation_independent.sql");
        assertThat(flatSelectionNullable()).isFalse();
        // 回滚后重新放开约束（等价于重新发布 506），表仍可用
        jdbc.execute("ALTER TABLE design_result_version ALTER COLUMN flat_selection_id DROP NOT NULL");
        assertThat(flatSelectionNullable()).isTrue();
    }

    // ===== 505：面宽/进深 NUMERIC(4,1) → INT（已知有损，用测试把行为固定下来）=====

    @Test
    void undoCaseDimensionDecimalRoundsToIntegerMeters() throws Exception {
        long caseId = catalog.createCompanyCase(1L, "尺寸案例", null, "MODERN", 2, 120,
                new BigDecimal("12.6"), new BigDecimal("9.4"), null, null);
        runUndo("V20261009.505__undo_case_dimension_decimal.sql");
        var row = jdbc.queryForMap("SELECT v.face_width, v.depth FROM design_case_version v "
                + "JOIN design_case c ON c.current_version_id = v.id WHERE c.id = ?", caseId);
        assertThat(((Number) row.get("face_width")).intValue()).as("12.6 四舍五入为 13").isEqualTo(13);
        assertThat(((Number) row.get("depth")).intValue()).as("9.4 四舍五入为 9").isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'design_case_version' AND column_name = 'face_width'", String.class))
                .isEqualTo("integer");
    }

    // ===== 601：home_featured 与 uploader 展示列删除（undo 会丢弃上传人展示数据，重做需重新录入）=====

    @Test
    void undoCaseHomeFeaturedAndUploaderDropsAllFourColumns() throws Exception {
        long caseId = catalog.createCompanyCase(1L, "回滚上传人", null, "MODERN", 2, 120,
                null, null, null, null);
        jdbc.update("UPDATE design_case SET uploader_name = '张三', uploader_company = '合家美宅', "
                + "home_featured = TRUE, home_featured_at = now() WHERE id = ?", caseId);
        try {
            runUndo("V20261016.601__undo_case_home_featured_and_uploader.sql");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'design_case' AND column_name IN "
                    + "('home_featured', 'home_featured_at', 'uploader_name', 'uploader_company')", Long.class))
                    .as("四列均已删除").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_case WHERE id = ?", Long.class, caseId))
                    .as("案例本体保留；上传人展示数据被丢弃，重做迁移后需重新录入").isEqualTo(1L);
        } finally {
            // 重新应用 601，避免同容器的其他用例（依赖新列的 createCompanyCase 插入路径）受影响
            try (var in = getClass().getClassLoader()
                    .getResourceAsStream("db/migration/design/V20261016.601__case_home_featured_and_uploader.sql")) {
                assertThat(in).as("找不到迁移脚本 V20261016.601").isNotNull();
                jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        // 重做后仅恢复列结构：上传人数据不回填为原值（ADD COLUMN DEFAULT '' 使存量行为空串，
        // 前端按空回退默认来源标签），重做迁移后需重新录入
        assertThat(jdbc.queryForObject("SELECT uploader_name FROM design_case WHERE id = ?", String.class, caseId))
                .isEmpty();
    }
}
