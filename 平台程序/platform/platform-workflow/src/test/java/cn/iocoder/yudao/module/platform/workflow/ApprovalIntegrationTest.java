package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO;
import jakarta.annotation.Resource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ApprovalServiceImpl.class)
class ApprovalIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final long ORG = 100L;
    private static final long OTHER_ORG = 200L;

    @Resource
    private ApprovalService approvalService;

    @Test
    void submitCreatesPendingApprovalAndResubmittingTheSameContentIsIdempotent() {
        long subject = IDS.incrementAndGet();
        PostSnapshot sales = post(ORG, "sales");

        ApprovalDO first = approvalService.submit(request(subject, "sig-1", 10_000L), sales);
        ApprovalDO again = approvalService.submit(request(subject, "sig-1", 10_000L), sales);

        assertThat(first.getStatus()).isEqualTo(ApprovalDO.STATUS_PENDING);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(approvalService.listPending(ORG, "boss")).extracting(ApprovalDO::getId).contains(first.getId());
    }

    @Test
    void changedContentSupersedesTheOldApprovalEvenIfItWasApproved() {
        long subject = IDS.incrementAndGet();
        PostSnapshot sales = post(ORG, "sales");
        ApprovalDO old = approvalService.submit(request(subject, "sig-1", 10_000L), sales);
        approvalService.decide(old.getId(), true, "同意", post(ORG, "boss"));
        assertThat(approvalService.isApproved("franchise", "quote", subject, "sig-1")).isTrue();

        ApprovalDO renewed = approvalService.submit(request(subject, "sig-2", 12_000L), sales);

        assertThat(renewed.getId()).isNotEqualTo(old.getId());
        assertThat(approvalService.isApproved("franchise", "quote", subject, "sig-1")).isFalse();
        assertThat(approvalService.isApproved("franchise", "quote", subject, "sig-2")).isFalse();
        assertThat(approvalService.latest("franchise", "quote", subject)).get()
                .extracting(ApprovalDO::getId).isEqualTo(renewed.getId());
    }

    @Test
    void qualifiedApproverOfTheSameOrgApprovesWithANote() {
        long subject = IDS.incrementAndGet();
        ApprovalDO pending = approvalService.submit(request(subject, "sig-1", 10_000L), post(ORG, "sales"));
        PostSnapshot boss = post(ORG, "boss");

        ApprovalDO decided = approvalService.decide(pending.getId(), true, "金额与依据核对无误", boss);

        assertThat(decided.getStatus()).isEqualTo(ApprovalDO.STATUS_APPROVED);
        assertThat(decided.getApproverUserId()).isEqualTo(boss.userId());
        assertThat(decided.getApproverPostId()).isEqualTo(boss.postId());
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(approvalService.isApproved("franchise", "quote", subject, "sig-1")).isTrue();
        assertThat(approvalService.listPending(ORG, "boss")).extracting(ApprovalDO::getId).doesNotContain(pending.getId());
    }

    @Test
    void returnedApprovalCanBeResubmittedAsANewPendingApproval() {
        long subject = IDS.incrementAndGet();
        PostSnapshot sales = post(ORG, "sales");
        ApprovalDO first = approvalService.submit(request(subject, "sig-1", 10_000L), sales);
        approvalService.decide(first.getId(), false, "服务费超出标准，请说明", post(ORG, "boss"));

        ApprovalDO second = approvalService.submit(request(subject, "sig-1", 10_000L), sales);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(ApprovalDO.STATUS_PENDING);
    }

    @Test
    void nobodyApprovesTheirOwnSubmissionEvenWithAQualifiedPost() {
        long subject = IDS.incrementAndGet();
        long person = IDS.incrementAndGet();
        PostSnapshot asSales = new PostSnapshot(IDS.incrementAndGet(), person, ORG, "store", "sales", null, false, Set.of());
        PostSnapshot asBoss = new PostSnapshot(IDS.incrementAndGet(), person, ORG, "store", "boss", null, false, Set.of());
        ApprovalDO pending = approvalService.submit(request(subject, "sig-1", 10_000L), asSales);

        assertError(() -> approvalService.decide(pending.getId(), true, "同意", asBoss), APPROVAL_SELF_FORBIDDEN);
    }

    @Test
    void onlyTheDesignatedRoleOfTheSameOrgMayDecide() {
        long subject = IDS.incrementAndGet();
        ApprovalDO pending = approvalService.submit(request(subject, "sig-1", 10_000L), post(ORG, "sales"));

        assertError(() -> approvalService.decide(pending.getId(), true, "同意", post(ORG, "finance-lead")), APPROVER_NOT_QUALIFIED);
        assertError(() -> approvalService.decide(pending.getId(), true, "同意", post(OTHER_ORG, "boss")), APPROVER_NOT_QUALIFIED);
    }

    @Test
    void decisionNeedsANoteAndCanOnlyBeMadeOnce() {
        long subject = IDS.incrementAndGet();
        ApprovalDO pending = approvalService.submit(request(subject, "sig-1", 10_000L), post(ORG, "sales"));
        PostSnapshot boss = post(ORG, "boss");

        assertError(() -> approvalService.decide(pending.getId(), true, "  ", boss), APPROVAL_NOTE_REQUIRED);
        approvalService.decide(pending.getId(), true, "同意", boss);
        assertError(() -> approvalService.decide(pending.getId(), false, "改主意", boss), APPROVAL_NOT_PENDING);
        assertError(() -> approvalService.decide(-1L, true, "同意", boss), APPROVAL_NOT_FOUND);
    }

    @Test
    void supersededApprovalCannotBeDecided() {
        long subject = IDS.incrementAndGet();
        PostSnapshot sales = post(ORG, "sales");
        ApprovalDO old = approvalService.submit(request(subject, "sig-1", 10_000L), sales);
        approvalService.submit(request(subject, "sig-2", 12_000L), sales);

        assertError(() -> approvalService.decide(old.getId(), true, "同意", post(ORG, "boss")), APPROVAL_NOT_PENDING);
    }

    @Test
    void approvalEntityIsTenantIgnored() {
        assertThat(ApprovalDO.class.isAnnotationPresent(TenantIgnore.class)).isTrue();
    }

    private static ApprovalRequest request(long subjectId, String signature, long amountCents) {
        return new ApprovalRequest("franchise", ORG, "quote", subjectId, 1L, signature, amountCents, "boss");
    }

    private static PostSnapshot post(long orgId, String role) {
        return new PostSnapshot(IDS.incrementAndGet(), IDS.incrementAndGet(), orgId, "store", role, null, false, Set.of());
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
