package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingDO;
import cn.iocoder.yudao.module.platform.identity.dal.WechatBindingMapper;
import cn.iocoder.yudao.module.platform.identity.wechat.WechatStaffClient;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

@Service
@Slf4j
public class StaffWechatExplicitBindingServiceImpl implements StaffWechatExplicitBindingService {

    @Resource private PlatformWechatProperties properties;
    @Resource private WechatStaffClient wechat;
    @Resource private StaffAccountDirectory accounts;
    @Resource private StaffPostService staffPostService;
    @Resource private StaffWechatBindingCommitService commitService;
    @Resource private WechatBindingMapper bindingMapper;

    @Override
    public void bind(String appid, String loginCode, Long currentUserId) {
        if (currentUserId == null || !properties.allows(appid)) throw exception(WECHAT_APP_NOT_ALLOWED);
        StaffAccountDirectory.StaffAccount account = accounts.find(currentUserId)
                .filter(StaffAccountDirectory.StaffAccount::enabled).orElseThrow(() -> exception(STAFF_NOT_ENROLLED));
        Long postId = staffPostService.listActivePosts(currentUserId).stream()
                .filter(post -> staffPostService.findActivePost(currentUserId, post.getId()).isPresent())
                .map(cn.iocoder.yudao.module.platform.org.dal.StaffPostDO::getId).sorted().findFirst().orElse(null);
        if (postId == null || account.mobile() == null || account.mobile().isBlank()) {
            throw exception(STAFF_NOT_ENROLLED);
        }

        // 微信外呼先完成，再进入独立 bean 的短事务锁定复核；避免自调用 @Transactional 失效。
        String openid = wechat.openid(appid, loginCode);
        try {
            commitService.commit(appid, openid, currentUserId, account.mobile(), postId);
        } catch (DuplicateKeyException e) {
            WechatBindingDO winner = bindingMapper.selectActive(appid, openid);
            if (winner != null && winner.getUserId().equals(currentUserId)) return;
            throw exception(WECHAT_BINDING_CONFLICT);
        }
        log.info("[explicitWechatBind][appid={} userId={}] 已绑定当前账号", appid, currentUserId);
    }
}
