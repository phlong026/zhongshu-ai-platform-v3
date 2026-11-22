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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH-02：真实 PostgreSQL / MyBatis 事务，覆盖四种成员撤权入口及并发的最后管理岗位保护。 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, DepartureHandoverService.class,
        MemberRevocationPolicyIntegrationTest.Config.class})
class MemberRevocationPolicyIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong USER_IDS = new AtomicLong(2_000_000_000L);
    private static final String MANAGE = "platform:org:manage";
    private static final String MANAGER = "audit-manager";
    private static final String SECOND_MANAGER = "audit-second-manager";
    private static final String EMPLOYEE = "audit-employee";

    @TestConfiguration
    static class Config {
        @Bean FakeSystemDirectory directory() { return new FakeSystemDirectory(); }
        @Bean RecordingWallet wallet() { return new RecordingWallet(); }
        @Bean RecordingPort port() { return new RecordingPort(); }
    }

    static class RecordingWallet implements WalletSessionRevocation {
        final List<Long> revokedUsers = new CopyOnWriteArrayList<>();
        @Override public void revokeForUserOrgs(long userId, List<Long> orgIds) { revokedUsers.add(userId); }
        @Override public void revokeForOrg(long orgId) { }
    }

    static class RecordingPort implements DepartureHandoverPort {
        final AtomicInteger scans = new AtomicInteger();
        final AtomicInteger transfers = new AtomicInteger();
        @Override public String domain() { return "franchise-crm"; }
        @Override public String label() { return "客户"; }
        @Override public long scanInFlight(long userId, long orgId) { scans.incrementAndGet(); return 1; }
        @Override public void validateReceiver(long userId, long orgId) { }
        @Override public int bulkTransfer(long from, long to, long org, long operator) {
            transfers.incrementAndGet(); return 1;
        }
    }

    @Resource private OrgService orgService;
    @Resource private StaffPostService posts;
    @Resource private DepartureHandoverService handovers;
    @Resource private FakeSystemDirectory directory;
    @Resource private RecordingWallet wallet;
    @Resource private RecordingPort port;
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactionManager;
    private long org;

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO pf_org_type(code,domain,name) VALUES('store','audit-test','门店') ON CONFLICT DO NOTHING");
        int roleId = 601;
        for (String role : List.of("boss", "owner", MANAGER, SECOND_MANAGER, EMPLOYEE)) {
            jdbc.update("INSERT INTO pf_post_template(role_code,domain,org_types,lead) VALUES(?,'audit-test','store',false) ON CONFLICT DO NOTHING", role);
            directory.roleIdsByCode.put(role, (long) roleId++);
            directory.rolePermissions.put(role, EMPLOYEE.equals(role) ? Set.of() : Set.of(MANAGE));
        }
        org = orgService.createOrg("store", "撤权保护测试", null);
        wallet.revokedUsers.clear();
        port.scans.set(0);
        port.transfers.set(0);
    }

    @Test
    void storeCannotRemoveBossOrOwnerThroughAnyMemberEntryIncludingMixedRoles() {
        long receiver = newUser();
        assign(receiver, EMPLOYEE);
        for (String role : List.of("boss", "owner")) {
            long source = newUser();
            long reserved = assign(source, role);
            assign(source, EMPLOYEE);

            assertAllEntriesBlocked(source, reserved, receiver, STAFF_RESERVED_ROLE);
            assertThat(posts.listActivePosts(source)).hasSize(2);
        }
        assertNoDepartureSideEffects();
    }

    @Test
    void lastManagerIsProtectedInAllFourEntriesUsingTheActualPermission() {
        long source = newUser();
        long managerPost = assign(source, MANAGER);
        long receiver = newUser();
        assign(receiver, EMPLOYEE);

        assertAllEntriesBlocked(source, managerPost, receiver, STAFF_LAST_MANAGER);
        assertThat(posts.getPost(managerPost)).isPresent();
        assertNoDepartureSideEffects();
    }

    @Test
    void oneUserCanKeepAnotherManagementPostButCannotRemoveAllOfThem() {
        long source = newUser();
        long first = assign(source, MANAGER);
        long second = assign(source, SECOND_MANAGER);

        posts.revokePost(first, source);
        assertThat(posts.getPost(first)).isEmpty();
        assertThat(posts.getPost(second)).isPresent();
        assertError(() -> posts.revokeAllInOrg(source, org, source), STAFF_LAST_MANAGER);
        assertThat(wallet.revokedUsers).containsExactly(source);
    }

    @Test
    void disabledUsersAndRolesWithoutManagementPermissionCannotServeAsSuccessors() {
        long source = newUser();
        long first = assign(source, MANAGER);
        long successor = newUser();
        assign(successor, SECOND_MANAGER);
        assign(newUser(), EMPLOYEE);
        long otherOrg = orgService.createOrg("store", "其他组织", null);
        posts.assignPost(newUser(), otherOrg, MANAGER, null, 99L);

        directory.setUserActive(successor, false);
        assertError(() -> posts.revokePost(first, 99L), STAFF_LAST_MANAGER);
        directory.setUserActive(successor, true);
        directory.rolePermissions.put(SECOND_MANAGER, Set.of());
        assertError(() -> posts.revokeAllInOrg(source, org, 99L), STAFF_LAST_MANAGER);
        directory.rolePermissions.put(SECOND_MANAGER, Set.of(MANAGE));

        posts.revokePost(first, 99L);
        assertThat(posts.getPost(first)).isEmpty();
        assertThat(posts.listActivePosts(successor)).hasSize(1);
    }

    @Test
    void ordinaryEmployeeCanBeRemovedWithoutChangingAnExistingManagementGap() {
        long employee = newUser();
        long post = assign(employee, EMPLOYEE);
        posts.revokeAllInOrg(employee, org, 99L);
        assertThat(posts.getPost(post)).isEmpty();
        assertThat(wallet.revokedUsers).containsExactly(employee);
    }

    @Test
    void headquartersRevocationAlsoRequiresAUsableManagerSuccessor() {
        long owner = newUser();
        long oldBoss = assign(owner, "boss");
        assertError(() -> posts.revokePostForHeadquarters(oldBoss, 99L), STAFF_LAST_MANAGER);

        long successor = newUser();
        long newBoss = assign(successor, "boss");
        posts.revokePostForHeadquarters(oldBoss, 99L);

        assertThat(posts.findActivePost(owner, oldBoss)).isEmpty();
        assertThat(posts.findActivePost(successor, newBoss)).isPresent();
        assertError(() -> posts.revokePost(newBoss, successor), STAFF_RESERVED_ROLE);
        assertThat(wallet.revokedUsers).containsExactly(owner);
    }

    @Test
    void concurrentSingleAndAllPostRevocationsCannotRemoveBothManagersEvenWithCachedLists() throws Exception {
        long firstUser = newUser();
        long firstPost = assign(firstUser, MANAGER);
        long secondUser = newUser();
        assign(secondUser, SECOND_MANAGER);
        CyclicBarrier readBoth = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> revokeAfterCachedRead(readBoth,
                    () -> posts.revokePost(firstPost, secondUser)));
            var second = executor.submit(() -> revokeAfterCachedRead(readBoth,
                    () -> posts.revokeAllInOrg(secondUser, org, firstUser)));

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, STAFF_LAST_MANAGER.getCode());
            assertThat(posts.listOrgPosts(org)).hasSize(1);
            assertThat(wallet.revokedUsers).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deletedUserCleanupRemainsAvailableForHeadquartersSecurityActions() {
        long owner = newUser();
        long boss = assign(owner, "boss");
        directory.users.remove(owner);

        posts.revokeAllForDeletedUser(owner);

        assertThat(posts.getPost(boss)).isEmpty();
        assertThat(wallet.revokedUsers).containsExactly(owner);
    }

    private int revokeAfterCachedRead(CyclicBarrier readBoth, Runnable revoke) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 两事务先把相同的 ACTIVE 列表放进各自真实 MyBatis SESSION 缓存，再竞争组织锁。
                assertThat(posts.listOrgPosts(org)).hasSize(2);
                try {
                    readBoth.await(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("并发测试未能同步读快照", e);
                }
                revoke.run();
            });
            return 0;
        } catch (ServiceException error) {
            return error.getCode();
        }
    }

    private void assertAllEntriesBlocked(long source, long postId, long receiver, ErrorCode expected) {
        assertError(() -> posts.revokePost(postId, receiver), expected);
        assertError(() -> posts.revokeAllInOrg(source, org, receiver), expected);
        assertError(() -> handovers.handover(org, source, receiver, receiver), expected);
        assertError(() -> handovers.emergencyRevoke(org, source, receiver), expected);
    }

    private void assertNoDepartureSideEffects() {
        assertThat(wallet.revokedUsers).isEmpty();
        assertThat(port.scans).hasValue(0);
        assertThat(port.transfers).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_handover_pending WHERE org_id=?", Long.class, org)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pf_handover_audit WHERE org_id=?", Long.class, org)).isZero();
    }

    private long assign(long userId, String role) { return posts.assignPost(userId, org, role, null, 99L); }

    private long newUser() {
        long id = USER_IDS.incrementAndGet();
        directory.users.add(id);
        return id;
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }
}
