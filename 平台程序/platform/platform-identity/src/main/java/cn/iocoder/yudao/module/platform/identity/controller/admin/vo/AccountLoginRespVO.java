package cn.iocoder.yudao.module.platform.identity.controller.admin.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class AccountLoginRespVO {
    private Boolean requirePasswordChange;
    private Long userId;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresTime;
    private Long preferredPostId;
    private String accountId;
    private String loginName;
    private String orgName;
}
