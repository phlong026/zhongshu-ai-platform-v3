package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M01-T04（Codex R1 P2-4）：同域重复注册的交接 Port 是装配错误——静默丢实现会导致该域既不扫描
 * 也不转移。编排器必须显式失败，不得静默覆盖。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, DepartureHandoverService.class,
        DepartureHandoverDuplicatePortTest.Config.class})
class DepartureHandoverDuplicatePortTest extends PlatformPgIntegrationTest {

    private static final java.util.concurrent.atomic.AtomicLong USER_IDS =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000_000L);

    static class SilentPort implements DepartureHandoverPort {
        private final String domain;

        SilentPort(String domain) {
            this.domain = domain;
        }

        @Override
        public String domain() {
            return domain;
        }

        @Override
        public String label() {
            return domain;
        }

        @Override
        public long scanInFlight(long fromUserId, long orgId) {
            return 0;
        }

        @Override
        public void validateReceiver(long toUserId, long orgId) {
        }

        @Override
        public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
            return 0;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        SilentPort crmPortOne() {
            return new SilentPort("franchise-crm");
        }

        @Bean
        SilentPort crmPortTwo() {
            return new SilentPort("franchise-crm"); // 同域重复
        }

        @Bean
        FakeSystemDirectory fakeSystemDirectory() {
            return new FakeSystemDirectory();
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private DepartureHandoverService handoverService;
    @Resource
    private FakeSystemDirectory directory;
    @Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                + "('t-sales','test','t-store','sales',FALSE,NULL) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("t-sales", 501L);
    }

    @Test
    void duplicateDomainRegistrationFailsLoudly() {
        long orgId = orgService.createOrg("t-store", "重复域门店", null);
        long userA = USER_IDS.incrementAndGet();
        long userB = USER_IDS.incrementAndGet();
        directory.users.add(userA);
        directory.users.add(userB);
        staffPostService.assignPost(userA, orgId, "t-sales", null, 99L);
        staffPostService.assignPost(userB, orgId, "t-sales", null, 99L);

        assertThatThrownBy(() -> handoverService.handover(orgId, userA, userB, 99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("franchise-crm");
    }
}
