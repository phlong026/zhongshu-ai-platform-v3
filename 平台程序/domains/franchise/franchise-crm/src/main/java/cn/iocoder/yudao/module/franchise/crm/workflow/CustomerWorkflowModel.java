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
        CONTRACT("合同签约"), START("开工大吉");
        public final String label;
        Step(String label) { this.label = label; }
        public boolean manual() { return ordinal() < 6; }
        public static Step parse(String code) {
            try { return valueOf(code); }
            catch (IllegalArgumentException | NullPointerException error) { throw new ServiceException(400, "跟进步骤无效"); }
        }
    }

    public record Attachment(String assetId, String title) { }
    /** nextFollowOn 是这条记录约定的下次跟进日期；升级前的小程序不携带，第 1 步只有 nextContactOn。 */
    public record SaveCommand(long revision, LocalDate occurredOn, String note,
                              Map<String, Object> fields, List<Attachment> attachments, LocalDate nextFollowOn) {
        public SaveCommand(long revision, LocalDate occurredOn, String note,
                           Map<String, Object> fields, List<Attachment> attachments) {
            this(revision, occurredOn, note, fields, attachments, null);
        }
    }
    public record RevisionCommand(long revision) { }
    public record SkipVisitCommand(long revision, String reason) { }
    public record ReopenCommand(long revision, String reason) { }
    /** 流失必选 reasonCode（“其他”须自填 reason）；暂缓必填 reason 与再联系日期 nextFollowOn；恢复可不填原因。 */
    public record StatusCommand(long revision, String status, String reasonCode, String reason, LocalDate nextFollowOn) {
        public StatusCommand(long revision, String status, String reason) {
            this(revision, status, null, reason, null);
        }
    }

    /** 流失原因码与默认文案；OTHER 由经办人自己填写。 */
    public static final Map<String, String> LOST_REASONS = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(Map.of(
            "PRICE", "价格超预算", "COMPETITOR", "选了同行", "PLAN", "方案不满意",
            "DEFERRED", "暂不建或推迟", "UNREACHABLE", "联系不上", "OTHER", "其他")));

    public record StepView(String code, String name, int order, String status, long revision,
                           LocalDate occurredOn, String note, Map<String, Object> fields,
                           List<Attachment> attachments, OffsetDateTime completedAt,
                           boolean canWrite, boolean canComplete, boolean canReopen,
                           List<String> completionErrors, String source, LocalDate nextFollowOn) { }

    public record RecordView(String id, String stepCode, String type, LocalDate occurredOn, String note,
                             OffsetDateTime createdAt, String actorUserId, String actorName, long revision,
                             Map<String, Object> fields, List<Attachment> attachments, LocalDate nextFollowOn) { }

    /** nextFollowOn 是客户当前的下次跟进日期（客户表投影），不是某一步的字段。 */
    public record Summary(String workflowId, int completedCount, int totalSteps, BigDecimal progressPercent,
                          String currentStepCode, String currentStepName, String nextStepCode,
                          String status, OffsetDateTime updatedAt, String nextContactOn, LocalDate nextFollowOn,
                          String statusReasonCode, String statusReason, boolean reconsulted) { }

    /** canChangeStatus 与流程是否进行中无关：流失、暂缓的客户仍由可办理人恢复。 */
    public record View(String workflowId, String customerId, String customerName, String companyId,
                       int templateVersion, String status, long revision, int completedCount,
                       int totalSteps, BigDecimal progressPercent, String currentStepCode,
                       String nextStepCode, boolean canWrite, List<StepView> steps,
                       List<RecordView> events, boolean hasMoreEvents, LocalDate nextFollowOn,
                       String statusReasonCode, String statusReason, boolean reconsulted, boolean canChangeStatus) { }
}
