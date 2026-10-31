package cn.iocoder.yudao.module.platform.identity.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 本地联调替身：不真正发短信，验证码只写 WARN 日志（手机号脱敏）。
 * 任何人都能借此收到任意验证码，所以与 StubWechatStaffClient 同口径：
 * 只有 local profile 激活（且未激活 prod）时才允许启动，否则启动失败。
 * 出码读取走 dev 包 SmsDevController（同样仅 local）。
 */
@Component
@ConditionalOnProperty(prefix = "platform.identity.sms", name = "provider", havingValue = "stub")
@Slf4j
public class StubSmsClient implements SmsClient {

    public StubSmsClient(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local")) || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("短信替身（platform.identity.sms.provider=stub）只允许在 local 配置下使用");
        }
    }

    @Override
    public void sendLoginCaptcha(String mobile, String captcha) {
        log.warn("[sendLoginCaptcha][stub 短信] mobile={} 验证码见 dev 接口（日志不打印验证码）", mask(mobile));
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

}
