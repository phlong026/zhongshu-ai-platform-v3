package cn.iocoder.yudao.module.design.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "小程序 - 补充需求 AI 优化 Request VO")
@Data
public class AppNotePolishReqVO {

    @Schema(description = "用户原始补充需求（≤200 字）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请先输入补充需求")
    @Size(max = 200, message = "补充需求不能超过 200 字")
    private String note;

    @Schema(description = "线路：FLAT（平面布置语境，默认）/ ELEVATION（外观风格语境）", example = "FLAT")
    @Pattern(regexp = "FLAT|ELEVATION", message = "线路只能是 FLAT 或 ELEVATION")
    private String line;
}
