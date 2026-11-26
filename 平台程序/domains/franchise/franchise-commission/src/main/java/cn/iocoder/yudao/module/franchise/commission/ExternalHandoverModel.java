package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.api.CustomerPaymentPlanDraftPort.Snapshot;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ExternalHandoverModel {
    private ExternalHandoverModel() { }
    public record Submit(Long revision, int resultVersion, String confirmationAssetId,
                         LocalDate confirmationDate, String note, String managerUserId, boolean confirmed,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         ContractVersion checkedContract) {
        public Submit(Long revision, int resultVersion, String confirmationAssetId, LocalDate confirmationDate,
                      String note, String managerUserId, boolean confirmed) {
            this(revision,resultVersion,confirmationAssetId,confirmationDate,note,managerUserId,confirmed,null);
        }
    }
    public record ContractVersion(String id, Integer version) {
        static ContractVersion of(Snapshot plan) {
            return plan == null || plan.contractId() == null ? new ContractVersion(null,null)
                    : new ContractVersion(plan.contractId().toString(),plan.contractPlanVersion());
        }
    }
    public record SubmissionTerms(Snapshot plan) {}
    public record Decision(Long revision, int handoverVersion, String reason, boolean confirmed) { }
    public record File(String slot, String assetId, String title) { }
    public record Packet(int version, int resultVersion, String status, String drawingVersion,
                         LocalDate confirmationDate, String note, String managerUserId, String managerName,
                         List<File> files, Snapshot paymentPlan, String submittedByUserId, OffsetDateTime createdAt,
                         String decisionNote, String decidedByUserId, OffsetDateTime decidedAt) { }
    public record View(String commissionId, String customerId, String customerName, String mode, String status,
                       long revision, Integer resultVersion, String projectId, boolean canSubmit, boolean canDecide,
                       List<String> allowedActions, Packet currentPacket, List<Packet> packets, SubmissionTerms submissionTerms, Snapshot currentContractTerms) { }
    record Mutation(String action, long commissionId, Object input) { }
}
