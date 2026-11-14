package cn.iocoder.yudao.module.platform.identity.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.SMS_SERVICE_UNAVAILABLE;

/** 联调环境显式关闭短信：不发送、不输出验证码，调用即返回服务不可用。 */
@Component
@Profile("integration & !prod & !production")
@ConditionalOnProperty(prefix = "platform.identity.sms", name = "provider", havingValue = "disabled")
public class DisabledSmsClient implements SmsClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void sendLoginCaptcha(String mobile, String captcha) {
        throw exception(SMS_SERVICE_UNAVAILABLE);
    }
}
