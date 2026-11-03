package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.system.event.AdminUserDeletedEvent;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M01-T01 授权回收契约：后台用户删除事件 → 平台侧全量任职撤销、钱包会话回收、快照即时失效。
 *
 * <p>system 侧令牌移除与事件发布见 AdminUserServiceImplTest；本测试覆盖平台侧监听回收
 * （跨组织全部任职 REVOKED、钱包按曾任职组织吊销、重复事件幂等、userUsable 门禁）。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, StaffPostUserDeletedListener.class,
        StaffPostUserDeletedRecycleIntegrationTest.Config.class})
class StaffPostUserDeletedRecycleIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration
    static class Config {
        @Bean
        FakeSystemDirectory fakeSystemDirectory() {
            return new FakeSystemDirectory();
        }

        @Bean
        RecordingWalletRevocation recordingWalletRevocation() {
            return new RecordingWalletRevocation();
        }
    }

    /** 记录钱包撤权回调（M01-T01 断言用；生产实现见 franchise-org）。支持注入末端故障验证回滚 */
    static class RecordingWalletRevocation implements WalletSessionRevocation {
        final List<Long> users = new CopyOnWriteArrayList<>();
        final List<List<Long>> orgs = new CopyOnWriteArrayList<>();
        int failNextCalls = 0; // >0 时抛异常并自减（模拟钱包吊销末端故障）

        @Override
        public void revokeForUserOrgs(long userId, List<Long> orgIds) {
            if (failNextCalls > 0) {
                failNextCalls--;
                throw new IllegalStateException("M01-T01 回滚注入：钱包吊销失败");
            }
            users.add(userId);
            orgs.add(orgIds);
        }

        @Override
        public void revokeForOrg(long orgId) {
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private FakeSystemDirectory directory;
    @Resource
    private RecordingWalletRevocation wallet;
    @Resource
    private ApplicationEventPublisher publisher;
    @Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void registerOrgTypesAndTemplates() {
        wallet.users.clear(); // 记录 bean 与测试类同上下文共享，跨用例累积须清空
        wallet.orgs.clear();
        wallet.failNextCalls = 0;
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-hq','test','总部'),('t-store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                + "('t-sales','test','t-store','sales',FALSE,NULL),"
                + "('t-finance','test','t-hq,t-store','finance',FALSE,NULL) ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("t-sales", 501L);
        directory.roleIdsByCode.put("t-finance", 503L);
    }

    @Test
    void deletingUserRevokesEveryPostAndWalletAcrossOrgs() {
        long store = orgService.createOrg("t-store", "门店", null);
        long hq = orgService.createOrg("t-hq", "总部", null);
        long user = newUser();
        long sales = staffPostService.assignPost(user, store, "t-sales", null, 99L);
        long finance = staffPostService.assignPost(user, hq, "t-finance", null, 99L);

        publisher.publishEvent(new AdminUserDeletedEvent(List.of(user)));

        // 全部任职（跨组织）REVOKED，撤销人=系统(0)，撤销时间非空——"删除后重放失败"的任职侧证明
        List<StaffPostDO> everHeld = jdbc.query(
                "SELECT id, status, revoked_by, revoked_at FROM pf_staff_post WHERE user_id = " + user,
                (rs, i) -> new StaffPostDO().setId(rs.getLong(1)).setStatus(rs.getString(2))
                        .setRevokedBy(rs.getLong(3)).setRevokedAt(rs.getObject(4, java.time.OffsetDateTime.class)));
        assertThat(everHeld).extracting(StaffPostDO::getStatus)
                .containsOnly(StaffPostDO.STATUS_REVOKED);
        assertThat(everHeld).extracting(StaffPostDO::getRevokedBy).containsOnly(0L);
        assertThat(everHeld).extracting(StaffPostDO::getRevokedAt).doesNotContainNull();
        assertThat(staffPostService.listActivePosts(user)).isEmpty();
        assertThat(staffPostService.findActivePost(user, sales)).isEmpty();
        assertThat(staffPostService.findActivePost(user, finance)).isEmpty();

        // 钱包会话按曾任职组织一次性吊销（含撤销前已撤销的组织，覆盖孤儿会话）
        assertThat(wallet.users).containsExactly(user);
        assertThat(wallet.orgs).hasSize(1);
        assertThat(wallet.orgs.get(0)).containsExactlyInAnyOrder(store, hq);

        // 平台角色随任职撤销一并回收（真实链路 system 已先清全部角色；此处按契约自洽回收平台角色）
        assertThat(directory.userRoles.get(user)).doesNotContain(501L, 503L);
    }

    @Test
    void republishIsIdempotentAndNoPostsMeansNoWalletCall() {
        long store = orgService.createOrg("t-store", "门店", null);
        long user = newUser();
        staffPostService.assignPost(user, store, "t-sales", null, 99L);
        long ghost = newUser(); // 从无任职的用户

        publisher.publishEvent(new AdminUserDeletedEvent(List.of(ghost)));
        assertThat(wallet.users).isEmpty(); // 无任职 → 不触发钱包调用（幂等边界）

        publisher.publishEvent(new AdminUserDeletedEvent(List.of(user)));
        assertThat(wallet.users).containsExactly(user);
        int walletCallsAfterFirst = wallet.users.size();

        publisher.publishEvent(new AdminUserDeletedEvent(List.of(user))); // 重复事件（重复请求有明确结果）
        assertThat(wallet.users).hasSize(walletCallsAfterFirst); // 不重复吊销
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = " + user + " AND status = 'ACTIVE'", Integer.class))
                .isZero();
    }

    @Test
    void walletFailureRollsBackPostRevocationAndRetryCompletes() {
        long store = orgService.createOrg("t-store", "门店", null);
        long user = newUser();
        long postId = staffPostService.assignPost(user, store, "t-sales", null, 99L);
        wallet.failNextCalls = 3; // 与监听器 MAX_ATTEMPTS 对齐：三次尝试全部注入失败

        publisher.publishEvent(new AdminUserDeletedEvent(List.of(user)));

        // 全部尝试失败：每次尝试独立事务整体回滚——任职保持 ACTIVE，不半撤（Codex R1 P2-5）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = " + user + " AND status = 'REVOKED'", Integer.class))
                .isZero();
        assertThat(wallet.users).isEmpty();
        assertThat(staffPostService.findActivePost(user, postId)).isPresent(); // userUsable=true 时仍可用

        // 故障解除后重放事件（重试语义的外部等价）→ 幂等回收成功
        wallet.failNextCalls = 0;
        publisher.publishEvent(new AdminUserDeletedEvent(List.of(user)));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pf_staff_post WHERE user_id = " + user + " AND status = 'REVOKED'", Integer.class))
                .isEqualTo(1);
        assertThat(wallet.users).containsExactly(user);
    }

    @Test
    void findActivePostRejectedWhenUserUnusable() {
        long store = orgService.createOrg("t-store", "门店", null);
        long deleted = newUser();
        long disabled = newUser();
        long postDeleted = staffPostService.assignPost(deleted, store, "t-sales", null, 99L);
        long postDisabled = staffPostService.assignPost(disabled, store, "t-finance", null, 99L);

        // 删除（用户行消失）：任职行仍 ACTIVE 但快照必须拒绝——任职校验的纵深防线
        directory.users.remove(deleted);
        assertThat(staffPostService.findActivePost(deleted, postDeleted)).isEmpty();

        // 停用（存在但禁用）：同样拒绝
        directory.userActive.put(disabled, false);
        assertThat(staffPostService.findActivePost(disabled, postDisabled)).isEmpty();
    }

    private long newUser() {
        long id = USER_IDS.incrementAndGet();
        directory.users.add(id);
        return id;
    }

}
