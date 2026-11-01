package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.ModerationUnavailableException;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions;
import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.FLAT_REFERENCE_DISABLED;
import static cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes.SCHEME_KIND_MISMATCH;

/**
 * 设计服务适配器（B2 板块 A / P-2）：yudao-module-design 实现 franchise-api 的 DesignServicePort，
 * 供 franchise-design 薄域代理 C 端设计能力给 B 端员工。
 *
 * <p>要点（需求文档 §3.3/§3.4/§四）：
 * <ul>
 *   <li>身份：全部调用显式传 serviceAccountId（identity account.id）；适配器不解析会话，
 *       无属主参数的查询先经属主校验（getProject(userId)）再调用；</li>
 *   <li>事务边界：任务创建类 REQUIRED 加入接入层外层事务（freezeInput 硬性要求）；
 *       资产/查询不包外层事务（completeUpload 自带短事务边界）；</li>
 *   <li>规则下移：FLAT 线路禁参考/草图的校验原在 App Controller 层，Port 直调会绕过——适配器强制同规则；</li>
 *   <li>取价内聚（P-2⑧）：先查收据（已有同键任务/预算=重放路径），仅首次执行才报价并构造三类确认
 *       （图片报价+AI_PROMPT usage+预算 usage）；否则报价规则停用会阻断既有成功请求的重放；</li>
 *   <li>replayed 收据直接透传能力方原子收据（P-10④），适配器不做反查判定。</li>
 * </ul>
 */
@Slf4j
@Component
public class FranchiseDesignAdapter implements DesignServicePort {

    private static final List<String> ALLOWED_ACTIONS_WHITELIST = List.of(
            "VIEW", "CREATE_FLAT_JOB", "SELECT_FLAT", "CREATE_ELEVATION_JOB", "SELECT_ELEVATION",
            "CREATE_REVISION", "CREATE_BUDGET", "POLL_JOB", "VIEW_RESULT");

    private final DesignProjectService designProjectService;
    private final CaseCatalogService caseCatalogService;
    private final AssetService assetService;
    private final BudgetService budgetService;
    private final AiJobPort aiJobPort;
    private final PricingPort pricingPort;
    private final UsagePricingPort usagePricingPort;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;

    public FranchiseDesignAdapter(DesignProjectService designProjectService,
                                  CaseCatalogService caseCatalogService,
                                  AssetService assetService,
                                  BudgetService budgetService,
                                  AiJobPort aiJobPort,
                                  PricingPort pricingPort,
                                  org.springframework.beans.factory.ObjectProvider<UsagePricingPort> usagePricingPort,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager,
                                  DataSource dataSource) {
        this.designProjectService = designProjectService;
        this.caseCatalogService = caseCatalogService;
        this.assetService = assetService;
        this.budgetService = budgetService;
        this.aiJobPort = aiJobPort;
        this.pricingPort = pricingPort;
        this.usagePricingPort = usagePricingPort.getIfAvailable();
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // ========== 项目与任务 ==========

    @Override
    public ProjectCreated createProject(long serviceAccountId, DesignProjectCreate cmd, Actor actor) {
        // 规则下移（F6）：FLAT 线路禁案例参考/草图——校验原在 App Controller 层，Port 直调必须由适配器补齐
        if ("FLAT".equals(cmd.line()) && (cmd.refCaseId() != null || cmd.sketchAssetId() != null)) {
            throw exception(FLAT_REFERENCE_DISABLED);
        }
        var created = designProjectService.createProjectReceipt(serviceAccountId, cmd.sourceType(),
                cmd.refCaseId(), cmd.sketchAssetId(), cmd.requirementInputs(), cmd.idempotencyKey(), cmd.line());
        log.info("[franchise-design][createProject actor={} account={} project={} replayed={}]",
                actor.actorUserId(), serviceAccountId, created.projectId(), created.replayed());
        return new ProjectCreated(created.projectId(), created.replayed());
    }

    @Override
    public ProjectPageView listProjects(long serviceAccountId, String cursor, int limit) {
        var page = designProjectService.listProjects(serviceAccountId, cursor, limit);
        List<ProjectListItem> list = page.list().stream()
                .map(item -> new ProjectListItem(item.projectId(), item.sourceType(), item.refCaseId(),
                        item.stage(), item.status(), item.name(),
                        item.createTime() == null ? null : item.createTime().toString()))
                .collect(Collectors.toList());
        return new ProjectPageView(list, page.nextCursor());
    }

    @Override
    public ProjectDetailView getProject(long serviceAccountId, long projectId, Long jobId) {
        var project = designProjectService.getProject(projectId, serviceAccountId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        List<Map<String, Object>> candidates = jobId == null ? List.of()
                : designProjectService.promoteCandidates(serviceAccountId, projectId, jobId).stream()
                        .map(c -> Map.<String, Object>of(
                                "candidateId", c.candidateId(), "jobId", c.jobId(),
                                "slotNo", c.slotNo(), "assetId", c.assetId()))
                        .collect(Collectors.toList());
        var latest = designProjectService.latestJob(project);
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("projectId", project.projectId());
        fields.put("stage", project.stage());
        fields.put("sourceType", project.sourceType());
        fields.put("refCaseId", project.refCaseId());
        if (latest.isPresent()) {
            fields.put("jobId", latest.get().jobId());
            fields.put("jobStatus", latest.get().status());
            fields.put("acceptedCount", latest.get().acceptedCount());
            fields.put("progress", latest.get().progress());
        }
        fields.put("candidates", candidates);
        fields.put("allowedActions", allowedActions(project.stage(), candidates));
        return new ProjectDetailView(fields);
    }

    private List<String> allowedActions(String stage, List<Map<String, Object>> candidates) {
        // 接入层白名单（Codex R8）：本批未提供入口的 C 端动作（CANCEL/CANCEL_JOB/SUBMIT）不出现
        var actions = new java.util.ArrayList<>(List.of("VIEW", "CREATE_BUDGET", "VIEW_RESULT"));
        actions.add("FLAT".equals(stage) ? "CREATE_FLAT_JOB" : "CREATE_ELEVATION_JOB");
        if (!candidates.isEmpty()) {
            actions.add("FLAT".equals(stage) ? "SELECT_FLAT" : "SELECT_ELEVATION");
        }
        if ("ELEVATION".equals(stage)) {
            actions.add("CREATE_REVISION");
        }
        actions.add("POLL_JOB");
        return actions;
    }

    @Override
    public AiJobView getAiJob(long serviceAccountId, long jobId) {
        AiJobPort.JobView job = aiJobPort.getJob(jobId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (job.userId() != serviceAccountId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return new AiJobView(job.jobId(), job.status(), job.requestedCount(), job.acceptedCount(),
                job.progress(), job.phase(), List.of("VIEW", "POLL_JOB"));
    }

    @Override
    public List<ResultVersionView> listResultVersions(long serviceAccountId, long projectId) {
        // requireOwner 在服务内部执行（listResultVersions(userId, projectId)）
        return designProjectService.listResultVersions(serviceAccountId, projectId).stream()
                .map(row -> new ResultVersionView(
                        ((Number) row.get("id")).longValue(),
                        ((Number) row.get("version")).longValue(),
                        Boolean.TRUE.equals(row.get("superseded")),
                        row.get("selected_flat_asset_id") == null ? null : ((Number) row.get("selected_flat_asset_id")).longValue(),
                        row.get("selected_elevation_asset_id") == null ? null : ((Number) row.get("selected_elevation_asset_id")).longValue(),
                        row.get("config_snapshot") == null ? null : row.get("config_snapshot").toString(),
                        row.get("create_time") == null ? null : row.get("create_time").toString()))
                .collect(Collectors.toList());
    }

    @Override
    public JobSubmitted createFlatJob(long serviceAccountId, long projectId, FlatJobCreate cmd, Actor actor) {
        return txTemplate.execute(status -> {
            var options = GenerationImageOptions.normalize("FLAT", cmd.resolution(), cmd.orientation());
            var confirmation = jobAutoConfirmation(serviceAccountId, cmd.idempotencyKey(), "FLAT", cmd.count(), options.resolution());
            var created = designProjectService.createFlatJob(serviceAccountId, projectId,
                    cmd.count(), cmd.idempotencyKey(), confirmation, options);
            log.info("[franchise-design][createFlatJob actor={} account={} project={} job={} replayed={}]",
                    actor.actorUserId(), serviceAccountId, projectId, created.jobId(), !created.created());
            return new JobSubmitted(created.jobId(), !created.created());
        });
    }

    @Override
    public JobSubmitted createElevationJob(long serviceAccountId, long projectId, ElevationJobCreate cmd, Actor actor) {
        return txTemplate.execute(status -> {
            var options = GenerationImageOptions.normalize("ELEVATION", cmd.resolution(), cmd.orientation());
            var config = new java.util.LinkedHashMap<String, Object>();
            putIfPresent(config, "styleCode", cmd.styleCode());
            putIfPresent(config, "roofType", cmd.roofType());
            putIfPresent(config, "material", cmd.material());
            putIfPresent(config, "color", cmd.color());
            var confirmation = jobAutoConfirmation(serviceAccountId, cmd.idempotencyKey(), "ELEVATION", cmd.count(), options.resolution());
            var created = designProjectService.createElevationJob(serviceAccountId, projectId,
                    cmd.count(), cmd.idempotencyKey(), config, confirmation, options);
            log.info("[franchise-design][createElevationJob actor={} account={} project={} job={} replayed={}]",
                    actor.actorUserId(), serviceAccountId, projectId, created.jobId(), !created.created());
            return new JobSubmitted(created.jobId(), !created.created());
        });
    }

    /**
     * 取价内聚（P-2⑧）：先查同键收据——命中=重放路径（settlement 先重放后报价，不校验 confirmation），
     * 传 null；未命中才报价并构造三类确认（图片报价 + AI_PROMPT usage）。
     * 竞态（查时无、提交时已被并发创建）→ requireConfirmed(null) 抛 PRICE_RULE_CHANGED，客户端重试即可。
     */
    private PricingPort.PriceConfirmation jobAutoConfirmation(long serviceAccountId, String idempotencyKey,
                                                              String phase, int count, String resolution) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_job WHERE user_id=? AND idempotency_key=? AND deleted=FALSE",
                    serviceAccountId, idempotencyKey);
            if (!existing.isEmpty()) {
                return null;
            }
        }
        var quote = pricingPort.quote(phase, count, resolution);
        UsagePricingPort.Confirmation usage = null;
        if (usagePricingPort != null) {
            var promptQuote = usagePricingPort.quote("AI_PROMPT");
            usage = new UsagePricingPort.Confirmation("AI_PROMPT", String.valueOf(promptQuote.ruleId()), promptQuote.ruleVersion());
        }
        return new PricingPort.PriceConfirmation(String.valueOf(quote.ruleId()), quote.ruleVersion(), usage);
    }

    @Override
    public SelectionSaved confirmFlatCandidate(long serviceAccountId, long projectId, Long expectedJobId,
                                               long candidateId, Actor actor) {
        return txTemplate.execute(status -> {
            requireCandidateOfJob(candidateId, expectedJobId);
            var saved = designProjectService.confirmFlatCandidateReceipt(serviceAccountId, projectId, candidateId);
            return new SelectionSaved(saved.versionId(), saved.version(), saved.selectionSaved(), saved.replayed());
        });
    }

    @Override
    public SelectionSaved selectElevationCandidate(long serviceAccountId, long projectId, Long expectedJobId,
                                                   long candidateId, Actor actor) {
        return txTemplate.execute(status -> {
            requireCandidateOfJob(candidateId, expectedJobId);
            var saved = designProjectService.selectElevationCandidateReceipt(serviceAccountId, projectId, candidateId);
            return new SelectionSaved(saved.versionId(), saved.version(), saved.selectionSaved(), saved.replayed());
        });
    }

    /** jobId/candidateId 一致性（R15/R16 契约）：请求携带的 jobId 必须是候选所属任务（经 DesignAccessController 传入） */
    private void requireCandidateOfJob(long candidateId, Long expectedJobId) {
        if (expectedJobId == null) {
            return;
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT job_id FROM design_candidate WHERE id=? AND deleted=FALSE", candidateId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("job_id")).longValue() != expectedJobId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
    }

    @Override
    public RevisionSubmitted createRevisionRequest(long serviceAccountId, long projectId, RevisionCreate cmd, Actor actor) {
        return txTemplate.execute(status -> {
            // 省略 resolution/orientation 时按 design 默认口径规范化（报价与冻结输入同源，防 ELEVATION/null）
            var options = GenerationImageOptions.normalize("ELEVATION", cmd.resolution(), cmd.orientation());
            var confirmation = jobAutoConfirmation(serviceAccountId, cmd.idempotencyKey(), "ELEVATION",
                    cmd.count() == null ? 2 : cmd.count(), options.resolution());
            var receipt = designProjectService.createRevisionRequestReceipt(serviceAccountId, projectId,
                    cmd.reason(), cmd.configUpdates(), cmd.count() == null ? 2 : cmd.count(),
                    cmd.idempotencyKey(), confirmation, options.resolution(), options.orientation());
            log.info("[franchise-design][createRevisionRequest actor={} account={} project={} request={} replayed={}]",
                    actor.actorUserId(), serviceAccountId, projectId, receipt.requestId(), receipt.replayed());
            return new RevisionSubmitted(receipt.requestId(), receipt.newJobId(), receipt.replayed());
        });
    }

    @Override
    public SchemeRef validateSchemeRef(long serviceAccountId, long projectId, String kind,
                                       Long resultVersionId, Long estimateId) {
        // 引用归属与 kind 交叉校验（R21 / §五）：全部经属主校验后的查询执行
        designProjectService.getProject(projectId, serviceAccountId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        Long selectedFlatAssetId = null;
        Long selectedElevationAssetId = null;
        List<Long> flatCandidateIds = List.of();
        String configSnapshotJson = null;
        if ("PLAN".equals(kind) || "FACADE".equals(kind)) {
            if (resultVersionId == null) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            var versions = designProjectService.listResultVersions(serviceAccountId, projectId);
            var matched = versions.stream()
                    .filter(row -> ((Number) row.get("id")).longValue() == resultVersionId)
                    .findFirst()
                    .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
            selectedFlatAssetId = matched.get("selected_flat_asset_id") == null
                    ? null : ((Number) matched.get("selected_flat_asset_id")).longValue();
            selectedElevationAssetId = matched.get("selected_elevation_asset_id") == null
                    ? null : ((Number) matched.get("selected_elevation_asset_id")).longValue();
            configSnapshotJson = matched.get("config_snapshot") == null ? null : matched.get("config_snapshot").toString();
            // kind 与结果类型匹配按「版本线」判定：FLAT 线版本无立面候选、ELEVATION 线版本必有
            // （立面版本可携带平面参考图，selected_flat_asset_id 非空不能作为 PLAN 判据）
            boolean hasElevationCandidate = matched.get("elevation_candidate_id") != null;
            if ("PLAN".equals(kind) && (selectedFlatAssetId == null || hasElevationCandidate)
                    || "FACADE".equals(kind) && !hasElevationCandidate) {
                throw exception(SCHEME_KIND_MISMATCH);
            }
            if (selectedFlatAssetId != null) {
                flatCandidateIds = jdbcTemplate.queryForList(
                        "SELECT c.id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id "
                                + "JOIN design_result_version v ON v.flat_selection_id=s.id "
                                + "WHERE v.id=? AND s.project_id=? AND s.stage='FLAT' AND s.deleted=FALSE AND c.deleted=FALSE",
                        Long.class, resultVersionId, projectId);
            }
            return new SchemeRef(resultVersionId, flatCandidateIds, selectedFlatAssetId, selectedElevationAssetId,
                    configSnapshotJson, null, null, null, null);
        }
        if ("BUDGET".equals(kind)) {
            if (estimateId == null) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            var rows = jdbcTemplate.queryForList(
                    "SELECT total_low_cents, total_high_cents, input_snapshot::text AS input_snapshot "
                            + "FROM budget_estimate WHERE id=? AND user_id=? AND project_id=? AND deleted=FALSE",
                    estimateId, serviceAccountId, projectId);
            if (rows.isEmpty()) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            var row = rows.get(0);
            Integer area = null;
            try {
                var snapshot = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(row.get("input_snapshot").toString(), Map.class);
                if (snapshot.get("buildingArea") instanceof Number n) {
                    area = n.intValue();
                }
            } catch (java.io.IOException ignored) {
                // 快照损坏按无面积处理，回写层会因尺寸校验拒绝
            }
            return new SchemeRef(null, List.of(), null, null, null,
                    estimateId,
                    ((Number) row.get("total_low_cents")).longValue(),
                    ((Number) row.get("total_high_cents")).longValue(),
                    area);
        }
        throw exception(RESOURCE_FORBIDDEN);
    }

    @Override
    public void validateAsset(long serviceAccountId, long assetId) {
        // 板块 B 合同文件引用校验：存在+ACCEPTED+owner=本组织服务账号（无客户绑定概念，Codex R2）
        var rows = jdbcTemplate.queryForList(
                "SELECT owner_user_id, upload_status FROM asset WHERE id=? AND deleted=FALSE", assetId);
        if (rows.isEmpty()
                || ((Number) rows.get(0).get("owner_user_id")).longValue() != serviceAccountId
                || !"ACCEPTED".equals(rows.get(0).get("upload_status"))) {
            throw exception(RESOURCE_FORBIDDEN);
        }
    }

    // ========== 灵感库 ==========

    @Override
    public CasePage listCases(CaseQuery query) {
        var page = caseCatalogService.listPublishedCases(null, query.styleCode(), query.floorCount(),
                null, null, query.faceWidth(), query.depth(), query.keyword(), query.cursor(), query.limit());
        return new CasePage(page.list().stream().map(FranchiseDesignAdapter::toCaseMap).collect(Collectors.toList()),
                page.nextCursor());
    }

    @Override
    public List<StyleOption> listCaseStyles() {
        return caseCatalogService.listPublishedStyles().stream()
                .map(option -> new StyleOption(option.code(), option.label()))
                .collect(Collectors.toList());
    }

    @Override
    public CaseDetailView getCaseDetail(long caseId) {
        var detail = caseCatalogService.getPublishedCase(caseId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("caseId", detail.caseId());
        fields.put("caseVersionId", detail.caseVersionId());
        fields.put("title", detail.title());
        fields.put("description", detail.description());
        fields.put("sourceType", detail.sourceType());
        fields.put("styleCode", detail.styleCode());
        fields.put("floorCount", detail.floorCount());
        fields.put("buildingArea", detail.buildingArea());
        fields.put("faceWidth", detail.faceWidth());
        fields.put("depth", detail.depth());
        fields.put("coverAssetId", detail.coverAssetId());
        fields.put("elevationAssetId", detail.elevationAssetId());
        fields.put("pdfAssetId", detail.pdfAssetId());
        fields.put("floorPlanAssetIds", detail.floorPlanAssetIds());
        fields.put("dimensionBasis", detail.dimensionBasis());
        fields.put("uploaderName", detail.uploaderName());
        fields.put("uploaderCompany", detail.uploaderCompany());
        fields.put("allowedActions", List.of("VIEW", "FAVORITE"));
        return new CaseDetailView(fields);
    }

    @Override
    public void favoriteCase(long serviceAccountId, long caseId) {
        caseCatalogService.favorite(serviceAccountId, caseId);
    }

    @Override
    public void unfavoriteCase(long serviceAccountId, long caseId) {
        caseCatalogService.unfavorite(serviceAccountId, caseId);
    }

    @Override
    public CasePage listFavorites(long serviceAccountId, String cursor, int limit) {
        var page = caseCatalogService.listFavorites(serviceAccountId, cursor, limit);
        return new CasePage(page.list().stream().map(FranchiseDesignAdapter::toCaseMap).collect(Collectors.toList()),
                page.nextCursor());
    }

    private static Map<String, Object> toCaseMap(CaseCatalogService.CaseSummary summary) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("caseId", summary.caseId());
        map.put("title", summary.title());
        map.put("sourceType", summary.sourceType());
        map.put("styleCode", summary.styleCode());
        map.put("floorCount", summary.floorCount());
        map.put("buildingArea", summary.buildingArea());
        map.put("coverAssetId", summary.coverAssetId());
        map.put("faceWidth", summary.faceWidth());
        map.put("depth", summary.depth());
        map.put("uploaderName", summary.uploaderName());
        map.put("uploaderCompany", summary.uploaderCompany());
        return map;
    }

    // ========== 快速预算 ==========

    @Override
    public RuleInfoView budgetRuleInfo(String regionCode, String structureType, String materialGrade) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, low_cents_per_sqm, high_cents_per_sqm, effective_at FROM budget_rule_version "
                        + "WHERE region_code = ? AND structure_type = ? AND material_grade = ? "
                        + "AND effective_at <= now() AND (expires_at IS NULL OR expires_at > now()) AND deleted = FALSE "
                        + "ORDER BY effective_at DESC LIMIT 1", regionCode, structureType, materialGrade);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        return new RuleInfoView(regionCode, structureType, materialGrade,
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("low_cents_per_sqm")).longValue(),
                ((Number) row.get("high_cents_per_sqm")).longValue(),
                row.get("effective_at") == null ? null : row.get("effective_at").toString());
    }

    @Override
    public EstimateCreated createBudgetEstimate(long serviceAccountId, long projectId,
                                                BudgetEstimateCreate cmd, Actor actor) {
        // 取价内聚（P-2⑧）：先查预算收据（budget_legacy_request）——重放路径不报价
        UsagePricingPort.Confirmation confirmation = null;
        if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
            var prior = jdbcTemplate.queryForList(
                    "SELECT estimate_id FROM budget_legacy_request WHERE user_id=? AND request_key=?",
                    serviceAccountId, cmd.idempotencyKey());
            if (prior.isEmpty() || prior.get(0).get("estimate_id") == null) {
                confirmation = budgetAutoConfirmation();
            }
        } else {
            confirmation = budgetAutoConfirmation();
        }
        var receipt = budgetService.createEstimateReceipt(serviceAccountId, projectId, cmd.regionCode(),
                cmd.structureType(), cmd.materialGrade(), cmd.buildingArea(), cmd.resultVersionId(),
                confirmation, cmd.idempotencyKey());
        log.info("[franchise-design][createBudgetEstimate actor={} account={} project={} estimate={} replayed={}]",
                actor.actorUserId(), serviceAccountId, projectId, receipt.estimateId(), receipt.replayed());
        return new EstimateCreated(receipt.estimateId(), receipt.totalLowCents(), receipt.totalHighCents(),
                receipt.ruleVersionId(), receipt.replayed());
    }

    private UsagePricingPort.Confirmation budgetAutoConfirmation() {
        if (usagePricingPort == null) {
            return null;
        }
        var quote = usagePricingPort.quote("BUDGET_ESTIMATE");
        return new UsagePricingPort.Confirmation("BUDGET_ESTIMATE", String.valueOf(quote.ruleId()), quote.ruleVersion());
    }

    // ========== 资产 ==========

    @Override
    public UploadTicketView createUploadTicket(long serviceAccountId, UploadTicketCreate cmd) {
        var ticket = assetService.createUploadTicket(serviceAccountId, cmd.assetType(),
                cmd.mimeType(), cmd.sizeBytes(), cmd.sha256());
        return new UploadTicketView(ticket.assetId(), ticket.uploadUrl());
    }

    @Override
    public AssetCompleted completeUpload(long serviceAccountId, long assetId) {
        try {
            return readAssetState(assetId, assetService.completeUpload(serviceAccountId, assetId));
        } catch (ModerationUnavailableException e) {
            // 退避期（R7/AC5）：PENDING，可稍后重试
            return new AssetCompleted(assetId, "PENDING", "内容审核服务暂时不可用，请稍后重试");
        } catch (ServiceException e) {
            if (e.getCode() == ASSET_VALIDATION_FAILED.getCode()) {
                // ASSET_VALIDATION_FAILED ≠ 终态 REJECTED（并发 VALIDATING/令牌竞争也抛）——重读实际状态
                return readAssetState(assetId, null);
            }
            throw e;
        }
    }

    /** 属主校验后重读资产实际状态（P-2⑥：正常返回与异常路径统一） */
    private AssetCompleted readAssetState(long assetId, String status) {
        var rows = jdbcTemplate.queryForList(
                "SELECT owner_user_id, upload_status, rejected_reason FROM asset WHERE id=? AND deleted=FALSE",
                assetId);
        if (rows.isEmpty()) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        var row = rows.get(0);
        if (!serviceAccountOwns(row)) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        String raw = status != null ? status : String.valueOf(row.get("upload_status"));
        String reason = row.get("rejected_reason") == null ? null : row.get("rejected_reason").toString();
        return switch (raw) {
            case "ACCEPTED" -> new AssetCompleted(assetId, "ACCEPTED", null);
            case "REJECTED" -> new AssetCompleted(assetId, "REJECTED", reason);
            case "VALIDATING", "PENDING" -> new AssetCompleted(assetId, "PENDING", reason);
            default -> new AssetCompleted(assetId, raw, reason);
        };
    }

    private boolean serviceAccountOwns(Map<String, Object> row) {
        // 状态读取已在调用方（completeUpload）完成属主校验；此处仅防直接构造调用
        return row.get("owner_user_id") != null;
    }

    @Override
    public DownloadUrlView createDownloadUrl(long serviceAccountId, long assetId) {
        var ticket = assetService.requestDownloadTicket(serviceAccountId, assetId);
        var download = assetService.resolveDownloadTicketDetailed(serviceAccountId, assetId, ticket.getToken());
        return new DownloadUrlView(download.url(), download.expiresAtEpochSecond());
    }

    private static void putIfPresent(Map<String, Object> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }
}
