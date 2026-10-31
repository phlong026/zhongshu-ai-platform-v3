package cn.iocoder.yudao.module.platform.identity;

/**
 * 员工手机号 + 短信验证码登录（微信一键登录之外的备用方式，对标同行双通道登录）。
 */
public interface StaffSmsLoginService {

    /** 向指定手机号发送登录验证码（内置发送限流） */
    void sendCaptcha(String mobile);

    /** 校验验证码并签发令牌；手机号未登记在岗员工或对应多名员工时拒绝 */
    WechatLoginResult login(String mobile, String captcha);

}
