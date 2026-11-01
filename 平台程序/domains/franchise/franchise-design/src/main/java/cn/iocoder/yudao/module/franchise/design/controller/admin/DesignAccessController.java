package cn.iocoder.yudao.module.franchise.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.design.DesignAccessService;
import cn.iocoder.yudao.module.franchise.design.DesignPermissions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 设计赋能接入层（B2 板块 A，R1-R21）：全部 /admin-api/franchise/design/**，能力门=franchise:design:use。
 * 服务账号由服务端按 post.orgId() 解析（永不收 accountId 入参，S2）；
 * design/commerce 业务错误原码透传（HTTP 200 + body.code≠0），接入层自身错误 1_112 段。
 */
@Tag(name = "加盟 - 设计赋能接入层")
@RestController
@RequestMapping("/franchise/design")
@Validated
@PreAuthorize("@post.has('" + DesignPermissions.USE + "')")
public class DesignAccessController {

    private final DesignAccessService access;

    public DesignAccessController(DesignAccessService access) {
        this.access = access;
    }

    // ========== 灵感库（R1-R5） ==========

    @GetMapping("/cases")
    @Operation(summary = "灵感库案例列表（游标分页，±1m 尺寸筛选）")
    public CommonResult<DesignServicePort.CasePage> cases(
            @RequestParam(value = "styleCode", required = false) String styleCode,
            @RequestParam(value = "floorCount", required = false) Integer floorCount,
            @RequestParam(value = "faceWidth", required = false) BigDecimal faceWidth,
            @RequestParam(value = "depth", required = false) BigDecimal depth,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        PostSnapshot post = access.post();
        return success(access.listCases(post, new DesignServicePort.CaseQuery(
                styleCode, floorCount, faceWidth, depth, keyword, cursor, limit)));
    }

    @GetMapping("/cases/styles")
    @Operation(summary = "风格筛选项")
    public CommonResult<List<DesignServicePort.StyleOption>> caseStyles() {
        return success(access.listCaseStyles(access.post()));
    }

    @GetMapping("/cases/{caseId}")
    @Operation(summary = "案例详情")
    @SuppressWarnings("unchecked")
    public CommonResult<Map<String, Object>> caseDetail(@PathVariable("caseId") long caseId) {
        return success((Map<String, Object>) access.getCaseDetail(access.post(), caseId).fields());
    }

    @PutMapping("/cases/{caseId}/favorite")
    @Operation(summary = "收藏案例（服务账号名下，组织共享，重放天然幂等）")
    public CommonResult<Boolean> favorite(@PathVariable("caseId") long caseId) {
        access.favoriteCase(access.post(), caseId);
        return success(true);
    }

    @DeleteMapping("/cases/{caseId}/favorite")
    @Operation(summary = "取消收藏")
    public CommonResult<Boolean> unfavorite(@PathVariable("caseId") long caseId) {
        access.unfavoriteCase(access.post(), caseId);
        return success(true);
    }

    @GetMapping("/favorites")
    @Operation(summary = "收藏列表")
    public CommonResult<DesignServicePort.CasePage> favorites(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return success(access.listFavorites(access.post(), cursor, limit));
    }

    // ========== 资产（R6-R8） ==========

    public record UploadTicketReq(String assetType, String mimeType, long sizeBytes, String sha256) {
    }

    @PostMapping("/assets/upload-tickets")
    @Operation(summary = "申请上传票据（COS=原始字节 PUT；local=dev 通道）")
    public CommonResult<DesignServicePort.UploadTicketView> uploadTicket(@RequestBody UploadTicketReq req) {
        return success(access.createUploadTicket(access.post(),
                new DesignServicePort.UploadTicketCreate(req.assetType(), req.mimeType(), req.sizeBytes(), req.sha256())));
    }

    @PostMapping("/assets/{assetId}/complete")
    @Operation(summary = "完成上传（首次触发审核；终态重放=幂等读）")
    public CommonResult<DesignServicePort.AssetCompleted> completeUpload(@PathVariable("assetId") long assetId) {
        return success(access.completeUpload(access.post(), assetId));
    }

    @PostMapping("/assets/{assetId}/download-tickets")
    @Operation(summary = "换取短期签名 URL（票据一次性消费在能力方内完成）")
    public CommonResult<DesignServicePort.DownloadUrlView> downloadTicket(@PathVariable("assetId") long assetId) {
        return success(access.createDownloadUrl(access.post(), assetId));
    }

    // ========== 项目与任务（R9-R18） ==========

    public record ProjectCreateReq(String sourceType, Long refCaseId, Long sketchAssetId,
                                   Map<String, Object> requirementInputs, String line) {
    }

    @PostMapping("/projects")
    @Operation(summary = "创建 AI 设计项目（FLAT 线路禁参考/草图，适配器强制）")
    public CommonResult<Map<String, Object>> createProject(
            @RequestBody ProjectCreateReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PostSnapshot post = access.post();
        var created = access.createProject(post, new DesignServicePort.DesignProjectCreate(
                req.sourceType(), req.refCaseId(), req.sketchAssetId(), req.requirementInputs(),
                req.line() == null ? "FLAT" : req.line(), idempotencyKey));
        return success(Map.of("projectId", created.projectId(), "replayed", created.replayed()));
    }

    @GetMapping("/projects")
    @Operation(summary = "项目列表（customerId 桥接过滤：分页前过滤不漏项）")
    public CommonResult<Map<String, Object>> projects(
            @RequestParam(value = "customerId", required = false) Long customerId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        PostSnapshot post = access.post();
        if (customerId != null) {
            return success(access.listProjectsForCustomer(post, customerId, cursor, limit));
        }
        var page = access.listProjects(post, cursor, limit);
        return success(Map.of("list", page.list(), "nextCursor", page.nextCursor()));
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "项目详情（jobId 触发拉式晋升；allowedActions 白名单）")
    @SuppressWarnings("unchecked")
    public CommonResult<Map<String, Object>> project(
            @PathVariable("projectId") long projectId,
            @RequestParam(value = "jobId", required = false) Long jobId) {
        return success((Map<String, Object>) access.getProject(access.post(), projectId, jobId).fields());
    }

    public record FlatJobReq(Integer count, String resolution, String orientation) {
    }

    @PostMapping("/projects/{projectId}/flat-jobs")
    @Operation(summary = "创建平面任务（无 prompt/requirementInputs——需求在创建项目时冻结；服务端内聚取价）")
    public CommonResult<Map<String, Object>> createFlatJob(@PathVariable("projectId") long projectId,
                                                           @RequestBody FlatJobReq req,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PostSnapshot post = access.post();
        var submitted = access.createFlatJob(post, projectId, new DesignServicePort.FlatJobCreate(
                req.count() == null ? 2 : req.count(), req.resolution(), req.orientation(), idempotencyKey));
        return success(Map.of("jobId", submitted.jobId(), "replayed", submitted.replayed()));
    }

    public record ElevationJobReq(Integer count, String resolution, String orientation,
                                  String styleCode, String roofType, String material, String color) {
    }

    @PostMapping("/projects/{projectId}/elevation-jobs")
    @Operation(summary = "创建立面任务（稳定编码入库，全字段参与指纹）")
    public CommonResult<Map<String, Object>> createElevationJob(@PathVariable("projectId") long projectId,
                                                                @RequestBody ElevationJobReq req,
                                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PostSnapshot post = access.post();
        var submitted = access.createElevationJob(post, projectId, new DesignServicePort.ElevationJobCreate(
                req.count() == null ? 2 : req.count(), req.resolution(), req.orientation(),
                req.styleCode(), req.roofType(), req.material(), req.color(), idempotencyKey));
        return success(Map.of("jobId", submitted.jobId(), "replayed", submitted.replayed()));
    }

    @GetMapping("/ai-jobs/{jobId}")
    @Operation(summary = "AI 任务轮询（job.userId 属主校验）")
    public CommonResult<DesignServicePort.AiJobView> aiJob(@PathVariable("jobId") long jobId) {
        return success(access.getAiJob(access.post(), jobId));
    }

    public record SelectionReq(Long jobId, long candidateId) {
    }

    @PostMapping("/projects/{projectId}/flat-selections")
    @Operation(summary = "选定平面候选（jobId/candidateId 一致性校验；ELEVATION 阶段 selectionSaved=true 无版本）")
    public CommonResult<Map<String, Object>> flatSelection(@PathVariable("projectId") long projectId,
                                                           @RequestBody SelectionReq req) {
        PostSnapshot post = access.post();
        var saved = access.confirmFlatCandidate(post, projectId, req.jobId(), req.candidateId());
        return success(selectionResult(saved));
    }

    @PostMapping("/projects/{projectId}/elevation-selections")
    @Operation(summary = "选定立面候选并生成结果版本")
    public CommonResult<Map<String, Object>> elevationSelection(@PathVariable("projectId") long projectId,
                                                                @RequestBody SelectionReq req) {
        PostSnapshot post = access.post();
        var saved = access.selectElevationCandidate(post, projectId, req.jobId(), req.candidateId());
        return success(selectionResult(saved));
    }

    private static Map<String, Object> selectionResult(DesignServicePort.SelectionSaved saved) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("resultVersionId", saved.resultVersionId());
        result.put("version", saved.version());
        result.put("selectionSaved", saved.selectionSaved());
        result.put("replayed", saved.replayed());
        return result;
    }

    @GetMapping("/projects/{projectId}/result-versions")
    @Operation(summary = "结果版本列表（含 superseded 标记）")
    public CommonResult<List<DesignServicePort.ResultVersionView>> resultVersions(
            @PathVariable("projectId") long projectId) {
        return success(access.listResultVersions(access.post(), projectId));
    }

    public record RevisionReq(String reason, Map<String, Object> configUpdates, Integer count,
                              String resolution, String orientation) {
    }

    @PostMapping("/projects/{projectId}/revision-requests")
    @Operation(summary = "调整请求（reason 必填，DB NOT NULL；收据比较含 count）")
    public CommonResult<Map<String, Object>> revisionRequest(@PathVariable("projectId") long projectId,
                                                             @RequestBody RevisionReq req,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (req.reason() == null || req.reason().isBlank() || req.reason().length() > 500) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "调整原因必填（≤500 字符）"));
        }
        PostSnapshot post = access.post();
        var submitted = access.createRevisionRequest(post, projectId, new DesignServicePort.RevisionCreate(
                req.reason(), req.configUpdates(), req.count(), req.resolution(), req.orientation(), idempotencyKey));
        return success(Map.of("requestId", submitted.requestId(), "newJobId", submitted.newJobId(),
                "replayed", submitted.replayed()));
    }

    // ========== 快速预算（R19-R20） ==========

    @GetMapping("/budget/rule-info")
    @Operation(summary = "区间预算规则元数据（三元组选择；无生效规则返回 null）")
    public CommonResult<DesignServicePort.RuleInfoView> budgetRuleInfo(
            @RequestParam("regionCode") String regionCode,
            @RequestParam("structureType") String structureType,
            @RequestParam("materialGrade") String materialGrade) {
        return success(access.budgetRuleInfo(access.post(), regionCode, structureType, materialGrade));
    }

    public record BudgetEstimateReq(String regionCode, String structureType, String materialGrade,
                                    Integer buildingArea, Long resultVersionId) {
    }

    @PostMapping("/projects/{projectId}/budget-estimates")
    @Operation(summary = "快速区间预算（buildingArea 整数 1-10000；服务端内聚取价）")
    public CommonResult<Map<String, Object>> budgetEstimate(@PathVariable("projectId") long projectId,
                                                            @RequestBody BudgetEstimateReq req,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (req.buildingArea() == null) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "建筑面积必填"));
        }
        PostSnapshot post = access.post();
        var created = access.createBudgetEstimate(post, projectId, new DesignServicePort.BudgetEstimateCreate(
                req.regionCode(), req.structureType(), req.materialGrade(), req.buildingArea(),
                req.resultVersionId(), idempotencyKey));
        return success(Map.of("estimateId", created.estimateId(),
                "totalLowCents", created.totalLowCents(), "totalHighCents", created.totalHighCents(),
                "ruleVersionId", created.ruleVersionId(), "replayed", created.replayed(),
                "disclaimer", "仅供参考，不构成报价或结算依据"));
    }

    // ========== 回写（R21） ==========

    public record SaveToCustomerReq(Long customerId, String kind, Long resultVersionId, Long estimateId,
                                    BigDecimal width, BigDecimal depth, Integer floors) {
    }

    @PostMapping("/projects/{projectId}/save-to-customer")
    @Operation(summary = "回写客户方案（PLAN/FACADE 必填 resultVersionId；BUDGET 必填 estimateId+尺寸，area 取快照）")
    public CommonResult<Map<String, Object>> saveToCustomer(@PathVariable("projectId") long projectId,
                                                            @RequestBody SaveToCustomerReq req,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (req.customerId() == null || req.kind() == null) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "customerId 与 kind 必填"));
        }
        PostSnapshot post = access.post();
        return success(access.saveToCustomer(post, new DesignAccessService.SaveToCustomerCommand(
                projectId, req.customerId(), req.kind(), req.resultVersionId(), req.estimateId(),
                req.width(), req.depth(), req.floors(), idempotencyKey)));
    }
}
