package cn.iocoder.yudao.module.design.controller.app.vo;

import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "小程序 - 按尺寸检索的平面方案 Response VO")
@Data
public class AppCaseDimensionRespVO {

    private String caseId;

    @Schema(description = "当前发布版本行编号")
    private String caseVersionId;

    private String title;

    @Schema(description = "来源：COMPANY / AI")
    private String sourceType;

    private BigDecimal faceWidth;

    private BigDecimal depth;

    @Schema(description = "与请求一致的尺寸口径：SITE / BUILDING_OUTLINE")
    private String dimensionBasis;

    private Integer floorCount;

    private Integer buildingArea;

    @Schema(description = "案例封面资产 ID；保留给需要展示立面的入口")
    private String coverAssetId;

    @Schema(description = "结果卡默认展示的平面资产 ID；有楼层编号时优先最低楼层")
    private String planPreviewAssetId;

    @Schema(description = "当前版本全部可公开展示的平面图，按楼层升序；未标楼层排最后")
    private List<CaseCatalogService.FloorPlan> floorPlans;

    @Schema(description = "当前对象允许执行的动作集合")
    private List<String> allowedActions;

}
