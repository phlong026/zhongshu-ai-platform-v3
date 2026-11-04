package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.dal.PlatformHandoverPendingDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M01-T04：离职交接编排——真实组织/任职（PG Testcontainers）+ 桩 Port。
 * 覆盖（Codex R1 修复回归）：交接清单聚合（已接入/未接入/清单外域）、接收人四类拒绝（先于写操作）、
 * 域级接收资格（P1-1）、转移数与扫描数不符整体回滚可重试（P1-5）、事务回滚真实性（P2-6：Port 先写库
 * 再失败，断言库内回滚）、紧急撤权独立事务先提交（P1-2：扫描失败撤权仍生效）、待分配 OPEN 行落地
 * 可查询可补交接（P1-3）、重复交接零重复、同域重复注册装配失败（P2-4）。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, DepartureHandoverService.class,
        DepartureHandoverServiceIntegrationTest.Config.class})
class DepartureHandoverServiceIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    /** 桩 Port：内存计数 + 可注入失败；writeThenFail 模式先真实写库再抛（验证事务回滚） */
    static class FakeHandoverPort implements DepartureHandoverPort {
        private final String domain;
        private final String label;
        long inFlight;
        boolean fail;
        boolean scanFail;
        boolean rejectReceiver;
        boolean shortfall;
        boolean writeThenFail;
        int transferCalls;
        JdbcTemplate jdbc;

        FakeHandoverPort(String domain, String label) {
            this.domain = domain;
            this.label = label;
        }

        @Override
        public String domain() {
            return domain;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public long scanInFlight(long fromUserId, long orgId) {
            if (scanFail) {
                // R3 P2：真实 SQL 故障（PG aborted 事务语义），而非内存异常
                if (jdbc != null) {
                    jdbc.execute("SELECT * FROM pf_table_not_exists_in_tests");
                }
                throw new IllegalStateException("模拟扫描故障（SQL 失败后兜底）");
            }
            return inFlight;
        }

        @Override
        public void validateReceiver(long toUserId, long orgId) {
            if (rejectReceiver) {
                throw new ServiceException(HANDOVER_TARGET_ROLE_MISMATCH);
            }
        }

        @Override
        public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
            transferCalls++;
            if (writeThenFail && jdbc != null) {
                // 真实写库后失败：同事务回滚后该行必须恢复原值（P2-6 回滚证明）
                jdbc.update("UPDATE pf_staff_post SET team_id = 424242 WHERE role_code = 't-sales' AND org_id = ?", orgId);
                throw new IllegalStateException("模拟写库后失败");
            }
            if (fail) {
                throw new IllegalStateException("模拟该域转移失败");
            }
            if (shortfall) {
                return (int) inFlight - 1; // 残留 1 个不扣减内存计数：重试时仍在（真实域语义）
            }
            int moved = (int) inFlight;
            inFlight = 0;
            return moved;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FakeHandoverPort crmPort() {
            return new FakeHandoverPort("franchise-crm", "客户");
        }

        @Bean
        FakeHandoverPort extraPort() {
            return new FakeHandoverPort("custom-domain-x", "清单外扩展域");
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
    @Resource
    private FakeHandoverPort crmPort;
    @Resource
    private FakeHandoverPort extraPort;

    private long orgId;
    private long otherOrgId;
    private long fromUser;
    private long toUser;
    private long fromPostId;

    @BeforeEach
    void setUpOrgAndPeople() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES ('t-hq','test','总部'),('t-store','test','门店') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                + "('t-sales','test','t-store','sales',FALSE,NULL),"
                + "('t-sales-lead','test','t-store','sales',TRUE,'t-sales') ON CONFLICT DO NOTHING");
        directory.roleIdsByCode.put("t-sales", 501L);
        directory.roleIdsByCode.put("t-sales-lead", 502L);

        orgId = orgService.createOrg("t-store", "交接门店", null);
        otherOrgId = orgService.createOrg("t-store", "别家门店", null);
        fromUser = newUser();
        toUser = newUser();
        fromPostId = staffPostService.assignPost(fromUser, orgId, "t-sales", null, 99L);
        staffPostService.assignPost(toUser, orgId, "t-sales-lead", null, 99L);
        crmPort.inFlight = 0;
        crmPort.fail = false;
        crmPort.scanFail = false;
        crmPort.rejectReceiver = false;
        crmPort.shortfall = false;
        crmPort.writeThenFail = false;
        crmPort.transferCalls = 0;
        crmPort.jdbc = jdbc;
        extraPort.inFlight = 0;
        extraPort.fail = false;
        extraPort.scanFail = false;
        extraPort.rejectReceiver = false;
        extraPort.shortfall = false;
        extraPort.writeThenFail = false;
        extraPort.transferCalls = 0;
        extraPort.jdbc = jdbc;
    }

    @Test
    void normalHandoverTransfersRegisteredDomainsAndReportsTheChecklist() {
        crmPort.inFlight = 3;
        extraPort.inFlight = 0; // 零在途域报 NONE 而非「已交接」（Codex R1 P1-5）

        DepartureHandoverService.HandoverResult result = handoverService.handover(orgId, fromUser, toUser, 99L);

        assertThat(result.mode()).isEqualTo(DepartureHandoverService.MODE_NORMAL);
        assertThat(result.toUserId()).isEqualTo(toUser);
        assertThat(result.revokedPostIds()).containsExactly(fromPostId);
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 3, 3, DepartureHandoverService.ST_HANDED_OVER),
                new DepartureHandoverService.HandoverItem("custom-domain-x", "清单外扩展域", 0, 0, DepartureHandoverService.ST_NONE));
        assertThat(result.items()).containsSubsequence(
                new DepartureHandoverService.HandoverItem("franchise-commission", "设计委托", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED),
                new DepartureHandoverService.HandoverItem("franchise-engineering", "工程任务", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED),
                new DepartureHandoverService.HandoverItem("franchise-finance", "财务账款", 0, 0, DepartureHandoverService.ST_NOT_INTEGRATED));
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
        assertThat(staffPostService.listActivePosts(toUser)).anyMatch(p -> p.getOrgId() == orgId);
        // 持久化审计（R2 P2-6）：清单落库可追溯，内容逐字段核对（R3 P2）
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT * FROM pf_handover_audit WHERE org_id=? AND mode='NORMAL' AND from_user_id=?",
                orgId, fromUser);
        assertThat(((Number) audit.get("to_user_id")).longValue()).isEqualTo(toUser);
        assertThat(((Number) audit.get("operator_user_id")).longValue()).isEqualTo(99L);
        assertThat((String) audit.get("revoked_post_ids")).contains(String.valueOf(fromPostId));
        String itemsJson = (String) audit.get("items_json");
        assertThat(itemsJson).contains("\"domain\":\"franchise-crm\"").contains("\"status\":\"HANDED_OVER\"")
                .contains("\"transferred\":3").contains("\"status\":\"NOT_INTEGRATED\"");
    }

    @Test
    void handoverRevokesOnlyTheTargetOrgAndKeepsOtherOrgPosts() {
        long keptPostId = staffPostService.assignPost(fromUser, otherOrgId, "t-sales", null, 99L);

        DepartureHandoverService.HandoverResult result = handoverService.handover(orgId, fromUser, toUser, 99L);

        assertThat(result.revokedPostIds()).containsExactly(fromPostId);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
        assertThat(postStatus(keptPostId)).isEqualTo(StaffPostDO.STATUS_ACTIVE);
    }

    @Test
    void zeroInFlightDomainsDoNotBlockHandoverOnRoleMismatch() {
        crmPort.inFlight = 0;           // 客户域无在途对象
        crmPort.rejectReceiver = true;  // 接收人无承接客户岗位：但无对象可转，资格校验应跳过（R2 P1）

        DepartureHandoverService.HandoverResult result = handoverService.handover(orgId, fromUser, toUser, 99L);

        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 0, 0, DepartureHandoverService.ST_NONE));
        assertThat(crmPort.transferCalls).isZero();
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
    }

    @Test
    void receiverValidationRejectsBeforeAnyWrite() {
        // 本人
        assertError(() -> handoverService.handover(orgId, fromUser, fromUser, 99L), HANDOVER_TARGET_SELF);
        // 停用（userUsable=false 覆盖不存在与停用两种形态）
        directory.setUserActive(toUser, false);
        assertError(() -> handoverService.handover(orgId, fromUser, toUser, 99L), HANDOVER_TARGET_DISABLED);
        directory.setUserActive(toUser, true);
        // 不在本组织任职（仅在别家门店）
        long outsider = newUser();
        staffPostService.assignPost(outsider, otherOrgId, "t-sales", null, 99L);
        assertError(() -> handoverService.handover(orgId, fromUser, outsider, 99L), HANDOVER_TARGET_NOT_IN_ORG);
        // 域级资格（P1-1）：通用资格通过但有在途对象且无本域承接岗位
        crmPort.inFlight = 1;
        crmPort.rejectReceiver = true;
        assertError(() -> handoverService.handover(orgId, fromUser, toUser, 99L), HANDOVER_TARGET_ROLE_MISMATCH);
        // 校验先于写操作：转移从未发起
        assertThat(crmPort.transferCalls).isZero();
    }

    @Test
    void sourceWithoutActivePostIsRejectedForBothPaths() {
        long stranger = newUser();
        assertError(() -> handoverService.handover(orgId, stranger, toUser, 99L), HANDOVER_SOURCE_NO_POST);
        assertError(() -> handoverService.emergencyRevoke(orgId, stranger, 99L), HANDOVER_SOURCE_NO_POST);
        assertThat(crmPort.transferCalls).isZero();
    }

    @Test
    void incompleteTransferRollsEverythingBackAndRetries() {
        crmPort.inFlight = 3;
        crmPort.shortfall = true; // 转移数(2) < 扫描数(3)：残留不得报已交接（P1-5）

        assertError(() -> handoverService.handover(orgId, fromUser, toUser, 99L), HANDOVER_TRANSFER_INCOMPLETE);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_ACTIVE);

        crmPort.shortfall = false;
        DepartureHandoverService.HandoverResult retry = handoverService.handover(orgId, fromUser, toUser, 99L);
        assertThat(retry.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 3, 3, DepartureHandoverService.ST_HANDED_OVER));
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
    }

    @Test
    void failedWriteInPortIsRolledBackByTheTransaction() {
        crmPort.inFlight = 2;
        crmPort.writeThenFail = true; // Port 先真实写库再抛（P2-6：证明事务回滚，而非仅「撤权未执行」）
        Long teamIdBefore = jdbc.queryForObject(
                "SELECT team_id FROM pf_staff_post WHERE id = (SELECT id FROM pf_staff_post WHERE role_code='t-sales' AND org_id=? LIMIT 1)",
                Long.class, orgId);

        assertThatThrownBy(() -> handoverService.handover(orgId, fromUser, toUser, 99L))
                .isInstanceOf(IllegalStateException.class);
        // Port 写入的 team_id=424242 已随事务回滚
        Long teamIdAfter = jdbc.queryForObject(
                "SELECT team_id FROM pf_staff_post WHERE id = (SELECT id FROM pf_staff_post WHERE role_code='t-sales' AND org_id=? LIMIT 1)",
                Long.class, orgId);
        assertThat(teamIdAfter).isEqualTo(teamIdBefore);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_ACTIVE);

        crmPort.writeThenFail = false; // 修复后重试成功（交接失败可重试）
        DepartureHandoverService.HandoverResult retry = handoverService.handover(orgId, fromUser, toUser, 99L);
        assertThat(retry.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 2, 2, DepartureHandoverService.ST_HANDED_OVER));
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
    }

    @Test
    void emergencyRevokeRevokesImmediatelyWithoutTransferring() {
        crmPort.inFlight = 4;
        extraPort.inFlight = 0;

        DepartureHandoverService.HandoverResult result = handoverService.emergencyRevoke(orgId, fromUser, 99L);

        assertThat(result.mode()).isEqualTo(DepartureHandoverService.MODE_EMERGENCY);
        assertThat(result.toUserId()).isNull();
        assertThat(result.revokedPostIds()).containsExactly(fromPostId);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 4, 0, DepartureHandoverService.ST_PENDING_UNASSIGNED),
                new DepartureHandoverService.HandoverItem("custom-domain-x", "清单外扩展域", 0, 0, DepartureHandoverService.ST_NONE));
        assertThat(crmPort.transferCalls).isZero();
        assertThat(extraPort.transferCalls).isZero();
        // 待分配落地（P1-3）：OPEN 行可查询，in_flight=扫描快照
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT in_flight FROM pf_handover_pending WHERE org_id=? AND from_user_id=? AND domain='franchise-crm'",
                Long.class, orgId, fromUser)).isEqualTo(4L);
        Map<String, Object> emergencyAudit = jdbc.queryForMap(
                "SELECT * FROM pf_handover_audit WHERE org_id=? AND mode='EMERGENCY' AND from_user_id=?",
                orgId, fromUser);
        assertThat(emergencyAudit.get("to_user_id")).isNull(); // 紧急撤权无接收人
        assertThat((String) emergencyAudit.get("items_json")).contains("\"status\":\"PENDING_UNASSIGNED\"");
    }

    @Test
    void emergencyScanFailureDoesNotBlockRevocation() {
        crmPort.scanFail = true; // P1-2：扫描故障不阻断撤权
        crmPort.inFlight = 4;

        DepartureHandoverService.HandoverResult result = handoverService.emergencyRevoke(orgId, fromUser, 99L);

        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", -1, 0, DepartureHandoverService.ST_SCAN_FAILED));
        // SQL 故障只隔离在本域小事务：其他域照常扫描（extraPort NONE）、撤权已提交
        assertThat(result.items()).contains(
                new DepartureHandoverService.HandoverItem("custom-domain-x", "清单外扩展域", 0, 0, DepartureHandoverService.ST_NONE));
        // 扫描失败同样落 OPEN 待分配行（in_flight=-1 未知，补交接按全量转移）
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT in_flight FROM pf_handover_pending WHERE org_id=? AND from_user_id=? AND domain='franchise-crm'",
                Long.class, orgId, fromUser)).isEqualTo(-1L);
    }

    @Test
    void handoverAfterEmergencyCompletesTheBackfill() {
        crmPort.inFlight = 2;
        handoverService.emergencyRevoke(orgId, fromUser, 99L);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(1);

        // 补交接（P1-3）：源已撤岗但存在 OPEN 待分配行 → 放行，完成后核销
        DepartureHandoverService.HandoverResult backfill = handoverService.handover(orgId, fromUser, toUser, 99L);
        assertThat(backfill.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 2, 2, DepartureHandoverService.ST_HANDED_OVER));
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pf_handover_pending WHERE org_id=? AND from_user_id=? AND domain='franchise-crm'",
                String.class, orgId, fromUser)).isEqualTo(PlatformHandoverPendingDO.STATUS_HANDED_OVER);
    }

    @Test
    void lateArrivingObjectsAreRecoverableViaBackfillHandover() {
        // 交接完成（残留复核读到零：模拟「建户事务晚于交接提交」且复核窗口错过）
        crmPort.inFlight = 0;
        handoverService.handover(orgId, fromUser, toUser, 99L);
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(0);
        assertThat(postStatus(fromPostId)).isEqualTo(StaffPostDO.STATUS_REVOKED);

        // 晚到对象落地（无 OPEN 行登记）：对账式补交接入口仍可发现并恢复（R3 P1）
        crmPort.inFlight = 1;
        DepartureHandoverService.HandoverResult backfill = handoverService.handover(orgId, fromUser, toUser, 99L);
        assertThat(backfill.items()).contains(
                new DepartureHandoverService.HandoverItem("franchise-crm", "客户", 1, 1, DepartureHandoverService.ST_HANDED_OVER));
        assertThat(openPendingRows(orgId, fromUser)).isEqualTo(0);
    }

    @Test
    void repeatedHandoverAfterCompletionIsRejectedWithoutDuplication() {
        crmPort.inFlight = 3;
        handoverService.handover(orgId, fromUser, toUser, 99L);
        int callsAfterFirst = crmPort.transferCalls;

        // 任职已撤且无待分配行：再次发起被拒（业务对象不可能被重复转移/重复创建）
        assertError(() -> handoverService.handover(orgId, fromUser, toUser, 99L), HANDOVER_SOURCE_NO_POST);
        assertThat(crmPort.transferCalls).isEqualTo(callsAfterFirst);
    }

    @Test
    void listPendingExposesOpenRowsForSupervisors() {
        crmPort.inFlight = 4;
        handoverService.emergencyRevoke(orgId, fromUser, 99L);

        List<DepartureHandoverService.PendingRow> pending = handoverService.listPending(orgId);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).domain()).isEqualTo("franchise-crm");
        assertThat(pending.get(0).domainLabel()).isEqualTo("客户");
        assertThat(pending.get(0).fromUserId()).isEqualTo(fromUser);
        assertThat(pending.get(0).inFlight()).isEqualTo(4L);
    }

    private int openPendingRows(long orgId, long fromUserId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_handover_pending WHERE org_id=? AND from_user_id=? AND status='OPEN'",
                Integer.class, orgId, fromUserId);
        return count == null ? 0 : count;
    }

    private String postStatus(long postId) {
        return jdbc.queryForObject("SELECT status FROM pf_staff_post WHERE id = ?", String.class, postId);
    }

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
