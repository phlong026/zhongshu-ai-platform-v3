package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Schema(description = "小程序员工微信登录 Response VO")
@Data
@Accessors(chain = true)
public class WechatLoginRespVO {

    @Schema(description = "为 true 时需要获取手机号授权后，以新的登录 code 再次调用")
    private Boolean needPhone;
    private Long userId;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresTime;

}
