package cn.iocoder.yudao.module.franchise.org.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.config.YudaoWebSecurityConfigurerAdapter;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.point.CompanyPointAccess;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.authz.RequestCurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.iocoder.yudao.module.platform.authz.CurrentPost.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 生产 SecurityFilterChain + TokenAuthenticationFilter + RequestCurrentPost + /admin-api 前缀。 */
class CompanyPointHttpSecurityTest {

    private static final String BASE = "/admin-api/franchise/company-points";
    private static final PostSnapshot SALES = snapshot(2, 12, "sales");
    private static final PostSnapshot BOSS = snapshot(1, 11, "boss");
    private static final PostSnapshot OWNER = snapshot(3, 13, "owner");

    private final OAuth2TokenCommonApi tokens = mock(OAuth2TokenCommonApi.class);
    private final StaffPostService posts = mock(StaffPostService.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final CompanyPointAccess access = mock(CompanyPointAccess.class);
    private final ApiErrorLogCommonApi apiErrors = mock(ApiErrorLogCommonApi.class);
    private AnnotationConfigWebApplicationContext web;
    private MockMvc mvc;

    @BeforeEach
    void open() {
        when(tokens.checkAccessToken(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            if ("expired-token".equals(value)) {
                throw new ServiceException(401, "token expired");
            }
            return token(value);
        });
        when(posts.findActivePost(12, 2)).thenReturn(Optional.of(SALES));
        when(posts.findActivePost(11, 1)).thenReturn(Optional.of(BOSS));
        when(posts.findActivePost(13, 3)).thenReturn(Optional.of(OWNER));
        when(directory.roleHasPermission("boss", "platform:org:manage")).thenReturn(true);
        when(directory.roleHasPermission("owner", "platform:org:manage")).thenReturn(true);
        when(access.summary(SALES)).thenReturn(new OrgPointPort.PoolSummary(
                101, 50, 0, "ACTIVE", "2026-11", 3));

        web = new AnnotationConfigWebApplicationContext();
        web.setServletContext(new MockServletContext());
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.application.name", "company-point-http-security-test",
                "yudao.web.admin-ui.url", "http://localhost")));
        web.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("oauth2TokenApi", tokens);
            factory.registerSingleton("staffPostService", posts);
            factory.registerSingleton("systemDirectory", directory);
            factory.registerSingleton("companyPointAccess", access);
            factory.registerSingleton("apiErrorLogCommonApi", apiErrors);
        });
        web.register(RestTemplateAutoConfiguration.class, WebMvcAutoConfiguration.class,
                YudaoWebAutoConfiguration.class,
                WebSecurity.class, CompanyPointController.class, RequestCurrentPost.class,
                PostPermissionEvaluator.class);
        web.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .addFilters(web.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        if (web != null) web.close();
    }

    @Test
    void anonymousIsRejectedByProductionSecurityChainBeforeController() throws Exception {
        var response = mvc.perform(get(BASE + "/summary").header(HEADER, SALES.postId()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(access);
    }

    @Test
    void unknownAndExpiredBearerTokensAreRejectedAsUnauthenticated() throws Exception {
        for (String token : List.of("unknown-token", "expired-token")) {
            var response = mvc.perform(get(BASE + "/summary")
                            .header("Authorization", "Bearer " + token).header(HEADER, SALES.postId()))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(401);
        }
        verify(tokens).checkAccessToken("unknown-token");
        verify(tokens).checkAccessToken("expired-token");
        verifyNoInteractions(access);
    }

    @Test
    void employeeTokenAndCurrentPostCanReadButCannotUseManagementRoutes() throws Exception {
        assertThat(mvc.perform(get(BASE + "/summary")
                        .header("Authorization", "Bearer sales-token").header(HEADER, SALES.postId()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(put(BASE + "/member-permissions/22")
                        .header("Authorization", "Bearer sales-token").header(HEADER, SALES.postId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedSubjects\":[\"copy\"]}"))
                .andReturn().getResponse().getContentAsString()).contains("\"code\":403");
        verify(access).summary(SALES);
        verify(access, never()).setMemberPermission(any(), anyLong(), any());
    }

    @Test
    void bossAndOwnerTokensReachEveryManagementRouteThroughAdminApi() throws Exception {
        for (var manager : List.of(BOSS, OWNER)) {
            String token = manager.roleCode() + "-token";
            assertOk(post(BASE + "/activate"), token, manager);
            assertOk(get(BASE + "/ledger"), token, manager);
            assertOk(get(BASE + "/operations"), token, manager);
            assertOk(get(BASE + "/member-permissions"), token, manager);
            assertOk(put(BASE + "/member-permissions/12").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"allowedSubjects\":[\"copy\"]}"), token, manager);
            assertOk(delete(BASE + "/member-permissions/12"), token, manager);
        }
        verify(access).activate(BOSS);
        verify(access).activate(OWNER);
        verify(access).setMemberPermission(BOSS, 12, List.of("copy"));
        verify(access).setMemberPermission(OWNER, 12, List.of("copy"));
        verify(access).clearMemberPermission(BOSS, 12);
        verify(access).clearMemberPermission(OWNER, 12);
    }

    @Test
    void forgedOrRevokedPostHeaderIsRejectedByRequestCurrentPost() throws Exception {
        var forged = mvc.perform(put(BASE + "/member-permissions/12")
                        .header("Authorization", "Bearer sales-token").header(HEADER, BOSS.postId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedSubjects\":[\"copy\"]}"))
                .andReturn().getResponse();
        var revoked = mvc.perform(put(BASE + "/member-permissions/12")
                        .header("Authorization", "Bearer boss-token").header(HEADER, 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedSubjects\":[\"copy\"]}"))
                .andReturn().getResponse();

        assertThat(forged.getContentAsString()).contains("1103000002");
        assertThat(revoked.getContentAsString()).contains("1103000002");
        verify(access, never()).setMemberPermission(any(), anyLong(), any());
    }

    private void assertOk(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token, PostSnapshot post) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + token)
                        .header(HEADER, post.postId())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"code\":0");
    }

    private static OAuth2AccessTokenCheckRespDTO token(String value) {
        Long userId = switch (value) {
            case "sales-token" -> SALES.userId();
            case "boss-token" -> BOSS.userId();
            case "owner-token" -> OWNER.userId();
            default -> null;
        };
        if (userId == null) return null;
        return new OAuth2AccessTokenCheckRespDTO().setUserId(userId)
                .setUserType(UserTypeEnum.ADMIN.getValue()).setTenantId(1L)
                .setScopes(List.of()).setExpiresTime(LocalDateTime.now().plusMinutes(5));
    }

    private static PostSnapshot snapshot(long postId, long userId, String role) {
        return new PostSnapshot(postId, userId, 101, "store", role, null, false, Set.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import(YudaoWebSecurityConfigurerAdapter.class)
    static class WebSecurity {
        @Bean SecurityProperties securityProperties() { return new SecurityProperties(); }
        @Bean List<AuthorizeRequestsCustomizer> authorizeRequestsCustomizers() { return List.of(); }
        @Bean TokenAuthenticationFilter tokenFilter(SecurityProperties properties,
                                                     GlobalExceptionHandler handler,
                                                     OAuth2TokenCommonApi tokenApi) {
            return new TokenAuthenticationFilter(properties, handler, tokenApi);
        }
        @Bean AuthenticationEntryPoint authenticationEntryPoint() {
            return (request, response, failure) -> response.sendError(401);
        }
        @Bean AccessDeniedHandler accessDeniedHandler() {
            return (request, response, failure) -> response.sendError(403);
        }
    }
}
