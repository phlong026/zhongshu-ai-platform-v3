package cn.iocoder.yudao.module.infra.zhongshu.api;

import java.util.List;

/**
 * identity 会话铸造/吊销端口（09 钱包会话桥接）。
 *
 * <p>消费方：franchise-org 钱包桥接（发卡）与 platform-org 撤权挂钩（吊销）；
 * 实现方：identity 模块（包装 {@code UserSessionService} 与微信换码适配器）。
 * 端口放 infra 共享包以避免业务模块互相依赖（与 {@link IdentitySessionPort} 同纪律）。
 *
 * <p>安全合同：
 * <ul>
 *   <li>session_key 属敏感凭据：不出现在本端口任何返回值/异常消息中，实现方换取即弃；</li>
 *   <li>login code 一次性：实现方单次使用，失败不重试；</li>
 *   <li>refresh token 不经本端口对外暴露（调用方仅获 access token）。</li>
 * </ul>
 */
public interface IdentitySessionIssuePort {

    /** 发卡 TTL 上限（对齐 identity access token 常量 2h） */
    long MAX_TTL_SECONDS = 7200;

    /**
     * 铸造「非受限」钱包会话。注意：会话受限状态由 identity 按 design_access_grant 实时重算
     * （validateAccessToken 语义），调用方须先确保账号已有 ACTIVE grant（fr-service: 账号由
     * 账号供给路径幂等补挂）。
     *
     * @param ttlSeconds access token 有效期，(0, {@link #MAX_TTL_SECONDS}]，越界抛 IllegalArgumentException
     */
    IssuedWalletSession issueWalletSession(long accountId, String appid, String openid,
                                           String deviceDigest, long ttlSeconds);

    record IssuedWalletSession(long sessionId, String accessToken, long expiresIn) {
    }

    /**
     * 用一次性 login code 换支付者 openid（支付签名所需 session_key 不出实现方，用后即弃）。
     * 失败抛 {@link WechatCodeExchangeException}（已消化 identity 内部异常类型，分类见枚举）。
     */
    PayPayerOpenid exchangePayerOpenid(String appid, String loginCode);

    record PayPayerOpenid(String openid) {
    }

    /** 换码失败分类：CODE_INVALID=code 缺失/无效/已用（客户端可恢复）；SERVICE_UNAVAILABLE=微信侧/配置异常 */
    enum CodeFailureKind {
        CODE_INVALID, SERVICE_UNAVAILABLE
    }

    class WechatCodeExchangeException extends RuntimeException {

        private final CodeFailureKind kind;

        public WechatCodeExchangeException(CodeFailureKind kind) {
            super("wechat code exchange failed: " + kind);
            this.kind = kind;
        }

        public CodeFailureKind kind() {
            return kind;
        }
    }

    /** 吊销这些账号的全部会话（撤权钩子用）；返回吊销条数 */
    int revokeForAccounts(List<Long> accountIds);

}
