package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Schema(description = "小程序员工短信验证码登录 Response VO（结构与微信登录一致，便于前端统一处理）")
@Data
@Accessors(chain = true)
public class SmsLoginRespVO {

    private Long userId;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresTime;

}
