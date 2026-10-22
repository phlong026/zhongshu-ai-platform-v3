package cn.iocoder.yudao.module.platform.identity;

import java.time.LocalDateTime;

/**
 * 签发后台用户令牌（与 Web 后台登录相同）。测试中可替换。
 */
public interface TokenIssuer {

    IssuedToken issue(long userId);

    /** 吊销该员工的全部令牌（含刷新令牌，Web 与小程序会话一并失效） */
    void revokeAll(long userId);

    record IssuedToken(String accessToken, String refreshToken, LocalDateTime expiresTime) {
    }

}
