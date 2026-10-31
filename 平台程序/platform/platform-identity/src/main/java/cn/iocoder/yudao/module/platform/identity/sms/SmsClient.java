package cn.iocoder.yudao.module.platform.identity.sms;

/**
 * 短信发送通道。实现不得在日志或异常中带出验证码明文以外的敏感信息（手机号需脱敏）。
 * real 通道（阿里云/腾讯云短信）待凭据到位后按 G0 门禁模式接入，当前仅 stub。
 */
public interface SmsClient {

    /** 发送登录验证码短信；失败直接抛业务异常，不重试 */
    void sendLoginCaptcha(String mobile, String captcha);

}
