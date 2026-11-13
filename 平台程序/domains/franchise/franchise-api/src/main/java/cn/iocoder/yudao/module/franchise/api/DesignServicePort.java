package cn.iocoder.yudao.module.franchise.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 设计能力服务端口（B2 板块 A / A1-A6，规则来源 docs/backend-parity/05-B2板块A设计赋能接入层-需求与验收.md）。
 *
 * <p>yudao-module-design 以 {@code FranchiseDesignAdapter} 实现，franchise-design 薄域经本端口代理
 * 灵感库 / 资产上传 / AI 设计两阶段管线 / 快速预算给 B 端员工；底层身份=组织服务账号（identity account.id），
 * 由调用方显式传入 {@code serviceAccountId}，端口实现不再解析会话。
 *
 * <p>约定（Codex 八轮评审定稿）：
 * <ul>
 *   <li>写方法统一返回 {@code {result, replayed}} 能力方原子收据——重放判定在能力方持有收据锁的事务内完成，
 *       适配器不得自行反查（并发不安全）；{@code replayed=true} 时调用方不重复落审计；</li>
 *   <li>端口零业务异常约定之外的错误一律抛 {@code ServiceException}（design 原码透传）；</li>
 *   <li>任务创建类方法要求调用线程已开事务（freezeInput 硬性要求），适配器以 REQUIRED 加入外层事务。</li>
 * </ul>
 */
public interface DesignServicePort {

    /** B 端操作者快照（审计留痕用；服务账号是纯技术占位，操作者身份以 Actor 追溯） */
    record Actor(long actorUserId, long actorPostId, String actorRoleCode, long orgId) {
        /** 兼容旧调用（无组织上下文 = App 链路由）。 */
        public Actor(long actorUserId, long actorPostId, String actorRoleCode) {
            this(actorUserId, actorPostId, actorRoleCode, 0L);
        }
    }

    // ========== 项目与任务（A2/A3/A4/A5） ==========

    ProjectCreated createProject(long serviceAccountId, DesignProjectCreate cmd, Actor actor);

    ProjectPageView listProjects(long serviceAccountId, String cursor, int limit);

    /** 项目详情；jobId 非空触发拉式晋升（任务须终态，否则业务码拒绝） */
    ProjectDetailView getProject(long serviceAccountId, long projectId, Long jobId);

    /** AI 任务轮询；属主校验=适配器比对 job.userId==serviceAccountId */
    AiJobView getAiJob(long serviceAccountId, long jobId);

    /** 请求取消生成任务（QUEUED/RUNNING/VALIDATING → CANCEL_REQUESTED；终态拒绝）。 */
    AiJobView cancelAiJob(long serviceAccountId, long jobId);

    /** 补充需求 AI 润色（按线路语境改写；限流与截断在服务内）→ polished 文本。 */
    String notePolish(long serviceAccountId, String note, String line);

    List<ResultVersionView> listResultVersions(long serviceAccountId, long projectId);

    JobSubmitted createFlatJob(long serviceAccountId, long projectId, FlatJobCreate cmd, Actor actor);

    JobSubmitted createElevationJob(long serviceAccountId, long projectId, ElevationJobCreate cmd, Actor actor);

    /** 选定平面并确认；项目已进入 ELEVATION 时 selectionSaved=true 且无版本（design 语义如实暴露）；
     *  expectedJobId 非空时校验候选所属任务一致（R15/R16 契约） */
    SelectionSaved confirmFlatCandidate(long serviceAccountId, long projectId, Long expectedJobId,
                                        long candidateId, Actor actor);

    SelectionSaved selectElevationCandidate(long serviceAccountId, long projectId, Long expectedJobId,
                                            long candidateId, Actor actor);

    RevisionSubmitted createRevisionRequest(long serviceAccountId, long projectId, RevisionCreate cmd, Actor actor);

    /**
     * 回写引用校验（R21 前置）：校验 resultVersion/estimate 属于该服务账号的该项目、kind 与结果类型匹配
     * （PLAN 须平面选定、FACADE 须立面选定，交叉 → SCHEME_KIND_MISMATCH），返回回写载荷所需的全部 ID 引用。
     */
    SchemeRef validateSchemeRef(long serviceAccountId, long projectId, String kind,
                                Long resultVersionId, Long estimateId);

    record DesignProjectCreate(String sourceType, Long refCaseId, Long sketchAssetId,
                               Map<String, Object> requirementInputs, String line, String idempotencyKey) {
    }

    record ProjectCreated(long projectId, boolean replayed) {
    }

    record ProjectListItem(long projectId, String sourceType, Long refCaseId, String stage,
                           String status, String name, String createTime) {
    }

    record ProjectPageView(List<ProjectListItem> list, String nextCursor) {
    }

    /** 详情以字段映射直出（含 candidates/allowedActions/imageOptions 等），键名与 design 侧一致 */
    record ProjectDetailView(Map<String, Object> fields) {
    }

    record AiJobView(long jobId, String status, int requestedCount, int acceptedCount, int progress,
                     String phase, List<String> allowedActions) {
    }

    record ResultVersionView(long versionId, long version, boolean superseded, Long selectedFlatAssetId,
                             Long selectedElevationAssetId, String configSnapshotJson, String createTime) {
    }

    record FlatJobCreate(int count, String resolution, String orientation, String idempotencyKey) {
    }

    /** 立面参数稳定编码入库：styleCode/roofType/material/color 全部参与不可变请求指纹 */
    record ElevationJobCreate(int count, String resolution, String orientation,
                              String styleCode, String roofType, String material, String color,
                              String idempotencyKey) {
    }

    record JobSubmitted(long jobId, boolean replayed) {
    }

    record SelectionSaved(Long resultVersionId, Long version, boolean selectionSaved, boolean replayed) {
    }

    record RevisionCreate(String reason, Map<String, Object> configUpdates, Integer count,
                          String resolution, String orientation, String idempotencyKey) {
    }

    record RevisionSubmitted(long requestId, long newJobId, boolean replayed) {
    }

    /** 回写引用（全部为 ID 引用，时效 URL 由调用方按需现取） */
    record SchemeRef(Long resultVersionId, List<Long> flatCandidateIds, Long selectedFlatAssetId,
                     Long selectedElevationAssetId, String configSnapshotJson,
                     Long estimateId, Long totalLowCents, Long totalHighCents, Integer estimateArea) {
    }

    /** 资产校验（板块 B 合同文件引用）：存在+ACCEPTED+owner=serviceAccountId，否则 RESOURCE_FORBIDDEN */
    void validateAsset(long serviceAccountId, long assetId);

    // ========== 灵感库（A1） ==========

    CasePage listCases(CaseQuery query);

    List<StyleOption> listCaseStyles();

    CaseDetailView getCaseDetail(long caseId);

    void favoriteCase(long serviceAccountId, long caseId);

    void unfavoriteCase(long serviceAccountId, long caseId);

    CasePage listFavorites(long serviceAccountId, String cursor, int limit);

    record CaseQuery(String styleCode, Integer floorCount, BigDecimal faceWidth, BigDecimal depth,
                     String keyword, String cursor, int limit) {
    }

    record StyleOption(String code, String label) {
    }

    record CasePage(List<Map<String, Object>> list, String nextCursor) {
    }

    record CaseDetailView(Map<String, Object> fields) {
    }

    // ========== 快速预算（A6） ==========

    /** 区间预算规则按 (region, structure, grade) 三元组选择；无生效规则返回 null */
    RuleInfoView budgetRuleInfo(String regionCode, String structureType, String materialGrade);

    EstimateCreated createBudgetEstimate(long serviceAccountId, long projectId,
                                         BudgetEstimateCreate cmd, Actor actor);

    record RuleInfoView(String regionCode, String structureType, String materialGrade,
                        long ruleVersionId, long lowCentsPerSqm, long highCentsPerSqm, String effectiveAt) {
    }

    /** buildingArea 为整数（1-10000，design 约束；与回写面积的两位小数口径分离） */
    record BudgetEstimateCreate(String regionCode, String structureType, String materialGrade,
                                int buildingArea, Long resultVersionId, String idempotencyKey) {
    }

    record EstimateCreated(long estimateId, long totalLowCents, long totalHighCents,
                           long ruleVersionId, boolean replayed) {
    }

    // ========== 资产（A2 上传 / A5 取图） ==========

    /** 新契约不返回 expiresAt 值（票据 VO 该字段现未赋值）；uploadUrl 为 COS PUT 或 local:// */
    UploadTicketView createUploadTicket(long serviceAccountId, UploadTicketCreate cmd);

    /**
     * 完成上传（R7）：首次调用触发审核；终态重放=幂等读。
     * ModerationUnavailableException → PENDING（退避期）；ASSET_VALIDATION_FAILED → 属主校验后重读实际状态
     * （PENDING/VALIDATING 映射 PENDING，仅实际 REJECTED 返回原因）。
     */
    AssetCompleted completeUpload(long serviceAccountId, long assetId);

    /** COS 模式返回 {签名URL, expiresAt=URL 到期}；local 模式由 dev 通道承载，端口统一走票据换 URL */
    DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId);

    record UploadTicketCreate(String assetType, String mimeType, long sizeBytes, String sha256) {
    }

    record UploadTicketView(long assetId, String uploadUrl) {
    }

    record AssetCompleted(long assetId, String uploadStatus, String rejectedReason) {
    }

    record DownloadUrlView(String downloadUrl, Long expiresAt) {
    }
}
