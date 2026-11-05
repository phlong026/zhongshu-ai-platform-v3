package cn.iocoder.yudao.module.franchise.commission.controller.admin.vo;

import cn.iocoder.yudao.module.franchise.commission.CommissionService.Detail;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionProgressDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO;
import cn.iocoder.yudao.module.platform.workflow.dal.ApprovalDO;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 设计委托的对外响应：不含 company_id / design_org_id 等内部归属字段；
 * 设计行政视角的金额已在 Service 层置空（字段裁剪），此处原样输出。
 */
public interface CommissionRespVos {

    record Policy(long id, int version, long totalCents, long firstCents, String scope, String recipient,
                  boolean active, OffsetDateTime createTime) {
        public static Policy of(DesignPolicyDO p) {
            return new Policy(p.getId(), p.getVersion(), p.getTotalCents(), p.getFirstCents(), p.getScope(),
                    p.getRecipient(), Boolean.TRUE.equals(p.getActive()), p.getCreateTime());
        }
    }

    record Commission(long id, long customerId, String customerName, String status, int submissionVersion,
                      Integer dispatchSeq, String dispatchedSchemeVersion, Integer dispatchedSubmissionVersion,
                      Integer handoverVersion, OffsetDateTime firstReadyAt,
                      OffsetDateTime tailPaidAt, OffsetDateTime tailReadyAt, long revision,
                      OffsetDateTime createTime, OffsetDateTime updateTime) {
        public static Commission of(CommissionDO c) {
            return new Commission(c.getId(), c.getCustomerId(), c.getCustomerName(), c.getStatus(),
                    c.getSubmissionVersion(), c.getDispatchSeq(), c.getDispatchedSchemeVersion(),
                    c.getDispatchedSubmissionVersion(), c.getHandoverVersion(), c.getFirstReadyAt(),
                    c.getTailPaidAt(), c.getTailReadyAt(), c.getRevision(), c.getCreateTime(), c.getUpdateTime());
        }
    }

    record Submission(long id, long commissionId, int version, String text, String scope, String attachment,
                      Integer budgetVersion, Integer planVersion, Integer facadeVersion,
                      java.math.BigDecimal width, java.math.BigDecimal depth, Integer floors,
                      java.math.BigDecimal area, String schemeSnapshot, Long totalCents, Long firstCents,
                      String recipient, String authKind, Integer policyVersion, long submittedByUserId,
                      OffsetDateTime createTime) {
        public static Submission of(CommissionSubmissionDO s) {
            return new Submission(s.getId(), s.getCommissionId(), s.getVersion(), s.getText(), s.getScope(),
                    s.getAttachment(), s.getBudgetVersion(), s.getPlanVersion(), s.getFacadeVersion(),
                    s.getWidth(), s.getDepth(), s.getFloors(), s.getArea(), s.getSchemeSnapshot(),
                    s.getTotalCents(), s.getFirstCents(), s.getRecipient(), s.getAuthKind(),
                    s.getPolicyVersion(), s.getSubmittedByUserId(), s.getCreateTime());
        }
    }

    record Progress(long id, long commissionId, String kind, int seq, String note, String recipient,
                    String feedbackStatus, Integer dispatchSeq, long actorUserId, OffsetDateTime createTime) {
        public static Progress of(CommissionProgressDO p) {
            return new Progress(p.getId(), p.getCommissionId(), p.getKind(), p.getSeq(), p.getNote(),
                    p.getRecipient(), p.getFeedbackStatus(), p.getDispatchSeq(), p.getActorUserId(),
                    p.getCreateTime());
        }
    }

    record Handover(long id, long commissionId, int version, int dispatchSeq, String resultFile,
                    String drawingFile, String reviewFile, String confirmationFile, String drawingVersion,
                    String frozenSchemeVersion,
                    LocalDate confirmationDate, String note, long managerUserId, String status,
                    String decisionNote, Long decidedByUserId, OffsetDateTime decidedAt,
                    long submittedByUserId, OffsetDateTime createTime) {
        public static Handover of(CommissionHandoverDO h) {
            return new Handover(h.getId(), h.getCommissionId(), h.getVersion(), h.getDispatchSeq(),
                    h.getResultFile(), h.getDrawingFile(), h.getReviewFile(), h.getConfirmationFile(),
                    h.getDrawingVersion(), h.getFrozenSchemeVersion(), h.getConfirmationDate(), h.getNote(),
                    h.getManagerUserId(), h.getStatus(), h.getDecisionNote(), h.getDecidedByUserId(),
                    h.getDecidedAt(), h.getSubmittedByUserId(), h.getCreateTime());
        }
    }

    record Approval(long id, String status, Long amountCents, String approverRoleCode, long submitterUserId,
                    Long approverUserId, String note, OffsetDateTime createTime, OffsetDateTime decidedAt) {
        public static Approval of(ApprovalDO a) {
            return a == null ? null : new Approval(a.getId(), a.getStatus(), a.getAmountCents(),
                    a.getApproverRoleCode(), a.getSubmitterUserId(), a.getApproverUserId(), a.getNote(),
                    a.getCreateTime(), a.getDecidedAt());
        }
    }

    record CommissionDetail(Commission commission, List<Submission> submissions, List<Progress> progress,
                            List<Handover> handovers, Approval feeApproval, List<String> allowedActions,
                            String displayStatus) {
        public static CommissionDetail of(Detail d) {
            return new CommissionDetail(Commission.of(d.commission()),
                    d.submissions().stream().map(Submission::of).toList(),
                    d.progress().stream().map(Progress::of).toList(),
                    d.handovers().stream().map(Handover::of).toList(),
                    Approval.of(d.feeApproval()), d.allowedActions(), d.displayStatus());
        }
    }

}
