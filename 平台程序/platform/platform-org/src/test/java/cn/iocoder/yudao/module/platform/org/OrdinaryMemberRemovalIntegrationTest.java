package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, DepartureHandoverService.class,
        OrdinaryMemberRemovalIntegrationTest.Config.class})
class OrdinaryMemberRemovalIntegrationTest extends PlatformPgIntegrationTest {
    private static final AtomicLong USERS = new AtomicLong(3_000_000_000L);
    private static final List<String> DOMAINS = List.of("franchise-crm", "franchise-commission",
            "franchise-engineering", "franchise-finance");

    static class WorkPort implements DepartureHandoverPort {
        final String domain;
        final JdbcTemplate jdbc;
        boolean failScan;
        Runnable afterScan;
        WorkPort(String domain, JdbcTemplate jdbc) { this.domain = domain; this.jdbc = jdbc; }
        @Override public String domain() { return domain; }
        @Override public String label() { return domain; }
        @Override public long scanInFlight(long user, long org) {
            if (failScan) jdbc.execute("SELECT * FROM auth03_missing_table");
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM auth03_work WHERE domain=? AND org_id=? AND owner_id=?", Long.class, domain, org, user);
            Runnable hook = afterScan;
            afterScan = null;
            if (hook != null) hook.run();
            return count;
        }
        @Override public void validateReceiver(long user, long org) { }
        @Override public int bulkTransfer(long from, long to, long org, long operator) {
            return jdbc.update("UPDATE auth03_work SET owner_id=? WHERE domain=? AND org_id=? AND owner_id=?", to, domain, org, from);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean FakeSystemDirectory directory() { return new FakeSystemDirectory(); }
        @Bean WorkPort crm(JdbcTemplate jdbc) { return new WorkPort(DOMAINS.get(0), jdbc); }
        @Bean WorkPort commission(JdbcTemplate jdbc) { return new WorkPort(DOMAINS.get(1), jdbc); }
        @Bean WorkPort engineering(JdbcTemplate jdbc) { return new WorkPort(DOMAINS.get(2), jdbc); }
        @Bean WorkPort finance(JdbcTemplate jdbc) { return new WorkPort(DOMAINS.get(3), jdbc); }
    }

    @Resource private JdbcTemplate jdbc;
    @Resource private OrgService orgService;
    @Resource private StaffPostService posts;
    @Resource private DepartureHandoverService handovers;
    @Resource private FakeSystemDirectory directory;
    @Resource private WorkPort crm;
    @Resource private WorkPort finance;
    private long org, from, to, post;

    @BeforeEach
    void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth03_work(id bigserial PRIMARY KEY,domain text,org_id bigint,owner_id bigint)");
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('audit-removal','test','撤权测试') ON CONFLICT DO NOTHING");
        for (String role : List.of("audit-removal-staff", "audit-removal-other")) {
            jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES(?,'test','audit-removal',false) ON CONFLICT DO NOTHING", role);
            directory.roleIdsByCode.put(role, role.endsWith("staff") ? 701L : 702L);
        }
        org = orgService.createOrg("audit-removal", "交接测试", null);
        from = USERS.incrementAndGet(); to = USERS.incrementAndGet();
        directory.users.add(from); directory.users.add(to);
        post = posts.assignPost(from, org, "audit-removal-staff", null, 99L);
        posts.assignPost(to, org, "audit-removal-staff", null, 99L);
        finance.failScan = false;
        finance.afterScan = null;
        crm.afterScan = null;
    }

    @Test
    void employeeSuspensionShowsAllFourPendingDomainsAndBackfillTransfersEveryResponsibility() {
        DOMAINS.forEach(this::addWork);
        var result = handovers.emergencyRevoke(org, from, 99L);
        assertThat(result.items()).hasSize(4).allSatisfy(item -> {
            assertThat(item.inFlight()).isEqualTo(1);
            assertThat(item.status()).isEqualTo(DepartureHandoverService.ST_PENDING_UNASSIGNED);
        });
        assertThat(posts.findActivePost(from, post)).isEmpty();
        assertThat(handovers.listPending(org)).hasSize(4);

        var backfill = handovers.handover(org, from, to, 99L);
        assertThat(backfill.items()).allSatisfy(item -> assertThat(item.transferred()).isEqualTo(1));
        assertThat(workCount(from)).isZero();
        assertThat(workCount(to)).isEqualTo(4);
        assertThat(handovers.listPending(org)).isEmpty();
    }

    @Test
    void failedDomainScanLeavesRevocationCommittedAndUnknownWorkRecoverable() {
        DOMAINS.forEach(this::addWork);
        finance.failScan = true;
        var result = handovers.emergencyRevoke(org, from, 99L);
        assertThat(posts.findActivePost(from, post)).isEmpty();
        assertThat(result.items()).filteredOn(item -> item.domain().equals(finance.domain))
                .singleElement().satisfies(item -> assertThat(item.status()).isEqualTo(DepartureHandoverService.ST_SCAN_FAILED));
        assertThat(handovers.listPending(org)).filteredOn(row -> row.domain().equals(finance.domain))
                .singleElement().satisfies(row -> assertThat(row.inFlight()).isEqualTo(-1));

        finance.failScan = false;
        handovers.handover(org, from, to, 99L);
        assertThat(workCount(to)).isEqualTo(4);
        assertThat(handovers.listPending(org)).isEmpty();
    }

    @Test
    void ordinarySingleRevocationRejectsUnfinishedWorkInEachDomain() {
        for (String domain : DOMAINS) {
            addWork(domain);
            assertError(() -> handovers.revokePost(post, 99L), STAFF_HANDOVER_REQUIRED);
            assertThat(posts.getPost(post)).isPresent();
            jdbc.update("DELETE FROM auth03_work WHERE org_id=?", org);
        }
        assertThat(handovers.listPending(org)).isEmpty();
        handovers.revokePost(post, 99L);
        assertThat(posts.getPost(post)).isEmpty();
    }

    @Test
    void unconfirmedScanKeepsBothPostsAndSuccessfulSingleRemovalKeepsTheOtherPost() {
        long other = posts.assignPost(from, org, "audit-removal-other", null, 99L);
        finance.failScan = true;
        assertError(() -> handovers.revokePost(post, 99L), STAFF_HANDOVER_UNCONFIRMED);
        assertThat(posts.listActivePosts(from)).hasSize(2);

        finance.failScan = false;
        handovers.revokePost(post, 99L);
        assertThat(posts.getPost(post)).isEmpty();
        assertThat(posts.getPost(other)).isPresent();
    }

    @Test
    void aResponsibilityCommittedOutsideTheUserLockIsFoundByThePostCommitCheck() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            // 真实独立连接模拟未加入用户锁协议的业务写入；不能声称单边锁消灭这一窗口。
            crm.afterScan = () -> {
                try { executor.submit(() -> addWork(crm.domain)).get(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
            };
            handovers.revokePost(post, 99L);
            assertThat(posts.getPost(post)).isEmpty();
            assertThat(handovers.listPending(org)).singleElement().satisfies(row -> {
                assertThat(row.fromUserId()).isEqualTo(from);
                assertThat(row.domain()).isEqualTo(crm.domain);
                assertThat(row.inFlight()).isEqualTo(1);
            });
        } finally { executor.shutdownNow(); }
    }

    @Test
    void postCommitScanFailurePersistsAnUnknownRowAndReportsThatThePostWasAlreadyRemoved() {
        finance.afterScan = () -> finance.failScan = true;
        assertError(() -> handovers.revokePost(post, 99L), STAFF_POST_REVOKED_VERIFY_REQUIRED);
        assertThat(posts.getPost(post)).isEmpty();
        assertThat(handovers.listPending(org)).singleElement().satisfies(row -> {
            assertThat(row.domain()).isEqualTo(finance.domain);
            assertThat(row.inFlight()).isEqualTo(-1);
        });
    }

    @Test
    void legacyFormerEmployeeWithoutPendingRowsCanStillCompleteBackfill() {
        DOMAINS.forEach(this::addWork);
        posts.revokeAllInOrg(from, org, 99L); // 模拟修复前只撤权的旧数据
        assertThat(handovers.listPending(org)).isEmpty();

        handovers.handover(org, from, to, 99L);

        assertThat(workCount(from)).isZero();
        assertThat(workCount(to)).isEqualTo(4);
        assertThat(handovers.listPending(org)).isEmpty();
    }

    private void addWork(String domain) {
        jdbc.update("INSERT INTO auth03_work(domain,org_id,owner_id) VALUES(?,?,?)", domain, org, from);
    }

    private long workCount(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth03_work WHERE org_id=? AND owner_id=?", Long.class, org, user);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode()).isEqualTo(code.getCode());
    }
}
