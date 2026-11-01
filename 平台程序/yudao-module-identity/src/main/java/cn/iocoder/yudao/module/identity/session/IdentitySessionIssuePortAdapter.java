package cn.iocoder.yudao.module.identity.session;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionIssuePort;
import cn.iocoder.yudao.module.infra.zhongshu.api.WechatPaySessionPort;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityException;
import cn.iocoder.yudao.module.identity.wechat.WechatLoginFailure;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * identity 会话铸造/吊销端口适配器（09 钱包会话桥接）。
 *
 * <p>包装 {@link UserSessionService} 与微信换码适配器：把 identity 内部的
 * {@link WechatIdentityException}（按 {@link WechatLoginFailure} 分类）消化为端口层的
 * {@link CodeFailureKind}，使消费方（franchise-org 桥接）无需依赖 identity 类型即可换译业务码。
 * session_key 只在 identity 内部流转，不出本端口。
 */
@Component
public class IdentitySessionIssuePortAdapter implements IdentitySessionIssuePort {

    private final UserSessionService userSessionService;
    private final WechatPaySessionPort wechatPaySessionPort;

    public IdentitySessionIssuePortAdapter(UserSessionService userSessionService,
                                           WechatPaySessionPort wechatPaySessionPort) {
        this.userSessionService = userSessionService;
        this.wechatPaySessionPort = wechatPaySessionPort;
    }

    @Override
    public IssuedWalletSession issueWalletSession(long accountId, String appid, String openid,
                                                  String deviceDigest, long ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds 越界: " + ttlSeconds);
        }
        var tokens = userSessionService.issue(accountId, appid, openid, false, deviceDigest, ttlSeconds);
        return new IssuedWalletSession(tokens.sessionId(), tokens.accessToken(), ttlSeconds);
    }

    @Override
    public PayPayerOpenid exchangePayerOpenid(String appid, String loginCode) {
        try {
            var session = wechatPaySessionPort.exchangeForPay(appid, loginCode);
            return new PayPayerOpenid(session.openid());
        } catch (WechatIdentityException e) {
            // 一次性 code 不重试：按失败分类收敛为端口层两态，session_key 绝不外泄
            WechatLoginFailure failure = e.getFailure();
            CodeFailureKind kind = failure == WechatLoginFailure.INVALID_CODE
                    || failure == WechatLoginFailure.CODE_ALREADY_USED
                    ? CodeFailureKind.CODE_INVALID : CodeFailureKind.SERVICE_UNAVAILABLE;
            throw new WechatCodeExchangeException(kind);
        }
    }

    @Override
    public int revokeForAccounts(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        int revoked = 0;
        for (Long accountId : accountIds) {
            revoked += userSessionService.revokeAllForAccount(accountId);
        }
        return revoked;
    }

}
