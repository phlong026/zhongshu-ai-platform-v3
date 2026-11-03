package cn.iocoder.yudao.module.platform.test;

import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.framework.mybatis.config.YudaoMybatisAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.github.yulichang.autoconfigure.MybatisPlusJoinAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 平台层与业务域集成测试基类：真实 PostgreSQL + Flyway + MyBatis-Plus。
 *
 * <p>同一个 JVM 内所有测试类共用一个容器（Testcontainers 的 Ryuk 负责退出时清理）。
 * Flyway 执行测试类路径上 {@code db/migration/pf} 下的全部迁移（平台层各模块共用这一目录），
 * 以及子类通过 {@code platform.test.extra-flyway-locations} 追加的业务域目录。
 * 子类用 {@code @Import} 引入被测的 Service；Mapper 由 yudao 的 {@code @MapperScan} 自动扫描。
 *
 * <p>测试之间不自动清库，用例应使用各自唯一的数据（如不同的 aggregateId）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = PlatformPgIntegrationTest.Application.class,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.banner-mode=off",
                "spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "yudao.info.base-package=cn.iocoder.yudao"
        })
public abstract class PlatformPgIntegrationTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        PG.start();
        createSystemSchemaShim();
    }

    /**
     * system_* 骨架表 shim（仅测试库）：pf/业务域迁移按「只在完整库执行」约定挂 system_menu /
     * system_role_menu 权限种子（如 V20261031.703、V20261101.706），纯 pf 测试库无这些表会令
     * Flyway 失败。此处按迁移实际引用列建最小骨架，使种子语句可执行（JOIN system_role 无匹配
     * 行即零写入）。正式库不经过本基类，迁移历史不受影响。
     */
    private static void createSystemSchemaShim() {
        var sqls = new String[] {
                """
                CREATE TABLE IF NOT EXISTS system_menu (
                    id int8 PRIMARY KEY, name varchar(64), permission varchar(128), type int4, sort int4,
                    parent_id int8, path varchar(256), icon varchar(128), component varchar(256),
                    component_name varchar(128), status int4, visible bool, keep_alive bool, always_show bool,
                    creator varchar(64), updater varchar(64))
                """,
                """
                CREATE TABLE IF NOT EXISTS system_role (
                    id int8 PRIMARY KEY, name varchar(64), code varchar(64), sort int4, data_scope int4,
                    data_scope_dept_ids varchar(256), status int4, type int4, remark varchar(256),
                    creator varchar(64), updater varchar(64), tenant_id int8)
                """,
                """
                CREATE TABLE IF NOT EXISTS system_role_menu (
                    id int8 PRIMARY KEY, role_id int8, menu_id int8,
                    creator varchar(64), updater varchar(64), tenant_id int8)
                """,
                // M01-T06：组织名称落 system_dept（幂等查重与断言联表）；迁移亦引用
                """
                CREATE TABLE IF NOT EXISTS system_dept (
                    id int8 PRIMARY KEY, name varchar(64), parent_id int8 DEFAULT 0,
                    sort int4 DEFAULT 0, status int4 DEFAULT 0, deleted int2 DEFAULT 0,
                    creator varchar(64), updater varchar(64), tenant_id int8 DEFAULT 0)
                """
        };
        try (var conn = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var stmt = conn.createStatement()) {
            for (var sql : sqls) {
                stmt.execute(sql);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("system 骨架表 shim 建表失败（测试库初始化）", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        // 业务域测试用 @TestPropertySource 设置 platform.test.extra-flyway-locations（以逗号开头）追加本域迁移目录
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/pf${platform.test.extra-flyway-locations:}");
        // system_* 骨架 shim 使 schema 非空；baseline 后照常应用 pf 迁移（shim 表与迁移建表无交集）
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    @Import({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            YudaoMybatisAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class,
            MybatisPlusJoinAutoConfiguration.class,
            SpringUtil.class
    })
    public static class Application {
    }

}
