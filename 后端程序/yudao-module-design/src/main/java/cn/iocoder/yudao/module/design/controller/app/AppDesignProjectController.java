package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppDesignCandidateRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppDesignJobCreateReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppDesignProjectCreateReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppDesignProjectRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppProjectRenameReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppResultVersionRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppRevisionRequestReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppSelectionCreateReqVO;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.FLAT_REFERENCE_DISABLED;

/**
 * 设计项目与两阶段任务（页面 04~11）。
 *
 * 类级 @PermitAll 放行安全链，鉴权由方法内 Bearer 会话校验强制执行——与 P2A/P3A 控制器一致：
 * 底座安全链对未登记路径直接 401，会让「会话无效」与「未放行」两类错误无法区分。
 */
@Tag(name = "小程序 - 设计项目与两阶段任务（页面 04~11）")
@RestController
@RequestMapping("/design/v1/design-projects")
@PermitAll
public class AppDesignProjectController {

    @Resource
    private DesignProjectService designProjectService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping
    @Operation(summary = "创建设计项目（参考案例或自主设计；仅案例可见不代表可作生成参考，创建任务时再验授权）")
    public CommonResult<AppDesignProjectRespVO> createProject(
            @Valid @RequestBody(required = false) AppDesignProjectCreateReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        long userId = requireAccountId(authorization);
        AppDesignProjectCreateReqVO req = reqVO == null ? new AppDesignProjectCreateReqVO() : reqVO;
        String sourceType = req.getSourceType() == null ? "SELF_UPLOAD" : req.getSourceType();
        String line = req.getLine() == null ? "FLAT" : req.getLine();
        Long sketchAssetId = parseIdOrNull(req.getSketchAssetId());
        // 平面生成已改为纯文生图：新 FLAT 项目不再接受案例参考或手绘草图。
        // 立面线路仍允许 CASE_REFERENCE 参考图，不受此限制。
        if ("FLAT".equals(line) && ("CASE_REFERENCE".equals(sourceType) || sketchAssetId != null)) {
            throw exception(FLAT_REFERENCE_DISABLED);
        }
        long projectId = designProjectService.createProjectWithKey(userId, sourceType,
                parseIdOrNull(req.getRefCaseId()), sketchAssetId,
                req.getRequirementInputs(), requestKey, line,
                parseReferenceVersion(req.getRefCaseVersionId()));
        return success(toProjectVo(designProjectService.getProject(projectId).orElseThrow(), null, null));
    }

    public CommonResult<AppDesignProjectRespVO> createProject(AppDesignProjectCreateReqVO reqVO, String authorization) {
        return createProject(reqVO, authorization, null);
    }

    @GetMapping
    @Operation(summary = "我的设计项目列表（游标分页）")
    public CommonResult<CursorPageResult<AppDesignProjectRespVO>> getProjectPage(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var page = designProjectService.listProjects(userId, cursor, limit);
        List<AppDesignProjectRespVO> list = page.list().stream().map(item -> {
        AppDesignProjectRespVO vo = new AppDesignProjectRespVO();
        vo.setProjectId(String.valueOf(item.projectId()));
        vo.setName(item.name());
        vo.setSourceType(item.sourceType());
            vo.setRefCaseId(item.refCaseId() == null ? null : String.valueOf(item.refCaseId()));
            vo.setStage(item.stage());
            vo.setStatus(item.status());
            vo.setJobStatus(item.jobStatus());
            vo.setCoverAssetId(item.coverAssetId());
            vo.setPointsSpent(item.pointsSpent());
            vo.setHasResult(item.hasResult());
            vo.setHasDualImages(item.hasDualImages());
            vo.setCreatedAt(item.createTime() == null ? null
                    : LocalDateTime.ofInstant(item.createTime(), ZoneId.systemDefault()));
            vo.setAllowedActions(List.of("VIEW"));
            return vo;
        }).toList();
        return success(new CursorPageResult<>(list, page.nextCursor()));
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "删除我的设计项目（软删；最新生成任务仍在进行中时拒绝）")
    public CommonResult<Boolean> deleteProject(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        designProjectService.deleteProject(userId, Long.parseLong(projectId));
        return success(Boolean.TRUE);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{projectId}/name")
    @Operation(summary = "重命名设计项目（仅属主；名称去首尾空白后 1~60 字符）")
    public CommonResult<AppDesignProjectRespVO> renameProject(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppProjectRenameReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        long id = Long.parseLong(projectId);
        designProjectService.renameProject(userId, id, reqVO.getName());
        var vo = toProjectVo(designProjectService.getProject(id)
                .orElseThrow(() -> new AccessDeniedException("项目不存在或无权访问")), null, null);
        return success(vo);
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "项目详情（含当前阶段选择状态）；传 jobId 时一并下发该任务已接受的候选")
    public CommonResult<AppDesignProjectRespVO> getProject(
            @PathVariable("projectId") String projectId,
            @Parameter(description = "任务编号：传入时先晋升该任务的已接受结果为候选再下发")
            @RequestParam(value = "jobId", required = false) String jobId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        long id = Long.parseLong(projectId);
        var project = designProjectService.getProject(id, userId)
                .orElseThrow(() -> new AccessDeniedException("项目不存在或无权访问"));
        // 候选是拉式晋升的：带 jobId 查询时先把该任务已接受的结果落为候选，再返回
        List<DesignProjectService.CandidateRow> candidates = jobId == null || jobId.isBlank()
                ? List.of()
                : designProjectService.promoteCandidates(userId, id, Long.parseLong(jobId));
        var vo = toProjectVo(project, jobId, candidates);
        vo.setPointsSpent(designProjectService.netPointsSpent(id));
        return success(vo);
    }

    @GetMapping("/{projectId}/result-versions")
    @Operation(summary = "结果版本列表（不可变版本，旧版本只读）")
    public CommonResult<CursorPageResult<AppResultVersionRespVO>> getResultVersions(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        List<AppResultVersionRespVO> list = designProjectService
                .listResultVersions(userId, Long.parseLong(projectId)).stream()
                .map(this::toResultVersionVo).toList();
        // 结果版本每个项目至多个位数条，一次返回完整列表，nextCursor 恒为空
        return success(new CursorPageResult<>(list, null));
    }

    @PostMapping("/{projectId}/revision-requests")
    @Operation(summary = "发起调整请求（生成新的不可变结果版本）")
    public CommonResult<AppDesignProjectRespVO> createRevisionRequest(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppRevisionRequestReqVO reqVO,
            @Parameter(description = "幂等键") @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        AppRevisionRequestReqVO req = reqVO == null ? new AppRevisionRequestReqVO() : reqVO;
        var created = designProjectService.createRevisionRequest(userId, Long.parseLong(projectId),
                req.getReason(), req.getConfigUpdates(),
                req.getCount() == null ? 2 : req.getCount(), idempotencyKey, req.getPriceConfirmation(),
                req.getResolution(), req.getOrientation());
        return success(jobAcceptedVo(projectId, created.newJobId()));
    }

    @PostMapping("/{projectId}/flat-jobs")
    @Operation(summary = "创建平面任务：同事务完成计价快照、锁账户、扣点流水、charge、job、outbox")
    public CommonResult<AppDesignProjectRespVO> createFlatJob(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppDesignJobCreateReqVO reqVO,
            @Parameter(description = "幂等键：同用户同 Key 同请求体返回同一任务")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var created = designProjectService.createFlatJob(userId, Long.parseLong(projectId),
                reqVO.getCount(), idempotencyKey, reqVO.getPriceConfirmation(),
                imageOptions("FLAT", reqVO.getResolution(), reqVO.getOrientation()));
        return success(jobAcceptedVo(projectId, created.jobId()));
    }

    @PostMapping("/{projectId}/flat-selections")
    @Operation(summary = "选定平面候选（乐观锁；任务未终态/候选不属本人拒绝；响应显式区分纯平面正式交付与历史立面线路选择成功）")
    public CommonResult<AppDesignProjectRespVO> selectFlat(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppSelectionCreateReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        long id = Long.parseLong(projectId);
        var confirmed = designProjectService.confirmFlatCandidate(userId, id, Long.parseLong(reqVO.getCandidateId()));
        var vo = toProjectVo(designProjectService.getProject(id).orElseThrow(), null, null);
        // 即使另一确认紧随其后，也应返回这次用户确认的不可变版本。
        vo.setSelectionSaved(true);
        if (confirmed != null) {
            vo.setResultVersionId(String.valueOf(confirmed.versionId()));
            vo.setFlatDeliveryCreated(true);
        } else {
            // R2 历史立面线路：平面选择按原选择语义保存（不创建新的平面交付）；
            // 必须清空项目旧结果版本号，不得把它冒充本次确认的交付下发（前端据此区分下一步）。
            vo.setResultVersionId(null);
            vo.setFlatDeliveryCreated(false);
            if (!"POLL_JOB".equals(vo.getResumeAction())) {
                vo.setResumeAction("CREATE_ELEVATION_JOB");
                vo.setAllowedActions(List.of("CREATE_ELEVATION_JOB"));
            }
        }
        return success(vo);
    }

    @PostMapping("/{projectId}/elevation-jobs")
    @Operation(summary = "创建立面任务：必须引用已选平面候选及其资产 Hash")
    public CommonResult<AppDesignProjectRespVO> createElevationJob(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppDesignJobCreateReqVO reqVO,
            @Parameter(description = "幂等键")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var created = designProjectService.createElevationJob(userId, Long.parseLong(projectId),
                reqVO.getCount(), idempotencyKey, elevationConfig(reqVO), reqVO.getPriceConfirmation(),
                imageOptions("ELEVATION", reqVO.getResolution(), reqVO.getOrientation()));
        return success(jobAcceptedVo(projectId, created.jobId()));
    }

    @PostMapping("/{projectId}/elevation-selections")
    @Operation(summary = "选定立面候选并生成最终结果版本")
    public CommonResult<AppDesignProjectRespVO> selectElevation(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppSelectionCreateReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        long id = Long.parseLong(projectId);
        var version = designProjectService.selectElevationCandidate(userId, id,
                Long.parseLong(reqVO.getCandidateId()));
        AppDesignProjectRespVO vo = toProjectVo(designProjectService.getProject(id).orElseThrow(),
                null, null);
        vo.setResultVersionId(String.valueOf(version.versionId()));
        return success(vo);
    }

    // ========== 内部 ==========

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private Long parseIdOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    private Long parseReferenceVersion(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "参考案例版本编号无效");
        }
    }

    /** 立面配置：只透传界面实际采集的字段，null 值不入快照以免污染可复算输入 */
    private Map<String, Object> elevationConfig(AppDesignJobCreateReqVO reqVO) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        if (reqVO.getRequirementInputs() != null) {
            config.putAll(reqVO.getRequirementInputs());
            config.put("referenceAssetIds", reqVO.getRequirementInputs().getOrDefault("referenceAssetIds", List.of()));
            config.put("referenceCaseId", reqVO.getRefCaseId() == null ? "" : reqVO.getRefCaseId());
        }
        putIfPresent(config, "styleCode", reqVO.getStyleCode());
        putIfPresent(config, "roofType", reqVO.getRoofType());
        putIfPresent(config, "material", reqVO.getMaterial());
        putIfPresent(config, "color", reqVO.getColor());
        putIfPresent(config, "prompt", reqVO.getPrompt());
        config.put("count", reqVO.getCount());
        return config;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions imageOptions(
            String stage, String resolution, String orientation) {
        try {
            return cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize(
                    stage, resolution, orientation);
        } catch (IllegalArgumentException e) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, e.getMessage());
        }
    }

    /** 任务已受理：客户端据 jobId 轮询 /ai-jobs/{jobId} */
    private AppDesignProjectRespVO jobAcceptedVo(String projectId, long jobId) {
        AppDesignProjectRespVO vo = new AppDesignProjectRespVO();
        vo.setProjectId(projectId);
        vo.setJobId(String.valueOf(jobId));
        var options = designProjectService.jobImageOptions(jobId);
        vo.setResolution(options.resolution());
        vo.setOrientation(options.orientation());
        vo.setImageOptions(Map.of("resolution", options.resolution(), "orientation", options.orientation()));
        vo.setAllowedActions(List.of("POLL_JOB", "CANCEL_JOB"));
        return vo;
    }

    private AppDesignProjectRespVO toProjectVo(DesignProjectService.ProjectSnapshot project,
                                               String jobId,
                                               List<DesignProjectService.CandidateRow> candidates) {
        AppDesignProjectRespVO vo = new AppDesignProjectRespVO();
        vo.setProjectId(String.valueOf(project.projectId()));
        vo.setName(project.name());
        vo.setInitialGenerationKey(designProjectService.initialGenerationKey(project.projectId(), project.userId()));
        vo.setSourceType(project.sourceType());
        vo.setRefCaseId(project.refCaseId() == null ? null : String.valueOf(project.refCaseId()));
        vo.setStage(project.stage());
        vo.setStatus(project.status());
        vo.setJobId(jobId);
        Long flat = designProjectService.activeSelectionCandidateId(project.projectId(), "FLAT");
        Long elevation = designProjectService.activeSelectionCandidateId(project.projectId(), "ELEVATION");
        // 需求①拆分：stage 即线路（创建时定 FLAT/ELEVATION），不再用「平面已选」覆盖展示阶段
        boolean elevationLine = "ELEVATION".equals(project.stage());
        vo.setSelectedFlatCandidateId(flat == null ? null : String.valueOf(flat));
        vo.setSelectedElevationCandidateId(elevation == null ? null : String.valueOf(elevation));
        Long versionId = designProjectService.latestResultVersionId(project.projectId());
        vo.setResultVersionId(versionId == null ? null : String.valueOf(versionId));
        if (candidates != null && !candidates.isEmpty()) {
            vo.setCandidates(candidates.stream().map(c -> {
                AppDesignCandidateRespVO cv = new AppDesignCandidateRespVO();
                cv.setCandidateId(String.valueOf(c.candidateId()));
                cv.setJobId(String.valueOf(c.jobId()));
                cv.setSlotNo(c.slotNo());
                cv.setAssetId(String.valueOf(c.assetId()));
                return cv;
            }).toList());
        }
        vo.setAllowedActions(allowedActions(elevationLine, flat, elevation));
        var currentJob = designProjectService.latestJob(project).orElse(null);
        if (currentJob != null) {
            if (jobId == null) vo.setJobId(String.valueOf(currentJob.jobId()));
            vo.setJobStatus(currentJob.status());
            vo.setRequestedCount(currentJob.requestedCount());
            var options = designProjectService.jobImageOptions(currentJob.jobId());
            vo.setResolution(options.resolution());
            vo.setOrientation(options.orientation());
            vo.setImageOptions(Map.of("resolution", options.resolution(), "orientation", options.orientation()));
        }
        Long flatAsset = designProjectService.selectedFlatAssetId(project.projectId());
        vo.setSelectedFlatAssetId(flatAsset == null ? null : String.valueOf(flatAsset));
        String action;
        Long selectedJob = designProjectService.selectedJobId(project.projectId(), elevationLine ? "ELEVATION" : "FLAT");
        boolean selectedCurrent = currentJob != null && selectedJob != null && selectedJob == currentJob.jobId();
        if ((elevation != null || (!elevationLine && flat != null && versionId != null))
                && (currentJob == null || selectedCurrent)) action = "VIEW_RESULT";
        else if (currentJob != null && !List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED").contains(currentJob.status())) {
            action = "POLL_JOB";
            vo.setAllowedActions(List.of("POLL_JOB", "CANCEL_JOB"));
        } else if (currentJob != null && List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(currentJob.status())) {
            // 需求①拆分：按线路提示选择动作（线路出生即定，立面线路选立面候选，平面线路选平面候选）
            action = elevationLine ? "SELECT_ELEVATION" : "SELECT_FLAT";
        } else if (elevationLine) {
            action = "CREATE_ELEVATION_JOB";
        } else {
            action = flat == null ? "CREATE_FLAT_JOB" : "SELECT_FLAT";
        }
        if (elevation != null && !selectedCurrent && currentJob != null) {
            var actions = new java.util.ArrayList<String>(vo.getAllowedActions());
            if (!actions.contains(action)) actions.add(action);
            if (!actions.contains("VIEW_RESULT")) actions.add("VIEW_RESULT");
            vo.setAllowedActions(actions);
        }
        if (!elevationLine && flat != null && versionId != null) {
            var actions = new java.util.ArrayList<String>(vo.getAllowedActions());
            for (String available : List.of("VIEW_RESULT", "CREATE_BUDGET", "SUBMIT"))
                if (!actions.contains(available)) actions.add(available);
            vo.setAllowedActions(actions);
        }
        vo.setResumeAction(action);
        vo.setRequirements(designProjectService.latestDesignInputs(project.projectId()));
        return vo;
    }

    /** 服务端下发可执行动作，客户端不自行推导权限（架构 §7.1）。
     *  需求①拆分：线路出生即定——立面线路不再以平面选择为前置；平面线路选完平面即收口，不再引导立面（彻底分家）。
     *  兼容存量：平面线路项目若历史上有立面选择，仍保留调整/预算/投稿动作。 */
    private List<String> allowedActions(boolean elevationLine, Long flatSelection, Long elevationSelection) {
        if (elevationLine) {
            if (elevationSelection == null) {
                return List.of("CREATE_ELEVATION_JOB", "SELECT_ELEVATION");
            }
            return List.of("CREATE_REVISION", "CREATE_BUDGET", "SUBMIT");
        }
        if (flatSelection == null) {
            return List.of("CREATE_FLAT_JOB", "SELECT_FLAT");
        }
        return elevationSelection == null
                ? List.of("CREATE_FLAT_JOB", "SELECT_FLAT")
                : List.of("CREATE_FLAT_JOB", "SELECT_FLAT", "CREATE_REVISION", "CREATE_BUDGET", "SUBMIT");
    }

    private AppResultVersionRespVO toResultVersionVo(Map<String, Object> row) {
        AppResultVersionRespVO vo = new AppResultVersionRespVO();
        vo.setVersionId(String.valueOf(row.get("id")));
        vo.setVersion(((Number) row.get("version")).longValue());
        vo.setFlatCandidateIds((String) row.get("flat_candidate_ids"));
        vo.setElevationCandidateId(row.get("elevation_candidate_id") == null ? null
                : String.valueOf(row.get("elevation_candidate_id")));
        vo.setConfigSnapshot(BudgetInputs.publicConfigJson((String) row.get("config_snapshot")));
        vo.setSelectedFlatAssetId(row.get("selected_flat_asset_id") == null ? null
                : String.valueOf(row.get("selected_flat_asset_id")));
        vo.setSelectedElevationAssetId(row.get("selected_elevation_asset_id") == null ? null
                : String.valueOf(row.get("selected_elevation_asset_id")));
        vo.setFlatImageInfo(versionImageInfo(row, "flat"));
        vo.setElevationImageInfo(versionImageInfo(row, "elevation"));
        vo.setSuperseded((Boolean) row.get("superseded"));
        Object createTime = row.get("create_time");
        vo.setCreatedAt(createTime instanceof Timestamp ts ? ts.toLocalDateTime() : null);
        return vo;
    }

    /** 只下发可证实的版本来源规格；未知不补当前默认档位或像素。 */
    private Map<String, Object> versionImageInfo(Map<String, Object> row, String phase) {
        var info = new java.util.LinkedHashMap<String, Object>();
        Object raw = row.get(phase + "_image_options");
        if (raw instanceof String json) {
            try {
                var options = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                String resolution = options.path("resolution").asText();
                String orientation = options.path("orientation").asText();
                if (List.of("2K", "4K").contains(resolution)) info.put("resolution", resolution);
                if (List.of("LANDSCAPE", "PORTRAIT").contains(orientation)) info.put("orientation", orientation);
            } catch (java.io.IOException ignored) { /* 历史异常规格不冒充默认值 */ }
        }
        if (row.get(phase + "_width") instanceof Number width && width.intValue() > 0
                && row.get(phase + "_height") instanceof Number height && height.intValue() > 0) {
            info.put("width", width.intValue()); info.put("height", height.intValue());
        }
        return info;
    }

}
