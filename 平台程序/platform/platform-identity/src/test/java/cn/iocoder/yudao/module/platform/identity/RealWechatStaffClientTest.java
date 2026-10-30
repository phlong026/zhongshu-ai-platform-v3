package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.util.FakeHttpServer;
import cn.iocoder.yudao.module.platform.identity.wechat.RealWechatStaffClient;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实微信客户端：全部请求打到本地 FakeHttpServer，不触达真实微信。
 */
class RealWechatStaffClientTest {

    private static final String APP = "wx-staff";
    private static final String SECRET = "super-secret-value";
    private static final String JSON = "application/json";

    private FakeHttpServer server;
    private RealWechatStaffClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = FakeHttpServer.start();
        PlatformWechatProperties props = new PlatformWechatProperties();
        PlatformWechatProperties.App app = new PlatformWechatProperties.App();
        app.setAppid(APP);
        app.setSecret(SECRET);
        props.setApps(List.of(app));
        props.setBaseUrl(server.baseUrl());
        client = new RealWechatStaffClient(props);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void exchangesLoginCodeForOpenidWithoutLeakingSessionKey() {
        server.stubGet("/sns/jscode2session", 200, JSON, "{\"openid\":\"o-1\",\"session_key\":\"SESSION-KEY\"}");

        assertThat(client.openid(APP, "code-1")).isEqualTo("o-1");
        FakeHttpServer.RecordedRequest request = server.lastRequest();
        assertThat(request.query()).contains("appid=" + APP).contains("js_code=code-1")
                .contains("grant_type=authorization_code");
    }

    @Test
    void invalidLoginCodeIsReportedWithoutSecretsInTheMessage() {
        server.stubGet("/sns/jscode2session", 200, JSON, "{\"errcode\":40029,\"errmsg\":\"invalid code, secret=" + SECRET + "\"}");

        assertError(() -> client.openid(APP, "code-1"), WECHAT_CODE_INVALID);
    }

    @Test
    void wechatFailuresAreServiceUnavailable() {
        server.stubGet("/sns/jscode2session", 500, JSON, "{}");
        assertError(() -> client.openid(APP, "code-1"), WECHAT_SERVICE_UNAVAILABLE);

        server.stubGet("/sns/jscode2session", 200, JSON, "{\"errcode\":-1,\"errmsg\":\"busy\"}");
        assertError(() -> client.openid(APP, "code-1"), WECHAT_SERVICE_UNAVAILABLE);

        server.stubGet("/sns/jscode2session", 200, JSON, "not json");
        assertError(() -> client.openid(APP, "code-1"), WECHAT_SERVICE_UNAVAILABLE);
    }

    @Test
    void unconfiguredAppIsRejectedWithoutAnyRequest() {
        assertError(() -> client.openid("wx-unknown", "code-1"), WECHAT_APP_NOT_ALLOWED);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void exchangesPhoneCodeForPhoneNumberAndCachesTheAccessToken() {
        server.stubPost("/cgi-bin/stable_token", 200, JSON, "{\"access_token\":\"AT-1\",\"expires_in\":7200}");
        server.stubPost("/wxa/business/getuserphonenumber", 200, JSON,
                "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"+86 13900000001\",\"purePhoneNumber\":\"13900000001\",\"countryCode\":\"86\"}}");

        assertThat(client.phoneNumber(APP, "p-1")).isEqualTo("13900000001");
        assertThat(client.phoneNumber(APP, "p-2")).isEqualTo("13900000001");

        long tokenCalls = server.requests().stream().filter(r -> r.path().equals("/cgi-bin/stable_token")).count();
        assertThat(tokenCalls).isEqualTo(1);
        FakeHttpServer.RecordedRequest phone = server.lastRequest();
        assertThat(phone.query()).contains("access_token=AT-1");
        assertThat(phone.body()).contains("\"code\":\"p-2\"");
    }

    @Test
    void expiredAccessTokenIsDroppedSoTheNextCallFetchesANewOne() {
        server.stubPost("/cgi-bin/stable_token", 200, JSON, "{\"access_token\":\"AT-1\",\"expires_in\":7200}");
        server.stubPost("/wxa/business/getuserphonenumber", 200, JSON, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        assertError(() -> client.phoneNumber(APP, "p-1"), WECHAT_SERVICE_UNAVAILABLE);

        server.stubPost("/wxa/business/getuserphonenumber", 200, JSON,
                "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13900000002\",\"countryCode\":\"86\"}}");
        assertThat(client.phoneNumber(APP, "p-2")).isEqualTo("13900000002");

        long tokenCalls = server.requests().stream().filter(r -> r.path().equals("/cgi-bin/stable_token")).count();
        assertThat(tokenCalls).isEqualTo(2);
    }

    @Test
    void invalidPhoneCodeIsReported() {
        server.stubPost("/cgi-bin/stable_token", 200, JSON, "{\"access_token\":\"AT-1\",\"expires_in\":7200}");
        server.stubPost("/wxa/business/getuserphonenumber", 200, JSON, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

        assertError(() -> client.phoneNumber(APP, "p-1"), WECHAT_CODE_INVALID);
    }

    @Test
    void foreignPhoneNumbersAreRejected() {
        server.stubPost("/cgi-bin/stable_token", 200, JSON, "{\"access_token\":\"AT-1\",\"expires_in\":7200}");
        server.stubPost("/wxa/business/getuserphonenumber", 200, JSON,
                "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"5551234\",\"countryCode\":\"1\"}}");

        assertError(() -> client.phoneNumber(APP, "p-1"), STAFF_NOT_ENROLLED);
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .satisfies(e -> {
                    ServiceException se = (ServiceException) e;
                    assertThat(se.getCode()).isEqualTo(expected.getCode());
                    assertThat(se.getMessage()).doesNotContain(SECRET).doesNotContain("SESSION-KEY")
                            .doesNotContain("code-1").doesNotContain("AT-1");
                    assertThat(se.getCause()).isNull();
                });
    }

}
