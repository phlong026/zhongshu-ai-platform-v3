package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "登记客资 Request VO")
@Data
public class LeadCaptureReqVO {

    @NotBlank(message = "请填写称呼")
    @Size(max = 32)
    private String name;

    @NotBlank(message = "请填写手机号")
    @Pattern(regexp = "1\\d{10}", message = "请填写有效手机号")
    private String phone;

    @NotBlank(message = "请填写咨询需求")
    @Size(max = 500)
    private String need;

    @Schema(description = "来源，如 内容咨询 / 直接来访 / 转介绍；为空记为直接来访")
    @Size(max = 32)
    private String source;

}
