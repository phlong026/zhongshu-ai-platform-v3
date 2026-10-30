package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseDimensionRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseDetailRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseSummaryRespVO;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.catalog.CaseGenerationCandidateService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 户型库与收藏（页面 02/03）")
@RestController
@RequestMapping("/design/v1")
@PermitAll // 列表/详情匿名可读；favorite 系列方法内强制 Bearer 校验
public class AppCaseController {

    @Resource
    private CaseCatalogService caseCatalogService;

    @Resource
    private CaseGenerationCandidateService caseGenerationCandidateService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping("/cases")
    @Operation(summary = "灵感库列表：风格/层数/面积/面宽/进深筛选（面宽进深±1m），keyword 编号精确+名称模糊，稳定游标分页")
    public CommonResult<CursorPageResult<AppCaseSummaryRespVO>> getCasePage(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "styleCode", required = false) String styleCode,
            @RequestParam(value = "floorCount", required = false) Integer floorCount,
            @RequestParam(value = "minArea", required = false) Integer minArea,
            @RequestParam(value = "maxArea", required = false) Integer maxArea,
            @RequestParam(value = "faceWidth", required = false) java.math.BigDecimal faceWidth,
            @RequestParam(value = "depth", required = false) java.math.BigDecimal depth,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        var page = caseCatalogService.listPublishedCases(sourceType, styleCode, floorCount,
                minArea, maxArea, faceWidth, depth, keyword, cursor, limit);
        return success(new CursorPageResult<>(toSummaryVos(page.list()),
                page.nextCursor()));
    }

    @GetMapping("/cases/styles")
    @Operation(summary = "灵感库风格筛选项：后台全部预设风格 + 已发布案例中的自定义风格")
    public CommonResult<List<CaseCatalogService.StyleOption>> getCaseStyles() {
        return success(caseCatalogService.listPublishedStyles());
    }

    @GetMapping("/cases/by-dimension")
    @Operation(summary = "外观设计平面：按同尺寸口径、面宽/进深（±1m 双维度）检索可公开平面图；返回各层图，默认建筑外轮廓口径，最多 50 条")
    public CommonResult<CursorPageResult<AppCaseDimensionRespVO>> getCasesByDimension(
            @RequestParam("faceWidth") java.math.BigDecimal faceWidth,
            @RequestParam("depth") java.math.BigDecimal depth,
            @Parameter(description = "尺寸口径：SITE / BUILDING_OUTLINE；默认 BUILDING_OUTLINE，UNKNOWN 不参与精确匹配")
            @RequestParam(value = "dimensionBasis", defaultValue = "BUILDING_OUTLINE") String dimensionBasis,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        var page = caseCatalogService.listCasesByDimension(faceWidth, depth, dimensionBasis, limit);
        return success(new CursorPageResult<>(page.list().stream().map(this::toDimensionVo).toList(),
                page.nextCursor()));
    }

    /** 灵感库批次D后前端已不再调用，保留一个版本待下线。 */
    @Deprecated
    @GetMapping("/cases/recommendation")
    @Operation(summary = "平面线路默认参考案例：层数一致优先、建筑面积最接近，且已授权生成参考；无可用案例返回空")
    public CommonResult<AppCaseSummaryRespVO> getRecommendedCase(
            @RequestParam("floorCount") Integer floorCount,
            @RequestParam("area") Integer area) {
        if (floorCount == null || floorCount < 1 || floorCount > 4 || area == null || area < 1 || area > 100_000) {
            throw new ServiceException(400, "层数需为 1~4，面积需为 1~100000 平方米");
        }
        return success(caseCatalogService.recommendForGeneration(floorCount, area)
                .map(summary -> toSummaryVo(summary, caseCatalogService.canUseForGeneration(summary.caseId())))
                .orElse(null));
    }

    /** 灵感库批次D后前端已不再调用，保留一个版本待下线。 */
    @Deprecated
    @GetMapping("/cases/generation-candidates")
    @Operation(summary = "最多五个真实授权参考案例；默认不进行精确尺寸匹配，查询不创建任务或收费")
    public CommonResult<CaseGenerationCandidateService.Candidates> getGenerationCandidates(
            @RequestParam("floorCount") int floorCount,
            @RequestParam(value = "faceWidth", required = false) java.math.BigDecimal faceWidth,
            @RequestParam(value = "depth", required = false) java.math.BigDecimal depth,
            @RequestParam(value = "dimensionBasis", defaultValue = "UNKNOWN") String dimensionBasis,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAccountId(authorization);
        return success(caseGenerationCandidateService.find(floorCount, faceWidth, depth, dimensionBasis));
    }

    /** 灵感库批次D后前端已不再调用，保留一个版本待下线。 */
    @Deprecated
    @GetMapping("/cases/{caseId}/generation-candidate")
    @Operation(summary = "重新校验候选的具体版本及当前授权，用于预览和确认参考")
    public CommonResult<CaseGenerationCandidateService.Candidate> getGenerationCandidate(
            @PathVariable("caseId") long caseId, @RequestParam("caseVersionId") long caseVersionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAccountId(authorization);
        return success(caseGenerationCandidateService.requireCandidate(caseId, caseVersionId));
    }

    @GetMapping("/cases/{caseId}")
    @Operation(summary = "户型详情：结构化参数、封面、各层平面、立面、来源")
    public CommonResult<AppCaseDetailRespVO> getCase(@PathVariable("caseId") String caseId) {
        var detail = caseCatalogService.getPublishedCase(Long.parseLong(caseId))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("案例不存在或未发布"));
        AppCaseDetailRespVO vo = new AppCaseDetailRespVO();
        vo.setCaseId(String.valueOf(detail.caseId()));
        vo.setCaseVersionId(String.valueOf(detail.caseVersionId()));
        vo.setDimensionBasis(detail.dimensionBasis());
        vo.setTitle(detail.title());
        vo.setDescription(detail.description());
        vo.setSourceType(detail.sourceType());
        vo.setUploaderName(detail.uploaderName());
        vo.setUploaderCompany(detail.uploaderCompany());
        vo.setCoverAssetId(detail.coverAssetId());
        vo.setFloorPlanAssetIds(detail.floorPlanAssetIds());
        vo.setFloorPlans(caseCatalogService.getFloorPlansForVersion(detail.caseVersionId()));
        vo.setElevationAssetId(detail.elevationAssetId());
        vo.setPdfAssetId(detail.pdfAssetId());
        vo.setVersion(detail.version());
        vo.setParameters(java.util.Map.of(
                "styleCode", detail.styleCode(),
                "floorCount", detail.floorCount(),
                "buildingArea", detail.buildingArea(),
                "faceWidth", detail.faceWidth() == null ? 0 : detail.faceWidth().doubleValue(),
                "depth", detail.depth() == null ? 0 : detail.depth().doubleValue()));
        boolean canDesign = caseCatalogService.canUseForGeneration(detail.caseId());
        vo.setAllowedActions(canDesign ? List.of("FAVORITE", "DESIGN_WITH") : List.of("FAVORITE"));
        vo.setDesignUnavailableReason(canDesign ? null : "该案例暂无有效的设计参考授权，可浏览和收藏");
        return success(vo);
    }

    @PutMapping("/cases/{caseId}/favorite")
    @Operation(summary = "收藏案例（并发幂等）")
    public CommonResult<Boolean> favorite(@PathVariable("caseId") String caseId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(caseCatalogService.favorite(requireAccountId(authorization), Long.parseLong(caseId)));
    }

    @DeleteMapping("/cases/{caseId}/favorite")
    @Operation(summary = "取消收藏")
    public CommonResult<Boolean> unfavorite(@PathVariable("caseId") String caseId,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(caseCatalogService.unfavorite(requireAccountId(authorization), Long.parseLong(caseId)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "收藏列表（游标分页；仅已发布案例）")
    public CommonResult<CursorPageResult<AppCaseSummaryRespVO>> getFavorites(
            @Parameter(description = "游标") @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var page = caseCatalogService.listFavorites(requireAccountId(authorization), cursor, limit);
        return success(new CursorPageResult<>(toSummaryVos(page.list()),
                page.nextCursor()));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private List<AppCaseSummaryRespVO> toSummaryVos(List<CaseCatalogService.CaseSummary> summaries) {
        Set<Long> allowedIds = caseCatalogService.findGenerationAllowedCaseIds(
                summaries.stream().map(CaseCatalogService.CaseSummary::caseId).toList());
        return summaries.stream().map(summary -> toSummaryVo(summary, allowedIds.contains(summary.caseId()))).toList();
    }

    private AppCaseSummaryRespVO toSummaryVo(CaseCatalogService.CaseSummary summary, boolean canDesign) {
        AppCaseSummaryRespVO vo = new AppCaseSummaryRespVO();
        vo.setCaseId(String.valueOf(summary.caseId()));
        vo.setTitle(summary.title());
        vo.setSourceType(summary.sourceType());
        vo.setStyleCode(summary.styleCode());
        vo.setFloorCount(summary.floorCount());
        vo.setBuildingArea(summary.buildingArea());
        vo.setCoverAssetId(summary.coverAssetId());
        vo.setUploaderName(summary.uploaderName());
        vo.setUploaderCompany(summary.uploaderCompany());
        vo.setFaceWidth(summary.faceWidth());
        vo.setDepth(summary.depth());
        vo.setAllowedActions(canDesign
                ? List.of("FAVORITE", "DESIGN_WITH") : List.of("FAVORITE"));
        return vo;
    }

    private AppCaseDimensionRespVO toDimensionVo(CaseCatalogService.DimensionCase result) {
        AppCaseDimensionRespVO vo = new AppCaseDimensionRespVO();
        vo.setCaseId(String.valueOf(result.caseId()));
        vo.setCaseVersionId(String.valueOf(result.caseVersionId()));
        vo.setTitle(result.title());
        vo.setSourceType(result.sourceType());
        vo.setFaceWidth(result.faceWidth());
        vo.setDepth(result.depth());
        vo.setDimensionBasis(result.dimensionBasis());
        vo.setFloorCount(result.floorCount());
        vo.setBuildingArea(result.buildingArea());
        vo.setCoverAssetId(result.coverAssetId());
        vo.setPlanPreviewAssetId(result.planPreviewAssetId());
        vo.setFloorPlans(result.floorPlans());
        vo.setAllowedActions(result.generationAllowed()
                ? List.of("FAVORITE", "DESIGN_WITH") : List.of("FAVORITE"));
        return vo;
    }

}
