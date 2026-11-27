package cn.iocoder.yudao.module.franchise.api.capability;

import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import java.util.Locale;
import java.util.Objects;

/** Version 1 internal contract. IDs remain 64-bit integers; business keys are never truncated. */
public final class DesignCapabilityContract {
    private DesignCapabilityContract() { }

    public enum Operation {
        FLOW_READ("designFlow", DesignFlowCall.class, Object.class, false, false),
        FLOW_WRITE("designFlow", DesignFlowCall.class, Object.class, true, false),
        FLOW_FLAT_JOB("designFlow", DesignFlowCall.class, Object.class, true, true),
        FLOW_ELEVATION_JOB("designFlow", DesignFlowCall.class, Object.class, true, true),
        FLOW_REVISION("designFlow", DesignFlowCall.class, Object.class, true, true),
        FLOW_BUDGET("designFlow", DesignFlowCall.class, Object.class, true, true),
        CREATE_PROJECT("createProject", DesignServicePort.DesignProjectCreate.class, DesignServicePort.ProjectCreated.class, true, false),
        LIST_PROJECTS("listProjects", Page.class, DesignServicePort.ProjectPageView.class, false, false),
        GET_PROJECT("getProject", Project.class, DesignServicePort.ProjectDetailView.class, true, false),
        GET_AI_JOB("getAiJob", Job.class, DesignServicePort.AiJobView.class, false, false),
        CANCEL_AI_JOB("cancelAiJob", Job.class, DesignServicePort.AiJobView.class, true, false),
        NOTE_POLISH("notePolish", Note.class, String.class, true, true),
        LIST_RESULT_VERSIONS("listResultVersions", Project.class, DesignServicePort.ResultVersionView.class, false, false),
        CREATE_FLAT_JOB("createFlatJob", FlatJob.class, DesignServicePort.JobSubmitted.class, true, true),
        CREATE_ELEVATION_JOB("createElevationJob", ElevationJob.class, DesignServicePort.JobSubmitted.class, true, true),
        CONFIRM_FLAT_CANDIDATE("confirmFlatCandidate", Selection.class, DesignServicePort.SelectionSaved.class, true, false),
        SELECT_ELEVATION_CANDIDATE("selectElevationCandidate", Selection.class, DesignServicePort.SelectionSaved.class, true, false),
        CREATE_REVISION_REQUEST("createRevisionRequest", Revision.class, DesignServicePort.RevisionSubmitted.class, true, true),
        VALIDATE_SCHEME_REF("validateSchemeRef", Scheme.class, DesignServicePort.SchemeRef.class, false, false),
        LIST_CASES("listCases", DesignServicePort.CaseQuery.class, DesignServicePort.CasePage.class, false, false),
        LIST_CASE_STYLES("listCaseStyles", Empty.class, DesignServicePort.StyleOption.class, false, false),
        GET_CASE_DETAIL("getCaseDetail", Case.class, DesignServicePort.CaseDetailView.class, false, false),
        FAVORITE_CASE("favoriteCase", Case.class, Boolean.class, true, false),
        UNFAVORITE_CASE("unfavoriteCase", Case.class, Boolean.class, true, false),
        LIST_FAVORITES("listFavorites", Page.class, DesignServicePort.CasePage.class, false, false),
        BUDGET_RULE_INFO("budgetRuleInfo", BudgetRule.class, DesignServicePort.RuleInfoView.class, false, false),
        CREATE_BUDGET_ESTIMATE("createBudgetEstimate", Budget.class, DesignServicePort.EstimateCreated.class, true, true),
        CREATE_UPLOAD_TICKET("createUploadTicket", DesignServicePort.UploadTicketCreate.class, DesignServicePort.UploadTicketView.class, true, false),
        COMPLETE_UPLOAD("completeUpload", Asset.class, DesignServicePort.AssetCompleted.class, true, false),
        // Short-lived credentials must be refreshed, never replayed from a durable business receipt.
        CREATE_DOWNLOAD_URL("createDownloadUrl", Asset.class, DesignServicePort.DownloadUrlView.class, false, false),
        QUERY_RECEIPT(null, ReceiptQuery.class, Receipt.class, false, false),
        GET_SETTLEMENT(null, Job.class, Settlement.class, false, false);

        private final String portMethod;
        private final Class<?> payloadType;
        private final Class<?> resultType;
        private final boolean write;
        private final boolean paid;

        Operation(String portMethod, Class<?> payloadType, Class<?> resultType, boolean write, boolean paid) {
            this.portMethod = portMethod;
            this.payloadType = payloadType;
            this.resultType = resultType;
            this.write = write;
            this.paid = paid;
        }
        public String portMethod() { return portMethod; }
        public Class<?> payloadType() { return payloadType; }
        public Class<?> resultType() { return resultType; }
        public boolean write() { return write; }
        public boolean mutating() { return write; }
        public boolean requiresReceipt(Object payload) {
            return write && (this != GET_PROJECT || ((Project) payload).jobId() != null);
        }
        public boolean paid() { return paid; }
        /** Provider cost tracking stays enabled; these operations are free to the company pool. */
        public boolean freeBudget() { return this == FLOW_BUDGET || this == CREATE_BUDGET_ESTIMATE; }
        public boolean listResult() { return this == LIST_RESULT_VERSIONS || this == LIST_CASE_STYLES; }
        public String action() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
        public String path() { return "/internal-api/design/v1/platform/" + action(); }
        public static Operation fromAction(String action) {
            for (Operation operation : values()) {
                if (operation.action().equals(action)) return operation;
            }
            throw new IllegalArgumentException("Unknown design action");
        }
    }

    public record Request<T>(String callerAppId, long orgId, DesignServicePort.Actor actor,
                             Operation operation, String requestKey, String nonce, T payload) {
        public Request {
            requireText(callerAppId, 80, "callerAppId");
            if (orgId <= 0) throw new IllegalArgumentException("orgId required");
            Objects.requireNonNull(operation, "operation");
            if (actor == null || actor.actorUserId() <= 0 || actor.actorPostId() <= 0) {
                throw new IllegalArgumentException("actor required");
            }
            requireText(actor.actorRoleCode(), 80, "actorRoleCode");
            requireText(requestKey, 200, "requestKey");
            requireText(nonce, 128, "nonce");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public enum ReceiptStatus { NOT_FOUND, PROCESSING, SUCCEEDED, FAILED, UNKNOWN }
    public record Reply<T>(int code, String message, T result, Receipt receipt, boolean replayed) { }
    public record Receipt(String receiptId, Operation operation, String requestKey, String fingerprint,
                          ReceiptStatus status, Object result, Long jobId, Integer errorCode, String errorMessage) { }
    public record ReceiptQuery(Operation operation, String requestKey) {
        public ReceiptQuery {
            if (operation == null || !operation.write()) throw new IllegalArgumentException("write operation required");
            requireText(requestKey, 200, "requestKey");
        }
    }

    /** All costs are cumulative integer micro-CNY. Units are facts, never B's retail prices. */
    public record Settlement(long jobId, long version, String status, boolean terminal,
                             int requestedCount, int validResultCount,
                             long cumulativeReservedCostMicros, long cumulativeChargedCostMicros,
                             long cumulativeRefundedCostMicros, long cumulativePromptUnits,
                             long cumulativeImageUnits, long cumulativeUsageUnits) {
        public Settlement {
            if (jobId <= 0 || version <= 0 || requestedCount < 0 || validResultCount < 0
                    || validResultCount > requestedCount || cumulativeReservedCostMicros < 0
                    || cumulativeChargedCostMicros < 0 || cumulativeRefundedCostMicros < 0
                    || cumulativePromptUnits < 0 || cumulativeImageUnits < 0 || cumulativeUsageUnits < 0) {
                throw new IllegalArgumentException("Invalid cumulative settlement facts");
            }
            requireText(status, 40, "status");
        }
    }

    /** B snapshots its own prices before first dispatch. Values use B's integer wallet points. */
    public record PlatformPriceSnapshot(String priceId, long version, long promptUnitPoints,
                                        long imageUnitPoints, long usageUnitPoints, long maximumPoints,
                                        int requestedImageCount, long requestedPromptUnits, long requestedUsageUnits,String companyPriceVersion) {
        public PlatformPriceSnapshot(String priceId,long version,long prompt,long image,long usage,long maximum,int count,long promptCount,long usageCount) {
            this(priceId,version,prompt,image,usage,maximum,count,promptCount,usageCount,null);
        }
        public PlatformPriceSnapshot {
            requireText(priceId, 120, "priceId");
            if (version <= 0 || promptUnitPoints < 0 || imageUnitPoints < 0 || usageUnitPoints < 0 || maximumPoints < 0
                    || requestedImageCount < 0 || requestedPromptUnits < 0 || requestedUsageUnits < 0) {
                throw new IllegalArgumentException("Invalid platform price snapshot");
            }
        }
    }

    public record Empty() { }
    /** Pagination normalization and cursor validation remain with the existing capability implementation. */
    public record Page(String cursor, int limit) { }
    public record Project(long projectId, Long jobId) { }
    public record Job(long jobId) { }
    public record Asset(long assetId) { }
    public record Case(long caseId) { }
    public record Note(String note, String line) { }
    public record FlatJob(long projectId, DesignServicePort.FlatJobCreate command) { }
    public record ElevationJob(long projectId, DesignServicePort.ElevationJobCreate command) { }
    public record Selection(long projectId, Long expectedJobId, long candidateId) { }
    public record Revision(long projectId, DesignServicePort.RevisionCreate command) { }
    public record Scheme(long projectId, String kind, Long resultVersionId, Long estimateId) { }
    public record BudgetRule(String regionCode, String structureType, String materialGrade) { }
    public record Budget(long projectId, DesignServicePort.BudgetEstimateCreate command) { }

    public static void requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is required and must be <= " + maximum + " characters");
        }
    }
}
