package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 C 员工业务权限矩阵验收（24 号 §七 OrgMemberPermissionIntegrationTest）：
 * 无行=全允许 / 空数组=全禁 / 部分准入；准入强制发生在预扣事务内、外呼之前。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgMemberPermissionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ORG = 4001L;
    private static final long USER = 7L;

    private JdbcTemplate jdbc;
    private OrgPointService service;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load().migrate();
        service = new OrgPointService(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_quota, org_member_permission");
        service.ensurePool(ORG);
        service.adjust(ORG, 10_000, "adjust:perm-base", "测试充值", 1L);
    }

    private OrgPointPort.ReserveCommand reserve(String key, String subject) {
        return new OrgPointPort.ReserveCommand(key, ORG, USER, null, subject, 1, "content_task", 1L);
    }

    @Test
    void noRowMeansAllAllowed() {
        service.reserve(reserve("content:p1", "copy"));
        service.reserve(reserve("content:p2", "digital"));
    }

    @Test
    void partialAllowListEnforced() {
        service.setMemberPermission(ORG, USER, List.of("copy"));
        service.reserve(reserve("content:p3", "copy"));
        assertThatThrownBy(() -> service.reserve(reserve("content:p4", "digital")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未被授权");
    }

    @Test
    void emptyListDeniesAll() {
        service.setMemberPermission(ORG, USER, List.of());
        assertThatThrownBy(() -> service.reserve(reserve("content:p5", "copy")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void clearRestoresAllAllowed() {
        service.setMemberPermission(ORG, USER, List.of("copy"));
        service.clearMemberPermission(ORG, USER);
        assertThat(service.memberPermissions(ORG)).isEmpty();
        service.reserve(reserve("content:p6", "digital"));
    }

    @Test
    void normalizationSortsAndDedups() {
        service.setMemberPermission(ORG, USER, List.of("digital", "copy", "copy"));
        assertThat(service.memberPermissions(ORG).get(0).allowedSubjects())
                .containsExactly("copy", "digital");
    }
}
