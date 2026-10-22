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
 * Flyway 执行测试类路径上 {@code db/migration/pf}（平台层）与各业务域迁移目录下的全部迁移。
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
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        // 业务域迁移目录也一并登记（不在类路径上的目录 Flyway 只告警），新增业务域时在此追加
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/pf,classpath:db/migration/franchise");
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
