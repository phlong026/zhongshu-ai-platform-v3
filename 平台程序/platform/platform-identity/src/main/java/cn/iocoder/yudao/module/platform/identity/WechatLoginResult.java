package cn.iocoder.yudao.module.platform.identity;

/**
 * 小程序员工登录结果。needPhone=true 表示该微信尚未绑定员工，前端需要获取手机号授权后重新调用（带新的登录 code）。
 */
public record WechatLoginResult(boolean needPhone, Long userId, TokenIssuer.IssuedToken token) {

    public static WechatLoginResult needPhoneNumber() {
        return new WechatLoginResult(true, null, null);
    }

    public static WechatLoginResult loggedIn(long userId, TokenIssuer.IssuedToken token) {
        return new WechatLoginResult(false, userId, token);
    }

}
