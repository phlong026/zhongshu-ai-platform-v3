package cn.iocoder.yudao.module.identity.controller.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "小程序 - 提交开通申请 Request VO")
@Data
public class AppActivationRequestSubmitReqVO {

    @Schema(description = "加盟商公司名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "公司名称不能为空")
    @Size(max = 60, message = "公司名称不能超过60字")
    private String companyName;

    @Schema(description = "联系人姓名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "联系人不能为空")
    @Size(max = 30, message = "联系人不能超过30字")
    private String contactName;

    @Schema(description = "联系电话（手机或座机）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "[0-9+\\-() ]{5,20}", message = "联系电话格式不正确")
    private String contactPhone;

}
