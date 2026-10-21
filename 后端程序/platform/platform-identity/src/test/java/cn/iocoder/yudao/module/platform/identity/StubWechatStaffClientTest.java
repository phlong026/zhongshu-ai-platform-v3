package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.identity.wechat.StubWechatStaffClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 替身客户端白名单：只有 local（且非 prod）才能启动 */
class StubWechatStaffClientTest {

    @Test
    void allowedOnlyUnderTheLocalProfile() {
        assertThat(new StubWechatStaffClient(env("local", "pg", "zsdev")).openid("wx", "c1")).isEqualTo("stub-c1");
    }

    @Test
    void rejectedWithoutLocalOrTogetherWithProd() {
        assertThatThrownBy(() -> new StubWechatStaffClient(env())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StubWechatStaffClient(env("pg"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StubWechatStaffClient(env("dev", "pg"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StubWechatStaffClient(env("local", "prod"))).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

}
