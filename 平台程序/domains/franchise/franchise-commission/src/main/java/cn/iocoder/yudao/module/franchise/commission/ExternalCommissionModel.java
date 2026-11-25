package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.api.CustomerPaymentPlanDraftPort.Snapshot;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ExternalCommissionModel {
    private ExternalCommissionModel() { }
    public record PlanRef(String source, long revision, String contractId, Integer contractPlanVersion) {
        static PlanRef of(Snapshot plan) {
            return plan == null ? new PlanRef("NONE", 0, null, null)
                    : new PlanRef(plan.source(), plan.revision(), plan.contractId() == null ? null : plan.contractId().toString(), plan.contractPlanVersion());
        }
    }
    public record RequestCommand(Long revision, LocalDate entrustedOn, String providerName, String providerReference,
                                 String text, String scope, BigDecimal area, Integer floors, List<Attachment> attachments,
                                 PlanRef paymentPlanRef, String reason) { }
    public record ResultCommand(Long revision, int requestVersion, LocalDate deliveredOn, String drawingVersion,
                                String resultAssetId, String drawingAssetId, String reviewAssetId, String note) { }
    public record RequestVersion(int version, String action, RequestCommand data, Snapshot paymentPlan,
                                 String previousStatus, List<String> supersededApprovalIds,
                                 String actorUserId, OffsetDateTime createdAt) { }
    public record ResultVersion(int version, ResultCommand data, List<Attachment> attachments,
                                String actorUserId, OffsetDateTime createdAt) { }
    public record View(String commissionId, String customerId, String customerName, String mode, String status,
                       long revision, boolean canWrite, boolean canConvert, List<String> allowedActions,
                       RequestVersion currentRequest, ResultVersion currentResult,
                       List<RequestVersion> requests, List<ResultVersion> results,
                       Snapshot currentPaymentPlan, PlanRef currentPaymentPlanRef) { }
    record Mutation(String action, long customerId, Object input) { }
}
