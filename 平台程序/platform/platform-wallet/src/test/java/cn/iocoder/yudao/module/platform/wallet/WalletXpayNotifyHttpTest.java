package cn.iocoder.yudao.module.platform.wallet;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.WxMaXPayService;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.config.YudaoWebSecurityConfigurerAdapter;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.config.YudaoTenantAutoConfiguration;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.Filter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实MVC、安全配置及租户注解扫描；渠道SDK不外呼，inbox使用真实PostgreSQL事务。 */
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletXpayNotifyHttpTest extends PlatformPgIntegrationTest {
    private static final String PATH = "/app-api/platform/wallet/payments/xpay/push";
    @Autowired DataSource dataSource;
    private AnnotationConfigWebApplicationContext web;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private VirtualPaymentAdapter adapter;
    private WechatXPayProperties properties;
    private WalletRecoveryJob recovery;
    private final AtomicBoolean failCommit = new AtomicBoolean();
    private long tenant;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tenant = IdWorker.getId();
        properties = new WechatXPayProperties();
        properties.setEnabled(true);
        properties.setAppid("notify-test-app");
        properties.setOfferId("notify-test-offer");
        properties.setPushToken("unit-test-push-token");
        properties.setNotificationTenantId(tenant);
        adapter = spy(new VirtualPaymentAdapter(properties, mock(WxMaService.class), mock(WxMaXPayService.class)));
        var channels = new StaticListableBeanFactory();
        channels.addBean("channel", adapter);
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                if (failCommit.get()) throw new TransactionSystemException("injected commit failure");
                super.doCommit(status);
            }
        };
        manager.setRollbackOnCommitFailure(true);
        recovery = new WalletRecoveryJob(dataSource, manager, mock(RechargePaymentService.class),
                mock(RefundPaymentService.class), properties, channels.getBeanProvider(PaymentPort.class));
        open("false", "true");
    }

    private void open(String bridge, String enabled) {
        if (web != null) web.close();
        web = new AnnotationConfigWebApplicationContext();
        web.setServletContext(new MockServletContext());
        if (bridge != null) TestPropertyValues.of("platform.legacy-design-bridge.enabled=" + bridge).applyTo(web);
        if (enabled != null) TestPropertyValues.of("platform.wallet.payment.enabled=" + enabled).applyTo(web);
        web.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("walletRecoveryJob", recovery);
            factory.registerSingleton("wechatXPayProperties", properties);
        });
        web.register(WebTestConfiguration.class, NeighborController.class);
        web.register(WalletXpayNotifyController.class, WalletXpayNotifyConfiguration.class);
        web.refresh();
        var tenantConfig = new YudaoTenantAutoConfiguration();
        ReflectionTestUtils.setField(tenantConfig, "applicationContext", web);
        var tenantFilter = tenantConfig.tenantSecurityWebFilter(new TenantProperties(), web.getBean(WebProperties.class),
                web.getBean(GlobalExceptionHandler.class), mock(TenantFrameworkService.class)).getFilter();
        var filters = new ArrayList<Filter>();
        web.getBeansOfType(FilterRegistrationBean.class).values().forEach(registration -> {
            assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
            filters.add(registration.getFilter());
        });
        boolean ingressActive = !filters.isEmpty();
        filters.add((request, response, chain) -> {
            var http = (jakarta.servlet.http.HttpServletRequest) request;
            if (ingressActive && PATH.equals(http.getRequestURI())
                    && ("GET".equals(http.getMethod()) || "POST".equals(http.getMethod()))) {
                // 位于真实共享缓存/访问日志之前；原文仅在本地attribute中供入口读取。
                assertThat(http.getQueryString()).isNull();
                assertThat(http.getParameterMap()).isEmpty();
                assertThat(http.getInputStream().readAllBytes()).isEmpty();
                assertThat(http.getReader().readLine()).isNull();
            }
            chain.doFilter(request, response);
        });
        filters.add(new CacheRequestBodyFilter());
        filters.add(new TenantContextWebFilter());
        filters.add(web.getBean("springSecurityFilterChain", Filter.class));
        filters.add(tenantFilter);
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(filters.toArray(Filter[]::new)).build();
    }

    @AfterEach
    void close() { if (web != null) web.close(); }

    @Test
    void anonymousUnknownJsonCommitsBeforeAckAndDoesNotQueryChannel() throws Exception {
        byte[] body = body("missing-" + tenant);
        var response = mvc.perform(post(PATH + "?" + query()).contentType("application/json").content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"ErrCode\":0,\"ErrMsg\":\"success\"}");
        var row = jdbc.queryForMap("SELECT tenant_id, payload, status FROM pf_payment_notification_inbox WHERE tenant_id = ?", tenant);
        assertThat(row.get("payload")).isEqualTo(body);
        assertThat(row.get("status")).isEqualTo("PENDING");
        verify(adapter, never()).queryOrder(any());
        verify(adapter, never()).queryRefund(any());
        verify(adapter, never()).parseNotification(any(), any());
    }

    @Test
    void xmlAckAndReplayKeepOneInboxAndIgnoreHttpTenant() throws Exception {
        byte[] body = ("<xml><Event>xpay_goods_deliver_notify</Event><OutTradeNo>unknown-" + tenant + "</OutTradeNo></xml>")
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            var response = mvc.perform(post(PATH + "?" + query()).header("tenant-id", tenant + 1)
                    .contentType("application/xml").content(body)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("<xml><ErrCode>0</ErrCode><ErrMsg><![CDATA[success]]></ErrMsg></xml>");
        }
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test
    void getValidationEchoesExactTextAndRejectsWrongSignature() throws Exception {
        var good = mvc.perform(get(java.net.URI.create(PATH + "?" + query() + "&echostr=a%2Bb%3Cc"))).andReturn().getResponse();
        assertThat(good.getStatus()).isEqualTo(200);
        assertThat(good.getContentType()).startsWith("text/plain");
        assertThat(good.getContentAsString()).isEqualTo("a+b<c");
        var bad = mvc.perform(get(PATH + "?signature=wrong&timestamp=1&nonce=2&echostr=bad")).andReturn().getResponse();
        assertThat(bad.getStatus()).isEqualTo(403);
        assertThat(bad.getContentAsString()).isEqualTo("verify failed");
        assertThat(inboxCount()).isZero();
    }

    @Test
    void invalidEmptyEncryptedOrBodyOnlySignatureCannotAck() throws Exception {
        for (String query : new String[]{"signature=wrong&timestamp=1&nonce=2", query() + "&encrypt_type=aes", query() + "&msg_signature=x", ""}) {
            var response = mvc.perform(post(PATH + "?" + query).contentType("application/json").content(body("invalid")))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isBetween(400, 599);
            assertThat(response.getContentAsString()).doesNotContain("\"ErrCode\":0");
        }
        var empty = mvc.perform(post(PATH + "?" + query()).contentType("application/json")).andReturn().getResponse();
        assertThat(empty.getStatus()).isBetween(400, 599);
        var form = mvc.perform(post(PATH).contentType("application/x-www-form-urlencoded").content(query()))
                .andReturn().getResponse();
        assertThat(form.getStatus()).isBetween(400, 599);
        assertThat(inboxCount()).isZero();
    }

    @Test
    void commitFailureReturnsFailureAndLeavesNoInbox() throws Exception {
        failCommit.set(true);
        var response = mvc.perform(post(PATH + "?" + query()).contentType("application/json").content(body("commit")))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isBetween(500, 599);
        assertThat(response.getContentAsString()).isEqualTo("{\"ErrCode\":1,\"ErrMsg\":\"process failed\"}");
        assertThat(inboxCount()).isZero();
    }

    @Test
    void streamingBodyStopsAtLimitPlusOneBeforeSharedCache() throws Exception {
        AtomicInteger read = new AtomicInteger();
        var response = mvc.perform(context -> {
            var request = new MockHttpServletRequest(context, "POST", PATH) {
                @Override public int getContentLength() { return -1; }
                @Override public long getContentLengthLong() { return -1; }
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        @Override public int read() { read.incrementAndGet(); return 'x'; }
                        @Override public boolean isFinished() { return false; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
            };
            request.setServletPath(PATH);
            request.setContentType("application/json");
            request.setQueryString(query());
            return request;
        }).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(read.get()).isLessThanOrEqualTo(65537);
        assertThat(inboxCount()).isZero();
    }

    @Test
    void knownOversizedBodyOrReadFailureCannotAck() throws Exception {
        var tooLarge = mvc.perform(post(PATH + "?" + query()).contentType("application/xml").content(new byte[65537]))
                .andReturn().getResponse();
        assertThat(tooLarge.getStatus()).isEqualTo(413);
        var failedRead = mvc.perform(context -> {
            var request = new MockHttpServletRequest(context, "POST", PATH) {
                @Override public ServletInputStream getInputStream() throws IllegalStateException {
                    return new ServletInputStream() {
                        @Override public int read() throws java.io.IOException { throw new java.io.IOException("sensitive-payload-must-not-leak"); }
                        @Override public boolean isFinished() { return false; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
            };
            request.setServletPath(PATH);
            request.setQueryString(query());
            return request;
        }).andReturn().getResponse();
        assertThat(failedRead.getStatus()).isEqualTo(400);
        assertThat(failedRead.getContentAsString()).doesNotContain("sensitive-payload");
        assertThat(inboxCount()).isZero();
    }

    @Test
    void exactBodyLimitIsPreservedAndAmbiguousQueryIsRejected() throws Exception {
        byte[] prefix = body("limit-" + tenant);
        byte[] body = java.util.Arrays.copyOf(prefix, 65536);
        java.util.Arrays.fill(body, prefix.length, body.length, (byte) ' ');
        assertThat(mvc.perform(post(PATH + "?" + query()).contentType("application/json").content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT payload FROM pf_payment_notification_inbox WHERE tenant_id = ?", byte[].class, tenant))
                .isEqualTo(body);
        for (String suffix : new String[]{"&signature=duplicate", "&unused=" + "x".repeat(4096)}) {
            assertThat(mvc.perform(post(PATH + "?" + query() + suffix).contentType("application/json").content(body("rejected")))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test
    void neighboringWalletAndOtherMethodsRemainProtected() throws Exception {
        for (String path : new String[]{"/admin-api/platform/wallet/account", PATH + "/extra"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(mvc.perform(put(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
        var tenantRequired = mvc.perform(get("/app-api/platform/wallet/tenant-required")).andReturn().getResponse();
        assertThat(tenantRequired.getContentAsString()).contains("租户");
        assertThat(tenantRequired.getContentAsString()).doesNotContain("neighbor reached");
    }

    @Test
    void defaultLegacyAndDisabledPaymentDoNotPublishNotifyRoute() throws Exception {
        for (String[] flags : new String[][]{{null, "true"}, {"true", "true"}, {"false", null}, {"false", "false"}}) {
            open(flags[0], flags[1]);
            assertThat(mvc.perform(get(PATH + "?" + query() + "&echostr=echo")).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(web.getBeansOfType(FilterRegistrationBean.class)).isEmpty();
        }
    }

    private long inboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM pf_payment_notification_inbox WHERE tenant_id = ?", Long.class, tenant);
    }

    private static byte[] body(String order) {
        return ("{\"Event\":\"xpay_goods_deliver_notify\",\"OutTradeNo\":\"" + order + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private String query() {
        String signature = cn.hutool.crypto.digest.DigestUtil.sha1Hex(java.util.stream.Stream.of(properties.getPushToken(), "1", "2")
                .sorted().collect(java.util.stream.Collectors.joining()));
        return "signature=" + signature + "&timestamp=1&nonce=2";
    }

    @RestController
    static class NeighborController {
        @GetMapping("/admin-api/platform/wallet/account") String account() { return "protected"; }
        @PermitAll @GetMapping("/app-api/platform/wallet/tenant-required") String tenantRequired() { return "neighbor reached"; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(YudaoWebSecurityConfigurerAdapter.class)
    static class WebTestConfiguration {
        @Bean WebProperties webProperties() { return new WebProperties(); }
        @Bean WebFrameworkUtils webFrameworkUtils(WebProperties properties) { return new WebFrameworkUtils(properties); }
        @Bean SecurityProperties securityProperties() { return new SecurityProperties(); }
        @Bean java.util.List<cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer> authorizeRequestsCustomizers() {
            return java.util.List.of();
        }
        @Bean GlobalExceptionHandler exceptionHandler() { return mock(GlobalExceptionHandler.class); }
        @Bean TokenAuthenticationFilter tokenFilter(SecurityProperties properties, GlobalExceptionHandler handler) {
            return new TokenAuthenticationFilter(properties, handler, mock(OAuth2TokenCommonApi.class));
        }
        @Bean AuthenticationEntryPoint entryPoint() { return (request, response, failure) -> response.sendError(401); }
        @Bean AccessDeniedHandler deniedHandler() { return (request, response, failure) -> response.sendError(403); }
    }
}
