package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "小程序员工短信验证码发送 Request VO")
@Data
public class SmsCaptchaSendReqVO {

    @Schema(description = "中国大陆手机号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
    private String mobile;

}
