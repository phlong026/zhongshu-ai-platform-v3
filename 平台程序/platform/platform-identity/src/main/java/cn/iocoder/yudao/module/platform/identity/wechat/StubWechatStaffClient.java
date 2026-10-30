package cn.iocoder.yudao.module.platform.identity.wechat;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.STAFF_NOT_ENROLLED;

/**
 * 本地联调替身：openid = "stub-" + 登录 code；手机号 code 本身即手机号。
 * 任何人都能借此冒充员工，所以采用白名单：只有 local profile 激活（且未激活 prod）时才允许启动，否则启动失败。
 */
@Component
@ConditionalOnProperty(prefix = "platform.identity.wechat", name = "provider", havingValue = "stub")
public class StubWechatStaffClient implements WechatStaffClient {

    public StubWechatStaffClient(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local")) || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("员工登录替身（platform.identity.wechat.provider=stub）只允许在 local 配置下使用");
        }
    }

    @Override
    public String openid(String appid, String loginCode) {
        return "stub-" + loginCode;
    }

    @Override
    public String phoneNumber(String appid, String phoneCode) {
        if (phoneCode == null || !phoneCode.matches("1\\d{10}")) {
            throw new ServiceException(STAFF_NOT_ENROLLED);
        }
        return phoneCode;
    }

}
