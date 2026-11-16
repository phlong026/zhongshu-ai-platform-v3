package cn.iocoder.yudao.module.platform.identity;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.platform.identity.StaffAccountDirectory.StaffAccount;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

@Service
@Slf4j
public class StaffWechatLoginServiceImpl implements StaffWechatLoginService {

    @Resource
    private PlatformWechatProperties properties;
    @Resource
    private WechatStaffClient wechat;
    @Resource
    private WechatBindingMapper bindingMapper;
    @Resource
    private StaffAccountDirectory accounts;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private TokenIssuer tokenIssuer;
    @Resource
    private StaffWechatBindingService bindingService;
    @Resource
    private StaffWechatBindingCommitService bindingCommitService;

    @Override
    public WechatLoginResult login(String appid, String loginCode, String phoneCode) {
        if (!properties.allows(appid)) {
            throw exception(WECHAT_APP_NOT_ALLOWED);
        }
        // 微信调用都在事务外完成，避免外部请求占用数据库连接
        String openid = wechat.openid(appid, loginCode);

        WechatBindingDO binding = bindingMapper.selectActive(appid, openid);
        if (binding != null) {
            String invalidReason = invalidReason(binding);
            if (invalidReason == null) {
                return issue(binding.getUserId());
            }
            bindingService.revoke(binding, invalidReason, null);
        }
        if (StrUtil.isBlank(phoneCode)) {
            return WechatLoginResult.needPhoneNumber();
        }

        String mobile = wechat.phoneNumber(appid, phoneCode);
        List<StaffAccount> candidates = accounts.findByMobile(mobile).stream()
                .filter(StaffAccount::enabled)
                .filter(a -> !staffPostService.listActivePosts(a.userId()).isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            log.warn("[login][appid={} mobile={}] 手机号无对应在岗员工", appid, mask(mobile));
            throw exception(STAFF_NOT_ENROLLED);
        }
        if (candidates.size() > 1) {
            log.warn("[login][appid={} mobile={}] 手机号对应 {} 名在岗员工，拒绝绑定", appid, mask(mobile), candidates.size());
            throw exception(STAFF_PHONE_AMBIGUOUS);
        }
        long userId = candidates.get(0).userId();
        Long postId = staffPostService.listActivePosts(userId).stream()
                .map(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getId)
                .sorted().findFirst().orElseThrow(() -> exception(STAFF_NOT_ENROLLED));
        try {
            // 老板、负责人及员工共用手机号自动识别；短事务内复核手机号、账号与任职，防止匹配后资料被修改。
            bindingCommitService.commit(appid, openid, userId, mobile, postId);
        } catch (DuplicateKeyException e) {
            WechatBindingDO winner = bindingMapper.selectActive(appid, openid);
            if (winner == null || !winner.getUserId().equals(userId)) {
                throw exception(WECHAT_BINDING_CONFLICT);
            }
        }
        log.info("[login][appid={} userId={} mobile={}] 员工微信绑定", appid, userId, mask(mobile));
        return issue(userId);
    }

    /** 绑定是否仍可用：账号存在且启用、手机号未变；返回失效原因，可用时返回 null */
    private String invalidReason(WechatBindingDO binding) {
        Optional<StaffAccount> account = accounts.find(binding.getUserId());
        if (account.isEmpty() || !account.get().enabled()) {
            return WechatBindingDO.REASON_USER_DISABLED;
        }
        if (account.get().mobile() == null
                || !StaffWechatBindingServiceImpl.hashMobile(account.get().mobile()).equals(binding.getMobileHash())) {
            return WechatBindingDO.REASON_MOBILE_CHANGED;
        }
        return null;
    }

    private WechatLoginResult issue(long userId) {
        if (staffPostService.listActivePosts(userId).isEmpty()) {
            log.warn("[login][userId={}] 员工已无有效任职，拒绝登录", userId);
            throw exception(STAFF_NOT_ENROLLED);
        }
        return WechatLoginResult.loggedIn(userId, tokenIssuer.issue(userId));
    }

    private static String mask(String mobile) {
        return mobile == null || mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

}
