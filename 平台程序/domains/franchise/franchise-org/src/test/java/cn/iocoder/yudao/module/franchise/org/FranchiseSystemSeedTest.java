package cn.iocoder.yudao.module.franchise.org;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定角色、菜单种子（db/migration/franchise-system）：在 yudao 角色/菜单表的最小副本上执行种子脚本，
 * 断言「岗位 × 权限码」矩阵与 M2 设计 §4.2 完全一致。@post.has 依赖这份数据，集成测试库里没有这几张表。
 */
class FranchiseSystemSeedTest extends FranchisePgIntegrationTest {

    /** M2 设计 §4.2 */
    private static final Set<String> EXPECTED = Set.of(
            "sales franchise:lead:capture", "sales-lead franchise:lead:capture",
            "sales franchise:lead:handle", "sales-lead franchise:lead:handle", "boss franchise:lead:handle", "owner franchise:lead:handle",
            "sales franchise:customer:query", "sales-lead franchise:customer:query", "boss franchise:customer:query", "owner franchise:customer:query",
            "sales franchise:customer:write", "sales-lead franchise:customer:write",
            "sales-lead franchise:customer:transfer", "boss franchise:customer:transfer", "owner franchise:customer:transfer",
            "boss franchise:design-policy:manage",
            "sales franchise:commission:query", "sales-lead franchise:commission:query", "boss franchise:commission:query",
            "owner franchise:commission:query", "hq-design-admin franchise:commission:query",
            "engineer franchise:commission:query", "project-manager franchise:commission:query",
            "sales franchise:commission:submit", "sales-lead franchise:commission:submit",
            "boss franchise:commission:fee-decide",
            "hq-design-admin franchise:commission:design",
            "engineer franchise:commission:receive", "project-manager franchise:commission:receive",
            "sales franchise:design:use", "sales-lead franchise:design:use", "hq-design-admin franchise:design:use",
            "hq-admin platform:announcement:query", "hq-admin platform:announcement:manage",
            "finance platform:announcement:query", "finance-lead platform:announcement:query");

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private DataSource dataSource;

    @Test
    void roleMenuMatrixMatchesTheDesign() throws SQLException {
        createYudaoTables();
        runSeedInCheckSchema();

        List<String> granted = jdbc.queryForList("""
                SELECT r.code || ' ' || m.permission FROM seed_check.system_role_menu rm
                JOIN seed_check.system_role r ON r.id = rm.role_id
                JOIN seed_check.system_menu m ON m.id = rm.menu_id
                WHERE m.type = 3""", String.class);
        Set<String> codes = new HashSet<>(jdbc.queryForList("SELECT code FROM seed_check.system_role", String.class));
        Set<String> templates = new HashSet<>(jdbc.queryForList(
                "SELECT role_code FROM public.pf_post_template WHERE domain = 'franchise'", String.class));
        Integer withoutDirectory = jdbc.queryForObject("""
                SELECT count(DISTINCT rm.role_id) FROM seed_check.system_role_menu rm
                WHERE rm.menu_id BETWEEN 810101 AND 810199 AND NOT EXISTS (
                  SELECT 1 FROM seed_check.system_role_menu d WHERE d.role_id = rm.role_id AND d.menu_id = 810100)""", Integer.class);

        assertThat(new HashSet<>(granted)).isEqualTo(EXPECTED);
        assertThat(granted).doesNotHaveDuplicates();
        assertThat(codes).isEqualTo(templates);
        assertThat(withoutDirectory).isZero();
    }

    private void runSeedInCheckSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO seed_check, public");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/franchise-system/V20261022.851__fr_system_roles_menus.sql"));
            // V853（B2 设计赋能）的 3 条 design:use 授予在 EXPECTED 矩阵内，但脚本清单漏挂——
            // 此前被 Testcontainers 环境故障掩盖（M01-T01 shim 解禁后暴露）。852/854-857 的授予
            // 尚未纳入 EXPECTED 锁定矩阵，属各业务域测试债（fin/content/engineering/performance 域任务补齐）。
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/franchise-system/V20261102.853__design_permissions.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/franchise-system/V20261125.1033__announcement_permissions.sql"));
        }
    }

    /** yudao 三张表的最小副本，放在独立 schema，每次重建；种子脚本经 search_path 写入这里 */
    private void createYudaoTables() {
        jdbc.execute("DROP SCHEMA IF EXISTS seed_check CASCADE");
        jdbc.execute("CREATE SCHEMA seed_check");
        jdbc.execute("""
                CREATE TABLE seed_check.system_role (id int8 PRIMARY KEY, name varchar(30) NOT NULL, code varchar(100) NOT NULL,
                  sort int4 NOT NULL, data_scope int2 NOT NULL DEFAULT 1, data_scope_dept_ids varchar(500) NOT NULL DEFAULT '',
                  status int2 NOT NULL, type int2 NOT NULL, remark varchar(500), creator varchar(64) DEFAULT '',
                  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, updater varchar(64) DEFAULT '',
                  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted int2 NOT NULL DEFAULT 0, tenant_id int8 NOT NULL DEFAULT 0)""");
        jdbc.execute("""
                CREATE TABLE seed_check.system_menu (id int8 PRIMARY KEY, name varchar(50) NOT NULL, permission varchar(100) NOT NULL DEFAULT '',
                  type int2 NOT NULL, sort int4 NOT NULL DEFAULT 0, parent_id int8 NOT NULL DEFAULT 0, path varchar(200) DEFAULT '',
                  icon varchar(100) DEFAULT '#', component varchar(255), component_name varchar(255), status int2 NOT NULL DEFAULT 0,
                  visible bool NOT NULL DEFAULT '1', keep_alive bool NOT NULL DEFAULT '1', always_show bool NOT NULL DEFAULT '1',
                  creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, updater varchar(64) DEFAULT '',
                  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted int2 NOT NULL DEFAULT 0)""");
        jdbc.execute("""
                CREATE TABLE seed_check.system_role_menu (id int8 PRIMARY KEY, role_id int8 NOT NULL, menu_id int8 NOT NULL,
                  creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, updater varchar(64) DEFAULT '',
                  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted int2 NOT NULL DEFAULT 0, tenant_id int8 NOT NULL DEFAULT 0)""");
    }

}
