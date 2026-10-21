package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "小程序 - 案例摘要 Response VO（列表/收藏/首页通用）")
@Data
public class AppCaseSummaryRespVO {

    @Schema(description = "案例编号")
    private String caseId;

    @Schema(description = "案例名称")
    private String title;

    @Schema(description = "来源：COMPANY / AI")
    private String sourceType;

    @Schema(description = "风格编码")
    private String styleCode;

    @Schema(description = "层数")
    private Integer floorCount;

    @Schema(description = "建筑面积（㎡）")
    private Integer buildingArea;

    @Schema(description = "封面资产 ID")
    private String coverAssetId;

    @Schema(description = "当前对象允许执行的动作集合（如 DESIGN_WITH、FAVORITE）")
    private List<String> allowedActions;

    @Schema(description = "上传人姓名（需求 3c：来源显示公司·人名；空则前端回退默认来源标签）")
    private String uploaderName;

    @Schema(description = "上传人公司（后台取操作人部门，投稿取兑换登记公司；空则只显示人名）")
    private String uploaderCompany;

    @Schema(description = "面宽（米；未记录为 null）")
    private java.math.BigDecimal faceWidth;

    @Schema(description = "进深（米；未记录为 null）")
    private java.math.BigDecimal depth;

}
