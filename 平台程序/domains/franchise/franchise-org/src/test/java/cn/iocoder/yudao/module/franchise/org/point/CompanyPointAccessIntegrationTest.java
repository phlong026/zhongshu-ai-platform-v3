package cn.iocoder.yudao.module.franchise.org.point;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompanyPointAccessIntegrationTest {

    private static final long ORG_A = 101L;
    private static final long ORG_B = 202L;
    private static final long BOSS_A = 11L;
    private static final long STAFF_A = 12L;
    private static final long STAFF_B = 22L;

    private PostgreSQLContainer<?> container;
    private JdbcTemplate jdbc;
    private OrgPointService points;
    private CompanyPointAccess service;
    private TransactionTemplate transaction;
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void database() throws Exception {
        container = new PostgreSQLContainer<>("postgres:17-alpine");
        container.start();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String path : List.of(
                "db/migration/platform-wallet/V20261114.911__org_point_pool.sql",
                "db/migration/platform-wallet/V20261114.912__design_billing_binding.sql",
                "db/migration/platform-wallet/V20261114.914__org_point_subject_price.sql")) {
            try (var stream = new ClassPathResource(path).getInputStream()) {
                jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        jdbc.execute("CREATE TABLE system_users (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "status INT NOT NULL, deleted INT NOT NULL)");
        jdbc.execute("CREATE TABLE system_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "status INT NOT NULL, deleted INT NOT NULL)");
        jdbc.execute("CREATE TABLE pf_org_unit (dept_id BIGINT PRIMARY KEY, org_id BIGINT NOT NULL, "
                + "kind TEXT NOT NULL, org_type TEXT, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE pf_post_template (role_code TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE pf_staff_post (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "org_id BIGINT NOT NULL, role_code TEXT NOT NULL, status TEXT NOT NULL)");
        var manager = new DataSourceTransactionManager(dataSource);
        transactionManager = manager;
        transaction = new TransactionTemplate(manager);
        points = new OrgPointService(dataSource, manager, message -> 0L,
                new OrgPointPriceService(dataSource, manager));
        service = new CompanyPointAccess(points, jdbc, manager);
    }

    @AfterAll
    void stop() {
        container.stop();
    }

    @BeforeEach
    void fixture() {
        TenantContextHolder.setTenantId(1L);
        jdbc.execute("TRUNCATE org_point_ledger, org_point_account, org_member_permission, "
                + "org_member_quota, org_point_subject_price, pf_staff_post, pf_org_unit, "
                + "pf_post_template, system_users, system_dept");
        jdbc.update("INSERT INTO pf_post_template VALUES ('boss'), ('owner'), ('sales')");
        jdbc.update("INSERT INTO system_dept VALUES (?, 1, 0, 0), (?, 1, 0, 0)", ORG_A, ORG_B);
        jdbc.update("INSERT INTO pf_org_unit VALUES (?, ?, 'ORG', 'store', 'ACTIVE'), "
                + "(?, ?, 'ORG', 'store', 'ACTIVE')", ORG_A, ORG_A, ORG_B, ORG_B);
        jdbc.update("INSERT INTO system_users VALUES (?, 1, 0, 0), (?, 1, 0, 0), "
                        + "(?, 1, 0, 0), (13, 1, 0, 0), (31, 1, 0, 0)", BOSS_A, STAFF_A, STAFF_B);
        jdbc.update("INSERT INTO pf_staff_post VALUES (1, ?, ?, 'boss', 'ACTIVE')", BOSS_A, ORG_A);
        jdbc.update("INSERT INTO pf_staff_post VALUES (2, ?, ?, 'sales', 'ACTIVE')", STAFF_A, ORG_A);
        jdbc.update("INSERT INTO pf_staff_post VALUES (3, ?, ?, 'sales', 'ACTIVE')", STAFF_B, ORG_B);
        jdbc.update("INSERT INTO pf_staff_post VALUES (4, 13, ?, 'sales', 'REVOKED')", ORG_A);
        jdbc.update("INSERT INTO pf_staff_post VALUES (5, 31, ?, 'boss', 'ACTIVE')", ORG_B);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void activationCreatesOnlyCurrentCompanyPoolAndEmployeeSeesOwnUsageWithoutQuota() {
        var boss = post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS);
        var employee = post(2, STAFF_A, ORG_A, FranchiseRoles.SALES);

        assertThat(service.activate(boss).balance()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_account WHERE org_id=?",
                Integer.class, ORG_A)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_account WHERE org_id=?",
                Integer.class, ORG_B)).isZero();

        points.adjust(ORG_A, 50, "test:fund", "test", BOSS_A);
        var usage = service.myUsage(employee, null, 1, 20);
        assertThat(usage.orgId()).isEqualTo(ORG_A);
        assertThat(usage.userId()).isEqualTo(STAFF_A);
        assertThat(usage.monthlyQuota()).isNull();
        assertThat(service.summary(employee).balance()).isEqualTo(50);
    }

    @Test
    void employeeCannotReadCompanyLedgerOrManagePermissions() {
        service.activate(post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS));
        var employee = post(2, STAFF_A, ORG_A, FranchiseRoles.SALES);

        assertThatThrownBy(() -> service.ledger(employee, null, null, null, 1, 20))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.setMemberPermission(employee, STAFF_A, List.of("copy")))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void staleOrRoleSpoofedCurrentPostIsRejected() {
        var revoked = post(4, 13L, ORG_A, FranchiseRoles.SALES);
        var roleSpoofed = post(2, STAFF_A, ORG_A, FranchiseRoles.BOSS);

        assertThatThrownBy(() -> service.summary(revoked)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.activate(roleSpoofed)).isInstanceOf(ServiceException.class);
    }

    @Test
    void bossCanManageOnlyActiveEmployeesOfOwnOrganization() {
        var boss = post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS);
        service.activate(boss);

        service.setMemberPermission(boss, STAFF_A, List.of("digital", "copy", "copy"));
        assertThat(points.memberPermissions(ORG_A).get(0).allowedSubjects())
                .containsExactly("copy", "digital");

        assertThatThrownBy(() -> service.setMemberPermission(boss, STAFF_B, List.of("copy")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.setMemberPermission(boss, 13L, List.of("copy")))
                .isInstanceOf(ServiceException.class);
        assertThat(points.memberPermissions(ORG_B)).isEmpty();
    }

    @Test
    void blankOrUnknownSubjectIsRejectedInsteadOfBecomingDenyAll() {
        var boss = post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS);
        service.activate(boss);

        assertThatThrownBy(() -> service.setMemberPermission(boss, STAFF_A, List.of(" ")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.setMemberPermission(boss, STAFF_A, List.of("unknown")))
                .isInstanceOf(ServiceException.class).hasMessage("业务科目无效");
        assertThat(points.memberPermissions(ORG_A)).isEmpty();
    }

    @Test
    void knownSubjectPriceFailureKeepsOriginalFailureInsteadOfReportingUnknownSubject() {
        OrgPointPort failingPoints = mock(OrgPointPort.class);
        when(failingPoints.isKnownSubject("copy")).thenReturn(true);
        when(failingPoints.quoteCredits("copy", 1)).thenThrow(new IllegalStateException("price store unavailable"));
        CompanyPointAccess failingAccess = new CompanyPointAccess(failingPoints, jdbc, transactionManager);

        assertThatThrownBy(() -> failingAccess.setMemberPermission(
                post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS), STAFF_A, List.of("copy")))
                .isInstanceOf(IllegalStateException.class).hasMessage("price store unavailable");
    }

    @Test
    void concurrentPostRevocationWinsBeforePermissionWriteAndIsRecheckedAfterLock() throws Exception {
        assertConcurrentInvalidationRejected(
                () -> {
                    jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) x",
                            Integer.class, STAFF_A);
                    jdbc.update("UPDATE pf_staff_post SET status='REVOKED' WHERE id=2");
                },
                () -> jdbc.update("UPDATE pf_staff_post SET status='ACTIVE' WHERE id=2"));
    }

    @Test
    void concurrentUserDisableWinsBeforePermissionWriteAndIsRecheckedAfterLock() throws Exception {
        assertConcurrentInvalidationRejected(
                () -> jdbc.update("UPDATE system_users SET status=1 WHERE id=?", STAFF_A),
                () -> jdbc.update("UPDATE system_users SET status=0 WHERE id=?", STAFF_A));
    }

    @Test
    void concurrentOrganizationDisableWinsBeforePermissionWriteAndIsRecheckedAfterLock() throws Exception {
        assertConcurrentInvalidationRejected(
                () -> jdbc.update("UPDATE pf_org_unit SET status='DISABLED' WHERE dept_id=?", ORG_A),
                () -> jdbc.update("UPDATE pf_org_unit SET status='ACTIVE' WHERE dept_id=?", ORG_A));
    }

    @Test
    void currentPostOrganizationCannotBeOverriddenByRequestData() {
        var otherBoss = post(5, 31L, ORG_B, FranchiseRoles.BOSS);
        service.activate(otherBoss);
        points.adjust(ORG_B, 99, "test:org-b", "test", 31L);

        assertThat(service.summary(otherBoss).orgId()).isEqualTo(ORG_B);
        assertThat(service.summary(otherBoss).balance()).isEqualTo(99);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE org_id=?",
                Integer.class, ORG_A)).isZero();
    }

    private static PostSnapshot post(long postId, long userId, long orgId, String role) {
        return new PostSnapshot(postId, userId, orgId, FranchiseRoles.ORG_STORE, role,
                null, false, Set.of());
    }

    private void assertConcurrentInvalidationRejected(Runnable invalidation, Runnable restore) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> invalidator = CompletableFuture.runAsync(() -> {
            TenantContextHolder.setTenantId(1L);
            try {
                transaction.executeWithoutResult(status -> {
                    invalidation.run();
                    changed.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            } finally {
                TenantContextHolder.clear();
            }
        });
        assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> writer = CompletableFuture.supplyAsync(() -> {
            TenantContextHolder.setTenantId(1L);
            try {
                return org.assertj.core.api.Assertions.catchThrowable(() -> service.setMemberPermission(
                        post(1, BOSS_A, ORG_A, FranchiseRoles.BOSS), STAFF_A, List.of("copy")));
            } finally {
                TenantContextHolder.clear();
            }
        });
        Thread.sleep(150);
        assertThat(writer).isNotDone();
        release.countDown();
        invalidator.get(5, TimeUnit.SECONDS);
        assertThat(writer.get(5, TimeUnit.SECONDS)).isInstanceOf(ServiceException.class);
        assertThat(points.memberPermissions(ORG_A)).isEmpty();
        restore.run();
    }
}
