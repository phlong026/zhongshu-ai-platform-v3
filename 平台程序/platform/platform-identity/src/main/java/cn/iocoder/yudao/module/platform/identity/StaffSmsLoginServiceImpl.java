package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.identity.StaffAccountDirectory.StaffAccount;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

/**
 * 员工手机号 + 短信验证码登录实现。
 * 员工解析与微信登录共用 StaffAccountDirectory（按手机号、仅在岗、单一候选），令牌同走 TokenIssuer。
 */
@Service
@Slf4j
public class StaffSmsLoginServiceImpl implements StaffSmsLoginService {

    @Resource
    private SmsCaptchaService smsCaptchaService;
    @Resource
    private StaffAccountDirectory accounts;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private TokenIssuer tokenIssuer;

    @Override
    public void sendCaptcha(String mobile) {
        smsCaptchaService.sendLoginCaptcha(mobile);
    }

    @Override
    public WechatLoginResult login(String mobile, String captcha) {
        smsCaptchaService.verifyLoginCaptcha(mobile, captcha);
        List<StaffAccount> candidates = accounts.findByMobile(mobile).stream()
                .filter(StaffAccount::enabled)
                .filter(a -> !staffPostService.listActivePosts(a.userId()).isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            log.warn("[smsLogin][mobile={}] 手机号无对应在岗员工", mask(mobile));
            throw exception(STAFF_NOT_ENROLLED);
        }
        if (candidates.size() > 1) {
            log.warn("[smsLogin][mobile={}] 手机号对应 {} 名在岗员工，拒绝登录", mask(mobile), candidates.size());
            throw exception(STAFF_PHONE_AMBIGUOUS);
        }
        long userId = candidates.get(0).userId();
        log.info("[smsLogin][userId={} mobile={}] 员工短信验证码登录成功", userId, mask(mobile));
        return WechatLoginResult.loggedIn(userId, tokenIssuer.issue(userId));
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

}
