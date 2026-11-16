package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.StubWechatIdentityAdapter;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除账号后同一微信重新注册（V20261016.109 契约）。
 *
 * 背景（2026-10-16 线上缺陷）：运营删除账号只软删 account 行，wechat_identity 的 (appid, openid)
 * 映射残留——该微信再登录解析到僵尸账号，兑换激活码报「账号已停用或关闭」且永久无法重新注册。
 * 修复契约：① (appid, openid) 唯一索引为部分索引（仅约束 deleted=FALSE 行，迁移 V109）；
 * ② 删除流程（AdminDataDeleteController）软删账号时须同步软删其身份映射并吊销会话。
 * 本测试锁定 ①+② 之后的核心不变量：身份映射软删后，同一微信 resolveAccount 必须能新建账号。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountReRegistrationAfterDeleteContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = cn.iocoder.yudao.module.platform.test.TestPostgres.database(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AccountLoginService loginService;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/identity")
                .load()
                .migrate();

        this.loginService = new AccountLoginService(dataSource,
                new DataSourceTransactionManager(dataSource),
                new StubWechatIdentityAdapter(), new UserSessionService(dataSource,
                        new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE user_session, design_access_grant, access_code_redemption, "
                + "design_access_code, design_access_code_batch, wechat_identity, account, "
                + "one_time_delivery_ticket");
    }

    @Test
    void migration109MakesAppidOpenidUniqueIndexPartialOnNonDeletedRows() {
        String indexdef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_wechat_identity_appid_openid'",
                String.class);
        assertThat(indexdef).contains("WHERE (deleted = false)");
    }

    @Test
    void identitySoftDeletedAllowsSameWechatToResolveFreshAccount() {
        long oldAccountId = loginService.resolveAccount("wx-app", "openid-restart", null);
        jdbc.update("UPDATE account SET deleted = TRUE, update_time = now() WHERE id = ?", oldAccountId);

        // 模拟删除流程（AdminDataDeleteController）：软删身份映射 + 吊销会话（会话无则无操作）
        jdbc.update("UPDATE wechat_identity SET deleted = TRUE, update_time = now() "
                + "WHERE account_id = ? AND deleted = FALSE", oldAccountId);

        long newAccountId = loginService.resolveAccount("wx-app", "openid-restart", null);
        assertThat(newAccountId).isNotEqualTo(oldAccountId);

        // 新账号在用且未删；旧账号保持已删；新身份行未删
        assertThat(queryAccountDeleted(newAccountId)).isFalse();
        assertThat(queryAccountDeleted(oldAccountId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wechat_identity WHERE appid = 'wx-app' AND openid = 'openid-restart' AND deleted = FALSE",
                Long.class)).isEqualTo(1);
    }

    @Test
    void zombieIdentityStillResolvesDeletedAccountSoDeleteFlowMustSoftDeleteIt() {
        long accountId = loginService.resolveAccount("wx-app", "openid-zombie", null);
        jdbc.update("UPDATE account SET deleted = TRUE, update_time = now() WHERE id = ?", accountId);

        // 只删账号、不删身份（缺陷行为）：resolveAccount 复用僵尸账号——
        // 正是兑换层报「账号已停用或关闭」的前置状态，故删除流程必须软删身份。
        Optional<Long> found = loginService.findAccountId("wx-app", "openid-zombie");
        assertThat(found).contains(accountId);
    }

    private boolean queryAccountDeleted(long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted FROM account WHERE id = ?", Boolean.class, accountId));
    }
}
