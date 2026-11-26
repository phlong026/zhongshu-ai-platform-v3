package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CustomerWorkflowModel {
    private CustomerWorkflowModel() { }

    public enum Step {
        INVITATION("邀约进店"), INTRODUCTION("店面讲解"), HOUSE_SELECTION("户型选择"),
        SITE_VISIT("带看工地"), MEASUREMENT("现场测量"), QUOTATION("别墅报价"),
        CONTRACT("施工签约"), START("开工大吉");
        public final String label;
        Step(String label) { this.label = label; }
        public boolean manual() { return ordinal() < 6; }
        public static Step parse(String code) {
            try { return valueOf(code); }
            catch (IllegalArgumentException | NullPointerException error) { throw new ServiceException(400, "跟进步骤无效"); }
        }
    }

    public record Attachment(String assetId, String title) { }
    public record SaveCommand(long revision, LocalDate occurredOn, String note,
                              Map<String, Object> fields, List<Attachment> attachments) { }
    public record RevisionCommand(long revision) { }
    public record SkipVisitCommand(long revision, String reason) { }
    public record ReopenCommand(long revision, String reason) { }
    public record StatusCommand(long revision, String status, String reason) { }

    public record StepView(String code, String name, int order, String status, long revision,
                           LocalDate occurredOn, String note, Map<String, Object> fields,
                           List<Attachment> attachments, OffsetDateTime completedAt,
                           boolean canWrite, boolean canComplete, boolean canReopen,
                           List<String> completionErrors, String source) { }

    public record RecordView(String id, String stepCode, String type, LocalDate occurredOn, String note,
                             OffsetDateTime createdAt, String actorUserId, String actorName, long revision,
                             Map<String, Object> fields, List<Attachment> attachments) { }

    public record Summary(String workflowId, int completedCount, int totalSteps, BigDecimal progressPercent,
                          String currentStepCode, String currentStepName, String nextStepCode,
                          String status, OffsetDateTime updatedAt, String nextContactOn) { }

    public record View(String workflowId, String customerId, String customerName, String companyId,
                       int templateVersion, String status, long revision, int completedCount,
                       int totalSteps, BigDecimal progressPercent, String currentStepCode,
                       String nextStepCode, boolean canWrite, List<StepView> steps,
                       List<RecordView> events, boolean hasMoreEvents) { }
}
