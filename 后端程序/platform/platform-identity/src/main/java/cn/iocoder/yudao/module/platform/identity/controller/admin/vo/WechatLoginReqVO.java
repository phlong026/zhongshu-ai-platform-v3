package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "小程序员工微信登录 Request VO")
@Data
public class WechatLoginReqVO {

    @Schema(description = "小程序 AppID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "AppID 不能为空")
    @Size(max = 32)
    private String appid;

    @Schema(description = "wx.login 返回的一次性 code", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "登录 code 不能为空")
    @Size(max = 128)
    private String loginCode;

    @Schema(description = "手机号授权返回的一次性 code；首次登录需要")
    @Size(max = 128)
    private String phoneCode;

}
