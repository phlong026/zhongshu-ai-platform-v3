package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.design.capability.security.CapabilityRequestAuthenticatorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilitySecurityFilterChainTest {
    private AnnotationConfigWebApplicationContext context(boolean enabled, Class<?>... implementations) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertyValues.of("zhongshu.design.capability.enabled=" + enabled,
                "zhongshu.design.capability.keys.key-1.caller-app-id=platform-b",
                "zhongshu.design.capability.keys.key-1.secret-base64=" + Base64.getEncoder().encodeToString(KEY),
                "zhongshu.design.capability.keys.key-1.operations[0]=LIST_PROJECTS")
                .applyTo(context);
        context.register(CapabilitySecurityConfiguration.class, WebSecurity.class);
        if (implementations.length > 0) {
            context.register(implementations);
        }
        return context;
    }

    @Test
    void actualSecurityChainIsolatesTenantAndUserTokenThenClearsContexts() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class);
            assertTrue(ingress.getOrder() < WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
            assertTrue(ingress.getOrder() < WebFilterOrderEnum.TENANT_CONTEXT_FILTER);
            Filter spring = context.getBean("springSecurityFilterChain", Filter.class);
            var request = freshRequest();
            var response = new MockHttpServletResponse();
            invoke(List.of(ingress.getFilter(), new TenantContextWebFilter(), spring), request, response, (req, res) -> {
                var principal = CapabilityAuthenticationFilter.requirePrincipal((jakarta.servlet.http.HttpServletRequest) req);
                assertEquals(888L, principal.tenantId());
                assertEquals(888L, TenantContextHolder.getTenantId());
                assertFalse(TenantContextHolder.isIgnore());
                assertNull(SecurityFrameworkUtils.getLoginUser());
                assertEquals(Operation.LIST_PROJECTS, CapabilityAuthenticationFilter.requireRequest(
                        (jakarta.servlet.http.HttpServletRequest) req).operation());
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
            });
            assertEquals(204, response.getStatus());
            verify(context.getBean(CapabilityProviderReadiness.class)).verifyReady();
            assertEquals(0, context.getBean(UserTokenFilter.class).calls.get());
            assertNull(TenantContextHolder.getTenantId());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertThrows(SecurityException.class, () -> CapabilityAuthenticationFilter.requirePrincipal(request));
        }
    }

    @Test
    void runtimeRoutesKeepTheirOriginalChainWithCapabilityEnabledOrDisabled() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            try (var context = context(enabled, Dependencies.class)) {
                context.refresh();
                var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
                for (String path : List.of("/internal-api/design/v1/ai-jobs/1/call-traces",
                        "/internal-api/design/v1/platform-other", "/internal-api/design/v1/platforms/action")) {
                    var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
                    request.addHeader("tenant-id", "77");
                    var response = new MockHttpServletResponse();
                    invoke(List.of(ingress, new TenantContextWebFilter(), context.getBean("springSecurityFilterChain", Filter.class)),
                            request, response, (req, res) -> {
                                assertEquals(77L, TenantContextHolder.getTenantId());
                                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
                            });
                    assertEquals(204, response.getStatus(), "Existing route " + path + " enabled=" + enabled);
                }
                assertEquals(3, context.getBean(UserTokenFilter.class).calls.get());
            }
        }
    }

    @Test
    void unrelatedRoutesRetainExistingTenantAndUserTokenFilters() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/admin-api/example");
            request.addHeader("tenant-id", "77");
            var response = new MockHttpServletResponse();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            invoke(List.of(ingress, new TenantContextWebFilter(), context.getBean("springSecurityFilterChain", Filter.class)),
                    request, response, (req, res) -> assertEquals(77L, TenantContextHolder.getTenantId()));
            assertEquals(1, context.getBean(UserTokenFilter.class).calls.get());
            assertNull(TenantContextHolder.getTenantId());
        }
    }

    @Test
    void forbiddenHeadersNeverReachTenantExtractionOrUserChain() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            Filter spring = context.getBean("springSecurityFilterChain", Filter.class);
            for (String header : new String[]{"tenant-id", "Authorization", "X-Post-Id"}) {
                var request = freshRequest();
                request.addHeader(header, "9999");
                var response = new MockHttpServletResponse();
                invoke(List.of(ingress, new TenantContextWebFilter(), spring), request, response,
                        (req, res) -> fail("Untrusted identity reached provider"));
                assertEquals(400, response.getStatus());
                assertNull(TenantContextHolder.getTenantId());
                assertNull(SecurityContextHolder.getContext().getAuthentication());
            }
            assertEquals(0, context.getBean(UserTokenFilter.class).calls.get());
        }
    }

    @Test
    void actualChainRejectsHeadQueryWrongContentTypeOversizeAndUncanonicalPaths() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            Filter spring = context.getBean("springSecurityFilterChain", Filter.class);
            var head = freshRequest();
            head.setMethod("HEAD");
            var query = freshRequest();
            query.setQueryString("tenant-id=999");
            var type = freshRequest();
            type.setContentType("text/plain");
            var huge = freshRequest();
            huge.setContent(new byte[262145]);
            var trailingSlash = freshRequest();
            trailingSlash.setRequestURI(Operation.LIST_PROJECTS.path() + "/");
            var encodedPath = freshRequest();
            encodedPath.setRequestURI("/internal-api/design/v1/platform/%6cist-projects");
            var cases = List.of(head, query, type, huge, trailingSlash, encodedPath);
            int[] statuses = {400, 400, 415, 413, 400, 400};
            for (int i = 0; i < cases.size(); i++) {
                var response = new MockHttpServletResponse();
                invoke(List.of(ingress, new TenantContextWebFilter(), spring), cases.get(i), response,
                        (req, res) -> fail("Invalid protocol reached provider"));
                assertEquals(statuses[i], response.getStatus());
            }
            verifyNoInteractions(context.getBean(StringRedisTemplate.class).opsForValue());
        }
    }

    @Test
    void actualChainBoundsUndeclaredBodyBeforeSharedBodyCache() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            AtomicInteger consumed = new AtomicInteger();
            var request = new jakarta.servlet.http.HttpServletRequestWrapper(freshRequest()) {
                @Override
                public long getContentLengthLong() { return -1; }
                @Override
                public jakarta.servlet.ServletInputStream getInputStream() {
                    return new jakarta.servlet.ServletInputStream() {
                        @Override
                        public int read() { consumed.incrementAndGet(); return ' '; }
                        @Override
                        public boolean isFinished() { return false; }
                        @Override
                        public boolean isReady() { return true; }
                        @Override
                        public void setReadListener(jakarta.servlet.ReadListener listener) {
                            throw new IllegalStateException("Synchronous fixture");
                        }
                    };
                }
            };
            var response = new MockHttpServletResponse();
            invoke(List.of(ingress, new TenantContextWebFilter(), context.getBean("springSecurityFilterChain", Filter.class)),
                    request, response, (req, res) -> fail("Unbounded body reached downstream"));
            assertEquals(413, response.getStatus());
            assertEquals(262145, consumed.get());
        }
    }

    @Test
    void actualChainFailsClosedForRedisReplayNullAndFailure() throws Exception {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            var operations = context.getBean(StringRedisTemplate.class).opsForValue();
            when(operations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false).thenReturn((Boolean) null)
                    .thenThrow(new IllegalStateException("Redis unavailable"));
            for (int expected : new int[]{409, 503, 503}) {
                var response = new MockHttpServletResponse();
                invoke(List.of(ingress, new TenantContextWebFilter(), context.getBean("springSecurityFilterChain", Filter.class)),
                        freshRequest(), response, (req, res) -> fail("Redis failure reached provider"));
                assertEquals(expected, response.getStatus());
                assertNull(TenantContextHolder.getTenantId());
                assertNull(SecurityContextHolder.getContext().getAuthentication());
            }
        }
    }

    @Test
    void providerExceptionStillClearsMappedIdentity() {
        try (var context = context(true, Dependencies.class)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            Filter spring = context.getBean("springSecurityFilterChain", Filter.class);
            assertThrows(ServletException.class, () -> invoke(List.of(ingress, new TenantContextWebFilter(), spring),
                    freshRequest(), new MockHttpServletResponse(), (req, res) -> { throw new ServletException("provider failed"); }));
            assertNull(TenantContextHolder.getTenantId());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test
    void disabledProviderRejectsBeforeAnyDownstreamFilterAndNeedsNoResolver() throws Exception {
        try (var context = context(false)) {
            context.refresh();
            var ingress = context.getBean("capabilityIngressRegistration", FilterRegistrationBean.class).getFilter();
            var response = new MockHttpServletResponse();
            ingress.doFilter(freshRequest(), response, (req, res) -> fail("Disabled provider reached downstream"));
            assertEquals(404, response.getStatus());
        }
    }

    @Test
    void enablingWithoutResolverOrReadinessFailsStartup() {
        try (var context = context(true, MissingResolver.class)) {
            var failure = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            assertTrue(failure.getMessage().contains("CallerOrganizationResolver"));
        }
        try (var context = context(true, MissingReadiness.class)) {
            var failure = assertThrows(org.springframework.beans.BeansException.class, context::refresh);
            assertTrue(failure.getMessage().contains("CapabilityProviderReadiness"));
        }
        try (var context = context(true, UnreadyDependencies.class)) {
            assertThrows(org.springframework.beans.BeansException.class, context::refresh);
        }
    }

    private org.springframework.mock.web.MockHttpServletRequest freshRequest() {
        byte[] body = envelope("nonce-000000000001");
        var request = request(body);
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
        request.removeHeader(cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature.TIMESTAMP_HEADER);
        request.addHeader(cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature.TIMESTAMP_HEADER, timestamp);
        request.removeHeader(cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature.SIGNATURE_HEADER);
        request.addHeader(cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature.SIGNATURE_HEADER,
                cn.iocoder.yudao.module.franchise.api.capability.security.CapabilitySignature.sign(KEY, timestamp, "POST", request.getRequestURI(), body));
        return request;
    }

    private static void invoke(List<Filter> filters, ServletRequest request, ServletResponse response, FilterChain terminal)
            throws ServletException, IOException {
        if (filters.isEmpty()) {
            terminal.doFilter(request, response);
        } else {
            filters.get(0).doFilter(request, response,
                    (req, res) -> invoke(filters.subList(1, filters.size()), req, res, terminal));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurity {
        @Bean
        UserTokenFilter userTokenFilter() { return new UserTokenFilter(); }

        @Bean
        @Order(100)
        SecurityFilterChain userChain(HttpSecurity http, UserTokenFilter filter) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(c -> c.anyRequest().permitAll())
                    .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
        }
    }

    static class UserTokenFilter extends cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter {
        final AtomicInteger calls = new AtomicInteger();

        UserTokenFilter() {
            super(new cn.iocoder.yudao.framework.security.config.SecurityProperties(),
                    mock(cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler.class),
                    mock(cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi.class));
        }

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            calls.incrementAndGet();
            super.doFilterInternal(request, response, chain);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        CallerOrganizationResolver resolver() { return (caller, org) -> new ServicePrincipal(caller, org, 888, 999); }
        @Bean
        CapabilityProviderReadiness readiness() { return mock(CapabilityProviderReadiness.class); }
        @Bean
        @SuppressWarnings("unchecked")
        StringRedisTemplate redis() {
            var redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
            return redis;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingResolver {
        @Bean
        StringRedisTemplate redis() { return new Dependencies().redis(); }
        @Bean
        CapabilityProviderReadiness readiness() { return new Dependencies().readiness(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingReadiness {
        @Bean
        StringRedisTemplate redis() { return new Dependencies().redis(); }
        @Bean
        CallerOrganizationResolver resolver() { return new Dependencies().resolver(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnreadyDependencies extends Dependencies {
        @Override
        @Bean
        CapabilityProviderReadiness readiness() {
            return () -> { throw new IllegalStateException("Real billing or durable receipt unavailable"); };
        }
    }
}
