package cn.iocoder.yudao.module.franchise.org.wallet;

import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionIssuePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 钱包会话桥接（09 文档 §二）：员工以平台身份为当前组织换取组织服务账号的钱包会话。
 *
 * <p>链路：岗位上下文 → 服务账号（准入，停用换译 1_112_000_004）→ 一次性 code 换支付者
 * openid（分类失败换译 1_104_000_001/002，session_key 不出 identity）→ 铸造 30 分钟非受限会话
 * （refresh token 不下发）→ 同事务审计。openid 只以 SHA-256 前 8 位摘要进审计。
 */
@Service
public class WalletSessionService {

    /** 30 分钟：收敛服务账号会话暴露面与撤权旁路缺口（09 文档 §四-3/§十-1） */
    static final long TTL_SECONDS = 1800;
    static final String DEVICE_DIGEST = "v3-platform-wallet";
    static final String AGGREGATE_TYPE = "wallet_session";
    static final String COMMAND = "ISSUE";

    @Resource
    private CurrentPost currentPost;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private StoreServiceAccountPort storeServiceAccountPort;
    @Resource
    private IdentitySessionIssuePort identitySessionIssuePort;
    @Resource
    private FranchiseCommands commands;

    @Value("${zhongshu.identity.wechat-appid}")
    private String wechatAppId;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> issueSession(String wxLoginCode) {
        PostSnapshot post = currentPost.require();
        if (wxLoginCode == null || wxLoginCode.isBlank()) {
            throw exception(WalletErrorCodes.WECHAT_CODE_INVALID);
        }
        long accountId;
        try {
            accountId = storeServiceAccountPort.requireServiceAccount(post.orgId());
        } catch (StoreServiceAccountPort.ServiceAccountInactiveException e) {
            throw exception(FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE);
        }
        IdentitySessionIssuePort.PayPayerOpenid payer;
        try {
            payer = identitySessionIssuePort.exchangePayerOpenid(wechatAppId, wxLoginCode);
        } catch (IdentitySessionIssuePort.WechatCodeExchangeException e) {
            throw exception(e.kind() == IdentitySessionIssuePort.CodeFailureKind.CODE_INVALID
                    ? WalletErrorCodes.WECHAT_CODE_INVALID
                    : WalletErrorCodes.WECHAT_SERVICE_UNAVAILABLE);
        }
        // M01-T01：外呼（换码）暂停期间岗位可能被撤/停用/账号删除——发卡前锁岗位行复验，
        // 锁保持至本事务提交；并发删除回收排队在后，会话要么未发出、要么发出即被回收覆盖
        staffPostService.revalidateActivePost(post.userId(), post.postId());
        var issued = identitySessionIssuePort.issueWalletSession(
                accountId, wechatAppId, payer.openid(), DEVICE_DIGEST, TTL_SECONDS);
        // 审计与发卡同事务：只记 openid 摘要（前 8 位），绝不记 code/session_key/openid 明文
        commands.record(post, AGGREGATE_TYPE, accountId, IdWorker.getId(), COMMAND,
                Map.of("openidDigest", DigestUtil.sha256Hex(payer.openid()).substring(0, 8),
                        "sessionId", String.valueOf(issued.sessionId())),
                null);
        return Map.of("token", issued.accessToken(), "expiresIn", issued.expiresIn());
    }

}
