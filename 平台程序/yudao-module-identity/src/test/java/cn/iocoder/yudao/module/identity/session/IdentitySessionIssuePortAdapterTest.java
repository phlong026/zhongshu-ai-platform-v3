package cn.iocoder.yudao.module.identity.session;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionIssuePort;
import cn.iocoder.yudao.module.infra.zhongshu.api.WechatPaySessionPort;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityException;
import cn.iocoder.yudao.module.identity.wechat.WechatLoginFailure;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 09 钱包会话桥接：identity 端口适配器的失败分类换译表与 TTL 校验（纯 Fake，零 Spring 零 DB）。
 * 换译契约=09 文档 §二-3：INVALID_CODE/CODE_ALREADY_USED → CODE_INVALID；其余 → SERVICE_UNAVAILABLE。
 */
class IdentitySessionIssuePortAdapterTest {

    /** 覆写 DB 触点的会话服务替身：记录入参、返回 canned 结果 */
    static class FakeUserSessionService extends UserSessionService {
        Long issuedTtl;
        int ttlOverrideCalls;
        int revokeCalls;
        long revokedAccount;

        FakeUserSessionService() {
            // 惰性替身：本测试只走覆写方法，构造器的 DataSource/事务管理器不会触达
            super(new org.springframework.jdbc.datasource.DriverManagerDataSource(),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource()));
        }

        @Override
        public IssuedTokens issue(long accountId, String appid, String openid, boolean restricted,
                                  String deviceDigest, long accessTokenTtlSeconds) {
            this.ttlOverrideCalls += 1;
            this.issuedTtl = accessTokenTtlSeconds;
            return new IssuedTokens(9527L, "access-token", "refresh-token",
                    Instant.now().plusSeconds(accessTokenTtlSeconds), restricted);
        }

        @Override
        public int revokeAllForAccount(long accountId) {
            this.revokeCalls += 1;
            this.revokedAccount = accountId;
            return 3;
        }
    }

    static WechatPaySessionPort wechatFailing(WechatLoginFailure failure) {
        return (appid, loginCode) -> {
            throw new WechatIdentityException("fake", null, failure);
        };
    }

    static WechatPaySessionPort wechatOk(String openid) {
        return (appid, loginCode) -> new WechatPaySessionPort.PaySession(openid, "session-key-不出端口");
    }

    private final FakeUserSessionService sessions = new FakeUserSessionService();

    @Test
    void 换码成功只透出openid() {
        var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatOk("openid-1"));
        var result = adapter.exchangePayerOpenid("appid-x", "code-1");
        assertThat(result.openid()).isEqualTo("openid-1");
    }

    @Test
    void 无效code与已用code归类CODE_INVALID() {
        for (WechatLoginFailure failure : List.of(WechatLoginFailure.INVALID_CODE,
                WechatLoginFailure.CODE_ALREADY_USED)) {
            var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatFailing(failure));
            assertThatThrownBy(() -> adapter.exchangePayerOpenid("appid-x", "code-x"))
                    .isInstanceOf(IdentitySessionIssuePort.WechatCodeExchangeException.class)
                    .satisfies(e -> assertThat(((IdentitySessionIssuePort.WechatCodeExchangeException) e).kind())
                            .isEqualTo(IdentitySessionIssuePort.CodeFailureKind.CODE_INVALID));
        }
    }

    @Test
    void 微信侧故障与配置错误归类SERVICE_UNAVAILABLE() {
        for (WechatLoginFailure failure : List.of(WechatLoginFailure.WECHAT_SERVICE_ERROR,
                WechatLoginFailure.CONFIG_ERROR)) {
            var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatFailing(failure));
            assertThatThrownBy(() -> adapter.exchangePayerOpenid("appid-x", "code-x"))
                    .isInstanceOf(IdentitySessionIssuePort.WechatCodeExchangeException.class)
                    .satisfies(e -> assertThat(((IdentitySessionIssuePort.WechatCodeExchangeException) e).kind())
                            .isEqualTo(IdentitySessionIssuePort.CodeFailureKind.SERVICE_UNAVAILABLE));
        }
    }

    @Test
    void TTL越界拒绝且不触底层() {
        var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatOk("openid-1"));
        assertThatThrownBy(() -> adapter.issueWalletSession(1L, "appid", "openid", "dev", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.issueWalletSession(1L, "appid", "openid", "dev", 7201))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.ttlOverrideCalls).isZero();
    }

    @Test
    void TTL合法值透传且refresh不出端口() {
        var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatOk("openid-1"));
        var issued = adapter.issueWalletSession(42L, "appid", "openid", "v3-platform-wallet", 1800);
        assertThat(sessions.ttlOverrideCalls).isEqualTo(1);
        assertThat(sessions.issuedTtl).isEqualTo(1800L);
        assertThat(issued.expiresIn()).isEqualTo(1800L);
        assertThat(issued.sessionId()).isEqualTo(9527L);
        // refresh token 不经端口对外（IssuedWalletSession 无该字段）
        assertThat(issued).hasOnlyFields("sessionId", "accessToken", "expiresIn");
    }

    @Test
    void 吊销按账号转发且空列表零调用() {
        var adapter = new IdentitySessionIssuePortAdapter(sessions, wechatOk("openid-1"));
        assertThat(adapter.revokeForAccounts(List.of(42L))).isEqualTo(3);
        assertThat(sessions.revokeCalls).isEqualTo(1);
        assertThat(sessions.revokedAccount).isEqualTo(42L);
        assertThat(adapter.revokeForAccounts(List.of())).isZero();
        assertThat(adapter.revokeForAccounts(null)).isZero();
        assertThat(sessions.revokeCalls).isEqualTo(1);
    }

}
